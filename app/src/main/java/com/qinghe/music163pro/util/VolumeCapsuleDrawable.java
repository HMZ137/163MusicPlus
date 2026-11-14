package com.qinghe.music163pro.util;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * M1b：音量条示例（`音量条示例.png`）里的**胶囊形音量条**。
 *
 * <p>形态：</p>
 * <ul>
 *   <li>整条是两端半圆的胶囊；</li>
 *   <li>已填充部分**主色紫**（M3 colorPrimary），未填充部分**灰**（M3 surfaceVariant）；</li>
 *   <li>条上一条**橙色竖线**标出「音量告警阈值」（对应 {@code SAFE_VOLUME_PERCENT}）；</li>
 *   <li>颜色规则（需求方 2026-10-17 补充）：音量数字/填充**默认不是橙色**，
 *       只有当前音量**超过阈值**时才变橙色；橙线本身始终是阈值标记。</li>
 * </ul>
 *
 * <p>作为 SeekBar 的 progressDrawable 使用：SeekBar 通过 {@link #setLevel(int)}
 * （0..10000）把进度传进来，绘制时换算成填充比例。</p>
 */
public class VolumeCapsuleDrawable extends Drawable {

    // M3 配色（对齐仓库版 LinearProgressIndicator：indicator=主色紫，track=surfaceVariant）
    private static final int TRACK_COLOR = 0xFF49454F;
    private static final int FILL_COLOR = 0xFFBB86FC;
    private static final int FILL_OVER_THRESHOLD_COLOR = 0xFFFFA726;
    private static final int THRESHOLD_COLOR = 0xFFFFA726;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF rect = new RectF();
    private final int thresholdPercent;
    private final int thresholdLineWidthPx;

    public VolumeCapsuleDrawable(int thresholdPercent, int thresholdLineWidthPx) {
        this.thresholdPercent = Math.max(0, Math.min(100, thresholdPercent));
        this.thresholdLineWidthPx = Math.max(2, thresholdLineWidthPx);
    }

    @Override
    public void draw(Canvas canvas) {
        RectF bounds = new RectF(getBounds());
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        float radius = bounds.height() / 2f;

        // 1) 灰色胶囊底
        paint.setColor(TRACK_COLOR);
        canvas.drawRoundRect(bounds, radius, radius, paint);

        // 2) 白色填充：裁剪到胶囊内再画矩形，右端保持平直、两端仍是圆角
        int level = Math.max(0, Math.min(10000, getLevel()));
        float fraction = level / 10000f;
        if (fraction > 0f) {
            clipPath.reset();
            clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            paint.setColor(fraction * 100f >= thresholdPercent
                    ? FILL_OVER_THRESHOLD_COLOR : FILL_COLOR);
            canvas.drawRect(bounds.left, bounds.top,
                    bounds.left + bounds.width() * fraction, bounds.bottom, paint);
            canvas.restore();
        }

        // 3) 橙色告警阈值竖线
        float x = bounds.left + bounds.width() * thresholdPercent / 100f;
        float half = thresholdLineWidthPx / 2f;
        paint.setColor(THRESHOLD_COLOR);
        rect.set(x - half, bounds.top, x + half, bounds.bottom);
        canvas.drawRoundRect(rect, half, half, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        // 不需要：颜色固定
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // 不需要：颜色固定
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
