package jp.bandsaw.pc;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 画面の色と文字（現場の操作盤風の暗い盤面。Android 版と同じ色） */
final class Theme {
    private Theme() {}
    static final Color BG = new Color(0x14181D), PANEL = new Color(0x1C2229), PANEL2 = new Color(0x232B34), LINE = new Color(0x323C47);
    static final Color FG = new Color(0xE8EDF2), MUTED = new Color(0x9AA7B4), ACCENT = new Color(0x4FA3E0), INST = new Color(0x4A6580);
    static final Color OK = new Color(0x3FB06A), WARN = new Color(0xF0B429), ALARM = new Color(0xEF4A3C), LEARN = new Color(0x8A7CF0);
    static final Color ALARM_BG = new Color(0x3A1F1F);

    private static String family;

    /** 日本語が読みやすいフォント（Windows: Yu Gothic UI / Meiryo UI） */
    static Font font(float size) {
        if (family == null) {
            Set<String> have = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            family = Font.DIALOG;
            for (String f : new String[]{"Yu Gothic UI", "Meiryo UI", "Meiryo", "Noto Sans CJK JP", "IPAexGothic"})
                if (have.contains(f)) { family = f; break; }
        }
        return new Font(family, Font.PLAIN, 1).deriveFont(size);
    }

    static Font mono(float size, int style) { return new Font(Font.MONOSPACED, style, 1).deriveFont(size); }
}
