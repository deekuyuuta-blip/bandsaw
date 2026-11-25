package jp.bandsaw.pc;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 音声入力。PC のマイク（Java Sound）または ffmpeg（カメラの RTSP・録画ファイル） */
public abstract class AudioInput implements AutoCloseable {
    public static final int SR = 48000;

    /** 読んだサンプル数（-1 = 終わり）。x は -1〜1 */
    public abstract int read(float[] x) throws IOException;

    public abstract String describe();

    @Override public abstract void close();

    // ------------------------------------------------------------ マイク
    public static List<String> micNames() {
        List<String> l = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format());
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                Mixer m = AudioSystem.getMixer(mi);
                if (m.isLineSupported(info)) l.add(mi.getName());
            } catch (Exception ignored) { }
        }
        return l;
    }

    static AudioFormat format() { return new AudioFormat(SR, 16, 1, true, false); }

    public static final class Mic extends AudioInput {
        private final TargetDataLine line;
        private final byte[] buf = new byte[8192];
        private final String name;

        public Mic(String micName) throws Exception {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format());
            TargetDataLine l = null;
            String nm = "既定のマイク";
            if (micName != null && !micName.isEmpty()) {
                for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                    if (!mi.getName().equals(micName)) continue;
                    Mixer m = AudioSystem.getMixer(mi);
                    if (m.isLineSupported(info)) { l = (TargetDataLine) m.getLine(info); nm = mi.getName(); }
                }
            }
            if (l == null) l = (TargetDataLine) AudioSystem.getLine(info);
            line = l;
            line.open(format(), SR / 2 * 2);
            line.start();
            name = nm;
        }

        @Override public int read(float[] x) {
            int want = Math.min(x.length * 2, buf.length);
            int n = line.read(buf, 0, want);
            int k = n / 2;
            for (int i = 0; i < k; i++) x[i] = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8)) / 32768f;
            return k;
        }

        @Override public String describe() { return "マイク: " + name + " / " + SR + "Hz"; }

        @Override public void close() {
            try { line.stop(); line.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------ ffmpeg
    /** ffmpeg.exe の場所。設定 → アプリと同じフォルダの ffmpeg\ffmpeg.exe → PATH の順に探す */
    public static String findFfmpeg(String configured) {
        if (configured != null && !configured.trim().isEmpty() && new File(configured.trim()).exists()) return configured.trim();
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "ffmpeg.exe" : "ffmpeg";
        String app = System.getProperty("jpackage.app-path");
        List<File> cands = new ArrayList<>();
        if (app != null) {
            File d = new File(app).getParentFile();
            cands.add(new File(d, "ffmpeg" + File.separator + exe));
            cands.add(new File(d, exe));
            cands.add(new File(d, "app" + File.separator + "ffmpeg" + File.separator + exe));
        }
        cands.add(new File(System.getProperty("user.dir"), "ffmpeg" + File.separator + exe));
        for (File f : cands) if (f.exists()) return f.getAbsolutePath();
        return exe;   // PATH
    }

    public static final class Ffmpeg extends AudioInput {
        private final Process proc;
        private final InputStream in;
        private final byte[] buf = new byte[8192];
        private final String src;
        private int carry = -1;

        /** realtime: 録画ファイルを実時間で流す（テスト用）。false なら最速で読む */
        public Ffmpeg(String ffmpeg, String source, boolean realtime) throws IOException {
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpeg);
            cmd.add("-hide_banner"); cmd.add("-loglevel"); cmd.add("error"); cmd.add("-nostdin");
            if (source.toLowerCase().startsWith("rtsp://")) { cmd.add("-rtsp_transport"); cmd.add("tcp"); }
            if (realtime) cmd.add("-re");
            String s = source;
            if (s.startsWith("dshow:")) {             // Windows のマイクを ffmpeg で: dshow:audio=マイク名
                cmd.add("-f"); cmd.add("dshow");
                s = s.substring(6);
            }
            cmd.add("-i"); cmd.add(s);
            cmd.add("-vn"); cmd.add("-ac"); cmd.add("1"); cmd.add("-ar"); cmd.add(String.valueOf(SR));
            cmd.add("-f"); cmd.add("s16le"); cmd.add("-");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            proc = pb.start();
            in = proc.getInputStream();
            src = source;
        }

        @Override public int read(float[] x) throws IOException {
            int want = Math.min(x.length * 2, buf.length);
            int off = 0;
            if (carry >= 0) { buf[0] = (byte) carry; off = 1; carry = -1; }
            int n = in.read(buf, off, want - off);
            if (n < 0) return off > 0 ? 0 : -1;
            n += off;
            if ((n & 1) == 1) { carry = buf[n - 1] & 0xFF; n--; }
            int k = n / 2;
            for (int i = 0; i < k; i++) x[i] = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8)) / 32768f;
            return k;
        }

        @Override public String describe() {
            String s = src.replaceAll("//[^/@]*@", "//***@");   // パスワードを隠す
            return "ffmpeg: " + s + " / " + SR + "Hz";
        }

        @Override public void close() {
            proc.destroy();
            try { in.close(); } catch (IOException ignored) { }
        }
    }
}
