package jp.bandsaw.pc;

import jp.bandsaw.monitor.Core;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** 打音スコア（瞬時・30秒平均）としきい値、注意・警報・帯鋸交換の縦線（Android 版 ChartView と同じ描き方） */
public class ChartPanel extends JPanel {
    public static final class Mark {
        final long t;
        final Color color;
        final String label;
        public Mark(long t, Color color, String label) { this.t = t; this.color = color; this.label = label; }
    }

    private List<double[]> pts = new ArrayList<>();
    private List<Mark> marks = new ArrayList<>();
    private Core.Thresholds thr;
    private long t0 = System.currentTimeMillis() - 15 * 60_000L, t1 = System.currentTimeMillis();
    private String emptyText = "";
    private boolean live = true;

    public ChartPanel() {
        setBackground(Theme.PANEL);
        setPreferredSize(new Dimension(600, 260));
    }

    public void setData(List<double[]> p, Core.Thresholds t, long from, long to, List<Mark> m, String empty, boolean fine) {
        pts = p; thr = t; t0 = from; t1 = to; marks = m != null ? m : new ArrayList<>(); emptyText = empty == null ? "" : empty; live = fine;
        repaint();
    }

    private List<double[]> downsample(double plotW) {
        int cols = Math.max(50, (int) (plotW / 1.5));
        if (pts.size() <= cols * 2) return pts;
        double colMs = (double) (t1 - t0) / cols;
        List<double[]> out = new ArrayList<>();
        int ci = -1; double iMax = Double.NaN, aSum = 0; int aN = 0; double tc = 0;
        for (double[] p : pts) {
            int c = (int) ((p[0] - t0) / colMs);
            if (c != ci) {
                if (ci >= 0) out.add(new double[]{tc, iMax, aN > 0 ? aSum / aN : Double.NaN});
                ci = c; iMax = Double.NaN; aSum = 0; aN = 0; tc = t0 + (c + 0.5) * colMs;
            }
            if (!Double.isNaN(p[1])) iMax = Double.isNaN(iMax) ? p[1] : Math.max(iMax, p[1]);
            if (!Double.isNaN(p[2])) { aSum += p[2]; aN++; }
        }
        if (ci >= 0) out.add(new double[]{tc, iMax, aN > 0 ? aSum / aN : Double.NaN});
        return out;
    }

    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
    private static final long[] STEPS = {MIN, 2 * MIN, 5 * MIN, 10 * MIN, 15 * MIN, 30 * MIN, HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY};

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        double L = 36, R = 10, T = 20, B = 24, plotW = w - L - R, plotH = h - T - B;
        Font small = Theme.font(12f);
        g.setFont(small);
        FontMetrics fm = g.getFontMetrics();
        List<double[]> ps = downsample(plotW);
        double ymax = 24;
        for (double[] p : ps) if (!Double.isNaN(p[1])) ymax = Math.max(ymax, p[1] + 1);
        final double ym = ymax;
        for (int v = 0; v <= ym; v += 5) {
            int y = (int) (T + (1 - v / ym) * plotH);
            g.setColor(Theme.LINE);
            g.drawLine((int) L, y, (int) (w - R), y);
            g.setColor(Theme.MUTED);
            g.drawString(String.valueOf(v), 6, y + 4);
        }
        long span = Math.max(1, t1 - t0);
        long step = DAY;
        for (long s : STEPS) if (plotW * s / span >= 80) { step = s; break; }
        Calendar cal = Calendar.getInstance();
        long off = cal.getTimeZone().getOffset(t0);
        for (long s = ((t0 + off) / step + 1) * step - off; s <= t1; s += step) {
            cal.setTimeInMillis(s);
            int x = (int) (L + (double) (s - t0) / span * plotW);
            g.setColor(Theme.LINE);
            g.drawLine(x, (int) T, x, (int) (h - B));
            String lab = (step >= DAY || (cal.get(Calendar.HOUR_OF_DAY) == 0 && cal.get(Calendar.MINUTE) == 0 && span > DAY))
                    ? String.format(Locale.JAPAN, "%d/%d", cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                    : String.format(Locale.JAPAN, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
            g.setColor(Theme.MUTED);
            g.drawString(lab, x - fm.stringWidth(lab) / 2, h - 6);
        }
        if (thr != null) {
            hline(g, thr.warn, Theme.WARN, new float[]{7, 5}, ym, L, R, T, plotH, w);
            hline(g, thr.alarmAvg, Theme.ALARM, new float[]{2, 4}, ym, L, R, T, plotH, w);
            hline(g, thr.inst, Theme.ALARM, new float[]{7, 5}, ym, L, R, T, plotH, w);
        }
        long gap = Math.max(span / Math.max(50, (int) (plotW / 1.5)) * 3, live && ps == pts ? 6000 : 16000);
        series(g, ps, 1, Theme.INST, 1.3f, gap, ym, L, T, plotW, plotH);
        series(g, ps, 2, Theme.ACCENT, 2.6f, gap, ym, L, T, plotW, plotH);
        g.setFont(Theme.font(11f).deriveFont(Font.BOLD));
        for (Mark m : marks) {
            if (m.t < t0 || m.t > t1) continue;
            int x = (int) (L + (double) (m.t - t0) / span * plotW);
            g.setColor(m.color);
            g.setStroke(new BasicStroke(1.8f));
            g.drawLine(x, (int) T, x, (int) (h - B));
            int tw = g.getFontMetrics().stringWidth(m.label);
            g.drawString(m.label, (int) Math.min(x + 3, w - R - tw), (int) T - 5);
        }
        if (ps.isEmpty() && !emptyText.isEmpty()) {
            g.setFont(Theme.font(14f));
            g.setColor(Theme.MUTED);
            int tw = g.getFontMetrics().stringWidth(emptyText);
            g.drawString(emptyText, (int) (L + plotW / 2 - tw / 2.0), (int) (T + plotH / 2));
        }
    }

    private void hline(Graphics2D g, double v, Color c, float[] dash, double ym, double L, double R, double T, double plotH, int w) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, dash, 0));
        g.setColor(c);
        int y = (int) (T + (1 - Math.max(0, v) / ym) * plotH);
        g.drawLine((int) L, y, (int) (w - R), y);
        g.setStroke(old);
    }

    private void series(Graphics2D g, List<double[]> ps, int k, Color c, float width, long gap, double ym, double L, double T, double plotW, double plotH) {
        Path2D.Double path = new Path2D.Double();
        boolean on = false;
        double lastT = -1e18;
        long span = Math.max(1, t1 - t0);
        for (double[] q : ps) {
            double v = q[k];
            if (Double.isNaN(v) || q[0] - lastT > gap) on = false;
            lastT = q[0];
            if (Double.isNaN(v)) continue;
            double x = L + (q[0] - t0) / span * plotW, y = T + (1 - Math.max(0, v) / ym) * plotH;
            if (!on) { path.moveTo(x, y); on = true; } else path.lineTo(x, y);
        }
        g.setColor(c);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
    }
}
