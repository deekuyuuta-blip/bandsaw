package jp.bandsaw.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 打音スコア（瞬時・30秒平均）としきい値、注意・警報・帯鋸交換の時刻を描く。
 * 表示期間は 15分〜7日。点が多いときは画面の幅に合わせて間引く（瞬時は最大、平均は平均）。
 */
public class ChartView extends View {
    /** 縦線で示すイベント */
    public static final class Mark {
        public final long t;
        public final int color;
        public final String label;
        public Mark(long t, int color, String label) { this.t = t; this.color = color; this.label = label; }
    }

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
            inst = new Paint(Paint.ANTI_ALIAS_FLAG), avg = new Paint(Paint.ANTI_ALIAS_FLAG),
            th = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG), empty = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** {時刻ms, 瞬時, 平均} */
    private List<double[]> pts = new ArrayList<>();
    private List<Mark> marks = new ArrayList<>();
    private Core.Thresholds thr;
    private long t0, t1;
    private String emptyText = "";
    private final float d;

    public ChartView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        grid.setColor(Ui.LINE); grid.setStrokeWidth(1 * d);
        text.setColor(Ui.MUTED); text.setTextSize(11 * d);
        inst.setColor(0xFF3A4F63); inst.setStyle(Paint.Style.STROKE); inst.setStrokeWidth(1.2f * d);
        avg.setColor(Ui.ACCENT); avg.setStyle(Paint.Style.STROKE); avg.setStrokeWidth(2.4f * d);
        th.setStyle(Paint.Style.STROKE); th.setStrokeWidth(1.4f * d);
        mark.setStrokeWidth(1.6f * d); mark.setTextSize(10 * d); mark.setFakeBoldText(true);
        empty.setColor(Ui.MUTED); empty.setTextSize(13 * d); empty.setTextAlign(Paint.Align.CENTER);
        long now = System.currentTimeMillis();
        t0 = now - 15 * 60 * 1000; t1 = now;
    }

    public void setData(List<double[]> p, Core.Thresholds t, long from, long to, List<Mark> m, String emptyMsg) {
        pts = p; thr = t; t0 = from; t1 = to; marks = m != null ? m : new ArrayList<>(); emptyText = emptyMsg == null ? "" : emptyMsg;
        invalidate();
    }

    /** 画面の幅に合わせて間引く */
    private List<double[]> downsample(float plotW) {
        int cols = Math.max(50, (int) (plotW / (1.5f * d)));
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
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), L = 30 * d, R = 6 * d, T = 14 * d, B = 20 * d;
        float plotW = w - L - R;
        List<double[]> ps = downsample(plotW);
        double ymax = 24;
        for (double[] p : ps) if (!Double.isNaN(p[1])) ymax = Math.max(ymax, p[1] + 1);
        final double ym = ymax;
        for (int v = 0; v <= ym; v += 5) {
            float y = (float) (T + (1 - v / ym) * (h - T - B));
            c.drawLine(L, y, w - R, y, grid);
            c.drawText(String.valueOf(v), 4 * d, y + 4 * d, text);
        }
        // 時刻の目盛（ラベルの間隔が約70dp以上になる刻み）
        long span = Math.max(1, t1 - t0);
        long step = DAY;
        for (long s : STEPS) if (plotW * s / span >= 70 * d) { step = s; break; }
        Calendar cal = Calendar.getInstance();
        long off = cal.getTimeZone().getOffset(t0);
        long first = ((t0 + off) / step + 1) * step - off;
        for (long s = first; s <= t1; s += step) {
            cal.setTimeInMillis(s);
            float x = L + (float) (s - t0) / span * plotW;
            c.drawLine(x, T, x, h - B, grid);
            String lab = (step >= DAY || (cal.get(Calendar.HOUR_OF_DAY) == 0 && cal.get(Calendar.MINUTE) == 0 && span > DAY))
                    ? String.format(Locale.JAPAN, "%d/%d", cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                    : String.format(Locale.JAPAN, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
            c.drawText(lab, x - text.measureText(lab) / 2, h - 5 * d, text);
        }
        if (thr != null) {
            th.setPathEffect(new DashPathEffect(new float[]{6 * d, 4 * d}, 0));
            th.setColor(Ui.WARN); hline(c, thr.warn, ym, L, R, T, B, w, h);
            th.setPathEffect(new DashPathEffect(new float[]{2 * d, 3 * d}, 0));
            th.setColor(Ui.ALARM); hline(c, thr.alarmAvg, ym, L, R, T, B, w, h);
            th.setPathEffect(new DashPathEffect(new float[]{6 * d, 4 * d}, 0));
            hline(c, thr.inst, ym, L, R, T, B, w, h);
        }
        // 点の間隔の3倍以上あいたら線を切る（停止中など）
        long gap = Math.max(span / Math.max(50, (int) (plotW / (1.5f * d))) * 3, pts == ps ? 6000 : 16000);
        series(c, ps, 1, inst, gap, ym, L, T, B, plotW, h);
        series(c, ps, 2, avg, gap, ym, L, T, B, plotW, h);
        for (Mark m : marks) {
            if (m.t < t0 || m.t > t1) continue;
            float x = L + (float) (m.t - t0) / span * plotW;
            mark.setColor(m.color);
            c.drawLine(x, T, x, h - B, mark);
            float tw = mark.measureText(m.label);
            c.drawText(m.label, Math.min(x + 2 * d, w - R - tw), T - 3 * d, mark);
        }
        if (ps.isEmpty() && !emptyText.isEmpty()) c.drawText(emptyText, L + plotW / 2, (T + h - B) / 2, empty);
    }

    private void hline(Canvas c, double v, double ym, float L, float R, float T, float B, float w, float h) {
        float y = (float) (T + (1 - Math.max(0, v) / ym) * (h - T - B));
        c.drawLine(L, y, w - R, y, th);
    }

    private void series(Canvas c, List<double[]> ps, int k, Paint p, long gap, double ym, float L, float T, float B, float plotW, float h) {
        Path path = new Path();
        boolean on = false;
        double lastT = -1e18;
        long span = Math.max(1, t1 - t0);
        for (double[] q : ps) {
            double v = q[k];
            if (Double.isNaN(v) || q[0] - lastT > gap) on = false;
            lastT = q[0];
            if (Double.isNaN(v)) continue;
            float x = L + (float) ((q[0] - t0) / span) * plotW;
            float y = (float) (T + (1 - Math.max(0, v) / ym) * (h - T - B));
            if (!on) { path.moveTo(x, y); on = true; } else path.lineTo(x, y);
        }
        c.drawPath(path, p);
    }
}
