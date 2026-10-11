# -*- coding: utf-8 -*-
"""Kiem thu backend (khong can Java): tao anh chu Trung gian the / phon the, nen don gian va nen phuc tap,
chay OCR + gop dong + dich (provider 'echo') + inpaint.   Chay:  python selftest.py"""
import os
import sys
import time

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server  # noqa: E402

FONTS = [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msjh.ttc", r"C:\Windows\Fonts\simsun.ttc",
         "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"]
font_path = next((f for f in FONTS if os.path.exists(f)), None)
assert font_path, "Khong tim thay font CJK de tao anh thu"
F = ImageFont.truetype(font_path, 28)


def make(bg, fg, lines, size=(900, 300), complex_bg=False):
    im = Image.new("RGB", size, bg)
    if complex_bg:
        rng = np.random.default_rng(1)  # nen giong anh: cac mang mau + nhieu
        small = rng.integers(20, 140, (size[1] // 20, size[0] // 20, 3), dtype=np.uint8)
        a = cv2.resize(small, size, interpolation=cv2.INTER_CUBIC).astype(int)
        a = (a + rng.integers(0, 30, a.shape)).clip(0, 255).astype(np.uint8)
        im = Image.fromarray(a)
    d = ImageDraw.Draw(im)
    y = 20
    for t in lines:
        d.text((30, y), t, font=F, fill=fg)
        y += 44
    return cv2.cvtColor(np.array(im), cv2.COLOR_RGB2BGR)


server.load_engine()
server.CFG["provider"] = "echo"
cases = {
    "gian the": make((255, 255, 255), (0, 0, 0),
                     ["你好，世界！这是一个测试。", "今天天气很好，我们去公园玩。", "", "Hello 欢迎使用 Screen Translator"]),
    "phon the": make((245, 240, 225), (30, 30, 30), ["這是繁體中文的測試文字。", "歡迎使用螢幕翻譯軟體。"]),
    "nen phuc tap": make((0, 0, 0), (255, 255, 255), ["背景比较复杂的文字", "第二行也是中文"], complex_bg=True),
    "chu sang tren nen toi": make((20, 20, 20), (230, 230, 230), ["游戏设置：音量 画质 语言"]),
}
ok = True
for name, img in cases.items():
    t = time.time()
    r = server.process(img)
    print("== %s: %d khoi, ocr %dms, dich %dms, tong %.0fms" % (
        name, len(r["items"]), r["ocr_ms"], r["tr_ms"], (time.time() - t) * 1000))
    for it in r["items"]:
        print("   (%d,%d %dx%d) bg=%s fg=%s complex=%s patch=%s | %s" % (
            it["x"], it["y"], it["w"], it["h"], it["bg"], it["fg"], it["complex"], bool(it["patch"]), it["src"]))
    if not r["items"]:
        ok = False
print("KET QUA:", "OK" if ok else "THAT BAI")
sys.exit(0 if ok else 1)
