package com.qinghe.music163pro.util;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 本地音频文件的元信息读取：标题 / 歌手 / 专辑 / 内嵌封面。
 *
 * <p>此前本地音乐只从**文件名**解析「曲名 - 曲师」，元信息里的标题与内嵌封面完全没被读取，
 * 于是自己拷进卡里的歌在列表里一律显示文件名，封面也是空的。</p>
 *
 * <p>优先级：<b>元信息标签 &gt; 文件名解析 &gt; 目录名</b>。标签缺失或为空时由调用方回落，
 * 本类只负责「读到了什么」，不做兜底拼接。</p>
 *
 * <p><b>封面为什么要落地成缓存文件：</b>列表与播放页都通过 {@code coverUrl} 取图，
 * 而 {@link NetworkImageLoader} 已经支持 {@code file://}。把内嵌封面抽到应用的 cacheDir
 * 下再给它一个 {@code file://} 地址，就能同时被列表、播放页封面和背景复用，
 * 不必为本地封面另开一条图片加载链路。cacheDir 会被 {@link CacheManager} 一并清理。</p>
 *
 * <p>本类会做磁盘 IO，**不要在主线程调用**。</p>
 */
public final class LocalMetadata {

    private static final String COVER_DIR = "local_cover";
    /** 有内嵌封面时的缓存后缀。 */
    private static final String SUFFIX_COVER = ".img";
    /** 确认过「没有内嵌封面」的标记后缀 —— 避免每次进页面都重新解一遍。 */
    private static final String SUFFIX_NONE = ".none";

    private LocalMetadata() {
    }

    /** 读到的元信息。拿不到的字段为 {@code null}。 */
    public static final class Info {
        public final String title;
        public final String artist;
        public final String album;
        /** 内嵌封面落地后的缓存文件；没有封面时为 {@code null}。 */
        public final File cover;

        Info(String title, String artist, String album, File cover) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.cover = cover;
        }

        public boolean hasTitle() {
            return !TextUtils.isEmpty(title);
        }

        public boolean hasArtist() {
            return !TextUtils.isEmpty(artist);
        }
    }

    /**
     * 读取一个音频文件的元信息。读不到（文件损坏 / 格式不支持 / 无标签）时各字段为 null，
     * 不会抛异常 —— 调用方永远可以安全地回落到文件名解析。
     */
    public static Info read(Context context, File audio) {
        if (context == null || audio == null || !audio.isFile()) {
            return new Info(null, null, null, null);
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(audio.getAbsolutePath());
            String title = clean(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_TITLE));
            String artist = clean(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_ARTIST));
            if (artist == null) {
                // 不少文件只写了 albumartist
                artist = clean(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            }
            String album = clean(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_ALBUM));
            File cover = resolveCover(context, retriever, audio);
            return new Info(title, artist, album, cover);
        } catch (Throwable ignored) {
            // 损坏文件、不支持的容器、个别 ROM 的原生崩溃都走这里
            return new Info(null, null, null, null);
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 取内嵌封面：命中缓存直接用，没命中就抽出来落盘。
     * 没有封面时写一个 {@code .none} 标记，下次不再重复抽取。
     */
    private static File resolveCover(Context context, MediaMetadataRetriever retriever, File audio) {
        File coverFile = cachedCoverFile(context, audio.getAbsolutePath());
        if (coverFile != null && coverFile.isFile()) {
            return coverFile;
        }
        File noneMarker = markerFile(context, audio.getAbsolutePath());
        if (noneMarker != null && noneMarker.isFile()) {
            return null;
        }
        try {
            byte[] picture = retriever.getEmbeddedPicture();
            if (picture == null || picture.length == 0) {
                touch(noneMarker);
                return null;
            }
            if (coverFile == null) {
                return null;
            }
            File parent = coverFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return null;
            }
            try (OutputStream out = new FileOutputStream(coverFile)) {
                out.write(picture);
            }
            return coverFile;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 该文件的封面缓存路径（可能尚不存在）。 */
    public static File cachedCoverFile(Context context, String audioPath) {
        return new File(coverCacheDir(context), keyOf(audioPath) + SUFFIX_COVER);
    }

    private static File markerFile(Context context, String audioPath) {
        return new File(coverCacheDir(context), keyOf(audioPath) + SUFFIX_NONE);
    }

    /** 「这个文件有没有内嵌封面」的缓存是否已建立。 */
    public static boolean isCoverResolved(Context context, String audioPath) {
        if (TextUtils.isEmpty(audioPath)) {
            return false;
        }
        return cachedCoverFile(context, audioPath).isFile()
                || markerFile(context, audioPath).isFile();
    }

    /** 清掉元信息缓存（封面 + 标记）。 */
    public static void clearCache(Context context) {
        File dir = coverCacheDir(context);
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            // 只删本类生成的两类文件，不碰 cacheDir 里别的东西
            if (child.getName().endsWith(SUFFIX_COVER)
                    || child.getName().endsWith(SUFFIX_NONE)) {
                //noinspection ResultOfMethodCallIgnored
                child.delete();
            }
        }
    }

    private static File coverCacheDir(Context context) {
        File dir = new File(context.getCacheDir(), COVER_DIR);
        if (!dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 用文件路径的摘要做缓存文件名 —— 路径可能含中文/空格，不能直接当文件名。 */
    private static String keyOf(String audioPath) {
        if (TextUtils.isEmpty(audioPath)) {
            return "empty";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(audioPath.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 理论上不会走到；退化成 hashCode 也比没有强
            return Integer.toHexString(audioPath.hashCode());
        }
    }

    private static void touch(File file) {
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            //noinspection ResultOfMethodCallIgnored
            file.createNewFile();
        } catch (Throwable ignored) {
        }
    }

    /** 去掉首尾空白；空串按「没有」处理（很多文件会写空标签）。 */
    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
