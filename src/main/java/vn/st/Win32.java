package vn.st;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.awt.Window;
import java.util.Arrays;
import java.util.List;

/** Cac loi goi Win32 toi thieu (JNA loi, khong can jna-platform). */
public final class Win32 {
    private Win32() {}

    public static final int GWL_EXSTYLE = -20;
    public static final int WS_EX_TRANSPARENT = 0x00000020;
    public static final int WS_EX_TOOLWINDOW = 0x00000080;
    public static final int WS_EX_LAYERED = 0x00080000;
    public static final int WS_EX_NOACTIVATE = 0x08000000;
    public static final int WDA_EXCLUDEFROMCAPTURE = 0x00000011;
    public static final int WM_HOTKEY = 0x0312;
    public static final int WM_QUIT = 0x0012;
    public static final int MOD_NOREPEAT = 0x4000;

    public static class MSG extends Structure {
        public Pointer hwnd;
        public int message;
        public Pointer wParam;
        public Pointer lParam;
        public int time;
        public int ptX;
        public int ptY;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("hwnd", "message", "wParam", "lParam", "time", "ptX", "ptY");
        }
    }

    public static class DATA_BLOB extends Structure {
        public int cbData;
        public Pointer pbData;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("cbData", "pbData");
        }
    }

    public interface User32 extends Library {
        User32 I = Native.load("user32", User32.class);

        int GetWindowLongW(Pointer hWnd, int nIndex);

        int SetWindowLongW(Pointer hWnd, int nIndex, int dwNewLong);

        boolean SetWindowDisplayAffinity(Pointer hWnd, int dwAffinity);

        boolean RegisterHotKey(Pointer hWnd, int id, int fsModifiers, int vk);

        boolean UnregisterHotKey(Pointer hWnd, int id);

        int GetMessageW(MSG lpMsg, Pointer hWnd, int wMsgFilterMin, int wMsgFilterMax);

        boolean PeekMessageW(MSG lpMsg, Pointer hWnd, int wMsgFilterMin, int wMsgFilterMax, int wRemoveMsg);

        boolean PostThreadMessageW(int idThread, int msg, Pointer wParam, Pointer lParam);
    }

    public interface Kernel32 extends Library {
        Kernel32 I = Native.load("kernel32", Kernel32.class);

        int GetCurrentThreadId();

        Pointer LocalFree(Pointer p);
    }

    public interface Dwmapi extends Library {
        Dwmapi I = Native.load("dwmapi", Dwmapi.class);

        int DwmFlush();
    }

    public interface Crypt32 extends Library {
        Crypt32 I = Native.load("crypt32", Crypt32.class);

        boolean CryptProtectData(DATA_BLOB in, Pointer descr, DATA_BLOB entropy, Pointer reserved,
                                 Pointer prompt, int flags, DATA_BLOB out);

        boolean CryptUnprotectData(DATA_BLOB in, Pointer descrOut, DATA_BLOB entropy, Pointer reserved,
                                   Pointer prompt, int flags, DATA_BLOB out);
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static Pointer hwnd(Window w) {
        return Native.getComponentPointer(w);
    }

    /** Cua so khong nhan chuot (click-through), khong lay tieu diem, khong hien tren taskbar. */
    public static void makeClickThrough(Window w) {
        if (!isWindows()) return;
        Pointer h = hwnd(w);
        if (h == null) throw new IllegalStateException("Khong lay duoc HWND cua overlay");
        int ex = User32.I.GetWindowLongW(h, GWL_EXSTYLE);
        ex |= WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW;
        User32.I.SetWindowLongW(h, GWL_EXSTYLE, ex);
    }

    /** Loai cua so khoi anh chup man hinh (Windows 10 2004+). Tra ve false neu khong ho tro. */
    public static boolean excludeFromCapture(Window w) {
        if (!isWindows()) return false;
        try {
            return User32.I.SetWindowDisplayAffinity(hwnd(w), WDA_EXCLUDEFROMCAPTURE);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Cho DWM ve xong khung hinh ke tiep (dam bao cua so vua an da that su bien mat truoc khi chup). */
    public static void dwmFlush() {
        if (!isWindows()) return;
        try {
            Dwmapi.I.DwmFlush();
        } catch (Throwable ignored) {
        }
    }

    /** Ma hoa DPAPI (gan voi tai khoan Windows hien tai). */
    public static byte[] protect(byte[] data) {
        DATA_BLOB in = new DATA_BLOB(), out = new DATA_BLOB();
        Memory m = new Memory(Math.max(1, data.length));
        m.write(0, data, 0, data.length);
        in.cbData = data.length;
        in.pbData = m;
        if (!Crypt32.I.CryptProtectData(in, null, null, null, null, 0, out)) throw new IllegalStateException("DPAPI protect");
        try {
            return out.pbData.getByteArray(0, out.cbData);
        } finally {
            Kernel32.I.LocalFree(out.pbData);
        }
    }

    public static byte[] unprotect(byte[] data) {
        DATA_BLOB in = new DATA_BLOB(), out = new DATA_BLOB();
        Memory m = new Memory(Math.max(1, data.length));
        m.write(0, data, 0, data.length);
        in.cbData = data.length;
        in.pbData = m;
        if (!Crypt32.I.CryptUnprotectData(in, null, null, null, null, 0, out)) throw new IllegalStateException("DPAPI unprotect");
        try {
            return out.pbData.getByteArray(0, out.cbData);
        } finally {
            Kernel32.I.LocalFree(out.pbData);
        }
    }
}
