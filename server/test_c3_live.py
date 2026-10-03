"""
test_c3_live.py — smoke-test a running server.py (local OR Render) with no
dependencies beyond the stdlib. Doesn't touch Supabase directly; it only
calls the HTTP endpoints your Android app already calls, so a pass here
means the app will actually work.

Usage:
    python test_c3_live.py http://127.0.0.1:8001
    python test_c3_live.py https://serverstat-cpsy.onrender.com
"""
import json
import sys
import urllib.request

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://127.0.0.1:8001"
EXPECTED_ANIMS = {"card_001": "spin", "card_002": "cry", "card_004": "ooze"}
failures = []


def get(path):
    with urllib.request.urlopen(f"{BASE}{path}", timeout=30) as r:
        return r.status, r.read()


def check(label, cond):
    print(("  PASS  " if cond else "  FAIL  ") + label)
    if not cond:
        failures.append(label)


print(f"Testing {BASE}\n")

status, body = get("/health")
h = json.loads(body)
check(f"GET /health -> 200 with cards>0 (got {h})", status == 200 and h.get("cards", 0) > 0)

status, body = get("/cards")
cards = {c["id"]: c for c in json.loads(body)}
check(f"GET /cards includes card_001, card_002, card_004 (got {sorted(cards)})",
      {"card_001", "card_002", "card_004"} <= set(cards))

for cid, anim in EXPECTED_ANIMS.items():
    if cid not in cards:
        check(f"{cid}: present in /cards", False)
        continue
    status, render = get(f"/cards/{cid}/render.png")
    check(f"{cid}: render.png fetchable ({len(render)} bytes)", status == 200 and len(render) > 100)

    status, body = get(f"/cards/{cid}/animations")
    names = json.loads(body)
    check(f"{cid}: animation '{anim}' listed (got {names})", anim in names)

    status, body = get(f"/cards/{cid}/animations/{anim}/meta")
    meta = json.loads(body)
    nframes = int(meta.get("frames", 0))
    check(f"{cid}/{anim}: meta.json has frames>0 (got {meta})", nframes > 0)

    status, frame = get(f"/cards/{cid}/animations/{anim}/frame_000.png")
    check(f"{cid}/{anim}: frame_000.png fetchable ({len(frame)} bytes)",
          status == 200 and len(frame) > 100)

print()
if failures:
    print(f"{len(failures)} FAILURE(S):")
    for f in failures:
        print("  -", f)
    sys.exit(1)
print("ALL CHECKS PASSED.")
