"""
Card catalog + recognition server.  Only needs: pip install -r requirements.txt
"""
import base64
import hashlib
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

CATALOG_SOURCE = os.getenv("CATALOG_SOURCE", "supabase" if os.getenv("SUPABASE_URL") else "folder").strip().lower()       
SUPABASE_URL = os.getenv("SUPABASE_URL", "").rstrip("/")
SUPABASE_SERVICE_KEY = os.getenv("SUPABASE_SERVICE_KEY", "")
SUPABASE_BUCKET = os.getenv("SUPABASE_BUCKET", "cards")
ADMIN_KEY = os.getenv("ADMIN_KEY", "")
CLAIM_SECRET_MODE = os.getenv("CLAIM_SECRET_MODE", "raw").strip().lower()
ALLOW_DEV_CLAIMS = os.getenv("ALLOW_DEV_CLAIMS", "0") == "1"
CATALOG_RECHECK_SECONDS = 60
_last_known_version = -1
_last_version_check_ts = 0.0

orb = cv2.ORB_create(1000, 1.2, 8, 15, 0, 2, cv2.ORB_HARRIS_SCORE, 15, 7)
matcher = cv2.BFMatcher(cv2.NORM_HAMMING)
lock = threading.Lock()  
cards = {}               

# --- P2P Trade Escrow Memory ---
active_trades = {}

IMG_NAMES = ["card.jpg", "card.jpeg", "card.png", "card.webp"]

def resolve_card_dir(d: Path):
    for cand in (d, d / d.name):
        if cand.is_dir() and any((cand / n).exists() for n in IMG_NAMES):
            return cand
    return None

def read_card_image(path: Path):
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
            continue
        root = resolve_card_dir(d)
        if root is None:
            continue
        src = next(root / n for n in IMG_NAMES if (root / n).exists())
        gray, jpg = read_card_image(src)
        if gray is None:
            continue
        gray = cv2.resize(gray, (REF_W, int(gray.shape[0] * REF_W / gray.shape[1])))
        kp, desc = orb.detectAndCompute(gray, None)
        if desc is None or len(kp) < MIN_INLIERS:
            continue
        meta = {"id": d.name, "name": d.name, "rarity": "", "description": ""}
        mf = root / "meta.json"
        if mf.exists():
            try:
                meta.update(json.loads(mf.read_text(encoding="utf-8-sig")))
            except ValueError:
                pass
        meta["id"] = d.name
        new[d.name] = dict(desc=desc, pts=np.float32([k.pt for k in kp]), meta=meta, dir=root, jpg=jpg, ref_shape=(gray.shape[1], gray.shape[0]), storage_prefix=None)
        print(f"  + {d.name} loaded", flush=True)
    with lock:
        cards = new

def _supabase_rest(path_and_query, method="GET"):
    req = urllib.request.Request(
        f"{SUPABASE_URL}/rest/v1/{path_and_query}",
        method=method,
        headers={"apikey": SUPABASE_SERVICE_KEY, "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}"},
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read() or b"[]")

def _supabase_storage_get(storage_path):
    url = f"{SUPABASE_URL}/storage/v1/object/{SUPABASE_BUCKET}/{urllib.parse.quote(storage_path)}"
    req = urllib.request.Request(url, headers={"apikey": SUPABASE_SERVICE_KEY, "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}"})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise

def _supabase_list_animations(cid):
    url = f"{SUPABASE_URL}/storage/v1/object/list/{SUPABASE_BUCKET}"
    body = json.dumps({"prefix": f"{cid}/animations/", "limit": 200}).encode()
    req = urllib.request.Request(url, data=body, method="POST", headers={"apikey": SUPABASE_SERVICE_KEY, "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            entries = json.loads(resp.read() or b"[]")
    except Exception:
        return []
    return sorted(e["name"] for e in entries if e.get("id") is None)

def _max_catalog_version():
    rows = _supabase_rest("cards?select=version&published=eq.true&order=version.desc&limit=1")
    return rows[0]["version"] if rows else 0

def load_catalog_from_supabase():
    global cards
    try:
        rows = _supabase_rest("cards?select=*&published=eq.true")
    except Exception:
        return
    new = {}
    for row in rows:
        cid = row.get("id", "")
        if not ID_RE.match(cid): continue
        try: img_bytes = _supabase_storage_get(f"{cid}/card.jpg")
        except Exception: continue
        if img_bytes is None: continue
        gray = cv2.imdecode(np.frombuffer(img_bytes, np.uint8), cv2.IMREAD_GRAYSCALE)
        if gray is None: continue
        gray = cv2.resize(gray, (REF_W, int(gray.shape[0] * REF_W / gray.shape[1])))
        kp, desc = orb.detectAndCompute(gray, None)
        if desc is None or len(kp) < MIN_INLIERS: continue
        color = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        ok, jpg = cv2.imencode(".jpg", color, [cv2.IMWRITE_JPEG_QUALITY, 95])
        meta = {"id": cid, "name": row.get("name") or cid, "rarity": row.get("rarity") or "", "description": row.get("description") or ""}
        new[cid] = dict(desc=desc, pts=np.float32([k.pt for k in kp]), meta=meta, dir=None, jpg=jpg.tobytes() if ok else None, ref_shape=(gray.shape[1], gray.shape[0]), storage_prefix=cid)
    with lock:
        cards = new

def maybe_refresh_supabase_catalog(force=False):
    global _last_known_version, _last_version_check_ts
    now = time.time()
    if not force and (now - _last_version_check_ts) < CATALOG_RECHECK_SECONDS: return
    _last_version_check_ts = now
    try: v = _max_catalog_version()
    except Exception: return
    if force or v != _last_known_version:
        _last_known_version = v
        load_catalog_from_supabase()

def load_catalog():
    if CATALOG_SOURCE == "supabase":
        if SUPABASE_URL and SUPABASE_SERVICE_KEY: maybe_refresh_supabase_catalog(force=True)
    else: load_catalog_from_folder()

def _asset_bytes(cid, rel_path):
    c = cards.get(cid)
    if c is None: return None
    if c["dir"] is not None:
        f = c["dir"] / rel_path
        return f.read_bytes() if f.exists() else None
    try: return _supabase_storage_get(f"{c['storage_prefix']}/{rel_path}")
    except Exception: return None

def _quad_is_plausible(quad, frame_w, frame_h, ref_w, ref_h):
    if quad is None or not np.isfinite(quad).all(): return False
    q = quad.reshape(4, 2).astype(np.float32)
    area = abs(float(cv2.contourArea(q)))
    frame_area = float(frame_w * frame_h)
    if frame_area <= 0: return False
    area_ratio = area / frame_area
    if area_ratio < MIN_CARD_AREA_RATIO or area_ratio > MAX_CARD_AREA_RATIO: return False
    if not cv2.isContourConvex(q.reshape(-1, 1, 2)): return False
    edges = np.linalg.norm(np.roll(q, -1, axis=0) - q, axis=1)
    if np.min(edges) < 12.0: return False
    ref_ratio = max(ref_w, ref_h) / max(1.0, min(ref_w, ref_h))
    e_sorted = np.sort(edges)
    projected_ratio = float(e_sorted[-1] / max(1.0, e_sorted[0]))
    if projected_ratio > ref_ratio * 2.5 or projected_ratio < 1.0: return False
    return True

def identify(jpeg: bytes):
    if CATALOG_SOURCE == "supabase": maybe_refresh_supabase_catalog()
    frame = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_GRAYSCALE)
    if frame is None: return {"match": False, "error": "bad image"}

    frame_h, frame_w = frame.shape[:2]
    with lock:
        kp, desc = orb.detectAndCompute(frame, None)
        snapshot = dict(cards)
        if desc is None or len(kp) < 2: return {"match": False}
        pts = np.float32([k.pt for k in kp])
        candidates = []
        for cid, c in snapshot.items():
            good = [m[0] for m in matcher.knnMatch(c["desc"], desc, k=2) if len(m) == 2 and m[0].distance < RATIO * m[1].distance]
            if len(good) < MIN_GOOD: continue
            src = c["pts"][[m.queryIdx for m in good]].reshape(-1, 1, 2)
            dst = pts[[m.trainIdx for m in good]].reshape(-1, 1, 2)
            H, mask = cv2.findHomography(src, dst, cv2.RANSAC, 4.0)
            n = int(mask.sum()) if H is not None and mask is not None else 0
            shape_ok = False
            if H is not None:
                rw, rh = c["ref_shape"]
                corners = np.float32([[[0, 0], [rw - 1, 0], [rw - 1, rh - 1], [0, rh - 1]]])
                shape_ok = _quad_is_plausible(cv2.perspectiveTransform(corners, H), frame_w, frame_h, rw, rh)
            if n >= MIN_INLIERS and shape_ok: candidates.append((cid, n, len(good)))

    if not candidates: return {"match": False}
    candidates.sort(key=lambda x: (x[1], x[2]), reverse=True)
    best = candidates[0]
    if len(candidates) > 1 and best[1] < candidates[1][1] * RUNNER_UP_MARGIN: return {"match": False}
    return {"match": True, "card_id": best[0], "inliers": best[1], "meta": snapshot[best[0]]["meta"]}

class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body=b'', content_type='application/json'):
        try:
            self.send_response(code)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            pass
    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj).encode())

    def do_HEAD(self):
        self.send_response(200)
        self.end_headers()

    def do_POST(self):
        # 1. Register Token
        if self.path == "/trade/create":
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                token, card_id = body.get("token"), body.get("card_id")
                if token and card_id:
                    with lock: active_trades[token] = {"card_id": card_id, "expires": time.time() + 300}
                    return self._json({"ok": True})
            except Exception: pass
            return self._json({"error": "Bad request"}, 400)

        # 2. Claim & Burn Trade Token
        if self.path == "/trade/claim":
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                token = body.get("token")
                with lock:
                    now = time.time()
                    expired = [k for k, v in active_trades.items() if v["expires"] < now]
                    for k in expired: del active_trades[k]
                    if token in active_trades:
                        trade = active_trades.pop(token)
                        return self._json({"ok": True, "card_id": trade["card_id"]})
            except Exception: pass
            return self._json({"error": "Invalid or expired token"}, 403)
            
        # 3. Cancel Trade Token (Restores to sender)
        if self.path == "/trade/cancel":
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                token = body.get("token")
                with lock:
                    if token in active_trades:
                        del active_trades[token]
                        return self._json({"ok": True})
            except Exception: pass
            return self._json({"error": "Already claimed"}, 400)

        # 4. Burn Physical Printed QR Code (Using Supabase)
        if self.path == "/claim/physical":
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                card_id, secret = body.get("card_id"), body.get("secret")
                
                if not card_id or not secret:
                    return self._json({"error": "Missing parameters"}, 400)

                if not SUPABASE_URL or not SUPABASE_SERVICE_KEY:
                    if ALLOW_DEV_CLAIMS:
                        return self._json({"ok": True, "card_id": card_id})
                    return self._json({"error": "Supabase is not configured"}, 503)

                secret_value = (
                    hashlib.sha256(secret.encode("utf-8")).hexdigest()
                    if CLAIM_SECRET_MODE == "sha256"
                    else secret
                )

                q_card = urllib.parse.quote(card_id, safe="")
                q_secret = urllib.parse.quote(secret_value, safe="")
                check_url = (
                    f"claim_keys?card_id=eq.{q_card}"
                    f"&secret_hash=eq.{q_secret}&select=card_id,claimed_at"
                )
                rows = _supabase_rest(check_url)
                if not rows:
                    return self._json({"error": "Invalid QR"}, 403)
                if rows[0].get("claimed_at"):
                    return self._json({"error": "Already claimed"}, 403)

                # Atomically burn only an unclaimed key.
                patch_url = (
                    f"{SUPABASE_URL}/rest/v1/claim_keys"
                    f"?card_id=eq.{q_card}"
                    f"&secret_hash=eq.{q_secret}"
                    f"&claimed_at=is.null"
                )
                timestamp = time.strftime('%Y-%m-%dT%H:%M:%S.000Z')
                req = urllib.request.Request(
                    patch_url,
                    data=json.dumps({"claimed_at": timestamp}).encode(),
                    method="PATCH",
                    headers={
                        "apikey": SUPABASE_SERVICE_KEY,
                        "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}",
                        "Content-Type": "application/json",
                        "Prefer": "return=representation",
                    },
                )
                with urllib.request.urlopen(req, timeout=15) as resp:
                    updated = json.loads(resp.read() or b"[]")
                    if updated:
                        return self._json({"ok": True, "card_id": card_id})

                return self._json({"error": "Already claimed"}, 403)
            except Exception as e:
                print(f"Physical Claim Error: {e}", flush=True)
            return self._json({"error": "Claim failed"}, 500)

        if self.path == "/identify":
            try: n = int(self.headers.get("Content-Length", 0))
            except ValueError: n = 0
            if n <= 0 or n > 4_000_000: return self._json({"error": "bad size"}, 400)
            return self._json(identify(self.rfile.read(n)))
            
        return self._json({"error": "not found"}, 404)

    def do_GET(self):
        parts = [p for p in self.path.split("?")[0].split("/") if p]
        if not parts or parts == ["health"]:
            return self._json({
                "ok": True,
                "cards": len(cards),
                "catalog_source": CATALOG_SOURCE,
                "supabase": bool(SUPABASE_URL and SUPABASE_SERVICE_KEY),
            })
        if parts == ["cards"]: return self._json([c["meta"] for c in cards.values()])
        if len(parts) == 3 and parts[0] == "cards" and ID_RE.match(parts[1]):
            cid = parts[1]
            if cid not in cards: return self._json({"error": "not found"}, 404)
            d = cards[cid]["dir"]
            if parts[2] == "meta": return self._json(cards[cid]["meta"])
            if parts[2] == "card.jpg": return self._send(200, cards[cid]["jpg"], "image/jpeg")
            if parts[2] == "render.png":
                b = _asset_bytes(cid, "render.png")
                if b is not None: return self._send(200, b, "image/png")
            if parts[2] == "animations":
                names = _supabase_list_animations(cid) if d is None else sorted(x.name for x in (d / "animations").iterdir() if x.is_dir() and (x / "meta.json").exists())
                return self._json(names)
                
        if len(parts) == 5 and parts[0] == "cards" and parts[2] == "animations":
            cid, anim, leaf = parts[1], parts[3], parts[4]
            if cid not in cards or not ID_RE.match(anim): return self._json({"error": "not found"}, 404)
            anim_root = f"animations/{anim}"
            card_dir = cards[cid]["dir"]
            meta_bytes = _asset_bytes(cid, f"{anim_root}/meta.json")
            if meta_bytes is None: return self._json({"error": "no meta"}, 404)
            meta = json.loads(meta_bytes)
            
            if leaf == "meta": return self._json(meta)
            
            if leaf == "bundle":
                frames = []
                frame_files = [f"frame_{i:03d}.png" for i in range(int(meta.get("frames", 0)))] if card_dir is None else sorted((card_dir / anim_root).glob("frame_*.png"))
                for f in frame_files:
                    raw = _asset_bytes(cid, f"{anim_root}/{f.name if hasattr(f, 'name') else f}")
                    if raw: frames.append("data:image/png;base64," + base64.b64encode(raw).decode("ascii"))
                return self._json({"meta": meta, "frames": frames})
                
        self._json({"error": "not found"}, 404)

    def log_message(self, fmt, *a): print(self.address_string(), fmt % a)

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    print("Loading catalog...")
    load_catalog()
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()