package com.qinghe.music163pro.util;

import android.content.Context;

import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.model.Song;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把已下载的歌曲导出到指定文件夹。
 *
 * <p>导出的是**副本**（只复制，不动原始下载目录），文件名用「曲名 - 曲师」，
 * 可以直接丢进别的播放器里听。同时把歌词（原文 / 翻译 / 音译）与封面一起带上，
 * 这样导出后不至于变成一堆没有信息的文件。</p>
 *
 * <p>同名文件**跳过不覆盖**：导出目录可能已经有用户的东西，覆盖太危险。</p>
 */
public final class ExportManager {

    private ExportManager() {
    }

    public interface ExportCallback {
        void onProgress(int done, int total, String currentName);

        /**
         * @param copied  成功复制的文件数
         * @param skipped 目标已存在而跳过的文件数
         * @param failed  失败的文件数
         */
        void onFinished(int copied, int skipped, int failed);
    }

    /** 需要一并带上的元数据文件。 */
    private static final String[] EXTRA_FILES = {
            "lyrics.lrc", "tlyrics.lrc", "romalrc.lrc", "cover.jpg"
    };

    /**
     * 执行导出。**请在后台线程调用**，会遍历下载目录并复制文件。
     *
     * @param targetDir 目标文件夹；不存在时自动创建
     */
    public static void exportAll(Context context, String targetDir, ExportCallback callback) {
        if (context == null || targetDir == null || targetDir.trim().isEmpty()) {
            if (callback != null) {
                callback.onFinished(0, 0, -1);
            }
            return;
        }
        File target = new File(targetDir.trim());
        if (!target.exists() && !target.mkdirs()) {
            if (callback != null) {
                callback.onFinished(0, 0, -1);
            }
            return;
        }

        List<File> songDirs = DownloadManager.getDownloadedSongDirs();

        int copied = 0;
        int skipped = 0;
        int failed = 0;

        for (File songDir : songDirs) {
            Song song = DownloadManager.loadSongInfo(songDir);
            String baseName = (song != null && !isEmpty(song.getName()))
                    ? safeBase(song.getName()) + " - " + safeBase(song.getArtist())
                    : safeBase(songDir.getName());

            List<File> audioFiles = audioFilesIn(songDir);
            for (File audioFile : audioFiles) {
                String ext = extensionOf(audioFile.getName());
                // 同一首歌有多份音质时，非首选那份加上音质名，避免互相覆盖
                String suffix = "";
                if (audioFiles.size() > 1) {
                    String quality = DownloadManager.detectLocalQualityFromPath(
                            audioFile.getAbsolutePath());
                    // 路径里没有 /songs/<quality>/ 结构时 detect 会返回 null，退回用所在目录名
                    if (quality == null || quality.trim().isEmpty()) {
                        quality = audioFile.getParentFile() != null
                                ? audioFile.getParentFile().getName() : "音频";
                    }
                    suffix = "（" + quality + "）";
                }
                File dest = new File(target, baseName + suffix + ext);
                if (dest.exists()) {
                    skipped++;
                    continue;
                }
                if (copyFile(audioFile, dest)) {
                    copied++;
                } else {
                    failed++;
                }
            }

            // 歌词 / 封面：只在目标还没有同名文件时写入
            for (String extraName : EXTRA_FILES) {
                File extra = new File(songDir, extraName);
                if (!extra.isFile()) {
                    continue;
                }
                File dest = new File(target, baseName + " - " + extraName);
                if (dest.exists()) {
                    continue;
                }
                if (copyFile(extra, dest)) {
                    copied++;
                } else {
                    failed++;
                }
            }

            if (callback != null) {
                final int done = songDirs.indexOf(songDir) + 1;
                final int total = songDirs.size();
                final String name = baseName;
                callback.onProgress(done, total, name);
            }
        }

        if (callback != null) {
            callback.onFinished(copied, skipped, failed);
        }
    }

    /** 收集一个下载目录里的所有音频文件（含各音质子目录）。 */
    private static List<File> audioFilesIn(File songDir) {
        List<File> result = new ArrayList<>();
        File[] children = songDir.listFiles();
        if (children == null) {
            return result;
        }
        for (File child : children) {
            if (child.isFile() && DownloadManager.isAudioFile(child)) {
                result.add(child);
            } else if (child.isDirectory()) {
                result.addAll(audioFilesIn(child));
            }
        }
        return result;
    }

    private static boolean copyFile(File source, File dest) {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[16 * 1024];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            return true;
        } catch (Exception e) {
            MusicLog.w("ExportManager", "复制失败: " + source.getAbsolutePath(), e);
            return false;
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private static String safeBase(String value) {
        if (value == null) {
            return "未知";
        }
        return value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
