package vn.st;

import com.sun.jna.Pointer;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Phim tat toan cuc qua RegisterHotKey (chay vong lap thong diep rieng). */
public final class HotkeyManager {
    public static final class Binding {
        final String key;
        final Runnable action;

        public Binding(String key, Runnable action) {
            this.key = key;
            this.action = action;
        }
    }

    private Thread thread;
    private volatile int threadId = 0;
    private final List<String> failed = new ArrayList<>();

    /** "F1".."F12" -> ma phim ao; 0 neu khong hop le. */
    static int vk(String name) {
        if (name == null) return 0;
        name = name.trim().toUpperCase();
        if (name.matches("F([1-9]|1[0-2])")) return 0x70 + Integer.parseInt(name.substring(1)) - 1;
        return 0;
    }

    public synchronized List<String> start(List<Binding> bindings) {
        stop();
        failed.clear();
        if (!Win32.isWindows()) return failed;
        final Object ready = new Object();
        thread = new Thread(() -> {
            Win32.MSG msg = new Win32.MSG();
            Win32.User32.I.PeekMessageW(msg, null, 0, 0, 0);      // tao hang doi thong diep cho luong nay
            Map<Integer, Runnable> map = new HashMap<>();
            int id = 1;
            for (Binding b : bindings) {
                int v = vk(b.key);
                if (v != 0 && Win32.User32.I.RegisterHotKey(null, id, Win32.MOD_NOREPEAT, v)) {
                    map.put(id++, b.action);
                } else {
                    failed.add(b.key);
                }
            }
            threadId = Win32.Kernel32.I.GetCurrentThreadId();
            synchronized (ready) {
                ready.notifyAll();
            }
            while (Win32.User32.I.GetMessageW(msg, null, 0, 0) > 0) {
                if (msg.message == Win32.WM_HOTKEY) {
                    Runnable r = map.get((int) Pointer.nativeValue(msg.wParam));
                    if (r != null) SwingUtilities.invokeLater(r);
                }
            }
            for (int k : map.keySet()) Win32.User32.I.UnregisterHotKey(null, k);
        }, "hotkeys");
        thread.setDaemon(true);
        synchronized (ready) {
            thread.start();
            try {
                ready.wait(2000);
            } catch (InterruptedException ignored) {
            }
        }
        return new ArrayList<>(failed);
    }

    public synchronized void stop() {
        if (thread != null && threadId != 0) {
            Win32.User32.I.PostThreadMessageW(threadId, Win32.WM_QUIT, null, null);
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        thread = null;
        threadId = 0;
    }
}
