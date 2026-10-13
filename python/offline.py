# -*- coding: utf-8 -*-
"""Dich Trung -> Viet OFFLINE bang tu dien (khop cum dai nhat), khong goi mang.

- Tu dien goc: dict_zh_vi.tsv (cung thu muc / trong goi PyInstaller).
- Tu dien rieng cua ban: <data-dir>/glossary.tsv  (cung dinh dang, ghi de tu dien goc, tu nap lai khi sua file).
- Chu phon the duoc doi sang gian the (OpenCC, neu da cai) chi de TRA CUU.
"""
import os
import re
import sys
import threading

CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
PUNCT = {"，": ", ", "。": ". ", "！": "! ", "？": "? ", "：": ": ", "；": "; ", "、": ", ",
         "（": " (", "）": ") ", "“": '"', "”": '"', "‘": "'", "’": "'", "「": '"', "」": '"',
         "『": '"', "』": '"', "《": '"', "》": '"', "【": "[", "】": "] ", "…": "...", "—": " - ", "～": "~"}

_lock = threading.Lock()
_base = {}
_user = {}
_user_mtime = None
_maxlen = 1
_t2s = None
_t2s_tried = False
DATA_DIR = os.path.join(os.path.expanduser("~"), ".screen-translator")


def _app_dir():
    return getattr(sys, "_MEIPASS", None) or os.path.dirname(os.path.abspath(__file__))


def _read_tsv(path):
    d = {}
    try:
        with open(path, encoding="utf-8-sig") as f:
            for line in f:
                line = line.rstrip("\r\n")
                if not line.strip() or line.lstrip().startswith("#") or "\t" not in line:
                    continue
                k, v = line.split("\t", 1)
                k = k.strip()
                if k:
                    d[k] = v.strip()
    except OSError:
        pass
    return d


def load(data_dir=None):
    """Nap tu dien goc (1 lan) va tu dien rieng (nap lai khi file doi)."""
    global _base, _user, _user_mtime, _maxlen, DATA_DIR
    if data_dir:
        DATA_DIR = data_dir
    with _lock:
        if not _base:
            for p in (os.path.join(_app_dir(), "dict_zh_vi.tsv"), os.path.join(os.path.dirname(_app_dir()), "python", "dict_zh_vi.tsv")):
                if os.path.exists(p):
                    _base = _read_tsv(p)
                    break
        up = os.path.join(DATA_DIR, "glossary.tsv")
        mt = os.path.getmtime(up) if os.path.exists(up) else None
        if mt != _user_mtime:
            _user = _read_tsv(up) if mt else {}
            _user_mtime = mt
        keys = list(_base) + list(_user)
        _maxlen = max([len(k) for k in keys] + [1])


def size():
    load()
    return len(_base), len(_user)


def _to_simplified(text):
    global _t2s, _t2s_tried
    if not _t2s_tried:
        _t2s_tried = True
        try:
            from opencc import OpenCC
            _t2s = OpenCC("t2s")
        except Exception:
            _t2s = None
    if _t2s is None:
        return text
    try:
        out = _t2s.convert(text)
        return out if len(out) == len(text) else text   # chi dung khi doi tung chu mot (giu vi tri)
    except Exception:
        return text


def _lookup(w):
    if w in _user:
        return _user[w]
    return _base.get(w)


def exact(text):
    """Khop nguyen ca doan (tu dien rieng hoac goc). None neu khong co."""
    load()
    t = text.strip()
    v = _lookup(t)
    if v is None:
        v = _lookup(_to_simplified(t))
    return _cap(v) if v else None


def _cap(s):
    for i, ch in enumerate(s):
        if ch.isalpha():
            return s[:i] + ch.upper() + s[i + 1:]
    return s


def _lower_first(v):
    """Ha chu hoa dau tu (gia tri tu dien viet hoa nhu nhan UI) khi nam giua cau; giu ten rieng (moi tu deu viet hoa)."""
    words = v.split()
    if not words or all(w[:1].isupper() for w in words) and len(words) > 1:
        return v
    return v[:1].lower() + v[1:]


def cjk_count(text):
    return len(CJK.findall(text))


def translate(text):
    """Tra ve (ban_dich, do_phu 0..1). Doan khong co trong tu dien giu nguyen chu Trung."""
    load()
    src = _to_simplified(text.strip())
    n = len(src)
    out = []
    covered = total = 0
    i = 0
    while i < n:
        ch = src[i]
        if CJK.match(ch):
            total_hit = None
            for L in range(min(_maxlen, n - i), 0, -1):
                w = src[i:i + L]
                v = _lookup(w)
                if v is not None:
                    total_hit = (L, v)
                    break
            if total_hit:
                L, v = total_hit
                covered += L
                total += L
                if v:
                    out.append(_lower_first(v))
                i += L
            else:   # chu la: gom ca chuoi chu la thanh 1 cum
                j = i
                while j < n and CJK.match(src[j]) and _lookup(src[j]) is None:
                    j += 1
                j = max(j, i + 1)
                total += j - i
                out.append(text.strip()[i:j])   # giu chu goc (co the la phon the)
                i = j
        elif ch in PUNCT:
            out.append("\x00" + PUNCT[ch])
            i += 1
        elif ch.isspace():
            i += 1
        else:                                  # chu Latin / so / ky tu khac: giu nguyen ca cum
            j = i
            while j < n and not CJK.match(src[j]) and src[j] not in PUNCT and not src[j].isspace():
                j += 1
            out.append(src[i:j])
            i = j
    s = ""
    for tok in out:
        if tok.startswith("\x00"):
            s = s.rstrip() + tok[1:]
        else:
            s += (" " if s and not s.endswith((" ", "(", '"', "[")) else "") + tok
    s = re.sub(r"\s+", " ", s).strip()
    cov = 1.0 if total == 0 else covered / total
    s = re.sub(r"([.!?]\s+)(\w)", lambda m: m.group(1) + m.group(2).upper(), s)   # viet hoa dau cau
    return _cap(s), cov
