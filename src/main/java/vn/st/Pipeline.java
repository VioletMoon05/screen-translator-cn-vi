package vn.st;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Luong xu ly: chup vung man hinh -> moi ban dich gan voi CHINH diem anh goc cua no (Item.ref).
 * - Ban dich chi hien khi diem anh ben duoi con nguyen; chu doi thi go ngay, chu khac thi OCR lai.
 * - Trang cuon (dich doc rigid): ban dich DI THEO chu (Pix.findShift), chi OCR phan chu moi lo ra.
 * - Ket qua OCR den tre duoc kiem tung khoi voi khung moi nhat (co bu do cuon xay ra trong luc cho),
 *   khoi nao khong khop thi bo rieng khoi do va dat lai hang doi -> khong con ban dich "ma" sai vi tri.
 * - Vung dang doi lien tuc cho on dinh (SETTLE) truoc khi OCR; chi OCR vung nho khi dang doi, vung lon cho lau hon.
 */
public class Pipeline {
    private static final int CELL = 40;              // kich thuoc o luoi (logical px)
    private static final long SETTLE_MS = 250;       // vung phai khong doi chung nay moi OCR
    private static final long MAX_WAIT_MS = 900;     // vung nho doi lien tuc (video...) van OCR sau chung nay
    private static final long MAX_WAIT_BIG_MS = 3000; // vung lon (> 35% khung) doi lien tuc: cho lau hon cho khoi OCR phi
    private static final long SWEEP_MS = 12000;      // quet an toan dinh ky bat chu moi nho khi man hinh dung yen
    private static final long RETRY_MIN_MS = 2000, RETRY_MAX_MS = 15000;
    private static final int KEEP_TOL = 30, KEEP_BAD = 5;    // chu con nguyen: <= 0.5% mau lech
    private static final int MOVE_TOL = 40, MOVE_BAD = 10;   // chu da dich chuyen: <= 1.0% mau lech

    private static final class Pend {
        Rectangle r;
        long first, last;
        boolean force;     // OCR ngay, khong tinh vung "khong co chu" (lan dau, dich thu cong, quet an toan, thu lai)
        boolean bg;        // viec nen (quet an toan): chiu gioi han CPU nhu vung "nong"

        Pend(Rectangle r, long first, long last, boolean force, boolean bg) {
            this.r = r;
            this.first = first;
            this.last = last;
            this.force = force;
            this.bg = bg;
        }
    }

    /**
     * Vung doi lien tuc (video, quang cao, hoat anh) ma OCR khong thay chu nao: khong OCR lai lien tuc ma lui dan
     * 3s -> 6s -> 12s ... toi da 60s. Vung yen tinh 2,5s, hoac trang cuon, hoac OCR thay chu thi duoc go bo ngay.
     */
    private static final class Zone {
        Rectangle r;
        int strikes;
        long lastTouch, lastOcr;

        long backoff() {
            return Math.min(60_000L, 3_000L << Math.min(5, strikes - 1));
        }
    }

    private final Config cfg;
    private final PythonBackend backend;
    private final OverlayWindow overlay;
    private final BiConsumer<String, String> status;
    private Robot robot;

    private volatile boolean running, paused, manualRequest;
    private volatile long retryAt = 0, retryDelay = RETRY_MIN_MS, lastDispatch = 0;
    private volatile int generation = 0;
    private Thread loop;
    private final ExecutorService ocrPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ocr-dispatch");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final List<Pend> pending = new ArrayList<>();   // vung can OCR (px vat ly), bao ve boi chinh no
    private final List<Zone> zones = new ArrayList<>();      // vung "khong co chu", cung bao ve boi pending
    private volatile long coolUntil = 0;                      // truoc moc nay khong OCR vung "nong" (gioi han CPU)
    private volatile boolean pickHot, pickZoneable, pickForce;           // dac diem cua lan pickReady gan nhat
    private volatile boolean stepActive;                      // khung vua roi co gi thay doi khong
    private int quietFrames = 0;
    private static final long BUDGET_WINDOW_MS = 10_000;
    private final java.util.ArrayDeque<long[]> ocrLog = new java.util.ArrayDeque<>();   // {thoi diem xong, thoi gian OCR ms}

    /** Da dung het ngan sach OCR trong cua so 10 s chua (gioi han CPU ben vung)? */
    private boolean overBudget(long now) {
        double cap = cfg.ocrBudget();
        if (cap >= 1.0) return false;
        long busy = 0;
        synchronized (ocrLog) {
            while (!ocrLog.isEmpty() && now - ocrLog.peekFirst()[0] > BUDGET_WINDOW_MS) ocrLog.pollFirst();
            for (long[] e : ocrLog) busy += e[1];
        }
        return busy >= cap * BUDGET_WINDOW_MS;
    }
    private volatile int pendCell = CELL;
    private Rectangle region;

    // Chi truy cap ben trong overlay.transform(...) (cung mot khoa voi danh sach khoi) de loop va OCR thay cung mot trang thai.
    private Pix.Shot latest;
    private long frameSeq;
    private final List<long[]> shiftLog = new ArrayList<>();   // {seq, dy} moi lan phat hien trang cuon

    public Pipeline(Config cfg, PythonBackend backend, OverlayWindow overlay, BiConsumer<String, String> status) {
        this.cfg = cfg;
        this.backend = backend;
        this.overlay = overlay;
        this.status = status;
        try {
            robot = new Robot();
        } catch (AWTException e) {
            status.accept("err", "Không thể chụp màn hình: " + e.getMessage());
        }
    }

    public boolean isRunning() { return running; }
    public boolean isPaused() { return paused; }
    public Rectangle region() { return region; }

    public synchronized void start(Rectangle r) {
        stop();
        if (robot == null) return;
        region = new Rectangle(r);
        generation++;
        running = true;
        paused = false;
        synchronized (pending) {
            pending.clear();
            zones.clear();
        }
        coolUntil = 0;
        synchronized (ocrLog) {
            ocrLog.clear();
        }
        overlay.transform(items -> {
            latest = null;
            frameSeq = 0;
            shiftLog.clear();
            return new ArrayList<>();
        });
        retryAt = 0;
        retryDelay = RETRY_MIN_MS;
        try {
            overlay.open(region);
        } catch (Throwable t) {
            AppLog.error("overlay.open that bai", t);
            status.accept("err", "Overlay lỗi: " + t);
        }
        overlay.setBadge("Đang quét…", 0);
        final int gen = generation;
        final Rectangle reg = new Rectangle(region);
        loop = new Thread(() -> runLoop(gen, reg), "scan-loop");
        loop.setDaemon(true);
        loop.start();
        AppLog.info("Bat dau dich vung " + r);
        status.accept("state", "Đang dịch vùng " + r.width + "×" + r.height + " (tự động phát hiện và dịch liên tục)");
    }

    public synchronized void stop() {
        running = false;
        generation++;
        if (loop != null) loop.interrupt();
        loop = null;
        overlay.setBadge("", 0);
        overlay.closeOverlay();
        status.accept("state", "Đã dừng");
    }

    public void togglePause() {
        if (!running) return;
        paused = !paused;
        overlay.setBadge(paused ? "Tạm dừng" : "Đang quét…", paused ? 2 : 0);
        status.accept("state", paused ? "Tạm dừng quét" : "Đang dịch vùng " + region.width + "×" + region.height);
    }

    public void toggleOverlay() {
        overlay.setUserVisible(!overlay.isUserVisible());
    }

    /** Che do thu cong: dich ngay noi dung hien tai. */
    public void translateNow() {
        manualRequest = true;
    }

    // ---------------------------------------------------------------- vong lap quet
    private void runLoop(int gen, Rectangle reg) {
        Pix.Shot prev = null;
        lastDispatch = System.currentTimeMillis();
        try {
            Thread.sleep(150); // doi cua so thu nho bien mat han khoi man hinh
        } catch (InterruptedException e) {
            return;
        }

        while (running && gen == generation) {
            long t0 = System.currentTimeMillis();
            try {
                if (!paused) {
                    BufferedImage img = capture(reg);
                    Pix.Shot cur = Pix.Shot.of(img);
                    double scale = img.getWidth() / (double) reg.width;
                    int cell = Math.max(8, (int) Math.round(CELL * scale));
                    pendCell = cell;
                    Rectangle whole = new Rectangle(0, 0, cur.w, cur.h);

                    long seq = step(prev, cur, scale, cell, whole, t0);
                    prev = cur;

                    if (manualRequest) pendNow(whole);
                    boolean idle = !inFlight.get();

                    // Thu lai sau loi va quet dinh ky an toan (quet la viec nen: chiu gioi han CPU)
                    if (idle && pendingEmpty()) {
                        boolean retry = retryAt != 0 && t0 >= retryAt;
                        boolean sweep = cfg.autoTranslate && (t0 - lastDispatch > sweepMs());
                        if (retry) {
                            pendNow(whole);
                            retryAt = 0;
                        } else if (sweep) {
                            pendBackground(whole);
                            lastDispatch = t0;
                        }
                    }

                    boolean allowed = cfg.autoTranslate || manualRequest;
                    if (allowed && idle) {
                        Rectangle pick = pickReady(t0, cur.w, cur.h, t0 < coolUntil, overBudget(t0));
                        if (pick != null) {
                            dispatch(gen, img, cur, scale, pick, seq, pickHot, pickZoneable, pickForce);
                            lastDispatch = t0;
                            manualRequest = false;
                        }
                    }
                    boolean busy = stepActive || !idle || !pendingEmpty();
                    quietFrames = busy ? 0 : quietFrames + 1;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                AppLog.error("Loi vong quet", e);
                status.accept("err", "Lỗi quét: " + e);
                overlay.setBadge("Lỗi quét", 3);
            }

            long interval = cfg.scanIntervalMs;
            if (cfg.hideOverlayWhileCapture || !overlay.isCaptureExcluded()) {
                interval = Math.max(interval, 400);
            } else if (interval <= 200) {
                // Man hinh dung yen mot luc: gian nhip quet de bot CPU (tu nhanh lai ngay khi co thay doi)
                int mult = 1;
                if (cfg.cpuMode == 1) mult = quietFrames >= 100 ? 5 : quietFrames >= 40 ? 4 : quietFrames >= 10 ? 2 : 1;
                else if (cfg.cpuMode == 0) mult = quietFrames >= 80 ? 3 : quietFrames >= 20 ? 2 : 1;
                interval = Math.min(500, interval * mult);
            }
            long sleep = interval - (System.currentTimeMillis() - t0);
            try {
                Thread.sleep(Math.max(15, sleep));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private long sweepMs() {
        long base = cfg.lowEnd ? 3 * SWEEP_MS : SWEEP_MS;
        return cfg.cpuMode == 1 ? base * 2 : base;
    }

    /**
     * Xu ly mot khung moi: giu / dich theo / go cac ban dich, roi dua vung can OCR vao hang doi. Tra ve so thu tu khung.
     */
    private long step(Pix.Shot prev, Pix.Shot cur, double scale, int cell, Rectangle whole, long now) {
        final boolean fresh = prev == null || prev.w != cur.w || prev.h != cur.h;
        final int[] dyOut = {0};
        final long[] seqOut = {0};
        final List<Rectangle> drops = new ArrayList<>();

        overlay.transform(items -> {
            seqOut[0] = ++frameSeq;
            latest = cur;
            if (fresh) {
                shiftLog.clear();
                return new ArrayList<>();
            }
            List<Item> keep = new ArrayList<>(items.size()), lost = new ArrayList<>();
            for (Item it : items) {
                if (Pix.same(it.ref, cur, it.px, it.py, KEEP_TOL, KEEP_BAD)) keep.add(it);
                else lost.add(it);
            }
            if (lost.isEmpty()) return items;      // khong doi gi: giu nguyen danh sach (overlay khong phai ve lai)
            int dy = lost.isEmpty() ? 0 : Pix.findShift(cur, lost, Math.max(8, cur.h - 8));
            for (Item it : lost) {
                if (dy != 0 && Pix.same(it.ref, cur, it.px, it.py + dy, MOVE_TOL, MOVE_BAD)) {
                    keep.add(it.movedTo(it.py + dy, scale));
                } else {
                    Rectangle f = footprint(it, dy, prev, cur, scale, whole);
                    if (f != null) drops.add(f);
                }
            }
            if (dy != 0) {
                shiftLog.add(new long[]{frameSeq, dy});
                while (shiftLog.size() > 600) shiftLog.remove(0);
            }
            dyOut[0] = dy;
            return keep;
        });

        if (fresh) {
            pendNow(whole);
            stepActive = true;
            return seqOut[0];
        }
        int dy = dyOut[0];
        if (dy != 0) {
            shiftPending(prev, cur, dy, whole);
            clearAllZones();                  // cuon: noi dung doi cho, vung "khong co chu" cu het y nghia
        }

        int cols = (cur.w + cell - 1) / cell, rows = (cur.h + cell - 1) / cell;
        boolean[] d = Pix.unexplained(prev, cur, dy, cell, cols, rows);
        List<Rectangle> cl = clusters(d, cols, rows, cell, whole);
        for (Rectangle c : cl) pend(c, now);
        for (Rectangle r : drops) pend(r, now);
        stepActive = dy != 0 || !cl.isEmpty() || !drops.isEmpty();
        return seqOut[0];
    }

    /**
     * Vung can OCR lai cho mot khoi vua mat khop. Neu khoi da cuon (dy != 0) va chi con mot doan lien tuc sat mep khop
     * (bi header / mep khung che mot phan) thi chi xep lai doan con thay, va chi khi du >= 1,5 dong; con lai bo, vi OCR
     * nua dong cho ban dich rac va chiem luot OCR cua chu moi.
     */
    private static Rectangle footprint(Item it, int dy, Pix.Shot prev, Pix.Shot cur, double scale, Rectangle whole) {
        Rectangle at = new Rectangle(it.px, it.py + dy, it.pw, it.ph);
        if (dy == 0) {
            Rectangle f = at.intersection(whole);
            return f.isEmpty() ? null : f;
        }
        int[] v = Pix.visibleRows(it.ref, cur, at.x, at.y, MOVE_TOL);
        if (v == null) {
            Rectangle f = at.intersection(whole);
            if (f.isEmpty() || !whole.contains(at)) return null;       // roi han khung -> bo
            return Pix.staticRegion(prev, cur, at) ? null : f;         // bi vat che tinh (header) che han -> bo; doi noi dung -> OCR lai
        }
        boolean touchesEdge = v[0] <= 2 || v[1] >= it.ph - 3;
        if (v[0] <= 2 && v[1] >= it.ph - 3) return at.intersection(whole).isEmpty() ? null : at.intersection(whole);
        if (!touchesEdge) {
            Rectangle f = at.intersection(whole);
            return f.isEmpty() ? null : f;
        }
        int len = v[1] - v[0] + 1;
        if (len < 1.5 * it.lh * scale) return null;
        Rectangle f = new Rectangle(at.x, at.y + v[0], at.width, len).intersection(whole);
        return f.isEmpty() ? null : f;
    }

    // ---------------------------------------------------------------- hang doi vung can OCR
    private boolean pendingEmpty() {
        synchronized (pending) {
            return pending.isEmpty();
        }
    }

    /** Them vung can OCR, gop voi vung ke ben; "doi lan cuoi" duoc cap nhat de cho on dinh. */
    private void pend(Rectangle r, long now) {
        add(r, now, now, false, false);
    }

    /** Vung can OCR ngay (khong cho on dinh, khong tinh vung "khong co chu"). */
    private void pendNow(Rectangle r) {
        add(r, 0, 0, true, false);
    }

    /** Viec nen (quet an toan): OCR ngay nhung chiu gioi han CPU. */
    private void pendBackground(Rectangle r) {
        add(r, 0, 0, true, true);
    }

    private void add(Rectangle r0, long first, long last, boolean force, boolean bg) {
        if (r0 == null || r0.isEmpty()) return;
        synchronized (pending) {
            if (last != 0) {                   // vung nay vua doi: danh dau vung "khong co chu" van dang doi
                for (Zone z : zones) if (inflate(z.r).intersects(r0)) z.lastTouch = last;
            }
            Rectangle nr = new Rectangle(r0);
            long f = first, l = last;
            boolean fo = force, b = bg;
            boolean merged = true;
            while (merged) {
                merged = false;
                for (int i = 0; i < pending.size(); i++) {
                    Pend p = pending.get(i);
                    Rectangle g = new Rectangle(p.r);
                    g.grow(pendCell / 2, pendCell / 2);
                    if (g.intersects(nr)) {
                        nr = nr.union(p.r);
                        f = Math.min(f, p.first);
                        l = (f == 0 && first == 0) ? 0 : Math.max(l, p.last);
                        fo |= p.force;
                        b &= p.bg;
                        pending.remove(i);
                        merged = true;
                        break;
                    }
                }
            }
            pending.add(new Pend(nr, f, l, fo, b));
            if (pending.size() > 12) {
                Rectangle u = null;
                long mf = Long.MAX_VALUE, ml = 0;
                boolean uf = false, ub = true;
                for (Pend p : pending) {
                    u = (u == null) ? new Rectangle(p.r) : u.union(p.r);
                    mf = Math.min(mf, p.first);
                    ml = Math.max(ml, p.last);
                    uf |= p.force;
                    ub &= p.bg;
                }
                pending.clear();
                pending.add(new Pend(u, mf, ml, uf, ub));
            }
        }
    }

    /** Cac vung dang cho se di chuyen theo noi dung khi trang cuon (bo phieu tren diem anh). */
    private void shiftPending(Pix.Shot prev, Pix.Shot cur, int dy, Rectangle whole) {
        synchronized (pending) {
            List<Pend> out = new ArrayList<>();
            for (Pend p : pending) {
                if (Pix.movedWith(prev, cur, p.r, dy)) {
                    Rectangle moved = new Rectangle(p.r.x, p.r.y + dy, p.r.width, p.r.height).intersection(whole);
                    if (moved.isEmpty()) continue;
                    p.r = moved;
                }
                out.add(p);
            }
            pending.clear();
            pending.addAll(out);
        }
    }

    private Rectangle inflate(Rectangle r) {
        Rectangle g = new Rectangle(r);
        g.grow(pendCell, pendCell);
        return g;
    }

    /** Vung "khong co chu" dang chi phoi rect (bo cac vung da yen tinh qua 2,5s). Goi khi dang giu khoa pending. */
    private Zone zoneFor(Rectangle rect, long now) {
        zones.removeIf(z -> now - z.lastTouch > 2500);
        for (Zone z : zones) if (inflate(z.r).intersects(rect)) return z;
        return null;
    }

    private void registerBarren(Rectangle r, long now) {
        synchronized (pending) {
            for (Zone z : zones) {
                if (inflate(z.r).intersects(r)) {
                    z.r = z.r.union(r);
                    z.strikes++;
                    z.lastOcr = now;
                    z.lastTouch = now;
                    return;
                }
            }
            Zone z = new Zone();
            z.r = new Rectangle(r);
            z.strikes = 1;
            z.lastOcr = z.lastTouch = now;
            zones.add(z);
            while (zones.size() > 8) zones.remove(0);
        }
    }

    private void clearZones(Rectangle r) {
        synchronized (pending) {
            zones.removeIf(z -> inflate(z.r).intersects(r));
        }
    }

    private void clearAllZones() {
        synchronized (pending) {
            zones.clear();
        }
    }

    /**
     * Lay ra mot cum vung da san sang OCR (da on dinh, hoac doi lien tuc qua lau); cac vung gan nhau duoc gop.
     * cool = dang trong thoi gian nghi gioi han CPU: chi nhan vung da on dinh (yen) hoac viec khan cap, khong nhan vung "nong".
     */
    private Rectangle pickReady(long now, int W, int H, boolean cool, boolean over) {
        synchronized (pending) {
            pickHot = false;
            pickZoneable = false;
            pickForce = false;
            if (pending.isEmpty()) return null;
            List<Pend> ready = new ArrayList<>();
            for (Pend p : pending) {
                boolean settled = p.last == 0 || now - p.last >= SETTLE_MS;
                boolean big = (long) p.r.width * p.r.height > 0.35 * W * H;
                boolean waited = now - p.first >= (big ? MAX_WAIT_BIG_MS : MAX_WAIT_MS);
                boolean ok;
                if (p.force) {
                    ok = !(p.bg && (cool || over));
                } else {
                    Zone z = zoneFor(p.r, now);
                    // het ngan sach OCR: vung vua doi trong 0,6 s qua phai cho; vung da yen >= 0,6 s (vd. vua cuon xong) van duoc dich ngay
                    boolean throttled = over && p.last != 0 && now - p.last < 600;
                    ok = !throttled && (settled || (!cool && waited)) && (z == null || now - z.lastOcr >= z.backoff());
                }
                if (ok) ready.add(p);
            }
            if (ready.isEmpty()) return null;
            ready.sort((a, b) -> Long.compare(a.first, b.first));
            Rectangle u = new Rectangle(ready.get(0).r);
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Pend p : ready) {
                    if (u.contains(p.r)) continue;
                    Rectangle un = u.union(p.r);
                    long sum = (long) u.width * u.height + (long) p.r.width * p.r.height;
                    Rectangle g = new Rectangle(u);
                    g.grow(pendCell, pendCell);
                    if (g.intersects(p.r) || (long) un.width * un.height <= sum * 1.3) {
                        u = un;
                        grew = true;
                    }
                }
            }
            final Rectangle fu = u;
            boolean hot = false, anyForce = false;
            for (Pend p : ready) {
                if (!fu.contains(p.r)) continue;
                boolean settled = p.last == 0 || now - p.last >= SETTLE_MS;
                if (p.bg || (!p.force && !settled)) hot = true;
                if (p.force) anyForce = true;
            }
            pickHot = hot;
            pickZoneable = hot && !anyForce;
            pickForce = anyForce;
            pending.removeIf(p -> fu.contains(p.r));
            return fu;
        }
    }

    /** Gom cac o thay doi thanh cac cum hinh chu nhat. Thay doi qua 40% so o -> mot cum tong. */
    private static List<Rectangle> clusters(boolean[] d, int cols, int rows, int cell, Rectangle whole) {
        int n = cols * rows, dirty = 0;
        int minC = cols, maxC = -1, minR = rows, maxR = -1;
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                if (d[r * cols + c]) {
                    dirty++;
                    minC = Math.min(minC, c); maxC = Math.max(maxC, c);
                    minR = Math.min(minR, r); maxR = Math.max(maxR, r);
                }
        if (dirty == 0) return List.of();
        if (dirty >= n * 0.4) {
            return List.of(new Rectangle(minC * cell, minR * cell, (maxC - minC + 1) * cell, (maxR - minR + 1) * cell).intersection(whole));
        }
        boolean[] m = new boolean[n];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                if (!d[r * cols + c]) continue;
                for (int rr = Math.max(0, r - 1); rr <= Math.min(rows - 1, r + 1); rr++)
                    for (int cc = Math.max(0, c - 1); cc <= Math.min(cols - 1, c + 1); cc++) m[rr * cols + cc] = true;
            }
        boolean[] seen = new boolean[n];
        List<Rectangle> out = new ArrayList<>();
        int[] stack = new int[n];
        for (int s = 0; s < n; s++) {
            if (!m[s] || seen[s]) continue;
            int sp = 0, cMin = cols, cMax = -1, rMin = rows, rMax = -1;
            stack[sp++] = s;
            seen[s] = true;
            while (sp > 0) {
                int i = stack[--sp];
                int r = i / cols, c = i % cols;
                cMin = Math.min(cMin, c); cMax = Math.max(cMax, c);
                rMin = Math.min(rMin, r); rMax = Math.max(rMax, r);
                if (c > 0 && m[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1; }
                if (c < cols - 1 && m[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1; }
                if (r > 0 && m[i - cols] && !seen[i - cols]) { seen[i - cols] = true; stack[sp++] = i - cols; }
                if (r < rows - 1 && m[i + cols] && !seen[i + cols]) { seen[i + cols] = true; stack[sp++] = i + cols; }
            }
            Rectangle rc = new Rectangle(cMin * cell, rMin * cell, (cMax - cMin + 1) * cell, (rMax - rMin + 1) * cell).intersection(whole);
            if (!rc.isEmpty()) out.add(rc);
        }
        return out;
    }

    private BufferedImage capture(Rectangle r) throws InterruptedException {
        boolean hide = (cfg.hideOverlayWhileCapture || !overlay.isCaptureExcluded()) && overlay.isUserVisible();
        if (hide) {
            overlay.setVisibleSync(false);
            Win32.dwmFlush();
            Thread.sleep(30);
        }
        try {
            MultiResolutionImage m = robot.createMultiResolutionScreenCapture(r);
            Image best = null;
            for (Image i : m.getResolutionVariants()) {
                if (best == null || i.getWidth(null) > best.getWidth(null)) best = i;
            }
            if (best instanceof BufferedImage b) return b;
            BufferedImage out = new BufferedImage(best.getWidth(null), best.getHeight(null), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(best, 0, 0, null);
            g.dispose();
            return out;
        } finally {
            if (hide) overlay.setVisibleSync(true);
        }
    }

    // ------------------------------------------------------------ gui OCR + dich
    private void dispatch(int gen, BufferedImage img, Pix.Shot shot, double scale, Rectangle pend, long seqCap,
                          final boolean hot, final boolean zoneable, final boolean forced) {
        int W = img.getWidth(), H = img.getHeight();
        int m = (int) Math.round(CELL * scale);
        Rectangle crop = new Rectangle(pend.x - m, pend.y - m, pend.width + 2 * m, pend.height + 2 * m)
                .intersection(new Rectangle(0, 0, W, H));
        boolean full = (long) crop.width * crop.height > 0.5 * W * H;
        if (full) crop = new Rectangle(0, 0, W, H);
        final Rectangle fc = crop;
        final BufferedImage sub = full ? img : img.getSubimage(crop.x, crop.y, crop.width, crop.height);

        inFlight.set(true);
        ocrPool.execute(() -> {
            try {
                long t0 = System.nanoTime();
                ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 16);
                ImageIO.write(sub, "png", bo);
                PythonBackend.Resp resp = backend.ocr(bo.toByteArray());
                if (!running || gen != generation) return;

                if (resp.error != null && resp.items.isEmpty()) {
                    status.accept("err", resp.error);
                    overlay.setBadge("Lỗi: " + resp.error, 3);
                    scheduleRetry();
                    return;
                }

                // Ung vien: toa do vat ly theo khung luc gui OCR, kem anh tham chieu cat tu chinh khung do
                List<Item> cands = new ArrayList<>();
                // Dong bi mep vung cat (khong phai mep khung) cat ngang -> OCR chi doc nua dong. Vung can OCR luon cach
                // mep cat mot le CELL, nen bo moi hop nam trong nua le tu mep cat khong lam mat chu hop le.
                final int et = Math.max(8, m / 2);
                for (PythonBackend.Row r : resp.items) {
                    if (fc.x > 0 && r.x <= et) continue;
                    if (fc.y > 0 && r.y <= et) continue;
                    if (fc.x + fc.width < W && r.x + r.w >= fc.width - et) continue;
                    if (fc.y + fc.height < H && r.y + r.h >= fc.height - et) continue;
                    Rectangle box = new Rectangle(fc.x + r.x, fc.y + r.y, r.w, r.h).intersection(new Rectangle(0, 0, W, H));
                    Pix.Patch ref = Pix.grab(shot, box.x, box.y, box.width, box.height);
                    if (ref == null) continue;
                    cands.add(new Item(box.x, box.y, box.width, box.height, ref, scale,
                            Math.max(8, (int) Math.round(r.lh / scale)), r.src, r.dst,
                            PythonBackend.rgb(r.bg, Color.WHITE), PythonBackend.rgb(r.fg, Color.BLACK),
                            r.complex, PythonBackend.decodePatch(r.patch)));
                }

                AppLog.info("OCR crop=" + fc.x + "," + fc.y + " " + fc.width + "x" + fc.height + " pend=" + pend
                        + " ocr_ms=" + resp.ocr_ms + " raw=" + resp.items.size() + " cands=" + cands.size());
                final List<Rectangle> dropped = new ArrayList<>();
                final int[] counts = new int[1];
                // Kiem tung khoi voi khung moi nhat (bu do cuon da xay ra tu luc chup), va gop vao overlay, trong cung mot khoa
                overlay.transform(current -> {
                    Pix.Shot lt = latest;
                    if (lt == null) return current;
                    int cum = 0;
                    for (long[] s : shiftLog) if (s[0] > seqCap) cum += (int) s[1];
                    Rectangle whole = new Rectangle(0, 0, lt.w, lt.h);
                    List<Item> accepted = new ArrayList<>();
                    for (Item c : cands) {
                        if (Pix.same(c.ref, lt, c.px, c.py, KEEP_TOL, KEEP_BAD)) {
                            accepted.add(c);
                        } else if (cum != 0 && Pix.same(c.ref, lt, c.px, c.py + cum, MOVE_TOL, MOVE_BAD)) {
                            accepted.add(c.movedTo(c.py + cum, scale));
                        } else {
                            Rectangle f = new Rectangle(c.px, c.py + cum, c.pw, c.ph).intersection(whole);
                            if (!f.isEmpty()) dropped.add(f);
                        }
                    }
                    List<Item> out = new ArrayList<>(current.size() + accepted.size());
                    for (Item old : current) if (!overlapsAny(old, accepted)) out.add(old);
                    out.addAll(accepted);
                    counts[0] = accepted.size();
                    return out;
                });
                long now = System.currentTimeMillis();
                for (Rectangle f : dropped) pend(f, now);

                long total = (System.nanoTime() - t0) / 1_000_000;
                // Vung doi lien tuc ma OCR khong thay chu: lui dan; co chu thi go bo vung "khong co chu"
                if (resp.items.isEmpty()) {
                    if (zoneable) registerBarren(pend, now);
                } else {
                    clearZones(pend);
                }
                if (!forced) {      // OCR khan cap / nen (khoi dong, thu cong, quet an toan) khong an vao ngan sach
                    synchronized (ocrLog) {
                        ocrLog.addLast(new long[]{System.currentTimeMillis(), total});
                    }
                }
                // Gioi han CPU: sau lan OCR "nong", nghi (thoi gian OCR x he so) truoc khi OCR tiep vung dang doi
                if (hot) coolUntil = System.currentTimeMillis() + (long) (total * cfg.coolFactor());
                int count = overlay.count();
                status.accept("ocr", "OCR " + resp.ocr_ms + " ms • " + counts[0] + " khối mới"
                        + (dropped.isEmpty() ? "" : " • bỏ " + dropped.size() + " khối đã đổi") + " • tổng " + total + " ms");
                status.accept("tr", "Dịch " + resp.tr_ms + " ms");
                status.accept("err", resp.error == null ? "" : resp.error);

                if (resp.error != null) {
                    overlay.setBadge(count + " khối • " + resp.error, 2);
                    scheduleRetry();
                } else {
                    retryDelay = RETRY_MIN_MS;
                    overlay.setBadge(count == 0 ? "Đang dịch • chưa thấy chữ Trung" : "Đang dịch • " + count + " khối", 1);
                }
            } catch (Throwable e) {
                AppLog.error("OCR/dich that bai", e);
                status.accept("err", "Mất kết nối backend/OCR: " + e.getMessage());
                overlay.setBadge("Mất kết nối OCR", 3);
                scheduleRetry();
            } finally {
                inFlight.set(false);
            }
        });
    }

    /** Khoi cu chong len mot khoi moi (>= 30% dien tich khoi nho hon) thi bi thay. */
    private static boolean overlapsAny(Item old, List<Item> fresh) {
        Rectangle a = old.physRect();
        for (Item n : fresh) {
            Rectangle i = a.intersection(n.physRect());
            if (i.isEmpty()) continue;
            long ia = (long) i.width * i.height;
            long small = Math.min((long) a.width * a.height, (long) n.pw * n.ph);
            if (ia * 10 >= small * 3) return true;
        }
        return false;
    }

    private void scheduleRetry() {
        retryAt = System.currentTimeMillis() + retryDelay;
        retryDelay = Math.min(RETRY_MAX_MS, retryDelay * 2);
    }
}
