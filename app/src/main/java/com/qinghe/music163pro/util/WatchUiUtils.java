package com.qinghe.music163pro.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * Shared UI helpers for watch-sized screens.
 */
public final class WatchUiUtils {

    public static final String SETTINGS_PREFERENCES = "music163_settings";
    public static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final float DESIGN_WIDTH_PX = 320f;

    private WatchUiUtils() {
    }

    public static void applyKeepScreenOnPreference(Activity activity) {
        if (activity == null) {
            return;
        }
        SharedPreferences preferences = activity.getSharedPreferences(
                SETTINGS_PREFERENCES, Context.MODE_PRIVATE);
        boolean keepScreenOn = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false);
        if (keepScreenOn) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    public static int px(Context context, int baseValue) {
        if (context == null || baseValue == 0) {
            return baseValue;
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        if (screenWidth <= 0) {
            screenWidth = (int) DESIGN_WIDTH_PX;
        }
        // 用户自定义缩放：XML 布局靠 densityDpi 缩放，代码里拼的界面必须在这里
        // 乘同一个系数，否则会出现「文字变大了、间距没变」的错位。
        float scale = UiScale.getScale(context);
        return (int) (baseValue * screenWidth / DESIGN_WIDTH_PX * scale + 0.5f);
    }
}
