package jp.bandsaw.pc;

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
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 記録（開始・停止・注意・警報・帯鋸交換・PLC など）。新しい順に最大2000件を events.tsv に保存 */
public final class EventLog {
    public static final class Event {
        public final long t;
        public final String type, text;
        public final double score;
        Event(long t, String type, double score, String text) { this.t = t; this.type = type; this.score = score; this.text = text; }
    }

    private static final int MAX = 2000;
    private final File file;
    private final List<Event> list = new ArrayList<>();   // 新しい順

    public EventLog(File dir) {
        file = new File(dir, "events.tsv");
        if (file.exists()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t", 4);
                    if (p.length < 4) continue;
                    try {
                        list.add(new Event(Long.parseLong(p[0]), p[1], p[2].isEmpty() ? Double.NaN : Double.parseDouble(p[2]), p[3]));
                    } catch (NumberFormatException ignored) { }
                }
            } catch (IOException ignored) { }
        }
    }

    public synchronized void add(long t, String type, double score, String text) {
        list.add(0, new Event(t, type, score, text.replace('\t', ' ').replace('\n', ' ')));
        if (list.size() > MAX) list.subList(MAX, list.size()).clear();
        save();
    }

    public synchronized List<Event> all() { return new ArrayList<>(list); }

    private void save() {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (Event e : list)
                w.write(e.t + "\t" + e.type + "\t" + (Double.isNaN(e.score) ? "" : String.format(Locale.ROOT, "%.1f", e.score)) + "\t" + e.text + "\n");
        } catch (IOException ignored) { }
    }

    public static String label(String t) {
        switch (t) {
            case "ALARM": return "警報";
            case "WARN": return "注意";
            case "BREAK": return "破断音";
            case "LOUD": return "大きな音";
            case "CLEAR": return "解除";
            case "LEARNED": return "基準OK";
            case "BASE_HIGH": return "基準";
            case "LEARN": return "交換";
            case "START": return "開始";
            case "STOP": return "停止";
            case "TEST": return "テスト";
            case "PLC": return "PLC";
            case "INPUT": return "入力";
            default: return t;
        }
    }

    /** 期間の記録を CSV（UTF-8 BOM 付き）で書き出す */
    public void writeCsv(OutputStream os, long t0, long t1) throws IOException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN);
        Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        w.write("﻿日時,種類,スコアdB,内容\r\n");
        List<Event> l = all();
        for (int i = l.size() - 1; i >= 0; i--) {
            Event e = l.get(i);
            if (e.t < t0 || e.t > t1) continue;
            w.write(f.format(new Date(e.t)) + "," + label(e.type) + "," + (Double.isNaN(e.score) ? "" : String.format(Locale.ROOT, "%.1f", e.score))
                    + ",\"" + e.text.replace("\"", "\"\"") + "\"\r\n");
        }
        w.flush();
    }
}
