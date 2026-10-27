package jp.bandsaw.monitor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 設定・帯鋸の基準・記録を端末内に保存する */
public final class Store {
    private final SharedPreferences sp;

    public Store(Context ctx) {
        sp = ctx.getSharedPreferences("bandsaw", Context.MODE_PRIVATE);
    }

    // ---------------- 設定
    public static final String[] CFG_KEYS = {"bladeMm", "wheelMm", "rpmMin", "rpmMax", "warnAvg", "alarmAvg",
            "alarmInst", "learnMin", "riseWarn", "riseAlarm", "idleDb"};
    public static final String[] CFG_LABELS = {"帯鋸の全長 (mm)", "ホイール直径 (mm)", "ホイール回転数 最小 (rpm)",
            "ホイール回転数 最大 (rpm)", "注意: 30秒平均 (dB)", "警報: 30秒平均 (dB)", "警報: 瞬時 (dB)",
            "基準を覚える時間 (分)", "基準からの上昇で注意 (dB)", "基準からの上昇で警報 (dB)", "停止とみなす音量 (dB)"};

    public static double get(Core.Config c, String k) {
        switch (k) {
            case "bladeMm": return c.bladeMm;
            case "wheelMm": return c.wheelMm;
            case "rpmMin": return c.rpmMin;
            case "rpmMax": return c.rpmMax;
            case "warnAvg": return c.warnAvg;
            case "alarmAvg": return c.alarmAvg;
            case "alarmInst": return c.alarmInst;
            case "learnMin": return c.learnMin;
            case "riseWarn": return c.riseWarn;
            case "riseAlarm": return c.riseAlarm;
            case "idleDb": return c.idleDb;
        }
        return 0;
    }

    public static void set(Core.Config c, String k, double v) {
        switch (k) {
            case "bladeMm": c.bladeMm = v; break;
            case "wheelMm": c.wheelMm = v; break;
            case "rpmMin": c.rpmMin = v; break;
            case "rpmMax": c.rpmMax = v; break;
            case "warnAvg": c.warnAvg = v; break;
            case "alarmAvg": c.alarmAvg = v; break;
            case "alarmInst": c.alarmInst = v; break;
            case "learnMin": c.learnMin = Math.max(1, v); break;
            case "riseWarn": c.riseWarn = v; break;
            case "riseAlarm": c.riseAlarm = v; break;
            case "idleDb": c.idleDb = v; break;
        }
    }

    public Core.Config loadConfig() {
        Core.Config c = new Core.Config();
        try {
            JSONObject o = new JSONObject(sp.getString("cfg", "{}"));
            for (String k : CFG_KEYS) if (o.has(k)) set(c, k, o.getDouble(k));
        } catch (Exception ignored) { }
        return c;
    }

    public void saveConfig(Core.Config c) {
        try {
            JSONObject o = new JSONObject();
            for (String k : CFG_KEYS) o.put(k, get(c, k));
            sp.edit().putString("cfg", o.toString()).apply();
        } catch (Exception ignored) { }
    }

    public boolean soundOn() { return sp.getBoolean("sound", true); }
    public void setSoundOn(boolean on) { sp.edit().putBoolean("sound", on).apply(); }

    // ---------------- PLC（SLMP）
    public static final class Plc {
        public boolean on = false, udp = false;
        public String host = "192.168.3.39", dev = "D";
        public int port = 5000, head = 1000;

        /** 画面表示用の先頭デバイス名（W は16進表記） */
        public String headName() {
            return "W".equalsIgnoreCase(dev) ? "W" + Integer.toHexString(head).toUpperCase() : dev.toUpperCase() + head;
        }
    }

    public Plc loadPlc() {
        Plc p = new Plc();
        p.on = sp.getBoolean("plcOn", false);
        p.udp = sp.getBoolean("plcUdp", false);
        p.host = sp.getString("plcHost", p.host);
        p.port = sp.getInt("plcPort", p.port);
        p.dev = sp.getString("plcDev", p.dev);
        p.head = sp.getInt("plcHead", p.head);
        return p;
    }

    public void savePlc(Plc p) {
        sp.edit().putBoolean("plcOn", p.on).putBoolean("plcUdp", p.udp).putString("plcHost", p.host)
                .putInt("plcPort", p.port).putString("plcDev", p.dev).putInt("plcHead", p.head).apply();
    }

    // ---------------- 帯鋸の基準
    public Core.Blade loadBlade() {
        Core.Blade b = new Core.Blade();
        try {
            JSONObject o = new JSONObject(sp.getString("blade", "{}"));
            b.learning = o.optBoolean("learning", false);
            b.hasBase = o.optBoolean("hasBase", false);
            b.baseP90 = o.optDouble("p90", 0);
            b.baseInst99 = o.optDouble("inst99", 0);
            b.baseMed = o.optDouble("med", 0);
            b.baseAt = o.optLong("at", 0);
            b.startedAt = o.optLong("started", 0);
            JSONArray a = o.optJSONArray("avgs"), s = o.optJSONArray("inst");
            if (a != null) for (int i = 0; i < a.length(); i++) b.avgs.add(a.getDouble(i));
            if (s != null) for (int i = 0; i < s.length(); i++) b.inst.add(s.getDouble(i));
        } catch (Exception ignored) { }
        return b;
    }

    public void saveBlade(Core.Blade b) {
        try {
            JSONObject o = new JSONObject();
            o.put("learning", b.learning);
            o.put("hasBase", b.hasBase);
            o.put("p90", b.baseP90);
            o.put("inst99", b.baseInst99);
            o.put("med", b.baseMed);
            o.put("at", b.baseAt);
            o.put("started", b.startedAt);
            JSONArray a = new JSONArray(), s = new JSONArray();
            for (double v : b.avgs) a.put(v);
            for (double v : b.inst) s.put(v);
            o.put("avgs", a);
            o.put("inst", s);
            sp.edit().putString("blade", o.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ---------------- 記録
    public static final class Event {
        public long t;
        public String type, text;
        public double score;
    }

    public synchronized void addEvent(long t, String type, double score, String text) {
        try {
            JSONArray a = new JSONArray(sp.getString("events", "[]"));
            JSONArray n = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("t", t);
            o.put("type", type);
            o.put("score", Double.isNaN(score) ? JSONObject.NULL : Math.round(score * 10) / 10.0);
            o.put("text", text);
            n.put(o);
            for (int i = 0; i < a.length() && i < 499; i++) n.put(a.get(i));
            sp.edit().putString("events", n.toString()).apply();
        } catch (Exception ignored) { }
    }

    public synchronized List<Event> events() {
        List<Event> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("events", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Event e = new Event();
                e.t = o.getLong("t");
                e.type = o.getString("type");
                e.score = o.isNull("score") ? Double.NaN : o.getDouble("score");
                e.text = o.optString("text", "");
                l.add(e);
            }
        } catch (Exception ignored) { }
        return l;
    }

    public synchronized void clearEvents() { sp.edit().remove("events").apply(); }
}
