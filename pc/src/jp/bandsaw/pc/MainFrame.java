package jp.bandsaw.pc;

import jp.bandsaw.monitor.Core;
import jp.bandsaw.monitor.HistoryStore;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PC 版の画面。左: 状態ランプと操作、右: 推移グラフと記録 */
public final class MainFrame extends JFrame {
    private final Engine eng;
    private final Widgets.Card lamp;
    private final JLabel stateL, avgL, msgL, thL, inputL, plcL, specL, baseL, rangeL;
    private final JLabel instV, freqV, lockV, lvlV;
    private final JProgressBar prog = new JProgressBar(0, 100);
    private final Widgets.FlatButton startB, ackB, bladeB, quitLinkB, nextB;
    private final Widgets.FlatButton[] rangeBs;
    private final ChartPanel chart = new ChartPanel();
    private final JTextPane log = new JTextPane();
    private TrayIcon tray;
    private boolean flashOn;
    private int flashCount;

    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
    private static final long[] RANGES = {15 * MIN, HOUR, 8 * HOUR, DAY, 7 * DAY};
    private static final String[] RANGE_LABELS = {"15分", "1時間", "8時間", "1日", "7日"};
    private int rangeIdx = 0;
    private long viewEnd = 0;   // 0 = 今
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "io"); t.setDaemon(true); return t; });
    private List<double[]> histPts = new ArrayList<>();
    private String histKey = "";
    private long histAt = 0;
    private boolean histLoading = false;
    private int lastEventCount = -1;
    private long lastEventTop = 0;

    public MainFrame(Engine e) {
        super("帯鋸打音モニター");
        eng = e;
        setIconImages(Arrays.asList(Widgets.icon(16, null), Widgets.icon(32, null), Widgets.icon(64, null)));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent we) { confirmExit(); }
        });

        JPanel root = new JPanel(new BorderLayout(14, 0));
        root.setBackground(Theme.BG);
        root.setBorder(Widgets.pad(14, 14, 14, 14));
        setContentPane(root);

        // ================= 左: 状態と操作
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setPreferredSize(new Dimension(430, 10));
        root.add(left, BorderLayout.WEST);

        JLabel title = Widgets.label("帯鋸打音モニター", 22f, Theme.FG, true);
        left.add(align(title));
        specL = Widgets.label("", 12f, Theme.MUTED, false);
        specL.setFont(Theme.mono(12f, Font.PLAIN));
        left.add(align(specL));
        left.add(Box.createVerticalStrut(10));

        lamp = new Widgets.Card(Theme.PANEL, Theme.LINE, 2.5f);
        lamp.setLayout(new BoxLayout(lamp, BoxLayout.Y_AXIS));
        JPanel r1 = Widgets.row(new BorderLayout());
        stateL = Widgets.label("停止中", 34f, Theme.FG, true);
        stateL.setVerticalAlignment(JLabel.BOTTOM);
        r1.add(stateL, BorderLayout.WEST);
        JPanel avgP = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        avgP.setOpaque(false);
        avgL = new JLabel("--.-");
        avgL.setFont(Theme.mono(44f, Font.BOLD));
        avgL.setForeground(Theme.FG);
        avgP.add(avgL);
        avgP.add(Widgets.label("dB", 15f, Theme.MUTED, false));
        r1.add(avgP, BorderLayout.EAST);
        lamp.add(align(r1));
        msgL = Widgets.label("", 15f, Theme.FG, true);
        lamp.add(align(msgL));
        lamp.add(Box.createVerticalStrut(8));
        prog.setForeground(Theme.LEARN);
        prog.setBackground(Theme.PANEL2);
        prog.setBorderPainted(false);
        prog.setVisible(false);
        lamp.add(align(Widgets.fixHeight(prog, 8)));
        lamp.add(Box.createVerticalStrut(8));
        JPanel metrics = Widgets.row(new GridLayout(2, 2, 8, 8));
        instV = metric(metrics, "瞬時スコア (dB)");
        freqV = metric(metrics, "周期");
        lockV = metric(metrics, "周波数の安定度");
        lvlV = metric(metrics, "入力の音量 (dBFS)");
        lamp.add(align(metrics));
        lamp.add(Box.createVerticalStrut(8));
        thL = Widgets.label("", 12f, Theme.MUTED, false);
        lamp.add(align(thL));
        inputL = Widgets.label("", 11.5f, Theme.MUTED, false);
        lamp.add(align(inputL));
        plcL = Widgets.label("", 12f, Theme.MUTED, true);
        lamp.add(align(plcL));
        left.add(align(lamp));
        left.add(Box.createVerticalStrut(12));

        ackB = Widgets.button("警報を確認（音を止める）", Theme.ALARM, Color.WHITE);
        ackB.setFont(Theme.font(18f).deriveFont(Font.BOLD));
        ackB.addActionListener(a -> eng.acknowledge());
        ackB.setVisible(false);
        left.add(align(Widgets.fixHeight(ackB, 56)));
        left.add(Box.createVerticalStrut(8));

        JPanel row = new JPanel(new GridLayout(1, 2, 10, 0));
        row.setOpaque(false);
        startB = Widgets.button("監視開始", Theme.ACCENT, Color.WHITE);
        startB.addActionListener(a -> onStartStop());
        bladeB = Widgets.button("帯鋸を交換した", Theme.PANEL2, Theme.FG);
        bladeB.addActionListener(a -> confirmBlade());
        row.add(startB);
        row.add(bladeB);
        left.add(align(Widgets.fixHeight(row, 54)));
        left.add(Box.createVerticalStrut(10));

        JPanel row2 = new JPanel(new GridLayout(1, 3, 8, 0));
        row2.setOpaque(false);
        Widgets.FlatButton cfgB = Widgets.button("設定", Theme.PANEL2, Theme.FG);
        cfgB.addActionListener(a -> new SettingsDialog(this, eng).setVisible(true));
        Widgets.FlatButton anaB = Widgets.button("録画を検証", Theme.PANEL2, Theme.FG);
        anaB.addActionListener(a -> new AnalyzeDialog(this, eng).setVisible(true));
        Widgets.FlatButton testB = Widgets.button("警報テスト", Theme.PANEL2, Theme.FG);
        testB.addActionListener(a -> eng.testAlarm());
        row2.add(cfgB);
        row2.add(anaB);
        row2.add(testB);
        left.add(align(Widgets.fixHeight(row2, 44)));
        left.add(Box.createVerticalStrut(8));
        quitLinkB = Widgets.button("", Theme.PANEL2, Theme.MUTED);
        quitLinkB.setVisible(false);
        left.add(align(Widgets.fixHeight(quitLinkB, 40)));
        left.add(Box.createVerticalGlue());
        JLabel note = Widgets.label("<html>補助的な予兆検知です。非常停止やカバーなどの安全装置の代わりにはなりません。</html>", 11.5f, Theme.MUTED, false);
        left.add(align(note));

        // ================= 右: グラフと記録
        JPanel right = new JPanel(new BorderLayout(0, 12));
        right.setOpaque(false);
        root.add(right, BorderLayout.CENTER);

        Widgets.Card gcard = new Widgets.Card(Theme.PANEL, Theme.LINE, 1f);
        gcard.setLayout(new BorderLayout(0, 8));
        JPanel gtop = new JPanel(new BorderLayout(10, 0));
        gtop.setOpaque(false);
        JPanel gtitle = new JPanel();
        gtitle.setOpaque(false);
        gtitle.setLayout(new BoxLayout(gtitle, BoxLayout.Y_AXIS));
        gtitle.add(Widgets.label("推移", 15f, Theme.MUTED, true));
        baseL = Widgets.label("", 12f, Theme.MUTED, false);
        gtitle.add(baseL);
        gtop.add(gtitle, BorderLayout.WEST);
        JPanel rr = new JPanel(new GridLayout(1, RANGES.length, 4, 0));
        rr.setOpaque(false);
        rangeBs = new Widgets.FlatButton[RANGES.length];
        for (int i = 0; i < RANGES.length; i++) {
            final int k = i;
            Widgets.FlatButton b = new Widgets.FlatButton(RANGE_LABELS[i], Theme.PANEL2, Theme.FG, 13f);
            b.setMargin(new java.awt.Insets(6, 10, 6, 10));
            b.addActionListener(a -> { rangeIdx = k; histKey = ""; refresh(); });
            rr.add(b);
            rangeBs[i] = b;
        }
        gtop.add(rr, BorderLayout.EAST);
        gcard.add(gtop, BorderLayout.NORTH);
        gcard.add(chart, BorderLayout.CENTER);
        JPanel nav = new JPanel(new BorderLayout(8, 0));
        nav.setOpaque(false);
        JPanel navL = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        navL.setOpaque(false);
        Widgets.FlatButton prevB = new Widgets.FlatButton("◀ 前へ", Theme.PANEL2, Theme.FG, 13f);
        prevB.addActionListener(a -> {
            long end = viewEnd == 0 ? System.currentTimeMillis() : viewEnd;
            viewEnd = Math.max(end - RANGES[rangeIdx], System.currentTimeMillis() - 60 * DAY + RANGES[rangeIdx]);
            histKey = "";
            refresh();
        });
        Widgets.FlatButton nowB = new Widgets.FlatButton("今", Theme.PANEL2, Theme.FG, 13f);
        nowB.addActionListener(a -> { viewEnd = 0; histKey = ""; refresh(); });
        nextB = new Widgets.FlatButton("次へ ▶", Theme.PANEL2, Theme.FG, 13f);
        nextB.addActionListener(a -> {
            if (viewEnd == 0) return;
            viewEnd += RANGES[rangeIdx];
            if (viewEnd >= System.currentTimeMillis() - 5000) viewEnd = 0;
            histKey = "";
            refresh();
        });
        navL.add(prevB);
        navL.add(nowB);
        navL.add(nextB);
        rangeL = Widgets.label("", 12f, Theme.MUTED, false);
        rangeL.setFont(Theme.mono(12f, Font.PLAIN));
        navL.add(Box.createHorizontalStrut(8));
        navL.add(rangeL);
        nav.add(navL, BorderLayout.WEST);
        Widgets.FlatButton csvB = new Widgets.FlatButton("表示中の期間を CSV で保存", Theme.PANEL2, Theme.ACCENT, 13f);
        csvB.addActionListener(a -> exportCsv());
        nav.add(csvB, BorderLayout.EAST);
        gcard.add(nav, BorderLayout.SOUTH);
        right.add(gcard, BorderLayout.CENTER);

        Widgets.Card lcard = new Widgets.Card(Theme.PANEL, Theme.LINE, 1f);
        lcard.setLayout(new BorderLayout(0, 6));
        lcard.add(Widgets.label("記録", 15f, Theme.MUTED, true), BorderLayout.NORTH);
        log.setEditable(false);
        log.setBackground(Theme.PANEL);
        log.setForeground(Theme.FG);
        log.setFont(Theme.font(13f));
        log.setBorder(Widgets.pad(2, 2, 2, 2));
        JScrollPane sp = new JScrollPane(log);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Theme.PANEL);
        sp.setPreferredSize(new Dimension(400, 230));
        lcard.add(sp, BorderLayout.CENTER);
        right.add(lcard, BorderLayout.SOUTH);

        setSize(1240, 820);
        setMinimumSize(new Dimension(1000, 700));
        setLocationRelativeTo(null);
        setupTray();

        eng.setListener(new Engine.Listener() {
            @Override public void onUpdate() { }
            @Override public void onAlarm(String level) { SwingUtilities.invokeLater(() -> alarmUi(level)); }
            @Override public void onAlarmCleared() { SwingUtilities.invokeLater(() -> { flashCount = 0; refresh(); }); }
        });
        new Timer(500, a -> refresh()).start();
        new Timer(450, a -> flash()).start();
        refresh();
    }

    private static <T extends Component> T align(T c) {
        if (c instanceof javax.swing.JComponent) ((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private JLabel metric(JPanel grid, String label) {
        Widgets.Card m = new Widgets.Card(Theme.PANEL2, Theme.PANEL2, 0f);
        m.setBorder(Widgets.pad(6, 10, 6, 10));
        m.setLayout(new BorderLayout());
        m.add(Widgets.label(label, 11.5f, Theme.MUTED, false), BorderLayout.NORTH);
        JLabel v = new JLabel("--");
        v.setFont(Theme.mono(19f, Font.BOLD));
        v.setForeground(Theme.FG);
        m.add(v, BorderLayout.CENTER);
        grid.add(m);
        return v;
    }

    // ------------------------------------------------------------ 操作
    private void onStartStop() {
        if (eng.running) eng.stop(); else eng.start();
        refresh();
    }

    private void confirmBlade() {
        int r = JOptionPane.showConfirmDialog(this,
                "新しい帯鋸で切削を始めてください。\n" + Settings.fmt(eng.config().learnMin) + " 分間の切削音を、この帯鋸の「平常時の基準」として覚えます。\n\n"
                        + "き裂がある帯鋸で覚えさせないよう、取り付けた直後に押してください。\n\n基準を覚え直しますか？",
                "帯鋸を交換しましたか？", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r == JOptionPane.OK_OPTION) eng.startLearning();
    }

    private void confirmExit() {
        if (eng.running || eng.linkActive()) {
            int r = JOptionPane.showConfirmDialog(this,
                    "監視" + (eng.linkActive() ? "と PLC との通信" : "") + "を止めて終了しますか？", "終了",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
        }
        eng.shutdown();
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        dispose();
        System.exit(0);
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        try {
            tray = new TrayIcon(Widgets.icon(32, null), "帯鋸打音モニター");
            tray.setImageAutoSize(true);
            tray.addActionListener(a -> { setVisible(true); setExtendedState(NORMAL); toFront(); });
            SystemTray.getSystemTray().add(tray);
        } catch (AWTException | UnsupportedOperationException ignored) { tray = null; }
    }

    private void alarmUi(String level) {
        boolean warn = "WARN".equals(level);
        String title = warn ? "【注意】帯鋸の打音" : "BREAK".equals(level) ? "【破断音】大きな異常音" : "【警報】帯鋸の打音が強い";
        String text = warn ? "帯鋸1周ごとの打音が続いています。早めに止めて溶接部を点検してください。"
                : "できるだけ早く止めて帯鋸を交換してください。機械に近づかないでください。";
        if (tray != null) tray.displayMessage(title, text, warn ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.ERROR);
        if (eng.settings.toFront) {
            setVisible(true);
            setExtendedState(NORMAL);
            setAlwaysOnTop(true);
            toFront();
            new Timer(1500, a -> setAlwaysOnTop(false)) {{ setRepeats(false); }}.start();
        }
        refresh();
    }

    private void flash() {
        boolean alarm = eng.alarming && ("ALARM".equals(eng.state) || !"WARN".equals(eng.state));
        if (eng.alarming && alarm) {
            flashOn = !flashOn;
            lamp.setColors(flashOn ? Theme.ALARM : Theme.ALARM_BG, Theme.ALARM);
            stateL.setForeground(flashOn ? Color.WHITE : Theme.ALARM);
            if (tray != null) tray.setImage(Widgets.icon(32, flashOn ? Theme.ALARM : null));
        } else if (flashOn) {
            flashOn = false;
            if (tray != null) tray.setImage(Widgets.icon(32, null));
        }
    }

    // ------------------------------------------------------------ 表示
    private void setLamp(String s, Color c, String msg) {
        stateL.setText(s);
        if (!(eng.alarming && c == Theme.ALARM)) stateL.setForeground(c == Theme.OK || c == Theme.MUTED ? Theme.FG : c);
        if (!(eng.alarming && c == Theme.ALARM)) lamp.setColors(c == Theme.ALARM ? Theme.ALARM_BG : Theme.PANEL, c);
        msgL.setText("<html>" + msg + "</html>");
    }

    private static String f1(double v) { return Double.isNaN(v) ? "--" : String.format(Locale.JAPAN, "%.1f", v); }

    void refresh() {
        Core.Config c = eng.config();
        double[] rg = c.bladeRange();
        specL.setText(String.format(Locale.JAPAN, "帯鋸 %smm / ホイール %smm / 1周 %.2f〜%.2fHz", Settings.fmt(c.bladeMm), Settings.fmt(c.wheelMm), rg[0], rg[1]));
        Core.Blade bl = eng.settings.loadBlade();
        boolean learning = eng.running ? eng.bladeLearning() : bl.learning;
        baseL.setText(learning ? "この帯鋸の基準を学習中" : bl.hasBase ? String.format(Locale.JAPAN, "この帯鋸の基準 %.1fdB", bl.baseP90) : "基準なし（固定のしきい値で判定）");

        startB.setText(eng.running ? "監視停止" : "監視開始");
        startB.setColors(eng.running ? Theme.PANEL2 : Theme.ACCENT, eng.running ? Theme.FG : Color.WHITE, eng.running ? Theme.LINE : null);
        ackB.setVisible(eng.alarming);

        Core.Thresholds th = eng.currentThresholds();
        String mode = "blade".equals(th.mode) ? "この帯鋸の基準から" : "learning".equals(th.mode) ? "基準を学習中" : "固定のしきい値";
        thL.setText(String.format(Locale.JAPAN, "%s　注意 平均%.1fdB ／ 警報 平均%.1fdB・瞬時%.1fdB", mode, th.warn, th.alarmAvg, th.inst));
        prog.setVisible(learning);
        prog.setValue((int) Math.round(eng.learnProgress * 100));

        Core.Result r = eng.last;
        if (eng.error != null && !eng.running) {
            setLamp("停止中", Theme.WARN, eng.error);
        } else if (!eng.running && eng.linkActive()) {
            setLamp("待機中", Theme.ACCENT, "PLC からの指令を待っています。「監視開始」か PLC の開始指令で監視を始めます。");
        } else if (!eng.running) {
            setLamp("停止中", Theme.MUTED, learning ? "「監視開始」を押すと、この帯鋸の基準を学習し始めます。" : "「監視開始」を押すと、音を聞き始めます。");
        } else {
            String st = eng.state;
            if (r == null || !r.ready) setLamp("準備中", Theme.MUTED, "音を集めています（約10秒）…");
            else if ("IDLE".equals(r.state)) setLamp("準備中", Theme.MUTED, "音が小さすぎます（停止中？）。マイクを帯鋸に近づけてください。");
            else if ("ALARM".equals(st)) setLamp("警報", Theme.ALARM, "帯鋸1周ごとの打音が強い。できるだけ早く止めて交換。機械に近づかない");
            else if ("WARN".equals(st)) setLamp("注意", Theme.WARN, "帯鋸1周ごとの打音あり。早めに止めて溶接部を点検");
            else if (learning) setLamp("学習中", Theme.LEARN, String.format(Locale.JAPAN, "この帯鋸の基準を学習中（%d%%）。強い打音のときだけ警報します", Math.round(eng.learnProgress * 100)));
            else setLamp("平常", Theme.OK, "き裂の打音は出ていません");
        }
        if (eng.running && r != null) {
            avgL.setText(f1(r.avg));
            instV.setText(f1(r.score));
            freqV.setText(Double.isNaN(r.freq) ? "--" : String.format(Locale.JAPAN, "%.2f Hz", r.freq));
            lockV.setText(Double.isNaN(r.lock) ? "--" : String.format(Locale.JAPAN, "%.2f", r.lock));
            lvlV.setText(f1(r.fullDb));
        } else if (!eng.running) {
            avgL.setText("--.-");
        }
        inputL.setText(eng.running ? eng.inputInfo : "入力: " + ("ffmpeg".equals(eng.settings.inputType) ? "ffmpeg（カメラ・ファイル）" : "マイク"));
        if (!eng.settings.plcOn) plcL.setText("");
        else {
            plcL.setText("<html>" + (eng.plcStatus.isEmpty() ? "PLC: 準備中" : eng.plcStatus) + "</html>");
            plcL.setForeground(eng.plcOk ? Theme.OK : Theme.WARN);
        }
        quitLinkB.setVisible(false);
        updateChart(th);
        renderLog();
    }

    private void updateChart(Core.Thresholds th) {
        long now = System.currentTimeMillis();
        long range = RANGES[rangeIdx];
        long t1 = viewEnd == 0 ? now : viewEnd, t0 = t1 - range;
        for (int i = 0; i < rangeBs.length; i++) {
            boolean sel = i == rangeIdx;
            rangeBs[i].setColors(sel ? Theme.ACCENT : Theme.PANEL2, sel ? Color.WHITE : Theme.FG, sel ? null : Theme.LINE);
        }
        nextB.setEnabled(viewEnd != 0);
        SimpleDateFormat f = new SimpleDateFormat(range >= DAY ? "MM/dd HH:mm" : "MM/dd HH:mm:ss", Locale.JAPAN);
        rangeL.setText(f.format(new Date(t0)) + " 〜 " + (viewEnd == 0 ? "今" : f.format(new Date(t1))));

        List<double[]> pts;
        boolean fine = false;
        if (rangeIdx == 0 && viewEnd == 0 && eng.running) {
            synchronized (eng.live) { pts = new ArrayList<>(eng.live); }
            fine = true;
        } else {
            String key = rangeIdx + ":" + viewEnd;
            if (!histLoading && (!key.equals(histKey) || (viewEnd == 0 && now - histAt > 10_000))) {
                histLoading = true;
                final long a = t0 - HistoryStore.BUCKET_MS, b = t1 + HistoryStore.BUCKET_MS;
                io.submit(() -> {
                    eng.history.flush();
                    List<double[]> l = eng.history.load(a, b);
                    SwingUtilities.invokeLater(() -> { histPts = l; histKey = key; histAt = System.currentTimeMillis(); histLoading = false; refresh(); });
                });
            }
            pts = histPts;
        }
        List<ChartPanel.Mark> marks = new ArrayList<>();
        for (EventLog.Event e : eng.events.all()) {
            if (e.t < t0 || e.t > t1) continue;
            switch (e.type) {
                case "ALARM": marks.add(new ChartPanel.Mark(e.t, Theme.ALARM, "警報")); break;
                case "WARN": marks.add(new ChartPanel.Mark(e.t, Theme.WARN, "注意")); break;
                case "BREAK": marks.add(new ChartPanel.Mark(e.t, Theme.ALARM, "破断音")); break;
                case "LEARN": marks.add(new ChartPanel.Mark(e.t, Theme.LEARN, "交換")); break;
            }
        }
        chart.setData(pts, th, t0, t1, marks, histLoading && pts.isEmpty() ? "読み込み中…" : "この期間の記録はありません", fine);
    }

    private void renderLog() {
        List<EventLog.Event> ev = eng.events.all();
        long top = ev.isEmpty() ? 0 : ev.get(0).t;
        if (ev.size() == lastEventCount && top == lastEventTop) return;
        lastEventCount = ev.size();
        lastEventTop = top;
        StyledDocument d = log.getStyledDocument();
        SimpleDateFormat f = new SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN);
        try {
            d.remove(0, d.getLength());
            if (ev.isEmpty()) { d.insertString(0, "まだ記録はありません。", style(Theme.MUTED, false)); return; }
            int n = 0;
            for (EventLog.Event e : ev) {
                if (n++ >= 200) break;
                if (d.getLength() > 0) d.insertString(d.getLength(), "\n", null);
                d.insertString(d.getLength(), f.format(new Date(e.t)) + "  ", style(Theme.MUTED, false));
                Color col = ("ALARM".equals(e.type) || "BREAK".equals(e.type)) ? Theme.ALARM : "WARN".equals(e.type) ? Theme.WARN
                        : ("LEARN".equals(e.type) || "LEARNED".equals(e.type) || "BASE_HIGH".equals(e.type)) ? Theme.LEARN
                        : "PLC".equals(e.type) ? Theme.ACCENT : Theme.FG;
                d.insertString(d.getLength(), "[" + EventLog.label(e.type) + "] ", style(col, true));
                d.insertString(d.getLength(), e.text + (Double.isNaN(e.score) ? "" : String.format(Locale.JAPAN, "  %.1fdB", e.score)), style(Theme.FG, false));
            }
            log.setCaretPosition(0);
        } catch (BadLocationException ignored) { }
    }

    private static SimpleAttributeSet style(Color c, boolean bold) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, c);
        StyleConstants.setBold(a, bold);
        return a;
    }

    private void exportCsv() {
        long range = RANGES[rangeIdx];
        long t1 = viewEnd == 0 ? System.currentTimeMillis() : viewEnd, t0 = t1 - range;
        String name = "帯鋸_履歴_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN).format(new Date(t0))
                + "-" + new SimpleDateFormat("MMdd_HHmm", Locale.JAPAN).format(new Date(t1)) + ".csv";
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("CSV の保存先");
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), name));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        File ev = new File(f.getParentFile(), f.getName().replace("履歴", "記録").replace(".csv", "") + (f.getName().contains("履歴") ? ".csv" : "_記録.csv"));
        io.submit(() -> {
            String msg;
            try {
                eng.history.flush();
                int rows;
                try (OutputStream os = new FileOutputStream(f)) { rows = eng.history.writeCsv(os, t0, t1); }
                try (OutputStream os = new FileOutputStream(ev)) { eng.events.writeCsv(os, t0, t1); }
                msg = rows + " 行を保存しました。\n" + f.getAbsolutePath() + "\n" + ev.getAbsolutePath();
            } catch (Exception e) {
                msg = "保存できませんでした（" + e.getMessage() + "）";
            }
            final String m = msg;
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, m, "CSV で保存", JOptionPane.INFORMATION_MESSAGE));
        });
    }
}
