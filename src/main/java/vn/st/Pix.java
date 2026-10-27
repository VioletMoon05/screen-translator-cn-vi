package vn.st;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cac ham xu ly diem anh thuan tuy (khong phu thuoc giao dien) de Pipeline gan moi ban dich voi CHINH diem anh goc cua no:
 * - ban dich chi duoc giu/hien khi diem anh ben duoi con nguyen (Patch + same);
 * - khi trang cuon (dich doc rigid) thi ban dich DI THEO chu thay vi bi xoa (findShift);
 * - chi nhung o man hinh KHONG giai thich duoc bang "dung yen" hoac "da cuon" moi can OCR lai (unexplained).
 * Toa do luon la px vat ly cua anh chup (da nhan he so DPI).
 */
final class Pix {
    private Pix() {}

    /** Nguong lech do sang de coi 1 diem anh la da doi. Chu thuong tuong phan >> 24. */
    static final int T = 24;

    static int lum(int p) {
        return (((p >> 16) & 255) * 30 + ((p >> 8) & 255) * 59 + (p & 255) * 11) / 100;
    }

    /** Mot khung chup: mang diem anh RGB (dung truc tiep mang cua BufferedImage neu duoc, tranh sao chep). */
    static final class Shot {
        final int w, h;
        final int[] px;

        Shot(int w, int h, int[] px) {
            this.w = w;
            this.h = h;
            this.px = px;
        }

        static Shot of(BufferedImage img) {
            int w = img.getWidth(), h = img.getHeight();
            int t = img.getType();
            if ((t == BufferedImage.TYPE_INT_RGB || t == BufferedImage.TYPE_INT_ARGB || t == BufferedImage.TYPE_INT_ARGB_PRE)
                    && img.getRaster().getDataBuffer() instanceof DataBufferInt db
                    && img.getRaster().getSampleModel() instanceof SinglePixelPackedSampleModel sm
                    && sm.getScanlineStride() == w
                    && img.getRaster().getSampleModelTranslateX() == 0 && img.getRaster().getSampleModelTranslateY() == 0
                    && db.getOffset() == 0 && db.getData().length >= w * h) {
                return new Shot(w, h, db.getData());
            }
            return new Shot(w, h, img.getRGB(0, 0, w, h, null, 0, w));
        }

        int lum(int x, int y) {
            return Pix.lum(px[y * w + x]);
        }
    }

    /** Anh xam tham chieu cua mot khoi chu luc OCR (dung de xac minh chu goc chua doi / tim lai sau khi cuon). */
    static final class Patch {
        final int w, h, step;
        final byte[] g;

        Patch(int w, int h, byte[] g) {
            this.w = w;
            this.h = h;
            this.g = g;
            long a = (long) w * h;
            this.step = a <= 1500 ? 1 : a <= 40_000 ? 2 : a <= 160_000 ? 3 : 4;
        }
    }

    /** Cat hinh chu nhat (da nam hoan toan trong khung) thanh Patch; null neu qua nho/ngoai khung. */
    static Patch grab(Shot s, int x, int y, int w, int h) {
        if (w < 4 || h < 4 || x < 0 || y < 0 || x + w > s.w || y + h > s.h) return null;
        byte[] g = new byte[w * h];
        for (int yy = 0; yy < h; yy++) {
            int row = (y + yy) * s.w + x, o = yy * w;
            for (int xx = 0; xx < w; xx++) g[o + xx] = (byte) lum(s.px[row + xx]);
        }
        return new Patch(w, h, g);
    }

    /**
     * Patch p co con nguyen tai (x, y) trong khung s khong? Lay mau luoi thua; cho phep toi da badPer1000/1000 mau lech.
     * Chu doi 1 ky tu van lech nhieu hon nguong nay nen duoc phat hien.
     */
    static boolean same(Patch p, Shot s, int x, int y, int tol, int badPer1000) {
        if (p == null || x < 0 || y < 0 || x + p.w > s.w || y + p.h > s.h) return false;
        int total = 0, bad = 0;
        for (int yy = 0; yy < p.h; yy += p.step) {
            int row = (y + yy) * s.w + x, o = yy * p.w;
            for (int xx = 0; xx < p.w; xx += p.step) {
                total++;
                if (Math.abs(lum(s.px[row + xx]) - (p.g[o + xx] & 255)) > tol) bad++;
            }
        }
        return bad <= Math.max(2, total * badPer1000 / 1000);
    }

    // ------------------------------------------------------------------ do do lech cuon
    /**
     * Tim do lech doc dy (px; dy>0: noi dung di xuong) sao cho cac khoi "lost" khop lai tai (x, y+dy).
     * Dung vai khoi lon lam "neo", bo phieu giua cac neo; tra ve 0 neu khong tin cay (mo ho / khong thay).
     */
    static int findShift(Shot cur, List<Item> lost, int maxAbs) {
        List<Item> cand = new ArrayList<>();
        for (Item it : lost) {
            if (it.ref != null && it.pw >= 24 && it.ph >= 10 && (long) it.pw * it.ph >= 600) cand.add(it);
        }
        if (cand.isEmpty()) return 0;
        cand.sort((a, b) -> Long.compare((long) b.pw * b.ph, (long) a.pw * a.ph));
        Map<Integer, Integer> votes = new HashMap<>();
        int used = 0;
        for (int i = 0; i < cand.size() && used < 4; i++) {
            List<Integer> c = candidates(cur, cand.get(i), maxAbs);
            if (c == null || c.isEmpty()) continue;      // khong thay hoac qua mo ho (chu lap lai) -> bo neo nay
            used++;
            for (int dy : c) votes.merge(dy, 1, Integer::sum);
        }
        if (used == 0) return 0;
        int best = 0, bestVotes = 0;
        boolean tie = false;
        for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestVotes) {
                best = e.getKey();
                bestVotes = e.getValue();
                tie = false;
            } else if (e.getValue() == bestVotes) {
                tie = true;
            }
        }
        if (tie || (used >= 2 && bestVotes < 2)) return 0;
        return best;
    }

    /** Cac do lech dy (khac 0) ma patch cua neo khop o vi tri moi; null neu qua nhieu ung vien (mo ho). */
    private static List<Integer> candidates(Shot cur, Item a, int maxAbs) {
        Patch p = a.ref;
        int sc = Math.max(2, p.step);
        int n = ((p.h + sc - 1) / sc) * ((p.w + sc - 1) / sc);
        int[] off = new int[n], ref = new int[n];
        int k = 0;
        for (int yy = 0; yy < p.h; yy += sc)
            for (int xx = 0; xx < p.w; xx += sc) {
                off[k] = yy * cur.w + xx;
                ref[k] = p.g[yy * p.w + xx] & 255;
                k++;
            }
        int limit = Math.max(2, n * 60 / 1000);
        int lo = Math.max(-maxAbs, -a.py), hi = Math.min(maxAbs, cur.h - a.ph - a.py);
        List<int[]> found = new ArrayList<>();
        for (int dy = lo; dy <= hi; dy++) {
            if (dy == 0) continue;
            int base = (a.py + dy) * cur.w + a.px;
            int bad = 0;
            for (int i = 0; i < n; i++) {
                if (Math.abs(lum(cur.px[base + off[i]]) - ref[i]) > 30 && ++bad > limit) break;
            }
            if (bad <= limit) found.add(new int[]{dy, bad});
        }
        if (found.size() > 40) return null;
        found.sort((x, y) -> Integer.compare(x[1], y[1]));
        List<Integer> out = new ArrayList<>();
        for (int[] f : found) {
            boolean near = false;
            for (int o : out) if (Math.abs(o - f[0]) <= 2) { near = true; break; }
            if (!near) out.add(f[0]);
            if (out.size() > 6) return null;
        }
        return out;
    }

    // ------------------------------------------------------------ o thay doi chua giai thich duoc
    /**
     * O luoi nao co noi dung doi ma KHONG giai thich duoc bang "dung yen" (prev) cung nhu "cuon dy" (prev dich dy).
     * Moi o lay mau 8x8 diem; o duoc coi la thay doi neu co >= 2 mau khong khop gia thuyet nao.
     */
    static boolean[] unexplained(Shot prev, Shot cur, int dy, int cell, int cols, int rows) {
        final int N = 8;
        boolean[] d = new boolean[cols * rows];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int cnt = 0;
                for (int j = 0; j < N && cnt < 2; j++) {
                    int y = Math.min(cur.h - 1, r * cell + (2 * j + 1) * cell / (2 * N));
                    for (int i = 0; i < N; i++) {
                        int x = Math.min(cur.w - 1, c * cell + (2 * i + 1) * cell / (2 * N));
                        int cv = lum(cur.px[y * cur.w + x]);
                        if (Math.abs(cv - lum(prev.px[y * prev.w + x])) <= T) continue;     // dung yen: giai thich duoc
                        if (dy != 0) {
                            int y2 = y - dy;
                            if (y2 >= 0 && y2 < prev.h && Math.abs(cv - lum(prev.px[y2 * prev.w + x])) <= T) continue;   // cuon: giai thich duoc
                        }
                        cnt++;
                    }
                }
                d[r * cols + c] = cnt >= 2;
            }
        }
        return d;
    }

    /** Vung r (toa do khung truoc) da di chuyen theo dy (true) hay dung yen (false)? Bo phieu tren cac diem phan biet duoc. */
    static boolean movedWith(Shot prev, Shot cur, Rectangle r, int dy) {
        int vote = 0;
        int x1 = Math.min(r.x + r.width, prev.w), y1 = Math.min(r.y + r.height, prev.h);
        for (int y = Math.max(0, r.y); y < y1; y += 3) {
            int y2 = y + dy;
            if (y2 < 0 || y2 >= cur.h) continue;
            for (int x = Math.max(0, r.x); x < x1; x += 3) {
                int pv = lum(prev.px[y * prev.w + x]);
                int cs = lum(cur.px[y * cur.w + x]);
                int cm = lum(cur.px[y2 * cur.w + x]);
                if (Math.abs(cs - cm) <= T) continue;           // hai gia thuyet cho cung ket qua: khong phan biet
                if (Math.abs(pv - cm) <= T) vote++;
                else if (Math.abs(pv - cs) <= T) vote--;
            }
        }
        return vote > 0;
    }

    /**
     * Patch p dat tai (x, y) trong khung s: tim doan hang lien tuc lon nhat con khop (cac hang ngoai khung tinh la khong khop).
     * Tra ve {hangDau, hangCuoi} (bao gom) hoac null neu khong con hang nao khop. Dung de nhan ra khoi bi header/mep che mot phan.
     */
    static int[] visibleRows(Patch p, Shot s, int x, int y, int tol) {
        int bestS = -1, bestE = -1, curS = -1;
        for (int yy = 0; yy <= p.h; yy++) {
            boolean ok = false;
            if (yy < p.h) {
                int ry = y + yy;
                if (ry >= 0 && ry < s.h && x >= 0 && x + p.w <= s.w) {
                    int total = 0, bad = 0, row = ry * s.w + x, o = yy * p.w;
                    for (int xx = 0; xx < p.w; xx += p.step) {
                        total++;
                        if (Math.abs(lum(s.px[row + xx]) - (p.g[o + xx] & 255)) > tol) bad++;
                    }
                    ok = bad <= Math.max(1, total * 30 / 1000);
                }
            }
            if (ok) {
                if (curS < 0) curS = yy;
            } else if (curS >= 0) {
                if (bestS < 0 || yy - 1 - curS > bestE - bestS) { bestS = curS; bestE = yy - 1; }
                curS = -1;
            }
        }
        return bestS < 0 ? null : new int[]{bestS, bestE};
    }

    /** Vung r giua hai khung co dung yen khong (>= 95% mau khong doi)? Dung nhan ra vat che tinh nhu header. */
    static boolean staticRegion(Shot prev, Shot cur, Rectangle r) {
        int x0 = Math.max(0, r.x), y0 = Math.max(0, r.y), x1 = Math.min(Math.min(prev.w, cur.w), r.x + r.width), y1 = Math.min(Math.min(prev.h, cur.h), r.y + r.height);
        int total = 0, bad = 0;
        for (int y = y0; y < y1; y += 2)
            for (int x = x0; x < x1; x += 2) {
                total++;
                if (Math.abs(lum(prev.px[y * prev.w + x]) - lum(cur.px[y * cur.w + x])) > T) bad++;
            }
        return total > 0 && bad * 20 <= total;
    }
}
