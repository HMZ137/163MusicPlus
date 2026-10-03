package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

/**
 * 语言学习模式（AI 歌词）的本地设置与用量统计。
 *
 * <h3>Key 的存法</h3>
 * <p>按约定用「混淆」而非明文：XOR 掩码 + Base64。这不是加密（能还原），只是避免 Key
 * 以肉眼可读的形式出现在 prefs 文件/备份里。</p>
 *
 * <h3>统计</h3>
 * <p>累计请求次数与 token 用量，供设置页展示；缓存条目里也各记一份。</p>
 */
public final class AiLyricSettings {

    private static final String PREFS = "music163_settings";

    private static final String KEY_ENABLED = "ai_lyric_enabled";
    private static final String KEY_API_KEY = "ai_lyric_api_key_obf";
    private static final String KEY_MODEL = "ai_lyric_model";
    private static final String KEY_TOTAL_REQUESTS = "ai_lyric_total_requests";
    private static final String KEY_TOTAL_TOKENS = "ai_lyric_total_tokens";

    /** 默认模型（用户可在设置页自行改成别的模型名）。 */
    public static final String DEFAULT_MODEL = "deepseek-flash";

    private static final String XOR_MASK = "163MusicPro#aiLyric#v1";

    private AiLyricSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ==================== 开关 ====================

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    // ==================== API Key（混淆存储） ====================

    public static String getApiKey(Context context) {
        return deobfuscate(prefs(context).getString(KEY_API_KEY, ""));
    }

    public static void setApiKey(Context context, String rawKey) {
        prefs(context).edit().putString(KEY_API_KEY, obfuscate(rawKey)).apply();
    }

    public static boolean hasApiKey(Context context) {
        String key = getApiKey(context);
        return key != null && !key.trim().isEmpty();
    }

    /** 设置页展示用的掩码形式：只留头尾。 */
    public static String maskedApiKey(Context context) {
        String key = getApiKey(context);
        if (key == null || key.isEmpty()) {
            return "未填写";
        }
        if (key.length() <= 10) {
            return "****";
        }
        return key.substring(0, 6) + "…" + key.substring(key.length() - 4);
    }

    static String obfuscate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        byte[] data = raw.getBytes(StandardCharsets.UTF_8);
        byte[] mask = XOR_MASK.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (data[i] ^ mask[i % mask.length]);
        }
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    static String deobfuscate(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        try {
            byte[] data = Base64.decode(stored, Base64.NO_WRAP);
            byte[] mask = XOR_MASK.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (data[i] ^ mask[i % mask.length]);
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 模型 ====================

    public static String getModel(Context context) {
        String model = prefs(context).getString(KEY_MODEL, DEFAULT_MODEL);
        return (model == null || model.trim().isEmpty()) ? DEFAULT_MODEL : model.trim();
    }

    public static void setModel(Context context, String model) {
        String value = (model == null || model.trim().isEmpty()) ? DEFAULT_MODEL : model.trim();
        prefs(context).edit().putString(KEY_MODEL, value).apply();
    }

    // ==================== 用量统计 ====================

    public static int getTotalRequests(Context context) {
        return prefs(context).getInt(KEY_TOTAL_REQUESTS, 0);
    }

    public static long getTotalTokens(Context context) {
        return prefs(context).getLong(KEY_TOTAL_TOKENS, 0);
    }

    /** 每次 AI 调用完成后累加（含失败时服务端已计入的部分 token）。 */
    public static void addUsage(Context context, int tokens) {
        SharedPreferences p = prefs(context);
        p.edit()
                .putInt(KEY_TOTAL_REQUESTS, p.getInt(KEY_TOTAL_REQUESTS, 0) + 1)
                .putLong(KEY_TOTAL_TOKENS, p.getLong(KEY_TOTAL_TOKENS, 0) + Math.max(0, tokens))
                .apply();
    }

    public static void resetUsage(Context context) {
        prefs(context).edit()
                .putInt(KEY_TOTAL_REQUESTS, 0)
                .putLong(KEY_TOTAL_TOKENS, 0)
                .apply();
    }
}
