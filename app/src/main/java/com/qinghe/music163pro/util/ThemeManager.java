package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.qinghe.music163pro.R;

/**
 * 主题管理（P1-10）：内置多套主题 + 独立持久化。
 *
 * <p>范围按需求方 2026-11-20 的口径：<b>只做内置主题与主色，不做预览卡片、不做深浅色切换</b>。
 * 每套主题只覆盖强调色相关角色（colorPrimary / colorAccent / colorSecondary 等），
 * 背景与文字色保持不变，避免大面积翻车。</p>
 *
 * <p>持久化 key 是固定字面量（{@code app_theme_index}），不依赖任何运行期索引；
 * 写入用 {@code commit()} 同步落盘，防止快速杀进程丢失。</p>
 */
public final class ThemeManager {

    private static final String PREFS = "music163_settings";
    private static final String KEY_INDEX = "app_theme_index";
    /** 自定义主色（0 表示未启用）。需求方 2026-11-21：支持输入 hex 自定义。 */
    private static final String KEY_CUSTOM = "app_theme_custom_color";

    /** 主题名（下标与 STYLES / COLORS 对齐）。 */
    public static final String[] NAMES = {
            "经典紫", "初音绿", "Deepseek蓝", "必刷题绿", "暖橙", "B站粉"
    };

    /** 每套主题的强调色（给自绘控件取色用）。 */
    public static final int[] COLORS = {
            0xFFBB86FC,   // 经典紫
            0xFF39C5BB,   // 初音绿
            0xFF5686FE,   // Deepseek蓝（原星蓝，色值不变）
            0xFF93D50A,   // 必刷题绿（需求方取色 RGB 147,213,10）
            0xFFFF9F43,   // 暖橙
            0xFFFE65A6    // B站粉（原樱花粉，需求方指定 fe65a6）
    };

    private static final int[] STYLES = {
            R.style.AppTheme,
            R.style.Theme_App_Green,
            R.style.Theme_App_StarBlue,
            R.style.Theme_App_ExamYellow,
            R.style.Theme_App_Orange,
            R.style.Theme_App_Pink
    };

    private ThemeManager() {
    }

    public static int getIndex(Context context) {
        if (context == null) return 0;
        return prefs(context).getInt(KEY_INDEX, 0);
    }

    public static void setIndex(Context context, int index) {
        if (context == null) return;
        int safe = Math.max(0, Math.min(NAMES.length - 1, index));
        // 选预设主题时清掉自定义色，避免「选了预设但颜色还是自定义」的错乱
        prefs(context).edit().putInt(KEY_INDEX, safe).remove(KEY_CUSTOM).commit();
    }

    /** 自定义主色；未启用返回 0。 */
    public static int getCustomColor(Context context) {
        if (context == null) return 0;
        return prefs(context).getInt(KEY_CUSTOM, 0);
    }

    /** 启用自定义主色（0 表示清除自定义）。 */
    public static void setCustomColor(Context context, int color) {
        if (context == null) return;
        SharedPreferences.Editor e = prefs(context).edit();
        if (color == 0) {
            e.remove(KEY_CUSTOM);
        } else {
            e.putInt(KEY_CUSTOM, color | 0xFF000000);   // 强制不透明
        }
        e.commit();
    }

    public static boolean isCustom(Context context) {
        return getCustomColor(context) != 0;
    }

    /**
     * 当前主题对应的 style 资源（给 setTheme 用）。
     *
     * <p>自定义色是运行时的，Android 没法运行时造一个 theme；这里挑**色相最接近的预设**
     * 作为 XML 侧（进度条 / 菜单 tint / drawable）的兜底，代码侧再由 {@link #accentColor}
     * 精确覆盖成用户输入的颜色。</p>
     */
    public static int styleRes(Context context) {
        int custom = getCustomColor(context);
        if (custom != 0) {
            return STYLES[nearestPreset(custom)];
        }
        int i = getIndex(context);
        return STYLES[Math.max(0, Math.min(STYLES.length - 1, i))];
    }

    /** 当前主题的强调色（自绘控件用；等价于主题里的 colorPrimary）。 */
    public static int accentColor(Context context) {
        int custom = getCustomColor(context);
        if (custom != 0) {
            return custom;
        }
        int i = getIndex(context);
        return COLORS[Math.max(0, Math.min(COLORS.length - 1, i))];
    }

    public static String name(Context context) {
        int custom = getCustomColor(context);
        if (custom != 0) {
            return String.format(java.util.Locale.ROOT, "自定义 #%06X", custom & 0xFFFFFF);
        }
        int i = getIndex(context);
        return NAMES[Math.max(0, Math.min(NAMES.length - 1, i))];
    }

    /** 按 RGB 距离找最接近的预设下标。 */
    private static int nearestPreset(int color) {
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < COLORS.length; i++) {
            int dr = ((color >> 16) & 0xFF) - ((COLORS[i] >> 16) & 0xFF);
            int dg = ((color >> 8) & 0xFF) - ((COLORS[i] >> 8) & 0xFF);
            int db = (color & 0xFF) - (COLORS[i] & 0xFF);
            long d = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * 把当前主题的强调色同步到各「运行时颜色常量」，页面创建时调用一次。
     *
     * <p>这些常量原本是 {@code static final}（会被编译器内联到引用处，改主题不生效），
     * P1-10 已改为可变字段，统一从这里刷新；XML 里的颜色由 {@code setTheme} 走主题属性。</p>
     */
    public static void applyTo(Context context) {
        int accent = accentColor(context);
        UiKit.COLOR_ACCENT = accent;
        UiKit.COLOR_TAB_ACTIVE = accent;
        VolumeCapsuleDrawable.FILL_COLOR = accent;
        MultiSelectController.SELECTED_TINT = withAlpha(accent, 0x33);
        MultiSelectController.CHECK_ACTIVE_COLOR = accent;
        LyricsCopyController.SELECTED_FILL = withAlpha(accent, 0x55);
        LyricsCopyController.SELECTED_STROKE = accent;
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
