package com.qinghe.music163pro.util;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qinghe.music163pro.R;

/**
 * 统一的操作菜单弹层（替代 {@code AlertDialog.setTitle(…).setItems(…)}）。
 *
 * <h3>为什么要自己写</h3>
 * <p>原来的写法在手表窄屏（约 216dp）+ 界面缩放放大后，<b>标题折成两行</b>，而 AlertDialog
 * 内部把标题与列表容器的高度算错，往下滑一段距离列表首项就会压到标题上（文字重叠）。</p>
 *
 * <p>这里把标题与列表彻底分开：标题是<b>固定单行</b>（超出省略）+ 下方 1px 分隔线；
 * 列表单独放在 ScrollView 里，最多占屏幕高度的 {@link #MAX_LIST_RATIO}，条目再多也只滚列表，
 * <b>不可能与标题重叠</b>。</p>
 *
 * <h3>样式</h3>
 * <ul>
 *   <li>弹层：直角卡片底（与全项目一致）、左右各留 8dp 边距；</li>
 *   <li>条目：44dp 高、卡片底 + 涟漪、14sp；</li>
 *   <li>当前项：主色文字 + 左侧 3dp 主色条；</li>
 *   <li>危险项：红色文字；</li>
 *   <li>底部「取消」按钮，点弹层外部也可关闭。</li>
 * </ul>
 */
public final class WatchMenuDialog {

    /** 列表最多占屏幕高度的比例（超出则列表内部滚动）。 */
    private static final float MAX_LIST_RATIO = 0.52f;
    private static final int ROW_HEIGHT_DP = 44;
    private static final int DIALOG_MARGIN_DP = 8;

    public static final int NO_SELECTION = -1;
    public static final int NO_DANGER = -1;

    /** 条目点击回调。 */
    public interface OnItemSelected {
        void onSelected(int index);
    }

    private WatchMenuDialog() {
    }

    public static void show(Context context, CharSequence title, String[] items,
                            OnItemSelected callback) {
        show(context, title, items, NO_SELECTION, NO_DANGER, callback);
    }

    public static void show(Context context, CharSequence title, String[] items,
                            int selectedIndex, OnItemSelected callback) {
        show(context, title, items, selectedIndex, NO_DANGER, callback);
    }

    /**
     * @param selectedIndex 当前项下标（主色高亮），{@link #NO_SELECTION} 表示无
     * @param dangerIndex   危险项下标（红色文字），{@link #NO_DANGER} 表示无
     */
    public static void show(Context context, CharSequence title, String[] items,
                            int selectedIndex, int dangerIndex, OnItemSelected callback) {
        if (context == null || items == null || items.length == 0) {
            return;
        }
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiKit.dialogCard(context));

        // ── 标题：居中（P5 统一浮窗口径：标题与按钮同一套视觉）、固定单行、不参与滚动 ──
        if (title != null && title.length() > 0) {
            TextView tvTitle = new TextView(context);
            tvTitle.setText(title);
            tvTitle.setTextColor(0xFFFFFFFF);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tvTitle.setTypeface(null, Typeface.BOLD);
            tvTitle.setSingleLine(true);
            tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvTitle.setGravity(Gravity.CENTER);
            int ph = UiKit.dp(context, 12);
            int pv = UiKit.dp(context, 9);
            tvTitle.setPadding(ph, pv, ph, pv);
            card.addView(tvTitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            View divider = new View(context);
            divider.setBackgroundColor(0x1FFFFFFF);
            card.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UiKit.dp(context, 1))));
        }

        // ── 列表：独立滚动区，永远不会盖到标题 ──
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(UiKit.dp(context, 6), UiKit.dp(context, 4),
                UiKit.dp(context, 6), UiKit.dp(context, 4));
        scroll.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < items.length; i++) {
            final int index = i;
            boolean selected = (i == selectedIndex);
            boolean danger = (i == dangerIndex);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_card_tile);
            row.setPadding(UiKit.dp(context, 12), 0, UiKit.dp(context, 12), 0);
            row.setClickable(true);
            row.setFocusable(true);
            row.setMinimumHeight(UiKit.dp(context, ROW_HEIGHT_DP));

            // 当前项左侧主色条
            View bar = new View(context);
            int barW = UiKit.dp(context, 3);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(barW, 0);
            barLp.setMargins(0, UiKit.dp(context, 8), UiKit.dp(context, 8), UiKit.dp(context, 8));
            if (selected) {
                bar.setBackgroundColor(UiKit.COLOR_ACCENT);
            }
            row.addView(bar, barLp);

            TextView label = new TextView(context);
            label.setText(items[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (danger) {
                label.setTextColor(0xFFFF6B6B);
            } else if (selected) {
                label.setTextColor(UiKit.COLOR_ACCENT);
            } else {
                label.setTextColor(0xFFFFFFFF);
            }
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);

            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (callback != null) {
                    callback.onSelected(index);
                }
            });
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, UiKit.dp(context, 2), 0, UiKit.dp(context, 2));
            list.addView(row, rowLp);
        }

        // 列表高度：按行数直接算（每行 44dp + 上下 2dp 间距），封顶 62% 屏高。
        // 必须在这里算准——之前用 post 回调在首次布局后再限制，第一帧已经按
        // wrap_content 撑满，弹层总高超出屏幕被系统压缩，标题与首项会贴到一起。
        // 每行留余量（行高 44dp + 间距 4dp + 字号放大余量 8dp），避免最后一项被裁
        int rowPitch = UiKit.dp(context, ROW_HEIGHT_DP + 12);
        int listContent = items.length * rowPitch + UiKit.dp(context, 10);
        int maxListHeight =
                (int) (context.getResources().getDisplayMetrics().heightPixels * MAX_LIST_RATIO);
        int listHeight = Math.min(listContent, maxListHeight);
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listHeight));

        // ── 取消 ──
        TextView cancel = new TextView(context);
        cancel.setText("取消");
        cancel.setTextColor(0xFFB0B0B0);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, UiKit.dp(context, 10), 0, UiKit.dp(context, 10));
        cancel.setClickable(true);
        cancel.setFocusable(true);
        cancel.setOnClickListener(v -> dialog.dismiss());
        card.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(0.55f);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.width = context.getResources().getDisplayMetrics().widthPixels
                    - 2 * UiKit.dp(context, DIALOG_MARGIN_DP);
            window.setAttributes(attrs);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }
}
