"""
Card catalog + recognition server.  Only needs: pip install -r requirements.txt

C1 recognition hardening:
- ORB + ratio test + RANSAC homography
- plausible projected card-shape/size check
- runner-up margin check to reject ambiguous matches
- optional DEBUG_SAVE_FRAMES=1 capture of the exact JPEG received by /identify

C3 persistent catalog (no-redeploy publishing):
- CATALOG_SOURCE=folder (default, unchanged disk-folder behaviour) or
  CATALOG_SOURCE=supabase (reads the `cards` table + Storage bucket; see
  publish_card.py and the SQL in CardVault_Change_Spec.md §C3).
- In supabase mode the catalog auto-refreshes when the DB's max(version)
  changes, checked at most once every CATALOG_RECHECK_SECONDS.
- POST /admin/reload with header X-Admin-Key forces an immediate refresh.

Folder mode: create catalog/<card_id>/ containing
    card.jpg / card.jpeg / card.png / card.webp
    render.png
    meta.json
then restart the server (or GET /reload, local-dev only, unauthenticated).
"""
import base64
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import cv2
import numpy as np

CATALOG = Path(__file__).parent / "catalog"
REF_W = 480
MIN_GOOD, MIN_INLIERS, RATIO = 8, 10, 0.85
RUNNER_UP_MARGIN = 1.30
MIN_CARD_AREA_RATIO = 0.05
MAX_CARD_AREA_RATIO = 0.95
DEBUG_SAVE_FRAMES = os.getenv("DEBUG_SAVE_FRAMES", "0") == "1"
DEBUG_FRAME_DIR = Path(__file__).parent / "debug_frames"
ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")

# --- C3: persistent catalog -------------------------------------------------
# CATALOG_SOURCE, SUPABASE_*, and ADMIN_KEY are read from environment
# variables only. Never hard-code a key here, never commit one, never log one.
CATALOG_SOURCE = os.getenv("CATALOG_SOURCE", "folder")       # folder | supabase
SUPABASE_URL = os.getenv("SUPABASE_URL", "").rstrip("/")
SUPABASE_SERVICE_KEY = os.getenv("SUPABASE_SERVICE_KEY", "")
SUPABASE_BUCKET = os.getenv("SUPABASE_BUCKET", "cards")
ADMIN_KEY = os.getenv("ADMIN_KEY", "")
CATALOG_RECHECK_SECONDS = 60
_last_known_version = -1
_last_version_check_ts = 0.0

orb = cv2.ORB_create(1000, 1.2, 8, 15, 0, 2, cv2.ORB_HARRIS_SCORE, 15, 7)
matcher = cv2.BFMatcher(cv2.NORM_HAMMING)
lock = threading.Lock()  # cv2.ORB is not thread-safe
cards = {}               # id -> dict(desc, pts, meta, dir, jpg, ref_shape, storage_prefix)

IMG_NAMES = ["card.jpg", "card.jpeg", "card.png", "card.webp"]


def resolve_card_dir(d: Path):
    """catalog/<id>/ normally; also accept catalog/<id>/<id>/."""
    for cand in (d, d / d.name):
        if cand.is_dir() and any((cand / n).exists() for n in IMG_NAMES):
            return cand
    return None


def read_card_image(path: Path):
    """-> (gray image, jpeg bytes). Transparent PNGs are flattened onto white."""
    img = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if img is None:
        return None, None
    if img.ndim == 3 and img.shape[2] == 4:
        a = img[:, :, 3:4].astype(np.float32) / 255.0
        img = (img[:, :, :3] * a + 255 * (1 - a)).astype(np.uint8)
    gray = img if img.ndim == 2 else cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    color = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR) if img.ndim == 2 else img
    ok, jpg = cv2.imencode(".jpg", color, [cv2.IMWRITE_JPEG_QUALITY, 95])
    return gray, jpg.tobytes() if ok else None


def load_catalog_from_folder():
    global cards
    new = {}
    print(f"Scanning {CATALOG} ...", flush=True)
    for d in sorted(CATALOG.iterdir()) if CATALOG.exists() else []:
        if not d.is_dir():
            continue
        if not ID_RE.match(d.name):
            print(f"  ! {d.name}: skipped - folder name may only use letters, digits, _ and -", flush=True)
            continue
        root = resolve_card_dir(d)
        if root is None:
            found = sorted(p.name for p in d.iterdir())
            print(f"  ! {d.name}: skipped - no card image inside. Folder contains: {found}", flush=True)
            continue
        src = next(root / n for n in IMG_NAMES if (root / n).exists())
        gray, jpg = read_card_image(src)
        if gray is None:
            print(f"  ! {d.name}: skipped - {src.name} could not be read as an image", flush=True)
            continue
        gray = cv2.resize(gray, (REF_W, int(gray.shape[0] * REF_W / gray.shape[1])))
        kp, desc = orb.detectAndCompute(gray, None)
        if desc is None or len(kp) < MIN_INLIERS:
            print(f"  ! {d.name}: skipped - too few features ({len(kp)}), card art needs more detail", flush=True)
            continue
        meta = {"id": d.name, "name": d.name, "rarity": "", "description": ""}
        mf = root / "meta.json"
        if mf.exists():
            try:
                meta.update(json.loads(mf.read_text(encoding="utf-8-sig")))
            except ValueError as e:
                print(f"  ~ {d.name}: meta.json is not valid JSON ({e}) - using defaults", flush=True)
        meta["id"] = d.name
        new[d.name] = dict(
            desc=desc,
            pts=np.float32([k.pt for k in kp]),
            meta=meta,
            dir=root,
            jpg=jpg,
            ref_shape=(gray.shape[1], gray.shape[0]),
            storage_prefix=None,
        )
        extra = []
        if not (root / "render.png").exists():
            extra.append("NO render.png")
        anims = [x.name for x in (root / "animations").iterdir() if x.is_dir() and (x / "meta.json").exists()] \
            if (root / "animations").is_dir() else []
        extra.append(f"animations: {anims or 'none'}")
        print(f"  + {d.name}: {len(kp)} features, {src.name}, " + ", ".join(extra), flush=True)
    with lock:
        cards = new


# --- C3: Supabase-backed catalog --------------------------------------------

def _supabase_rest(path_and_query, method="GET"):
    req = urllib.request.Request(
        f"{SUPABASE_URL}/rest/v1/{path_and_query}",
        method=method,
        headers={
            "apikey": SUPABASE_SERVICE_KEY,
            "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}",
        },
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read() or b"[]")


def _supabase_storage_get(storage_path):
    """Fetch one object from the Storage bucket. None on 404, raises otherwise."""
    url = f"{SUPABASE_URL}/storage/v1/object/{SUPABASE_BUCKET}/{urllib.parse.quote(storage_path)}"
    req = urllib.request.Request(url, headers={
        "apikey": SUPABASE_SERVICE_KEY,
        "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}",
    })
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def _supabase_list_animations(cid):
    """Storage 'list' call -> animation folder names under <cid>/animations/."""
    url = f"{SUPABASE_URL}/storage/v1/object/list/{SUPABASE_BUCKET}"
    body = json.dumps({"prefix": f"{cid}/animations/", "limit": 200}).encode()
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "apikey": SUPABASE_SERVICE_KEY,
        "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}",
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            entries = json.loads(resp.read() or b"[]")
    except Exception as e:
        print(f"  ~ {cid}: could not list animations ({e})", flush=True)
        return []
    # Supabase Storage's "list" returns immediate children of the prefix;
    # folders come back as entries with id=None.
    return sorted(e["name"] for e in entries if e.get("id") is None)


def _max_catalog_version():
    rows = _supabase_rest("cards?select=version&published=eq.true&order=version.desc&limit=1")
    return rows[0]["version"] if rows else 0


def load_catalog_from_supabase():
    global cards
    print("Loading catalog from Supabase...", flush=True)
    try:
        rows = _supabase_rest("cards?select=*&published=eq.true")
    except Exception as e:
        print(f"  ! Supabase fetch failed, keeping previous catalog: {e}", flush=True)
        return
    new = {}
    for row in rows:
        cid = row.get("id", "")
        if not ID_RE.match(cid):
            print(f"  ! {cid!r}: skipped - invalid id", flush=True)
            continue
        try:
            img_bytes = _supabase_storage_get(f"{cid}/card.jpg")
        except Exception as e:
            print(f"  ! {cid}: skipped - card.jpg fetch failed ({e})", flush=True)
            continue
        if img_bytes is None:
            print(f"  ! {cid}: skipped - no card.jpg in storage", flush=True)
            continue
        gray = cv2.imdecode(np.frombuffer(img_bytes, np.uint8), cv2.IMREAD_GRAYSCALE)
        if gray is None:
            print(f"  ! {cid}: skipped - card.jpg unreadable", flush=True)
            continue
        gray = cv2.resize(gray, (REF_W, int(gray.shape[0] * REF_W / gray.shape[1])))
        kp, desc = orb.detectAndCompute(gray, None)
        if desc is None or len(kp) < MIN_INLIERS:
            print(f"  ! {cid}: skipped - too few features ({len(kp)})", flush=True)
            continue
        color = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        ok, jpg = cv2.imencode(".jpg", color, [cv2.IMWRITE_JPEG_QUALITY, 95])
        meta = {
            "id": cid,
            "name": row.get("name") or cid,
            "rarity": row.get("rarity") or "",
            "description": row.get("description") or "",
        }
        new[cid] = dict(
            desc=desc,
            pts=np.float32([k.pt for k in kp]),
            meta=meta,
            dir=None,
            jpg=jpg.tobytes() if ok else None,
            ref_shape=(gray.shape[1], gray.shape[0]),
            storage_prefix=cid,
        )
        print(f"  + {cid}: {len(kp)} features (version {row.get('version')})", flush=True)
    with lock:
        cards = new


def maybe_refresh_supabase_catalog(force=False):
    """Checked at most once every CATALOG_RECHECK_SECONDS; reloads only if
    the DB's max(version) actually changed since our last known value."""
    global _last_known_version, _last_version_check_ts
    now = time.time()
    if not force and (now - _last_version_check_ts) < CATALOG_RECHECK_SECONDS:
        return
    _last_version_check_ts = now
    try:
        v = _max_catalog_version()
    except Exception as e:
        print(f"  ~ Supabase version check failed: {e}", flush=True)
        return
    if force or v != _last_known_version:
        _last_known_version = v
        load_catalog_from_supabase()


def load_catalog():
    if CATALOG_SOURCE == "supabase":
        if not SUPABASE_URL or not SUPABASE_SERVICE_KEY:
            print("  ! CATALOG_SOURCE=supabase but SUPABASE_URL/SUPABASE_SERVICE_KEY "
                  "are not set - serving an empty catalog", flush=True)
            return
        maybe_refresh_supabase_catalog(force=True)
    else:
        load_catalog_from_folder()


def _asset_bytes(cid, rel_path):
    """rel_path like 'render.png' or 'animations/spin/frame_003.png'.
    Works for both folder-mode (dir on disk) and supabase-mode (Storage)."""
    c = cards.get(cid)
    if c is None:
        return None
    if c["dir"] is not None:
        f = c["dir"] / rel_path
        return f.read_bytes() if f.exists() else None
    try:
        return _supabase_storage_get(f"{c['storage_prefix']}/{rel_path}")
    except Exception as e:
        print(f"  ~ {cid}: asset fetch failed for {rel_path} ({e})", flush=True)
        return None


def _quad_is_plausible(quad, frame_w, frame_h, ref_w, ref_h):
    """Reject degenerate/unrealistic homographies before accepting a card."""
    if quad is None or not np.isfinite(quad).all():
        return False

    q = quad.reshape(4, 2).astype(np.float32)
    area = abs(float(cv2.contourArea(q)))
    frame_area = float(frame_w * frame_h)
    if frame_area <= 0:
        return False
    area_ratio = area / frame_area
    if area_ratio < MIN_CARD_AREA_RATIO or area_ratio > MAX_CARD_AREA_RATIO:
        return False

    # A valid projected rectangle must be convex and have non-trivial edges.
    if not cv2.isContourConvex(q.reshape(-1, 1, 2)):
        return False
    edges = np.linalg.norm(np.roll(q, -1, axis=0) - q, axis=1)
    if np.min(edges) < 12.0:
        return False

    # Keep the projected shape broadly consistent with the reference card's
    # portrait/landscape orientation. This is deliberately loose for perspective.
    ref_ratio = max(ref_w, ref_h) / max(1.0, min(ref_w, ref_h))
    e_sorted = np.sort(edges)
    projected_ratio = float(e_sorted[-1] / max(1.0, e_sorted[0]))
    if projected_ratio > ref_ratio * 2.5 or projected_ratio < 1.0:
        return False

    return True


def _save_debug_frame(jpeg: bytes):
    if not DEBUG_SAVE_FRAMES:
        return
    try:
        DEBUG_FRAME_DIR.mkdir(parents=True, exist_ok=True)
        existing = list(DEBUG_FRAME_DIR.glob("frame_*.jpg"))
        idx = len(existing)
        (DEBUG_FRAME_DIR / f"frame_{idx:05d}.jpg").write_bytes(jpeg)
    except OSError as e:
        print(f"  ~ debug frame save failed: {e}", flush=True)


def identify(jpeg: bytes):
    if CATALOG_SOURCE == "supabase":
        maybe_refresh_supabase_catalog()  # throttled internally, cheap to call every request
    _save_debug_frame(jpeg)
    frame = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_GRAYSCALE)
    if frame is None:
        return {"match": False, "error": "bad image"}

    frame_h, frame_w = frame.shape[:2]
    with lock:
        kp, desc = orb.detectAndCompute(frame, None)
        snapshot = dict(cards)
        if desc is None or len(kp) < 2:
            print("  no match (frame had no features)", flush=True)
            return {"match": False}

        pts = np.float32([k.pt for k in kp])
        candidates = []

        for cid, c in snapshot.items():
            good = [m[0] for m in matcher.knnMatch(c["desc"], desc, k=2)
                    if len(m) == 2 and m[0].distance < RATIO * m[1].distance]
            if len(good) < MIN_GOOD:
                print(f"  {cid}: only {len(good)} good matches (need {MIN_GOOD})", flush=True)
                continue

            src = c["pts"][[m.queryIdx for m in good]].reshape(-1, 1, 2)
            dst = pts[[m.trainIdx for m in good]].reshape(-1, 1, 2)
            H, mask = cv2.findHomography(src, dst, cv2.RANSAC, 4.0)
            n = int(mask.sum()) if H is not None and mask is not None else 0
            shape_ok = False
            if H is not None:
                rw, rh = c["ref_shape"]
                corners = np.float32([[[0, 0], [rw - 1, 0], [rw - 1, rh - 1], [0, rh - 1]]])
                projected = cv2.perspectiveTransform(corners, H)
                shape_ok = _quad_is_plausible(projected, frame_w, frame_h, rw, rh)

            print(
                f"  {cid}: good={len(good)} inliers={n} "
                f"shape={'ok' if shape_ok else 'reject'} (need {MIN_INLIERS})",
                flush=True,
            )
            if n >= MIN_INLIERS and shape_ok:
                candidates.append((cid, n, len(good)))

    if not candidates:
        print(f"  no match (frame had {len(kp)} features)", flush=True)
        return {"match": False}

    candidates.sort(key=lambda x: (x[1], x[2]), reverse=True)
    best = candidates[0]
    if len(candidates) > 1:
        runner_up = candidates[1]
        if best[1] < runner_up[1] * RUNNER_UP_MARGIN:
            print(
                f"  ambiguous: {best[0]}={best[1]} vs {runner_up[0]}={runner_up[1]} "
                f"(need {RUNNER_UP_MARGIN:.2f}x margin)",
                flush=True,
            )
            return {"match": False}

    print(f"  MATCH {best[0]} ({best[1]} inliers, {best[2]} good)", flush=True)
    return {
        "match": True,
        "card_id": best[0],
        "inliers": best[1],
        "meta": snapshot[best[0]]["meta"],
    }


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body: bytes, ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj).encode())

    def do_HEAD(self):
        self.send_response(200)
        self.end_headers()

    def do_POST(self):
        if self.path == "/admin/reload":
            if not ADMIN_KEY:
                return self._json({"error": "ADMIN_KEY not configured on server"}, 503)
            if self.headers.get("X-Admin-Key") != ADMIN_KEY:
                return self._json({"error": "unauthorized"}, 401)
            load_catalog()
            return self._json({"ok": True, "cards": len(cards)})
        if self.path != "/identify":
            return self._json({"error": "not found"}, 404)
        try:
            n = int(self.headers.get("Content-Length", 0))
        except ValueError:
            n = 0
        if n <= 0 or n > 4_000_000:
            return self._json({"error": "bad size"}, 400)
        self._json(identify(self.rfile.read(n)))

    def do_GET(self):
        parts = [p for p in self.path.split("?")[0].split("/") if p]

        if not parts or parts == ["health"]:
            return self._json({"ok": True, "cards": len(cards)})
        if parts == ["reload"]:
            load_catalog()
            return self._json({"ok": True, "cards": len(cards)})
        if parts == ["cards"]:
            return self._json([c["meta"] for c in cards.values()])

        if len(parts) == 3 and parts[0] == "cards" and ID_RE.match(parts[1]):
            cid = parts[1]
            if cid not in cards:
                return self._json({"error": "not found"}, 404)
            d = cards[cid]["dir"]

            if parts[2] == "meta":
                return self._json(cards[cid]["meta"])
            if parts[2] == "card.jpg":
                return self._send(200, cards[cid]["jpg"], "image/jpeg")
            if parts[2] == "render.png":
                b = _asset_bytes(cid, "render.png")
                if b is not None:
                    return self._send(200, b, "image/png")
            if parts[2] == "animations":
                if d is not None:
                    anim_dir = d / "animations"
                    names = sorted(x.name for x in anim_dir.iterdir() if x.is_dir() and (x / "meta.json").exists()) \
                        if anim_dir.exists() else []
                else:
                    names = _supabase_list_animations(cid)
                return self._json(names)

        if len(parts) == 5 and parts[0] == "cards" and parts[2] == "animations":
            cid, anim, leaf = parts[1], parts[3], parts[4]
            if cid not in cards or not ID_RE.match(anim):
                return self._json({"error": "not found"}, 404)
            anim_root = f"animations/{anim}"
            card_dir = cards[cid]["dir"]
            if card_dir is not None and not (card_dir / anim_root).is_dir():
                return self._json({"error": "no animation"}, 404)

            meta_bytes = _asset_bytes(cid, f"{anim_root}/meta.json")
            if meta_bytes is None:
                return self._json({"error": "no meta"}, 404)
            meta = json.loads(meta_bytes)

            if leaf == "meta":
                return self._json(meta)

            if leaf == "bundle":
                frames = []
                if card_dir is not None:
                    frame_files = sorted((card_dir / anim_root).glob("frame_*.png"))
                else:
                    n = int(meta.get("frames", 0))
                    frame_files = [f"frame_{i:03d}.png" for i in range(n)]
                for f in frame_files:
                    raw = f.read_bytes() if card_dir is not None else _asset_bytes(cid, f"{anim_root}/{f}")
                    if raw is None:
                        continue
                    b64 = base64.b64encode(raw).decode("ascii")
                    frames.append("data:image/png;base64," + b64)
                return self._json({"meta": meta, "frames": frames})

            if leaf.startswith("frame_") and leaf.endswith(".png"):
                b = _asset_bytes(cid, f"{anim_root}/{leaf}")
                if b is not None:
                    return self._send(200, b, "image/png")

        self._json({"error": "not found"}, 404)

    def log_message(self, fmt, *a):
        print(self.address_string(), fmt % a)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    print("Loading catalog...")
    load_catalog()
    print(f"Serving {len(cards)} card(s) on 0.0.0.0:{port}")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
