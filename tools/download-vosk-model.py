import urllib.request, zipfile, os, ssl, sys, time

URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
WS = r"C:\Users\wxf20\Desktop\LingMouApp"
ZIP = os.path.join(WS, ".toolchain", "vosk-model-small-cn-0.22.zip")
DEST = os.path.join(WS, "app", "src", "main", "assets", "models")

ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE

os.makedirs(os.path.dirname(ZIP), exist_ok=True)
os.makedirs(DEST, exist_ok=True)

def report(done, total, t0):
    pct = 100.0 * done / total if total else 0
    mb = done / 1048576
    print(f"  {mb:7.1f} MB ({pct:5.1f}%)  {time.time()-t0:4.0f}s", flush=True)

print("下载中:", URL)
req = urllib.request.Request(URL, headers={"User-Agent": "Mozilla/5.0"})
with urllib.request.urlopen(req, timeout=120, context=ctx) as r:
    total = int(r.headers.get("Content-Length") or 0)
    t0 = time.time()
    done = 0
    last = 0
    with open(ZIP, "wb") as f:
        while True:
            chunk = r.read(1024 * 256)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if time.time() - last > 2:
                report(done, total, t0)
                last = time.time()
    report(done, total, t0)
print("下载完成:", ZIP, f"({done/1048576:.1f} MB)")

print("解压到:", DEST)
with zipfile.ZipFile(ZIP) as zf:
    zf.extractall(DEST)

model_dir = os.path.join(DEST, "vosk-model-small-cn-0.22")
ok = os.path.isdir(os.path.join(model_dir, "am")) and os.path.isdir(os.path.join(model_dir, "conf"))
print("模型目录:", model_dir)
print("am 目录存在:", os.path.isdir(os.path.join(model_dir, "am")))
print("conf 目录存在:", os.path.isdir(os.path.join(model_dir, "conf")))
print("RESULT:", "OK" if ok else "FAIL")
sys.exit(0 if ok else 1)
