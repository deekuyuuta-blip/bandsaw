package jp.bandsaw.pc;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/** 確認するまで鳴り続けるサイレン（注意: ゆっくり2音、警報: 速い2音） */
public final class AlarmSound {
    private Thread th;
    private volatile boolean stop;

    public synchronized void start(boolean warn) {
        stopSound();
        stop = false;
        th = new Thread(() -> play(warn), "alarm-sound");
        th.setDaemon(true);
        th.start();
    }

    public synchronized void stopSound() {
        stop = true;
        if (th != null) {
            try { th.join(500); } catch (InterruptedException ignored) { }
            th = null;
        }
    }

    private void play(boolean warn) {
        int sr = 44100;
        AudioFormat f = new AudioFormat(sr, 16, 1, true, false);
        try (SourceDataLine l = AudioSystem.getSourceDataLine(f)) {
            l.open(f, sr / 5);
            l.start();
            double f1 = warn ? 880 : 1200, f2 = warn ? 660 : 900, seg = warn ? 0.5 : 0.18;
            byte[] b = new byte[(int) (sr * seg) * 2];
            double ph = 0;
            boolean hi = true;
            while (!stop) {
                double fr = hi ? f1 : f2;
                for (int i = 0; i < b.length / 2; i++) {
                    ph += 2 * Math.PI * fr / sr;
                    double env = Math.min(1, Math.min(i, b.length / 2 - i) / 200.0);
                    short v = (short) (Math.signum(Math.sin(ph)) * 0.35 * env * 32767);
                    b[2 * i] = (byte) v;
                    b[2 * i + 1] = (byte) (v >> 8);
                }
                l.write(b, 0, b.length);
                hi = !hi;
                if (warn && hi) {   // 注意は2音のあと少し休む
                    byte[] z = new byte[sr];
                    l.write(z, 0, z.length);
                }
            }
            l.flush();
            l.stop();
        } catch (Exception ignored) {
            // 音が出せない PC（サウンドデバイスなし）でも監視は続ける
        }
    }
}
