package vn.st;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

/** Mot khoi van ban da dich, toa do (logical px) tuong doi so voi goc vung dich. */
public final class Item {
    public final int x, y, w, h, lh;
    public final String src, dst;
    public final Color bg, fg;
    public final boolean complex;
    public final BufferedImage patch;      // mieng nen da xoa chu (nen phuc tap), co the null
    // cache bo cuc de ve (do OverlayWindow quan ly)
    Object layout;
    double layoutScale = -1;

    public Item(int x, int y, int w, int h, int lh, String src, String dst, Color bg, Color fg,
                boolean complex, BufferedImage patch) {
        this.x = x; this.y = y; this.w = w; this.h = h; this.lh = lh;
        this.src = src; this.dst = dst; this.bg = bg; this.fg = fg;
        this.complex = complex; this.patch = patch;
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
