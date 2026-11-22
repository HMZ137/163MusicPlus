package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 表冠调音量的用户偏好（需求 2026-11-21）。
 *
 * <p>独立 key，不与现有音量/设置混用：
 * <ul>
 *   <li>{@code crown_volume_enabled}：总开关，关=表冠不响应音量调整</li>
 *   <li>{@code crown_volume_sensitivity}：灵敏度 1~3（影响连转时的步进节拍）</li>
 *   <li>{@code crown_volume_max}：表冠调音量的上限百分比（10~100），到顶不再加</li>
 * </ul></p>
 */
public final class CrownVolumePrefs {

    private static final String PREFS = "crown_volume_settings";

    public static final String KEY_ENABLED = "crown_volume_enabled";
    public static final String KEY_SENSITIVITY = "crown_volume_sensitivity";
    public static final String KEY_MAX_PERCENT = "crown_volume_max_percent";
    /** 2026-11-22：自定义灵敏度倍率（默认 1.0，0.1~5.0）。 */
    public static final String KEY_CUSTOM_SENSITIVITY = "crown_volume_custom_sensitivity";

    private CrownVolumePrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        if (context == null) return true;   // 默认开（保持原有行为）
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit();
    }

    /** 灵敏度 1~3，默认 2。 */
    public static int getSensitivity(Context context) {
        if (context == null) return 2;
        return Math.max(1, Math.min(3,
                prefs(context).getInt(KEY_SENSITIVITY, 2)));
    }

    public static void setSensitivity(Context context, int level) {
        if (context == null) return;
        prefs(context).edit().putInt(KEY_SENSITIVITY,
                Math.max(1, Math.min(3, level))).commit();
    }

    /**
     * 2026-11-22：自定义灵敏度倍率（默认 1.0，范围 0.1~5.0）。
     * 直接乘在表冠增量上：1.0 = 标准，2.0 = 快一倍，0.5 = 慢一半。
     */
    public static float getCustomSensitivity(Context context) {
        if (context == null) return 1.0f;
        return Math.max(0.1f, Math.min(5.0f,
                prefs(context).getFloat(KEY_CUSTOM_SENSITIVITY, 1.0f)));
    }

    public static void setCustomSensitivity(Context context, float multiplier) {
        if (context == null) return;
        prefs(context).edit().putFloat(KEY_CUSTOM_SENSITIVITY,
                Math.max(0.1f, Math.min(5.0f, multiplier))).commit();
    }

    /** 表冠可调到的最大音量百分比（10~100），默认 100。 */
    public static int getMaxPercent(Context context) {
        if (context == null) return 100;
        return Math.max(10, Math.min(100,
                prefs(context).getInt(KEY_MAX_PERCENT, 100)));
    }

    public static void setMaxPercent(Context context, int percent) {
        if (context == null) return;
        prefs(context).edit().putInt(KEY_MAX_PERCENT,
                Math.max(10, Math.min(100, percent))).commit();
    }
}
