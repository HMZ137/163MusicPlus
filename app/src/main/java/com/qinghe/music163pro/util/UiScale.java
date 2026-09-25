package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

/**
 * 全局界面缩放。
 *
 * <p>这个 App 的界面有两个来源，必须**同比例**缩放，否则会出现「文字变大了、行高没变」
 * 之类的错位：</p>
 * <ul>
 *   <li><b>XML 布局</b>用的是 dp / sp，像素值 = 数值 × density。
 *       所以缩放要靠改写 {@link Configuration#densityDpi} 实现（见 {@link #wrap}）。</li>
 *   <li><b>代码里拼的界面</b>用的是 {@link WatchUiUtils#px}，它按
 *       {@code 屏幕宽度 / 320} 换算。屏幕宽度不随 densityDpi 变化，所以这里要额外乘一次缩放系数。</li>
 * </ul>
 *
 * <p>两者都乘同一个 {@code scale} 之后结果是一致的：320px 宽、density=1.0 的屏上，
 * {@code px(16)} 与 {@code 16dp} 本来都是 16px；缩放 1.2 倍后前者是
 * {@code 16 × 1.2}、后者是 {@code 16 × 1.0 × 1.2}，仍然相等。</p>
 */
public final class UiScale {

    private static final String PREFS_NAME = "music163_settings";
    private static final String KEY_SCALE_PERCENT = "ui_scale_percent";

    /** 可选档位（百分比）。范围刻意收紧，避免把手表界面撑爆或缩到点不中。 */
    public static final int[] STEPS = {85, 100, 115, 130};
    private static final int DEFAULT_STEP_INDEX = 1;

    private UiScale() {
    }

    /**
     * 缓存缩放系数。
     *
     * <p>{@link WatchUiUtils#px} 在布局期间会被调用成百上千次，每次去读
     * SharedPreferences 太亏；缩放只在设置页改动，改完把缓存置为无效即可。</p>
     */
    private static volatile float cachedScale = -1f;

    /** 当前缩放百分比（85 / 100 / 115 / 130）。 */
    public static int getPercent(Context context) {
        if (context == null) {
            return STEPS[DEFAULT_STEP_INDEX];
        }
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_SCALE_PERCENT, STEPS[DEFAULT_STEP_INDEX]);
    }

    public static void setPercent(Context context, int percent) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_SCALE_PERCENT, percent).apply();
        cachedScale = -1f;
    }

    /** 循环切到下一档，返回切换后的百分比。 */
    public static int cycle(Context context) {
        int current = getPercent(context);
        int index = 0;
        for (int i = 0; i < STEPS.length; i++) {
            if (STEPS[i] == current) {
                index = i;
                break;
            }
        }
        int next = STEPS[(index + 1) % STEPS.length];
        setPercent(context, next);
        return next;
    }

    /** 缩放系数（1.0 = 不缩放）。 */
    public static float getScale(Context context) {
        float cached = cachedScale;
        if (cached > 0f) {
            return cached;
        }
        float scale = getPercent(context) / 100f;
        cachedScale = scale;
        return scale;
    }

    /**
     * 返回一个被改写了 densityDpi / fontScale 的 Context 包装。
     *
     * <p>在 {@code Activity.attachBaseContext} 里调用，就能让该 Activity 的 XML 布局
     * 整体按缩放系数排布。**不修改全局 Resources**，只影响当前 Activity。</p>
     */
    public static Context wrap(Context base) {
        if (base == null) {
            return null;
        }
        float scale = getScale(base);
        if (Math.abs(scale - 1.0f) < 0.001f) {
            return base;
        }
        try {
            Resources resources = base.getResources();
            Configuration configuration = new Configuration(resources.getConfiguration());
            int baseDensityDpi = configuration.densityDpi;
            if (baseDensityDpi <= 0) {
                baseDensityDpi = resources.getDisplayMetrics().densityDpi;
            }
            configuration.densityDpi = Math.round(baseDensityDpi * scale);
            // sp 也一起放大：否则「页面缩放」只动了间距、文字还是原样
            configuration.fontScale = configuration.fontScale * scale;
            return base.createConfigurationContext(configuration);
        } catch (Exception e) {
            // 缩放只是观感功能，出问题就退回原始 Context，别把页面带崩
            MusicLog.w("UiScale", "应用界面缩放失败，已回退到原始 Context", e);
            return base;
        }
    }
}
