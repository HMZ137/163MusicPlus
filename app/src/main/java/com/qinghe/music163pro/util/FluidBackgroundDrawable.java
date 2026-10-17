package com.qinghe.music163pro.util;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * P9：「流体背景」——几个大色团在深色底上缓慢游动、边缘柔和地互相叠加，像流体 / 岩浆灯。
 *
 * <p>设计口径（需求方 2026-10-17）：主界面背景除「跟随封面（可调模糊）」外，另外提供一个
 * <b>会自己流动的背景</b>，不跟随歌曲封面，也不需要任何图片素材。</p>
 *
 * <h3>为什么写成 Drawable</h3>
 * <p>现有背景体系（{@link BackgroundUtil#applyBackground}）是「给根 View 设 background」，
 * 封面 / 自定义图都走 {@link CenterCropDrawable}。写成 Drawable 可以直接接进这套体系
 * （主界面背景层、歌词页都自动生效），且由 View 的可见性驱动动画：</p>
 * <ul>
 *   <li>{@link #setVisible(boolean, boolean)} 在页面可见时启动、不可见时停止 —— 不会在后台空转；</li>
 *   <li>每帧只做 5 次「平移 + 画圆」，渐变色块在 {@link #onBoundsChange(Rect)} 里**只建一次**，
 *       避免逐帧 new {@link RadialGradient}（手表上那是主要开销）；</li>
 *   <li>画色团用 {@link PorterDuff.Mode#SCREEN}：深底上叠加出「发光」的流体感，越叠越亮而不是糊成一片。</li>
 * </ul>
 *
 * <p>色团用固定配色（不取封面主色）：流体背景是「不跟封面走」的一档，没有封面时也应完整可用。</p>
 */
public final class FluidBackgroundDrawable extends Drawable {

    /** 帧间隔（毫秒）：手表上 25fps 足够顺滑，也比 30fps 省电。 */
    private static final int FRAME_MS = 40;

    /** 底色：略带蓝的近黑，比纯黑更衬色团。 */
    private static final int BASE_COLOR = 0xFF0A0A12;

    /** 色团配色：紫 / 蓝 / 青 / 品红 / 深紫，与 App 主色（#BB86FC）同族。 */
    private static final int[] COLORS = {
            0xFF7C4DFF,
            0xFF2F80ED,
            0xFF17C3B2,
            0xFFE05A8A,
            0xFF3A2A6D,
    };
    /** 每个色团的半径（占屏幕长边比例）。 */
    private static final float[] RADIUS = {0.55f, 0.48f, 0.42f, 0.40f, 0.62f};
    /** 每个色团的运动参数：{x 角速度, y 角速度, 初相}（单位：rad/s 与 rad）。 */
    private static final float[][] MOTION = {
            {0.21f, 0.15f, 0.0f},
            {0.13f, 0.19f, 1.7f},
            {0.17f, 0.11f, 3.1f},
            {0.11f, 0.23f, 4.6f},
            {0.09f, 0.13f, 2.3f},
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint bgPaint = new Paint();
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 每个色团当前的渐变色块与半径（按 bounds 建一次）。 */
    private RadialGradient[] gradients;
    private float[] radii;

    private final long startMs = SystemClock.uptimeMillis();
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidateSelf();
            if (running) {
                handler.postDelayed(this, FRAME_MS);
            }
        }
    };

    public FluidBackgroundDrawable() {
        bgPaint.setColor(BASE_COLOR);
        blobPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        int w = bounds.width();
        int h = bounds.height();
        if (w <= 0 || h <= 0) {
            gradients = null;
            return;
        }
        float maxR = Math.max(w, h);
        gradients = new RadialGradient[COLORS.length];
        radii = new float[COLORS.length];
        for (int i = 0; i < COLORS.length; i++) {
            float r = Math.max(8f, maxR * RADIUS[i]);
            radii[i] = r;
            // 中心不透明 → 边缘全透明；渐变色块按「圆心在 (0,0)」建，绘制时整体平移
            gradients[i] = new RadialGradient(0f, 0f, r,
                    new int[]{COLORS[i], COLORS[i] & 0x00FFFFFF},
                    new float[]{0.0f, 1.0f}, Shader.TileMode.CLAMP);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) {
            return;
        }
        canvas.drawRect(b, bgPaint);
        if (gradients == null) {
            onBoundsChange(b);
            if (gradients == null) {
                return;
            }
        }
        float t = (SystemClock.uptimeMillis() - startMs) / 1000f;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        int save = canvas.save();
        for (int i = 0; i < gradients.length; i++) {
            float phase = MOTION[i][2];
            // 每个色团走不同的李萨如轨迹（x 用 sin、y 用 cos），速度互不整除 ⇒ 肉眼看不到循环
            float x = cx + (float) Math.sin(t * MOTION[i][0] + phase) * w * 0.34f;
            float y = cy + (float) Math.cos(t * MOTION[i][1] + phase * 1.7f) * h * 0.34f;
            // 轻微「呼吸」：透明度在小范围内起伏，让色团边界有融化感
            blobPaint.setAlpha(150 + (int) (45 * Math.sin(t * 0.9f + phase)));
            blobPaint.setShader(gradients[i]);
            canvas.save();
            canvas.translate(x, y);
            canvas.drawCircle(0f, 0f, radii[i], blobPaint);
            canvas.restore();
        }
        canvas.restoreToCount(save);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (visible) {
            start();
        } else {
            stop();
        }
        return changed;
    }

    private void start() {
        if (running) {
            return;
        }
        running = true;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, FRAME_MS);
    }

    private void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    @Override
    public void setAlpha(int alpha) {
        bgPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // 流体背景不用色滤；保留接口兼容
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
