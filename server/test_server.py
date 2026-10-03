"""Self-test: start the server, send a warped photo of card_001 and a random frame."""
import threading, urllib.request, json, cv2, numpy as np, server
from http.server import ThreadingHTTPServer
server.load_catalog()
srv = ThreadingHTTPServer(("127.0.0.1", 8765), server.Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()
def post(img):
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 80])
    r = urllib.request.Request("http://127.0.0.1:8765/identify", buf.tobytes(), {"Content-Type": "image/jpeg"})
    return json.load(urllib.request.urlopen(r))
ref = cv2.imread("catalog/card_001/card.jpg"); ref = cv2.resize(ref, (480, 627))
rng = np.random.default_rng(3); hits = 0; T = 12
for i in range(T):
    M = cv2.getRotationMatrix2D((240, 313), rng.uniform(-30, 30), rng.uniform(0.6, 0.95))
    M[:, 2] += (rng.uniform(0, 60), rng.uniform(0, 60))
    bg = rng.normal(120, 25, (720, 640, 3)).clip(0, 255).astype(np.uint8)
    frame = cv2.warpAffine(ref, M, (640, 720), bg, borderMode=cv2.BORDER_TRANSPARENT)
    hits += post(cv2.GaussianBlur(frame, (0, 0), 1.0)).get("match", False)
print("card frames matched:", hits, "/", T)
print("random frame:", post(rng.normal(120, 40, (720, 640, 3)).clip(0, 255).astype(np.uint8)))
for p in ("health", "cards", "cards/card_001/meta", "cards/card_001/render.png", "cards/../x/card.jpg"):
    try: print(p, "->", len(urllib.request.urlopen("http://127.0.0.1:8765/" + p).read()), "bytes")
    except Exception as e: print(p, "->", e)
