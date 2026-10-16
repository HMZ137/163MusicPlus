package com.qinghe.music163pro.util;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * F2：按背景主色决定歌词颜色，保证歌词在任何背景上都清晰可读。
 *
 * <p><b>为什么缓存：</b>背景只在换歌/换背景时变，所以结果**缓存**，只在背景位图变化时重算 ——
 * 绝不在每帧或每次滚动时计算（文档 F2 改动要点 3）。</p>
 *
 * <p><b>算法（不引入 androidx.palette，省一个依赖）：</b></p>
 * <ol>
 *   <li>把背景缩成 ~24×24 的低分辨率副本（几十微秒级，不卡 UI）；</li>
 *   <li>量化成 4×4×4 的颜色直方图，取<b>出现最多的桶</b>的平均色作为主色；</li>
 *   <li>用 <b>WCAG 相对亮度</b>（不是简单的通道平均）作为判据 —— 人眼对绿最敏感、对蓝最不敏感；</li>
 *   <li>在「近黑歌词」与「近白歌词」两套方案里<b>选对比度更高的那一套</b>，并把两个对比度都写进日志，
 *       便于核验（WCAG AA 对正文要求 ≥ 4.5）。</li>
 * </ol>
 *
 * <p><b>与既有语义的关系：</b>只提供「底色」用词颜色；AI 词块着色（{@code LyricBlockSpan} 的
 * fill/stroke）与罗马音用的强调色**不接管**，以免破坏既有语义（文档 F2 改动要点 4）。</p>
 */
public final class LyricsTint {

    private static final String TAG = "LyricsTint";

    /** 暗背景下的歌词基色：与改造前的观感一致（白），避免"改了个功能反而变丑"。 */
    private static final int LIGHT_BASE = 0xFFFFFFFF;
    /** 亮背景下的歌词基色：近黑（纯黑在亮背景上更硬，这里留一点柔度）。 */
    private static final int DARK_BASE = 0xFF14141A;
    /** 无背景/默认背景时的主色（与 {@code BackgroundUtil.DEFAULT_BG_COLOR} 同族）。 */
    private static final int DEFAULT_DOMINANT = 0xFF121212;

    private static int sBase = LIGHT_BASE;
    private static int sDominant = DEFAULT_DOMINANT;
    /** 已算过的背景标识（同一个 Bitmap 对象即同一个标识；重新解码会得到新对象 ⇒ 自动重算）。 */
    private static String sKey = null;

    private LyricsTint() {
    }

    /** 当前歌词基色（不含透明度）。 */
    public static int base() {
        return sBase;
    }

    /**
     * 当前歌词颜色，带上指定的透明度。
     *
     * @param alpha 0~255；改造前的调用点写的是 {@code 0xB3FFFFFF} 这类字面量，
     *              改成 {@code LyricsTint.textColor(0xB3)} 即可保持同样的透明度语义。
     */
    public static int textColor(int alpha) {
        return (sBase & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** 默认/无背景时的固定取值（深色页面 ⇒ 白字）。 */
    public static void useDefault() {
        if (sKey != null && "default".equals(sKey)) {
            return;
        }
        sBase = LIGHT_BASE;
        sDominant = DEFAULT_DOMINANT;
        sKey = "default";
        MusicLog.i(TAG, "背景=默认深色 → 歌词基色=#" + hex(sBase) + "（对比度 "
                + round(contrastRatio(sBase, sDominant)) + "）");
    }

    /** 背景位图变化时调用；同一个位图对象重复调用会直接返回（缓存）。 */
    public static void refresh(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            useDefault();
            return;
        }
        String key = bitmap.getWidth() + "x" + bitmap.getHeight() + "@" + System.identityHashCode(bitmap);
        if (key.equals(sKey)) {
            return;   // 缓存的全部意义在这里：没变就不算
        }
        int dominant = dominantColor(bitmap);
        int dark = contrastRatio(DARK_BASE, dominant) >= contrastRatio(LIGHT_BASE, dominant)
                ? DARK_BASE : LIGHT_BASE;
        sBase = dark;
        sDominant = dominant;
        sKey = key;
        MusicLog.i(TAG, "背景主色=#" + hex(dominant)
                + " 相对亮度=" + round(relativeLuminance(dominant))
                + " → 歌词基色=#" + hex(sBase)
                + "（选中对比度 " + round(contrastRatio(sBase, dominant))
                + " / 另一套 " + round(contrastRatio(sBase == DARK_BASE ? LIGHT_BASE : DARK_BASE, dominant)) + "）");
    }

    /** 低分辨率直方图取主色：把图缩到 24×24 后按 4×4×4 量化，取最大桶的平均色。 */
    private static int dominantColor(Bitmap src) {
        Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(src, 24, 24, false);
        } catch (Throwable t) {
            return DEFAULT_DOMINANT;   // 缩放出问题也不能影响界面
        }
        if (small == null) {
            return DEFAULT_DOMINANT;
        }
        int[] sumsR = new int[64];
        int[] sumsG = new int[64];
        int[] sumsB = new int[64];
        int[] counts = new int[64];
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int c = small.getPixel(x, y);
                int r = Color.red(c);
                int g = Color.green(c);
                int b = Color.blue(c);
                int bucket = ((r >> 6) << 4) | ((g >> 6) << 2) | (b >> 6);
                sumsR[bucket] += r;
                sumsG[bucket] += g;
                sumsB[bucket] += b;
                counts[bucket]++;
            }
        }
        if (small != src) {
            small.recycle();
        }
        int best = 0;
        for (int i = 1; i < 64; i++) {
            if (counts[i] > counts[best]) {
                best = i;
            }
        }
        if (counts[best] == 0) {
            return DEFAULT_DOMINANT;
        }
        return Color.rgb(sumsR[best] / counts[best], sumsG[best] / counts[best], sumsB[best] / counts[best]);
    }

    /** WCAG 相对亮度（0=黑，1=白）。 */
    static double relativeLuminance(int color) {
        return 0.2126 * channel(Color.red(color))
                + 0.7152 * channel(Color.green(color))
                + 0.0722 * channel(Color.blue(color));
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** WCAG 对比度，范围 1~21（越大越清晰；AA 正文要求 ≥ 4.5）。 */
    static double contrastRatio(int a, int b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static String hex(int color) {
        return String.format("%06X", color & 0x00FFFFFF);
    }

    private static String round(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }
}
