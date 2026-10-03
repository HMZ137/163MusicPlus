package com.qinghe.music163pro.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

/**
 * 歌词「词块」底衬：在文字下面画一个圆角、半透明的色块（同色描边），文字自己绘制在上层。
 *
 * <p>为什么用 {@link ReplacementSpan} 而不是 {@code BackgroundColorSpan}：
 * 后者只能画整块纯色矩形、不支持圆角也不支持描边，做出来就是「硬色块」（旧效果），
 * 与本需求要的「柔和的词性色块」差距很大。</p>
 */
public class LyricBlockSpan extends ReplacementSpan {

    private final int fillColor;
    private final int strokeColor;
    private final int textColor;
    private final float textSizePx;
    private final float padPx;
    private final float radiusPx;
    /** 短语/习语：描边更实一些，视觉上比单词更"重"。 */
    private final boolean emphasize;
    /**
     * 关联角标：同一句里出现多个<b>同类</b>词块时，给它们编号（1、2、3…），
     * 原文块与译文对应块是同一个号 —— 用户就能认出「哪个词对应哪段翻译」。
     * 0 表示不显示（同类只有一个词块时不需要标注，保持界面干净）。
     */
    private final int badge;
    /** 为角标预留的宽度（0 = 无角标）。 */
    private final float badgeReserve;

    public LyricBlockSpan(int fillColor, int strokeColor, int textColor, float textSizePx,
                          float padPx, float radiusPx, boolean emphasize) {
        this(fillColor, strokeColor, textColor, textSizePx, padPx, radiusPx, emphasize, 0);
    }

    public LyricBlockSpan(int fillColor, int strokeColor, int textColor, float textSizePx,
                          float padPx, float radiusPx, boolean emphasize, int badge) {
        this.fillColor = fillColor;
        this.strokeColor = strokeColor;
        this.textColor = textColor;
        this.textSizePx = textSizePx;
        this.padPx = padPx;
        this.radiusPx = radiusPx;
        this.emphasize = emphasize;
        this.badge = badge;
        if (badge > 0) {
            // 角标字号 ≈ 文字的一半；为它预留出绘制宽度，让后面的文字/词块自动右移，
            // 不再被角标压住（这就是「文字对角标做避让」）
            Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);
            float badgeSize = Math.max(8f, textSizePx * 0.55f);
            probe.setTextSize(badgeSize);
            probe.setFakeBoldText(true);
            this.badgeReserve = probe.measureText(String.valueOf(badge))
                    + Math.max(2f, textSizePx * 0.14f);
        } else {
            this.badgeReserve = 0f;
        }
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
                       Paint.FontMetricsInt fm) {
        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        if (fm != null) {
            fm.ascent = metrics.ascent;
            fm.descent = metrics.descent;
            fm.top = metrics.top;
            fm.bottom = metrics.bottom;
        }
        float saved = paint.getTextSize();
        paint.setTextSize(textSizePx);
        float width = paint.measureText(text, start, end);
        paint.setTextSize(saved);
        // 只返回文字宽度：色块的 padding 与角标都画在文字范围之外，
        // 不计入文本流，否则一行会被撑得比 TextView 还宽 —— 英文长句会溢出、两侧文字被裁掉。
        return (int) Math.ceil(width);
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top,
                     int y, int bottom, Paint paint) {
        float savedSize = paint.getTextSize();
        int savedColor = paint.getColor();

        paint.setTextSize(textSizePx);
        float width = paint.measureText(text, start, end);
        // 色块包住文字并左右各外扩一点（视觉上的"内边距"），但不占文本流宽度。
        // 有角标时右侧再外扩一小段，把角标画在色块**内部**：
        // 既不占布局宽度（避免整行超宽被裁），也不超出视图边界（避免顶部第一行角标被裁），
        // 同时不压到本词块的文字。
        float sidePad = padPx * 0.55f;
        float badgePad = badge > 0 ? badgeReserve : 0f;
        float left = x - sidePad;
        float right = x + width + sidePad + badgePad;

        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        float textTop = y + fm.ascent;
        float textBottom = y + fm.descent;
        // 竖直方向只比文字高出一点点：之前是 0.35 倍，相邻两行的色块会上下压到一起
        float blockTop = textTop - padPx * 0.12f;
        float blockBottom = textBottom + padPx * 0.12f;

        Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        blockPaint.setStyle(Paint.Style.FILL);
        blockPaint.setColor(fillColor);
        RectF rect = new RectF(left, blockTop, right, blockBottom);
        canvas.drawRoundRect(rect, radiusPx, radiusPx, blockPaint);

        blockPaint.setStyle(Paint.Style.STROKE);
        blockPaint.setStrokeWidth(Math.max(1f, padPx * 0.22f));
        blockPaint.setColor(emphasize ? strokeColor : withLowerAlpha(strokeColor));
        canvas.drawRoundRect(rect, radiusPx, radiusPx, blockPaint);

        // 文字照常按自己的起点绘制（不因色块而位移）
        paint.setColor(textColor);
        canvas.drawText(text, start, end, x, y, paint);

        // 关联角标：画在色块**内部右侧**那段外扩区里（见上面的 badgePad）
        if (badge > 0) {
            Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            badgePaint.setColor(withAlpha(strokeColor, 0xF0));
            badgePaint.setTextSize(Math.max(8f, textSizePx * 0.5f));
            badgePaint.setFakeBoldText(true);
            badgePaint.setTextAlign(Paint.Align.LEFT);
            float badgeX = x + width + sidePad + badgeReserve * 0.12f;
            float badgeBaseline = blockTop + badgePaint.getTextSize() * 1.02f;
            canvas.drawText(String.valueOf(badge), badgeX, badgeBaseline, badgePaint);
        }

        paint.setTextSize(savedSize);
        paint.setColor(savedColor);
    }

    /** 覆盖 alpha 的辅助（角标用）。 */
    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int withLowerAlpha(int color) {
        int alpha = (color >>> 24) & 0xFF;
        return (color & 0x00FFFFFF) | ((int) (alpha * 0.6f) << 24);
    }
}
