package vn.st;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class Main {
    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> AppLog.error("Loi khong bat duoc o luong " + t.getName(), e));
        AppLog.info("Khoi dong Screen Translator, Java " + System.getProperty("java.version")
                + ", " + System.getProperty("os.name"));
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new MainWindow().setVisible(true);
        });
    }
}
