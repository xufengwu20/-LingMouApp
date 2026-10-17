import urllib.request, ssl, os, sys, time, threading

URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
SIZE = 43898754          # 由 Range 探测得到
THREADS = 8
WS = r"C:\Users\wxf20\Desktop\LingMouApp"
ZIP = os.path.join(WS, ".toolchain", "vosk-model-small-cn-0.22.zip")
DEST = os.path.join(WS, "app", "src", "main", "assets", "models")

ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
UA = {"User-Agent": "Mozilla/5.0"}

lock = threading.Lock()
done_bytes = 0
last_print = [0.0]
t0 = time.time()

# 预分配文件
with open(ZIP, "wb") as f:
    f.truncate(SIZE)

def part(idx, start, end):
    global done_bytes
    req = urllib.request.Request(URL, headers={**UA, "Range": f"bytes={start}-{end}"})
    try:
        with urllib.request.urlopen(req, timeout=120, context=ctx) as r:
            with open(ZIP, "r+b") as f:
                f.seek(start)
                pos = start
                while True:
                    chunk = r.read(1024 * 128)
                    if not chunk:
                        break
                    f.seek(pos)
                    f.write(chunk)
                    pos += len(chunk)
                    with lock:
                        done_bytes += len(chunk)
    except Exception as e:
        print(f"  线程{idx} 失败: {type(e).__name__} {e}", flush=True)

step = SIZE // THREADS
threads = []
for i in range(THREADS):
    s = i * step
    e = SIZE - 1 if i == THREADS - 1 else (i + 1) * step - 1
    t = threading.Thread(target=part, args=(i, s, e), daemon=True)
    t.start()
    threads.append(t)

while any(t.is_alive() for t in threads):
    time.sleep(2)
    mb = done_bytes / 1048576
    speed = done_bytes / max(1, time.time() - t0) / 1024
    print(f"  {mb:6.1f} / {SIZE/1048576:.1f} MB  ({100*done_bytes/SIZE:4.1f}%)  {speed:5.1f} KB/s", flush=True)

for t in threads:
    t.join()

# 校验：解压测试
import zipfile
print("下载完成，开始解压…", flush=True)
os.makedirs(DEST, exist_ok=True)
try:
    with zipfile.ZipFile(ZIP) as zf:
        zf.extractall(DEST)
    model_dir = os.path.join(DEST, "vosk-model-small-cn-0.22")
    ok = os.path.isdir(os.path.join(model_dir, "am")) and os.path.isdir(os.path.join(model_dir, "conf"))
    print(f"模型目录: {model_dir}")
    print("am 存在:", os.path.isdir(os.path.join(model_dir, "am")))
    print("RESULT:", "OK" if ok else "FAIL")
    sys.exit(0 if ok else 1)
except zipfile.BadZipFile as e:
    print("RESULT: FAIL 压缩包损坏（并行下载丢段？）", e)
    sys.exit(1)
