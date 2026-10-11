package vn.st;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Cua so dieu khien nho gon. */
public class MainWindow extends JFrame {
    private final Config cfg = Config.load();
    private final PythonBackend backend = new PythonBackend(cfg);
    private final OverlayWindow overlay = new OverlayWindow(cfg);
    private final HotkeyManager hotkeys = new HotkeyManager();
    private final Pipeline pipeline;
    private volatile boolean ready = false;

    private final JLabel lState = new JLabel("Đang khởi động OCR…");
    private final JLabel lOcr = new JLabel(" ");
    private final JLabel lTr = new JLabel(" ");
    private final JLabel lErr = new JLabel(" ");
    private final JButton bStart = new JButton("Bắt đầu dịch");
    private final JButton bReselect = new JButton("Chọn lại vùng");
    private final JButton bPause = new JButton("Tạm dừng");
    private final JButton bStop = new JButton("Dừng dịch");
    private final JButton bOverlay = new JButton("Ẩn/hiện bản dịch");
    private final JButton bNow = new JButton("Dịch ngay");
    private final JCheckBox cAuto = new JCheckBox("Tự động dịch", cfgAuto());

    private boolean cfgAuto() { return cfg.autoTranslate; }

    public MainWindow() {
        super("Screen Translator CN-VI");
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
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) { quit(); }
        });

        JPanel buttons = new JPanel(new GridLayout(0, 2, 6, 6));
        for (JButton b : List.of(bStart, bReselect, bPause, bStop, bOverlay, bNow)) buttons.add(b);
        JButton bSettings = new JButton("Cài đặt");
        buttons.add(bSettings);
        buttons.add(cAuto);

        JPanel status = new JPanel(new GridLayout(0, 1, 0, 2));
        status.add(lState);
        status.add(lOcr);
        status.add(lTr);
        status.add(lErr);
        status.setBorder(BorderFactory.createTitledBorder("Trạng thái"));

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(buttons, BorderLayout.NORTH);
        root.add(status, BorderLayout.CENTER);
        setContentPane(root);
        setSize(420, 330);
        setAlwaysOnTop(true);
        setLocationByPlatform(true);

        bStart.addActionListener(e -> beginSelect());
        bReselect.addActionListener(e -> beginSelect());
        bPause.addActionListener(e -> pipeline.togglePause());
        bStop.addActionListener(e -> pipeline.stop());
        bOverlay.addActionListener(e -> pipeline.toggleOverlay());
        bNow.addActionListener(e -> pipeline.translateNow());
        bSettings.addActionListener(e -> new SettingsDialog(this, cfg, this::settingsChanged).setVisible(true));
        cAuto.addActionListener(e -> { cfg.autoTranslate = cAuto.isSelected(); cfg.save(); });
        refreshButtons();
        registerHotkeys();
        startBackend();
    }

    private void refreshButtons() {
        boolean run = pipeline.isRunning();
        bStart.setEnabled(ready && !run);
        bReselect.setEnabled(ready);
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
                SwingUtilities.invokeLater(() -> { lState.setText("Sẵn sàng. Bấm \"Bắt đầu dịch\"."); refreshButtons(); });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    lState.setText("Lỗi khởi động backend");
                    lErr.setText(String.valueOf(e.getMessage()));
                    lErr.setForeground(new Color(0xB00020));
                });
            }
        }, "backend-start").start();
    }

    private void registerHotkeys() {
        List<String> failed = hotkeys.start(List.of(
                new HotkeyManager.Binding(cfg.hkToggle, pipeline::toggleOverlay),
                new HotkeyManager.Binding(cfg.hkPause, () -> { pipeline.togglePause(); refreshButtons(); }),
                new HotkeyManager.Binding(cfg.hkReselect, this::beginSelect)));
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

    /** Chon (hoac chon lai) vung dich. */
    private void beginSelect() {
        if (!ready) return;
        pipeline.stop();                       // go overlay cu truoc khi chon
        setState(Frame.ICONIFIED);
        Timer t = new Timer(350, e -> RegionSelector.select(r -> SwingUtilities.invokeLater(() -> {
            if (r == null) {
                setState(Frame.NORMAL);
                toFront();
            } else {
                pipeline.start(r);               // cua so dieu khien van thu nho; mo lai tu taskbar
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
