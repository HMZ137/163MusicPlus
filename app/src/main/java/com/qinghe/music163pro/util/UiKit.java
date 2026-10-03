package com.qinghe.music163pro.util;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * UI 现代化的**统一样式工具**（圆角卡片风）。
 *
 * <p>设计令牌都收在这里，各页面要用卡片 / 按钮 / 分区标题时从这里拿，
 * 不要再各自 new 一个 {@link GradientDrawable} 写死颜色和圆角 ——
 * 之前弹窗底色出现两套、列表行样式各写各的，就是这么来的。</p>
 *
 * <p>令牌（用户拍板）：**页面（背景/卡片/行/Tab/索引格/弹窗）一律直角；
 * 按钮一律统一圆角 10dp（{@link #DP_BUTTON_RADIUS}），不允许各页面自定圆角。**</p>
 * <ul>
 *   <li>页面底色 {@code #121212}（维持原样）；</li>
 *   <li>卡片 {@code #1E1F24}、直角（圆角 0）；</li>
 *   <li>列表行即卡片：直角、内边距 8dp；选中态由 {@code bg_card_row} 的涟漪表现；</li>
 *   <li>主按钮：主色实心、统一圆角 10dp、高 44dp；次按钮：透明底 + 1dp 描边；</li>
 *   <li>弹窗底色 {@code #1E1F24}、直角（把原先两套底色归成一套）；</li>
 *   <li>字号阶梯：标题 15 / 正文 13 / 次要 11。</li>
 * </ul>
 */
public final class UiKit {

    public static final int COLOR_PAGE = 0xFF121212;
    public static final int COLOR_CARD = 0xFF1E1F24;
    public static final int COLOR_ACCENT = 0xFFBB86FC;
    public static final int COLOR_ON_ACCENT = 0xFF1D1D1D;

    public static final int DP_CARD_RADIUS = 0;
    public static final int DP_ROW_RADIUS = 0;
    public static final int DP_DIALOG_RADIUS = 0;
    /** 按钮统一圆角（全 App 唯一值）：页面直角、按钮圆角 10dp。 */
    public static final int DP_BUTTON_RADIUS = 10;
    public static final int DP_BUTTON_HEIGHT = 44;

    private UiKit() {
    }

    private static GradientDrawable rounded(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** 卡片背景（圆角 12dp）。 */
    public static GradientDrawable card(Context c) {
        return rounded(c, COLOR_CARD, DP_CARD_RADIUS);
    }

    /** 弹窗背景（圆角 14dp）。 */
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
}
