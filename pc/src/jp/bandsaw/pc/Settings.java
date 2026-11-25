package jp.bandsaw.pc;

import jp.bandsaw.monitor.Core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 設定・帯鋸の基準を保存する。
 * Windows: %APPDATA%\BandsawMonitor\settings.properties / blade.properties
 */
public final class Settings {
    public static final String[] CFG_KEYS = {"bladeMm", "wheelMm", "rpmMin", "rpmMax", "warnAvg", "alarmAvg",
            "alarmInst", "learnMin", "riseWarn", "riseAlarm", "idleDb"};
    public static final String[] CFG_LABELS = {"帯鋸の全長 (mm)", "ホイール直径 (mm)", "ホイール回転数 最小 (rpm)",
            "ホイール回転数 最大 (rpm)", "注意: 30秒平均 (dB)", "警報: 30秒平均 (dB)", "警報: 瞬時 (dB)",
            "基準を覚える時間 (分)", "基準からの上昇で注意 (dB)", "基準からの上昇で警報 (dB)", "停止とみなす音量 (dB)"};

    public final File dir;
    private final File file, bladeFile;
    private final Properties p = new Properties();

    // ---- 入力
    public String inputType = "mic";          // mic / ffmpeg
    public String micName = "";               // 空 = 既定のマイク
    public String ffmpegSource = "rtsp://user:pass@192.168.1.100:554/stream1";
    public String ffmpegPath = "";            // 空 = 同梱 / PATH の ffmpeg
    // ---- 警報
    public boolean sound = true, toFront = true;
    // ---- PLC
    public boolean plcOn = false, plcServer = true, plcUdp = true;
    public String plcHost = "192.168.3.39", plcDev = "D";
    public int plcPort = 5000, plcHead = 0;
    // ---- 起動時
    public boolean autoStart = false;

    public Settings() {
        String appdata = System.getenv("APPDATA");
        dir = appdata != null ? new File(appdata, "BandsawMonitor") : new File(System.getProperty("user.home"), ".bandsaw-monitor");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        file = new File(dir, "settings.properties");
        bladeFile = new File(dir, "blade.properties");
        load();
    }

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

    private void load() {
        if (file.exists()) {
            try (InputStreamReader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ignored) { }
        }
        inputType = p.getProperty("inputType", inputType);
        micName = p.getProperty("micName", micName);
        ffmpegSource = p.getProperty("ffmpegSource", ffmpegSource);
        ffmpegPath = p.getProperty("ffmpegPath", ffmpegPath);
        sound = bool("sound", sound);
        toFront = bool("toFront", toFront);
        plcOn = bool("plcOn", plcOn);
        plcServer = bool("plcServer", plcServer);
        plcUdp = bool("plcUdp", plcUdp);
        plcHost = p.getProperty("plcHost", plcHost);
        plcDev = p.getProperty("plcDev", plcDev);
        plcPort = integer("plcPort", plcPort);
        plcHead = integer("plcHead", plcHead);
        autoStart = bool("autoStart", autoStart);
    }

    private boolean bool(String k, boolean d) { return Boolean.parseBoolean(p.getProperty(k, String.valueOf(d))); }

    private int integer(String k, int d) {
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(d)).trim()); } catch (NumberFormatException e) { return d; }
    }

    public Core.Config config() {
        Core.Config c = new Core.Config();
        for (String k : CFG_KEYS) {
            String v = p.getProperty("cfg." + k);
            if (v != null) try { set(c, k, Double.parseDouble(v)); } catch (NumberFormatException ignored) { }
        }
        return c;
    }

    public synchronized void saveConfig(Core.Config c) {
        for (String k : CFG_KEYS) p.setProperty("cfg." + k, fmt(get(c, k)));
        save();
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%s", v);
    }

    public synchronized void save() {
        p.setProperty("inputType", inputType);
        p.setProperty("micName", micName);
        p.setProperty("ffmpegSource", ffmpegSource);
        p.setProperty("ffmpegPath", ffmpegPath);
        p.setProperty("sound", String.valueOf(sound));
        p.setProperty("toFront", String.valueOf(toFront));
        p.setProperty("plcOn", String.valueOf(plcOn));
        p.setProperty("plcServer", String.valueOf(plcServer));
        p.setProperty("plcUdp", String.valueOf(plcUdp));
        p.setProperty("plcHost", plcHost);
        p.setProperty("plcDev", plcDev);
        p.setProperty("plcPort", String.valueOf(plcPort));
        p.setProperty("plcHead", String.valueOf(plcHead));
        p.setProperty("autoStart", String.valueOf(autoStart));
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            p.store(w, "BandsawMonitor settings");
        } catch (IOException ignored) { }
    }

    /** PC 側の先頭デバイス名（W は16進表記） */
    public String plcHeadName() {
        return "W".equalsIgnoreCase(plcDev) ? "W" + Integer.toHexString(plcHead).toUpperCase(Locale.ROOT) : plcDev.toUpperCase(Locale.ROOT) + plcHead;
    }

    // ------------------------------------------------------------ 帯鋸の基準
    public synchronized Core.Blade loadBlade() {
        Core.Blade b = new Core.Blade();
        Properties q = new Properties();
        if (bladeFile.exists()) {
            try (InputStreamReader r = new InputStreamReader(new FileInputStream(bladeFile), StandardCharsets.UTF_8)) {
                q.load(r);
            } catch (IOException ignored) { }
        }
        b.learning = Boolean.parseBoolean(q.getProperty("learning", "false"));
        b.hasBase = Boolean.parseBoolean(q.getProperty("hasBase", "false"));
        b.baseP90 = dbl(q, "p90");
        b.baseInst99 = dbl(q, "inst99");
        b.baseMed = dbl(q, "med");
        b.baseAt = (long) dbl(q, "at");
        b.startedAt = (long) dbl(q, "started");
        b.avgs.addAll(list(q.getProperty("avgs", "")));
        b.inst.addAll(list(q.getProperty("inst", "")));
        return b;
    }

    private static double dbl(Properties q, String k) {
        try { return Double.parseDouble(q.getProperty(k, "0")); } catch (NumberFormatException e) { return 0; }
    }

    private static List<Double> list(String s) {
        List<Double> l = new ArrayList<>();
        for (String x : s.split(",")) {
            if (x.isEmpty()) continue;
            try { l.add(Double.parseDouble(x)); } catch (NumberFormatException ignored) { }
        }
        return l;
    }

    private static String join(List<Double> l) {
        StringBuilder sb = new StringBuilder();
        for (double v : l) { if (sb.length() > 0) sb.append(','); sb.append(String.format(Locale.ROOT, "%.3f", v)); }
        return sb.toString();
    }

    public synchronized void saveBlade(Core.Blade b) {
        Properties q = new Properties();
        q.setProperty("learning", String.valueOf(b.learning));
        q.setProperty("hasBase", String.valueOf(b.hasBase));
        q.setProperty("p90", String.valueOf(b.baseP90));
        q.setProperty("inst99", String.valueOf(b.baseInst99));
        q.setProperty("med", String.valueOf(b.baseMed));
        q.setProperty("at", String.valueOf(b.baseAt));
        q.setProperty("started", String.valueOf(b.startedAt));
        q.setProperty("avgs", join(b.avgs));
        q.setProperty("inst", join(b.inst));
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(bladeFile), StandardCharsets.UTF_8)) {
            q.store(w, "blade baseline");
        } catch (IOException ignored) { }
    }
}
