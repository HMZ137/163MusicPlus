package com.qinghe.music163pro.util;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

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

    /**
     * 设计基准尺寸（320px 宽屏幕下为 48px）。
     *
     * <p>早期取 36px（与全局按钮高度一致），但实机反馈「太小、容易点不中而误触到别处」——
     * 表盘上手指落点本来就不精确，36px 直径只有屏幕宽度的 11%，偏小。现按
     * 触控目标通行下限放大到 48px（屏幕宽度的 15%）。</p>
     */
    private static final int BASE_SIZE = 48;
    private static final int BASE_MARGIN = 5;
    /** 触控目标下限，避免小屏设备上按钮过小。 */
    private static final int MIN_SIZE_DP = 40;
    private static final int MIN_MARGIN_DP = 4;
    /**
     * 图标占按钮边长的比例。
     *
     * <p>按钮变大后比例要从 0.56 降到 0.5 —— 否则图标会跟着一起变粗，看起来像个实心圆。</p>
     */
    private static final float GLYPH_RATIO = 0.5f;

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
     * 把自绘标题栏撑到能容纳浮动返回按钮的高度。
     *
     * <p><b>为什么需要：</b>返回按钮是浮在内容之上的，页面自己的标题栏并不会自动让它。
     * 标题栏多为 {@code wrap_content}（常见高度只有 30 出头），而按钮现在的占位是
     * {@code buttonSize + 2*margin}（320px 屏上 58px）—— 于是按钮会往下探进第一行内容。
     * 列表里第一行左侧正好是封面，结果是封面被盖住一角、而且那块区域点不到（按钮抢走了事件）。</p>
     *
     * <p><b>这是把按钮从 36px 放大到 48px 之后才暴露出来的：</b>36px 时按钮只越界约 2px，
     * 看不出来；48px 时越界 20 多像素，第一行封面的上半截就被压住了。所以放大按钮必须
     * 连带把标题栏的竖直占位补上。</p>
     *
     * <p>标题文字本身居中，所以把行高撑大不会让它看起来跑偏，只是上下各多了一点留白。</p>
     *
     * @param titleBar 页面自绘的标题行。null 或拿不到 LayoutParams 时静默返回。
     */
    public static void applyTitleBarInset(Activity activity, View titleBar) {
        if (activity == null || titleBar == null) {
            return;
        }
        ViewGroup.LayoutParams params = titleBar.getLayoutParams();
        if (params == null) {
            return;
        }
        int reserved = getReservedStartPadding(activity);
        int current = params.height;
        // wrap_content 是 -2、match_parent 是 -1，都不是「实际高度」。
        // wrap_content 可以安全撑大；match_parent 说明这一行本来就是占满父容器的，
        // 不要去动它。已经比占位高的固定高度也不压小。
        boolean growable = current == ViewGroup.LayoutParams.WRAP_CONTENT
                || (current > 0 && current < reserved);
        if (growable) {
            params.height = reserved;
            titleBar.setLayoutParams(params);
        }

        // 圆角屏：顶栏贴左右边缘，补横向内缩避让物理圆角。
        // 让标题文字 / 右端按钮不进切除区；只对小于基准值的内边距生效，不缩小已有留白。
        // 这是把「圆角内缩」从单页（PlaylistDetailActivity）推广到全部子页面顶栏的统一切点：
        // 17 个 XML 页都调用了本方法，一次改动即全员受益。
        int inset = WatchUiUtils.cornerInset(activity);
        // 左上角浮动返回按钮（X）的占位比圆角内缩更大，必须按它的实际占地预留左内边距，
        // 否则居中标题（尤其「本地音乐 (N首)」这种较长文本）左半截会压到返回按钮底下。
        // 所有调用本方法的页面都挂了浮动返回按钮（B 站 / MV 系列自带返回控件、不走本方法），
        // 所以这里统一把左内边距撑到 max(圆角内缩, 返回按钮占位) 是安全的。
        int neededStart = Math.max(inset, getReservedStartPadding(activity));
        int padStart = titleBar.getPaddingStart();
        int padEnd = titleBar.getPaddingEnd();
        if (padStart < neededStart || padEnd < inset) {
            titleBar.setPaddingRelative(
                    Math.max(padStart, neededStart),
                    titleBar.getPaddingTop(),
                    Math.max(padEnd, inset),
                    titleBar.getPaddingBottom());
        }

        // 标题栏里的文字一律压成单行 + 结尾截断。
        //
        // 为什么必须统一做：本方法把标题行的 paddingStart 撑到返回按钮占位（本机约 100px）、
        // paddingEnd 撑到圆角内缩，再加上全局缩放(115%)，标题实际可用宽度只剩约 220px。而不少
        // 页面的标题是运行时才填进去的（歌单名 / 歌名 / MV 名 / 设置项名），一旦超过这个宽度，
        // TextView 默认换行 —— 中文会被折成一列竖排（本项目已实测多例：我的歌单、本地音乐管理…）。
        // 统一单行 + END 截断后，超长文本退化为结尾「…」，不会再竖排。
        forceSingleLineTitles(titleBar);
    }

    /** 把标题栏内的所有文字压成单行 + 结尾省略（含直接传入标题 TextView 的页面）。 */
    private static void forceSingleLineTitles(View root) {
        if (root == null) {
            return;
        }
        if (root instanceof TextView) {
            applySingleLine((TextView) root);
        } else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                forceSingleLineTitles(group.getChildAt(i));
            }
        }
    }

    /** 幂等地把某个 TextView 设成单行 + END 截断。 */
    private static void applySingleLine(TextView tv) {
        if (tv == null || tv.getMaxLines() == 1) {
            return;
        }
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
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
