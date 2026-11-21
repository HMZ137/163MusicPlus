package com.qinghe.music163pro.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 局域网传歌网页背景（需求 2026-11-21 任务 1）：独立于现有背景设置的存储。
 *
 * <p>键前缀 {@code lan_page_*}，与播放页背景 / 封面完全不共用。存一张图 +
 * 模糊档位（0=关 1=弱 2=中 3=强），服务端把图片通过 {@code /api/lan-bg} 下发给网页。</p>
 */
public final class LanPageBackground {

    private static final String PREFS = "lan_page_settings";
    private static final String KEY_BLUR = "lan_page_blur";
    private static final String FILE_NAME = "lan_page_bg.jpg";
    private static volatile Bitmap sCache;

    private LanPageBackground() {
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static boolean has(Context context) {
        return context != null && file(context).exists() && file(context).length() > 0;
    }

    public static int getBlurLevel(Context context) {
        if (context == null) return 2;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_BLUR, 2);
    }

    public static void setBlurLevel(Context context, int level) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_BLUR, Math.max(0, Math.min(3, level))).commit();
        sCache = null;   // 模糊档位变了，缓存失效
    }

    /** 保存图片（长边上限 1600px：网页全屏背景用，720 太小了，模糊=无时会糊）。 */
    public static void save(Context context, android.net.Uri uri) {
        if (context == null || uri == null) return;
        try {
            Bitmap raw = BitmapFactory.decodeStream(
                    context.getContentResolver().openInputStream(uri));
            if (raw == null) return;
            int w = raw.getWidth(), h = raw.getHeight();
            int target = 1600;
            Bitmap scaled = w > target
                    ? Bitmap.createScaledBitmap(raw, target, h * target / w, true)
                    : raw;
            FileOutputStream fos = new FileOutputStream(file(context));
            scaled.compress(Bitmap.CompressFormat.JPEG, 86, fos);
            fos.flush();
            fos.close();
            sCache = null;
        } catch (Throwable t) {
            MusicLog.w("LanPageBg", "保存网页背景失败: " + t);
        }
    }

    public static void clear(Context context) {
        if (context == null) return;
        file(context).delete();
        sCache = null;
    }

    /** 按模糊档位取处理过的位图（给服务端直接输出给网页）。未设置返回 null。 */
    public static Bitmap load(Context context) {
        if (context == null) return null;
        File f = file(context);
        if (!f.exists()) return null;
        try {
            Bitmap raw = BitmapFactory.decodeFile(f.getAbsolutePath());
            int blur = getBlurLevel(context);
            if (blur <= 0 || raw == null) return raw;
            return blur(raw, blur);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 高斯观感模糊 = 两轮 box blur（滑动窗口均值，纯 Java 无依赖）。
     * 需求 2026-11-21：降采样放大会出「方块感」，换成真正的模糊算法。
     * 半径按档位：弱 3 / 中 6 / 强 12；先缩到 ≤720px 再模糊（控制耗时）。
     */
    static Bitmap blur(Bitmap raw, int level) {
        if (raw == null || level <= 0) return raw;
        int radius = level == 1 ? 3 : level == 2 ? 6 : 12;
        // 模糊前只做适度缩放：之前缩到 720 会让全屏背景明显发糊（需求方 2026-11-21 反馈）
        int maxDim = 1400;
        Bitmap src = raw;
        if (raw.getWidth() > maxDim) {
            int h = Math.max(1, raw.getHeight() * maxDim / raw.getWidth());
            src = Bitmap.createScaledBitmap(raw, maxDim, h, true);
        }
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        boxBlurPass(out, radius);
        boxBlurPass(out, radius);
        return out;
    }

    /** 对 ARGB_8888 位图做一次「水平 + 垂直」的 box blur（滑动窗口均值，O(n)）。 */
    private static void boxBlurPass(Bitmap bmp, int radius) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0 || radius < 1) return;
        int[] pix = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        int div = radius * 2 + 1;
        // 横向
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = pix[row + clamp(i, 0, w - 1)];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = 0xFF000000 | (sr / div << 16) | (sg / div << 8) | (sb / div);
                int pAdd = pix[row + clamp(x + radius + 1, 0, w - 1)];
                int pSub = pix[row + clamp(x - radius, 0, w - 1)];
                sr += ((pAdd >> 16) & 0xFF) - ((pSub >> 16) & 0xFF);
                sg += ((pAdd >> 8) & 0xFF) - ((pSub >> 8) & 0xFF);
                sb += (pAdd & 0xFF) - (pSub & 0xFF);
            }
        }
        // 纵向
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = tmp[clamp(i, 0, h - 1) * w + x];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                pix[y * w + x] = 0xFF000000 | (sr / div << 16) | (sg / div << 8) | (sb / div);
                int pAdd = tmp[clamp(y + radius + 1, 0, h - 1) * w + x];
                int pSub = tmp[clamp(y - radius, 0, h - 1) * w + x];
                sr += ((pAdd >> 16) & 0xFF) - ((pSub >> 16) & 0xFF);
                sg += ((pAdd >> 8) & 0xFF) - ((pSub >> 8) & 0xFF);
                sb += (pAdd & 0xFF) - (pSub & 0xFF);
            }
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
