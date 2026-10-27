package jp.bandsaw.monitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 帯鋸 打音監視の判定コア（Android 非依存）。
 * Web 版 src/core.js と同じ処理。
 *
 *  1. 1.5〜4kHz の帯域だけ取り出す（4次バターワース HP + LP）
 *  2. 5ms ごとの音の強さ（包絡線, 200点/秒）
 *  3. 0.5秒ごとに直近8.5秒の包絡線を対数化・2Hz未満を除去して FFT
 *  4. 帯鋸1周の周波数 f0 と 2*f0 に、周りより何dB鋭い山があるか → 瞬時スコア
 *  5. 30秒平均と周波数の安定度で 注意/警報 を判定。帯鋸ごとの基準にも対応
 */
public final class Core {
    private Core() {}

    // ------------------------------------------------------------ 設定
    public static final class Config {
        public double bladeMm = 8350, wheelMm = 1200, rpmMin = 720, rpmMax = 830;
        public double bandLo = 1500, bandHi = 4000, windowSec = 8.5, hopSec = 0.5, idleDb = -60;
        public double avgSec = 30, lockRatio = 0.7;
        public double warnAvg = 8, alarmAvg = 11, alarmInst = 14;
        public int alarmHold = 3;
        public double riseWarn = 4, riseAlarm = 7, riseInst = 5;
        public double learnMin = 10, learnAlarmAvg = 16;
        public int clearHold = 240;

        public double[] bladeRange() {
            double circ = Math.PI * wheelMm / 1000.0, len = bladeMm / 1000.0;
            return new double[]{rpmMin / 60.0 * circ / len, rpmMax / 60.0 * circ / len};
        }
    }

    // ------------------------------------------------------------ 1回の判定結果
    public static final class Result {
        public double bandDb, fullDb, peak, jumpDb;
        public boolean ready;
        public double freq = Double.NaN, score = Double.NaN, p1, p2;
        public double avg = Double.NaN, lock = Double.NaN;
        public String state = "IDLE";
        public Thresholds th;
    }

    public static final class Thresholds {
        public double warn, alarmAvg, inst;
        public String mode; // fixed / learning / blade
    }

    // ------------------------------------------------------------ 帯鋸ごとの基準
    public static final class Blade {
        public boolean learning = false;
        public ArrayList<Double> avgs = new ArrayList<>(), inst = new ArrayList<>();
        public boolean hasBase = false;
        public double baseP90, baseInst99, baseMed;
        public long baseAt, startedAt;
    }

    // ------------------------------------------------------------ フィルタ
    private static final class Biquad {
        double b0, b1, b2, a1, a2, z1, z2;
        Biquad(boolean lowpass, double f, double q, double sr) {
            double w = 2 * Math.PI * f / sr, c = Math.cos(w), s = Math.sin(w), a = s / (2 * q);
            double bb0, bb1, bb2;
            if (lowpass) { bb0 = (1 - c) / 2; bb1 = 1 - c; bb2 = (1 - c) / 2; }
            else { bb0 = (1 + c) / 2; bb1 = -(1 + c); bb2 = (1 + c) / 2; }
            double a0 = 1 + a;
            b0 = bb0 / a0; b1 = bb1 / a0; b2 = bb2 / a0; a1 = -2 * c / a0; a2 = (1 - a) / a0;
        }
        double step(double v) {
            double y = b0 * v + z1;
            z1 = b1 * v - a1 * y + z2;
            z2 = b2 * v - a2 * y;
            return y;
        }
    }

    // ------------------------------------------------------------ FFT
    static double[] fftPow(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            int h = len >> 1;
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < h; k++) {
                    int a = i + k, b = a + h;
                    double tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
                    double nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
        double[] p = new double[n / 2 + 1];
        for (int i = 0; i <= n / 2; i++) p[i] = re[i] * re[i] + im[i] * im[i];
        return p;
    }

    public static double median(double[] v, int n) {
        if (n == 0) return Double.NaN;
        double[] a = Arrays.copyOf(v, n);
        Arrays.sort(a);
        return (n % 2 == 1) ? a[(n - 1) / 2] : (a[n / 2 - 1] + a[n / 2]) / 2;
    }

    public static double median(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return median(a, a.length);
    }

    public static double pct(List<Double> l, double q) {
        if (l.isEmpty()) return Double.NaN;
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        Arrays.sort(a);
        double idx = (a.length - 1) * q;
        int lo = (int) Math.floor(idx), hi = (int) Math.ceil(idx);
        return a[lo] + (a[hi] - a[lo]) * (idx - lo);
    }

    // ------------------------------------------------------------ 検出器
    public static final class Detector {
        private final Config c;
        private final int blk, winN, hopN;
        private final double envFs, fr, fmin, fmax;
        private final Biquad[] filt;
        private final double[] env;
        private int envLen = 0, envPos = 0, accN = 0, hopCnt = 0, fullN = 0;
        private double acc = 0, fullAcc = 0, peak = 0;
        private final ArrayDeque<Double> fullHist = new ArrayDeque<>();
        private static final int NFFT = 16384;
        private final double[] re = new double[NFFT], im = new double[NFFT];

        public Detector(Config cfg, double sr) {
            c = cfg;
            blk = Math.max(1, (int) Math.round(sr / 200.0));
            envFs = sr / blk;
            winN = (int) Math.round(c.windowSec * envFs);
            hopN = (int) Math.round(c.hopSec * envFs);
            filt = new Biquad[]{new Biquad(false, c.bandLo, 0.5412, sr), new Biquad(false, c.bandLo, 1.3066, sr),
                    new Biquad(true, c.bandHi, 0.5412, sr), new Biquad(true, c.bandHi, 1.3066, sr)};
            env = new double[winN];
            fr = envFs / NFFT;
            double[] r = c.bladeRange();
            fmin = r[0]; fmax = r[1];
        }

        public double[] range() { return new double[]{fmin, fmax}; }

        private double prominence(double[] p, double f) {
            double pk = 0;
            int i0 = Math.max(1, (int) Math.floor((f - 1.5) / fr)), i1 = Math.min(p.length - 1, (int) Math.ceil((f + 1.5) / fr));
            double[] nb = new double[i1 - i0 + 1];
            int nn = 0;
            for (int i = i0; i <= i1; i++) {
                double d = Math.abs(i * fr - f);
                if (d <= 0.1) pk = Math.max(pk, p[i]);
                else if (d > 0.25 && d < 1.5) nb[nn++] = p[i];
            }
            return 10 * Math.log10(pk / (median(nb, nn) + 1e-30) + 1e-30);
        }

        private void analyze(Result r) {
            int n = winN;
            double[] e = new double[n];
            for (int i = 0; i < n; i++) e[i] = Math.log(env[(envPos + i) % n] + 1e-6);
            int k2 = (int) Math.floor(envFs / 2), half = k2 / 2;
            double[] cs = new double[n + 1];
            for (int i = 0; i < n; i++) cs[i + 1] = cs[i] + e[i];
            int t0 = (int) Math.floor(envFs / 4), m = n - 2 * t0;
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            for (int j = 0; j < m; j++) {
                int i = j + t0;
                int a = Math.max(0, i - half), b = Math.min(n, i - half + k2);
                double ma = (cs[b] - cs[a]) / k2;
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * j / (m - 1));
                re[j] = (e[i] - ma) * w;
            }
            double[] p = fftPow(re, im);
            double best = -1, f0 = fmin;
            for (int i = (int) Math.ceil(fmin / fr); i <= (int) Math.floor(fmax / fr); i++)
                if (p[i] > best) { best = p[i]; f0 = i * fr; }
            r.freq = f0;
            r.p1 = prominence(p, f0);
            r.p2 = prominence(p, 2 * f0);
            r.score = (r.p1 + r.p2) / 2;
        }

        /** x[0..len) を入れ、0.5秒ごとの結果を out に追加する */
        public void push(float[] x, int len, List<Result> out) {
            for (int i = 0; i < len; i++) {
                double v = x[i];
                double a = Math.abs(v);
                if (a > peak) peak = a;
                fullAcc += v * v; fullN++;
                for (Biquad f : filt) v = f.step(v);
                acc += v * v; accN++;
                if (accN == blk) {
                    env[envPos] = Math.sqrt(acc / blk + 1e-12);
                    envPos = (envPos + 1) % winN;
                    if (envLen < winN) envLen++;
                    acc = 0; accN = 0; hopCnt++;
                    if (hopCnt == hopN) {
                        hopCnt = 0;
                        double s2 = 0;
                        for (double ev : env) s2 += ev * ev;
                        Result r = new Result();
                        r.bandDb = envLen > 0 ? 20 * Math.log10(Math.sqrt(s2 / envLen) + 1e-9) : -120;
                        r.fullDb = 20 * Math.log10(Math.sqrt(fullAcc / Math.max(1, fullN)) + 1e-9);
                        Double prev = null;
                        if (fullHist.size() >= 4) {
                            double[] h = new double[fullHist.size()]; int k = 0;
                            for (double d : fullHist) h[k++] = d;
                            prev = median(h, h.length);
                        }
                        fullHist.addLast(r.fullDb);
                        if (fullHist.size() > Math.round(5 / c.hopSec)) fullHist.removeFirst();
                        r.peak = peak;
                        r.jumpDb = prev == null ? 0 : r.fullDb - prev;
                        r.ready = envLen >= winN;
                        if (r.ready) analyze(r);
                        out.add(r);
                        fullAcc = 0; fullN = 0; peak = 0;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ 判定
    public static final class Judge {
        public static final String[] LEVELS = {"NORMAL", "WARN", "ALARM"};
        private final Config c;
        private final int histN, learnNeed;
        private final ArrayDeque<double[]> hist = new ArrayDeque<>();
        private String state = "NORMAL";
        private int nAlarm = 0, nClear = 0, breakCool = 0;
        private double lastF = Double.NaN;
        public Blade blade;

        public Judge(Config cfg, Blade b) {
            c = cfg;
            blade = b != null ? b : new Blade();
            histN = Math.max(2, (int) Math.round(c.avgSec / c.hopSec));
            learnNeed = (int) Math.round(c.learnMin * 60 / c.hopSec);
        }

        public String state() { return state; }

        public Thresholds thresholds() {
            Thresholds t = new Thresholds();
            if (!blade.hasBase) {
                t.warn = c.warnAvg; t.alarmAvg = c.alarmAvg; t.inst = c.alarmInst;
                t.mode = blade.learning ? "learning" : "fixed";
            } else {
                t.warn = Math.max(c.warnAvg, blade.baseP90 + c.riseWarn);
                t.alarmAvg = Math.max(c.alarmAvg, blade.baseP90 + c.riseAlarm);
                t.inst = Math.max(c.alarmInst, blade.baseInst99 + c.riseInst);
                t.mode = "blade";
            }
            return t;
        }

        public double learnProgress() {
            if (blade.learning) return Math.min(1.0, blade.avgs.size() / (double) learnNeed);
            return blade.hasBase ? 1 : 0;
        }

        public void startLearning(long now) {
            blade = new Blade();
            blade.learning = true;
            blade.startedAt = now;
            state = "NORMAL"; nAlarm = 0; nClear = 0;
        }

        public void acknowledgeReset() { state = "NORMAL"; nAlarm = 0; nClear = 0; }

        private static int idx(String s) { for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].equals(s)) return i; return 0; }

        /** 戻り値: 発生したイベント (WARN/ALARM/BREAK/LOUD/CLEAR/LEARNED/BASE_HIGH) */
        public List<String> update(Result r, long now) {
            List<String> ev = new ArrayList<>();
            breakCool = Math.max(0, breakCool - 1);
            if (r.jumpDb >= 8 && r.peak >= 0.9 && breakCool == 0) {
                ev.add("NORMAL".equals(state) ? "LOUD" : "BREAK");
                breakCool = (int) Math.round(10 / c.hopSec);
            }
            if (!r.ready || Double.isNaN(r.score) || r.bandDb < c.idleDb) {
                r.state = "NORMAL".equals(state) ? "IDLE" : state;
                return ev;
            }
            double s = r.score;
            hist.addLast(new double[]{s, r.freq});
            if (hist.size() > histN) hist.removeFirst();
            boolean full = hist.size() >= histN * 0.8;
            double sum = 0; double[] fs = new double[hist.size()]; int k = 0;
            for (double[] h : hist) { sum += h[0]; fs[k++] = h[1]; }
            double avg = sum / hist.size();
            double mf = median(fs, fs.length);
            int lk = 0; for (double f : fs) if (Math.abs(f - mf) <= 0.06) lk++;
            double lock = lk / (double) fs.length;
            r.avg = avg; r.lock = lock;
            boolean sustained = full && lock >= c.lockRatio;

            if (blade.learning) {
                if (full) { blade.avgs.add(avg); blade.inst.add(s); }
                if (blade.avgs.size() >= learnNeed) {
                    blade.baseP90 = pct(blade.avgs, 0.9);
                    blade.baseInst99 = pct(blade.inst, 0.99);
                    blade.baseMed = median(blade.avgs);
                    blade.baseAt = now;
                    blade.hasBase = true;
                    blade.learning = false;
                    blade.avgs.clear(); blade.inst.clear();
                    ev.add(blade.baseP90 >= c.warnAvg ? "BASE_HIGH" : "LEARNED");
                }
            }
            Thresholds th = thresholds();
            if (!Double.isNaN(lastF) && Math.abs(r.freq - lastF) > 0.1) nAlarm = 0;
            lastF = r.freq;
            String next = state;
            if (blade.learning) {
                if (sustained && avg >= c.learnAlarmAvg) next = "ALARM";
            } else {
                nAlarm = s >= th.inst ? nAlarm + 1 : 0;
                if (nAlarm >= c.alarmHold || (sustained && avg >= th.alarmAvg)) next = "ALARM";
                else if (sustained && avg >= th.warn && "NORMAL".equals(state)) next = "WARN";
            }
            if (!"NORMAL".equals(state) && full && avg < th.warn - 3) {
                if (++nClear >= c.clearHold) { next = "NORMAL"; nClear = 0; ev.add("CLEAR"); }
            } else nClear = 0;
            if (idx(next) > idx(state)) ev.add(next);
            state = next;
            r.state = state;
            r.th = th;
            return ev;
        }
    }
}
