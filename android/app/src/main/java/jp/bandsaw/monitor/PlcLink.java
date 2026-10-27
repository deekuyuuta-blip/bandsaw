package jp.bandsaw.monitor;

/**
 * シンプルCPU通信で PLC とやり取りするデータの割り当てと、PLC からの指令の解釈（Android 非依存）。
 *
 * スマホ側デバイス（先頭 base, 初期値 D0）
 *   アプリ → PLC（PLC が「読出し」で取り込む, base+0〜base+19 の20点）
 *     +0  状態 0停止/待機 1準備中 2平常 3学習中 4注意 5警報
 *     +1  生存カウンタ（0.5秒ごとに+1。待機中も増える）
 *     +2  30秒平均×10   +3 瞬時×10   +4 未確認の警報(1)
 *     +5  指令の受付カウンタ（指令を1つ受けるごとに+1）
 *     +6  最後の指令の結果 0=OK 2=監視を開始できない 3=PLC連携が無効 101〜110=設定値の範囲外（何番目か）
 *     +7  学習の進み具合 %   +8 監視中=1 / 待機中=0
 *     +10〜+19 今の設定値（並びは PARAM_* と同じ）
 *   PLC → アプリ（PLC が「書込み」で書く, base+20〜base+39 の20点）
 *     +20 指令ビット .0開始 .1停止 .2帯鋸交換(基準の学習) .3警報確認 .4設定を反映（0→1 で1回実行）
 *     +21〜+30 新しい設定値（.4 で反映）
 */
public final class PlcLink {
    public static final int READ_POINTS = 20, WRITE_OFFSET = 20, WRITE_POINTS = 20;
    public static final int CMD_START = 0, CMD_STOP = 1, CMD_LEARN = 2, CMD_ACK = 3, CMD_APPLY = 4;
    public static final int RES_OK = 0, RES_MIC = 2, RES_DISABLED = 3;

    /** 設定値の並び（名前, 単位の倍率, 最小, 最大） */
    public static final String[] PARAM_NAMES = {"帯鋸の全長 mm", "ホイール直径 mm", "回転数 最小 rpm", "回転数 最大 rpm",
            "注意 平均 dB×10", "警報 平均 dB×10", "警報 瞬時 dB×10", "基準を覚える時間 分", "基準からの上昇 注意 dB×10", "基準からの上昇 警報 dB×10"};
    private static final double[] SCALE = {1, 1, 1, 1, 10, 10, 10, 1, 10, 10};
    private static final double[] MIN = {1000, 200, 50, 50, 1, 1, 1, 1, 0, 0};
    private static final double[] MAX = {30000, 3000, 3000, 3000, 60, 60, 60, 120, 40, 40};

    public interface Actions {
        int start();
        int stop();
        int learn();
        int ack();
        int apply(Core.Config next);
    }

    private final SlmpServer srv;
    private final int base;
    private int prevCmd = -1;     // -1 = まだ一度も読んでいない
    private int accepted = 0, lastResult = 0;

    public PlcLink(SlmpServer srv, int base) {
        this.srv = srv;
        this.base = base;
    }

    public static double[] toArray(Core.Config c) {
        return new double[]{c.bladeMm, c.wheelMm, c.rpmMin, c.rpmMax, c.warnAvg, c.alarmAvg, c.alarmInst, c.learnMin, c.riseWarn, c.riseAlarm};
    }

    public static short[] encode(Core.Config c) {
        double[] v = toArray(c);
        short[] w = new short[v.length];
        for (int i = 0; i < v.length; i++) w[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v[i] * SCALE[i])));
        return w;
    }

    /** PLC からの設定値を検査して新しい Config を返す。範囲外なら code[0] に 101〜110 */
    public static Core.Config decode(short[] w, Core.Config cur, int[] code) {
        Core.Config n = new Core.Config();
        for (String k : Store.CFG_KEYS) Store.set(n, k, Store.get(cur, k));
        double[] v = new double[w.length];
        for (int i = 0; i < w.length; i++) {
            v[i] = w[i] / SCALE[i];
            if (v[i] < MIN[i] || v[i] > MAX[i]) { code[0] = 101 + i; return null; }
        }
        if (v[3] < v[2]) { code[0] = 104; return null; }          // 回転数 最大 < 最小
        if (v[5] < v[4]) { code[0] = 106; return null; }          // 警報平均 < 注意平均
        n.bladeMm = v[0]; n.wheelMm = v[1]; n.rpmMin = v[2]; n.rpmMax = v[3];
        n.warnAvg = v[4]; n.alarmAvg = v[5]; n.alarmInst = v[6]; n.learnMin = v[7]; n.riseWarn = v[8]; n.riseAlarm = v[9];
        code[0] = RES_OK;
        return n;
    }

    /** 状態を PLC が読む領域に書く */
    public void publish(int state, int heartbeat, short avg10, short inst10, boolean alarmUnacked,
                        int learnPct, boolean monitoring, Core.Config cfg) {
        short[] w = new short[READ_POINTS];
        w[0] = (short) state;
        w[1] = (short) heartbeat;
        w[2] = avg10;
        w[3] = inst10;
        w[4] = (short) (alarmUnacked ? 1 : 0);
        w[5] = (short) accepted;
        w[6] = (short) lastResult;
        w[7] = (short) learnPct;
        w[8] = (short) (monitoring ? 1 : 0);
        short[] p = encode(cfg);
        System.arraycopy(p, 0, w, 10, p.length);
        srv.setWords(base, w);
    }

    /** PLC が書いた指令を調べ、0→1 になったビットの指令を実行する。実行した指令の数を返す */
    public int poll(Core.Config cur, Actions a) {
        if (srv.writeCount() == 0) return 0;                    // PLC からまだ一度も書かれていない
        int cmd = srv.getWord(base + WRITE_OFFSET) & 0xFFFF;
        if (prevCmd < 0) { prevCmd = cmd; return 0; }          // 最初に届いた時点で 1 のビットでは動かない
        int rise = cmd & ~prevCmd;
        prevCmd = cmd;
        if (rise == 0) return 0;
        int n = 0;
        // 設定反映 → 帯鋸交換 → 警報確認 → 停止 → 開始 の順に処理
        if ((rise & (1 << CMD_APPLY)) != 0) {
            short[] w = new short[10];
            for (int i = 0; i < 10; i++) w[i] = srv.getWord(base + WRITE_OFFSET + 1 + i);
            int[] code = {0};
            Core.Config next = decode(w, cur, code);
            done(next == null ? code[0] : a.apply(next)); n++;
        }
        if ((rise & (1 << CMD_LEARN)) != 0) { done(a.learn()); n++; }
        if ((rise & (1 << CMD_ACK)) != 0) { done(a.ack()); n++; }
        if ((rise & (1 << CMD_STOP)) != 0 && (rise & (1 << CMD_START)) == 0) { done(a.stop()); n++; }
        if ((rise & (1 << CMD_START)) != 0 && (rise & (1 << CMD_STOP)) == 0) { done(a.start()); n++; }
        return n;
    }

    private void done(int result) {
        accepted = (accepted + 1) & 0x7FFF;
        lastResult = result;
    }

    public int accepted() { return accepted; }
    public int lastResult() { return lastResult; }
}
