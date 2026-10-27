package vn.st;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Mot khoi van ban da dich. x,y,w,h la toa do logical (px) so voi goc vung dich, de ve overlay.
 * px,py,pw,ph la toa do VAT LY (px thuc cua anh chup) va ref la anh xam goc luc OCR: Pipeline dung chung de
 * xac minh chu goc con nguyen, va de dich ban dich theo chu khi trang cuon.
 */
public final class Item {
    public final int x, y, w, h, lh;
    public final String src, dst;
    public final Color bg, fg;
    public final boolean complex;
    public final BufferedImage patch;      // mieng nen da xoa chu (nen phuc tap), co the null
    final int px, py, pw, ph;
    final Pix.Patch ref;
    // cache bo cuc de ve (do OverlayWindow quan ly)
    Object layout;
    double layoutScale = -1;

    public Item(int x, int y, int w, int h, int lh, String src, String dst, Color bg, Color fg,
                boolean complex, BufferedImage patch) {
        this.x = x; this.y = y; this.w = w; this.h = h; this.lh = lh;
        this.src = src; this.dst = dst; this.bg = bg; this.fg = fg;
        this.complex = complex; this.patch = patch;
        this.px = 0; this.py = 0; this.pw = 0; this.ph = 0; this.ref = null;
    }

    /** Khoi moi tu toa do vat ly; toa do logical suy ra bang cach chia cho scale (khong tich luy sai so khi dich nhieu lan). */
    Item(int px, int py, int pw, int ph, Pix.Patch ref, double scale, int lh, String src, String dst,
         Color bg, Color fg, boolean complex, BufferedImage patch) {
        this.px = px; this.py = py; this.pw = pw; this.ph = ph; this.ref = ref;
        this.x = (int) Math.round(px / scale);
        this.y = (int) Math.round(py / scale);
        this.w = Math.max(1, (int) Math.round(pw / scale));
        this.h = Math.max(1, (int) Math.round(ph / scale));
        this.lh = lh; this.src = src; this.dst = dst; this.bg = bg; this.fg = fg;
        this.complex = complex; this.patch = patch;
    }

    /** Ban sao dich doc toi py moi (giu bo cuc da tinh vi chi doi y). */
    Item movedTo(int newPy, double scale) {
        Item n = new Item(px, newPy, pw, ph, ref, scale, lh, src, dst, bg, fg, complex, patch);
        n.layout = layout;
        n.layoutScale = layoutScale;
        return n;
    }

    Rectangle physRect() {
        return new Rectangle(px, py, pw, ph);
    }

    public Rectangle rect() {
        return new Rectangle(x, y, w, h);
    }

    public static Rectangle union(List<Item> l) {
        Rectangle r = null;
        for (Item i : l) r = r == null ? i.rect() : r.union(i.rect());
        return r;
    }
}
