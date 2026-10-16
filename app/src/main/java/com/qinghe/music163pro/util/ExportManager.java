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
        exportAll(context, targetDir, false, callback);   // F6：默认仍为「同名跳过」
    }

    /**
     * F6：带「强制覆盖」的导出。
     *
     * @param forceOverwrite true 时同名文件**覆盖**（先写 .tmp 再 rename，保证原子性，不留半个文件）；
     *                       false 时维持原策略「同名跳过」
     */
    public static void exportAll(Context context, String targetDir, boolean forceOverwrite,
                                 ExportCallback callback) {
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
        MusicLog.i("Export", "开始导出: 目标=" + target.getAbsolutePath()
                + " 强制覆盖=" + forceOverwrite + " 曲目数=" + songDirs.size());

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
                if (dest.exists() && !forceOverwrite) {
                    skipped++;
                    MusicLog.i("Export", "跳过（同名已存在）: " + dest.getName());
                    continue;
                }
                if (copyFileAtomic(audioFile, dest, forceOverwrite)) {
                    copied++;
                    MusicLog.i("Export", (forceOverwrite ? "覆盖 → " : "复制 → ") + dest.getName());
                } else {
                    failed++;
                    MusicLog.w("Export", "失败: " + dest.getName());
                }
            }

            // 歌词 / 封面：只在目标还没有同名文件时写入
            for (String extraName : EXTRA_FILES) {
                File extra = new File(songDir, extraName);
                if (!extra.isFile()) {
                    continue;
                }
                File dest = new File(target, baseName + " - " + extraName);
                if (dest.exists() && !forceOverwrite) {
                    continue;
                }
                if (copyFileAtomic(extra, dest, forceOverwrite)) {
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

    /**
     * F6：带覆盖的复制。覆盖时**先写临时文件再改名**，保证原子性 ——
     * 中途失败也不会在目标留下半个文件（原文件保持不动）。
     */
    private static boolean copyFileAtomic(File source, File dest, boolean overwrite) {
        if (!overwrite || !dest.exists()) {
            return copyFile(source, dest);
        }
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        if (tmp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        if (!copyFile(source, tmp)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        dest.delete();   // 某些文件系统 rename 不覆盖已存在的目标
        if (tmp.renameTo(dest)) {
            return true;
        }
        // rename 失败就退回直接复制，别把结果丢了
        boolean ok = copyFile(tmp, dest);
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        return ok;
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
