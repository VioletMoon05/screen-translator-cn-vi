#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Backend Python cho Screen Translator CN-VI.

Java gui anh PNG (vung man hinh) toi POST /ocr; backend:
  1. OCR (RapidOCR / ONNX Runtime) -> cac dong chu + toa do
  2. Loc dong chua chu Han, gop dong thanh doan (tuy chon)
  3. Dich Trung -> Viet (Google qua deep-translator, hoac API kieu OpenAI / Ollama cuc bo)
  4. Phan tich nen / mau chu, tao mieng nen da xoa chu (inpaint) cho nen phuc tap
Giao tiep: HTTP JSON chi tren 127.0.0.1.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import sqlite3
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import cv2
import numpy as np

import offline

CFG = {
    "provider": "auto",            # auto | offline | google | openai | echo (de kiem thu)
    "openaiUrl": "http://localhost:11434/v1",
    "openaiKey": "",
    "openaiModel": "qwen2.5:7b",
    "mode": "speed",               # speed | accuracy
    "lowEnd": False,
    "mergeLines": True,
    "minConfidence": 0.5,
    "saveHistory": False,
}
DATA_DIR = os.path.join(os.path.expanduser("~"), ".screen-translator")

CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
SENT_END = tuple("。！？!?」』”…")


# --------------------------------------------------------------------------- OCR
ENGINE = None
OCR_LOCK = threading.Lock()


def load_engine():
    global ENGINE
    from rapidocr_onnxruntime import RapidOCR
    ENGINE = RapidOCR()


def run_ocr(img):
    """Tra ve list dict {x0,y0,x1,y1,text,score} theo toa do anh goc."""
    h, w = img.shape[:2]
    scale = 1.0
    proc = img
    if CFG["lowEnd"] and max(h, w) > 1280:
        scale = 1280.0 / max(h, w)
        proc = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    elif CFG["mode"] == "accuracy" and max(h, w) < 1600:
        scale = 1.5
        proc = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
    with OCR_LOCK:
        result, _ = ENGINE(proc, use_cls=False)
    lines = []
    for box, text, score in (result or []):
        score = float(score)
        text = (text or "").strip()
        if not text or score < CFG["minConfidence"]:
            continue
        cjk = len(CJK_RE.findall(text))
        letters = len(re.sub(r"\s", "", text))
        # giu dong co chu Han (ke ca lan tieng Anh); bo chu rac / dong khong phai tieng Trung
        if not (cjk >= 2 or (cjk == 1 and letters <= 4)):
            continue
        xs = [p[0] / scale for p in box]
        ys = [p[1] / scale for p in box]
        x0, y0, x1, y1 = int(min(xs)), int(min(ys)), int(max(xs)) + 1, int(max(ys)) + 1
        x0, y0 = max(0, x0), max(0, y0)
        x1, y1 = min(w, x1), min(h, y1)
        if x1 - x0 < 6 or y1 - y0 < 6:
            continue
        lines.append(dict(x0=x0, y0=y0, x1=x1, y1=y1, text=text, score=score))
    return lines


def join_text(parts):
    out = ""
    for p in parts:
        if out and out[-1].isascii() and out[-1].isalnum() and p[:1].isascii() and p[:1].isalnum():
            out += " "
        out += p
    return out


def merge_lines(lines):
    """Gop cac dong sat nhau, thang hang thanh doan van (giu bo cuc, dich co ngu canh hon)."""
    lines = sorted(lines, key=lambda l: (l["y0"], l["x0"]))
    blocks = []
    for ln in lines:
        h = ln["y1"] - ln["y0"]
        best, best_gap = None, None
        for b in blocks:
            last = b["lines"][-1]
            lh = last["y1"] - last["y0"]
            gap = ln["y0"] - last["y1"]
            if gap < -0.3 * h or gap > 0.6 * min(h, lh):
                continue
            if not 0.7 <= h / max(1, lh) <= 1.4:
                continue
            if ln["x0"] >= last["x1"]:
                continue
            margin = min(l["x0"] for l in b["lines"])
            if len(b["lines"]) == 1:
                ok = (last["x0"] - 2.6 * h) <= ln["x0"] <= (last["x0"] + 0.6 * h)
            else:
                ok = abs(ln["x0"] - margin) <= 0.6 * h
            if not ok:
                continue
            maxx = max(l["x1"] for l in b["lines"])
            if last["text"].endswith(SENT_END) and last["x1"] < maxx - 1.5 * h:
                continue  # dong cuoi cua doan truoc
            if best is None or gap < best_gap:
                best, best_gap = b, gap
        if best is None:
            blocks.append({"lines": [ln]})
        else:
            best["lines"].append(ln)
    out = []
    for b in blocks:
        ls = b["lines"]
        hs = [l["y1"] - l["y0"] for l in ls]
        out.append(dict(
            x0=min(l["x0"] for l in ls), y0=min(l["y0"] for l in ls),
            x1=max(l["x1"] for l in ls), y1=max(l["y1"] for l in ls),
            lh=int(statistics.median(hs)), text=join_text([l["text"] for l in ls])))
    return out


# --------------------------------------------------------------- nen / mau chu
def analyze(img, x0, y0, x1, y1):
    H, W = img.shape[:2]
    h = y1 - y0
    pad = int(max(3, min(12, h * 0.3)))
    X0, Y0, X1, Y1 = max(0, x0 - pad), max(0, y0 - pad), min(W, x1 + pad), min(H, y1 + pad)
    reg = img[Y0:Y1, X0:X1]
    inner_mask = np.zeros(reg.shape[:2], bool)
    inner_mask[y0 - Y0:y1 - Y0, x0 - X0:x1 - X0] = True
    ring = reg[~inner_mask]
    inner = reg[inner_mask].astype(np.float32)
    if ring.shape[0] < 20:
        ring = inner.reshape(-1, 3)
    bg = np.median(ring, axis=0).astype(np.float32)
    spread = float(np.mean(np.std(ring.astype(np.float32), axis=0)))
    dist = np.linalg.norm(inner - bg, axis=1)
    bg_lum = 0.114 * bg[0] + 0.587 * bg[1] + 0.299 * bg[2]
    if dist.size == 0 or dist.max() < 40:
        fg = np.array([255, 255, 255] if bg_lum < 128 else [0, 0, 0], np.float32)
    else:
        thr = max(np.percentile(dist, 85), 0.6 * dist.max())
        fg = inner[dist >= thr].mean(axis=0)
    fg_lum = 0.114 * fg[0] + 0.587 * fg[1] + 0.299 * fg[2]
    complex_bg = spread > 22
    patch_b64 = None
    if complex_bg and (x1 - x0) * (y1 - y0) <= 250000:
        sub = img[y0:y1, x0:x1].copy()
        gray = cv2.cvtColor(sub, cv2.COLOR_BGR2GRAY)
        ks = max(3, int(h * 0.25)) | 1
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (ks, ks))
        op = cv2.MORPH_BLACKHAT if fg_lum < bg_lum else cv2.MORPH_TOPHAT
        hat = cv2.morphologyEx(gray, op, k)
        _, m = cv2.threshold(hat, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        m = cv2.dilate(m, np.ones((3, 3), np.uint8), iterations=2)
        patch = cv2.inpaint(sub, m, 3, cv2.INPAINT_TELEA)
        ok, buf = cv2.imencode(".png", patch)
        if ok:
            patch_b64 = base64.b64encode(buf.tobytes()).decode("ascii")
    to_rgb = lambda c: [int(c[2]), int(c[1]), int(c[0])]
    return to_rgb(bg), to_rgb(fg), complex_bg, patch_b64


# ------------------------------------------------------------------- dich thuat
class RateLimited(Exception):
    def __init__(self, wait):
        super().__init__("Google dang gioi han yeu cau, thu lai sau %ds" % int(wait))
        self.wait = wait


class GoogleFree:
    """Google Dich (endpoint gtx) nhung 'lich su': gop nhieu doan vao 1 request, cach nhau >= MIN_GAP giay,
    gap 429/503 thi NGHI (cooldown tang dan 20s -> 10 phut) thay vi dap them request."""
    URL = "https://translate.googleapis.com/translate_a/single"
    MIN_GAP = 0.6
    MAX_CHARS = 1500
    MAX_LINES = 25

    def __init__(self):
        self.lock = threading.Lock()
        self.last = 0.0
        self.cool_until = 0.0
        self.cool_step = 20

    def remaining(self):
        return max(0.0, self.cool_until - time.time())

    def _post(self, text):                       # tach rieng de co the thay khi kiem thu
        data = urllib.parse.urlencode({"client": "gtx", "sl": "zh-CN", "tl": "vi", "dt": "t", "q": text}).encode("utf-8")
        req = urllib.request.Request(self.URL, data=data, headers={
            "User-Agent": "Mozilla/5.0", "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"})
        with urllib.request.urlopen(req, timeout=12) as r:
            return json.loads(r.read().decode("utf-8"))

    def _request(self, text):
        with self.lock:                          # tuan tu + cach quang: khong bao gio bung no nhieu request
            wait = self.last + self.MIN_GAP - time.time()
            if wait > 0:
                time.sleep(wait)
            self.last = time.time()
        try:
            j = self._post(text)
        except urllib.error.HTTPError as e:
            if e.code in (429, 503):
                self.cool_until = time.time() + self.cool_step
                self.cool_step = min(self.cool_step * 2, 600)
                raise RateLimited(self.remaining())
            raise
        self.cool_step = 20
        return "".join(seg[0] for seg in j[0] if seg and seg[0])

    def translate_many(self, texts):
        if self.remaining() > 0:
            raise RateLimited(self.remaining())
        clean = [re.sub(r"\s+", " ", t).strip() for t in texts]
        res = [None] * len(texts)
        batches, cur, chars = [], [], 0
        for i, t in enumerate(clean):
            if cur and (chars + len(t) > self.MAX_CHARS or len(cur) >= self.MAX_LINES):
                batches.append(cur)
                cur, chars = [], 0
            cur.append(i)
            chars += len(t) + 1
        if cur:
            batches.append(cur)
        for b in batches:
            out = self._request("\n".join(clean[i] for i in b))
            lines = [x.strip() for x in out.strip().split("\n")]
            if len(lines) == len(b):
                for i, l in zip(b, lines):
                    res[i] = l or None
            else:                                # Google gop/tach dong -> dich rieng (toi da 6 request them)
                for i in b[:6]:
                    res[i] = self._request(clean[i]).strip() or None
        return res


class Translator:
    """Thu tu: cache -> glossary/tu dien (exact) -> tu dien (cum ngan) -> online (gop, gioi han) -> tu dien (du phong)."""

    def __init__(self):
        self.cache = OrderedDict()               # ban dich ONLINE da hoc (luu ca ra dia)
        self.lock = threading.Lock()
        self.pool = ThreadPoolExecutor(max_workers=4)
        self.last_error = None
        self.google = GoogleFree()
        self.db_path = None

    # ---- cache vinh vien (SQLite): dich mot lan, lan sau khong goi mang nua
    def load_cache(self, data_dir):
        self.db_path = os.path.join(data_dir, "cache.db")
        try:
            os.makedirs(data_dir, exist_ok=True)
            con = sqlite3.connect(self.db_path)
            con.execute("CREATE TABLE IF NOT EXISTS cache(tag TEXT, src TEXT, dst TEXT, ts REAL, PRIMARY KEY(tag, src))")
            for tag, src, dst in con.execute("SELECT tag, src, dst FROM cache ORDER BY ts DESC LIMIT 20000"):
                self.cache[(tag, src)] = dst
            con.close()
        except Exception as e:
            print("Khong nap duoc cache:", e, flush=True)

    def _persist(self, rows):
        if not self.db_path or not rows:
            return
        try:
            con = sqlite3.connect(self.db_path)
            con.executemany("INSERT OR REPLACE INTO cache VALUES(?,?,?,?)", [(t, s, d, time.time()) for t, s, d in rows])
            con.commit()
            con.close()
        except Exception:
            pass

    def _tag(self):
        return "openai:" + CFG["openaiModel"] if CFG["provider"] == "openai" else "google"

    def _get(self, t):
        with self.lock:
            k = (self._tag(), t)
            v = self.cache.get(k)
            if v is not None:
                self.cache.move_to_end(k)
            return v

    def _put(self, t, v):
        with self.lock:
            self.cache[(self._tag(), t)] = v
            while len(self.cache) > 20000:
                self.cache.popitem(last=False)

    # ---- chinh
    def translate(self, texts):
        self.last_error = None
        prov = CFG["provider"]
        n = len(texts)
        out = [None] * n
        fallback = {}                            # i -> (ban dich tu dien, do phu)
        need = []
        for i, t in enumerate(texts):
            e = offline.exact(t)                 # glossary cua ban + tu dien: luon uu tien, khong mat mang
            if e:
                out[i] = e
                continue
            if prov == "echo":
                out[i] = "[VI] " + t
                continue
            c = self._get(t)
            if c is not None:
                out[i] = c
                continue
            if prov == "openai":
                need.append(i)
                continue
            tr, cov = offline.translate(t)
            fallback[i] = (tr, cov)
            if prov == "offline":
                out[i] = tr
            elif prov == "auto" and cov >= 0.999 and offline.cjk_count(t) <= 8 and "\u7684" not in t:
                out[i] = tr                      # nhan ngan hoan toan co trong tu dien -> dich offline luon
            else:
                need.append(i)
        if need:
            src = [texts[i] for i in need]
            try:
                res = self._openai(src) if prov == "openai" else self.google.translate_many(src)
            except RateLimited as e:
                self.last_error = "Google đang giới hạn yêu cầu, tạm dùng từ điển offline (thử lại sau %d giây)" % int(e.wait)
                res = [None] * len(need)
            except Exception as e:
                self.last_error = "Dịch online lỗi (%s), tạm dùng từ điển offline" % type(e).__name__
                res = [None] * len(need)
            rows = []
            for i, r in zip(need, res):
                if r:
                    out[i] = r
                    self._put(texts[i], r)
                    rows.append((self._tag(), texts[i], r))
            self._persist(rows)
            for i in need:                       # du phong: tu dien neu phu >= 85% (khong luu cache)
                if out[i] is None and i in fallback and fallback[i][1] >= 0.85:
                    out[i] = fallback[i][0]
        return out

    # ---- API kieu OpenAI / Ollama (giu nhu truoc)
    def _chat(self, system, user):
        url = CFG["openaiUrl"].rstrip("/") + "/chat/completions"
        body = json.dumps({
            "model": CFG["openaiModel"], "temperature": 0.2,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        }).encode("utf-8")
        req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
        if CFG["openaiKey"]:
            req.add_header("Authorization", "Bearer " + CFG["openaiKey"])
        with urllib.request.urlopen(req, timeout=90) as r:
            data = json.loads(r.read().decode("utf-8"))
        return data["choices"][0]["message"]["content"].strip()

    def _openai(self, texts):
        sys_arr = ("Bạn là dịch giả chuyên nghiệp, dịch tiếng Trung (giản thể hoặc phồn thể) sang tiếng Việt "
                   "tự nhiên, đúng ngữ cảnh. Đầu vào là mảng JSON các đoạn chữ cùng xuất hiện trên một màn hình. "
                   "Chỉ trả về một mảng JSON các chuỗi, cùng số phần tử, đúng thứ tự. Không giải thích, không markdown.")
        try:
            txt = self._chat(sys_arr, json.dumps(texts, ensure_ascii=False))
            a, b = txt.find("["), txt.rfind("]")
            arr = json.loads(txt[a:b + 1])
            if isinstance(arr, list) and len(arr) == len(texts):
                return [str(x) for x in arr]
        except urllib.error.URLError:
            raise
        except Exception:
            pass
        sys_one = "Dịch đoạn tiếng Trung sau sang tiếng Việt. Chỉ trả về bản dịch, không giải thích."
        return list(self.pool.map(lambda t: self._chat(sys_one, t), texts))


TR = Translator()


# ----------------------------------------------------------------------- lich su
def save_history(rows):
    try:
        os.makedirs(DATA_DIR, exist_ok=True)
        con = sqlite3.connect(os.path.join(DATA_DIR, "history.db"))
        con.execute("CREATE TABLE IF NOT EXISTS history(ts REAL, src TEXT, dst TEXT)")
        con.executemany("INSERT INTO history VALUES(?,?,?)", [(time.time(), s, d) for s, d in rows])
        con.commit()
        con.close()
    except Exception:
        pass


# ------------------------------------------------------------------- xu ly chinh
OCR_CACHE = OrderedDict()                        # md5(anh) -> cac dong OCR: quet lai/thu lai khong OCR lai


def process(img, key=None):
    t0 = time.time()
    lines = OCR_CACHE.get(key) if key else None
    cached = lines is not None
    if not cached:
        lines = run_ocr(img)
        if key:
            OCR_CACHE[key] = lines
            while len(OCR_CACHE) > 16:
                OCR_CACHE.popitem(last=False)
    t1 = time.time()
    if CFG["mergeLines"]:
        blocks = merge_lines(lines)
    else:
        blocks = [dict(x0=l["x0"], y0=l["y0"], x1=l["x1"], y1=l["y1"], lh=l["y1"] - l["y0"], text=l["text"])
                  for l in lines]
    dst = TR.translate([b["text"] for b in blocks]) if blocks else []
    t2 = time.time()
    items, hist = [], []
    for b, d in zip(blocks, dst):
        if not d:
            continue
        bg, fg, cx, patch = analyze(img, b["x0"], b["y0"], b["x1"], b["y1"])
        items.append(dict(x=b["x0"], y=b["y0"], w=b["x1"] - b["x0"], h=b["y1"] - b["y0"], lh=b["lh"],
                          src=b["text"], dst=d, bg=bg, fg=fg, complex=cx, patch=patch))
        hist.append((b["text"], d))
    if CFG["saveHistory"] and hist:
        save_history(hist)
    resp = dict(items=items, ocr_ms=int((t1 - t0) * 1000), tr_ms=int((t2 - t1) * 1000), blocks=len(blocks), cached=cached)
    if TR.last_error:
        resp["error"] = "Dịch lỗi: " + TR.last_error
    return resp


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, obj, code=200):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/health"):
            self._send({"ok": True, "ready": ENGINE is not None, "dict": offline.size()})
        else:
            self._send({"error": "not found"}, 404)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(n) if n else b""
        try:
            if self.path.startswith("/config"):
                CFG.update(json.loads(body.decode("utf-8")))
                OCR_CACHE.clear()
                self._send({"ok": True})
            elif self.path.startswith("/ocr"):
                img = cv2.imdecode(np.frombuffer(body, np.uint8), cv2.IMREAD_COLOR)
                if img is None:
                    self._send({"items": [], "error": "Ảnh không hợp lệ"}, 400)
                else:
                    self._send(process(img, hashlib.md5(body).hexdigest()))
            else:
                self._send({"error": "not found"}, 404)
        except Exception as e:
            self._send({"items": [], "error": "%s: %s" % (type(e).__name__, e)}, 500)


def watch_parent():
    """Java giu stdin mo; khi Java thoat (ke ca bi kill) stdin dong -> backend tu thoat."""
    try:
        while sys.stdin.read(1024):
            pass
    except Exception:
        pass
    os._exit(0)


def main():
    global DATA_DIR
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--data-dir", default=DATA_DIR)
    ap.add_argument("--no-watch", action="store_true")
    a = ap.parse_args()
    DATA_DIR = a.data_dir
    offline.load(DATA_DIR)
    TR.load_cache(DATA_DIR)
    load_engine()
    if not a.no_watch:
        threading.Thread(target=watch_parent, daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    print("READY", a.port, flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
