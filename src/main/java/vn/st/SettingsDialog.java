package vn.st;

import javax.swing.*;
import java.awt.*;

/** Hop thoai cai dat. Thay doi ap dung ngay khi bam "Luu". */
public class SettingsDialog extends JDialog {
    private final Config cfg;
    private int row = 0;
    private final JPanel form = new JPanel(new GridBagLayout());

    private final JComboBox<String> provider = new JComboBox<>(new String[]{"google", "openai"});
    private final JTextField url = new JTextField(24), model = new JTextField(24);
    private final JPasswordField key = new JPasswordField(24);
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"speed", "accuracy"});
    private final JCheckBox lowEnd = new JCheckBox("Chế độ nhẹ cho máy yếu (thu nhỏ ảnh trước OCR)");
    private final JCheckBox merge = new JCheckBox("Gộp các dòng liền nhau thành đoạn (dịch đúng ngữ cảnh hơn)");
    private final JCheckBox history = new JCheckBox("Lưu lịch sử dịch vào SQLite (~/.screen-translator/history.db)");
    private final JCheckBox hide = new JCheckBox("Ẩn overlay khi chụp (dùng nếu OCR đọc lại bản dịch)");
    private final JSpinner interval = new JSpinner(new SpinnerNumberModel(250, 50, 3000, 50));
    private final JSpinner fontScale = new JSpinner(new SpinnerNumberModel(1.0, 0.5, 2.0, 0.1));
    private final JSlider opacity = new JSlider(0, 255, 255);
    private final JTextField color = new JTextField(10);
    private final JSpinner minConf = new JSpinner(new SpinnerNumberModel(0.5, 0.1, 0.95, 0.05));
    private final JComboBox<String> hk1 = fkeys(), hk2 = fkeys(), hk3 = fkeys(), hk4 = fkeys();
    private final JTextField python = new JTextField(24);

    private static JComboBox<String> fkeys() {
        String[] k = new String[12];
        for (int i = 0; i < 12; i++) k[i] = "F" + (i + 1);
        return new JComboBox<>(k);
    }

    private void add(String label, JComponent c) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = row; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(3, 6, 3, 6);
        if (label != null) form.add(new JLabel(label), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        form.add(c, g);
        row++;
    }

    private void span(JComponent c) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = row++; g.gridwidth = 2; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(3, 6, 3, 6);
        form.add(c, g);
    }

    public SettingsDialog(Frame owner, Config cfg, Runnable onSaved) {
        super(owner, "Cài đặt", true);
        this.cfg = cfg;
        provider.setSelectedItem(cfg.provider);
        url.setText(cfg.openaiUrl);
        model.setText(cfg.openaiModel);
        key.setText(cfg.getApiKey());
        mode.setSelectedItem(cfg.mode);
        lowEnd.setSelected(cfg.lowEnd);
        merge.setSelected(cfg.mergeLines);
        history.setSelected(cfg.saveHistory);
        hide.setSelected(cfg.hideOverlayWhileCapture);
        interval.setValue(cfg.scanIntervalMs);
        fontScale.setValue(cfg.fontScale);
        opacity.setValue(cfg.bgOpacity);
        color.setText(cfg.textColor);
        minConf.setValue(cfg.minConfidence);
        hk1.setSelectedItem(cfg.hkToggle);
        hk2.setSelectedItem(cfg.hkPause);
        hk3.setSelectedItem(cfg.hkReselect);
        hk4.setSelectedItem(cfg.hkStop);
        python.setText(cfg.pythonExe);

        add("Dịch vụ dịch:", provider);
        add("API URL (openai/Ollama):", url);
        add("Model:", model);
        add("API key:", key);
        add("Ưu tiên:", mode);
        span(lowEnd);
        span(merge);
        add("Tốc độ quét (ms):", interval);
        add("Cỡ chữ tối đa (×):", fontScale);
        add("Độ đục nền bản dịch:", opacity);
        JPanel cp = new JPanel(new BorderLayout(4, 0));
        cp.add(color, BorderLayout.CENTER);
        JButton pick = new JButton("Chọn màu…");
        pick.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Màu chữ", Color.WHITE);
            if (c != null) color.setText(String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()));
        });
        JButton auto = new JButton("Tự động");
        auto.addActionListener(e -> color.setText("auto"));
        JPanel bt = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        bt.add(pick);
        bt.add(auto);
        cp.add(bt, BorderLayout.EAST);
        add("Màu chữ (auto hoặc #RRGGBB):", cp);
        add("Độ tin cậy OCR tối thiểu:", minConf);
        add("Phím: bật/tắt overlay:", hk1);
        add("Phím: tạm dừng/tiếp tục:", hk2);
        add("Phím: chọn lại vùng:", hk3);
        add("Phím: dừng dịch:", hk4);
        span(hide);
        span(history);
        add("Python (để trống = tự tìm):", python);

        JButton ok = new JButton("Lưu");
        JButton cancel = new JButton("Hủy");
        ok.addActionListener(e -> {
            save();
            onSaved.run();
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(ok);
        south.add(cancel);
        JLabel note = new JLabel("<html><i>Dịch trực tuyến (google) gửi riêng phần văn bản OCR ra ngoài, không gửi ảnh. "
                + "Dùng 'openai' + Ollama/LM Studio để dịch hoàn toàn cục bộ.</i></html>");
        note.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(note, BorderLayout.CENTER);
        bottom.add(south, BorderLayout.SOUTH);
        getContentPane().add(bottom, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    private void save() {
        cfg.provider = (String) provider.getSelectedItem();
        cfg.openaiUrl = url.getText().trim();
        cfg.openaiModel = model.getText().trim();
        cfg.setApiKey(new String(key.getPassword()).trim());
        cfg.mode = (String) mode.getSelectedItem();
        cfg.lowEnd = lowEnd.isSelected();
        cfg.mergeLines = merge.isSelected();
        cfg.saveHistory = history.isSelected();
        cfg.hideOverlayWhileCapture = hide.isSelected();
        cfg.scanIntervalMs = (Integer) interval.getValue();
        cfg.fontScale = ((Number) fontScale.getValue()).doubleValue();
        cfg.bgOpacity = opacity.getValue();
        cfg.textColor = color.getText().trim().isEmpty() ? "auto" : color.getText().trim();
        cfg.minConfidence = ((Number) minConf.getValue()).doubleValue();
        cfg.hkToggle = (String) hk1.getSelectedItem();
        cfg.hkPause = (String) hk2.getSelectedItem();
        cfg.hkReselect = (String) hk3.getSelectedItem();
        cfg.hkStop = (String) hk4.getSelectedItem();
        cfg.pythonExe = python.getText().trim();
        cfg.save();
    }
}
