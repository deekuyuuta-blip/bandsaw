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

/** 直近15分の打音スコア（瞬時・30秒平均）としきい値を描く */
public class ChartView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
            inst = new Paint(Paint.ANTI_ALIAS_FLAG), avg = new Paint(Paint.ANTI_ALIAS_FLAG), th = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<double[]> pts = new ArrayList<>();
    private Core.Thresholds thr;
    private final float d;

    public ChartView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        grid.setColor(Ui.LINE); grid.setStrokeWidth(1 * d);
        text.setColor(Ui.MUTED); text.setTextSize(11 * d);
        inst.setColor(0xFF3A4F63); inst.setStyle(Paint.Style.STROKE); inst.setStrokeWidth(1.2f * d);
        avg.setColor(Ui.ACCENT); avg.setStyle(Paint.Style.STROKE); avg.setStrokeWidth(2.6f * d);
        th.setStyle(Paint.Style.STROKE); th.setStrokeWidth(1.4f * d);
    }

    public void setData(List<double[]> p, Core.Thresholds t) {
        pts = p; thr = t; invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), L = 30 * d, R = 6 * d, T = 8 * d, B = 20 * d;
        long t1 = System.currentTimeMillis(), t0 = t1 - 15 * 60 * 1000;
        double ymax = 24;
        for (double[] p : pts) if (!Double.isNaN(p[1])) ymax = Math.max(ymax, p[1] + 1);
        final double ym = ymax;
        for (int v = 0; v <= ym; v += 5) {
            float y = (float) (T + (1 - v / ym) * (h - T - B));
            c.drawLine(L, y, w - R, y, grid);
            c.drawText(String.valueOf(v), 4 * d, y + 4 * d, text);
        }
        Calendar cal = Calendar.getInstance();
        for (long s = (t0 / 180000 + 1) * 180000; s <= t1; s += 180000) {
            cal.setTimeInMillis(s);
            float x = L + (float) (s - t0) / (t1 - t0) * (w - L - R);
            String lab = String.format(Locale.JAPAN, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
            c.drawText(lab, x - text.measureText(lab) / 2, h - 5 * d, text);
        }
        if (thr != null) {
            th.setPathEffect(new DashPathEffect(new float[]{6 * d, 4 * d}, 0));
            th.setColor(Ui.WARN); line(c, thr.warn, ym, L, R, T, B, w, h);
            th.setPathEffect(new DashPathEffect(new float[]{2 * d, 3 * d}, 0));
            th.setColor(Ui.ALARM); line(c, thr.alarmAvg, ym, L, R, T, B, w, h);
            th.setPathEffect(new DashPathEffect(new float[]{6 * d, 4 * d}, 0));
            line(c, thr.inst, ym, L, R, T, B, w, h);
        }
        series(c, 1, inst, t0, t1, ym, L, R, T, B, w, h);
        series(c, 2, avg, t0, t1, ym, L, R, T, B, w, h);
    }

    private void line(Canvas c, double v, double ym, float L, float R, float T, float B, float w, float h) {
        float y = (float) (T + (1 - Math.max(0, v) / ym) * (h - T - B));
        c.drawLine(L, y, w - R, y, th);
    }

    private void series(Canvas c, int k, Paint p, long t0, long t1, double ym, float L, float R, float T, float B, float w, float h) {
        Path path = new Path();
        boolean on = false;
        double lastT = 0;
        for (double[] q : pts) {
            double v = q[k];
            if (Double.isNaN(v) || q[0] - lastT > 5000) on = false;
            lastT = q[0];
            if (Double.isNaN(v)) continue;
            float x = L + (float) ((q[0] - t0) / (t1 - t0)) * (w - L - R);
            float y = (float) (T + (1 - Math.max(0, v) / ym) * (h - T - B));
            if (!on) { path.moveTo(x, y); on = true; } else path.lineTo(x, y);
        }
        c.drawPath(path, p);
    }
}
