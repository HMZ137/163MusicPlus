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

    /**
     * 圆角屏的「安全内缩」（320px 屏上的基准像素值）。
     *
     * <p>手表屏幕四角是圆角，而**界面绘制区仍是矩形** —— 贴到四角的元素会被物理圆角切掉。
     * 项目原来没有任何这方面的约定，各页根布局 padding 普遍只有 6dp，远小于任何可能的
     * 圆角半径，于是「我的歌单」右上角那个 + 图标被切掉了大半。</p>
     *
     * <p><b>为什么只能估算：</b>拿不到真实的圆角半径。</p>
     * <ul>
     *   <li>本机是 Android 8.1（API 27）。{@code WindowInsets.getRoundedCorner()} 要 API 30，
     *       {@code DisplayCutout} 要 API 28，都用不了。</li>
     *   <li>圆角是屏幕物理属性，不在 {@code Configuration} / {@code DisplayMetrics} 里。</li>
     * </ul>
     * <p>所以按 320px 宽圆角表的常见几何取一个**偏保守**的值：宁可多留一点白，
     * 也别让元素再被切。真机上若发现留白过多（或仍被切），只改这一个数即可。</p>
     */
    private static final int CORNER_INSET_BASE = 28;

    private WatchUiUtils() {
    }

    /**
     * 贴在屏幕四角的元素至少离屏边这么远，否则会被物理圆角切掉。
     *
     * <p>只在「同时贴近上/下边与左/右边」时才需要（如标题栏两端、底部按钮）。
     * 屏幕正中的内容不受影响，不必无脑加。</p>
     */
    public static int cornerInset(Context context) {
        return px(context, CORNER_INSET_BASE);
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
