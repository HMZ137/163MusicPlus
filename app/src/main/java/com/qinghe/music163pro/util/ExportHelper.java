package com.qinghe.music163pro.util;

import android.app.Activity;
import android.os.Environment;

import java.io.File;

/**
 * 「导出下载的音乐」的公共入口（2026-11-27 从 {@code SettingsActivity} 抽出）。
 *
 * <p>设置页的「导出下载的音乐」与「本地音乐管理 → 下载管理 → 折叠菜单 → 导出音乐」
 * 走的是同一套流程：选目标文件夹 → 确认（含是否覆盖）→ 后台复制 → 汇总结果。
 * 抽到这里之后两处只有 UI 提示的差别，复制逻辑／目标候选／覆盖开关都只维护一份。</p>
 *
 * <p>{@link Callback} 可传 {@code null}：此时只用 toast 反馈（下载管理页就是这么用的）。</p>
 */
public final class ExportHelper {

    /** 进度回调（回调都在主线程）。 */
    public interface Callback {
        /** 开始导出（用于把行内的「导出中…」点亮）。 */
        void onStart();

        /** 进度：done / total，currentName 是正在复制的文件名。 */
        void onProgress(int done, int total, String currentName);

        /** 结束：message 是可直接展示的一句话结果。 */
        void onFinished(String message);
    }

    /** 强制覆盖（默认跳过）。勾选状态在本次会话内保留，下次打开导出默认还是上次的选择。 */
    private static boolean forceOverwrite = false;

    private ExportHelper() {
    }

    /** 第一步：选目标文件夹（给几个常见位置，也允许自定义完整路径）。 */
    public static void chooseTarget(final Activity activity, final Callback callback) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final File external = Environment.getExternalStorageDirectory();
        final String musicDir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                .getAbsolutePath();
        final String[] targets = {
                musicDir,
                new File(external, "Music/163Music导出").getAbsolutePath(),
                new File(external, "Download/163Music导出").getAbsolutePath(),
                new File(external, "163Music/导出").getAbsolutePath(),
        };
        // 2026-11-28：菜单里不再放「强制覆盖」——
        // 那一项点一下会关掉当前浮窗再弹一个新的（只为改个文案），体验很突兀。
        // 现在覆盖开关改到下一步「导出确认」里的复选框，同一个浮窗内勾选即可。
        final String[] labels = {"Music 文件夹", "Music/163Music", "Download 目录",
                "163Music/导出", "自定义路径…"};
        Runnable[] actions = {
                () -> confirm(activity, callback, targets[0]),
                () -> confirm(activity, callback, targets[1]),
                () -> confirm(activity, callback, targets[2]),
                () -> confirm(activity, callback, targets[3]),
                () -> promptCustomTarget(activity, callback)
        };
        WatchMenuDialog.show(activity, "导出到文件夹", labels, which -> {
            if (which >= 0 && which < actions.length && actions[which] != null) {
                actions[which].run();
            }
        });
    }

    private static void promptCustomTarget(final Activity activity, final Callback callback) {
        String initial = new File(Environment.getExternalStorageDirectory(),
                "163Music/导出").getAbsolutePath();
        WatchPopup.input(activity, "目标文件夹",
                "输入完整路径，例如 /sdcard/Music/我的导出",
                "/sdcard/…", initial, "确定", path -> {
                    if (path == null || path.isEmpty()) {
                        UiKit.toast(activity, "路径不能为空");
                        return;
                    }
                    confirm(activity, callback, path);
                });
    }

    /** 第二步：确认 + 「同名文件覆盖」复选框（同一浮窗内完成，不再刷新新浮窗）。 */
    private static void confirm(final Activity activity, final Callback callback,
                                final String targetDir) {
        // 浮窗高度有限：全路径会折成 4 行，把底部按钮挤出屏幕，所以统一压成 /sdcard 别名
        String shown = targetDir;
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (root != null && !root.isEmpty() && shown != null && shown.startsWith(root)) {
            shown = "/sdcard" + shown.substring(root.length());
        }
        final WatchPopup popup = WatchPopup.create(activity)
                .title("导出确认")
                .content("目标：" + shown + "\n复制一份到该目录，原下载不受影响。")
                .checkbox("同名文件：覆盖（不勾选则跳过）", forceOverwrite)
                .cancelButton();
        popup.primaryButton("开始导出", text -> {
            forceOverwrite = popup.isCheckboxChecked();
            run(activity, callback, targetDir, forceOverwrite);
        });
        popup.show();
    }

    private static void run(final Activity activity, final Callback callback,
                            final String targetDir, final boolean force) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        UiKit.toast(activity, "正在导出…");
        if (callback != null) {
            callback.onStart();
        }
        new Thread(() -> ExportManager.exportAll(activity.getApplicationContext(), targetDir, force,
                new ExportManager.ExportCallback() {
                    @Override
                    public void onProgress(int done, int total, String currentName) {
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing()) {
                                return;
                            }
                            if (callback != null) {
                                callback.onProgress(done, total, currentName);
                            }
                        });
                    }

                    @Override
                    public void onFinished(int copied, int skipped, int failed) {
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing()) {
                                return;
                            }
                            String message;
                            if (failed < 0) {
                                message = "导出失败：无法创建目标文件夹";
                            } else {
                                message = "已导出 " + copied + " 个文件";
                                if (skipped > 0) {
                                    message += "，跳过 " + skipped + " 个";
                                }
                                if (failed > 0) {
                                    message += "，失败 " + failed + " 个";
                                }
                            }
                            if (callback != null) {
                                callback.onFinished(message);
                            }
                            UiKit.toastLong(activity, message);
                        });
                    }
                })).start();
    }
}
