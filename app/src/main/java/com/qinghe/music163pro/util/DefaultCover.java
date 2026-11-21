package com.qinghe.music163pro.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 默认封面（需求 2026-11-21 任务 4）：歌曲无封面信息时，列表左侧缩略图用它兜底。
 *
 * <p>独立存储（{@code default_cover.jpg}，内部私有目录），不与现有封面/背景共用任何键。</p>
 */
public final class DefaultCover {

    private static final String FILE_NAME = "default_cover.jpg";
    private static volatile Bitmap sCache;

    private DefaultCover() {
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static boolean has(Context context) {
        return context != null && file(context).exists() && file(context).length() > 0;
    }

    /** 保存选中的图片（先缩到 300px 再存，避免大图直接进内存缓存）。 */
    public static void save(Context context, android.net.Uri uri) {
        if (context == null || uri == null) return;
        try {
            Bitmap raw = BitmapFactory.decodeStream(
                    context.getContentResolver().openInputStream(uri));
            if (raw == null) return;
            int w = raw.getWidth(), h = raw.getHeight();
            int target = 300;
            Bitmap scaled = w > target
                    ? Bitmap.createScaledBitmap(raw, target, h * target / w, true)
                    : raw;
            FileOutputStream fos = new FileOutputStream(file(context));
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            fos.flush();
            fos.close();
            sCache = null;   // 失效缓存
        } catch (Throwable t) {
            MusicLog.w("DefaultCover", "保存默认封面失败: " + t);
        }
    }

    public static void clear(Context context) {
        if (context == null) return;
        file(context).delete();
        sCache = null;
    }

    /** 取默认封面位图；未设置返回 null。 */
    public static Bitmap load(Context context) {
        if (context == null) return null;
        if (sCache != null) return sCache;
        File f = file(context);
        if (!f.exists()) return null;
        try {
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            sCache = bmp;
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }
}
