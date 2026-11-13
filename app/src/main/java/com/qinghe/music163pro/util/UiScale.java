package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

/**
 * 全局界面缩放（**按 densityDpi**）。
 *
 * <p>需求方 2026-11-14：原来的「百分比缩放」改成直接选 dpi。百分比本质也是换算成
 * {@code densityDpi = 基准 × 百分比}，直接暴露 dpi 更直观，也不会出现「115% 到底是多大」的疑问。</p>
 *
 * <p>这个 App 的界面有两个来源，必须**同比例**缩放：</p>
 * <ul>
 *   <li><b>XML 布局</b>用 dp / sp，像素值 = 数值 × density；改 {@link Configuration#densityDpi}
 *       之后两者都跟着变（sp 的 scaledDensity = density × fontScale，所以**不需要再动 fontScale**）。</li>
 *   <li><b>代码里拼的界面</b>用 {@link WatchUiUtils#px}，它按 {@code 屏幕宽度 / 320} 换算，
 *       屏幕宽度不随 densityDpi 变化，所以那里要额外乘 {@link #getScale}（= dpi / 320）。</li>
 * </ul>
 */
public final class UiScale {

    private static final String PREFS_NAME = "music163_settings";
    /** 界面密度（dpi）。320 是手表原始值，等于 100%。 */
    private static final String KEY_DENSITY_DPI = "ui_density_dpi";
    /** 旧版「百分比」键，仅用于一次性迁移。 */
    private static final String KEY_LEGACY_PERCENT = "ui_scale_percent";

    /** 基准 dpi（设备原始 density=2.0）。 */
    public static final int BASE_DPI = 320;
    /** 可选档位（dpi）：320 = 原始，360/400/440/480 依次放大。 */
    public static final int[] STEPS = {320, 360, 400, 440, 480};
    private static final int DEFAULT_DPI = BASE_DPI;

    private UiScale() {
    }

    /** 缓存当前 dpi：{@link WatchUiUtils#px} 在布局期间会被调上千次。 */
    private static volatile int cachedDpi = -1;

    /** 当前界面 dpi；首次读取时把旧版的百分比换算并落盘。 */
    public static int getDpi(Context context) {
        int cached = cachedDpi;
        if (cached > 0) {
            return cached;
        }
        if (context == null) {
            return DEFAULT_DPI;
        }
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int dpi = prefs.getInt(KEY_DENSITY_DPI, 0);
        if (dpi <= 0) {
            // 迁移旧的百分比（85/100/115/130）→ 最接近的 dpi 档位
            int legacyPercent = prefs.getInt(KEY_LEGACY_PERCENT, 100);
            int target = Math.round(BASE_DPI * legacyPercent / 100f);
            int nearest = STEPS[0];
            int best = Integer.MAX_VALUE;
            for (int step : STEPS) {
                int distance = Math.abs(step - target);
                if (distance < best) {
                    best = distance;
                    nearest = step;
                }
            }
            dpi = nearest;
            prefs.edit().putInt(KEY_DENSITY_DPI, dpi).apply();
        }
        cachedDpi = dpi;
        return dpi;
    }

    public static void setDpi(Context context, int dpi) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_DENSITY_DPI, dpi).apply();
        cachedDpi = dpi;
    }

    /** 切到下一档 dpi，返回切换后的值。 */
    public static int cycle(Context context) {
        int current = getDpi(context);
        int index = 0;
        for (int i = 0; i < STEPS.length; i++) {
            if (STEPS[i] == current) {
                index = i;
                break;
            }
        }
        int next = STEPS[(index + 1) % STEPS.length];
        setDpi(context, next);
        return next;
    }

    /** 相对基准 dpi 的倍数（给按屏幕宽度换算的 {@link WatchUiUtils#px} 用）。 */
    public static float getScale(Context context) {
        return getDpi(context) / (float) BASE_DPI;
    }

    /**
     * 返回一个改写了 densityDpi 的 Context 包装，在 {@code attachBaseContext} 里调用。
     *
     * <p>dpi 与当前一致时直接返回原 Context（不制造多余的 Resources 拷贝）。</p>
     */
    public static Context wrap(Context base) {
        if (base == null) {
            return null;
        }
        int dpi = getDpi(base);
        try {
            Resources resources = base.getResources();
            Configuration configuration = new Configuration(resources.getConfiguration());
            int baseDensityDpi = configuration.densityDpi;
            if (baseDensityDpi <= 0) {
                baseDensityDpi = resources.getDisplayMetrics().densityDpi;
            }
            if (dpi == baseDensityDpi) {
                return base;
            }
            configuration.densityDpi = dpi;
            // sp 依赖 densityDpi（scaledDensity = density × fontScale），改 dpi 后文字会自动
            // 跟着缩放，所以这里**不再额外乘 fontScale**（否则文字会被放大两次）。
            return base.createConfigurationContext(configuration);
        } catch (Exception e) {
            // 缩放只是观感功能，出问题就退回原始 Context，别把页面带崩
            MusicLog.w("UiScale", "应用界面 dpi 缩放失败，已回退到原始 Context", e);
            return base;
        }
    }
}
