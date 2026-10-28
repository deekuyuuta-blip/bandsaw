package jp.bandsaw.monitor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 打音スコアの履歴を端末内に保存する（Android 非依存）。
 * 0.5秒ごとの判定結果を5秒ごとにまとめ、日ごとのファイル hist_yyyyMMdd.csv に追記する。
 * 1行 = 時刻ms,瞬時スコア最大,30秒平均の平均,30秒平均の最大,状態（0〜5 の最大）
 */
public final class HistoryStore {
    public static final long BUCKET_MS = 5000;
    private final File dir;
    private final int keepDays;

    private long bStart = -1;
    private double instMax, avgSum, avgMax;
    private int avgN, instN, stMax;

    public HistoryStore(File dir, int keepDays) {
        this.dir = dir;
        this.keepDays = keepDays;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        prune(System.currentTimeMillis());
    }

    private static String dayKey(long t) {
        return new SimpleDateFormat("yyyyMMdd", Locale.JAPAN).format(new Date(t));
    }

    private File fileOf(long t) { return new File(dir, "hist_" + dayKey(t) + ".csv"); }

    private void reset(long b) {
        bStart = b;
        instMax = Double.NaN; avgMax = Double.NaN;
        avgSum = 0; avgN = 0; instN = 0; stMax = 0;
    }

    /** 0.5秒ごとの判定結果を入れる（score/avg は NaN 可） */
    public synchronized void add(long t, double inst, double avg, int state) {
        long b = t - (t % BUCKET_MS);
        if (bStart >= 0 && b != bStart) flush();
        if (bStart != b) reset(b);
        if (!Double.isNaN(inst)) { instMax = instN == 0 ? inst : Math.max(instMax, inst); instN++; }
        if (!Double.isNaN(avg)) { avgSum += avg; avgN++; avgMax = Double.isNaN(avgMax) ? avg : Math.max(avgMax, avg); }
        stMax = Math.max(stMax, state);
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.2f", v);
    }

    /** まとめ中の5秒分をファイルに書く */
    public synchronized void flush() {
        if (bStart < 0) return;
        if (instN > 0 || avgN > 0) {
            String line = bStart + "," + num(instMax) + "," + num(avgN > 0 ? avgSum / avgN : Double.NaN) + "," + num(avgMax) + "," + stMax + "\n";
            try (FileOutputStream o = new FileOutputStream(fileOf(bStart), true)) {
                o.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }
        bStart = -1;
    }

    private static double parse(String s) {
        if (s.isEmpty()) return Double.NaN;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /** [t0, t1) の履歴 {時刻, 瞬時最大, 平均, 平均最大, 状態} */
    public List<double[]> load(long t0, long t1) {
        List<double[]> out = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t0);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        while (c.getTimeInMillis() < t1) {
            File f = fileOf(c.getTimeInMillis());
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String[] p = line.split(",", -1);
                        if (p.length < 5) continue;     // 書きかけの行など
                        long t;
                        try { t = Long.parseLong(p[0]); } catch (NumberFormatException e) { continue; }
                        if (t < t0 || t >= t1) continue;
                        double st;
                        try { st = Integer.parseInt(p[4].trim()); } catch (NumberFormatException e) { st = 0; }
                        out.add(new double[]{t, parse(p[1]), parse(p[2]), parse(p[3]), st});
                    }
                } catch (IOException ignored) { }
            }
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    /** 期間の履歴を CSV（Excel で開ける UTF-8 BOM 付き）で書き出す。書いた行数を返す */
    public int writeCsv(OutputStream os, long t0, long t1) throws IOException {
        List<double[]> rows = load(t0, t1);
        SimpleDateFormat f = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN);
        String[] st = {"停止", "準備中", "平常", "学習中", "注意", "警報"};
        Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        w.write("﻿日時,瞬時スコア最大dB,30秒平均dB,30秒平均の最大dB,状態\r\n");
        for (double[] r : rows) {
            int s = (int) r[4];
            w.write(f.format(new Date((long) r[0])) + "," + num(r[1]) + "," + num(r[2]) + "," + num(r[3]) + ","
                    + (s >= 0 && s < st.length ? st[s] : String.valueOf(s)) + "\r\n");
        }
        w.flush();
        return rows.size();
    }

    /** keepDays より古いファイルを消す */
    public void prune(long now) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        String limit = dayKey(now - keepDays * 86400000L);
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith("hist_") && n.endsWith(".csv") && n.substring(5, 13).compareTo(limit) < 0) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** 履歴の合計サイズ（バイト） */
    public long totalBytes() {
        File[] fs = dir.listFiles();
        long s = 0;
        if (fs != null) for (File f : fs) s += f.length();
        return s;
    }
}
