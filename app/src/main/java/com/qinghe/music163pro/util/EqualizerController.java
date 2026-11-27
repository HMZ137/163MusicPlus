package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.util.Log;

import org.json.JSONArray;

/**
 * 均衡器 + 音效（2026-11-28 增强版，方案 B）。
 *
 * <p>实现仍然走系统 {@code audiofx}（手表 CPU 有限，链路内音效比 JVM 侧逐帧处理省得多）：</p>
 * <ul>
 *   <li><b>均衡器</b>：{@link Equalizer}。段数<b>跟随设备</b>（通常 5 段），每段可调 ±12dB；
 *       预设仍是 8 套内置 + 自定义，内置预设的 3 个锚点（低/中/高）会按段数插值展开。</li>
 *   <li><b>低音增强</b>：{@link BassBoost}，EQ 抬不动的 60Hz 以下靠它补，强度 0~100%。</li>
 *   <li><b>响度增强</b>：{@link LoudnessEnhancer}（API 19+），整体响度/小音量下的动态，
 *       功率小的手表扬声器收益最大。</li>
 * </ul>
 *
 * <p>三个效果各自独立开关，可以同时开；某个 ROM 缺某个效果时只影响那一项，不影响其余。</p>
 */
public final class EqualizerController {

    private static final String TAG = "Equalizer";
    private static final String PREFS = "music163_settings";
    private static final String KEY_ENABLED = "eq_enabled";
    private static final String KEY_PRESET = "eq_preset";
    /** 各段增益（单位 0.1dB），JSON 数组；长度 = 段数。 */
    private static final String KEY_BANDS = "eq_band_levels";
    // 旧版 3 段键（仅用于迁移）
    private static final String KEY_LOW = "eq_low";
    private static final String KEY_MID = "eq_mid";
    private static final String KEY_HIGH = "eq_high";
    private static final String KEY_BASS_ON = "eq_bass_enabled";
    private static final String KEY_BASS_STRENGTH = "eq_bass_strength";
    private static final String KEY_LOUD_ON = "eq_loud_enabled";
    private static final String KEY_LOUD_GAIN = "eq_loud_gain_db";

    /** 未挂载到设备时的兜底段数（大多数 Android EQ 是 5 段）。 */
    private static final int FALLBACK_BANDS = 5;
    /** 单段增益上限 ±12dB（单位 0.1dB）。 */
    private static final int BAND_LIMIT = 120;

    /** 预设：名称 + 三个锚点（低/中/高，单位 0.1dB）。 */
    public static final String[] PRESET_NAMES = {
            "平坦", "流行", "摇滚", "人声", "低音增强", "爵士", "电子", "古典", "自定义"
    };
    private static final short[][] PRESET_ANCHORS = {
            {0, 0, 0},       // 平坦
            {20, -10, 15},   // 流行
            {45, -15, 30},   // 摇滚
            {-15, 35, 10},   // 人声
            {60, 0, 10},     // 低音增强
            {30, 8, -20},    // 爵士
            {70, -20, 50},   // 电子（V 形）
            {18, 2, 38},     // 古典
            {0, 0, 0}        // 自定义（用保存值）
    };
    public static final int PRESET_CUSTOM = PRESET_NAMES.length - 1;

    private static Equalizer sEqualizer;
    private static BassBoost sBassBoost;
    private static LoudnessEnhancer sLoudness;
    private static int sSession = -1;
    private static boolean sAttached = false;
    private static boolean sBassAvailable = false;
    private static boolean sLoudAvailable = false;

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

    /** 选预设：把三个锚点插值展开成当前段数并写盘。 */
    public static void setPreset(Context context, int preset) {
        if (context == null) return;
        int safe = Math.max(0, Math.min(PRESET_NAMES.length - 1, preset));
        SharedPreferences.Editor e = prefs(context).edit().putInt(KEY_PRESET, safe);
        if (safe != PRESET_CUSTOM) {
            short[] levels = expandAnchors(PRESET_ANCHORS[safe], getBandCount(context));
            e.putString(KEY_BANDS, toJson(levels));
        }
        e.commit();
        if (sAttached) {
            apply(context);
        }
    }

    /** 当前段数：设备报几段就用几段；没挂上时用保存的长度（再不行用 5）。 */
    public static int getBandCount(Context context) {
        try {
            if (sEqualizer != null) {
                int n = sEqualizer.getNumberOfBands();
                if (n > 0) {
                    return n;
                }
            }
        } catch (Throwable ignored) {
        }
        short[] saved = readSavedBands(context);
        return saved != null && saved.length > 0 ? saved.length : FALLBACK_BANDS;
    }

    /** 某段当前增益（单位 0.1dB）。 */
    public static short getBandLevel(Context context, int index) {
        short[] levels = currentLevels(context);
        if (index < 0 || index >= levels.length) {
            return 0;
        }
        return levels[index];
    }

    public static short[] getBandLevels(Context context) {
        return currentLevels(context).clone();
    }

    public static void setBandLevel(Context context, int index, short level) {
        if (context == null) return;
        short[] levels = currentLevels(context);
        if (index < 0 || index >= levels.length) {
            return;
        }
        levels[index] = (short) Math.max(-BAND_LIMIT, Math.min(BAND_LIMIT, level));
        prefs(context).edit()
                .putString(KEY_BANDS, toJson(levels))
                .putInt(KEY_PRESET, PRESET_CUSTOM)
                .commit();
        if (sAttached) {
            apply(context);
        }
    }

    /** 某段的中心频率（Hz），给 UI 显示；拿不到时按常见 5 段分布估算。 */
    public static int bandCenterHz(Context context, int index) {
        try {
            if (sEqualizer != null && index >= 0 && index < sEqualizer.getNumberOfBands()) {
                return sEqualizer.getCenterFreq((short) index) / 1000;   // mHz → Hz
            }
        } catch (Throwable ignored) {
        }
        double[] estimate = {60, 230, 910, 3600, 14000};
        int n = Math.max(1, getBandCount(context));
        if (index < 0 || index >= n) {
            return 0;
        }
        int pos = (int) Math.round(index * (estimate.length - 1.0) / Math.max(1, n - 1));
        return (int) estimate[Math.min(estimate.length - 1, Math.max(0, pos))];
    }

    private static short[] currentLevels(Context context) {
        int count = getBandCount(context);
        short[] saved = readSavedBands(context);
        if (saved == null) {
            // 首次升级：把旧的三段键映射成当前段数
            saved = migrateLegacy(context, count);
        }
        if (saved.length == count) {
            return saved;
        }
        // 段数变了（换设备 / 换 ROM）：重采样到新段数，尽量保持曲线形状
        return resample(saved, count);
    }

    private static short[] readSavedBands(Context context) {
        if (context == null) {
            return null;
        }
        String json = prefs(context).getString(KEY_BANDS, null);
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONArray arr = new JSONArray(json);
            short[] out = new short[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                out[i] = (short) Math.max(-BAND_LIMIT, Math.min(BAND_LIMIT, arr.optInt(i, 0)));
            }
            return out.length == 0 ? null : out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static short[] migrateLegacy(Context context, int count) {
        short[] three = {
                (short) prefs(context).getInt(KEY_LOW, 0),
                (short) prefs(context).getInt(KEY_MID, 0),
                (short) prefs(context).getInt(KEY_HIGH, 0)
        };
        return expandAnchors(three, count);
    }

    /** 三个锚点（低/中/高）线性插值展开成 count 段。 */
    private static short[] expandAnchors(short[] three, int count) {
        int n = Math.max(1, count);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0 : (double) i / (n - 1);      // 0..1
            double value;
            if (t <= 0.5) {
                value = three[0] + (three[1] - three[0]) * (t / 0.5);
            } else {
                value = three[1] + (three[2] - three[1]) * ((t - 0.5) / 0.5);
            }
            out[i] = (short) Math.round(value);
        }
        return out;
    }

    /** 把任意长度的曲线重采样成 count 段。 */
    private static short[] resample(short[] src, int count) {
        int n = Math.max(1, count);
        if (src == null || src.length == 0) {
            return new short[n];
        }
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double pos = n == 1 ? 0 : (double) i * (src.length - 1) / (n - 1);
            int lo = (int) Math.floor(pos);
            int hi = Math.min(src.length - 1, lo + 1);
            double frac = pos - lo;
            out[i] = (short) Math.round(src[lo] + (src[hi] - src[lo]) * frac);
        }
        return out;
    }

    private static String toJson(short[] levels) {
        JSONArray arr = new JSONArray();
        for (short v : levels) {
            arr.put(v);
        }
        return arr.toString();
    }

    // ────────────────────────── 音效（低音 / 响度） ──────────────────────────

    public static boolean isBassBoostEnabled(Context context) {
        return context != null && prefs(context).getBoolean(KEY_BASS_ON, false);
    }

    public static void setBassBoostEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_BASS_ON, enabled).commit();
        if (sAttached) {
            apply(context);
        }
    }

    /** 低音强度 0~100（%）。 */
    public static int getBassBoostStrength(Context context) {
        return context == null ? 60 : prefs(context).getInt(KEY_BASS_STRENGTH, 60);
    }

    public static void setBassBoostStrength(Context context, int percent) {
        if (context == null) return;
        prefs(context).edit()
                .putInt(KEY_BASS_STRENGTH, Math.max(0, Math.min(100, percent))).commit();
        if (sAttached) {
            apply(context);
        }
    }

    public static boolean isLoudnessEnabled(Context context) {
        return context != null && prefs(context).getBoolean(KEY_LOUD_ON, false);
    }

    public static void setLoudnessEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_LOUD_ON, enabled).commit();
        if (sAttached) {
            apply(context);
        }
    }

    /** 响度增益 0~10 dB。 */
    public static int getLoudnessGainDb(Context context) {
        return context == null ? 3 : prefs(context).getInt(KEY_LOUD_GAIN, 3);
    }

    public static void setLoudnessGainDb(Context context, int db) {
        if (context == null) return;
        prefs(context).edit().putInt(KEY_LOUD_GAIN, Math.max(0, Math.min(10, db))).commit();
        if (sAttached) {
            apply(context);
        }
    }

    public static boolean isBassBoostAvailable() {
        return sBassAvailable;
    }

    public static boolean isLoudnessAvailable() {
        return sLoudAvailable;
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
            Log.i(TAG, "均衡器已挂载 session=" + audioSessionId
                    + " bands=" + sEqualizer.getNumberOfBands());
        } catch (Throwable t) {
            Log.w(TAG, "均衡器挂载失败（保持原声）: " + t);
            detach();
            return;
        }
        // 音效各自独立 try：某个 ROM 缺某个效果时，其它效果与 EQ 照常工作
        try {
            sBassBoost = new BassBoost(0, audioSessionId);
            sBassAvailable = true;
        } catch (Throwable t) {
            sBassBoost = null;
            sBassAvailable = false;
            Log.w(TAG, "低音增强不可用: " + t);
        }
        try {
            sLoudness = new LoudnessEnhancer(audioSessionId);
            sLoudAvailable = true;
        } catch (Throwable t) {
            sLoudness = null;
            sLoudAvailable = false;
            Log.w(TAG, "响度增强不可用: " + t);
        }
        apply(context);
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
        if (sBassBoost != null) {
            try {
                sBassBoost.setEnabled(false);
                sBassBoost.release();
            } catch (Throwable ignored) {
            }
            sBassBoost = null;
        }
        if (sLoudness != null) {
            try {
                sLoudness.setEnabled(false);
                sLoudness.release();
            } catch (Throwable ignored) {
            }
            sLoudness = null;
        }
        sBassAvailable = false;
        sLoudAvailable = false;
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

    /** 给 UI 用：单段可调范围（mB，已按设备范围与本工程的 ±12dB 取交集）。 */
    public static short[] levelRange() {
        short lo = -BAND_LIMIT;
        short hi = BAND_LIMIT;
        try {
            if (sEqualizer != null) {
                short[] device = sEqualizer.getBandLevelRange();
                lo = (short) Math.max(device[0], -BAND_LIMIT);
                hi = (short) Math.min(device[1], BAND_LIMIT);
            }
        } catch (Throwable ignored) {
        }
        return new short[]{lo, hi};
    }

    /** 把保存的段增益与两项音效写进系统效果链。 */
    private static void apply(Context context) {
        if (sEqualizer != null) {
            try {
                boolean on = isEnabled(context);
                int bands = sEqualizer.getNumberOfBands();
                if (on && bands > 0) {
                    short[] range = sEqualizer.getBandLevelRange();
                    short lo = range[0], hi = range[1];
                    short[] levels = currentLevels(context);
                    for (int i = 0; i < bands; i++) {
                        int raw = i < levels.length ? levels[i] : 0;
                        int clamped = Math.max(-BAND_LIMIT, Math.min(BAND_LIMIT, raw));
                        short lv = (short) Math.max(lo, Math.min(hi, clamped));
                        try {
                            sEqualizer.setBandLevel((short) i, lv);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                sEqualizer.setEnabled(on);
            } catch (Throwable t) {
                Log.w(TAG, "应用均衡器参数失败: " + t);
            }
        }
        applyBassBoost(context);
        applyLoudness(context);
    }

    private static void applyBassBoost(Context context) {
        if (sBassBoost == null) {
            return;
        }
        try {
            boolean on = isBassBoostEnabled(context);
            if (on) {
                int percent = getBassBoostStrength(context);
                // BassBoost 的强度是 0~1000（1000 = 最大），这里存的是百分比
                sBassBoost.setStrength((short) Math.max(0, Math.min(1000, percent * 10)));
            }
            sBassBoost.setEnabled(on);
        } catch (Throwable t) {
            Log.w(TAG, "应用低音增强失败: " + t);
        }
    }

    private static void applyLoudness(Context context) {
        if (sLoudness == null) {
            return;
        }
        try {
            boolean on = isLoudnessEnabled(context);
            if (on) {
                // 目标增益单位 mB：dB × 100
                sLoudness.setTargetGain(getLoudnessGainDb(context) * 100);
            } else {
                sLoudness.setTargetGain(0);
            }
            sLoudness.setEnabled(on);
        } catch (Throwable t) {
            Log.w(TAG, "应用响度增强失败: " + t);
        }
    }
}
