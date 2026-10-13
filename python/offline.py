# -*- coding: utf-8 -*-
"""Dich Trung -> Viet OFFLINE, khong goi mang.

Nguon du lieu, uu tien tu cao xuong thap khi trung do dai khop:
  1. <data-dir>/glossary.tsv   - tu dien rieng cua ban (tu nap lai khi sua file)
  2. dict_zh_vi.tsv            - tu dien chinh tay di kem (nhan UI, game, web, tu thong dung)
  3. <data-dir>/dict_big.db    - tu dien lon ~1,2 trieu cum + ten rieng + Han Viet (tao bang build_dict.py)
Khop CUM DAI NHAT truoc; chu la -> doc theo am Han Viet (ten rieng viet hoa); co luat dao trat tu va "我的X -> X cua toi".
"""
import os
import re
import sqlite3
import sys
import threading

CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
PUNCT = {"，": ", ", "。": ". ", "！": "! ", "？": "? ", "：": ": ", "；": "; ", "、": ", ",
         "（": " (", "）": ") ", "“": '"', "”": '"', "‘": "'", "’": "'", "「": '"', "」": '"',
         "『": '"', "』": '"', "《": '"', "》": '"', "【": "[", "】": "] ", "…": "...", "—": " - ", "～": "~"}
PRON = {"我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "咱们", "您"}
SEP = "，。！？；：、,.!?;:\\s"

_lock = threading.Lock()
_base, _user = {}, {}
_user_mtime = None
_ml = 12
_t2s = None
_t2s_tried = False
DATA_DIR = os.path.join(os.path.expanduser("~"), ".screen-translator")
_big_mtime = None
_big_count = 0
_big_gen = 0
_rules = []
_tls = threading.local()


def _app_dir():
    return getattr(sys, "_MEIPASS", None) or os.path.dirname(os.path.abspath(__file__))


def _big_path():
    return os.path.join(DATA_DIR, "dict_big.db")


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
    """Nap tu dien chinh (1 lan), glossary rieng va tu dien lon (nap lai khi file doi)."""
    global _base, _user, _user_mtime, _ml, DATA_DIR, _big_mtime, _big_count, _big_gen, _rules
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
        _ml = min(16, max([12] + [len(k) for k in list(_base) + list(_user)]))
        bp = _big_path()
        bm = os.path.getmtime(bp) if os.path.exists(bp) else None
        if bm != _big_mtime:
            _big_mtime = bm
            _big_gen += 1                        # buoc cac luong mo lai ket noi
            _big_count, _rules = 0, []
            if bm:
                try:
                    c = sqlite3.connect(bp)
                    _big_count = int(c.execute("SELECT v FROM meta WHERE k='count'").fetchone()[0])
                    rules = c.execute("SELECT pat, tpl FROM rules").fetchall()
                    c.close()
                    _rules = _compile_rules(rules)
                except Exception:
                    _big_count, _rules = 0, []


def _compile_rules(rows):
    out = []
    for pat, tpl in rows:
        parts = pat.split("{0}")
        if len(parts) != 2 or not any(parts):
            continue
        rx = re.compile(re.escape(parts[0]) + "([^%s]{1,12}?)" % SEP + re.escape(parts[1]))
        out.append((rx, tpl, [p for p in parts if p], len(pat)))
    out.sort(key=lambda r: -r[3])
    return out


def size():
    """(so muc tu dien chinh, so muc glossary rieng, so muc tu dien lon)"""
    load()
    return len(_base), len(_user), _big_count


def _conn():
    c = getattr(_tls, "c", None)
    if c is not None and getattr(_tls, "gen", -1) == _big_gen:
        return c
    if not _big_count:
        return None
    try:
        c = sqlite3.connect(_big_path(), check_same_thread=False)
        c.execute("PRAGMA query_only=1")
    except Exception:
        return None
    _tls.c, _tls.gen = c, _big_gen
    return c


def _query(table, keys):
    c = _conn()
    out = {}
    if c is None or not keys:
        return out
    keys = list(keys)
    for i in range(0, len(keys), 500):
        chunk = keys[i:i + 500]
        try:
            for k, v in c.execute("SELECT k, v FROM %s WHERE k IN (%s)" % (table, ",".join("?" * len(chunk))), chunk):
                out[k] = v
        except Exception:
            break
    return out


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


def _lookup_small(w):
    if w in _user:
        return _user[w]
    return _base.get(w)


def exact(text):
    """Khop nguyen ca doan trong glossary/tu dien chinh (nhan UI). None neu khong co."""
    load()
    t = text.strip()
    v = _lookup_small(t)
    if v is None:
        v = _lookup_small(_to_simplified(t))
    return _cap(v) if v else None


def _cap(s):
    for i, ch in enumerate(s):
        if ch.isalpha():
            return s[:i] + ch.upper() + s[i + 1:]
    return s


def _lower_first(v):
    """Nhan UI viet hoa chu dau -> ha xuong khi nam giua cau; giu ten rieng (moi tu deu viet hoa)."""
    words = v.split()
    if not words or (all(w[:1].isupper() for w in words) and len(words) > 1):
        return v
    return v[:1].lower() + v[1:]


_MODERN = [(re.compile(r"\bcác ngươi\b"), "các bạn"), (re.compile(r"\bngươi\b"), "bạn"), (re.compile(r"\bnàng\b"), "cô ấy"),
           (re.compile(r"\bhắn\b"), "anh ấy"), (re.compile(r"(?<!chúng )(?<!bọn )(?<!đại )\bta\b"), "tôi")]


def _modern(v):
    """Doi dai tu co cua VietPhrase sang cach dung hien nay (ngươi->bạn, ta->tôi...)."""
    for rx, rep in _MODERN:
        v = rx.sub(rep, v)
    return v


def cjk_count(text):
    return len(CJK.findall(text))


# ------------------------------------------------------------------ dich
class _Ctx:
    pass


def _prepare(src):
    """Tra cuu hang loat tu dien lon cho moi chuoi con CJK + am Han Viet cua tung chu."""
    ctx = _Ctx()
    cands, chars = set(), set()
    n = len(src)
    i = 0
    while i < n:
        if not CJK.match(src[i]):
            i += 1
            continue
        j = i
        while j < n and CJK.match(src[j]):
            j += 1
        for a in range(i, j):
            chars.add(src[a])
            for L in range(1, min(_ml, j - a) + 1):
                cands.add(src[a:a + L])
        i = j
    ctx.big = _query("d", cands)
    ctx.hv = _query("hv", chars)
    return ctx


def _greedy(src, orig, ctx):
    """Khop cum dai nhat. Tra ve (tokens, so_chu_co_nghia, tong_chu_Han). token = (zh, vn, loai)."""
    n = len(src)
    toks = []
    covered = total = 0
    i = 0
    while i < n:
        ch = src[i]
        if CJK.match(ch):
            j = i
            while j < n and CJK.match(src[j]):
                j += 1
            hit = None
            for L in range(min(_ml, j - i), 0, -1):
                w = src[i:i + L]
                v = _lookup_small(w)
                src_small = v is not None
                if v is None:
                    v = ctx.big.get(w)
                if v is not None:
                    hit = (L, v, src_small)
                    break
            if hit:
                L, v, small = hit
                covered += L if (L >= 2 or small) else 0.5      # chu don le tu tu dien lon: tin cay thap
                total += L
                toks.append((src[i:i + L], _lower_first(v) if small else _modern(v), "w"))
                i += L
            else:
                total += 1
                toks.append((ch, ctx.hv.get(ch, orig[i]), "u"))     # chu la: se doc theo am Han Viet
                i += 1
        elif ch in PUNCT:
            toks.append((ch, PUNCT[ch], "p"))
            i += 1
        elif ch.isspace():
            i += 1
        else:                                                       # chu Latin / so / ky tu khac: giu nguyen
            j = i
            while j < n and not CJK.match(src[j]) and src[j] not in PUNCT and not src[j].isspace():
                j += 1
            toks.append((src[i:j], src[i:j], "o"))
            i = j
    return toks, covered, total


def _tr(src, orig, ctx, depth=0):
    """Ap dung luat dao trat tu (neu co) roi khop cum. Tra ve (tokens, covered, total)."""
    if depth < 3 and _rules:
        for rx, tpl, parts, _ in _rules:
            if all(p in src for p in parts):
                m = rx.search(src)
                if m:
                    s, e = m.span()
                    lt, lc, ln = _tr(src[:s], orig[:s], ctx, depth + 1)
                    mt, mc, mn = _tr(src[m.start(1):m.end(1)], orig[m.start(1):m.end(1)], ctx, depth + 1)
                    rt, rc, rn = _tr(src[e:], orig[e:], ctx, depth + 1)
                    fixed = len(src[s:e]) - (m.end(1) - m.start(1))
                    vn = _modern(tpl).replace("{0}", _join(mt, cap=False))
                    return lt + [(src[s:e], vn, "w")] + rt, lc + mc + rc + fixed, ln + mn + rn + fixed
    return _greedy(src, orig, ctx)


def _merge_unknown(toks):
    """Gom chu la lien tiep: 2-4 chu -> ten rieng (viet hoa moi am), con lai viet thuong."""
    out, run = [], []

    def flush():
        if run:
            syl = [t[1] for t in run]
            if 2 <= len(syl) <= 4:
                syl = [s[:1].upper() + s[1:] for s in syl]
            out.append(("".join(t[0] for t in run), " ".join(syl), "n"))
            run.clear()

    for t in toks:
        if t[2] == "u":
            run.append(t)
        else:
            flush()
            out.append(t)
    flush()
    return out


def _join(toks, cap=True):
    toks = _merge_unknown(toks)
    # "我的书" -> "sach cua toi": dai tu + 的 + tu ke tiep
    k = 0
    fixed = []
    while k < len(toks):
        if (k + 2 < len(toks) and toks[k][0] in PRON and toks[k + 1][0] == "的"
                and toks[k + 2][2] in ("w", "n") and toks[k + 2][1]):
            fixed.append((toks[k + 2][0], toks[k + 2][1] + " của " + toks[k][1], "w"))
            k += 3
        else:
            fixed.append(toks[k])
            k += 1
    s = ""
    for _, vn, kind in fixed:
        if kind == "p":
            s = s.rstrip() + vn
        elif vn:
            s += (" " if s and not s.endswith((" ", "(", '"', "[")) else "") + vn
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"([.!?]\s+)(\w)", lambda m: m.group(1) + m.group(2).upper(), s)   # viet hoa dau cau
    return _cap(s) if cap else s


def translate(text):
    """Tra ve (ban_dich, do_phu 0..1). Do phu = ty le chu Han co trong tu dien (chu doc theo Han Viet khong tinh)."""
    load()
    orig = text.strip()
    src = _to_simplified(orig)
    ctx = _prepare(src)
    toks, covered, total = _tr(src, orig, ctx)
    cov = 1.0 if total == 0 else covered / total
    return _join(toks), cov
