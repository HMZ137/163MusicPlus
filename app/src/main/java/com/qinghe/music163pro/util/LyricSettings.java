package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * 歌词相关的用户设置：时间轴偏移、翻译开关、音译（音）开关。
 *
 * <p>集中放在这里，是为了让播放器歌词浮层（{@code MainActivity}）和全屏歌词页
 * （{@code LyricsActivity}）读同一份配置 —— 否则两边对「偏移该怎么算」「开关是开是关」
 * 很容易各写一套，出现同一个设置在两个页面表现不一致的问题。</p>
 */
public final class LyricSettings {

    private static final String PREFS_NAME = "music163_settings";
    private static final String KEY_OFFSET_MS = "lyric_offset_ms";
    private static final String KEY_TRANSLATION = "lyrics_translation";
    private static final String KEY_ROMANIZATION = "lyrics_romanization";

    /** 偏移的调节步进：0.5 秒。 */
    public static final int OFFSET_STEP_MS = 500;
    /** 偏移的上下限：±5 秒。再大就基本是选错歌词了。 */
    public static final int OFFSET_LIMIT_MS = 5000;

    private LyricSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 歌词时间轴偏移（毫秒）。
     *
     * <p><b>正值 = 歌词延后出现，负值 = 歌词提前出现。</b>默认 0。</p>
     */
    public static int getOffsetMs(Context context) {
        return prefs(context).getInt(KEY_OFFSET_MS, 0);
    }

    public static void setOffsetMs(Context context, int offsetMs) {
        int clamped = Math.max(-OFFSET_LIMIT_MS, Math.min(OFFSET_LIMIT_MS, offsetMs));
        prefs(context).edit().putInt(KEY_OFFSET_MS, clamped).apply();
    }

    /** 在当前偏移基础上增减一个步进（用于设置页的 +/− 按钮）。 */
    public static int stepOffsetMs(Context context, int direction) {
        setOffsetMs(context, getOffsetMs(context) + direction * OFFSET_STEP_MS);
        return getOffsetMs(context);
    }

    /**
     * 把播放进度换算成「用于和歌词时间戳比较」的参考点。
     *
     * <p>判断该高亮哪一句时，用它代替原始进度即可：
     * {@code line.timeMs <= LyricSettings.referencePosition(ctx, positionMs)}。
     * 这样偏移只作用在比较处，解析出来的时间戳保持原样，
     * 主歌词与翻译 / 音译之间靠时间戳对齐的映射关系不会被破坏。</p>
     */
    public static int referencePosition(Context context, int positionMs) {
        return positionMs - getOffsetMs(context);
    }

    /**
     * 把歌词时间戳换算成「应该 seek 到的进度」。
     * 点歌词跳转时使用，保证跳过去之后高亮的还是这一句。
     */
    public static int seekPositionForLine(Context context, long lineTimeMs) {
        return (int) (lineTimeMs + getOffsetMs(context));
    }

    public static boolean isTranslationEnabled(Context context) {
        return prefs(context).getBoolean(KEY_TRANSLATION, false);
    }

    public static void setTranslationEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_TRANSLATION, enabled).apply();
    }

    public static boolean isRomanizationEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ROMANIZATION, false);
    }

    public static void setRomanizationEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ROMANIZATION, enabled).apply();
    }

    /** 偏移的展示文本，例如 {@code +0.5s} / {@code -1.0s} / {@code 0.0s}。 */
    public static String formatOffset(int offsetMs) {
        return String.format(Locale.US, "%+.1fs", offsetMs / 1000f);
    }

    // ==================== 歌词字号 ====================

    private static final String KEY_FONT_ORIGINAL = "lyric_font_original";
    private static final String KEY_FONT_ROMANIZATION = "lyric_font_romanization";
    private static final String KEY_FONT_TRANSLATION = "lyric_font_translation";

    /** 字号可调范围（sp）。下限保证还能看清，上限避免一行放不下几个字。 */
    public static final int FONT_MIN = 9;
    public static final int FONT_MAX = 22;
    public static final int FONT_DEFAULT_ORIGINAL = 13;
    public static final int FONT_DEFAULT_ROMANIZATION = 11;
    public static final int FONT_DEFAULT_TRANSLATION = 11;

    /** 原文歌词字号（sp）。 */
    public static int getOriginalFontSize(Context context) {
        return getFontSize(context, KEY_FONT_ORIGINAL, FONT_DEFAULT_ORIGINAL);
    }

    /** 音译（音）歌词字号（sp）。 */
    public static int getRomanizationFontSize(Context context) {
        return getFontSize(context, KEY_FONT_ROMANIZATION, FONT_DEFAULT_ROMANIZATION);
    }

    /** 翻译（译）歌词字号（sp）。 */
    public static int getTranslationFontSize(Context context) {
        return getFontSize(context, KEY_FONT_TRANSLATION, FONT_DEFAULT_TRANSLATION);
    }

    /**
     * 调整某一类歌词的字号。
     *
     * @param which 0=原文 1=音译 2=翻译
     * @param direction +1 放大 / -1 缩小
     * @return 调整后的字号
     */
    public static int stepFontSize(Context context, int which, int direction) {
        String key;
        int fallback;
        switch (which) {
            case 1:
                key = KEY_FONT_ROMANIZATION;
                fallback = FONT_DEFAULT_ROMANIZATION;
                break;
            case 2:
                key = KEY_FONT_TRANSLATION;
                fallback = FONT_DEFAULT_TRANSLATION;
                break;
            case 0:
            default:
                key = KEY_FONT_ORIGINAL;
                fallback = FONT_DEFAULT_ORIGINAL;
                break;
        }
        int next = getFontSize(context, key, fallback) + direction;
        next = Math.max(FONT_MIN, Math.min(FONT_MAX, next));
        prefs(context).edit().putInt(key, next).apply();
        return next;
    }

    // ==================== 歌词底衬 ====================

    private static final String KEY_BACKDROP = "lyric_backdrop_enabled";
    /**
     * 底衬填色：半透明黑。
     *
     * <p>用半透明而不是纯色，是为了既压得住背景、又还能看见自定义背景本身。
     * 透明度约 60%（0x99），在浅色封面图上也能把白字衬出来。</p>
     */
    private static final int BACKDROP_COLOR = 0x99000000;

    /** 是否给歌词文字加半透明底衬（默认关）。 */
    public static boolean isBackdropEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BACKDROP, false);
    }

    public static void setBackdropEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BACKDROP, enabled).apply();
    }

    public static int getBackdropColor() {
        return BACKDROP_COLOR;
    }

    private static int getFontSize(Context context, String key, int fallback) {
        int value = prefs(context).getInt(key, fallback);
        return Math.max(FONT_MIN, Math.min(FONT_MAX, value));
    }
}
