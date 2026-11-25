package jp.bandsaw.pc;

import jp.bandsaw.monitor.Core;
import jp.bandsaw.monitor.Slmp;
import jp.bandsaw.monitor.SlmpServer;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Locale;

/** 設定（判定・音声入力・警報・PLC） */
final class SettingsDialog extends JDialog {
    private final Engine eng;
    private final JTextField[] cfgF = new JTextField[Settings.CFG_KEYS.length];
    private final JRadioButton inMic = new JRadioButton("PC のマイク"), inFf = new JRadioButton("ffmpeg（カメラの音声 RTSP・録画ファイル・dshow）");
    private final JComboBox<String> micBox = new JComboBox<>();
    private final JTextField ffSrc = new JTextField(36), ffPath = new JTextField(30);
    private final JCheckBox sound = new JCheckBox("注意・警報でサイレンを鳴らす"), toFront = new JCheckBox("注意・警報で画面を手前に出す"),
            autoStart = new JCheckBox("アプリを開いたら自動で監視を始める");
    private final JCheckBox plcOn = new JCheckBox("PLC と連携する");
    private final JRadioButton mServer = new JRadioButton("PLC が読みに来る（シンプルCPU通信）おすすめ"),
            mClient = new JRadioButton("PC から PLC に書き込む（PLC 側で RUN中書込みの許可が必要）");
    private final JTextField port = new JTextField(6), head = new JTextField(6), host = new JTextField(14), dev = new JTextField(4);
    private final JCheckBox udp = new JCheckBox("UDP で送る（チェックなしは TCP）");
    private final JTextArea map = new JTextArea();
    private final JLabel headLabel = new JLabel(), portLabel = new JLabel();
    private final JComponent[] clientOnly;

    SettingsDialog(MainFrame owner, Engine e) {
        super(owner, "設定", true);
        eng = e;
        Settings s = e.settings;
        Core.Config c = e.config();
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Theme.font(13f));

        // ---- 判定
        JPanel p1 = grid();
        int y = 0;
        for (int i = 0; i < Settings.CFG_KEYS.length; i++) {
            cfgF[i] = new JTextField(Settings.fmt(Settings.get(c, Settings.CFG_KEYS[i])), 10);
            addRow(p1, y++, Settings.CFG_LABELS[i], cfgF[i]);
        }
        addNote(p1, y, "帯鋸の基準を覚えたあとは「固定のしきい値」と「基準＋上昇幅」の大きい方で判定します。");
        tabs.addTab("判定", wrap(p1));

        // ---- 音声入力
        JPanel p2 = grid();
        ButtonGroup g1 = new ButtonGroup();
        g1.add(inMic); g1.add(inFf);
        (("ffmpeg".equals(s.inputType)) ? inFf : inMic).setSelected(true);
        y = 0;
        addFull(p2, y++, inMic);
        micBox.addItem("（既定のマイク）");
        List<String> mics = AudioInput.micNames();
        for (String m : mics) micBox.addItem(m);
        micBox.setSelectedItem(s.micName.isEmpty() ? "（既定のマイク）" : s.micName);
        addRow(p2, y++, "使うマイク", micBox);
        addFull(p2, y++, inFf);
        ffSrc.setText(s.ffmpegSource);
        addRow(p2, y++, "入力元", ffSrc);
        addNote(p2, y++, "<html>例: カメラ <b>rtsp://ユーザー:パスワード@192.168.1.100:554/…</b>（URL はカメラ／NVR のメーカーで違います）<br>"
                + "録画ファイル <b>C:\\録画\\cam9.mp4</b>（実時間で再生して監視）<br>Windows のマイクを ffmpeg で <b>dshow:audio=マイク名</b></html>");
        JPanel ffRow = new JPanel(new BorderLayout(6, 0));
        ffPath.setText(s.ffmpegPath);
        ffRow.add(ffPath, BorderLayout.CENTER);
        JButton browse = new JButton("参照…");
        browse.addActionListener(a -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) ffPath.setText(fc.getSelectedFile().getAbsolutePath());
        });
        ffRow.add(browse, BorderLayout.EAST);
        addRow(p2, y++, "ffmpeg.exe の場所", ffRow);
        addNote(p2, y, "空欄ならアプリに同梱の ffmpeg を使います（今: " + AudioInput.findFfmpeg(s.ffmpegPath) + "）");
        tabs.addTab("音声入力", wrap(p2));

        // ---- 警報
        JPanel p3 = grid();
        sound.setSelected(s.sound);
        toFront.setSelected(s.toFront);
        autoStart.setSelected(s.autoStart);
        addFull(p3, 0, sound);
        addFull(p3, 1, toFront);
        addFull(p3, 2, autoStart);
        addNote(p3, 3, "注意・警報は Windows の通知（タスクトレイ）にも出ます。");
        tabs.addTab("警報・起動", wrap(p3));

        // ---- PLC
        JPanel p4 = grid();
        plcOn.setSelected(s.plcOn);
        ButtonGroup g2 = new ButtonGroup();
        g2.add(mServer); g2.add(mClient);
        (s.plcServer ? mServer : mClient).setSelected(true);
        port.setText(String.valueOf(s.plcPort));
        head.setText(!s.plcServer && "W".equalsIgnoreCase(s.plcDev) ? Integer.toHexString(s.plcHead).toUpperCase(Locale.ROOT) : String.valueOf(s.plcHead));
        host.setText(s.plcHost);
        dev.setText(s.plcDev);
        udp.setSelected(s.plcUdp);
        y = 0;
        addFull(p4, y++, plcOn);
        addFull(p4, y++, mServer);
        addFull(p4, y++, mClient);
        addNote(p4, y++, "この PC の IP: " + Net.ipList() + "（PLC の交信先に登録します。PC は固定IPにしてください）");
        addRow(p4, y++, portLabel, port);
        addRow(p4, y++, headLabel, head);
        JLabel hl = new JLabel("PLC の IP アドレス"), dl = new JLabel("書き込む先頭デバイス（D / W / R / ZR）");
        addRow(p4, y++, hl, host);
        addRow(p4, y++, dl, dev);
        addFull(p4, y++, udp);
        clientOnly = new JComponent[]{hl, host, dl, dev, udp};
        map.setEditable(false);
        map.setFont(Theme.font(12f));
        map.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.gridy = y; gc.gridwidth = 2; gc.anchor = GridBagConstraints.WEST; gc.insets = new Insets(6, 4, 4, 4);
        p4.add(map, gc);
        mServer.addActionListener(a -> { head.setText("0"); applyMode(); });
        mClient.addActionListener(a -> { head.setText("1000"); applyMode(); });
        applyMode();
        tabs.addTab("PLC", wrap(p4));

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("保存"), cancel = new JButton("閉じる");
        ok.addActionListener(a -> save());
        cancel.addActionListener(a -> dispose());
        btns.add(ok);
        btns.add(cancel);
        getRootPane().setDefaultButton(ok);

        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(btns, BorderLayout.SOUTH);
        setSize(720, 640);
        setLocationRelativeTo(owner);
    }

    private void applyMode() {
        boolean sv = mServer.isSelected();
        for (JComponent c : clientOnly) c.setVisible(!sv);
        portLabel.setText(sv ? "待ち受けるポート番号（TCP・UDP 両方）" : "PLC のポート番号");
        headLabel.setText(sv ? "先頭デバイス（PC 側の D 番号。40語使う）" : "先頭番号（W は16進。連続5語に書く）");
        map.setText(sv
                ? "PC→PLC（読出し20点）\n +0 状態（0停止/待機 1準備中 2平常 3学習中 4注意 5警報）\n +1 生存カウンタ ／ +2 平均×10 ／ +3 瞬時×10 ／ +4 未確認の警報\n"
                + " +5 指令の受付カウンタ ／ +6 結果（0=OK）／ +7 学習% ／ +8 監視中\n +10〜+19 今の設定値\n"
                + "PLC→PC（書込み20点）\n +20 指令 .0開始 .1停止 .2帯鋸交換 .3警報確認 .4設定反映\n +21〜+30 新しい設定値"
                : " +0 状態（0停止 1準備中 2平常 3学習中 4注意 5警報）\n +1 生存カウンタ（0.5秒ごとに+1）\n +2 30秒平均×10 ／ +3 瞬時×10\n +4 未確認の警報（1=鳴動中）");
        revalidate();
        repaint();
    }

    private void save() {
        Settings s = eng.settings;
        Core.Config c = eng.config().copy();
        for (int i = 0; i < cfgF.length; i++) {
            try { Settings.set(c, Settings.CFG_KEYS[i], Double.parseDouble(cfgF[i].getText().trim())); }
            catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "「" + Settings.CFG_LABELS[i] + "」の値が数字ではありません。", "設定", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        if (c.rpmMax < c.rpmMin) { JOptionPane.showMessageDialog(this, "回転数の最大が最小より小さくなっています。", "設定", JOptionPane.WARNING_MESSAGE); return; }
        String oldInput = s.inputType + "|" + s.micName + "|" + s.ffmpegSource + "|" + s.ffmpegPath;
        s.inputType = inFf.isSelected() ? "ffmpeg" : "mic";
        Object mic = micBox.getSelectedItem();
        s.micName = mic == null || micBox.getSelectedIndex() == 0 ? "" : mic.toString();
        s.ffmpegSource = ffSrc.getText().trim();
        s.ffmpegPath = ffPath.getText().trim();
        s.sound = sound.isSelected();
        s.toFront = toFront.isSelected();
        s.autoStart = autoStart.isSelected();
        s.plcOn = plcOn.isSelected();
        s.plcServer = mServer.isSelected();
        s.plcUdp = udp.isSelected();
        s.plcHost = host.getText().trim();
        String dv = dev.getText().trim().toUpperCase(Locale.ROOT);
        boolean okDev = false;
        for (String d0 : Slmp.DEVICES) if (d0.equals(dv)) okDev = true;
        s.plcDev = okDev ? dv : "D";
        try { s.plcPort = Integer.parseInt(port.getText().trim()); } catch (NumberFormatException ignored) { }
        try { s.plcHead = Integer.parseInt(head.getText().trim(), (!s.plcServer && "W".equals(s.plcDev)) ? 16 : 10); } catch (NumberFormatException ignored) { }
        if (s.plcServer) s.plcHead = Math.max(0, Math.min(SlmpServer.MEM - 40, s.plcHead));
        s.save();
        s.saveConfig(c);
        String newInput = s.inputType + "|" + s.micName + "|" + s.ffmpegSource + "|" + s.ffmpegPath;
        eng.applySettings(!oldInput.equals(newInput));
        dispose();
    }

    // ------------------------------------------------------------ 並べ方
    private static JPanel grid() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        return p;
    }

    private static JScrollPane wrap(JPanel p) {
        JPanel outer = new JPanel(new BorderLayout());
        outer.add(p, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(outer);
        sp.setBorder(BorderFactory.createEmptyBorder());
        return sp;
    }

    private static void addRow(JPanel p, int y, Object label, JComponent field) {
        GridBagConstraints a = new GridBagConstraints();
        a.gridx = 0; a.gridy = y; a.anchor = GridBagConstraints.WEST; a.insets = new Insets(4, 4, 4, 10);
        JLabel l = label instanceof JLabel ? (JLabel) label : new JLabel(label.toString());
        l.setFont(Theme.font(13f));
        p.add(l, a);
        GridBagConstraints b = new GridBagConstraints();
        b.gridx = 1; b.gridy = y; b.anchor = GridBagConstraints.WEST; b.fill = GridBagConstraints.HORIZONTAL; b.weightx = 1; b.insets = new Insets(4, 4, 4, 4);
        field.setFont(field instanceof JTextField ? Theme.mono(14f, Font.PLAIN) : Theme.font(13f));
        p.add(field, b);
    }

    private static void addFull(JPanel p, int y, JComponent c) {
        GridBagConstraints a = new GridBagConstraints();
        a.gridx = 0; a.gridy = y; a.gridwidth = 2; a.anchor = GridBagConstraints.WEST; a.insets = new Insets(6, 2, 2, 4);
        c.setFont(Theme.font(13f));
        p.add(c, a);
    }

    private static void addNote(JPanel p, int y, String text) {
        JLabel l = new JLabel(text.startsWith("<html>") ? text : "<html>" + text + "</html>");
        l.setFont(Theme.font(12f));
        l.setForeground(new java.awt.Color(0x55606B));
        GridBagConstraints a = new GridBagConstraints();
        a.gridx = 0; a.gridy = y; a.gridwidth = 2; a.anchor = GridBagConstraints.WEST; a.fill = GridBagConstraints.HORIZONTAL; a.insets = new Insets(2, 6, 8, 4);
        p.add(l, a);
    }
}
