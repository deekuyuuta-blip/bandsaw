package jp.bandsaw.monitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private Store store;
    private float dp;
    private LinearLayout card;
    private TextView plcTv;
    private TextView stateTv, avgTv, msgTv, instTv, freqTv, lockTv, lvlTv, thTv, micTv, banner, baseTv, logTv, specTv;
    private ProgressBar prog;
    private Button startBtn, ackBtn, batteryBtn, standbyBtn, quitBtn;
    private String pendingAction = MonitorService.ACT_START;
    private ChartView chart;
    // ---- グラフの期間と履歴
    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
    private static final long[] RANGES = {15 * MIN, HOUR, 8 * HOUR, DAY, 7 * DAY};
    private static final String[] RANGE_LABELS = {"15分", "1時間", "8時間", "1日", "7日"};
    private int rangeIdx = 0;
    /** 表示の右端の時刻。0 = 今（自動で進む） */
    private long viewEnd = 0;
    private Button[] rangeBtns;
    private TextView rangeTv;
    private Button nextBtn;
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private List<double[]> histPts = new ArrayList<>();
    private String histKey = "";
    private long histAt = 0;
    private boolean histLoading = false;
    private List<Store.Event> evCache = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private long lastLogRefresh = 0;
    private int lastEventCount = -1;
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 1000); }
    };

    // ------------------------------------------------------------ 画面の組み立て
    private int px(float v) { return Math.round(v * dp); }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, float radius, float strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius * dp);
        if (strokeW > 0) g.setStroke(px(strokeW), stroke);
        return g;
    }

    private Button button(String s, int fill, int textColor) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(textColor);
        b.setBackground(box(fill, Ui.LINE, 10, fill == Ui.PANEL2 ? 1 : 0));
        b.setMinHeight(px(54));
        b.setStateListAnimator(null);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int hgt, float weight, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, hgt, weight);
        p.topMargin = px(top);
        return p;
    }

    private TextView metric(LinearLayout row, String label) {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setBackground(box(Ui.PANEL2, 0, 8, 0));
        m.setPadding(px(10), px(6), px(10), px(6));
        m.addView(tv(label, 11, Ui.MUTED, false));
        TextView v = tv("--", 18, Ui.FG, true);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        m.addView(v);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.leftMargin = row.getChildCount() == 0 ? 0 : px(8);
        row.addView(m, p);
        return v;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        store = new Store(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(16), px(16), px(16), px(28));
        sv.addView(root);

        root.addView(tv("帯鋸打音モニター", 20, Ui.FG, true));
        specTv = tv("", 12, Ui.MUTED, false);
        specTv.setTypeface(Typeface.MONOSPACE);
        root.addView(specTv);

        banner = tv("", 14, Ui.FG, false);
        banner.setBackground(box(0xFF3A3220, Ui.WARN, 10, 1));
        banner.setPadding(px(12), px(10), px(12), px(10));
        banner.setVisibility(View.GONE);
        root.addView(banner, lp(-1, -2, 0, 12));

        // 状態ランプ
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        stateTv = tv("停止中", 30, Ui.FG, true);
        r1.addView(stateTv, new LinearLayout.LayoutParams(0, -2, 1));
        avgTv = tv("--.-", 42, Ui.FG, true);
        avgTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        r1.addView(avgTv);
        r1.addView(tv(" dB", 15, Ui.MUTED, false));
        card.addView(r1);
        msgTv = tv("「監視開始」を押すと、マイクで帯鋸の音を聞き始めます。画面を消しても監視を続けます。", 15, Ui.FG, true);
        card.addView(msgTv, lp(-1, -2, 0, 6));
        prog = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        prog.setMax(100);
        prog.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.LEARN));
        prog.setVisibility(View.GONE);
        card.addView(prog, lp(-1, px(8), 0, 8));
        LinearLayout m1 = new LinearLayout(this), m2 = new LinearLayout(this);
        instTv = metric(m1, "瞬時スコア");
        freqTv = metric(m1, "周期");
        lockTv = metric(m2, "周波数の安定度");
        lvlTv = metric(m2, "マイク音量");
        card.addView(m1, lp(-1, -2, 0, 10));
        card.addView(m2, lp(-1, -2, 0, 8));
        thTv = tv("", 12, Ui.MUTED, false);
        card.addView(thTv, lp(-1, -2, 0, 8));
        micTv = tv("", 11, Ui.MUTED, false);
        card.addView(micTv);
        plcTv = tv("", 12, Ui.MUTED, true);
        card.addView(plcTv, lp(-1, -2, 0, 4));
        root.addView(card, lp(-1, -2, 0, 14));

        ackBtn = button("警報を確認（音を止める）", Ui.ALARM, 0xFFFFFFFF);
        ackBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        ackBtn.setVisibility(View.GONE);
        ackBtn.setOnClickListener(v -> MonitorService.send(this, MonitorService.ACT_ACK));
        root.addView(ackBtn, lp(-1, -2, 0, 14));

        LinearLayout row = new LinearLayout(this);
        startBtn = button("監視開始", Ui.ACCENT, 0xFFFFFFFF);
        startBtn.setOnClickListener(v -> onStartStop());
        Button bladeBtn = button("帯鋸を交換した", Ui.PANEL2, Ui.FG);
        bladeBtn.setOnClickListener(v -> confirmBlade());
        row.addView(startBtn, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -2, 1);
        p2.leftMargin = px(10);
        row.addView(bladeBtn, p2);
        root.addView(row, lp(-1, -2, 0, 12));

        standbyBtn = button("待機する（PLC からの指令を待つ・マイクは使わない）", Ui.PANEL2, Ui.ACCENT);
        standbyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        standbyBtn.setOnClickListener(v -> requestAndSend(MonitorService.ACT_STANDBY));
        root.addView(standbyBtn, lp(-1, -2, 0, 10));
        quitBtn = button("終了（PLC からの指令も受けない）", Ui.PANEL2, Ui.MUTED);
        quitBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        quitBtn.setOnClickListener(v -> MonitorService.send(this, MonitorService.ACT_QUIT));
        root.addView(quitBtn, lp(-1, -2, 0, 10));

        batteryBtn = button("電池の最適化を外す（監視が止まらないように）", Ui.PANEL2, Ui.WARN);
        batteryBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        batteryBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        root.addView(batteryBtn, lp(-1, -2, 0, 10));

        // グラフ
        LinearLayout gcard = section(root, "推移");
        baseTv = tv("", 12, Ui.MUTED, false);
        gcard.addView(baseTv);
        LinearLayout rr = new LinearLayout(this);
        rangeBtns = new Button[RANGES.length];
        for (int i = 0; i < RANGES.length; i++) {
            final int k = i;
            Button b = button(RANGE_LABELS[i], Ui.PANEL2, Ui.FG);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            b.setMinHeight(px(38));
            b.setPadding(0, 0, 0, 0);
            b.setOnClickListener(v -> { rangeIdx = k; if (viewEnd != 0) viewEnd = Math.max(viewEnd, System.currentTimeMillis() - 60 * DAY + RANGES[k]); histKey = ""; refresh(); });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, px(40), 1);
            if (i > 0) bp.leftMargin = px(4);
            rr.addView(b, bp);
            rangeBtns[i] = b;
        }
        gcard.addView(rr, lp(-1, -2, 0, 8));
        chart = new ChartView(this);
        gcard.addView(chart, lp(-1, px(210), 0, 6));
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button prevBtn = button("◀ 前へ", Ui.PANEL2, Ui.FG);
        prevBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        prevBtn.setMinHeight(px(38));
        prevBtn.setOnClickListener(v -> {
            long end = viewEnd == 0 ? System.currentTimeMillis() : viewEnd;
            viewEnd = Math.max(end - RANGES[rangeIdx], System.currentTimeMillis() - 60 * DAY + RANGES[rangeIdx]);
            histKey = "";
            refresh();
        });
        nextBtn = button("次へ ▶", Ui.PANEL2, Ui.FG);
        nextBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        nextBtn.setMinHeight(px(38));
        nextBtn.setOnClickListener(v -> {
            if (viewEnd == 0) return;
            viewEnd += RANGES[rangeIdx];
            if (viewEnd >= System.currentTimeMillis() - 5000) viewEnd = 0;
            histKey = "";
            refresh();
        });
        Button nowBtn = button("今", Ui.PANEL2, Ui.FG);
        nowBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        nowBtn.setMinHeight(px(38));
        nowBtn.setOnClickListener(v -> { viewEnd = 0; histKey = ""; refresh(); });
        nav.addView(prevBtn, new LinearLayout.LayoutParams(0, px(40), 1.2f));
        LinearLayout.LayoutParams np1 = new LinearLayout.LayoutParams(0, px(40), 0.8f);
        np1.leftMargin = px(6);
        nav.addView(nowBtn, np1);
        LinearLayout.LayoutParams np2 = new LinearLayout.LayoutParams(0, px(40), 1.2f);
        np2.leftMargin = px(6);
        nav.addView(nextBtn, np2);
        gcard.addView(nav, lp(-1, -2, 0, 6));
        rangeTv = tv("", 12, Ui.MUTED, false);
        rangeTv.setTypeface(Typeface.MONOSPACE);
        gcard.addView(rangeTv, lp(-1, -2, 0, 6));
        Button csvBtn = button("表示中の期間を CSV で保存", Ui.PANEL2, Ui.ACCENT);
        csvBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        csvBtn.setMinHeight(px(40));
        csvBtn.setOnClickListener(v -> exportCsv());
        gcard.addView(csvBtn, lp(-1, -2, 0, 8));
        SpannableStringBuilder lg = new SpannableStringBuilder();
        legend(lg, "━ 30秒平均  ", Ui.ACCENT);
        legend(lg, "━ 瞬時  ", 0xFF5E7A94);
        legend(lg, "┅ 注意  ", Ui.WARN);
        legend(lg, "┅ 警報", Ui.ALARM);
        TextView lgt = tv("", 11, Ui.MUTED, false);
        lgt.setText(lg);
        gcard.addView(lgt);

        // 記録
        LinearLayout lcard = section(root, "記録");
        logTv = tv("", 13, Ui.FG, false);
        logTv.setLineSpacing(0, 1.25f);
        lcard.addView(logTv);

        // 設定など
        LinearLayout row2 = new LinearLayout(this);
        Button cfgBtn = button("設定", Ui.PANEL2, Ui.FG);
        cfgBtn.setOnClickListener(v -> openSettings());
        Button testBtn = button("警報をテスト", Ui.PANEL2, Ui.FG);
        testBtn.setOnClickListener(v -> { ensureNotifPermission(); MonitorService.testAlarm(this); });
        row2.addView(cfgBtn, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(0, -2, 1);
        p3.leftMargin = px(10);
        row2.addView(testBtn, p3);
        root.addView(row2, lp(-1, -2, 0, 14));

        LinearLayout hcard = section(root, "使い方と注意");
        hcard.addView(tv(
                "・スマホを帯鋸の近く（上下ホイールやガイドの音が届く場所）に固定し、充電器につないで使ってください。\n" +
                "・監視中は画面を消しても動き続けます。通知欄に「監視中」が出ていれば動いています。\n" +
                "・帯鋸を交換したら、取り付けた直後に「帯鋸を交換した」を押します。最初の切削音をその帯鋸の基準として覚えます。\n" +
                "・注意: 帯鋸1周ごとの打音が続いています。早めに止めて溶接部を中心に点検してください。\n" +
                "・警報: 打音が強くなっています。できるだけ早く止めて交換し、機械に近づかないでください。\n" +
                "・このアプリは補助的な予兆検知です。非常停止やカバーなどの安全装置の代わりにはなりません。\n" +
                "・2026/10/27 の CAM9 録画では、溶接部の割れの打音が出てから破断まで約10分でした。", 13, Ui.MUTED, false));

        setContentView(sv);
    }

    private void legend(SpannableStringBuilder sb, String s, int color) {
        int st = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(color), st, st + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private LinearLayout section(LinearLayout root, String title) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setBackground(box(Ui.PANEL, Ui.LINE, 12, 1));
        s.setPadding(px(14), px(12), px(14), px(12));
        s.addView(tv(title, 14, Ui.MUTED, true));
        root.addView(s, lp(-1, -2, 0, 14));
        return s;
    }

    // ------------------------------------------------------------ ライフサイクル
    @Override
    protected void onResume() {
        super.onResume();
        MonitorService.listener = this::refresh;
        lastEventCount = -1;
        h.post(tick);
    }

    @Override
    protected void onPause() {
        MonitorService.listener = null;
        h.removeCallbacks(tick);
        super.onPause();
    }

    // ------------------------------------------------------------ 操作
    private static final int REQ_START = 10, REQ_NOTIF = 11;

    private void onStartStop() {
        if (MonitorService.running) {
            MonitorService.send(this, MonitorService.ACT_STOP);
            return;
        }
        requestAndSend(MonitorService.ACT_START);
    }

    /** マイク・通知の許可を確かめてからサービスに指示する */
    private void requestAndSend(String action) {
        pendingAction = action;
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_START);
            return;
        }
        MonitorService.error = null;
        banner.setVisibility(View.GONE);
        MonitorService.send(this, action);
    }

    private void ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_START) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            MonitorService.send(this, pendingAction);
        } else {
            showBanner("マイクが許可されていません。Android の設定 →「アプリ」→「帯鋸打音モニター」→「権限」でマイクを許可してください。");
        }
    }

    private void confirmBlade() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("帯鋸を交換しましたか？")
                .setMessage("新しい帯鋸で切削を始めてください。" + fmt(store.loadConfig().learnMin, 0) +
                        "分間の切削音を、この帯鋸の「平常時の基準」として覚えます。\n\nき裂がある帯鋸で覚えさせないよう、取り付けた直後に押してください。")
                .setPositiveButton("基準を覚え直す", (d, w) -> MonitorService.send(this, MonitorService.ACT_LEARN))
                .setNegativeButton("やめる", null)
                .show();
    }

    private void openSettings() {
        Core.Config c = store.loadConfig();
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(px(20), px(8), px(20), px(8));
        sv.addView(l);
        final EditText[] eds = new EditText[Store.CFG_KEYS.length];
        for (int i = 0; i < Store.CFG_KEYS.length; i++) {
            l.addView(tv(Store.CFG_LABELS[i], 12, Ui.MUTED, false), lp(-1, -2, 0, i == 0 ? 0 : 8));
            EditText e = new EditText(this);
            e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            e.setText(fmt(Store.get(c, Store.CFG_KEYS[i]), -1));
            e.setTextColor(Ui.FG);
            e.setTypeface(Typeface.MONOSPACE);
            l.addView(e);
            eds[i] = e;
        }
        // ---- PLC
        Store.Plc pc = store.loadPlc();
        l.addView(tv("PLC との連携（三菱 SLMP）", 14, Ui.FG, true), lp(-1, -2, 0, 18));
        CheckBox plcOn = new CheckBox(this);
        plcOn.setText("PLC と連携する");
        plcOn.setTextColor(Ui.FG);
        plcOn.setChecked(pc.on);
        l.addView(plcOn);
        android.widget.RadioGroup mode = new android.widget.RadioGroup(this);
        android.widget.RadioButton mServer = new android.widget.RadioButton(this), mClient = new android.widget.RadioButton(this);
        mServer.setId(View.generateViewId());
        mClient.setId(View.generateViewId());
        mServer.setText("PLC が読みに来る（シンプルCPU通信）おすすめ");
        mClient.setText("アプリから PLC に書き込む（RUN中書込みの許可が必要）");
        mServer.setTextColor(Ui.FG);
        mClient.setTextColor(Ui.FG);
        mode.addView(mServer);
        mode.addView(mClient);
        mode.check(pc.server ? mServer.getId() : mClient.getId());
        l.addView(mode, lp(-1, -2, 0, 4));

        String myIp = MonitorService.wifiIp(this);
        TextView ipInfo = tv("このスマホの IP: " + (myIp == null ? "Wi-Fi 未接続" : myIp) + "\n（PLC の交信先に登録します。スマホは固定IPにしてください）", 12, Ui.ACCENT, false);
        l.addView(ipInfo, lp(-1, -2, 0, 6));

        TextView ipLabel = tv("PLC の IP アドレス", 12, Ui.MUTED, false);
        l.addView(ipLabel, lp(-1, -2, 0, 8));
        EditText ip = new EditText(this);
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ip.setText(pc.host); ip.setTextColor(Ui.FG); ip.setTypeface(Typeface.MONOSPACE);
        l.addView(ip);
        TextView portLabel = tv("", 12, Ui.MUTED, false);
        l.addView(portLabel, lp(-1, -2, 0, 8));
        EditText port = new EditText(this);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(pc.port)); port.setTextColor(Ui.FG); port.setTypeface(Typeface.MONOSPACE);
        l.addView(port);
        CheckBox udp = new CheckBox(this);
        udp.setText("UDP で送る（チェックなしは TCP）");
        udp.setTextColor(Ui.FG);
        udp.setChecked(pc.udp);
        l.addView(udp);
        TextView devLabel = tv("書き込む先頭デバイス（D / W / R / ZR）", 12, Ui.MUTED, false);
        l.addView(devLabel, lp(-1, -2, 0, 8));
        EditText dev = new EditText(this);
        dev.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        dev.setText(pc.dev); dev.setTextColor(Ui.FG); dev.setTypeface(Typeface.MONOSPACE);
        l.addView(dev);
        TextView headLabel = tv("", 12, Ui.MUTED, false);
        l.addView(headLabel, lp(-1, -2, 0, 8));
        EditText head = new EditText(this);
        head.setInputType(InputType.TYPE_CLASS_TEXT);
        head.setTextColor(Ui.FG); head.setTypeface(Typeface.MONOSPACE);
        l.addView(head);
        TextView mapTv = tv("", 12, Ui.MUTED, false);
        l.addView(mapTv, lp(-1, -2, 0, 4));
        final boolean[] isServer = {pc.server};
        Runnable applyMode = () -> {
            boolean sv0 = isServer[0];
            int vis = sv0 ? View.GONE : View.VISIBLE;
            ipLabel.setVisibility(vis); ip.setVisibility(vis); udp.setVisibility(vis); devLabel.setVisibility(vis); dev.setVisibility(vis);
            ipInfo.setVisibility(sv0 ? View.VISIBLE : View.GONE);
            portLabel.setText(sv0 ? "待ち受けるポート番号（TCP・UDP 両方。PLC の交信先ポートと同じ値）" : "PLC のポート番号（PLC のパラメータで開けたもの）");
            headLabel.setText(sv0 ? "先頭デバイス（スマホ側の D 番号。ここから40語を使います）" : "先頭番号（W は16進。ここから連続5語を書き込みます）");
            mapTv.setText(sv0
                    ? "アプリ→PLC（読出し20点）\n+0 状態（0停止/待機 1準備中 2平常 3学習中 4注意 5警報）\n+1 生存カウンタ ／ +2 平均×10 ／ +3 瞬時×10 ／ +4 未確認の警報\n+5 指令の受付カウンタ ／ +6 結果（0=OK）／ +7 学習% ／ +8 監視中\n+10〜+19 今の設定値\nPLC→アプリ（書込み20点）\n+20 指令 .0開始 .1停止 .2帯鋸交換 .3警報確認 .4設定反映\n+21〜+30 新しい設定値"
                    : "+0 状態（0停止 1準備中 2平常 3学習中 4注意 5警報）\n+1 生存カウンタ（0.5秒ごとに+1）\n+2 30秒平均×10 ／ +3 瞬時×10\n+4 未確認の警報（1=鳴動中）");
        };
        head.setText(!pc.server && "W".equalsIgnoreCase(pc.dev) ? Integer.toHexString(pc.head).toUpperCase() : String.valueOf(pc.head));
        applyMode.run();
        mode.setOnCheckedChangeListener((g, id) -> {
            isServer[0] = id == mServer.getId();
            head.setText(isServer[0] ? "0" : "1000");
            applyMode.run();
        });

        CheckBox snd = new CheckBox(this);
        snd.setText("注意・警報でアラーム音を鳴らす");
        snd.setTextColor(Ui.FG);
        snd.setChecked(store.soundOn());
        l.addView(snd, lp(-1, -2, 0, 12));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("設定")
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    for (int i = 0; i < eds.length; i++) {
                        try { Store.set(c, Store.CFG_KEYS[i], Double.parseDouble(eds[i].getText().toString().trim())); }
                        catch (NumberFormatException ignored) { }
                    }
                    store.saveConfig(c);
                    store.setSoundOn(snd.isChecked());
                    Store.Plc np = new Store.Plc();
                    np.on = plcOn.isChecked();
                    np.server = isServer[0];
                    np.udp = udp.isChecked();
                    np.host = ip.getText().toString().trim();
                    String dv = dev.getText().toString().trim().toUpperCase(Locale.ROOT);
                    boolean okDev = false;
                    for (String d0 : Slmp.DEVICES) if (d0.equals(dv)) okDev = true;
                    np.dev = okDev ? dv : "D";
                    try { np.port = Integer.parseInt(port.getText().toString().trim()); } catch (NumberFormatException e) { np.port = pc.port; }
                    try { np.head = Integer.parseInt(head.getText().toString().trim(), (!np.server && "W".equals(np.dev)) ? 16 : 10); } catch (NumberFormatException e) { np.head = pc.head; }
                    if (np.server) np.head = Math.max(0, Math.min(SlmpServer.MEM - 5, np.head));
                    store.savePlc(np);
                    // 動いていれば新しい設定でやり直す（監視中なら監視、待機中なら待機に戻す）
                    boolean wasRunning = MonitorService.running, wasAlive = MonitorService.alive;
                    if (wasAlive) {
                        MonitorService.send(this, MonitorService.ACT_QUIT);
                        h.postDelayed(() -> {
                            if (wasRunning) requestAndSend(MonitorService.ACT_START);
                            else if (np.on && np.server) requestAndSend(MonitorService.ACT_STANDBY);
                        }, 1500);
                    } else if (np.on && np.server) {
                        h.postDelayed(() -> requestAndSend(MonitorService.ACT_STANDBY), 300);
                    }
                    refresh();
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void showBanner(String s) {
        banner.setText(s);
        banner.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------ 表示更新
    private static String fmt(double v, int digits) {
        if (Double.isNaN(v)) return "--";
        if (digits < 0) return (v == Math.rint(v)) ? String.valueOf((long) v) : String.valueOf(v);
        return String.format(Locale.JAPAN, "%." + digits + "f", v);
    }

    private void setLamp(String s, int color, String msg) {
        stateTv.setText(s);
        stateTv.setTextColor(color == Ui.OK ? Ui.FG : color);
        int fill = color == Ui.ALARM ? 0xFF3A1F1F : Ui.PANEL;
        card.setBackground(box(fill, color, 14, 2));
        msgTv.setText(msg);
    }

    private void refresh() {
        Core.Config c = store.loadConfig();
        double[] rg = c.bladeRange();
        specTv.setText(String.format(Locale.JAPAN, "帯鋸 %smm / ホイール %smm / 1周 %.2f〜%.2fHz",
                fmt(c.bladeMm, -1), fmt(c.wheelMm, -1), rg[0], rg[1]));
        Core.Blade bl = store.loadBlade();
        boolean learning = MonitorService.running ? MonitorService.thresholds != null && "learning".equals(MonitorService.thresholds.mode) : bl.learning;
        baseTv.setText(learning ? "この帯鋸の基準を学習中" : bl.hasBase ? String.format(Locale.JAPAN, "この帯鋸の基準 %.1fdB", bl.baseP90) : "基準なし（固定のしきい値で判定）");

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        batteryBtn.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName()) ? View.GONE : View.VISIBLE);
        if (MonitorService.error != null) showBanner(MonitorService.error);

        startBtn.setText(MonitorService.running ? "監視停止" : "監視開始");
        startBtn.setBackground(box(MonitorService.running ? Ui.PANEL2 : Ui.ACCENT, Ui.LINE, 10, MonitorService.running ? 1 : 0));
        startBtn.setTextColor(MonitorService.running ? Ui.FG : 0xFFFFFFFF);
        ackBtn.setVisibility(MonitorService.alarming ? View.VISIBLE : View.GONE);

        Core.Result r = MonitorService.last;
        Core.Thresholds th = MonitorService.thresholds;
        if (th == null) th = new Core.Judge(c, bl).thresholds();
        String mode = "blade".equals(th.mode) ? "この帯鋸の基準から" : "learning".equals(th.mode) ? "基準を学習中" : "固定のしきい値";
        thTv.setText(String.format(Locale.JAPAN, "%s　注意 平均%.1fdB ／ 警報 平均%.1fdB・瞬時%.1fdB", mode, th.warn, th.alarmAvg, th.inst));
        prog.setVisibility(learning ? View.VISIBLE : View.GONE);
        prog.setProgress((int) Math.round(MonitorService.learnProgress * 100));

        Store.Plc pcNow = store.loadPlc();
        boolean serverMode = pcNow.on && pcNow.server;
        standbyBtn.setVisibility(!MonitorService.alive && serverMode ? View.VISIBLE : View.GONE);
        quitBtn.setVisibility(MonitorService.alive && !MonitorService.running ? View.VISIBLE : View.GONE);
        if (!MonitorService.running && MonitorService.alive) {
            setLamp("待機中", Ui.ACCENT, "PLC からの指令を待っています（マイクは止まっています）。「監視開始」か PLC の開始指令で監視を始めます。");
            avgTv.setText("--.-");
            micTv.setText("");
            plcTv.setText(MonitorService.plcStatus);
            plcTv.setTextColor(MonitorService.plcOk ? Ui.OK : Ui.WARN);
        } else if (!MonitorService.running) {
            setLamp("停止中", Ui.MUTED, learning ? "「監視開始」を押すと、この帯鋸の基準を学習し始めます。" : "「監視開始」を押すと、マイクで帯鋸の音を聞き始めます。画面を消しても監視を続けます。");
            avgTv.setText("--.-");
            micTv.setText("");
            Store.Plc pc = store.loadPlc();
            if (!pc.on) plcTv.setText("");
            else if (pc.server) {
                String ip0 = MonitorService.wifiIp(this);
                plcTv.setText("PLC: 監視開始で待ち受けます（このスマホ " + (ip0 == null ? "Wi-Fi 未接続" : ip0) + " ポート " + pc.port + "・D" + pc.head + "〜D" + (pc.head + 39) + "）");
            } else plcTv.setText("PLC: 監視開始で " + pc.host + " の " + pc.headName() + "〜 に書き込みます");
            plcTv.setTextColor(Ui.MUTED);
        } else {
            micTv.setText("マイク: " + MonitorService.micInfo);
            plcTv.setText(MonitorService.plcStatus);
            plcTv.setTextColor(MonitorService.plcOk ? Ui.OK : Ui.WARN);
            String st = MonitorService.state;
            if (r == null || !r.ready) setLamp("準備中", Ui.MUTED, "音を集めています（約10秒）…");
            else if ("IDLE".equals(r.state)) setLamp("準備中", Ui.MUTED, "音が小さすぎます（停止中？）。マイクを帯鋸に近づけてください。");
            else if ("ALARM".equals(st)) setLamp("警報", Ui.ALARM, "帯鋸1周ごとの打音が強い。できるだけ早く止めて交換。機械に近づかない");
            else if ("WARN".equals(st)) setLamp("注意", Ui.WARN, "帯鋸1周ごとの打音あり。早めに止めて溶接部を点検");
            else if (learning) setLamp("学習中", Ui.LEARN, String.format(Locale.JAPAN, "この帯鋸の基準を学習中（%d%%）。強い打音のときだけ警報します", Math.round(MonitorService.learnProgress * 100)));
            else setLamp("平常", Ui.OK, "き裂の打音は出ていません");
            if (r != null) {
                avgTv.setText(fmt(r.avg, 1));
                instTv.setText(fmt(r.score, 1));
                freqTv.setText(Double.isNaN(r.freq) ? "--" : String.format(Locale.JAPAN, "%.2fHz", r.freq));
                lockTv.setText(fmt(r.lock, 2));
                lvlTv.setText(fmt(r.fullDb, 1));
            }
        }
        updateChart(th);

        long now = System.currentTimeMillis();
        if (now - lastLogRefresh > 2000 || lastEventCount < 0) {
            lastLogRefresh = now;
            renderLog();
        }
    }

    // ------------------------------------------------------------ グラフ（期間・履歴・CSV）
    private void updateChart(Core.Thresholds th) {
        long now = System.currentTimeMillis();
        long range = RANGES[rangeIdx];
        long t1 = viewEnd == 0 ? now : viewEnd, t0 = t1 - range;
        for (int i = 0; i < rangeBtns.length; i++) {
            boolean sel = i == rangeIdx;
            rangeBtns[i].setBackground(box(sel ? Ui.ACCENT : Ui.PANEL2, Ui.LINE, 8, sel ? 0 : 1));
            rangeBtns[i].setTextColor(sel ? 0xFFFFFFFF : Ui.FG);
        }
        nextBtn.setEnabled(viewEnd != 0);
        nextBtn.setAlpha(viewEnd != 0 ? 1f : 0.4f);
        SimpleDateFormat f = new SimpleDateFormat(range >= DAY ? "MM/dd HH:mm" : "MM/dd HH:mm:ss", Locale.JAPAN);
        rangeTv.setText(f.format(new Date(t0)) + " 〜 " + (viewEnd == 0 ? "今" : f.format(new Date(t1))));

        List<double[]> pts;
        if (rangeIdx == 0 && viewEnd == 0 && MonitorService.running) {
            synchronized (MonitorService.history) { pts = new ArrayList<>(MonitorService.history); }   // 0.5秒ごとの細かい値
        } else {
            String key = rangeIdx + ":" + viewEnd;
            if (!histLoading && (!key.equals(histKey) || (viewEnd == 0 && now - histAt > 10_000))) {
                histLoading = true;
                final long a = t0 - HistoryStore.BUCKET_MS, b = t1 + HistoryStore.BUCKET_MS;
                final HistoryStore hs = MonitorService.history(this);
                io.submit(() -> {
                    hs.flush();
                    List<double[]> l = hs.load(a, b);
                    h.post(() -> { histPts = l; histKey = key; histAt = System.currentTimeMillis(); histLoading = false; refresh(); });
                });
            }
            pts = histPts;
        }
        List<ChartView.Mark> marks = new ArrayList<>();
        for (Store.Event e : evCache) {
            if (e.t < t0 || e.t > t1) continue;
            switch (e.type) {
                case "ALARM": marks.add(new ChartView.Mark(e.t, Ui.ALARM, "警報")); break;
                case "WARN": marks.add(new ChartView.Mark(e.t, Ui.WARN, "注意")); break;
                case "BREAK": marks.add(new ChartView.Mark(e.t, Ui.ALARM, "破断音")); break;
                case "LEARN": marks.add(new ChartView.Mark(e.t, Ui.LEARN, "交換")); break;
            }
        }
        chart.setData(pts, th, t0, t1, marks, histLoading && pts.isEmpty() ? "読み込み中…" : "この期間の記録はありません");
    }

    private void exportCsv() {
        long range = RANGES[rangeIdx];
        long t1 = viewEnd == 0 ? System.currentTimeMillis() : viewEnd, t0 = t1 - range;
        final HistoryStore hs = MonitorService.history(this);
        final List<Store.Event> evs = store.events();
        String name = "帯鋸_履歴_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN).format(new Date(t0))
                + "-" + new SimpleDateFormat("MMdd_HHmm", Locale.JAPAN).format(new Date(t1)) + ".csv";
        io.submit(() -> {
            String msg;
            try {
                hs.flush();
                int[] rows = {0};
                String where = saveToDownloads(name, os -> rows[0] = hs.writeCsv(os, t0, t1));
                String evName = name.replace("履歴", "記録");
                saveToDownloads(evName, os -> writeEvents(os, evs, t0, t1));
                msg = rows[0] == 0 ? "この期間の記録はありませんでした（空のファイルを保存）" : rows[0] + " 行を保存しました: " + where;
            } catch (Exception e) {
                msg = "保存できませんでした（" + e.getMessage() + "）";
            }
            final String m = msg;
            h.post(() -> android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show());
        });
    }

    private interface CsvWriter { void write(java.io.OutputStream os) throws Exception; }

    /** ダウンロード/帯鋸打音モニター に保存（Android 10 以上）。それより古い端末はアプリ専用フォルダ */
    private String saveToDownloads(String name, CsvWriter w) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/帯鋸打音モニター");
            Uri u = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (u == null) throw new java.io.IOException("ファイルを作れません");
            try (java.io.OutputStream os = getContentResolver().openOutputStream(u)) { w.write(os); }
            return "ダウンロード/帯鋸打音モニター/" + name;
        } else {
            java.io.File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
            java.io.File f = new java.io.File(dir, name);
            try (java.io.OutputStream os = new java.io.FileOutputStream(f)) { w.write(os); }
            return f.getAbsolutePath();
        }
    }

    private static void writeEvents(java.io.OutputStream os, List<Store.Event> evs, long t0, long t1) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN);
        java.io.Writer w = new java.io.OutputStreamWriter(os, java.nio.charset.StandardCharsets.UTF_8);
        w.write("\uFEFF日時,種類,スコアdB,内容\r\n");
        for (int i = evs.size() - 1; i >= 0; i--) {
            Store.Event e = evs.get(i);
            if (e.t < t0 || e.t > t1) continue;
            w.write(f.format(new Date(e.t)) + "," + label(e.type) + "," + (Double.isNaN(e.score) ? "" : String.format(Locale.ROOT, "%.1f", e.score))
                    + ",\"" + e.text.replace("\"", "\"\"") + "\"\r\n");
        }
        w.flush();
    }

    private static String label(String t) {
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
            default: return t;
        }
    }

    private void renderLog() {
        List<Store.Event> ev = store.events();
        evCache = ev;
        lastEventCount = ev.size();
        if (ev.isEmpty()) { logTv.setText("まだ記録はありません。"); logTv.setTextColor(Ui.MUTED); return; }
        logTv.setTextColor(Ui.FG);
        SimpleDateFormat f = new SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int n = 0;
        for (Store.Event e : ev) {
            if (n++ >= 60) break;
            if (sb.length() > 0) sb.append("\n");
            int s0 = sb.length();
            sb.append(f.format(new Date(e.t))).append("  ");
            sb.setSpan(new ForegroundColorSpan(Ui.MUTED), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int s1 = sb.length();
            sb.append("[").append(label(e.type)).append("]");
            int col = ("ALARM".equals(e.type) || "BREAK".equals(e.type)) ? Ui.ALARM : "WARN".equals(e.type) ? Ui.WARN
                    : ("LEARN".equals(e.type) || "LEARNED".equals(e.type) || "BASE_HIGH".equals(e.type)) ? Ui.LEARN : Ui.FG;
            sb.setSpan(new ForegroundColorSpan(col), s1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), s1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(" ").append(e.text);
            if (!Double.isNaN(e.score)) sb.append(String.format(Locale.JAPAN, "  %.1fdB", e.score));
        }
        logTv.setText(sb);
    }
}
