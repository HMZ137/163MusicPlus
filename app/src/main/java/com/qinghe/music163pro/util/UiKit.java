package com.qinghe.music163pro.util;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * UI 现代化的**统一样式工具**（圆角卡片风）。
 *
 * <p>设计令牌都收在这里，各页面要用卡片 / 按钮 / 分区标题时从这里拿，
 * 不要再各自 new 一个 {@link GradientDrawable} 写死颜色和圆角 ——
 * 之前弹窗底色出现两套、列表行样式各写各的，就是这么来的。</p>
 *
 * <p>令牌（2026-10-16 改版）：**全 App 圆角化**。卡片 / 列表行 / Tab 统一
 * {@link #DP_CARD_RADIUS}，弹窗与浮层统一 {@link #DP_DIALOG_RADIUS}，
 * 按钮统一 {@link #DP_BUTTON_RADIUS}，不允许各页面自定圆角。</p>
 *
 * <p>圆角取值来自参考图实测（{@code 界面_1.png} / {@code Local_1.png} / {@code Local_2.png}
 * 三张图一致）：卡片左上角圆弧水平跨度 19px，本机 density=2.0 ⇒ 约 10dp。</p>
 * <ul>
 *   <li>页面底色 {@code #121212}（维持原样；**整屏背景不参与圆角**）；</li>
 *   <li>卡片 / 列表行 {@code #1E1F24}、圆角 10dp；列表行内边距 8dp，选中态由
 *       {@code bg_card_row} 的涟漪表现；</li>
 *   <li>主按钮：主色实心、圆角 10dp、高 44dp；次按钮：透明底 + 1dp 描边；</li>
 *   <li>弹窗 / 浮层 {@code #1E1F24}、圆角 14dp；</li>
 *   <li>字号阶梯：见下方 {@code SP_TEXT_*}（G2 建立，XML 侧对应 dimens.xml 的
 *       {@code watch_text_*}；两套世界必须取同一档，别再写 px(n) 当字号）。</li>
 * </ul>
 */
public final class UiKit {

    public static final int COLOR_PAGE = 0xFF121212;
    public static final int COLOR_CARD = 0xFF1E1F24;
    public static final int COLOR_ACCENT = 0xFFBB86FC;
    public static final int COLOR_ON_ACCENT = 0xFF1D1D1D;

    /** 页内二级标签的选中 / 未选中文字色（与「收藏」页一致）。 */
    public static final int COLOR_TAB_ACTIVE = COLOR_ACCENT;
    public static final int COLOR_TAB_INACTIVE = 0xB3FFFFFF;
    /** 标签行高度（与「收藏」页一致）。 */
    public static final int DP_TAB_ROW_HEIGHT = 32;

    public static final int DP_CARD_RADIUS = 10;
    public static final int DP_ROW_RADIUS = 10;
    public static final int DP_DIALOG_RADIUS = 14;
    /** 按钮统一圆角（全 App 唯一值）：与卡片同值，保持观感一致。 */
    public static final int DP_BUTTON_RADIUS = 10;
    public static final int DP_BUTTON_HEIGHT = 44;

    // ==================== G2 字号与尺寸令牌（技术文档 [1-5]） ====================
    //
    // 背景：全 App 的字号原本是散的 —— XML 里写 13/14/11sp，代码自绘页面却写
    // setTextSize(COMPLEX_UNIT_PX, px(15))。这两套**不同源**：
    //     XML 的 sp → density × fontScale
    //     px(n)     → 屏幕宽/320 × 用户缩放，**完全不含 density**
    // 本机实测 14sp 渲染字高约 31px，而 px(14) 只有约 19px —— 同一个「14」差一大截，
    // 这正是「有的页面字大、有的页面字小」的根因。
    //
    // 所以令牌只定义**一套标称值**（sp 语义），两套世界共用：
    //     XML  → res/values/dimens.xml 的 watch_text_* / watch_*
    //     自绘 → 本类的 text*Px(context) / *Px(context)
    // 自绘页面**不要**再拿 COMPLEX_UNIT_PX + px(n) 当字号，改用 sp(c, n) 或下面的取值器。
    //
    // 取值依据：参考图 界面_1.png / Local_1.png / Local_2.png 的墨迹高度实测后换算
    // （见技术文档 [1-5]）。刻意按**默认 100% 缩放**定标 —— 100% 即达参考图比例，
    // 115%/130% 只会更大（验收口径是「不小于」）。

    /** 字号阶梯（标称 sp；与 dimens.xml 的 watch_text_* 一一对应）。 */
    public static final float SP_TEXT_HERO = 21f;
    /** 页面标题（原 XML 16sp / 自绘 px(15)）。 */
    public static final float SP_TEXT_TITLE = 18f;
    /** 列表项主标题（原 13sp）。 */
    public static final float SP_TEXT_ITEM = 17f;
    /** 行标签 / 正文（原 14sp）。 */
    public static final float SP_TEXT_BODY = 16f;
    /** 列表项副标题（原 11sp）。 */
    public static final float SP_TEXT_SUB = 13f;
    /** 次要数值 / 说明（原 11sp）。 */
    public static final float SP_TEXT_CAPTION = 12f;
    /** 小徽章（原自绘 8~10px）。 */
    public static final float SP_TEXT_BADGE = 11f;

    /** 尺寸阶梯（标称 dp；与 dimens.xml 的 watch_* 一一对应）。 */
    public static final int DP_ROW_MIN_HEIGHT = 56;
    public static final int DP_ROW_PADDING_V = 6;
    public static final int DP_ROW_PADDING_H = 12;
    public static final int DP_CARD_PADDING = 12;
    public static final int DP_COVER_SIZE = 40;
    public static final int DP_ICON_SIZE = 24;
    /** 触控目标下限（与 {@code WatchBackButton.MIN_SIZE_DP} 同值，避免两处漂移）。 */
    public static final int DP_TOUCH_MIN = 40;

    /**
     * sp → px：**走系统 sp 度量**，与 XML 里的 {@code sp} 同源。
     *
     * <p>自绘页面要设字号请用它，不要再写
     * {@code setTextSize(COMPLEX_UNIT_PX, px(n))} —— 那条路不含 density，会明显偏小。</p>
     */
    public static int sp(Context c, float spValue) {
        if (c == null) {
            return Math.round(spValue);
        }
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, spValue,
                c.getResources().getDisplayMetrics()));
    }

    public static int textHeroPx(Context c) { return sp(c, SP_TEXT_HERO); }

    public static int textTitlePx(Context c) { return sp(c, SP_TEXT_TITLE); }

    public static int textItemPx(Context c) { return sp(c, SP_TEXT_ITEM); }

    public static int textBodyPx(Context c) { return sp(c, SP_TEXT_BODY); }

    public static int textSubPx(Context c) { return sp(c, SP_TEXT_SUB); }

    public static int textCaptionPx(Context c) { return sp(c, SP_TEXT_CAPTION); }

    public static int textBadgePx(Context c) { return sp(c, SP_TEXT_BADGE); }

    public static int rowMinHeightPx(Context c) { return dp(c, DP_ROW_MIN_HEIGHT); }

    public static int rowPaddingVPx(Context c) { return dp(c, DP_ROW_PADDING_V); }

    public static int rowPaddingHPx(Context c) { return dp(c, DP_ROW_PADDING_H); }

    public static int cardPaddingPx(Context c) { return dp(c, DP_CARD_PADDING); }

    public static int coverSizePx(Context c) { return dp(c, DP_COVER_SIZE); }

    public static int iconSizePx(Context c) { return dp(c, DP_ICON_SIZE); }

    public static int touchMinPx(Context c) { return dp(c, DP_TOUCH_MIN); }

    /** 卡片 / 列表行的圆角像素值。自绘页面直接 new GradientDrawable 时用它取值，别再写 0。 */
    public static int cardRadiusPx(Context c) {
        return dp(c, DP_CARD_RADIUS);
    }

    /** 弹窗 / 浮层的圆角像素值。 */
    public static int dialogRadiusPx(Context c) {
        return dp(c, DP_DIALOG_RADIUS);
    }

    private UiKit() {
    }

    private static GradientDrawable rounded(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** 卡片背景（圆角 {@link #DP_CARD_RADIUS}）。 */
    public static GradientDrawable card(Context c) {
        return rounded(c, COLOR_CARD, DP_CARD_RADIUS);
    }

    /** 弹窗背景（圆角 {@link #DP_DIALOG_RADIUS}）。 */
    public static GradientDrawable dialogCard(Context c) {
        return rounded(c, COLOR_CARD, DP_DIALOG_RADIUS);
    }

    /** 分区小标题（次要色、11sp）。 */
    public static TextView sectionHeader(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(0x80FFFFFF);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 4));
        return tv;
    }

    /** 主按钮：主色实心、圆角 10dp、高 44dp、字号 14。 */
    public static TextView primaryButton(Context c, String text) {
        TextView b = baseButton(c, text);
        GradientDrawable bg = rounded(c, COLOR_ACCENT, 10);
        b.setBackground(bg);
        b.setTextColor(COLOR_ON_ACCENT);
        return b;
    }

    /** 次按钮：透明底 + 1dp 主色描边、统一圆角 10dp、高 44dp。 */
    public static TextView secondaryButton(Context c, String text) {
        TextView b = baseButton(c, text);
        GradientDrawable bg = rounded(c, 0x00000000, DP_BUTTON_RADIUS);
        bg.setStroke(dp(c, 1), COLOR_ACCENT);
        b.setBackground(bg);
        b.setTextColor(COLOR_ACCENT);
        return b;
    }

    private static TextView baseButton(Context c, String text) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        b.setClickable(true);
        b.setFocusable(true);
        b.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(c, DP_BUTTON_HEIGHT)));
        return b;
    }

    /** 统一的 dp 换算。 */
    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ==================== 页内二级标签 ====================

    /**
     * 造一个页内二级标签：**纯文字、无底衬、等宽**，只靠文字颜色区分选中态。
     *
     * <p>这是「收藏」页（{@code activity_favorites_list.xml} 的 tab_fav_* ）定下的样式，
     * 现在统一成全 App 的标签样式 —— 之前有的页面用圆角底衬胶囊（{@code bg_tab_selected}），
     * 和收藏页两种观感并存，故收口到这里。改样式只改这一个方法 + 那个布局。</p>
     *
     * <p>宽度由调用方通过 {@link #tabRowParams} 给权重（等宽平分），或用 MATCH_PARENT 独占一行。</p>
     */
    public static TextView tab(Context c, CharSequence text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(COLOR_TAB_INACTIVE);
        tv.setClickable(true);
        tv.setFocusable(true);
        applySelectableItemBackground(c, tv);
        return tv;
    }

    /** 标签行容器：高度 {@link #DP_TAB_ROW_HEIGHT}、下方留 4dp，横向排布。 */
    public static LinearLayout tabRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, DP_TAB_ROW_HEIGHT));
        lp.bottomMargin = dp(c, 4);
        row.setLayoutParams(lp);
        return row;
    }

    /** 标签在 {@link #tabRow} 里的布局参数：等宽平分、撑满行高。 */
    public static LinearLayout.LayoutParams tabRowParams() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
    }

    /** 刷新标签选中态（只管颜色，不管底衬 —— 本来就没有底衬）。 */
    public static void setTabSelected(TextView tab, boolean selected) {
        if (tab == null) {
            return;
        }
        tab.setTextColor(selected ? COLOR_TAB_ACTIVE : COLOR_TAB_INACTIVE);
    }

    /**
     * 把 XML 里的 {@code ?attr/selectableItemBackground} 等效地应用到代码创建的视图上
     * （纯文字标签没有底衬，仍需要按下涟漪作为反馈）。
     */
    private static void applySelectableItemBackground(Context c, View v) {
        android.util.TypedValue out = new android.util.TypedValue();
        if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                && out.resourceId != 0) {
            v.setBackgroundResource(out.resourceId);
        }
    }
}
