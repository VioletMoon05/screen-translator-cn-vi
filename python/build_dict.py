# -*- coding: utf-8 -*-
"""Dung TU DIEN LON offline (~1,1 trieu cum Trung->Viet) vao  ~/.screen-translator/dict_big.db

Nguon: bo du lieu VietPhrase cong dong (dung cho QuickTranslator): Vietphrase.txt (cum tu), Names.txt (ten rieng),
HanViet.txt (am Han Viet tung chu), ZZLuatNhan.txt (luat dao trat tu, vd "在{0}的面前=truoc mat {0}").
Du lieu cong dong, KHONG co giay phep ro rang -> chi tai ve may ban de dung ca nhan, khong phat hanh lai.

Cach dung:
    python build_dict.py                    # tu tai tu GitHub (can mang, ~45 MB, chi can 1 lan)
    python build_dict.py --from-dir D:\\vp   # dung thu muc co san 4 file tren (khong can mang)
"""
import argparse
import os
import re
import sqlite3
import sys
import time
import urllib.request

BASE = "https://raw.githubusercontent.com/hoangtuantk/Vietphrase/HEAD/data/"
FILES = ["Vietphrase.txt", "Names.txt", "HanViet.txt", "ZZLuatNhan.txt"]
DATA_DIR = os.path.join(os.path.expanduser("~"), ".screen-translator")
KEY = re.compile(r"^[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]{1,12}$")
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")


def download(name, dest):
    last = None
    for attempt in range(3):
        try:
            print("  tai %s ..." % name, flush=True)
            with urllib.request.urlopen(urllib.request.Request(BASE + name, headers={"User-Agent": "Mozilla/5.0"}), timeout=120) as r, open(dest, "wb") as f:
                while True:
                    b = r.read(1 << 20)
                    if not b:
                        break
                    f.write(b)
            return
        except Exception as e:
            last = e
            time.sleep(2)
    raise RuntimeError("Khong tai duoc %s: %s\nHay tai tay 4 file tu %s roi chay:  python build_dict.py --from-dir <thu muc>" % (name, last, BASE))


def lines(path):
    with open(path, encoding="utf-8-sig", errors="replace") as f:
        for ln in f:
            ln = ln.rstrip("\r\n").lstrip("\ufeff")
            if "=" in ln and not ln.startswith("#"):
                k, v = ln.split("=", 1)
                yield k.strip(), v


def first(v):
    v = v.split("/")[0].strip()
    return v if v and not CJK.search(v) else ""


def lower_first(v):
    """Gia tri VietPhrase thuong viet thuong; ha chu hoa dau, tru cum nhieu tu deu viet hoa (ten rieng: Bac Kinh)."""
    words = v.split()
    if not words or (all(w[:1].isupper() for w in words) and len(words) > 1):
        return v
    return v[:1].lower() + v[1:]


def build(out, from_dir=None, say=print):
    """Dung dict_big.db. Nem SystemExit/Exception khi loi (de server bao ra giao dien)."""
    os.makedirs(os.path.dirname(out), exist_ok=True)
    src = from_dir
    tmpdir = None
    if not src:
        import tempfile
        tmpdir = src = tempfile.mkdtemp(prefix="vp_")
        say("Đang tải dữ liệu từ GitHub…")
        for n in FILES:
            download(n, os.path.join(src, n))
        say("Đang dựng từ điển…")
    for n in FILES:
        if not os.path.exists(os.path.join(src, n)):
            raise RuntimeError("Thieu file %s trong %s" % (n, src))
    tmp = out + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    con = sqlite3.connect(tmp)
    con.executescript("""
        PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
        CREATE TABLE d(k TEXT PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE hv(k TEXT PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE rules(pat TEXT, tpl TEXT);
        CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);""")
    t0 = time.time()
    n_vp = n_names = n_hv = n_rules = 0
    # thu tu quan trong: INSERT OR IGNORE -> VietPhrase thang Names khi trung khoa
    batch = []
    for k, v in lines(os.path.join(src, "Vietphrase.txt")):
        v = first(v)
        if v and KEY.match(k):
            batch.append((k, lower_first(v)))
        if len(batch) >= 50000:
            con.executemany("INSERT OR IGNORE INTO d VALUES(?,?)", batch)
            n_vp += len(batch)
            batch = []
    con.executemany("INSERT OR IGNORE INTO d VALUES(?,?)", batch)
    n_vp += len(batch)
    batch = []
    for k, v in lines(os.path.join(src, "Names.txt")):
        v = v.strip()
        if v and KEY.match(k) and not CJK.search(v):
            batch.append((k, v))
        if len(batch) >= 50000:
            con.executemany("INSERT OR IGNORE INTO d VALUES(?,?)", batch)
            n_names += len(batch)
            batch = []
    con.executemany("INSERT OR IGNORE INTO d VALUES(?,?)", batch)
    n_names += len(batch)
    hv = [(k, first(v)) for k, v in lines(os.path.join(src, "HanViet.txt")) if len(k) == 1 and first(v)]
    con.executemany("INSERT OR IGNORE INTO hv VALUES(?,?)", hv)
    n_hv = len(hv)
    rules = [(k, v.split("/")[0].strip()) for k, v in lines(os.path.join(src, "ZZLuatNhan.txt")) if "{0}" in k and "{0}" in v]
    con.executemany("INSERT INTO rules VALUES(?,?)", rules)
    n_rules = len(rules)
    total = con.execute("SELECT COUNT(*) FROM d").fetchone()[0]
    con.execute("INSERT INTO meta VALUES('count', ?)", (str(total),))
    con.commit()
    con.execute("VACUUM")
    con.close()
    os.replace(tmp, out)
    if tmpdir:
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)
    msg = ("Xong sau %.0fs: %d cum (VietPhrase %d + ten rieng %d), %d chu Han Viet, %d luat -> %s (%.0f MB)"
           % (time.time() - t0, total, n_vp, n_names, n_hv, n_rules, out, os.path.getsize(out) / 1e6))
    say(msg)
    return total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from-dir")
    ap.add_argument("--out", default=os.path.join(DATA_DIR, "dict_big.db"))
    a = ap.parse_args()
    build(a.out, a.from_dir)


if __name__ == "__main__":
    main()
