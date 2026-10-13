package vn.st;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Cua so overlay trong suot, khong vien, luon tren cung, khong nhan chuot / khong lay tieu diem.
 * Tu loai minh khoi anh chup man hinh (WDA_EXCLUDEFROMCAPTURE) de OCR khong doc lai ban dich.
 */
public class OverlayWindow extends JWindow {
    private static final class Layout {
        final Font font;
        final List<String> lines;
        final int width, height, lineH, ascent;

        Layout(Font f, List<String> l, int w, int h, int lineH, int ascent) {
            font = f; lines = l; width = w; height = h; this.lineH = lineH; this.ascent = ascent;
        }
    }

    private final Config cfg;
    private final Object lock = new Object();
    private volatile List<Item> items = new ArrayList<>();
    private boolean nativeApplied = false;
    private volatile boolean excluded = false;
    private volatile boolean userVisible = true;
    private int offY = 0;                         // le tren de ve thanh trang thai (ngoai vung dich)
    private volatile String badgeText = "";
    private volatile int badgeLevel = 0;          // 0 trung tinh, 1 ok, 2 canh bao, 3 loi

    public OverlayWindow(Config cfg) {
        super();
        this.cfg = cfg;
        setType(Window.Type.UTILITY);
        setAlwaysOnTop(true);
        setFocusableWindowState(false);
        setBackground(new Color(0, 0, 0, 0));
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setComposite(AlphaComposite.SrcOver);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                paintBadge(g);
                g.translate(0, offY);
                for (Item it : items) paintItem(g, it);
                g.setColor(new Color(0, 200, 120, 150));       // vien mong quanh vung dang dich
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f));
                g.drawRect(0, 0, getWidth() - 1, getHeight() - offY - 1);
                g.dispose();
            }
        };
        p.setOpaque(false);
        setContentPane(p);
    }

    public boolean isCaptureExcluded() {
        return excluded;
    }

    public boolean isUserVisible() {
        return userVisible;
    }

    private void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Dat vi tri theo vung dich va hien overlay. Loi native (JNA/Win32) khong duoc lam hong luong dich. */
    public void open(Rectangle region) {
        onEdt(() -> {
            int room = region.y - RegionSelector.virtualBounds().y;
            offY = room >= 20 ? 20 : 0;
            setBounds(region.x, region.y - offY, region.width, region.height + offY);
            setVisible(true);                      // phai hien truoc de co HWND
            if (!nativeApplied) {
                nativeApplied = true;
                try {
                    Win32.makeClickThrough(this);
                } catch (Throwable t) {
                    AppLog.error("makeClickThrough that bai", t);
                }
                try {
                    excluded = Win32.excludeFromCapture(this);
                } catch (Throwable t) {
                    excluded = false;
                    AppLog.error("excludeFromCapture that bai", t);
                }
                AppLog.info("Overlay: captureExcluded=" + excluded);
            }
            if (!userVisible) setVisible(false);
            repaint();
        });
    }

    public void setBadge(String text, int level) {
        badgeText = text == null ? "" : text;
        badgeLevel = level;
        repaint();
    }

    private void paintBadge(Graphics2D g) {
        String t = badgeText;
        if (t.isEmpty()) return;
        g.setFont(fontFor(t, Font.BOLD));
        FontMetrics fm = g.getFontMetrics();
        int w = Math.min(getWidth(), fm.stringWidth(t) + 28), h = 18;
        int y = offY > 0 ? 1 : 2;                   // khong con cho phia tren thi ve de len goc tren trai
        int x = offY > 0 ? 0 : 2;
        Color dot = switch (badgeLevel) {
            case 1 -> new Color(0, 200, 120);
            case 2 -> new Color(255, 170, 0);
            case 3 -> new Color(230, 50, 60);
            default -> new Color(150, 150, 150);
        };
        g.setColor(new Color(20, 20, 20, 220));
        g.fillRoundRect(x, y, w, h, 8, 8);
        g.setColor(dot);
        g.fillOval(x + 7, y + 5, 8, 8);
        g.setColor(Color.WHITE);
        String shown = t;
        while (shown.length() > 3 && fm.stringWidth(shown) > w - 28) shown = shown.substring(0, shown.length() - 2);
        if (shown.length() < t.length()) shown += "…";
        g.drawString(shown, x + 20, y + 13);
    }

    public void closeOverlay() {
        clear();
        onEdt(() -> setVisible(false));
    }

    public void setUserVisible(boolean v) {
        userVisible = v;
        onEdt(() -> {
            if (nativeApplied) setVisible(v);
        });
    }

    /** Dung khi khong the loai overlay khoi anh chup: an tam thoi luc chup. */
    public void setVisibleSync(boolean v) {
        if (!nativeApplied) return;
        onEdt(() -> setVisible(v));
    }

    // ------------------------------------------------------------- trang thai noi dung
    public void clear() {
        synchronized (lock) {
            items = new ArrayList<>();
        }
        repaint();
    }

    public void removeIntersecting(Rectangle r) {
        synchronized (lock) {
            List<Item> n = new ArrayList<>();
            boolean changed = false;
            for (Item it : items) {
                if (it.rect().intersects(r)) changed = true;
                else n.add(it);
            }
            if (!changed) return;
            items = n;
        }
        repaint();
    }

    /** Thay the cac khoi nam tron trong vung quet bang ket qua moi. */
    public void replace(Rectangle crop, boolean full, List<Item> add) {
        synchronized (lock) {
            List<Item> n = new ArrayList<>();
            for (Item it : items) {
                if (!(full || crop.contains(it.rect()))) n.add(it);
            }
            n.addAll(add);
            items = n;
        }
        repaint();
    }

    public int count() {
        return items.size();
    }

    public List<Item> snapshot() {
        return new ArrayList<>(items);
    }

    /** Font co du glyph cho chuoi: Segoe UI -> Microsoft YaHei UI -> font logic Dialog (tu fallback CJK). Tranh o vuong □. */
    static Font fontFor(String text, int style) {
        for (String name : new String[]{"Segoe UI", "Microsoft YaHei UI", "Microsoft JhengHei UI"}) {
            Font f = new Font(name, style, 12);
            if (f.getFamily().equalsIgnoreCase(name) && f.canDisplayUpTo(text) == -1) return f;
        }
        return new Font(Font.DIALOG, style, 12);   // font logic: Java tu ghep font du phong cho tung ky tu
    }

    // ----------------------------------------------------------------------- ve
    private Layout layout(Graphics2D g, Item it) {
        if (it.layout != null && it.layoutScale == cfg.fontScale + getWidth()) return (Layout) it.layout;
        Font base = fontFor(it.dst, Font.PLAIN);
        double s0 = Math.max(10, Math.min(64, it.lh * 0.92 * cfg.fontScale));
        int availR = Math.max(it.w, getWidth() - it.x - 2);
        int[] widths = {it.w, Math.min(availR, (int) (it.w * 1.35))};
        Layout res = null;
        outer:
        for (int w : widths) {
            for (double s = s0; s >= 9; s -= 1) {
                Font f = base.deriveFont((float) s);
                FontMetrics fm = g.getFontMetrics(f);
                List<String> ls = wrap(it.dst, fm, Math.max(10, w - 2));
                int hh = ls.size() * fm.getHeight();
                if (hh <= it.h + 3) {
                    res = new Layout(f, ls, maxW(ls, fm), hh, fm.getHeight(), fm.getAscent());
                    break outer;
                }
            }
        }
        if (res == null) {        // khong vua: giu co chu doc duoc, cho phep tran xuong duoi
            Font f = base.deriveFont(10f);
            FontMetrics fm = g.getFontMetrics(f);
            List<String> ls = wrap(it.dst, fm, Math.max(10, widths[1] - 2));
            res = new Layout(f, ls, maxW(ls, fm), ls.size() * fm.getHeight(), fm.getHeight(), fm.getAscent());
        }
        it.layout = res;
        it.layoutScale = cfg.fontScale + getWidth();
        return res;
    }

    private static int maxW(List<String> ls, FontMetrics fm) {
        int m = 0;
        for (String s : ls) m = Math.max(m, fm.stringWidth(s));
        return m;
    }

    static List<String> wrap(String text, FontMetrics fm, int maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            String test = cur.length() == 0 ? word : cur + " " + word;
            if (fm.stringWidth(test) <= maxW) {
                cur.setLength(0);
                cur.append(test);
                continue;
            }
            if (cur.length() > 0) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            if (fm.stringWidth(word) <= maxW) {
                cur.append(word);
            } else {                              // tu qua dai: cat theo ky tu
                for (char ch : word.toCharArray()) {
                    if (cur.length() > 0 && fm.stringWidth(cur.toString() + ch) > maxW) {
                        lines.add(cur.toString());
                        cur.setLength(0);
                    }
                    cur.append(ch);
                }
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    private static double lum(Color c) {
        double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    private static double contrast(Color a, Color b) {
        double l1 = lum(a), l2 = lum(b);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private Color textColor(Item it) {
        String t = cfg.textColor;
        if (t != null && t.startsWith("#") && t.length() == 7) {
            try {
                return Color.decode(t);
            } catch (NumberFormatException ignored) {
            }
        }
        if (contrast(it.fg, it.bg) >= 3.0) return it.fg;
        return lum(it.bg) < 0.4 ? Color.WHITE : Color.BLACK;
    }

    private void paintItem(Graphics2D g, Item it) {
        Layout L = layout(g, it);
        int pad = 2;
        int fw = Math.max(it.w, L.width + 2) + 2 * pad;
        int fh = Math.max(it.h, L.height) + 2 * pad;
        int fx = it.x - pad, fy = it.y - pad;
        if (it.patch != null) g.drawImage(it.patch, it.x, it.y, it.w, it.h, null);
        // nen phuc tap + da co mieng nen inpaint: chi can lop mo de chu de doc; con lai dung mau nen lay mau quanh chu
        int alpha = (it.complex && it.patch != null) ? Math.min(cfg.bgOpacity, 140) : cfg.bgOpacity;
        g.setColor(new Color(it.bg.getRed(), it.bg.getGreen(), it.bg.getBlue(), Math.max(0, Math.min(255, alpha))));
        g.fillRoundRect(fx, fy, fw, fh, 4, 4);
        g.setFont(L.font);
        g.setColor(textColor(it));
        int ty = fy + pad + Math.max(0, (fh - 2 * pad - L.height) / 2) + L.ascent;
        for (String line : L.lines) {
            g.drawString(line, it.x + 1, ty);
            ty += L.lineH;
        }
    }
}
