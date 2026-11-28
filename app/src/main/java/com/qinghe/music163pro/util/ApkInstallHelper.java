package com.qinghe.music163pro.util;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * 拉起系统安装器安装一个 APK（2026-11-28）。
 *
 * <p>局域网传歌收到 APK 后要「点了就能装」，因此统一走这里：
 * FileProvider 授权 + {@code ACTION_VIEW} + {@code application/vnd.android.package-archive}。
 * {@code file_provider_paths.xml} 已包含 {@code TransFiles/}，所以 {@code /sdcard/TransFiles}
 * 下的安装包也能直接给出去。</p>
 */
public final class ApkInstallHelper {

    private ApkInstallHelper() {
    }

    /** @return true 表示已成功拉起安装界面 */
    public static boolean install(Activity activity, File apk) {
        if (activity == null || apk == null) {
            return false;
        }
        if (!apk.exists() || apk.length() <= 0) {
            UiKit.toast(activity, "安装包不完整（文件不存在或 0 字节）");
            return false;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            UiKit.toastLong(activity, "系统没有安装器，请用「浏览 TransFiles」手动打开\n"
                    + apk.getAbsolutePath());
            return false;
        } catch (Exception e) {
            UiKit.toastLong(activity, "安装失败: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return false;
        }
    }
}
