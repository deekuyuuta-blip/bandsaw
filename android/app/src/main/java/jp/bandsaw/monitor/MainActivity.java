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
    private Button startBtn, ackBtn, batteryBtn;
    private ChartView chart;
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
        LinearLayout gcard = section(root, "推移（直近15分）");
        baseTv = tv("", 12, Ui.MUTED, false);
        gcard.addView(baseTv);
        chart = new ChartView(this);
        gcard.addView(chart, lp(-1, px(190), 0, 6));
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
        MonitorService.send(this, MonitorService.ACT_START);
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
            MonitorService.send(this, MonitorService.ACT_START);
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
        l.addView(tv("PLC への送信（三菱 SLMP / 3Eフレーム バイナリ）", 14, Ui.FG, true), lp(-1, -2, 0, 18));
        CheckBox plcOn = new CheckBox(this);
        plcOn.setText("PLC に状態を送る");
        plcOn.setTextColor(Ui.FG);
        plcOn.setChecked(pc.on);
        l.addView(plcOn);
        l.addView(tv("PLC の IP アドレス", 12, Ui.MUTED, false), lp(-1, -2, 0, 4));
        EditText ip = new EditText(this);
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ip.setText(pc.host); ip.setTextColor(Ui.FG); ip.setTypeface(Typeface.MONOSPACE);
        l.addView(ip);
        l.addView(tv("ポート番号（PLC のパラメータで開けたもの）", 12, Ui.MUTED, false), lp(-1, -2, 0, 8));
        EditText port = new EditText(this);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(pc.port)); port.setTextColor(Ui.FG); port.setTypeface(Typeface.MONOSPACE);
        l.addView(port);
        CheckBox udp = new CheckBox(this);
        udp.setText("UDP で送る（チェックなしは TCP）");
        udp.setTextColor(Ui.FG);
        udp.setChecked(pc.udp);
        l.addView(udp);
        l.addView(tv("書き込む先頭デバイス（D / W / R / ZR）", 12, Ui.MUTED, false), lp(-1, -2, 0, 8));
        EditText dev = new EditText(this);
        dev.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        dev.setText(pc.dev); dev.setTextColor(Ui.FG); dev.setTypeface(Typeface.MONOSPACE);
        l.addView(dev);
        l.addView(tv("先頭番号（W は16進。ここから連続5語を使います）", 12, Ui.MUTED, false), lp(-1, -2, 0, 8));
        EditText head = new EditText(this);
        head.setInputType(InputType.TYPE_CLASS_TEXT);
        head.setText("W".equalsIgnoreCase(pc.dev) ? Integer.toHexString(pc.head).toUpperCase() : String.valueOf(pc.head));
        head.setTextColor(Ui.FG); head.setTypeface(Typeface.MONOSPACE);
        l.addView(head);
        l.addView(tv("+0 状態（0停止 1準備中 2平常 3学習中 4注意 5警報）\n+1 生存カウンタ（0.5秒ごとに+1）\n+2 30秒平均×10 ／ +3 瞬時×10\n+4 未確認の警報（1=鳴動中）", 12, Ui.MUTED, false), lp(-1, -2, 0, 4));

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
                    np.udp = udp.isChecked();
                    np.host = ip.getText().toString().trim();
                    String dv = dev.getText().toString().trim().toUpperCase(Locale.ROOT);
                    boolean okDev = false;
                    for (String d0 : Slmp.DEVICES) if (d0.equals(dv)) okDev = true;
                    np.dev = okDev ? dv : "D";
                    try { np.port = Integer.parseInt(port.getText().toString().trim()); } catch (NumberFormatException e) { np.port = pc.port; }
                    try { np.head = Integer.parseInt(head.getText().toString().trim(), "W".equals(np.dev) ? 16 : 10); } catch (NumberFormatException e) { np.head = pc.head; }
                    store.savePlc(np);
                    if (MonitorService.running) {
                        MonitorService.send(this, MonitorService.ACT_STOP);
                        h.postDelayed(() -> MonitorService.send(this, MonitorService.ACT_START), 1200);
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

        if (!MonitorService.running) {
            setLamp("停止中", Ui.MUTED, learning ? "「監視開始」を押すと、この帯鋸の基準を学習し始めます。" : "「監視開始」を押すと、マイクで帯鋸の音を聞き始めます。画面を消しても監視を続けます。");
            avgTv.setText("--.-");
            micTv.setText("");
            Store.Plc pc = store.loadPlc();
            plcTv.setText(pc.on ? "PLC: 監視開始で " + pc.host + " の " + pc.headName() + "〜 に送信します" : "");
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
        List<double[]> pts;
        synchronized (MonitorService.history) { pts = new ArrayList<>(MonitorService.history); }
        chart.setData(pts, th);

        long now = System.currentTimeMillis();
        if (now - lastLogRefresh > 2000 || lastEventCount < 0) {
            lastLogRefresh = now;
            renderLog();
        }
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
