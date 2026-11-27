package com.qinghe.music163pro.util;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

/**
 * 长按二级页标题栏 → 回到主界面（2026-11-28 需求，可在设置里关掉）。
 *
 * <p>手表上从深层页面往回退往往要连点好几次返回，长按标题栏一步回主界面。
 * 采用与 {@link TitleBarCollapse} 相同的方式接入：由
 * {@link WatchBackButton#applyTitleBarInset} 这个全 App 统一的时机登记，
 * 页面不必逐页改。</p>
 */
public final class TitleBarLongPress {

    /** 设置项存储键（设置 → 交互与浮层）。 */
    public static final String PREF_ENABLED = "title_long_press_home";

    private TitleBarLongPress() {
    }

    /** 是否启用（默认开）。 */
    public static boolean isEnabled(Activity activity) {
        if (activity == null) {
            return false;
        }
        return activity.getSharedPreferences("music163_settings", Activity.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, true);
    }

    /**
     * 给标题栏挂长按事件。已经在主界面时跳过（主界面没有「返回主界面」的语义）。
     *
     * <p>注意：只加长按，不加点击 —— 标题栏本身的普通点击行为在有页面里是有用的
     * （例如点标题回到列表顶部），不能被这里吞掉。</p>
     */
    public static void attach(final Activity activity, View titleBar) {
        if (activity == null || titleBar == null) {
            return;
        }
        if ("com.qinghe.music163pro.activity.MainActivity"
                .equals(activity.getClass().getName())) {
            return;
        }
        titleBar.setLongClickable(true);
        titleBar.setOnLongClickListener(v -> {
            if (!isEnabled(activity)) {
                return false;
            }
            Intent intent = new Intent();
            intent.setClassName(activity, "com.qinghe.music163pro.activity.MainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                activity.startActivity(intent);
                UiKit.toast(activity, "已回到主界面");
            } catch (Exception e) {
                MusicLog.w("TitleBarLongPress", "回到主界面失败", e);
            }
            return true;
        });
    }
}
