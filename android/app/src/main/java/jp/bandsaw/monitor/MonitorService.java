package jp.bandsaw.monitor;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * マイクで帯鋸の音を聞き続けるフォアグラウンドサービス。
 *
 *  ・監視中: マイクで音を聞いて判定し、注意・警報で通知・アラーム音・振動を出す。画面を消しても続ける。
 *  ・待機中: PLC 連携（PLC が読みに来る方式）のときだけ。マイクは止め、PLC からの読出しと指令
 *            （開始・停止・帯鋸交換・警報確認・設定反映）を待ち受ける。
 */
public class MonitorService extends Service {
    public static final String ACT_START = "jp.bandsaw.monitor.START";
    public static final String ACT_STOP = "jp.bandsaw.monitor.STOP";
    public static final String ACT_QUIT = "jp.bandsaw.monitor.QUIT";
    /** PLC 待受だけを始める（マイクは使わない） */
    public static final String ACT_STANDBY = "jp.bandsaw.monitor.STANDBY";
    public static final String ACT_LEARN = "jp.bandsaw.monitor.LEARN";
    public static final String ACT_ACK = "jp.bandsaw.monitor.ACK";
    private static final String ACT_TEST = "jp.bandsaw.monitor.TEST";

    private static final String CH_STATUS = "status";
    private static final String CH_ALARM = "alarm2";
    private static final int ID_STATUS = 1, ID_ALARM = 2;

    // ---- 画面と共有する状態
    /** マイクで監視中 */
    public static volatile boolean running = false;
    /** サービスが動いている（監視中または待機中） */
    public static volatile boolean alive = false;
    public static volatile boolean alarming = false;
    public static volatile Core.Result last = null;
    public static volatile String state = "IDLE";
    public static volatile double learnProgress = 0;
    public static volatile Core.Thresholds thresholds = null;
    public static volatile String error = null;
    public static volatile String micInfo = "";
    /** 直近15分 {時刻ms, 瞬時, 30秒平均} */
    public static final ArrayDeque<double[]> history = new ArrayDeque<>();
    public static volatile Runnable listener = null;
    public static volatile String plcStatus = "";
    public static volatile boolean plcOk = false;

    private Store store;
    private Vibrator vibrator;
    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;
    private volatile boolean stopFlag;
    private Core.Judge judge;
    private Core.Config cfg;
    private Store.Plc plcCfg;
    private long lastStatusUpdate = 0;
    private final AtomicInteger heartbeat = new AtomicInteger();

    // PLC: 待受（シンプルCPU通信）
    private SlmpServer server;
    private PlcLink link;
    private volatile long lastReqAt = 0;
    private volatile String lastPeer = "";
    private boolean serverReading = false;
    // PLC: アプリから書込み
    private final ExecutorService plcExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean plcBusy = new AtomicBoolean(false);
    private Slmp slmp;
    private long plcFailSince = 0;

    /** 待機中に PLC への状態更新と指令の確認をする（0.5秒ごと） */
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            if (link != null) {
                pollCommands();
                if (!running) publishLink(null);
            }
            main.postDelayed(this, 500);
        }
    };

    public static void send(Context ctx, String action) {
        Intent i = new Intent(ctx, MonitorService.class).setAction(action);
        if (ACT_START.equals(action) || ACT_STANDBY.equals(action)) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void testAlarm(Context ctx) {
        ctx.startService(new Intent(ctx, MonitorService.class).setAction(ACT_TEST));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel st = new NotificationChannel(CH_STATUS, "監視中の表示", NotificationManager.IMPORTANCE_LOW);
        st.setShowBadge(false);
        nm.createNotificationChannel(st);
        NotificationChannel al = new NotificationChannel(CH_ALARM, "注意・警報", NotificationManager.IMPORTANCE_HIGH);
        al.setSound(null, null);          // 音はアプリが鳴らす（止めるまで鳴り続ける）
        al.enableVibration(false);
        al.setBypassDnd(true);
        al.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(al);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null && intent.getAction() != null ? intent.getAction() : ACT_START;
        switch (a) {
            case ACT_START:
                if (goForeground()) { setupLink(); startMic(); }
                break;
            case ACT_STANDBY:
                if (goForeground()) {
                    setupLink();
                    if (link == null) {
                        if (error == null) error = "PLC 待受を始められませんでした。設定の「PLC と連携する」と方式を確認してください。";
                        quit();
                    } else {
                        getSystemService(NotificationManager.class).notify(ID_STATUS, statusNotification("待機中（PLC からの指令を待っています）"));
                    }
                }
                break;
            case ACT_STOP:
                stopMic();
                if (link == null) quit();          // PLC 待受がなければ完全に止める
                break;
            case ACT_QUIT: quit(); break;
            case ACT_LEARN:
                startLearning();
                if (!alive) stopSelf();
                break;
            case ACT_ACK:
                acknowledge();
                if (!alive) stopSelf();
                break;
            case ACT_TEST:
                store.addEvent(System.currentTimeMillis(), "TEST", Double.NaN, "警報テスト");
                raiseAlarm("ALARM");
                main.postDelayed(() -> { if (!running) { acknowledge(); if (!alive) stopSelf(); } }, 5000);
                break;
        }
        notifyUi();
        return alive ? START_STICKY : START_NOT_STICKY;
    }

    // ------------------------------------------------------------ サービスの起動・終了
    private boolean goForeground() {
        if (alive) return true;
        try {
            Notification n = statusNotification("起動しています…");
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(ID_STATUS, n);
        } catch (Exception e) {
            error = "監視を始められませんでした（" + e.getClass().getSimpleName() + "）。アプリを開いた状態で「監視開始」を押してください。";
            stopSelf();
            return false;
        }
        alive = true;
        error = null;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bandsaw:monitor");
        wakeLock.acquire();
        return true;
    }

    private void quit() {
        stopMic();
        main.removeCallbacks(ticker);
        closeLink();
        alive = false;
        state = "IDLE";
        acknowledge();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        notifyUi();
    }

    @Override
    public void onDestroy() {
        stopFlag = true;
        running = false;
        alive = false;
        main.removeCallbacks(ticker);
        closeLink();
        stopAlarmSound();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        plcExec.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------ PLC 連携の準備
    private void setupLink() {
        plcCfg = store.loadPlc();
        cfg = store.loadConfig();
        if (!plcCfg.on) { plcStatus = ""; return; }
        if (!plcCfg.server) { plcStatus = "PLC に接続しています…"; plcOk = false; plcFailSince = 0; return; }
        if (server != null) return;
        try {
            server = new SlmpServer(plcCfg.port, (peer, cmd, sub, ok) -> {
                lastReqAt = System.currentTimeMillis();
                lastPeer = peer;
                if (!ok) plcStatus = String.format(Locale.JAPAN, "PLC から未対応の要求（コマンド %04X / %04X）を受けました", cmd, sub);
            });
            server.start();
            link = new PlcLink(server, plcCfg.head);
            lastReqAt = 0;
            serverReading = false;
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bandsaw:plc");
            wifiLock.acquire();
            publishLink(null);
            main.removeCallbacks(ticker);
            main.post(ticker);
        } catch (Exception e) {
            server = null;
            link = null;
            plcStatus = "ポート " + plcCfg.port + " で待ち受けできません（" + e.getMessage() + "）";
            store.addEvent(System.currentTimeMillis(), "PLC", Double.NaN, plcStatus);
        }
    }

    private void closeLink() {
        if (server != null) { server.close(); server = null; }
        link = null;
        if (slmp != null) { final Slmp s = slmp; plcExec.submit(s::close); slmp = null; }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;
        plcStatus = "";
        plcOk = false;
    }

    // ------------------------------------------------------------ 監視（マイク）の開始・停止
    /** @return PlcLink の結果コード */
    private int startMic() {
        if (running) return PlcLink.RES_OK;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "マイクの許可がありません。";
            return PlcLink.RES_MIC;
        }
        error = null;
        cfg = store.loadConfig();
        running = true;
        stopFlag = false;
        store.addEvent(System.currentTimeMillis(), "START", Double.NaN, "監視開始");
        worker = new Thread(this::loop, "bandsaw-audio");
        worker.start();
        notifyUi();
        return PlcLink.RES_OK;
    }

    private void stopMic() {
        if (!running && worker == null) return;
        stopFlag = true;
        if (worker != null) {
            try { worker.join(1500); } catch (InterruptedException ignored) { }
            worker = null;
        }
        if (running) store.addEvent(System.currentTimeMillis(), "STOP", Double.NaN, "監視停止");
        running = false;
        state = "IDLE";
        last = null;
        acknowledge();
        if (judge != null) store.saveBlade(judge.blade);
        if (plcCfg != null && plcCfg.on && !plcCfg.server) sendPlc(null, true);   // 書込み方式: 「停止」を書いて終わる
        if (link != null) {
            publishLink(null);
            getSystemService(NotificationManager.class).notify(ID_STATUS, statusNotification("待機中（PLC からの指令を待っています）"));
        }
        notifyUi();
    }

    private void startLearning() {
        long now = System.currentTimeMillis();
        if (judge != null && running) {
            synchronized (this) { judge.startLearning(now); store.saveBlade(judge.blade); }
        } else {
            Core.Blade b = new Core.Blade();
            b.learning = true;
            b.startedAt = now;
            store.saveBlade(b);
        }
        store.addEvent(now, "LEARN", Double.NaN, "帯鋸交換。基準の学習を開始");
        acknowledge();
    }

    private void acknowledge() {
        stopAlarmSound();
        alarming = false;
        getSystemService(NotificationManager.class).cancel(ID_ALARM);
        notifyUi();
    }

    // ------------------------------------------------------------ PLC からの指令
    private void pollCommands() {
        if (link == null) return;
        Core.Config cur = cfg != null ? cfg : store.loadConfig();
        link.poll(cur, new PlcLink.Actions() {
            @Override public int start() {
                store.addEvent(System.currentTimeMillis(), "PLC", Double.NaN, "PLC から「開始」");
                return startMic();
            }
            @Override public int stop() {
                store.addEvent(System.currentTimeMillis(), "PLC", Double.NaN, "PLC から「停止」");
                stopMic();
                return PlcLink.RES_OK;
            }
            @Override public int learn() {
                store.addEvent(System.currentTimeMillis(), "PLC", Double.NaN, "PLC から「帯鋸交換」");
                startLearning();
                return PlcLink.RES_OK;
            }
            @Override public int ack() {
                store.addEvent(System.currentTimeMillis(), "PLC", Double.NaN, "PLC から「警報確認」");
                acknowledge();
                return PlcLink.RES_OK;
            }
            @Override public int apply(Core.Config next) {
                store.saveConfig(next);
                cfg = next;
                store.addEvent(System.currentTimeMillis(), "PLC", Double.NaN, String.format(Locale.JAPAN,
                        "PLC から設定を反映（帯鋸 %.0fmm・ホイール %.0fmm・%.0f〜%.0frpm・注意 %.1f・警報 %.1f/%.1fdB・学習 %.0f分）",
                        next.bladeMm, next.wheelMm, next.rpmMin, next.rpmMax, next.warnAvg, next.alarmAvg, next.alarmInst, next.learnMin));
                if (running) { stopMic(); return startMic(); }    // 新しい設定で監視をやり直す
                return PlcLink.RES_OK;
            }
        });
        if (link != null && link.lastResult() >= 100 && link.accepted() != lastLoggedAccept) {
            lastLoggedAccept = link.accepted();
            store.addEvent(System.currentTimeMillis(), "PLC", Double.NaN, "PLC からの設定値が範囲外（結果コード " + link.lastResult() + "）。反映しませんでした");
        }
        notifyUi();
    }
    private int lastLoggedAccept = -1;

    private int stateCode(Core.Result r) {
        if (!running) return 0;
        if (r == null || !r.ready || "IDLE".equals(r.state)) return 1;
        if ("ALARM".equals(state)) return 5;
        if ("WARN".equals(state)) return 4;
        if (judge != null && judge.blade.learning) return 3;
        return 2;
    }

    private static short x10(double v) {
        if (Double.isNaN(v)) return 0;
        return (short) Math.max(-32768, Math.min(32767, Math.round(v * 10)));
    }

    /** 待受方式: PLC が読む領域を更新し、読出し状況を表示用にまとめる */
    private void publishLink(Core.Result r) {
        PlcLink l = link;
        if (l == null) return;
        Core.Config c = cfg != null ? cfg : store.loadConfig();
        l.publish(stateCode(r), heartbeat.incrementAndGet() & 0x7FFF,
                x10(r == null ? Double.NaN : r.avg), x10(r == null ? Double.NaN : r.score),
                alarming, (int) Math.round(learnProgress * 100), running, c);
        long now = System.currentTimeMillis();
        long age = now - lastReqAt;
        if (lastReqAt == 0) {
            plcOk = false;
            String ip = wifiIp(this);
            plcStatus = "PLC からの読出し待ち（このスマホ " + (ip == null ? "Wi-Fi 未接続" : ip) + " ポート " + plcCfg.port + "）";
        } else if (age < 5000) {
            plcOk = true;
            plcStatus = String.format(Locale.JAPAN, "PLC と通信中（%s・%.1f秒前）D%d〜D%d", lastPeer, age / 1000.0, plcCfg.head, plcCfg.head + 39);
            if (!serverReading) store.addEvent(now, "PLC", Double.NaN, "PLC が読出しを始めました（" + lastPeer + "）");
            serverReading = true;
        } else {
            plcOk = false;
            plcStatus = String.format(Locale.JAPAN, "PLC からの読出しが %d 秒止まっています（最後: %s）", age / 1000, lastPeer);
            if (serverReading) store.addEvent(now, "PLC", Double.NaN, "PLC からの読出しが途絶えました");
            serverReading = false;
        }
    }

    /** このスマホの Wi-Fi の IPv4 アドレス（PLC の交信先に登録する値） */
    public static String wifiIp(Context ctx) {
        try {
            android.net.ConnectivityManager cm = ctx.getSystemService(android.net.ConnectivityManager.class);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue;
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (android.net.LinkAddress la : lp.getLinkAddresses())
                    if (la.getAddress() instanceof java.net.Inet4Address)
                        return la.getAddress().getHostAddress() + "/" + la.getPrefixLength();
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ------------------------------------------------------------ 書込み方式（アプリ → PLC）
    private void bindToWifi() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                    cm.bindProcessToNetwork(n);
                    return;
                }
            }
            cm.bindProcessToNetwork(null);
        } catch (Exception ignored) { }
    }

    /** D(n)=状態 / D(n+1)=生存カウンタ / D(n+2)=平均×10 / D(n+3)=瞬時×10 / D(n+4)=未確認の警報 */
    private void sendPlc(Core.Result r, boolean stopping) {
        if (plcCfg == null || !plcCfg.on || plcCfg.server) return;
        if (!stopping && !plcBusy.compareAndSet(false, true)) return;
        final short[] w = {(short) (stopping ? 0 : stateCode(r)), (short) (heartbeat.incrementAndGet() & 0x7FFF),
                x10(r == null ? Double.NaN : r.avg), x10(r == null ? Double.NaN : r.score), (short) (alarming && !stopping ? 1 : 0)};
        final Store.Plc pc = plcCfg;
        Runnable job = () -> {
            try {
                if (slmp == null) { bindToWifi(); slmp = new Slmp(pc.host, pc.port, pc.udp); }
                slmp.writeWords(pc.dev, pc.head, w);
                if (!plcOk) store.addEvent(System.currentTimeMillis(), "PLC", Double.NaN, "PLC に接続しました（" + pc.host + " " + pc.headName() + "〜）");
                plcOk = true;
                plcFailSince = 0;
                plcStatus = "PLC 送信中: " + pc.host + ":" + pc.port + (pc.udp ? " UDP " : " TCP ") + pc.headName() + "〜";
            } catch (Exception e) {
                long now = System.currentTimeMillis();
                if (plcOk || plcFailSince == 0) {
                    plcFailSince = now;
                    store.addEvent(now, "PLC", Double.NaN, "PLC に送れません: " + e.getMessage());
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

    // ------------------------------------------------------------ 音声ループ
    private AudioRecord openRecorder(int[] srOut) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        boolean unprocessed = "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int[] sources = unprocessed
                ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        String[] names = unprocessed ? new String[]{"未処理", "音声認識用", "標準"} : new String[]{"音声認識用", "標準"};
        int[] rates = {48000, 44100};
        for (int si = 0; si < sources.length; si++) {
            for (int sr : rates) {
                int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT);
                if (min <= 0) continue;
                try {
                    @SuppressWarnings("MissingPermission")
                    AudioRecord r = new AudioRecord(sources[si], sr, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_FLOAT, Math.max(min, sr * 4 * 2));
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                        srOut[0] = sr;
                        micInfo = names[si] + " / " + sr + "Hz";
                        return r;
                    }
                    r.release();
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    private void loop() {
        int[] srBox = new int[1];
        AudioRecord rec = openRecorder(srBox);
        if (rec == null) {
            error = "マイクを開けませんでした。";
            running = false;
            main.post(() -> { worker = null; if (link == null) quit(); else notifyUi(); });
            return;
        }
        Core.Config c = cfg != null ? cfg : store.loadConfig();
        Core.Detector det = new Core.Detector(c, srBox[0]);
        synchronized (this) { judge = new Core.Judge(c, store.loadBlade()); }
        float[] buf = new float[4096];
        ArrayList<Core.Result> out = new ArrayList<>();
        int saveCnt = 0;
        try {
            rec.startRecording();
        } catch (Exception e) {
            error = "マイクを開始できませんでした（" + e.getMessage() + "）";
            rec.release();
            running = false;
            main.post(() -> { worker = null; if (link == null) quit(); else notifyUi(); });
            return;
        }
        try {
            while (!stopFlag) {
                int n = rec.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                if (n <= 0) { if (n < 0) error = "マイクの読み取りに失敗しました（" + n + "）"; continue; }
                out.clear();
                det.push(buf, n, out);
                for (Core.Result r : out) {
                    long now = System.currentTimeMillis();
                    List<String> evs;
                    synchronized (this) {
                        evs = judge.update(r, now);
                        learnProgress = judge.learnProgress();
                        thresholds = judge.thresholds();
                        state = judge.state();
                    }
                    last = r;
                    synchronized (history) {
                        history.addLast(new double[]{now, r.score, r.avg});
                        while (!history.isEmpty() && history.peekFirst()[0] < now - 15 * 60 * 1000) history.removeFirst();
                    }
                    for (String e : evs) handleEvent(e, r, now);
                    if (link != null) publishLink(r); else sendPlc(r, false);
                    if (!evs.isEmpty() || (judge.blade.learning && ++saveCnt % 120 == 0)) store.saveBlade(judge.blade);
                    if (now - lastStatusUpdate > 5000) {
                        lastStatusUpdate = now;
                        updateStatusNotification(r);
                    }
                    notifyUi();
                }
            }
        } finally {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
        }
    }

    private static String label(String s) {
        switch (s) {
            case "ALARM": return "警報";
            case "WARN": return "注意";
            case "NORMAL": return "平常";
            default: return "準備中";
        }
    }

    private void handleEvent(String e, Core.Result r, long now) {
        switch (e) {
            case "LEARNED":
                store.addEvent(now, e, judge.blade.baseP90, String.format(Locale.JAPAN, "基準を覚えました（%.1fdB）", judge.blade.baseP90));
                break;
            case "BASE_HIGH":
                store.addEvent(now, e, judge.blade.baseP90, String.format(Locale.JAPAN,
                        "この帯鋸は最初から規則的な打音あり（基準 %.1fdB）。念のため溶接部を確認。以後は基準からの増加で判定", judge.blade.baseP90));
                break;
            case "WARN":
                store.addEvent(now, e, r.score, "帯鋸1周ごとの打音あり。早めに停止して溶接部を点検");
                raiseAlarm(e);
                break;
            case "ALARM":
                store.addEvent(now, e, r.score, "帯鋸1周ごとの打音が強い。できるだけ早く停止・交換");
                raiseAlarm(e);
                break;
            case "BREAK":
                store.addEvent(now, e, r.score, "注意・警報中に大きな異常音");
                raiseAlarm(e);
                break;
            case "LOUD":
                store.addEvent(now, e, r.score, "大きな音（記録のみ）");
                break;
            case "CLEAR":
                store.addEvent(now, e, r.score, "打音が平常に戻った");
                main.post(this::acknowledge);
                break;
        }
    }

    // ------------------------------------------------------------ 警報
    private void raiseAlarm(String level) {
        alarming = true;
        boolean warn = "WARN".equals(level);
        String title = warn ? "【注意】帯鋸の打音" : ("BREAK".equals(level) ? "【破断音】大きな異常音" : "【警報】帯鋸の打音が強い");
        String text = warn ? "帯鋸1周ごとの打音が続いています。早めに止めて溶接部を点検してください。"
                : "できるだけ早く止めて帯鋸を交換してください。機械に近づかないでください。";
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pAck = PendingIntent.getService(this, 2, new Intent(this, MonitorService.class).setAction(ACT_ACK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setColor(warn ? 0xFFF0B429 : 0xFFEF4A3C)
                .setOngoing(true)
                .setContentIntent(pOpen)
                .setFullScreenIntent(pOpen, true)
                .addAction(new Notification.Action.Builder(null, "確認（音を止める）", pAck).build())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
        getSystemService(NotificationManager.class).notify(ID_ALARM, n);
        main.post(() -> startAlarmSound(warn));
    }

    private void startAlarmSound(boolean warn) {
        stopAlarmSound();
        if (store.soundOn()) {
            try {
                Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                player.setDataSource(this, u);
                player.setLooping(true);
                player.prepare();
                player.start();
            } catch (Exception e) {
                player = null;
            }
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pat = warn ? new long[]{0, 400, 1600} : new long[]{0, 700, 300};
            vibrator.vibrate(VibrationEffect.createWaveform(pat, 0),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        }
    }

    private void stopAlarmSound() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
        if (vibrator != null) vibrator.cancel();
    }

    // ------------------------------------------------------------ 通知（監視中・待機中）
    private Notification statusNotification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent p = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(running ? "帯鋸打音モニター 監視中" : "帯鋸打音モニター 待機中")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(p)
                .build();
    }

    private void updateStatusNotification(Core.Result r) {
        String t;
        if (!r.ready) t = "音を集めています…";
        else if (judge.blade.learning) t = String.format(Locale.JAPAN, "%s ・ 基準を学習中 %d%% ・ 平均 %.1fdB", label(state), Math.round(learnProgress * 100), r.avg);
        else t = String.format(Locale.JAPAN, "%s ・ 30秒平均 %.1fdB ・ 瞬時 %.1fdB", label(state), r.avg, r.score);
        getSystemService(NotificationManager.class).notify(ID_STATUS, statusNotification(t));
    }

    private void notifyUi() {
        Runnable l = listener;
        if (l != null) main.post(l);
    }
}
