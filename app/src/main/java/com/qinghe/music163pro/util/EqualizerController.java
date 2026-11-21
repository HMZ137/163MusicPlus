package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;
import android.util.Log;

/**
 * 均衡器（原「响度平衡」的正名）—— 只影响播放输出，不改歌曲文件 / 缓存 / 下载文件。
 *
 * <p>实现走系统 {@link Equalizer}（AudioEffect），不自写 DSP：手表性能有限，
 * 系统音效在音频链路里跑，CPU 开销远低于 JVM 侧逐帧处理。</p>
 *
 * <p>对外只暴露「开关 + 预设 + 三段（低/中/高）增益」，实际段数由设备决定
 * （通常是 5 段），三段会映射到设备段上，换设备也能用。</p>
 */
public final class EqualizerController {

    private static final String TAG = "Equalizer";
    private static final String PREFS = "music163_settings";
    private static final String KEY_ENABLED = "eq_enabled";
    private static final String KEY_PRESET = "eq_preset";
    private static final String KEY_LOW = "eq_low";
    private static final String KEY_MID = "eq_mid";
    private static final String KEY_HIGH = "eq_high";

    /** 预设：名称 + 低/中/高 三段增益（单位 0.1dB；60 = +6.0dB，限制在 ±12dB 内）。 */
    public static final String[] PRESET_NAMES = {
            "平坦", "流行", "摇滚", "人声", "低音增强", "爵士", "电子", "古典", "自定义"
    };
    private static final short[][] PRESET_LEVELS = {
            {0, 0, 0},       // 平坦
            {20, -10, 15},   // 流行    +2.0 / -1.0 / +1.5 dB
            {45, -15, 30},   // 摇滚    +4.5 / -1.5 / +3.0 dB
            {-15, 35, 10},   // 人声    -1.5 / +3.5 / +1.0 dB
            {60, 0, 10},     // 低音增强 +6.0 / 0 / +1.0 dB
            {30, 8, -20},    // 爵士    +3.0 / +0.8 / -2.0 dB（暖、高频收敛）
            {70, -20, 50},   // 电子    +7.0 / -2.0 / +5.0 dB（V 形）
            {18, 2, 38},     // 古典    +1.8 / +0.2 / +3.8 dB（保真、微提高频）
            {0, 0, 0}        // 自定义（用保存值）
    };
    public static final int PRESET_CUSTOM = PRESET_NAMES.length - 1;

    private static Equalizer sEqualizer;
    private static int sSession = -1;
    private static boolean sAttached = false;

    private EqualizerController() {
    }

    // ────────────────────────── 状态读写 ──────────────────────────

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return context != null && prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit();
        if (sAttached) {
            apply(context);
        }
    }

    public static int getPreset(Context context) {
        return context == null ? 0 : prefs(context).getInt(KEY_PRESET, 0);
    }

    public static void setPreset(Context context, int preset) {
        if (context == null) return;
        int safe = Math.max(0, Math.min(PRESET_NAMES.length - 1, preset));
        SharedPreferences.Editor e = prefs(context).edit().putInt(KEY_PRESET, safe);
        if (safe != PRESET_CUSTOM) {
            short[] lv = PRESET_LEVELS[safe];
            e.putInt(KEY_LOW, lv[0]).putInt(KEY_MID, lv[1]).putInt(KEY_HIGH, lv[2]);
        }
        e.commit();
        if (sAttached) {
            apply(context);
        }
    }

    /** 三段索引：0=低 1=中 2=高（读取时夹到 ±12dB，兼容历史越界值） */
    public static short getBand(Context context, int index) {
        if (context == null) return 0;
        String key = index == 0 ? KEY_LOW : index == 1 ? KEY_MID : KEY_HIGH;
        int raw = prefs(context).getInt(key, 0);
        return (short) Math.max(-120, Math.min(120, raw));
    }

    public static void setBand(Context context, int index, short level) {
        if (context == null || index < 0 || index > 2) return;
        String key = index == 0 ? KEY_LOW : index == 1 ? KEY_MID : KEY_HIGH;
        prefs(context).edit().putInt(key, level).putInt(KEY_PRESET, PRESET_CUSTOM).commit();
        if (sAttached) {
            apply(context);
        }
    }

    // ────────────────────────── 音效挂载 ──────────────────────────

    /**
     * 挂到指定 audio session。切换引擎 / 切歌 / 重建播放器后都要重新调用。
     * 内部会先释放旧实例，重复调用是安全的。
     */
    public static synchronized void attach(Context context, int audioSessionId) {
        if (context == null || audioSessionId == 0) return;
        if (sAttached && sSession == audioSessionId) {
            apply(context);
            return;
        }
        detach();
        try {
            sEqualizer = new Equalizer(0, audioSessionId);
            sSession = audioSessionId;
            sAttached = true;
            apply(context);
            Log.i(TAG, "均衡器已挂载 session=" + audioSessionId
                    + " bands=" + sEqualizer.getNumberOfBands());
        } catch (Throwable t) {
            Log.w(TAG, "均衡器挂载失败（保持原声）: " + t);
            detach();
        }
    }

    public static synchronized void detach() {
        if (sEqualizer != null) {
            try {
                sEqualizer.setEnabled(false);
            } catch (Throwable ignored) {
            }
            try {
                sEqualizer.release();
            } catch (Throwable ignored) {
            }
            sEqualizer = null;
        }
        sAttached = false;
        sSession = -1;
    }

    public static boolean isAttached() {
        return sAttached;
    }

    /** 设备实际段数（没挂上时返回 0）。 */
    public static int getDeviceBandCount() {
        try {
            return sEqualizer == null ? 0 : sEqualizer.getNumberOfBands();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 把保存的三段增益写进系统 EQ。 */
    private static void apply(Context context) {
        if (sEqualizer == null) return;
        try {
            boolean on = isEnabled(context);
            int bands = sEqualizer.getNumberOfBands();
            if (on && bands > 0) {
                short[] range = sEqualizer.getBandLevelRange();   // [min, max]，单位 mB
                short lo = range[0], hi = range[1];
                // 三段 → 设备段：低=0、中=中间、高=最后一段
                int[] map = {0, bands / 2, bands - 1};
                short[] levels = new short[]{
                        getBand(context, 0), getBand(context, 1), getBand(context, 2)
                };
                for (int i = 0; i < 3; i++) {
                    // 先夹到 ±12dB，再夹到设备范围：防止历史遗留的越界值（如 +60dB）被真的应用
                    int clamped = Math.max(-120, Math.min(120, levels[i]));
                    short lv = (short) Math.max(lo, Math.min(hi, clamped));
                    try {
                        sEqualizer.setBandLevel((short) map[i], lv);
                    } catch (Throwable ignored) {
                    }
                }
            }
            sEqualizer.setEnabled(on);
            Log.i(TAG, "均衡器 " + (on ? "已启用" : "已关闭")
                    + " preset=" + getPreset(context)
                    + " low=" + getBand(context, 0)
                    + " mid=" + getBand(context, 1)
                    + " high=" + getBand(context, 2));
        } catch (Throwable t) {
            Log.w(TAG, "应用均衡器参数失败: " + t);
        }
    }

    /** 给 UI 用：某一段在设备上支持的增益范围（mB）。 */
    public static short[] levelRange() {
        try {
            if (sEqualizer != null) return sEqualizer.getBandLevelRange();
        } catch (Throwable ignored) {
        }
        return new short[]{-1500, 1500};
    }
}
