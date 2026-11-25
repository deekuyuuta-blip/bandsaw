package jp.bandsaw.pc;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.Border;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** 平らなボタン・角丸パネル・アイコン */
final class Widgets {
    private Widgets() {}

    static final class FlatButton extends JButton {
        private Color fill, stroke;

        FlatButton(String text, Color fill, Color fg, float size) {
            super(text);
            this.fill = fill;
            setForeground(fg);
            setFont(Theme.font(size).deriveFont(Font.BOLD));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new Insets(10, 14, 10, 14));
        }

        void setColors(Color fill, Color fg, Color stroke) {
            this.fill = fill; this.stroke = stroke; setForeground(fg); repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color f = fill;
            if (!isEnabled()) f = f.darker();
            else if (getModel().isPressed()) f = f.darker();
            else if (getModel().isRollover()) f = f.brighter();
            g.setColor(f);
            RoundRectangle2D r = new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1, 12, 12);
            g.fill(r);
            if (stroke != null) { g.setColor(stroke); g.setStroke(new BasicStroke(1f)); g.draw(r); }
            if (isFocusOwner()) { g.setColor(Theme.ACCENT); g.setStroke(new BasicStroke(2f)); g.draw(r); }
            g.dispose();
            super.paintComponent(g0);
        }
    }

    static FlatButton button(String text, Color fill, Color fg) {
        FlatButton b = new FlatButton(text, fill, fg, 15f);
        if (fill == Theme.PANEL2) b.setColors(fill, fg, Theme.LINE);
        return b;
    }

    /** 角丸の背景と枠を描くパネル */
    static class Card extends JPanel {
        Color fill, stroke;
        float strokeW;

        Card(Color fill, Color stroke, float strokeW) {
            this.fill = fill; this.stroke = stroke; this.strokeW = strokeW;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        }

        void setColors(Color fill, Color stroke) { this.fill = fill; this.stroke = stroke; repaint(); }

        /** 縦に並べたときに余白まで伸びないようにする */
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D r = new RoundRectangle2D.Double(strokeW / 2, strokeW / 2, getWidth() - strokeW, getHeight() - strokeW, 16, 16);
            g.setColor(fill);
            g.fill(r);
            if (strokeW > 0) { g.setColor(stroke); g.setStroke(new BasicStroke(strokeW)); g.draw(r); }
            g.dispose();
            super.paintComponent(g0);
        }
    }

    /** 縦に並べたときに伸びない透明なパネル */
    static JPanel row(java.awt.LayoutManager lm) {
        JPanel p = new JPanel(lm) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        p.setOpaque(false);
        return p;
    }

    static JLabel label(String s, float size, Color c, boolean bold) {
        JLabel l = new JLabel(s);
        l.setFont(Theme.font(size).deriveFont(bold ? Font.BOLD : Font.PLAIN));
        l.setForeground(c);
        return l;
    }

    static Border pad(int t, int l, int b, int r) { return BorderFactory.createEmptyBorder(t, l, b, r); }

    static <T extends JComponent> T fixHeight(T c, int h) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        c.setPreferredSize(new Dimension(c.getPreferredSize().width, h));
        return c;
    }

    /** 帯鋸のループと打音のアイコン */
    static Image icon(int size, Color dot) {
        BufferedImage im = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 64f;
        g.setColor(Theme.BG);
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, 14 * s, 14 * s));
        g.setColor(Theme.ACCENT);
        g.setStroke(new BasicStroke(5 * s));
        g.draw(new RoundRectangle2D.Double(10 * s, 18 * s, 44 * s, 22 * s, 22 * s, 22 * s));
        g.setColor(Theme.WARN);
        g.fillRect((int) (29 * s), (int) (45 * s), (int) (6 * s), (int) (12 * s));
        if (dot != null) { g.setColor(dot); g.fillOval((int) (44 * s), (int) (4 * s), (int) (16 * s), (int) (16 * s)); }
        g.dispose();
        return im;
    }
}
