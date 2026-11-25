package jp.bandsaw.pc;

import jp.bandsaw.monitor.Core;
import jp.bandsaw.monitor.HistoryStore;
import jp.bandsaw.monitor.PlcLink;
import jp.bandsaw.monitor.Slmp;
import jp.bandsaw.monitor.SlmpServer;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PC 版の監視エンジン（Android 版 MonitorService と同じ動き）。
 *  ・監視: 音声入力 → 判定（Core）→ 注意・警報
 *  ・PLC: 待受（シンプルCPU通信で PLC が読みに来る・指令を受ける）または PLC へ書込み
 *  ・履歴: 5秒ごとに60日分（HistoryStore）
 * PLC 待受はアプリを開いている間ずっと動く（監視を止めても PLC から開始できる）。
 */
public final class Engine {
    public interface Listener {
        void onUpdate();
        void onAlarm(String level);
        void onAlarmCleared();
    }

    public final Settings settings;
    public final EventLog events;
    public final HistoryStore history;
    private final AlarmSound sound = new AlarmSound();
    private volatile Listener listener;

    // ---- 画面から読む状態
    public volatile boolean running = false;
    public volatile boolean alarming = false;
    public volatile Core.Result last = null;
    public volatile String state = "IDLE";
    public volatile double learnProgress = 0;
    public volatile Core.Thresholds thresholds = null;
    public volatile String error = null;
    public volatile String inputInfo = "";
    public volatile String plcStatus = "";
    public volatile boolean plcOk = false;
    /** 直近15分 {時刻ms, 瞬時, 30秒平均} */
    public final ArrayDeque<double[]> live = new ArrayDeque<>();

    private Core.Config cfg;
    private Core.Judge judge;
    private Thread worker;
    private volatile boolean stopFlag;
    private volatile AudioInput input;
    private final AtomicInteger heartbeat = new AtomicInteger();
    private long lastPersist = 0;

    // PLC
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "plc-ticker"); t.setDaemon(true); return t;
    });
    private SlmpServer server;
    private PlcLink link;
    private volatile long lastReqAt = 0;
    private volatile String lastPeer = "";
    private boolean serverReading = false;
    private final ExecutorService plcExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plc-client"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean plcBusy = new AtomicBoolean(false);
    private Slmp slmp;
    private long plcFailSince = 0;
    private int lastLoggedAccept = -1;

    public Engine(Settings s) {
        settings = s;
        events = new EventLog(s.dir);
        history = new HistoryStore(new File(s.dir, "history"), 60);
        cfg = s.config();
        ticker.scheduleWithFixedDelay(this::tick, 500, 500, TimeUnit.MILLISECONDS);
    }

    public void setListener(Listener l) { listener = l; }

    private void notifyUi() {
        Listener l = listener;
        if (l != null) l.onUpdate();
    }

    public Core.Config config() { return cfg; }

    public synchronized Core.Thresholds currentThresholds() {
        if (thresholds != null && running) return thresholds;
        return new Core.Judge(cfg, settings.loadBlade()).thresholds();
    }

    public boolean bladeLearning() {
        Core.Judge j = judge;
        if (running && j != null) return j.blade.learning;
        return settings.loadBlade().learning;
    }

    // ------------------------------------------------------------ PLC 連携の開始・終了（設定を変えたら呼ぶ）
    public synchronized void restartLink() {
        closeLink();
        if (!settings.plcOn) return;
        if (!settings.plcServer) { plcStatus = "PLC に接続しています…"; plcOk = false; plcFailSince = 0; return; }
        try {
            server = new SlmpServer(settings.plcPort, (peer, cmd, sub, ok) -> {
                lastReqAt = System.currentTimeMillis();
                lastPeer = peer;
                if (!ok) plcStatus = String.format(Locale.JAPAN, "PLC から未対応の要求（コマンド %04X / %04X）を受けました", cmd, sub);
            });
            server.start();
            link = new PlcLink(server, settings.plcHead);
            lastReqAt = 0;
            serverReading = false;
            publishLink(null);
        } catch (Exception e) {
            server = null;
            link = null;
            plcStatus = "ポート " + settings.plcPort + " で待ち受けできません（" + e.getMessage() + "）。ほかのソフトが使っていないか確認してください";
            events.add(System.currentTimeMillis(), "PLC", Double.NaN, plcStatus);
        }
        notifyUi();
    }

    private void closeLink() {
        if (server != null) { server.close(); server = null; }
        link = null;
        if (slmp != null) { final Slmp s = slmp; plcExec.submit(s::close); slmp = null; }
        plcStatus = "";
        plcOk = false;
    }

    public boolean linkActive() { return link != null; }

    // ------------------------------------------------------------ 監視の開始・停止
    /** @return PlcLink の結果コード */
    public synchronized int start() {
        if (running) return PlcLink.RES_OK;
        error = null;
        cfg = settings.config();
        AudioInput in;
        try {
            in = openInput();
        } catch (Exception e) {
            error = "音声入力を開けませんでした（" + e.getMessage() + "）。設定の「音声入力」を確認してください。";
            events.add(System.currentTimeMillis(), "INPUT", Double.NaN, error);
            notifyUi();
            return PlcLink.RES_MIC;
        }
        input = in;
        inputInfo = in.describe();
        running = true;
        stopFlag = false;
        synchronized (live) { live.clear(); }
        events.add(System.currentTimeMillis(), "START", Double.NaN, "監視開始（" + inputInfo + "）");
        worker = new Thread(this::loop, "audio");
        worker.setDaemon(true);
        worker.start();
        notifyUi();
        return PlcLink.RES_OK;
    }

    private AudioInput openInput() throws Exception {
        if ("ffmpeg".equals(settings.inputType))
            return new AudioInput.Ffmpeg(AudioInput.findFfmpeg(settings.ffmpegPath), settings.ffmpegSource,
                    settings.ffmpegSource.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|avi|mov|wav|m4a|mp3)$"));
        return new AudioInput.Mic(settings.micName);
    }

    public synchronized void stop() {
        if (!running) return;
        stopFlag = true;
        AudioInput in = input;
        if (in != null) in.close();
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(2000); } catch (InterruptedException ignored) { }
        }
        worker = null;
        input = null;
        running = false;
        state = "IDLE";
        last = null;
        events.add(System.currentTimeMillis(), "STOP", Double.NaN, "監視停止");
        acknowledge();
        if (judge != null) settings.saveBlade(judge.blade);
        history.flush();
        if (settings.plcOn && !settings.plcServer) sendPlc(null, true);
        publishLink(null);
        notifyUi();
    }

    public void shutdown() {
        stop();
        synchronized (this) { closeLink(); }
        ticker.shutdownNow();
        plcExec.shutdownNow();
        sound.stopSound();
    }

    public synchronized void startLearning() {
        long now = System.currentTimeMillis();
        if (judge != null && running) {
            judge.startLearning(now);
            settings.saveBlade(judge.blade);
        } else {
            Core.Blade b = new Core.Blade();
            b.learning = true;
            b.startedAt = now;
            settings.saveBlade(b);
        }
        events.add(now, "LEARN", Double.NaN, "帯鋸交換。基準の学習を開始");
        acknowledge();
        notifyUi();
    }

    public void acknowledge() {
        sound.stopSound();
        boolean was = alarming;
        alarming = false;
        Listener l = listener;
        if (was && l != null) l.onAlarmCleared();
        notifyUi();
    }

    /** 設定を保存したあとに呼ぶ */
    public void applySettings(boolean inputChanged) {
        boolean wasRunning = running;
        cfg = settings.config();
        if (wasRunning && inputChanged) { stop(); start(); }
        else if (wasRunning) { stop(); start(); }
        restartLink();
    }

    public void testAlarm() {
        events.add(System.currentTimeMillis(), "TEST", Double.NaN, "警報テスト");
        raise("ALARM");
    }

    // ------------------------------------------------------------ 音声ループ
    private void loop() {
        AudioInput in = input;
        Core.Config c = cfg;
        Core.Detector det = new Core.Detector(c, AudioInput.SR);
        synchronized (this) { judge = new Core.Judge(c, settings.loadBlade()); }
        float[] buf = new float[4096];
        List<Core.Result> out = new ArrayList<>();
        int saveCnt = 0;
        boolean stream = "ffmpeg".equals(settings.inputType) && !settings.ffmpegSource.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|avi|mov|wav|m4a|mp3)$");
        while (!stopFlag) {
            int n;
            try { n = in.read(buf); } catch (Exception e) { n = -1; }
            if (n < 0) {
                if (stopFlag) break;
                if (stream) {   // カメラの音声が途切れた → 5秒後につなぎ直す
                    events.add(System.currentTimeMillis(), "INPUT", Double.NaN, "音声入力が途切れました。5秒後につなぎ直します");
                    in.close();
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                    if (stopFlag) break;
                    try { in = openInput(); input = in; det = new Core.Detector(c, AudioInput.SR); } catch (Exception e) { error = e.getMessage(); }
                    continue;
                }
                error = "音声入力が終わりました（" + inputInfo + "）";
                new Thread(this::stop, "stop").start();
                break;
            }
            if (n == 0) continue;
            out.clear();
            det.push(buf, n, out);
            for (Core.Result r : out) process(r, ++saveCnt);
        }
    }

    private void process(Core.Result r, int cnt) {
        long now = System.currentTimeMillis();
        List<String> evs;
        Core.Judge j;
        synchronized (this) {
            j = judge;
            if (j == null || !running) return;
            evs = j.update(r, now);
            learnProgress = j.learnProgress();
            thresholds = j.thresholds();
            state = j.state();
        }
        last = r;
        synchronized (live) {
            live.addLast(new double[]{now, r.score, r.avg});
            while (!live.isEmpty() && live.peekFirst()[0] < now - 15 * 60 * 1000) live.removeFirst();
        }
        if (r.ready) history.add(now, r.score, r.avg, stateCode(r));
        for (String e : evs) handleEvent(e, r, now);
        if (link != null) publishLink(r); else sendPlc(r, false);
        if (!evs.isEmpty() || (j.blade.learning && now - lastPersist > 60_000)) { settings.saveBlade(j.blade); lastPersist = now; }
        notifyUi();
    }

    private void handleEvent(String e, Core.Result r, long now) {
        Core.Judge j = judge;
        switch (e) {
            case "LEARNED":
                events.add(now, e, j.blade.baseP90, String.format(Locale.JAPAN, "基準を覚えました（%.1fdB）", j.blade.baseP90));
                break;
            case "BASE_HIGH":
                events.add(now, e, j.blade.baseP90, String.format(Locale.JAPAN,
                        "この帯鋸は最初から規則的な打音あり（基準 %.1fdB）。念のため溶接部を確認。以後は基準からの増加で判定", j.blade.baseP90));
                break;
            case "WARN":
                events.add(now, e, r.score, "帯鋸1周ごとの打音あり。早めに停止して溶接部を点検");
                raise(e);
                break;
            case "ALARM":
                events.add(now, e, r.score, "帯鋸1周ごとの打音が強い。できるだけ早く停止・交換");
                raise(e);
                break;
            case "BREAK":
                events.add(now, e, r.score, "注意・警報中に大きな異常音");
                raise(e);
                break;
            case "LOUD":
                events.add(now, e, r.score, "大きな音（記録のみ）");
                break;
            case "CLEAR":
                events.add(now, e, r.score, "打音が平常に戻った");
                acknowledge();
                break;
        }
    }

    private void raise(String level) {
        alarming = true;
        if (settings.sound) sound.start("WARN".equals(level));
        Listener l = listener;
        if (l != null) l.onAlarm(level);
    }

    // ------------------------------------------------------------ PLC
    private void tick() {
        try {
            PlcLink l;
            synchronized (this) { l = link; }
            if (l == null) return;
            l.poll(cfg, new PlcLink.Actions() {
                @Override public int start() {
                    events.add(System.currentTimeMillis(), "PLC", Double.NaN, "PLC から「開始」");
                    return Engine.this.start();
                }
                @Override public int stop() {
                    events.add(System.currentTimeMillis(), "PLC", Double.NaN, "PLC から「停止」");
                    Engine.this.stop();
                    return PlcLink.RES_OK;
                }
                @Override public int learn() {
                    events.add(System.currentTimeMillis(), "PLC", Double.NaN, "PLC から「帯鋸交換」");
                    startLearning();
                    return PlcLink.RES_OK;
                }
                @Override public int ack() {
                    events.add(System.currentTimeMillis(), "PLC", Double.NaN, "PLC から「警報確認」");
                    acknowledge();
                    return PlcLink.RES_OK;
                }
                @Override public int apply(Core.Config next) {
                    settings.saveConfig(next);
                    cfg = next;
                    events.add(System.currentTimeMillis(), "PLC", Double.NaN, String.format(Locale.JAPAN,
                            "PLC から設定を反映（帯鋸 %.0fmm・ホイール %.0fmm・%.0f〜%.0frpm・注意 %.1f・警報 %.1f/%.1fdB・学習 %.0f分）",
                            next.bladeMm, next.wheelMm, next.rpmMin, next.rpmMax, next.warnAvg, next.alarmAvg, next.alarmInst, next.learnMin));
                    if (running) { Engine.this.stop(); return Engine.this.start(); }
                    return PlcLink.RES_OK;
                }
            });
            if (l.lastResult() >= 100 && l.accepted() != lastLoggedAccept) {
                lastLoggedAccept = l.accepted();
                events.add(System.currentTimeMillis(), "PLC", Double.NaN, "PLC からの設定値が範囲外（結果コード " + l.lastResult() + "）。反映しませんでした");
            }
            if (!running) publishLink(null);
            notifyUi();
        } catch (Exception ignored) { }
    }

    private int stateCode(Core.Result r) {
        if (!running) return 0;
        if (r == null || !r.ready || "IDLE".equals(r.state)) return 1;
        if ("ALARM".equals(state)) return 5;
        if ("WARN".equals(state)) return 4;
        Core.Judge j = judge;
        if (j != null && j.blade.learning) return 3;
        return 2;
    }

    private static short x10(double v) {
        if (Double.isNaN(v)) return 0;
        return (short) Math.max(-32768, Math.min(32767, Math.round(v * 10)));
    }

    private void publishLink(Core.Result r) {
        PlcLink l = link;
        if (l == null) return;
        l.publish(stateCode(r), heartbeat.incrementAndGet() & 0x7FFF,
                x10(r == null ? Double.NaN : r.avg), x10(r == null ? Double.NaN : r.score),
                alarming, (int) Math.round(learnProgress * 100), running, cfg);
        long now = System.currentTimeMillis();
        long age = now - lastReqAt;
        if (lastReqAt == 0) {
            plcOk = false;
            plcStatus = "PLC からの読出し待ち（このPC " + Net.ipList() + " ポート " + settings.plcPort + "）";
        } else if (age < 5000) {
            plcOk = true;
            plcStatus = String.format(Locale.JAPAN, "PLC と通信中（%s・%.1f秒前）D%d〜D%d", lastPeer, age / 1000.0, settings.plcHead, settings.plcHead + 39);
            if (!serverReading) events.add(now, "PLC", Double.NaN, "PLC が読出しを始めました（" + lastPeer + "）");
            serverReading = true;
        } else {
            plcOk = false;
            plcStatus = String.format(Locale.JAPAN, "PLC からの読出しが %d 秒止まっています（最後: %s）", age / 1000, lastPeer);
            if (serverReading) events.add(now, "PLC", Double.NaN, "PLC からの読出しが途絶えました");
            serverReading = false;
        }
    }

    /** 書込み方式: D(n)=状態 / D(n+1)=生存カウンタ / D(n+2)=平均×10 / D(n+3)=瞬時×10 / D(n+4)=未確認の警報 */
    private void sendPlc(Core.Result r, boolean stopping) {
        if (!settings.plcOn || settings.plcServer) return;
        if (!stopping && !plcBusy.compareAndSet(false, true)) return;
        final short[] w = {(short) (stopping ? 0 : stateCode(r)), (short) (heartbeat.incrementAndGet() & 0x7FFF),
                x10(r == null ? Double.NaN : r.avg), x10(r == null ? Double.NaN : r.score), (short) (alarming && !stopping ? 1 : 0)};
        final String host = settings.plcHost, dev = settings.plcDev, headName = settings.plcHeadName();
        final int port = settings.plcPort, head = settings.plcHead;
        final boolean udp = settings.plcUdp;
        Runnable job = () -> {
            try {
                if (slmp == null) slmp = new Slmp(host, port, udp);
                slmp.writeWords(dev, head, w);
                if (!plcOk) events.add(System.currentTimeMillis(), "PLC", Double.NaN, "PLC に接続しました（" + host + " " + headName + "〜）");
                plcOk = true;
                plcFailSince = 0;
                plcStatus = "PLC 送信中: " + host + ":" + port + (udp ? " UDP " : " TCP ") + headName + "〜";
            } catch (Exception e) {
                long now = System.currentTimeMillis();
                if (plcOk || plcFailSince == 0) {
                    plcFailSince = now;
                    events.add(now, "PLC", Double.NaN, "PLC に送れません: " + e.getMessage());
                }
                plcOk = false;
                plcStatus = "PLC に送れません（" + e.getMessage() + "）。再接続を続けます";
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            } finally {
                plcBusy.set(false);
            }
        };
        if (stopping) {
            try { plcExec.submit(job).get(3, TimeUnit.SECONDS); } catch (Exception ignored) { }
        } else plcExec.submit(job);
    }
}
