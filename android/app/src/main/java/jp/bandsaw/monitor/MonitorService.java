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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

/**
 * マイクで帯鋸の音を聞き続けるフォアグラウンドサービス。
 * 画面を消しても監視を続け、注意・警報で通知・アラーム音・振動を出す。
 */
public class MonitorService extends Service {
    public static final String ACT_START = "jp.bandsaw.monitor.START";
    public static final String ACT_STOP = "jp.bandsaw.monitor.STOP";
    public static final String ACT_LEARN = "jp.bandsaw.monitor.LEARN";
    public static final String ACT_ACK = "jp.bandsaw.monitor.ACK";

    private static final String CH_STATUS = "status";
    private static final String CH_ALARM = "alarm2";
    private static final int ID_STATUS = 1, ID_ALARM = 2;

    // ---- 画面と共有する状態（メインスレッドからも読む）
    public static volatile boolean running = false;
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
    /** PLC 送信の状況（画面表示用） */
    public static volatile String plcStatus = "";
    public static volatile boolean plcOk = false;

    // ---- PLC 送信（音声処理を止めないよう別スレッド）
    private final java.util.concurrent.ExecutorService plcExec = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean plcBusy = new java.util.concurrent.atomic.AtomicBoolean(false);
    private Slmp slmp;
    private Store.Plc plcCfg;
    private int heartbeat = 0;
    private long plcFailSince = 0;

    /** D(n)=状態 0停止 1準備中 2平常 3学習中 4注意 5警報 / D(n+1)=生存カウンタ / D(n+2)=平均×10 / D(n+3)=瞬時×10 / D(n+4)=未確認の警報 */
    private short[] plcWords(Core.Result r, boolean stopping) {
        int st;
        if (stopping) st = 0;
        else if (r == null || !r.ready || "IDLE".equals(r.state)) st = 1;
        else if ("ALARM".equals(state)) st = 5;
        else if ("WARN".equals(state)) st = 4;
        else if (judge != null && judge.blade.learning) st = 3;
        else st = 2;
        heartbeat = (heartbeat + 1) & 0x7FFF;
        return new short[]{(short) st, (short) heartbeat, x10(r == null ? Double.NaN : r.avg),
                x10(r == null ? Double.NaN : r.score), (short) (alarming && !stopping ? 1 : 0)};
    }

    /**
     * 工場の Wi-Fi はインターネットにつながっていないことが多く、その場合 Android は通信を
     * モバイル回線に流してしまう。PLC への通信は必ず Wi-Fi を通るようにする。
     */
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

    private static short x10(double v) {
        if (Double.isNaN(v)) return 0;
        return (short) Math.max(-32768, Math.min(32767, Math.round(v * 10)));
    }

    private void sendPlc(Core.Result r, boolean stopping) {
        if (plcCfg == null || !plcCfg.on) return;
        if (!stopping && !plcBusy.compareAndSet(false, true)) return;   // 前回の送信中なら今回は飛ばす
        final short[] w = plcWords(r, stopping);
        Runnable job = () -> {
            try {
                if (slmp == null) {
                    bindToWifi();
                    slmp = new Slmp(plcCfg.host, plcCfg.port, plcCfg.udp);
                }
                slmp.writeWords(plcCfg.dev, plcCfg.head, w);
                if (!plcOk) store.addEvent(System.currentTimeMillis(), "PLC", Double.NaN, "PLC に接続しました（" + plcCfg.host + " " + plcCfg.headName() + "〜）");
                plcOk = true;
                plcFailSince = 0;
                plcStatus = "PLC 送信中: " + plcCfg.host + ":" + plcCfg.port + (plcCfg.udp ? " UDP " : " TCP ") + plcCfg.headName() + "〜";
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
            try { plcExec.submit(job).get(3, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) { }
        } else plcExec.submit(job);
    }

    private Thread worker;
    private volatile boolean stopFlag;
    private PowerManager.WakeLock wakeLock;
    private MediaPlayer player;
    private Vibrator vibrator;
    private Store store;
    private Core.Judge judge;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastStatusUpdate = 0;

    public static void send(Context ctx, String action) {
        Intent i = new Intent(ctx, MonitorService.class).setAction(action);
        if (ACT_START.equals(action)) ctx.startForegroundService(i);
        else ctx.startService(i);
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
        String a = intent != null ? intent.getAction() : ACT_START;
        if (a == null) a = ACT_START;
        switch (a) {
            case ACT_START: startMonitoring(); break;
            case ACT_STOP: stopMonitoring(); break;
            case ACT_LEARN: startLearning(); break;
            case ACT_ACK:
                acknowledge();
                if (!running) stopSelf();
                break;
            case "jp.bandsaw.monitor.TEST":
                store.addEvent(System.currentTimeMillis(), "TEST", Double.NaN, "警報テスト");
                raiseAlarm("ALARM");
                main.postDelayed(() -> { if (!running) { acknowledge(); stopSelf(); } }, 5000);
                break;
        }
        return running ? START_STICKY : START_NOT_STICKY;
    }

    // ------------------------------------------------------------ 開始・停止
    private void startMonitoring() {
        Notification n = statusNotification("監視を開始しています…");
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(ID_STATUS, n);
        } catch (Exception e) {
            error = "監視を始められませんでした（" + e.getClass().getSimpleName() + "）。アプリを開いた状態で「監視開始」を押してください。";
            notifyUi();
            stopSelf();
            return;
        }
        if (running) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "マイクの許可がありません。";
            notifyUi();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        error = null;
        running = true;
        stopFlag = false;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bandsaw:monitor");
        wakeLock.acquire();
        store.addEvent(System.currentTimeMillis(), "START", Double.NaN, "監視開始");
        worker = new Thread(this::loop, "bandsaw-audio");
        worker.start();
        notifyUi();
    }

    private void stopMonitoring() {
        stopFlag = true;
        if (worker != null) {
            try { worker.join(1500); } catch (InterruptedException ignored) { }
            worker = null;
        }
        if (running) {
            store.addEvent(System.currentTimeMillis(), "STOP", Double.NaN, "監視停止");
            alarming = false;
            sendPlc(last, true);          // PLC に「停止」(0) を書いて終わる
        }
        if (slmp != null) { final Slmp s = slmp; plcExec.submit(s::close); slmp = null; }
        plcStatus = "";
        running = false;
        state = "IDLE";
        stopAlarmSound();
        alarming = false;
        if (judge != null) store.saveBlade(judge.blade);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        getSystemService(NotificationManager.class).cancel(ID_ALARM);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        notifyUi();
    }

    @Override
    public void onDestroy() {
        stopFlag = true;
        running = false;
        stopAlarmSound();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    private void startLearning() {
        long now = System.currentTimeMillis();
        if (judge != null) {
            synchronized (this) { judge.startLearning(now); store.saveBlade(judge.blade); }
        } else {
            Core.Blade b = new Core.Blade();
            b.learning = true;
            b.startedAt = now;
            store.saveBlade(b);
        }
        store.addEvent(now, "LEARN", Double.NaN, "帯鋸交換。基準の学習を開始");
        acknowledge();
        if (!running) stopSelf();
        notifyUi();
    }

    private void acknowledge() {
        stopAlarmSound();
        alarming = false;
        getSystemService(NotificationManager.class).cancel(ID_ALARM);
        notifyUi();
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
            main.post(this::stopMonitoring);
            return;
        }
        Core.Config cfg = store.loadConfig();
        plcCfg = store.loadPlc();
        slmp = null;
        plcOk = false;
        plcFailSince = 0;
        plcStatus = plcCfg.on ? "PLC に接続しています…" : "";
        Core.Detector det = new Core.Detector(cfg, srBox[0]);
        synchronized (this) { judge = new Core.Judge(cfg, store.loadBlade()); }
        float[] buf = new float[4096];
        ArrayList<Core.Result> out = new ArrayList<>();
        int saveCnt = 0;
        rec.startRecording();
        try {
            while (!stopFlag) {
                int n = rec.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                if (n <= 0) { if (n < 0) { error = "マイクの読み取りに失敗しました（" + n + "）"; } continue; }
                out.clear();
                det.push(buf, n, out);
                for (Core.Result r : out) {
                    long now = System.currentTimeMillis();
                    java.util.List<String> evs;
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
                    sendPlc(r, false);
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

    /** 設定画面の「警報をテストする」用 */
    public static void testAlarm(Context ctx) {
        Intent i = new Intent(ctx, MonitorService.class).setAction("jp.bandsaw.monitor.TEST");
        ctx.startService(i);
    }

    // ------------------------------------------------------------ 通知（監視中）
    private Notification statusNotification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent p = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("帯鋸打音モニター 監視中")
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
