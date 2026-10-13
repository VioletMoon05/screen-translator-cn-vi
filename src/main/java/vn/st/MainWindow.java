package vn.st;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cua so dieu khien nho gon.
 * Luong: "Chon vung" -> "Bat dau dich" -> dich lien tuc tren desktop cho den khi bam "Dung dich" (hoac phim tat).
 */
public class MainWindow extends JFrame {
    private final Config cfg = Config.load();
    private final PythonBackend backend = new PythonBackend(cfg);
    private final OverlayWindow overlay = new OverlayWindow(cfg);
    private final HotkeyManager hotkeys = new HotkeyManager();
    private final Pipeline pipeline;
    private volatile boolean ready = false;
    private final AtomicBoolean restarting = new AtomicBoolean(false);
    private Rectangle region;                       // vung dich dang chon (nho giua cac lan chay)

    private final JLabel lState = new JLabel("Đang khởi động OCR…");
    private final JLabel lRegion = new JLabel(" ");
    private final JLabel lOcr = new JLabel(" ");
    private final JLabel lTr = new JLabel(" ");
    private final JLabel lErr = new JLabel(" ");
    private final JButton bSelect = new JButton("1. Chọn vùng");
    private final JButton bStart = new JButton("2. Bắt đầu dịch");
    private final JButton bPause = new JButton("Tạm dừng");
    private final JButton bStop = new JButton("Dừng dịch");
    private final JButton bOverlay = new JButton("Ẩn/hiện bản dịch");
    private final JButton bNow = new JButton("Dịch ngay");
    private final JCheckBox cAuto = new JCheckBox("Tự động dịch", cfgAuto());

    private boolean cfgAuto() { return cfg.autoTranslate; }

    public MainWindow() {
        super("Screen Translator CN-VI");
        if (cfg.lastRegion != null && cfg.lastRegion.length == 4 && cfg.lastRegion[2] > 20 && cfg.lastRegion[3] > 20)
            region = new Rectangle(cfg.lastRegion[0], cfg.lastRegion[1], cfg.lastRegion[2], cfg.lastRegion[3]);

        pipeline = new Pipeline(cfg, backend, overlay, (k, v) -> SwingUtilities.invokeLater(() -> {
            switch (k) {
                case "state" -> { lState.setText(v); refreshButtons(); }
                case "ocr" -> lOcr.setText(v);
                case "tr" -> lTr.setText(v);
                case "err" -> { lErr.setText(v); lErr.setForeground(v.isEmpty() ? Color.DARK_GRAY : new Color(0xB00020)); }
                default -> { }
            }
        }));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) { quit(); }
        });

        bStart.setFont(bStart.getFont().deriveFont(Font.BOLD));
        JPanel buttons = new JPanel(new GridLayout(0, 2, 6, 6));
        for (JButton b : List.of(bSelect, bStart, bPause, bStop, bOverlay, bNow)) buttons.add(b);
        JButton bSettings = new JButton("Cài đặt");
        JButton bLog = new JButton("Mở file log");
        buttons.add(bSettings);
        buttons.add(bLog);
        buttons.add(cAuto);

        JPanel status = new JPanel(new GridLayout(0, 1, 0, 2));
        status.add(lState);
        status.add(lRegion);
        status.add(lOcr);
        status.add(lTr);
        status.add(lErr);
        status.setBorder(BorderFactory.createTitledBorder("Trạng thái"));

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(buttons, BorderLayout.NORTH);
        root.add(status, BorderLayout.CENTER);
        setContentPane(root);
        setSize(460, 400);
        setAlwaysOnTop(true);
        setLocationByPlatform(true);

        bSelect.addActionListener(e -> selectRegion(false));
        bStart.addActionListener(e -> startTranslate());
        bPause.addActionListener(e -> { pipeline.togglePause(); refreshButtons(); });
        bStop.addActionListener(e -> stopTranslate());
        bOverlay.addActionListener(e -> pipeline.toggleOverlay());
        bNow.addActionListener(e -> pipeline.translateNow());
        bSettings.addActionListener(e -> new SettingsDialog(this, cfg, this::settingsChanged).setVisible(true));
        bLog.addActionListener(e -> openLog());
        cAuto.addActionListener(e -> { cfg.autoTranslate = cAuto.isSelected(); cfg.save(); });
        updateRegionLabel();
        refreshButtons();
        registerHotkeys();
        startBackend();

        // giam sat backend: neu tien trinh Python chet thi tu khoi dong lai
        Timer watchdog = new Timer(3000, e -> {
            if (ready && !backend.isAlive() && restarting.compareAndSet(false, true)) {
                AppLog.info("Backend Python da dung, khoi dong lai");
                ready = false;
                lState.setText("Backend dừng bất ngờ, đang khởi động lại…");
                refreshButtons();
                startBackend();
            }
        });
        watchdog.start();
    }

    private void updateRegionLabel() {
        lRegion.setText(region == null ? "Vùng dịch: chưa chọn"
                : "Vùng dịch: " + region.width + "×" + region.height + " tại (" + region.x + ", " + region.y + ")");
    }

    private void refreshButtons() {
        boolean run = pipeline.isRunning();
        bSelect.setEnabled(ready);
        bStart.setEnabled(ready && !run);
        bPause.setEnabled(run);
        bPause.setText(pipeline.isPaused() ? "Tiếp tục" : "Tạm dừng");
        bStop.setEnabled(run);
        bOverlay.setEnabled(run);
        bNow.setEnabled(run);
    }

    private void startBackend() {
        new Thread(() -> {
            try {
                backend.start();
                if (!backend.waitReady(180))
                    throw new IllegalStateException("Backend Python không khởi động được. Log cuối: " + backend.lastLog());
                backend.pushConfig();
                ready = true;
                final int big = backend.bigDictSize();
                AppLog.info("Tu dien lon: " + big + " cum");
                SwingUtilities.invokeLater(() -> {
                    if (big == 0) {
                        lErr.setText("Chưa có từ điển lớn: đang tự tải (cần mạng, ~45 MB, chỉ một lần)…");
                        lErr.setForeground(new Color(0xB36B00));
                        autoBuildDict();
                    } else if (big > 0) {
                        lTr.setText("Từ điển offline: " + String.format("%,d", big) + " cụm + từ điển riêng");
                    }
                    lState.setText(region == null ? "Sẵn sàng. Bấm \"Chọn vùng\", rồi \"Bắt đầu dịch\"."
                            : "Sẵn sàng. Bấm \"Bắt đầu dịch\" (hoặc \"Chọn vùng\" để đổi vùng).");
                    refreshButtons();
                });
            } catch (Exception e) {
                AppLog.error("Khoi dong backend that bai", e);
                SwingUtilities.invokeLater(() -> {
                    lState.setText("Lỗi khởi động backend (xem file log)");
                    lErr.setText(String.valueOf(e.getMessage()));
                    lErr.setForeground(new Color(0xB00020));
                });
            } finally {
                restarting.set(false);
            }
        }, "backend-start").start();
    }

    /** Chua co dict_big.db -> backend tu tai/dung; poll tien do, xong thi dich lai vung dang chay. */
    private void autoBuildDict() {
        new Thread(() -> {
            try { backend.startDictBuild(); } catch (Exception e) { AppLog.error("startDictBuild", e); }
        }, "dict-start").start();
        final Timer[] t = new Timer[1];
        t[0] = new Timer(1500, ev -> new Thread(() -> {
            try {
                java.util.Map<?, ?> m = new com.google.gson.Gson().fromJson(backend.dictStatus(), java.util.Map.class);
                String st = String.valueOf(m.get("state")), msg = String.valueOf(m.get("msg"));
                SwingUtilities.invokeLater(() -> {
                    lTr.setText("Từ điển lớn: " + msg);
                    if ("done".equals(st)) {
                        t[0].stop();
                        lErr.setText(" ");
                        lErr.setForeground(Color.DARK_GRAY);
                        if (pipeline.isRunning()) pipeline.translateNow();     // dich lai bang tu dien moi
                    } else if ("error".equals(st)) {
                        t[0].stop();
                        lErr.setText("Từ điển lớn: " + msg);
                        lErr.setForeground(new Color(0xB00020));
                    }
                });
            } catch (Exception ignored) {
            }
        }, "dict-poll").start());
        t[0].start();
    }

    private void registerHotkeys() {
        List<String> failed = hotkeys.start(List.of(
                new HotkeyManager.Binding(cfg.hkToggle, pipeline::toggleOverlay),
                new HotkeyManager.Binding(cfg.hkPause, () -> { pipeline.togglePause(); refreshButtons(); }),
                new HotkeyManager.Binding(cfg.hkReselect, () -> selectRegion(true)),
                new HotkeyManager.Binding(cfg.hkStop, this::stopTranslate)));
        if (!failed.isEmpty()) {
            lErr.setText("Phím tắt bị trùng, hãy đổi trong Cài đặt: " + String.join(", ", failed));
            lErr.setForeground(new Color(0xB00020));
        }
    }

    private void settingsChanged() {
        new Thread(() -> {
            try { backend.pushConfig(); } catch (Exception e) {
                SwingUtilities.invokeLater(() -> lErr.setText("Không gửi được cấu hình: " + e.getMessage()));
            }
        }).start();
        registerHotkeys();
        overlay.repaint();
    }

    private void openLog() {
        try {
            Desktop.getDesktop().open(AppLog.file().toFile());
        } catch (Exception e) {
            lErr.setText("Log nằm tại: " + AppLog.file());
        }
    }

    // ------------------------------------------------------------------ luong chinh
    /** "Bat dau dich": co vung roi thi dich ngay; chua co vung thi chon vung truoc roi dich luon. */
    private void startTranslate() {
        if (!ready) return;
        if (region == null) {
            selectRegion(true);
            return;
        }
        launch(region, true);
    }

    private void stopTranslate() {
        pipeline.stop();
        refreshButtons();
        if (getState() == Frame.ICONIFIED) setState(Frame.NORMAL);
        toFront();
    }

    /** Thu nho cua so dieu khien (de khong lot vao vung dich) roi bat dau dich ngay. */
    private void launch(Rectangle r, boolean needIconify) {
        if (needIconify && getState() != Frame.ICONIFIED) {
            setState(Frame.ICONIFIED);
            Timer t = new Timer(400, e -> { pipeline.start(r); refreshButtons(); });   // doi hieu ung thu nho xong
            t.setRepeats(false);
            t.start();
        } else {
            pipeline.start(r);
            refreshButtons();
        }
    }

    /** Chon (hoac chon lai) vung dich. startAfter = true: chon xong dich luon. Dang chay thi chon xong tu dich lai. */
    private void selectRegion(boolean startAfter) {
        if (!ready) return;
        final boolean wasRunning = pipeline.isRunning();
        final Rectangle old = region;
        pipeline.stop();                       // go overlay cu truoc khi chon
        setState(Frame.ICONIFIED);
        Timer t = new Timer(350, e -> RegionSelector.select(r -> SwingUtilities.invokeLater(() -> {
            if (r == null) {                    // huy
                if (wasRunning && old != null) {
                    launch(old, false);
                } else {
                    setState(Frame.NORMAL);
                    toFront();
                }
            } else {
                region = r;
                cfg.lastRegion = new int[]{r.x, r.y, r.width, r.height};
                cfg.save();
                updateRegionLabel();
                if (startAfter || wasRunning) {
                    launch(r, false);            // dich ngay, cua so van thu nho (mo lai tu taskbar hoac nhan phim dung)
                } else {
                    setState(Frame.NORMAL);
                    toFront();
                    lState.setText("Đã chọn vùng. Bấm \"Bắt đầu dịch\" để dịch.");
                }
            }
            refreshButtons();
        })));
        t.setRepeats(false);
        t.start();
    }

    private void quit() {
        hotkeys.stop();
        pipeline.stop();
        backend.stop();
        System.exit(0);
    }
}
