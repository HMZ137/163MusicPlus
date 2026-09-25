package com.qinghe.music163pro.util;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.qinghe.music163pro.R;

/**
 * 左上角浮动返回按钮。
 *
 * <p>部分高版本 Android 手表系统不再把「右滑」映射成返回键，二级页面因此没有
 * 可靠的返回入口，这里统一在页面左上角挂一个浮层返回按钮兜底。</p>
 *
 * <p>按钮直接加在 {@code android.R.id.content} 上，位于页面内容之上，不改变任何
 * 已有布局层级；点击后走 {@link Activity#onBackPressed()}，因此页面自定义的返回
 * 逻辑（关闭浮层、WebView 后退、退出多选等）依然生效。</p>
 *
 * <p>尺寸按屏幕宽度等比缩放（以 320px 宽的手表屏为设计基准），并且不会小于
 * 32dp 的触控下限，避免出现「过大过小」的问题。</p>
 */
public final class WatchBackButton {

    private static final String VIEW_TAG = "watch_floating_back_button";

    /** 设计基准尺寸（320px 宽屏幕下为 36px，与全局按钮高度规范一致）。 */
    private static final int BASE_SIZE = 36;
    private static final int BASE_MARGIN = 4;
    /** 触控目标下限，避免小屏设备上按钮过小。 */
    private static final int MIN_SIZE_DP = 32;
    private static final int MIN_MARGIN_DP = 3;
    /** 图标占按钮边长的比例。 */
    private static final float GLYPH_RATIO = 0.56f;

    private WatchBackButton() {
    }

    private static int buttonSize(Activity activity) {
        return Math.max(WatchUiUtils.px(activity, BASE_SIZE), dp(activity, MIN_SIZE_DP));
    }

    private static int buttonMargin(Activity activity) {
        return Math.max(WatchUiUtils.px(activity, BASE_MARGIN), dp(activity, MIN_MARGIN_DP));
    }

    /**
     * 页面自绘头部时应该预留的左侧内边距。
     *
     * <p>返回按钮是**浮在最上层的独立 View**（TOP|START），页面自己画的标题栏 / 标签行
     * 并不会自动让它。没预留就会出现「返回按钮压住第一个标签，点不到」的问题 ——
     * 评论区那排 推荐/最热/最新 就是这么被压住的。</p>
     *
     * <p>凡是左上角要放内容（标题、排序标签、开关等）的页面，都应该拿这个值当 start padding。</p>
     */
    public static int getReservedStartPadding(Activity activity) {
        if (activity == null) {
            return 0;
        }
        // 按钮自身占位 + 两侧外边距，留出一点间隙避免紧贴
        return buttonSize(activity) + buttonMargin(activity) * 2;
    }

    /**
     * 在页面左上角挂载返回按钮。重复调用是安全的（同一页面只会存在一个）。
     */
    public static void attach(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        FrameLayout content = activity.findViewById(android.R.id.content);
        if (content == null || content.findViewWithTag(VIEW_TAG) != null) {
            return;
        }

        int size = buttonSize(activity);
        int margin = buttonMargin(activity);
        int padding = Math.max(0, Math.round((size - size * GLYPH_RATIO) / 2f));

        ImageView button = new ImageView(activity);
        button.setTag(VIEW_TAG);
        button.setImageResource(R.drawable.ic_arrow_back);
        button.setColorFilter(activity.getResources().getColor(R.color.text_primary));
        button.setBackgroundResource(R.drawable.bg_back_button_ripple);
        button.setContentDescription(activity.getString(R.string.back));
        button.setPadding(padding, padding, padding, padding);
        button.setClickable(true);
        button.setFocusable(true);
        // 不加 elevation：它是 android.R.id.content 的最后一个子 View，天然绘制在页面内容之上；
        // 一旦抬高反而会抢在之后弹出的确认弹窗之前拿到触摸事件。

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = margin;
        params.topMargin = margin;
        button.setLayoutParams(params);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!activity.isFinishing()) {
                    activity.onBackPressed();
                }
            }
        });

        content.addView(button);
    }

    private static int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        if (density <= 0f) {
            density = 1f;
        }
        return (int) (value * density + 0.5f);
    }
}
