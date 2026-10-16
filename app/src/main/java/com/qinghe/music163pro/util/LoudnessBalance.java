package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Map;

/**
 * F5 限音器：**总开关**与「按曲目取增益」的门面。
 *
 * <p>文档 F5 改动要点 5：必须提供开关，允许用户关闭；<b>关闭后行为完全恢复原状</b>
 * —— 所以这里默认**关闭**（opt-in），关闭时 {@link #gainDbFor} 恒返回 0，播放路径不做任何事。</p>
 *
 * <p>索引只在内存里缓存一份（换歌时按曲目查表，不能每次读盘）。分析完成后由
 * {@link #invalidateCache()} 让下次查表重新加载。</p>
 */
public final class LoudnessBalance {

    private static final String TAG = "Loudness";
    private static final String PREFS_NAME = "music163_settings";
    /** 开关键；默认 false（opt-in）。 */
    public static final String PREF_ENABLED = "loudness_balance_enabled";

    private static Map<String, LoudnessIndex.Entry> sIndex;

    private LoudnessBalance() {
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(PREF_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply();
        MusicLog.i(TAG, "响度平衡开关 = " + enabled);
    }

    /** 分析完成后调用：下次查表重新读盘。 */
    public static void invalidateCache() {
        sIndex = null;
    }

    /**
     * 该曲目应施加的增益（dB）。
     *
     * @return 0 表示「不施加」（未启用 / 无索引 / 无该条目 / 条目已失效）——
     *         调用方据此走完全不变的原路径。
     */
    public static float gainDbFor(Context context, String path) {
        if (!isEnabled(context) || path == null || path.isEmpty()) {
            return 0f;
        }
        Map<String, LoudnessIndex.Entry> index = index(context);
        LoudnessIndex.Entry e = index.get(path);
        if (e == null) {
            return 0f;
        }
        // 文件被换过/改过 ⇒ 索引已失效，宁可不加增益也不要按错的值去调音量
        File f = new File(path);
        if (f.isFile() && (e.size != f.length() || e.mtime != f.lastModified())) {
            return 0f;
        }
        return LoudnessIndex.gainDbFor(e);
    }

    private static Map<String, LoudnessIndex.Entry> index(Context context) {
        if (sIndex == null) {
            sIndex = LoudnessIndex.load(context);
            MusicLog.i(TAG, "载入响度索引 " + sIndex.size() + " 条");
        }
        return sIndex;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
