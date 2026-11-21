package com.qinghe.music163pro.util;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * AI 歌词分析结果的本地缓存。
 *
 * <h3>为什么按「歌曲 + 歌词哈希」命名</h3>
 * <p>歌曲 ID 保证同一首歌只分析一次（省 token）；歌词哈希保证歌词变了（用户换了版本、
 * 本地文件被替换）会自动失效重算，而不是拿旧结果硬套。</p>
 *
 * <h3>为什么放公开目录</h3>
 * <p>按约定放在 {@code /sdcard/163MusicPlus/ai_lyrics/}：不进 App 私有目录，用户/文件管理器
 * 可随时手动清理；设置页也提供「占用统计 + 一键清空」。纯文本 JSON，方便排查。</p>
 */
public final class AiLyricCache {

    private static final String TAG = "AiLyricCache";
    private static final String DIR_NAME = "163MusicPlus/ai_lyrics";

    private AiLyricCache() {
    }

    /** 缓存目录（公开存储）。 */
    public static File dir() {
        return new File(Environment.getExternalStorageDirectory(), DIR_NAME);
    }

    /**
     * 缓存文件路径。
     *
     * @param songKey   歌曲键：网易云数字 ID、本地歌用路径派生的键、B 站用 bvid/cid
     * @param lyricsKey 歌词文本（用于计算哈希；为空时退化为只按歌曲键）
     */
    public static File fileFor(String songKey, String lyricsKey) {
        String safeSong = sanitize(songKey);
        String hash = shortHash(lyricsKey == null ? "" : lyricsKey);
        // 每个歌曲一个子目录，方便人工按歌查看/删除
        File songDir = new File(dir(), safeSong);
        return new File(songDir, "ai-" + hash + ".json");
    }

    public static boolean exists(String songKey, String lyricsKey) {
        File f = fileFor(songKey, lyricsKey);
        return f.exists() && f.length() > 0;
    }

    /** 读取缓存（无缓存/读失败返回 null）。 */
    public static String read(String songKey, String lyricsKey) {
        File f = fileFor(songKey, lyricsKey);
        if (!f.exists()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(buf, 0, read, "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "读取 AI 歌词缓存失败: " + f.getAbsolutePath(), e);
            return null;
        }
    }

    /** 写入缓存（JSON 文本）。 */
    public static boolean write(String songKey, String lyricsKey, String json) {
        File f = fileFor(songKey, lyricsKey);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "创建缓存目录失败: " + parent.getAbsolutePath());
            return false;
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(json.getBytes("UTF-8"));
            fos.flush();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "写入 AI 歌词缓存失败: " + f.getAbsolutePath(), e);
            return false;
        }
    }

    // ==================== 统计 / 清理 ====================

    /** 已缓存歌曲数（子目录数）。 */
    public static int countSongs() {
        File[] dirs = dir().listFiles(File::isDirectory);
        return dirs == null ? 0 : dirs.length;
    }

    /** 缓存条目数（json 文件数）。 */
    public static int countEntries() {
        return countJson(dir());
    }

    private static int countJson(File d) {
        if (d == null || !d.exists()) {
            return 0;
        }
        File[] files = d.listFiles();
        if (files == null) {
            return 0;
        }
        int n = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                n += countJson(f);
            } else if (f.getName().endsWith(".json")) {
                n++;
            }
        }
        return n;
    }

    /** 缓存占用字节数。 */
    public static long sizeBytes() {
        return sizeOf(dir());
    }

    private static long sizeOf(File f) {
        if (f == null || !f.exists()) {
            return 0;
        }
        if (f.isFile()) {
            return f.length();
        }
        File[] files = f.listFiles();
        if (files == null) {
            return 0;
        }
        long total = 0;
        for (File child : files) {
            total += sizeOf(child);
        }
        return total;
    }

    /** 人类可读的体积。 */
    public static String sizeText() {
        long bytes = sizeBytes();
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.CHINA, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    /** 清空全部缓存。 */
    public static boolean clearAll() {
        return deleteRecursively(dir());
    }

    private static boolean deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return true;
        }
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursively(child);
                }
            }
        }
        return f.delete();
    }

    // ==================== 工具 ====================

    /** 文件名安全化（歌曲键可能是路径/含特殊字符）。 */
    static String sanitize(String key) {
        if (key == null || key.isEmpty()) {
            return "unknown";
        }
        String s = key.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        if (s.length() > 64) {
            s = s.substring(0, 48) + "_" + shortHash(key);
        }
        return s;
    }

    /** 8 位短哈希（歌词内容用于失效判断）。 */
    public static String shortHash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < digest.length; i++) {
                sb.append(String.format(Locale.US, "%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.format(Locale.US, "%08x", text.hashCode());
        }
    }
}
