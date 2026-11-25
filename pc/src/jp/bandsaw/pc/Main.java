package jp.bandsaw.pc;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** 帯鋸打音モニター PC 版 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        Settings s = new Settings();
        Engine e = new Engine(s);
        SwingUtilities.invokeLater(() -> {
            MainFrame f = new MainFrame(e);
            f.setVisible(true);
            e.restartLink();
            if (s.autoStart) e.start();
        });
    }
}
