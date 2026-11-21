package com.qinghe.music163pro.util;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 自定义字体（需求 2026-11-21 任务 8）。
 *
 * <p>把用户导入的字体文件（ttf/otf）复制到应用私有目录并持久化，全局生效；
 * 支持恢复默认。不做预览。</p>
 */
public final class CustomFont {

    private static final String FILE_NAME = "custom_font.ttf";
    private static volatile Typeface sCached;
    private static volatile boolean sLoaded;

    private CustomFont() {
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static boolean has(Context context) {
        return context != null && file(context).exists() && file(context).length() > 0;
    }

    /** 保存导入的字体文件；失败返回 false。 */
    public static boolean save(Context context, Uri uri) {
        if (context == null || uri == null) return false;
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(file(context))) {
            if (is == null) return false;
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            sCached = null;
            sLoaded = false;
            return true;
        } catch (Throwable t) {
            MusicLog.w("CustomFont", "导入字体失败: " + t);
            return false;
        }
    }

    public static void clear(Context context) {
        if (context == null) return;
        file(context).delete();
        sCached = null;
        sLoaded = false;
    }

    /** 当前全局字体；未设置或加载失败返回 null（表示用系统默认）。 */
    public static Typeface get(Context context) {
        if (context == null) return null;
        if (sLoaded) return sCached;
        File f = file(context);
        if (!f.exists()) {
            sLoaded = true;
            sCached = null;
            return null;
        }
        try {
            sCached = Typeface.createFromFile(f);
        } catch (Throwable t) {
            MusicLog.w("CustomFont", "加载字体失败: " + t);
            sCached = null;
        }
        sLoaded = true;
        return sCached;
    }
}
