package com.qinghe.music163pro.util;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * P9 补充（需求方 2026-10-17）：「流体背景」的色团**从当前背景（专辑封面 / 选定图片）取色**。
 *
 * <p>算法与 {@link LyricsTint} 同源、但目标不同：LyricsTint 只要一个「主色」来决定歌词黑白，
 * 这里要一组**相互有区分度、且足够鲜艳**的颜色来画色团。步骤：</p>
 * <ol>
 *   <li>缩到 32×32（几百微秒级）；</li>
 *   <li>量化成 4×4×4 = 64 个颜色桶，累计权重与各通道均值；</li>
 *   <li>按权重从大到小取桶，跳过与已选色太接近的（色相/亮度差异小），凑够 count 个；</li>
 *   <li>统一做 HSV 提升（饱和度 ≥ 0.55、明度 ≥ 0.65）—— 色团是在深底上 SCREEN 叠加的，
 *       原图偏暗的颜色直接画会「看不见」；</li>
 *   <li>不足 count 个时，用已选色派生更亮/更暗的变体补齐（保证流体始终是 5 个色团）。</li>
 * </ol>
 *
 * <p>取色失败（空图/全黑）返回空数组，调用方回退到固定配色。</p>
 */
public final class PaletteExtractor {

    private static final int SAMPLE = 32;
    private static final int BUCKET_BITS = 4;      // 4x4x4
    private static final int BUCKET_LEVELS = 1 << BUCKET_BITS;

    private static final float MIN_SATURATION = 0.55f;
    private static final float MIN_VALUE = 0.65f;
    /** 两个候选色的最小差异（色相 0..1 环绕距离 + 明度差）。 */
    private static final float MIN_HUE_DISTANCE = 0.09f;

    private PaletteExtractor() {
    }

    /** 从位图提取最多 count 个鲜艳色；位图为空时返回空数组。 */
    public static int[] extract(Bitmap src, int count) {
        if (src == null || src.isRecycled() || count <= 0) {
            return new int[0];
        }
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) {
            return new int[0];
        }
        Bitmap sample = Bitmap.createScaledBitmap(src, SAMPLE, SAMPLE, false);
        int[] pixels = new int[SAMPLE * SAMPLE];
        sample.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
        if (sample != src) {
            sample.recycle();
        }

        int buckets = BUCKET_LEVELS * BUCKET_LEVELS * BUCKET_LEVELS;
        long[] weights = new long[buckets];
        long[] sumR = new long[buckets];
        long[] sumG = new long[buckets];
        long[] sumB = new long[buckets];
        int shift = 8 - BUCKET_BITS;
        for (int p : pixels) {
            if ((p >>> 24) < 0x40) {
                continue;   // 近全透明像素不计
            }
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int br = r >> shift;
            int bg = g >> shift;
            int bb = b >> shift;
            int idx = (br << (BUCKET_BITS * 2)) | (bg << BUCKET_BITS) | bb;
            weights[idx]++;
            sumR[idx] += r;
            sumG[idx] += g;
            sumB[idx] += b;
        }

        // 按权重排序桶下标（64 个，直接插入排序即可）
        Integer[] order = new Integer[buckets];
        for (int i = 0; i < buckets; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Long.compare(weights[b], weights[a]));

        int[] picked = new int[count];
        float[] pickedHue = new float[count];
        float[] pickedVal = new float[count];
        int pickedCount = 0;
        for (Integer idx : order) {
            if (weights[idx] == 0) {
                break;
            }
            int r = (int) (sumR[idx] / weights[idx]);
            int g = (int) (sumG[idx] / weights[idx]);
            int b = (int) (sumB[idx] / weights[idx]);
            int boosted = boostVividness(r, g, b);
            float[] hsv = new float[3];
            Color.colorToHSV(boosted, hsv);
            boolean duplicate = false;
            for (int i = 0; i < pickedCount; i++) {
                float dh = Math.abs(hsv[0] - pickedHue[i]);
                dh = Math.min(dh, 1f - dh);
                if (dh < MIN_HUE_DISTANCE && Math.abs(hsv[2] - pickedVal[i]) < 0.18f) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue;
            }
            picked[pickedCount] = boosted;
            pickedHue[pickedCount] = hsv[0];
            pickedVal[pickedCount] = hsv[2];
            pickedCount++;
            if (pickedCount >= count) {
                break;
            }
        }

        if (pickedCount == 0) {
            return new int[0];
        }
        // 不足 count 个：用已选色派生变体补齐
        int[] result = new int[count];
        int out = 0;
        while (out < count) {
            int base = picked[out % pickedCount];
            int cycle = out / pickedCount;
            result[out] = cycle == 0 ? base : shiftColor(base, cycle);
            out++;
        }
        return result;
    }

    /** HSV 提升：饱和度、明度都抬到下限之上，保证在深底上发光。 */
    private static int boostVividness(int r, int g, int b) {
        float[] hsv = new float[3];
        Color.colorToHSV(Color.rgb(r, g, b), hsv);
        hsv[1] = Math.max(hsv[1], MIN_SATURATION);
        hsv[2] = Math.max(hsv[2], MIN_VALUE);
        return Color.HSVToColor(hsv);
    }

    /** 派生变体：奇数轮色相偏移、偶数轮提亮，让补齐的色团与原色有明显但不突兀的区别。 */
    private static int shiftColor(int color, int cycle) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (cycle % 2 == 0) {
            hsv[2] = Math.min(1f, hsv[2] + 0.18f);
        } else {
            hsv[0] = (hsv[0] + 0.08f) % 1f;
            hsv[2] = Math.max(0.5f, hsv[2] - 0.12f);
        }
        return Color.HSVToColor(hsv);
    }
}
