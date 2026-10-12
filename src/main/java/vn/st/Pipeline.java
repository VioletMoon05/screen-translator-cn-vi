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
 * Luong xu ly: chup vung -> phat hien thay doi theo o luoi -> (khi man hinh on dinh) gui phan thay doi
 * cho backend OCR+dich -> cap nhat overlay. Moi ket qua gan voi so thu tu thay doi; ket qua cu bi bo.
 */
public class Pipeline {
    private static final int CELL = 40;              // kich thuoc o luoi (logical px)
    private static final int CHANGE_THRESHOLD = 6;   // chenh lech do sang trung binh / mau de coi la thay doi
    private static final long MAX_WAIT_MS = 1500;    // man hinh lien tuc thay doi (video...) van quet sau ngan nay
    private static final long SWEEP_MS = 12000;      // luoi an toan: quet lai toan vung du man hinh "khong doi"
    private static final long RETRY_MIN_MS = 2000, RETRY_MAX_MS = 15000;

    private record Change(long seq, Rectangle r) {}

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
    private final List<Change> changeLog = new ArrayList<>();
    private long seq = 0;
    private Rectangle region;

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
        synchronized (changeLog) {
            changeLog.clear();
        }
        retryAt = 0;
        retryDelay = RETRY_MIN_MS;
        try {
            overlay.open(region);
        } catch (Throwable t) {                 // overlay loi van phai chay duoc vong quet
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
        status.accept("state", "Đang dịch vùng " + r.width + "×" + r.height + " (tự dịch liên tục đến khi bấm Dừng)");
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
        Sig prev = null;
        Rectangle pending = null;
        long firstPending = 0;
        boolean first = true;
        lastDispatch = System.currentTimeMillis();
        try {
            Thread.sleep(150);                  // doi lop chon vung bien mat han khoi man hinh
        } catch (InterruptedException e) {
            return;
        }
        while (running && gen == generation) {
            long t0 = System.currentTimeMillis();
            try {
                if (!paused) {
                    BufferedImage img = capture(reg);
                    Rectangle whole = new Rectangle(0, 0, img.getWidth(), img.getHeight());
                    double scale = img.getWidth() / (double) reg.width;
                    Sig cur = new Sig(img, Math.max(8, (int) Math.round(CELL * scale)));
                    Rectangle dirty;
                    if (prev == null) dirty = new Rectangle(whole);
                    else dirty = Sig.diff(prev, cur);
                    prev = cur;
                    if (dirty != null) {
                        dirty = dirty.intersection(whole);
                        pending = pending == null ? dirty : pending.union(dirty);
                        if (firstPending == 0) firstPending = t0;
                        Rectangle logical = toLogical(dirty, scale);
                        overlay.removeIntersecting(logical);          // xoa ban dich o noi dung vua doi
                        synchronized (changeLog) {
                            changeLog.add(new Change(++seq, logical));
                            while (changeLog.size() > 300) changeLog.remove(0);
                        }
                    }
                    boolean idle = !inFlight.get();
                    // "Dich ngay" luc man hinh dung yen: quet toan vung
                    if (manualRequest && pending == null) pending = new Rectangle(whole);
                    // thu lai sau loi (mang/backend) va quet an toan dinh ky
                    if (pending == null && idle) {
                        boolean retry = retryAt != 0 && t0 >= retryAt;
                        boolean sweep = cfg.autoTranslate && t0 - lastDispatch > (cfg.lowEnd ? 3 * SWEEP_MS : SWEEP_MS);
                        if (retry || sweep) {
                            pending = new Rectangle(whole);
                            retryAt = 0;
                        }
                    }
                    boolean allowed = cfg.autoTranslate || manualRequest;
                    boolean settled = first || dirty == null || t0 - firstPending > MAX_WAIT_MS;
                    if (pending != null && allowed && settled && idle) {
                        boolean forced = dirty != null;
                        dispatch(gen, img, scale, pending, seq, forced);
                        lastDispatch = t0;
                        pending = null;
                        firstPending = 0;
                        manualRequest = false;
                        first = false;
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {              // bat ca Error: vong quet khong duoc chet im lang
                AppLog.error("Loi vong quet", e);
                status.accept("err", "Lỗi quét: " + e);
                overlay.setBadge("Lỗi quét", 3);
            }
            long interval = cfg.scanIntervalMs;
            if (cfg.hideOverlayWhileCapture || !overlay.isCaptureExcluded()) interval = Math.max(interval, 600);   // che do an/hien: bot nhap nhay
            long sleep = interval - (System.currentTimeMillis() - t0);
            try {
                Thread.sleep(Math.max(15, sleep));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private BufferedImage capture(Rectangle r) throws InterruptedException {
        boolean hide = (cfg.hideOverlayWhileCapture || !overlay.isCaptureExcluded()) && overlay.isUserVisible();
        if (hide) {
            overlay.setVisibleSync(false);
            Win32.dwmFlush();                    // cho DWM bo overlay khoi man hinh that su
            Thread.sleep(30);
        }
        try {
            MultiResolutionImage m = robot.createMultiResolutionScreenCapture(r);
            Image best = null;
            for (Image i : m.getResolutionVariants())
                if (best == null || i.getWidth(null) > best.getWidth(null)) best = i;
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

    private static Rectangle toLogical(Rectangle px, double scale) {
        int x = (int) Math.floor(px.x / scale), y = (int) Math.floor(px.y / scale);
        int w = (int) Math.ceil(px.width / scale) + 1, h = (int) Math.ceil(px.height / scale) + 1;
        return new Rectangle(x, y, w, h);
    }

    private boolean staleSince(long since, Rectangle cropLogical) {
        synchronized (changeLog) {
            for (Change c : changeLog) if (c.seq > since && c.r.intersects(cropLogical)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ gui OCR + dich
    private void dispatch(int gen, BufferedImage img, double scale, Rectangle pend, long seqNow, boolean forced) {
        int W = img.getWidth(), H = img.getHeight();
        int m = (int) Math.round(CELL * scale * 2);
        Rectangle crop = new Rectangle(pend.x - m, pend.y - m, pend.width + 2 * m, pend.height + 2 * m)
                .intersection(new Rectangle(0, 0, W, H));
        boolean full = (long) crop.width * crop.height > 0.5 * W * H;
        if (full) crop = new Rectangle(0, 0, W, H);
        final Rectangle fc = crop;
        final boolean isFull = full;
        final BufferedImage sub = full ? img : img.getSubimage(crop.x, crop.y, crop.width, crop.height);
        inFlight.set(true);
        ocrPool.execute(() -> {
            try {
                long t0 = System.nanoTime();
                ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 16);
                ImageIO.write(sub, "png", bo);
                PythonBackend.Resp resp = backend.ocr(bo.toByteArray());
                if (!running || gen != generation) return;
                Rectangle cropL = toLogical(fc, scale);
                if (!forced && staleSince(seqNow, cropL)) {
                    status.accept("ocr", "Bỏ kết quả cũ (nội dung đã đổi)");
                    return;
                }
                if (resp.error != null && resp.items.isEmpty()) {
                    status.accept("err", resp.error);
                    overlay.setBadge("Lỗi: " + resp.error, 3);
                    scheduleRetry();
                    return;
                }
                List<Item> items = new ArrayList<>();
                for (PythonBackend.Row r : resp.items) {
                    if (!isFull) {      // bo dong bi cat o mep vung quet (dong goc con nguyen, giu ban dich cu)
                        if (fc.x > 0 && r.x <= 2) continue;
                        if (fc.y > 0 && r.y <= 2) continue;
                        if (fc.x + fc.width < W && r.x + r.w >= fc.width - 2) continue;
                        if (fc.y + fc.height < H && r.y + r.h >= fc.height - 2) continue;
                    }
                    int x = (int) Math.round((fc.x + r.x) / scale), y = (int) Math.round((fc.y + r.y) / scale);
                    int w = Math.max(1, (int) Math.round(r.w / scale)), h = Math.max(1, (int) Math.round(r.h / scale));
                    items.add(new Item(x, y, w, h, Math.max(8, (int) Math.round(r.lh / scale)), r.src, r.dst,
                            PythonBackend.rgb(r.bg, Color.WHITE), PythonBackend.rgb(r.fg, Color.BLACK),
                            r.complex, PythonBackend.decodePatch(r.patch)));
                }
                overlay.replace(cropL, isFull, items);
                long total = (System.nanoTime() - t0) / 1_000_000;
                status.accept("ocr", "OCR " + resp.ocr_ms + " ms • " + items.size() + " khối • tổng " + total + " ms");
                status.accept("tr", "Dịch " + resp.tr_ms + " ms");
                status.accept("err", resp.error == null ? "" : resp.error);
                if (resp.error != null) {          // dich loi mot phan -> thu lai
                    overlay.setBadge(items.size() + " khối • " + resp.error, 2);
                    scheduleRetry();
                } else {
                    retryDelay = RETRY_MIN_MS;
                    overlay.setBadge(items.isEmpty() ? "Đang dịch • chưa thấy chữ Trung" : "Đang dịch • " + items.size() + " khối", 1);
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

    /** Thu lai sau loi, giu khoang cach tang dan de khong dap vao backend/mang. */
    private void scheduleRetry() {
        retryAt = System.currentTimeMillis() + retryDelay;
        retryDelay = Math.min(RETRY_MAX_MS, retryDelay * 2);
    }

    // ------------------------------------------------------- phat hien thay doi
    /** Chu ky o luoi: moi o lay mau NxN diem do sang. */
    private static final class Sig {
        static final int N = 8;
        final int cols, rows, cell;
        final byte[] v;

        Sig(BufferedImage img, int cell) {
            int w = img.getWidth(), h = img.getHeight();
            this.cell = cell;
            cols = (w + cell - 1) / cell;
            rows = (h + cell - 1) / cell;
            v = new byte[cols * rows * N * N];
            int k = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    for (int j = 0; j < N; j++) {
                        int y = Math.min(h - 1, r * cell + (2 * j + 1) * cell / (2 * N));
                        for (int i = 0; i < N; i++) {
                            int x = Math.min(w - 1, c * cell + (2 * i + 1) * cell / (2 * N));
                            int p = img.getRGB(x, y);
                            v[k++] = (byte) ((((p >> 16) & 255) * 30 + ((p >> 8) & 255) * 59 + (p & 255) * 11) / 100);
                        }
                    }
                }
            }
        }

        /** Hinh chu nhat bao cac o thay doi, hoac null neu khong doi. */
        static Rectangle diff(Sig a, Sig b) {
            if (a.cols != b.cols || a.rows != b.rows || a.cell != b.cell) return new Rectangle(0, 0, b.cols * b.cell, b.rows * b.cell);
            int per = N * N;
            int minC = Integer.MAX_VALUE, minR = Integer.MAX_VALUE, maxC = -1, maxR = -1;
            for (int r = 0; r < b.rows; r++) {
                for (int c = 0; c < b.cols; c++) {
                    int base = (r * b.cols + c) * per, sum = 0;
                    for (int k = 0; k < per; k++) sum += Math.abs((a.v[base + k] & 255) - (b.v[base + k] & 255));
                    if (sum / (double) per > CHANGE_THRESHOLD) {
                        minC = Math.min(minC, c);
                        maxC = Math.max(maxC, c);
                        minR = Math.min(minR, r);
                        maxR = Math.max(maxR, r);
                    }
                }
            }
            if (maxC < 0) return null;
            return new Rectangle(minC * b.cell, minR * b.cell, (maxC - minC + 1) * b.cell, (maxR - minR + 1) * b.cell);
        }
    }
}
