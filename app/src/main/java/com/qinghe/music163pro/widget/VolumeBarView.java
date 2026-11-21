package com.qinghe.music163pro.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 右侧滚轮音量细条（需求 2026-11-21：参考零度音乐的 ZeroVerticalProgressBar）。
 *
 * <p>三支 Paint 画三条竖线：柔光晕（模糊）/ 灰色轨道 / 白色进度段，进度从底部向上长。
 * 控件本身只负责「画当前值」，平滑感由调用方逐帧喂 {@link #setProgress} 产生。</p>
 */
public class VolumeBarView extends View {

    private static final float PAD = 10f;
    private static final float STROKE = 12f;
    private static final float GLOW_STROKE = 13f;

    private final Paint trackPaint;
    private final Paint progressPaint;
    private final Paint glowPaint;

    private float progress = 50f;
    private float targetProgress = 50f;
    private boolean animating = false;

    public VolumeBarView(Context context) {
        super(context);
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(0x80FFFFFF);
        trackPaint.setStrokeWidth(STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setColor(0xFFFFFFFF);
        progressPaint.setStrokeWidth(STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setColor(0x1A000000);
        glowPaint.setStrokeWidth(GLOW_STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        // 2026-11-21：去掉 BlurMaskFilter + LAYER_TYPE_SOFTWARE。
        // 软件层模糊每帧都要整条重绘，滚轮连续调节时明显拖慢响应；
        // 改成略宽一点的低透明度描边来模拟柔光，硬件加速可用，视觉差别很小。
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = MeasureSpec.getSize(heightMeasureSpec);
        // 宽度 = 最大线宽 + 2.5 倍留白 ≈ 38px，视觉上才显得细
        int width = (int) (GLOW_STROKE + 2.5f * PAD);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float x = getWidth() / 2f;
        float halfStroke = STROKE / 2f;
        float yTop = 2 * PAD + halfStroke;
        float yBottom = getHeight() - yTop;
        if (yBottom <= yTop) {
            return;
        }
        // ① 柔光晕
        canvas.drawLine(x, yTop - PAD / 2f, x, yBottom + PAD / 2f, glowPaint);
        // ② 灰色轨道
        canvas.drawLine(x, yTop, x, yBottom, trackPaint);
        // ③ 白色进度段：从底部长上来
        if (progress > 0f) {
            float span = yBottom - yTop;
            float yStart = yBottom - (progress / 100f) * span;
            canvas.drawLine(x, yStart, x, yBottom, progressPaint);
        }
    }

    /** 设置进度（0~100），只重绘，不带动画。 */
    public void setProgress(float value) {
        progress = Math.max(0f, Math.min(100f, value));
        targetProgress = progress;
        invalidate();
    }

    /**
     * 平滑过渡到目标值。
     *
     * <p>2026-11-21：用「缓动逼近」而不是固定时长补间 —— 滚轮连续调节时目标值一直在变，
     * 每帧朝目标走 35%，既跟手又不会跳格；目标停下后约 6~8 帧收敛（≈100ms）。</p>
     */
    public void setProgressSmooth(float value) {
        targetProgress = Math.max(0f, Math.min(100f, value));
        if (!animating) {
            animating = true;
            postOnAnimation(stepRunnable);
        }
    }

    private final Runnable stepRunnable = new Runnable() {
        @Override
        public void run() {
            float diff = targetProgress - progress;
            if (Math.abs(diff) < 0.4f) {
                progress = targetProgress;
                invalidate();
                animating = false;
                return;
            }
            progress += diff * 0.35f;
            invalidate();
            postOnAnimation(this);
        }
    };

    public float getProgress() {
        return progress;
    }
}
