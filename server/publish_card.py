"""
publish_card.py — push one catalog/<card_id>/ folder to Supabase (C3 no-redeploy publishing).

Run this on YOUR OWN PC only. Never on Render, never with the key committed anywhere.

Setup (one time):
    1. In the Supabase SQL Editor, run the two `create table` statements from
       CardVault_Change_Spec.md section C3 (cards + claim_keys).
    2. In Supabase: Storage -> New bucket -> name it "cards" -> Public bucket: ON
       (card art isn't a secret today; C5/C6 will lock full assets behind a
       claim receipt later — see the spec).
    3. Set two env vars in YOUR terminal (never in a file you commit):
         setx SUPABASE_URL "https://nevbunlcvwbcfjtuwcwi.supabase.co"
         setx SUPABASE_SERVICE_KEY "<the secret key from Settings -> API Keys>"
       (setx only takes effect in NEW terminal windows — open a fresh one after.)

Usage:
    python publish_card.py catalog/card_004

What it does:
    - Reads catalog/<id>/{card.jpg|png, render.png, meta.json, animations/*}
    - Uploads card.jpg (converted/flattened exactly like the server does),
      render.png, and every animation's frames + meta.json to the "cards"
      Storage bucket under <id>/...
    - Upserts one row in the `cards` table with version = (current version)+1
    - Prints the new version. The live server (CATALOG_SOURCE=supabase)
      picks this up within CATALOG_RECHECK_SECONDS (~60s), or instantly via:
        curl -X POST https://<your-render-host>/admin/reload -H "X-Admin-Key: <ADMIN_KEY>"
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import cv2
import numpy as np

SUPABASE_URL = "https://nevbunlcvwbcfjtuwcwi.supabase.co"
SUPABASE_SERVICE_KEY =os.environ.get("SUPABASE_SERVICE_KEY", "")
BUCKET = "cards"
ID_RE_SRC = "^[A-Za-z0-9_-]{1,64}$"

IMG_NAMES = ["card.jpg", "card.jpeg", "card.png", "card.webp"]


def _die(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def _headers(extra=None):
    h = {"apikey": SUPABASE_SERVICE_KEY, "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}"}
    if extra:
        h.update(extra)
    return h


def _rest_get(path_and_query):
    req = urllib.request.Request(f"{SUPABASE_URL}/rest/v1/{path_and_query}", headers=_headers())
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read() or b"[]")


def _rest_upsert_card(row):
    req = urllib.request.Request(
        f"{SUPABASE_URL}/rest/v1/cards",
        data=json.dumps([row]).encode(),
        method="POST",
        headers=_headers({
            "Content-Type": "application/json",
            "Prefer": "resolution=merge-duplicates,return=representation",
        }),
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read() or b"[]")


def _storage_put(storage_path, data, content_type):
    url = f"{SUPABASE_URL}/storage/v1/object/{BUCKET}/{urllib.parse.quote(storage_path)}"
    req = urllib.request.Request(
        url, data=data, method="PUT",
        headers=_headers({"Content-Type": content_type, "x-upsert": "true"}),
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            resp.read()
    except urllib.error.HTTPError as e:
        _die(f"upload failed for {storage_path}: {e.code} {e.read().decode(errors='replace')}")


def _flatten_to_jpeg(src_path: Path) -> bytes:
    """Mirrors server.py's read_card_image(): flatten transparent PNGs onto
    white, encode as JPEG q95 — so Storage always holds a plain card.jpg,
    whatever the source format was."""
    img = cv2.imread(str(src_path), cv2.IMREAD_UNCHANGED)
    if img is None:
        _die(f"could not read {src_path}")
    if img.ndim == 3 and img.shape[2] == 4:
        a = img[:, :, 3:4].astype(np.float32) / 255.0
        img = (img[:, :, :3] * a + 255 * (1 - a)).astype(np.uint8)
    if img.ndim == 2:
        img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)
    ok, jpg = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 95])
    if not ok:
        _die(f"could not encode {src_path} as JPEG")
    return jpg.tobytes()


def main():
    if not SUPABASE_URL or not SUPABASE_SERVICE_KEY:
        _die("set SUPABASE_URL and SUPABASE_SERVICE_KEY as environment variables first (see the docstring).")
    if len(sys.argv) != 2:
        _die("usage: python publish_card.py catalog/<card_id>")

    folder = Path(sys.argv[1])
    cid = folder.name
    import re
    if not re.match(ID_RE_SRC, cid):
        _die(f"'{cid}' is not a valid card id (letters/digits/_/- only)")
    if not folder.is_dir():
        _die(f"{folder} is not a directory")

    src = next((folder / n for n in IMG_NAMES if (folder / n).exists()), None)
    if src is None:
        _die(f"no card.jpg/png/jpeg/webp found in {folder}")
    render = folder / "render.png"
    meta_file = folder / "meta.json"
    meta = {"name": cid, "rarity": "", "description": ""}
    if meta_file.exists():
        meta.update(json.loads(meta_file.read_text(encoding="utf-8-sig")))

    print(f"Publishing {cid} ...")
    print(f"  uploading {src.name} -> {cid}/card.jpg")
    _storage_put(f"{cid}/card.jpg", _flatten_to_jpeg(src), "image/jpeg")

    if render.exists():
        print(f"  uploading render.png")
        _storage_put(f"{cid}/render.png", render.read_bytes(), "image/png")
    else:
        print("  (no render.png found - skipping)")

    anim_dir = folder / "animations"
    if anim_dir.is_dir():
        for a in sorted(p for p in anim_dir.iterdir() if p.is_dir()):
            amf = a / "meta.json"
            if not amf.exists():
                print(f"  ~ skipping animation '{a.name}': no meta.json")
                continue
            print(f"  uploading animation '{a.name}'")
            _storage_put(f"{cid}/animations/{a.name}/meta.json", amf.read_bytes(), "application/json")
            for f in sorted(a.glob("frame_*.png")):
                _storage_put(f"{cid}/animations/{a.name}/{f.name}", f.read_bytes(), "image/png")

    existing = _rest_get(f"cards?id=eq.{cid}&select=version")
    next_version = (existing[0]["version"] + 1) if existing else 1
    row = {"id": cid, "name": meta.get("name", cid), "rarity": meta.get("rarity", ""),
           "description": meta.get("description", ""), "version": next_version, "published": True}
    _rest_upsert_card(row)

    print(f"Done. {cid} is now version {next_version}.")
    print("The live server will pick it up within ~60s, or immediately via POST /admin/reload.")


if __name__ == "__main__":
    main()
