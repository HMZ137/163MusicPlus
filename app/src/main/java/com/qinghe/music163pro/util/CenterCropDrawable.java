package com.qinghe.music163pro.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 等比放大铺满可用区域、超出部分居中裁掉的背景 Drawable，
 * 等价于 {@code ImageView} 的 {@code centerCrop}。
 *
 * <p>背景原来直接用 {@link android.graphics.drawable.BitmapDrawable}，而它的默认
 * gravity 是 {@code Gravity.FILL} —— 会把图片按 view 的宽高**非等比拉伸**。
 * 手表屏幕是 320×360 这种接近方形、又比较窄的比例，而专辑封面多为正方形，
 * 自定义图片更是各种比例都有，拉伸后变形非常明显。这里改成「等比放大到刚好盖住，
 * 再多出来的部分裁掉」，也就是需求里说的「填充」而不是「拉伸」。</p>
 *
 * <p>用 Drawable 而不是把 bitmap 预先裁成固定尺寸，是因为 view 的尺寸在
 * {@code onBoundsChange} / 布局之后才知道，绘制时按实际 bounds 计算可以自适应。</p>
 */
public final class CenterCropDrawable extends Drawable {

    private final Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();

    public CenterCropDrawable(Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    @Override
    public void draw(Canvas canvas) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        Rect bounds = getBounds();
        int bw = bounds.width();
        int bh = bounds.height();
        int iw = bitmap.getWidth();
        int ih = bitmap.getHeight();
        if (bw <= 0 || bh <= 0 || iw <= 0 || ih <= 0) {
            return;
        }

        // 取较大的缩放比：两个方向都保证盖满，不留白
        float scale = Math.max((float) bw / iw, (float) bh / ih);
        int cropW = Math.min(Math.round(bw / scale), iw);
        int cropH = Math.min(Math.round(bh / scale), ih);

        int left = (iw - cropW) / 2;
        int top = (ih - cropH) / 2;
        srcRect.set(left, top, left + cropW, top + cropH);
        dstRect.set(bounds);
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return bitmap != null && bitmap.hasAlpha() ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
    }
}
