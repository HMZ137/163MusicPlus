package com.qinghe.music163pro.util;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared confirm dialog builder adapted to the watch UI.
 *
 * <p>提供两种形态，都走同一套样式，避免各页面自己拼弹窗导致风格不一致：</p>
 * <ul>
 *   <li>{@link #show} —— 经典的「取消 / 确定」二选一，用于删除这类需要确认的操作。</li>
 *   <li>{@link #showChoice} —— 取消 + 若干动作按钮（纵向排列），
 *       用于长按之类的「弹出操作菜单」场景。</li>
 * </ul>
 */
public final class WatchConfirmDialog {

    private static final int DEFAULT_OVERLAY_COLOR = 0xCC000000;
    private static final int DEFAULT_TITLE_TEXT_COLOR = 0xFFFFFFFF;
    private static final int DEFAULT_MESSAGE_TEXT_COLOR = 0xB3FFFFFF;
    private static final int DEFAULT_CANCEL_BUTTON_COLOR = 0xFF2D2D2D;
    private static final String OVERLAY_TAG = "watch_confirm_dialog_overlay";

    private WatchConfirmDialog() {
    }

    /** 「取消 / 确定」二选一。 */
    public static void show(Activity activity, String title, String message,
                            Runnable onConfirm, Options options) {
        Options resolved = options != null ? options : Options.defaultOptions();
        FrameLayout overlay = createOverlay(activity, title, message, resolved);
        if (overlay == null) {
            return;
        }
        LinearLayout dialog = (LinearLayout) overlay.getChildAt(0);

        LinearLayout buttonRow = new LinearLayout(activity);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.addView(createButton(activity, overlay, "取消",
                DEFAULT_CANCEL_BUTTON_COLOR, false, null));
        buttonRow.addView(createButton(activity, overlay, "确定",
                resolved.confirmButtonColor, true, onConfirm));
        dialog.addView(buttonRow);

        attach(activity, overlay, resolved.dismissOnOutsideTap);
    }

    /**
     * 取消 + 多个动作按钮（纵向排列，适配手表的窄屏）。
     *
     * <p>用于「长按某项后弹出可选操作」的场景，例如播放队列的
     * 「移除 / 下一首播放」。动作按钮按传入顺序自上而下排列。</p>
     *
     * @param labels  动作按钮文案
     * @param actions 与 labels 一一对应的动作；为 null 表示只关闭弹窗
     */
    public static void showChoice(Activity activity, String title, String message,
                                  String[] labels, Runnable[] actions, Options options) {
        if (labels == null || labels.length == 0) {
            return;
        }
        Options resolved = options != null ? options : Options.defaultOptions();
        FrameLayout overlay = createOverlay(activity, title, message, resolved);
        if (overlay == null) {
            return;
        }
        LinearLayout dialog = (LinearLayout) overlay.getChildAt(0);

        LinearLayout buttonColumn = new LinearLayout(activity);
        buttonColumn.setOrientation(LinearLayout.VERTICAL);
        buttonColumn.setGravity(Gravity.CENTER);

        for (int i = 0; i < labels.length; i++) {
            Runnable action = (actions != null && i < actions.length) ? actions[i] : null;
            buttonColumn.addView(createStackedButton(activity, overlay, labels[i],
                    resolved.confirmButtonColor, action));
        }
        // 取消放最后，避免误触到危险动作
        buttonColumn.addView(createStackedButton(activity, overlay, "取消",
                DEFAULT_CANCEL_BUTTON_COLOR, null));

        dialog.addView(buttonColumn);
        attach(activity, overlay, resolved.dismissOnOutsideTap);
    }

    /**
     * 搭出「遮罩 + 对话框 + 标题 + 正文」，动作按钮由调用方接着往 dialog 里加。
     *
     * @return 遮罩层；activity 不可用时返回 null
     */
    private static FrameLayout createOverlay(Activity activity, String title, String message,
                                            Options options) {
        if (activity == null || activity.isFinishing()) {
            return null;
        }
        FrameLayout rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) {
            return null;
        }
        dismissExisting(rootView);

        FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag(OVERLAY_TAG);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(DEFAULT_OVERLAY_COLOR);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout dialog = new LinearLayout(activity);
        dialog.setOrientation(LinearLayout.VERTICAL);
        // UI 现代化：弹窗底色统一走 UiKit（#1E1F24、圆角 14dp）——
        // 之前"两套底色"的分叉就从这类各自 setBackgroundColor 的地方来的
        dialog.setBackground(UiKit.dialogCard(activity));
        dialog.setPadding(
                WatchUiUtils.px(activity, 16),
                WatchUiUtils.px(activity, 12),
                WatchUiUtils.px(activity, 16),
                WatchUiUtils.px(activity, 12));
        FrameLayout.LayoutParams dialogParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        dialogParams.gravity = Gravity.CENTER;
        dialogParams.leftMargin = WatchUiUtils.px(activity, 16);
        dialogParams.rightMargin = WatchUiUtils.px(activity, 16);
        dialog.setLayoutParams(dialogParams);

        dialog.addView(createTitleView(activity, title));
        dialog.addView(createMessageView(activity, message));

        overlay.addView(dialog);
        return overlay;
    }

    /** 把搭好的遮罩挂到内容视图上，并处理「点外部关闭」。 */
    private static void attach(Activity activity, FrameLayout overlay, boolean dismissOnOutsideTap) {
        FrameLayout rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) {
            return;
        }
        View dialog = overlay.getChildAt(0);
        if (dismissOnOutsideTap) {
            overlay.setOnClickListener(v -> rootView.removeView(overlay));
        } else {
            overlay.setOnClickListener(v -> {
                // Consume outside taps without dismissing the dialog.
            });
        }
        if (dialog != null) {
            dialog.setOnClickListener(v -> {
                // Consume dialog taps so they do not propagate to the overlay.
            });
        }
        rootView.addView(overlay);
    }

    private static TextView createTitleView(Activity activity, String title) {
        TextView titleView = new TextView(activity);
        titleView.setText(title != null ? title : "");
        titleView.setTextColor(DEFAULT_TITLE_TEXT_COLOR);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, WatchUiUtils.px(activity, 18));
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, WatchUiUtils.px(activity, 6));
        return titleView;
    }

    private static TextView createMessageView(Activity activity, String message) {
        TextView messageView = new TextView(activity);
        messageView.setText(message != null ? message : "");
        messageView.setTextColor(DEFAULT_MESSAGE_TEXT_COLOR);
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_PX, WatchUiUtils.px(activity, 15));
        messageView.setGravity(Gravity.CENTER);
        messageView.setPadding(0, 0, 0, WatchUiUtils.px(activity, 12));
        return messageView;
    }

    /** 横向并排的按钮（用于「取消 / 确定」）。 */
    private static TextView createButton(Activity activity, FrameLayout overlay, String label,
                                         int backgroundColor, boolean isConfirm,
                                         Runnable action) {
        TextView button = newButton(activity, label, backgroundColor);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (isConfirm) {
            params.leftMargin = WatchUiUtils.px(activity, 4);
        } else {
            params.rightMargin = WatchUiUtils.px(activity, 4);
        }
        button.setLayoutParams(params);
        button.setOnClickListener(v -> {
            dismiss(overlay);
            if (action != null) {
                action.run();
            }
        });
        return button;
    }

    /** 纵向堆叠的整宽按钮（用于动作菜单）。 */
    private static TextView createStackedButton(Activity activity, FrameLayout overlay,
                                                String label, int backgroundColor,
                                                Runnable action) {
        TextView button = newButton(activity, label, backgroundColor);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = WatchUiUtils.px(activity, 4);
        button.setLayoutParams(params);
        button.setOnClickListener(v -> {
            dismiss(overlay);
            if (action != null) {
                action.run();
            }
        });
        return button;
    }

    private static TextView newButton(Activity activity, String label, int backgroundColor) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(DEFAULT_TITLE_TEXT_COLOR);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, WatchUiUtils.px(activity, 16));
        button.setGravity(Gravity.CENTER);
        button.setPadding(
                WatchUiUtils.px(activity, 12),
                WatchUiUtils.px(activity, 8),
                WatchUiUtils.px(activity, 12),
                WatchUiUtils.px(activity, 8));
        button.setBackgroundColor(backgroundColor);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private static void dismiss(FrameLayout overlay) {
        if (overlay == null || overlay.getParent() == null) {
            return;
        }
        ((FrameLayout) overlay.getParent()).removeView(overlay);
    }

    private static void dismissExisting(FrameLayout rootView) {
        View existingOverlay = rootView.findViewWithTag(OVERLAY_TAG);
        if (existingOverlay != null) {
            rootView.removeView(existingOverlay);
        }
    }

    public static final class Options {
        /** 统一的弹窗底色。改动前各页面分别传 0xFF1E1E1E / 0xFF424242，看起来不像同一个 App。 */
        private static final int DIALOG_BACKGROUND = 0xFF1E1E1E;
        /** 普通确认按钮（主色）。 */
        private static final int CONFIRM_COLOR = 0xFFBB86FC;
        /** 破坏性确认按钮（删除、移除等）。 */
        private static final int DESTRUCTIVE_COLOR = 0xFFCF6679;

        private final int dialogBackgroundColor;
        private final int confirmButtonColor;
        private final boolean dismissOnOutsideTap;

        public Options(int dialogBackgroundColor, int confirmButtonColor,
                       boolean dismissOnOutsideTap) {
            this.dialogBackgroundColor = dialogBackgroundColor;
            this.confirmButtonColor = confirmButtonColor;
            this.dismissOnOutsideTap = dismissOnOutsideTap;
        }

        /** 常规确认框，点外部可关闭。 */
        public static Options defaultOptions() {
            return new Options(DIALOG_BACKGROUND, CONFIRM_COLOR, true);
        }

        /** 常规确认框，但不允许点外部关闭（用于不希望误触关闭的流程）。 */
        public static Options defaultOptionsNoOutsideDismiss() {
            return new Options(DIALOG_BACKGROUND, CONFIRM_COLOR, false);
        }

        /** 破坏性操作确认框（删除 / 移除），确认按钮用警示色。 */
        public static Options destructiveOptions() {
            return new Options(DIALOG_BACKGROUND, DESTRUCTIVE_COLOR, true);
        }
    }
}
