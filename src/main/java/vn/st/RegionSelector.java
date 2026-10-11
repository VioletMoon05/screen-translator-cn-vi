package vn.st;

import javax.swing.JWindow;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;

/** Lop phu mo toan bo man hinh (ke ca nhieu man hinh); keo chuot trai de chon vung. */
public final class RegionSelector extends JWindow {
    private Point start, cur;
    private final Rectangle virtual;
    private boolean done = false;
    private final Consumer<Rectangle> callback;

    public static Rectangle virtualBounds() {
        Rectangle r = new Rectangle();
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
            r = r.union(d.getDefaultConfiguration().getBounds());
        return r;
    }

    /** callback nhan vung theo toa do man hinh, hoac null neu huy. */
    public static void select(Consumer<Rectangle> cb) {
        new RegionSelector(cb).setVisible(true);
    }

    private RegionSelector(Consumer<Rectangle> cb) {
        super();
        this.callback = cb;
        this.virtual = virtualBounds();
        setAlwaysOnTop(true);
        setBackground(new Color(0, 0, 0, 1));
        setBounds(virtual);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        setFocusable(true);

        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilitiesIsRight(e)) {
                    finish(null);
                    return;
                }
                start = e.getPoint();
                cur = start;
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                cur = e.getPoint();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (start == null) return;
                cur = e.getPoint();
                Rectangle r = rect();
                if (r.width < 20 || r.height < 20) {
                    finish(null);
                } else {
                    finish(new Rectangle(virtual.x + r.x, virtual.y + r.y, r.width, r.height));
                }
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) finish(null);
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                toFront();
                requestFocus();
            }
        });
    }

    private static boolean SwingUtilitiesIsRight(MouseEvent e) {
        return javax.swing.SwingUtilities.isRightMouseButton(e);
    }

    private Rectangle rect() {
        return new Rectangle(Math.min(start.x, cur.x), Math.min(start.y, cur.y),
                Math.abs(start.x - cur.x), Math.abs(start.y - cur.y));
    }

    private void finish(Rectangle r) {
        if (done) return;
        done = true;
        dispose();
        callback.accept(r);
    }

    @Override
    public void paint(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new Color(0, 0, 0, 90));          // lam mo nhe man hinh
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("Segoe UI", Font.BOLD, 16));
        g.setColor(Color.WHITE);
        g.drawString("Kéo chuột trái để chọn vùng cần dịch  •  Esc hoặc chuột phải: hủy", 40, 40);
        if (start != null && cur != null) {
            Rectangle r = rect();
            g.setColor(new Color(255, 255, 255, 14));  // alpha > 0 de van nhan su kien chuot
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(new Color(0, 200, 120));
            g.setStroke(new BasicStroke(2f));
            g.drawRect(r.x, r.y, r.width, r.height);
            String s = r.width + " × " + r.height;
            g.setColor(Color.WHITE);
            g.drawString(s, r.x + 4, Math.max(18, r.y - 6));
        }
        g.dispose();
    }
}
