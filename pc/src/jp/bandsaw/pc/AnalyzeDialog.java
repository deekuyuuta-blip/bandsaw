package jp.bandsaw.pc;

import jp.bandsaw.monitor.Core;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 録画ファイル（CAM9 の mp4 など）を解析して、いつ注意・警報が出るかを調べる */
final class AnalyzeDialog extends JDialog {
    private final Engine eng;
    private final JTextField fileF = new JTextField(34), startF = new JTextField(8);
    private final JComboBox<String> base = new JComboBox<>(new String[]{"固定のしきい値", "今の帯鋸の基準", "ファイルの最初で学習"});
    private final JProgressBar prog = new JProgressBar(0, 100);
    private final ChartPanel chart = new ChartPanel();
    private final JTextArea out = new JTextArea(8, 60);
    private final JButton run = new JButton("解析する");
    private volatile boolean cancel;

    AnalyzeDialog(MainFrame owner, Engine e) {
        super(owner, "録画ファイルを検証", false);
        eng = e;
        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        r1.add(new JLabel("ファイル"));
        r1.add(fileF);
        JButton pick = new JButton("選ぶ…");
        pick.addActionListener(a -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                fileF.setText(fc.getSelectedFile().getAbsolutePath());
                Matcher m = Pattern.compile("(\\d{2})[-_:]?(\\d{2})[-_:]?(\\d{2})").matcher(fc.getSelectedFile().getName());
                if (startF.getText().isEmpty() && m.find()) startF.setText(m.group(1) + ":" + m.group(2) + ":" + m.group(3));
            }
        });
        r1.add(pick);
        top.add(r1);
        JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        r2.add(new JLabel("録画の開始時刻（任意）"));
        startF.setToolTipText("例 12:58:45。グラフと結果をこの時刻で表示します");
        r2.add(startF);
        r2.add(new JLabel("  判定の基準"));
        r2.add(base);
        r2.add(run);
        top.add(r2);
        prog.setStringPainted(true);
        top.add(prog);
        run.addActionListener(a -> start());

        chart.setPreferredSize(new java.awt.Dimension(900, 300));
        JPanel chartWrap = new JPanel(new BorderLayout());
        chartWrap.setBackground(Theme.PANEL);
        chartWrap.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        chartWrap.add(chart);
        out.setEditable(false);
        out.setFont(Theme.font(13f));
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(chartWrap, BorderLayout.CENTER);
        getContentPane().add(new JScrollPane(out), BorderLayout.SOUTH);
        setSize(980, 700);
        setLocationRelativeTo(owner);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent we) { cancel = true; }
        });
    }

    private void start() {
        String path = fileF.getText().trim();
        if (path.isEmpty() || !new File(path).exists()) { out.setText("ファイルが見つかりません。"); return; }
        Calendar cal = Calendar.getInstance();
        boolean clock = false;
        Matcher m = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$").matcher(startF.getText().trim());
        if (m.find()) {
            cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
            cal.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
            cal.set(Calendar.SECOND, m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
            cal.set(Calendar.MILLISECOND, 0);
            clock = true;
        } else {
            cal.setTimeInMillis(0);
            cal.add(Calendar.MILLISECOND, -cal.getTimeZone().getOffset(0));   // 00:00:00 から数える
        }
        final long t0 = cal.getTimeInMillis();
        final boolean useClock = clock;
        final int mode = base.getSelectedIndex();
        run.setEnabled(false);
        cancel = false;
        out.setText("解析中…");
        new Thread(() -> analyze(path, t0, useClock, mode), "analyze").start();
    }

    private void analyze(String path, long t0, boolean clock, int mode) {
        String ff = AudioInput.findFfmpeg(eng.settings.ffmpegPath);
        double dur = probeDuration(ff, path);
        Core.Config c = eng.config();
        Core.Blade b;
        if (mode == 1) { b = eng.settings.loadBlade(); b.learning = false; }
        else { b = new Core.Blade(); b.learning = mode == 2; }
        Core.Detector det = new Core.Detector(c, AudioInput.SR);
        Core.Judge j = new Core.Judge(c, b);
        List<double[]> pts = new ArrayList<>();
        List<ChartPanel.Mark> marks = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);
        double t = 0, maxS = Double.NaN, maxA = Double.NaN;
        String firstWarn = null, firstAlarm = null;
        try (AudioInput.Ffmpeg in = new AudioInput.Ffmpeg(ff, path, false)) {
            float[] buf = new float[8192];
            List<Core.Result> rs = new ArrayList<>();
            int n;
            while (!cancel && (n = in.read(buf)) >= 0) {
                rs.clear();
                det.push(buf, n, rs);
                for (Core.Result r : rs) {
                    t += c.hopSec;
                    long tm = t0 + Math.round(t * 1000);
                    for (String ev : j.update(r, tm)) {
                        String lab = EventLog.label(ev);
                        if ("LOUD".equals(ev)) continue;
                        log.append(f.format(new Date(tm))).append("  [").append(lab).append("] ")
                                .append(Double.isNaN(r.score) ? "" : String.format(Locale.JAPAN, "瞬時 %.1fdB / 平均 %.1fdB", r.score, r.avg)).append('\n');
                        Color col = "WARN".equals(ev) ? Theme.WARN : ("LEARNED".equals(ev) || "BASE_HIGH".equals(ev)) ? Theme.LEARN : Theme.ALARM;
                        if (!"CLEAR".equals(ev)) marks.add(new ChartPanel.Mark(tm, col, lab));
                        if ("WARN".equals(ev) && firstWarn == null) firstWarn = f.format(new Date(tm));
                        if ("ALARM".equals(ev) && firstAlarm == null) firstAlarm = f.format(new Date(tm));
                    }
                    pts.add(new double[]{tm, r.score, r.avg});
                    if (!Double.isNaN(r.score)) maxS = Double.isNaN(maxS) ? r.score : Math.max(maxS, r.score);
                    if (!Double.isNaN(r.avg)) maxA = Double.isNaN(maxA) ? r.avg : Math.max(maxA, r.avg);
                }
                if (dur > 0) { final int pc = (int) Math.min(100, t / dur * 100); SwingUtilities.invokeLater(() -> prog.setValue(pc)); }
            }
        } catch (Exception e) {
            final String msg = "読み込めませんでした（" + e.getMessage() + "）。ffmpeg の場所を設定で確認してください。";
            SwingUtilities.invokeLater(() -> { out.setText(msg); run.setEnabled(true); });
            return;
        }
        final long t1 = t0 + Math.round(t * 1000);
        String head = String.format(Locale.JAPAN, "%.0f 秒を解析（%s 〜 %s）。瞬時スコア 最大 %.1fdB ／ 30秒平均 最大 %.1fdB\n",
                t, f.format(new Date(t0)), f.format(new Date(t1)), maxS, maxA)
                + (firstAlarm != null ? "→ 最初の警報: " + firstAlarm + (clock ? "" : "（録画の先頭から）") + "\n"
                : firstWarn != null ? "→ 最初の注意: " + firstWarn + "\n" : "→ 注意・警報は出ませんでした\n")
                + (mode == 2 ? "（最初の " + Settings.fmt(c.learnMin) + " 分で基準を学習）\n" : "")
                + "\n" + (log.length() == 0 ? "" : log.toString());
        Core.Thresholds th = j.thresholds();
        SwingUtilities.invokeLater(() -> {
            prog.setValue(100);
            chart.setData(pts, th, t0, Math.max(t1, t0 + 1000), marks, "音声がありません", false);
            out.setText(head);
            out.setCaretPosition(0);
            run.setEnabled(true);
        });
    }

    /** ffmpeg -i の出力から長さ（秒）を読む。分からなければ 0 */
    private static double probeDuration(String ff, String path) {
        try {
            Process p = new ProcessBuilder(ff, "-hide_banner", "-i", path).redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            Matcher m = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+\\.?\\d*)").matcher(s);
            if (m.find()) return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Double.parseDouble(m.group(3));
        } catch (Exception ignored) { }
        return 0;
    }
}
