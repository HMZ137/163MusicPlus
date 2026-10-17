package com.qinghe.music163pro.util;

import android.app.Activity;

/**
 * 统一确认框（P5 起内部改为 {@link WatchPopup} 渲染，样式跟 `浮窗改进_1.bmp` 一致：
 * 14dp 大圆角卡片、标题居中、可选说明、分隔线、底部圆角按钮、按钮可多于两个）。
 *
 * <p>对外 API 保持不变，既有 20+ 调用点无需改动即可获得新样式：</p>
 * <ul>
 *   <li>{@link #show} —— 「取消 / 确定」二选一；</li>
 *   <li>{@link #showChoice} —— 取消 + 若干动作按钮（纵向排列，可多于两个）。</li>
 * </ul>
 */
public final class WatchConfirmDialog {

    private static final int DEFAULT_CANCEL_BUTTON_COLOR = 0xFF2D2D2D;
    private static final int CONFIRM_COLOR = 0xFFBB86FC;
    private static final int DESTRUCTIVE_COLOR = 0xFFCF6679;
    private static final String OVERLAY_TAG = "watch_confirm_dialog_overlay";

    private WatchConfirmDialog() {
    }

    /** 「取消 / 确定」二选一。 */
    public static void show(Activity activity, String title, String message,
                            Runnable onConfirm, Options options) {
        Options resolved = options != null ? options : Options.defaultOptions();
        boolean danger = resolved.confirmButtonColor == DESTRUCTIVE_COLOR;
        WatchPopup.popup(activity, title, null, message, resolved.dismissOnOutsideTap,
                resolved.confirmButtonColor == CONFIRM_COLOR, danger, onConfirm);
    }

    /**
     * 取消 + 多个动作按钮（纵向排列，适配手表的窄屏）。
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
        WatchPopup.actions(activity, title, message, labels, actions);
    }

    /** 旧版本使用的遮罩标签（保留给需要主动清理的调试工具）。 */
    public static String overlayTag() {
        return OVERLAY_TAG;
    }

    public static final class Options {
        /** 统一的弹窗底色（P5 起由 UiKit.dialogCard 决定，此值仅保留兼容）。 */
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
