package com.qinghe.music163pro.util;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.Locale;

/**
 * 缓存占用统计与清理。
 *
 * <p><b>只管「可以随时丢」的东西</b>：应用私有缓存目录（封面临时文件、铃声裁剪用的临时 mp3、
 * 头像临时图、云盘上传暂存）以及历史残留的更新包。下载的音乐、歌单备份、下载来的歌词
 * 都在 {@code 163Music/Download/} 与 {@code 163Music/*.json}，那是用户的资产，绝不在这里删。</p>
 */
public final class CacheManager {

    /** 历史遗留的更新包路径（下载更新功能已停用，但旧文件可能还在）。 */
    private static final String UPDATE_APK_PATH = "163Music/update.apk";

    private CacheManager() {
    }

    /** 一次统计的结果。 */
    public static final class CacheStat {
        /** 应用私有缓存目录占用。 */
        public final long appCacheBytes;
        /** 历史更新包占用。 */
        public final long updatePackageBytes;
        /** 文件个数（不含目录）。 */
        public final int fileCount;

        CacheStat(long appCacheBytes, long updatePackageBytes, int fileCount) {
            this.appCacheBytes = appCacheBytes;
            this.updatePackageBytes = updatePackageBytes;
            this.fileCount = fileCount;
        }

        public long getTotalBytes() {
            return appCacheBytes + updatePackageBytes;
        }
    }

    /** 统计当前可清理的缓存占用。会遍历缓存目录，请在后台线程调用。 */
    public static CacheStat getStat(Context context) {
        long appCache = 0;
        int count = 0;
        File cacheDir = getAppCacheDir(context);
        if (cacheDir != null) {
            appCache = sizeOf(cacheDir);
            count = countFiles(cacheDir);
        }

        long updateApk = 0;
        File updateFile = getUpdatePackageFile();
        if (updateFile != null && updateFile.isFile()) {
            updateApk = updateFile.length();
            count++;
        }
        return new CacheStat(appCache, updateApk, count);
    }

    /**
     * 清理全部可清理的缓存。
     *
     * @return true 表示没有失败项
     */
    public static boolean clearAll(Context context) {
        boolean ok = true;
        File cacheDir = getAppCacheDir(context);
        if (cacheDir != null && !deleteContents(cacheDir)) {
            ok = false;
        }
        File updateFile = getUpdatePackageFile();
        if (updateFile != null && updateFile.isFile() && !updateFile.delete()) {
            ok = false;
        }
        // 内存里的封面缓存也一并丢掉，否则清完仍显示旧图
        NetworkImageLoader.clearMemoryCache();
        return ok;
    }

    /**
     * 应用私有缓存目录。
     *
     * <p>只用 {@code getCacheDir()}，非 null 时优先 {@code getExternalCacheDir()} 的说法在这里不成立 ——
     * 视频/音频的临时文件都是写到内部 cacheDir 的（见 CloudUploadProgressActivity、
     * MainActivity 的铃声裁剪），统计外部缓存会算出与实际清理范围不一致的数字。</p>
     */
    private static File getAppCacheDir(Context context) {
        return context == null ? null : context.getCacheDir();
    }

    private static File getUpdatePackageFile() {
        try {
            return new File(Environment.getExternalStorageDirectory(), UPDATE_APK_PATH);
        } catch (Exception e) {
            return null;
        }
    }

    /** 递归统计目录占用（跳过符号链接，避免绕进死循环）。 */
    private static long sizeOf(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        File[] children = file.listFiles();
        if (children == null) {
            return 0;
        }
        long total = 0;
        for (File child : children) {
            total += sizeOf(child);
        }
        return total;
    }

    private static int countFiles(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return 1;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            count += countFiles(child);
        }
        return count;
    }

    /**
     * 删掉目录里的内容但保留目录本身（删掉 cacheDir 可能导致后续写缓存失败）。
     *
     * @return true 表示全部删除成功
     */
    private static boolean deleteContents(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return true;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return true;
        }
        boolean ok = true;
        for (File child : children) {
            if (!deleteRecursively(child)) {
                ok = false;
            }
        }
        return ok;
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }

    /** 把字节数格式化成人能读的字符串，例如 {@code 1.2 MB}。 */
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.0f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
