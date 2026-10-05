package com.qinghe.music163pro.manager;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.qinghe.music163pro.api.BilibiliApiHelper;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.Song;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages downloading songs to /sdcard/163Music/Download/
 * Each song is saved in its own subfolder with info.json.
 */
public class DownloadManager {

    private static final String TAG = "DownloadManager";
    private static final String DOWNLOAD_DIR = "163Music/Download";
    private static final String INFO_FILE = "info.json";
    private static final String SONGS_DIR = "songs";
    private static final String SONG_FILE_MP3 = "song.mp3";
    private static final String SONG_FILE_FLAC = "song.flac";
    /**
     * 早期版本固定使用的音频文件名。查找时仍然优先返回它们，保证旧下载不受影响；
     * 现在的下载按「曲名 - 曲师」命名，因此主要靠 {@link #AUDIO_EXTENSIONS} 扫描。
     */
    private static final String[] LEGACY_AUDIO_FILE_NAMES = {SONG_FILE_MP3, SONG_FILE_FLAC};
    /** 会被识别为音频的扩展名（小写，含点）。 */
    private static final String[] AUDIO_EXTENSIONS = {
            ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".ape", ".wma"
    };
    /** 音频文件名（不含扩展名）的长度上限，避免超出文件系统对单级名字长度的限制。 */
    private static final int MAX_AUDIO_BASE_NAME_LENGTH = 80;
    /** 目录名 / 文件名里「曲名 - 曲师」的分隔符。 */
    private static final String NAME_ARTIST_SEPARATOR = " - ";
    private static final String LYRICS_FILE = "lyrics.lrc";
    private static final String ROMALRC_FILE = "romalrc.lrc";
    private static final String COVER_FILE = "cover.jpg";

    /**
     * 媒体扫描器的「跳过此目录」标记。
     *
     * <p><b>为什么必须有它：</b>每个下载目录里的 {@code cover.jpg} 是给 App 自己展示用的
     * 专辑封面，但 Android 的媒体扫描器只按扩展名判断，会把它们全部收进相册 ——
     * 下载几十首歌，相册里就多出几十张一模一样的方形封面图，翻照片时非常碍事。
     * 一个 {@code .nomedia} 文件能让扫描器跳过所在目录**及其整棵子树**，
     * 从根上阻止封面被收录。</p>
     *
     * <p>放在 {@code 163Music/Download/} 根下（而不是每首歌的目录里）：既省事，
     * 又顺带避免系统音乐库把下载的歌重复收录一遍。本 App 的本地歌曲走自己的目录扫描
     * （见 {@code LocalMusicActivity}）、铃声走直接插入 MediaStore，都不依赖媒体库，
     * 因此屏蔽扫描不影响任何功能。</p>
     */
    private static final String NOMEDIA_FILE = ".nomedia";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==================== 下载队列（仅内存，用于 UI 展示） ====================

    /** 下载任务的状态。因为是单线程 executor，同一时刻最多只有一个 DOWNLOADING。 */
    public enum DownloadState {
        /** 已提交，还在排队等前面的任务 */
        QUEUED,
        /** 正在下载 */
        DOWNLOADING
    }

    /** 下载任务的只读快照，供 UI 展示。 */
    public static final class DownloadTask {
        public final long songId;
        public final String songName;
        public final DownloadState state;
        /** 0~100；还不知道总大小时为 -1 */
        public final int percent;

        DownloadTask(long songId, String songName, DownloadState state, int percent) {
            this.songId = songId;
            this.songName = songName;
            this.state = state;
            this.percent = percent;
        }
    }

    /** 可变的任务记录，只在下载线程与主线程之间靠 volatile 字段传递。 */
    private static final class MutableTask {
        final long songId;
        final String songName;
        volatile DownloadState state = DownloadState.QUEUED;
        volatile int percent = -1;

        MutableTask(long songId, String songName) {
            this.songId = songId;
            this.songName = songName;
        }
    }

    /**
     * 排队中 + 下载中的任务。
     *
     * <p>纯粹为 UI 服务，进程重启即丢失；任务进入终态（成功/失败）时会被移除，
     * 因为那时列表重新扫盘就能看到真实结果，留着反而会显示过期状态。</p>
     */
    private static final Map<Long, MutableTask> activeTasks = new ConcurrentHashMap<>();

    /** 按歌曲 id 取任务快照；不在队列里则返回 null。 */
    public static DownloadTask getActiveDownload(long songId) {
        MutableTask task = activeTasks.get(songId);
        return task == null ? null
                : new DownloadTask(task.songId, task.songName, task.state, task.percent);
    }

    /** 是否正在下载或排队中。 */
    public static boolean isDownloadActive(long songId) {
        return activeTasks.containsKey(songId);
    }

    /** 整个队列的快照。 */
    public static List<DownloadTask> getActiveDownloads() {
        List<DownloadTask> snapshot = new ArrayList<>();
        for (MutableTask task : activeTasks.values()) {
            snapshot.add(new DownloadTask(task.songId, task.songName, task.state, task.percent));
        }
        return snapshot;
    }

    /** 队列里的任务数（排队中 + 下载中）。给每秒刷新的 UI 用，避免反复构造快照。 */
    public static int getActiveDownloadCount() {
        return activeTasks.size();
    }

    private static void registerTask(Song song) {
        activeTasks.put(song.getId(), new MutableTask(song.getId(), song.getName()));
    }

    private static void markTaskDownloading(long songId) {
        MutableTask task = activeTasks.get(songId);
        if (task != null) {
            task.state = DownloadState.DOWNLOADING;
            if (task.percent < 0) {
                task.percent = 0;
            }
        }
    }

    private static void updateTaskProgress(long songId, int percent) {
        MutableTask task = activeTasks.get(songId);
        if (task != null) {
            task.percent = percent;
        }
    }

    private static void finishTask(long songId) {
        activeTasks.remove(songId);
    }

    /** Structured local download entry for one audio quality and its resolved file. */
    private static final class LocalTrackInfo {
        final String quality;
        final File audioFile;

        LocalTrackInfo(String quality, File audioFile) {
            this.quality = quality;
            this.audioFile = audioFile;
        }
    }

    public interface DownloadCallback {
        void onSuccess(String filePath);
        void onError(String message);

        /**
         * 下载进度（0~100），主线程回调。
         *
         * <p>做成 default 方法是为了不影响既有调用方；服务器没返回 Content-Length 时不会回调。</p>
         */
        default void onProgress(int percent) {
        }
    }

    /** 旧下载改名任务的回调，全部在主线程触发。 */
    public interface MigrateCallback {
        /**
         * @param done        已处理数量
         * @param total       总数量
         * @param currentName 正在处理的歌曲名，用于展示进度
         */
        void onProgress(int done, int total, String currentName);

        /**
         * @param renamed 改名成功数
         * @param skipped 无需处理 / 目标已存在而跳过数
         * @param failed  改名失败数；为 -1 表示整体异常
         */
        void onFinished(int renamed, int skipped, int failed);
    }

    /**
     * Download a song to /sdcard/163Music/Download/<folder>/
     */
    public static void downloadSong(Song song, String cookie, DownloadCallback callback) {
        downloadSong(song, cookie, "exhigh", callback);
    }

    /**
     * Download a song with the specified audio quality level.
     * Quality: standard / higher / exhigh / lossless / hires / jyeffect / sky / jymaster
     * Falls back to best free quality if requested level is unavailable.
     */
    public static void downloadSong(Song song, String cookie, String quality,
                                    DownloadCallback callback) {
        // 在调用线程（通常是主线程）就登记，这样排在后面的任务立刻能显示为「排队中」，
        // 而不是等到前面的任务跑完才出现。
        if (song.getId() > 0) {
            registerTask(song);
        }

        // 包一层：无论成功、失败还是提前返回，都保证任务会从队列里摘掉，
        // 同时把进度同步进队列快照。
        final DownloadCallback wrapped = new DownloadCallback() {
            @Override
            public void onSuccess(String filePath) {
                finishTask(song.getId());
                callback.onSuccess(filePath);
            }

            @Override
            public void onError(String message) {
                finishTask(song.getId());
                callback.onError(message);
            }

            @Override
            public void onProgress(int percent) {
                updateTaskProgress(song.getId(), percent);
                callback.onProgress(percent);
            }
        };

        executor.execute(() -> {
            try {
                if (song.isBilibili()) {
                    if (isDownloaded(song)) {
                        mainHandler.post(() -> wrapped.onError("歌曲已下载"));
                        return;
                    }
                    BilibiliApiHelper.getAudioStreamUrl(song.getBvid(), song.getCid(), cookie,
                            new BilibiliApiHelper.AudioStreamCallback() {
                                @Override
                                public void onResult(String url) {
                                    executor.execute(() -> doDownload(song, url, null, true, wrapped));
                                }

                                @Override
                                public void onError(String message) {
                                    mainHandler.post(() -> wrapped.onError("获取下载链接失败: " + message));
                                }
                            });
                    return;
                }
                if (hasDownloadedQuality(song, quality)) {
                    mainHandler.post(() -> wrapped.onError("该音质已下载"));
                    return;
                }
                MusicApiHelper.getSongUrlWithQuality(song.getId(), cookie, quality,
                        new MusicApiHelper.UrlCallback() {
                            @Override
                            public void onResult(String url) {
                                executor.execute(() -> doDownload(song, url, quality, false, wrapped));
                            }

                            @Override
                            public void onError(String message) {
                                mainHandler.post(() -> wrapped.onError("获取下载链接失败: " + message));
                            }
                        });
            } catch (Exception e) {
                mainHandler.post(() -> wrapped.onError("下载失败: " + e.getMessage()));
            }
        });
    }

    /**
     * Download a song to a specific output file (used for caching, e.g. ringtone).
     * Does NOT save info.json or lyrics. Calls callback with the output file path.
     */
    public static void downloadSongToFile(Song song, String cookie, String quality,
                                          File outputFile, DownloadCallback callback) {
        executor.execute(() -> {
            try {
                if (song.isBilibili()) {
                    BilibiliApiHelper.getAudioStreamUrl(song.getBvid(), song.getCid(), cookie,
                            new BilibiliApiHelper.AudioStreamCallback() {
                                @Override
                                public void onResult(String url) {
                                    executor.execute(() -> doDownloadToFile(url, true, outputFile, callback));
                                }

                                @Override
                                public void onError(String message) {
                                    mainHandler.post(() -> callback.onError("获取链接失败: " + message));
                                }
                            });
                    return;
                }
                MusicApiHelper.getSongUrlWithQuality(song.getId(), cookie, quality,
                        new MusicApiHelper.UrlCallback() {
                            @Override
                            public void onResult(String url) {
                                executor.execute(() -> doDownloadToFile(url, false, outputFile, callback));
                            }

                            @Override
                            public void onError(String message) {
                                mainHandler.post(() -> callback.onError("获取链接失败: " + message));
                            }
                        });
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("下载失败: " + e.getMessage()));
            }
        });
    }

    private static void doDownloadToFile(String urlStr, boolean bilibili,
                                         File outputFile, DownloadCallback callback) {
        try {
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (bilibili) {
                conn.setRequestProperty("Referer", "https://www.bilibili.com");
            }

            try {
                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(outputFile);
                copyWithProgress(is, fos, conn.getContentLength(), callback);
                fos.flush();
                fos.close();
                is.close();

                String filePath = outputFile.getAbsolutePath();
                mainHandler.post(() -> callback.onSuccess(filePath));
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Download to file error", e);
            mainHandler.post(() -> callback.onError("下载失败: " + e.getMessage()));
        }
    }

    /**
     * 把响应流写进文件，并按已写字节数上报进度。
     *
     * <p>只在百分比真正变化时才回调，否则一次几十 MB 的下载会往主线程抛上千个 post。</p>
     *
     * @param totalLength 服务器给的 Content-Length；小于等于 0 时不报进度（无法计算百分比）
     * @return 实际写入的字节数
     */
    private static long copyWithProgress(InputStream is, FileOutputStream fos, long totalLength,
                                         DownloadCallback callback) throws Exception {
        byte[] buffer = new byte[8192];
        long written = 0;
        int lastPercent = -1;
        int len;
        while ((len = is.read(buffer)) != -1) {
            fos.write(buffer, 0, len);
            written += len;
            if (totalLength > 0) {
                int percent = (int) (written * 100 / totalLength);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    final int reported = percent;
                    mainHandler.post(() -> callback.onProgress(reported));
                }
            }
        }
        return written;
    }

    private static void doDownload(Song song, String urlStr, String quality, boolean bilibili,
                                   DownloadCallback callback) {
        // 这里已经是真正开始传输了，状态从「排队中」切到「下载中」
        if (song.getId() > 0) {
            markTaskDownloading(song.getId());
        }
        try {
            File songDir = getSongDir(song);
            if (!songDir.exists()) {
                if (!songDir.mkdirs()) {
                    mainHandler.post(() -> callback.onError("无法创建下载目录"));
                    return;
                }
            }
            // 保证下载树对媒体扫描不可见：否则 cover.jpg 会被相册收录，
            // 下载几十首歌相册里就多出几十张封面图。
            ensureNoMediaMarker();

            File outputParent = bilibili ? songDir : getQualityDir(songDir, quality);
            if (!outputParent.exists() && !outputParent.mkdirs()) {
                mainHandler.post(() -> callback.onError("无法创建音质目录"));
                return;
            }
            if (!bilibili && findAudioFileInDir(outputParent) != null) {
                mainHandler.post(() -> callback.onError("该音质已下载"));
                return;
            }

            String audioFileName = buildAudioFileName(song, urlStr);
            File outputFile = new File(outputParent, audioFileName);

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (bilibili) {
                conn.setRequestProperty("Referer", "https://www.bilibili.com");
            }

            try {
                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(outputFile);
                copyWithProgress(is, fos, conn.getContentLength(), callback);
                fos.flush();
                fos.close();
                is.close();

                saveSongInfo(songDir, song);
                if (!bilibili) {
                    downloadLyrics(songDir, song);
                    downloadCover(songDir, song);
                }

                long now = System.currentTimeMillis();
                outputFile.setLastModified(now);
                outputParent.setLastModified(now);
                songDir.setLastModified(now);

                String filePath = outputFile.getAbsolutePath();
                mainHandler.post(() -> callback.onSuccess(filePath));
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Download error", e);
            mainHandler.post(() -> callback.onError("下载失败: " + e.getMessage()));
        }
    }

    /**
     * Save song metadata to info.json in the song's download folder.
     */
    private static void saveSongInfo(File songDir, Song song) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", song.getId());
            obj.put("name", song.getName());
            obj.put("artist", song.getArtist());
            obj.put("album", song.getAlbum());
            obj.put("source", song.getSource());
            obj.put("bvid", song.getBvid());
            obj.put("cid", song.getCid());
            if (song.getCoverUrl() != null && !song.getCoverUrl().isEmpty()) {
                obj.put("coverUrl", song.getCoverUrl());
            }

            File infoFile = new File(songDir, INFO_FILE);
            FileOutputStream fos = new FileOutputStream(infoFile);
            OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(obj.toString(2));
            writer.flush();
            writer.close();
            fos.close();
        } catch (Exception e) {
            Log.w(TAG, "Error saving song info", e);
        }
    }

    /**
     * Download lyrics for a song and save as lyrics.lrc in the song's download folder.
     * Also downloads translated lyrics as tlyrics.lrc if available.
     */
    private static void downloadLyrics(File songDir, Song song) {
        if (song.getId() <= 0) return;
        try {
            String lrcText = MusicApiHelper.fetchLyricsSync(song.getId(), null);
            if (lrcText != null && !lrcText.isEmpty()) {
                File lrcFile = new File(songDir, LYRICS_FILE);
                try (FileOutputStream fos = new FileOutputStream(lrcFile);
                     OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8")) {
                    writer.write(lrcText);
                    writer.flush();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error downloading lyrics", e);
        }
        try {
            String tlyricText = MusicApiHelper.fetchTranslatedLyricsSync(song.getId(), null);
            if (tlyricText != null && !tlyricText.isEmpty()) {
                File tlyricFile = new File(songDir, "tlyrics.lrc");
                try (FileOutputStream fos = new FileOutputStream(tlyricFile);
                     OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8")) {
                    writer.write(tlyricText);
                    writer.flush();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error downloading translated lyrics", e);
        }
        // 音译（音）歌词，保存后离线也能显示发音
        try {
            String romalrcText = MusicApiHelper.fetchRomajiLyricsSync(song.getId(), null);
            if (romalrcText != null && !romalrcText.isEmpty()) {
                File romalrcFile = new File(songDir, ROMALRC_FILE);
                try (FileOutputStream fos = new FileOutputStream(romalrcFile);
                     OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8")) {
                    writer.write(romalrcText);
                    writer.flush();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error downloading romanized lyrics", e);
        }
    }

    /**
     * Download album cover for a song and save as cover.jpg in the song's download folder.
     */
    private static void downloadCover(File songDir, Song song) {
        String coverUrl = song.getCoverUrl();
        if (coverUrl == null || coverUrl.isEmpty()) return;
        File coverFile = new File(songDir, COVER_FILE);
        if (coverFile.exists()) return;
        try {
            java.net.URL url = new java.net.URL(coverUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            try {
                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(coverFile);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                fos.flush();
                fos.close();
                is.close();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error downloading cover for " + song.getName(), e);
        }
    }

    /**
     * 在下载目录里找出音频文件。
     *
     * <p>早期实现只按 {@code song.mp3} / {@code song.flac} 这两个固定名字做白名单匹配，
     * 所以一旦音频改按「曲名 - 曲师」命名就会全部认不出来。现在改成扫描扩展名
     * （见 {@link #AUDIO_EXTENSIONS}），文件名只作展示用途，不再影响识别。</p>
     *
     * <p>旧的固定文件名仍然优先返回，保证升级前下载的文件继续可用。</p>
     *
     * @return 目录里的音频文件；没有则返回 null
     */
    private static File findAudioFileInDir(File songDir) {
        if (!isManagedDownloadDir(songDir)) {
            return null;
        }
        for (String name : LEGACY_AUDIO_FILE_NAMES) {
            File legacy = new File(songDir, name);
            if (legacy.isFile()) {
                return legacy;
            }
        }
        File[] children = songDir.listFiles();
        if (children == null) {
            return null;
        }
        // 正常情况下一个目录只有一个音频；万一有多个（例如残留文件），取最新的一个。
        File newest = null;
        for (File child : children) {
            if (child == null || !isAudioFile(child)) {
                continue;
            }
            if (newest == null || child.lastModified() > newest.lastModified()) {
                newest = child;
            }
        }
        return newest;
    }

    /** 是否是能识别的音频文件（只按扩展名判断）。 */
    public static boolean isAudioFile(File file) {
        return file != null && file.isFile() && hasAudioExtension(file.getName());
    }

    private static boolean hasAudioExtension(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        for (String extension : AUDIO_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去掉已知的音频扩展名，把文件名还原成「曲名 - 曲师」。
     * 不是已知扩展名时原样返回。
     */
    public static String stripAudioExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lower = fileName.toLowerCase();
        for (String extension : AUDIO_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return fileName.substring(0, fileName.length() - extension.length());
            }
        }
        return fileName;
    }

    private static File getQualityDir(File songDir, String quality) {
        String safeQuality = TextUtils.isEmpty(quality) ? "exhigh" : sanitizeFileName(quality);
        return new File(new File(songDir, SONGS_DIR), safeQuality);
    }

    private static List<LocalTrackInfo> getStructuredTracks(File songDir) {
        List<LocalTrackInfo> tracks = new ArrayList<>();
        if (!isManagedDownloadDir(songDir)) {
            return tracks;
        }
        File songsDir = new File(songDir, SONGS_DIR);
        File[] qualityDirs = songsDir.listFiles();
        if (qualityDirs == null) {
            return tracks;
        }
        for (File qualityDir : qualityDirs) {
            if (qualityDir == null || !qualityDir.isDirectory()) {
                continue;
            }
            File audioFile = findAudioFileInDir(qualityDir);
            if (audioFile != null) {
                tracks.add(new LocalTrackInfo(qualityDir.getName(), audioFile));
            }
        }
        // Prefer the highest quality first; if multiple files share a level,
        // keep the newest one first so recent re-downloads win ties.
        tracks.sort((left, right) -> {
            int rankCompare = Integer.compare(
                    MusicApiHelper.qualityLevelRank(right.quality),
                    MusicApiHelper.qualityLevelRank(left.quality));
            if (rankCompare != 0) {
                return rankCompare;
            }
            return Long.compare(right.audioFile.lastModified(), left.audioFile.lastModified());
        });
        return tracks;
    }

    private static LocalTrackInfo getBestStructuredTrack(File songDir) {
        List<LocalTrackInfo> tracks = getStructuredTracks(songDir);
        return tracks.isEmpty() ? null : tracks.get(0);
    }

    private static LocalTrackInfo getTrackForQuality(File songDir, String quality) {
        if (TextUtils.isEmpty(quality)) {
            return null;
        }
        for (LocalTrackInfo track : getStructuredTracks(songDir)) {
            if (quality.equals(track.quality)) {
                return track;
            }
        }
        return null;
    }

    private static boolean isManagedDownloadDir(File songDir) {
        if (songDir == null || !songDir.isDirectory()) {
            return false;
        }
        // 用 getAbsolutePath 而不是 getCanonicalPath：后者是系统调用（逐级 stat 父目录），
        // 而本方法在每个目录上会被调用 6~10 次。这里的目录名来自 listFiles()，
        // 本来就是规范路径，绝对路径足以做前缀判断。
        String dirPath = songDir.getAbsolutePath();
        String rootPath = downloadRootPath();
        return rootPath != null
                && (dirPath.equals(rootPath) || dirPath.startsWith(rootPath + File.separator));
    }

    /**
     * 下载根目录的绝对路径。
     *
     * <p>同一进程内不会变化，缓存一次避免重复拼接。</p>
     */
    private static volatile String cachedDownloadRootPath;

    private static String downloadRootPath() {
        String cached = cachedDownloadRootPath;
        if (cached == null) {
            cached = new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR).getAbsolutePath();
            cachedDownloadRootPath = cached;
        }
        return cached;
    }

    // ==================== 媒体扫描屏蔽（相册不再收录封面） ====================

    /**
     * 确保下载根目录下存在 {@link #NOMEDIA_FILE}，让媒体扫描器跳过整棵下载树。
     *
     * <p>幂等且廉价（只 stat 一次），可以在每次写下载文件前放心调用。
     * 注意<b>不能</b>把 {@code .nomedia} 写进单首歌的目录：那是下一层的标记，
     * 而扫描器只认「向上最近的标记」，写在根上才能覆盖全部歌曲目录，
     * 也免去每下载一首就多一个标记文件。</p>
     *
     * @return 本次是否新创建了标记文件
     */
    public static boolean ensureNoMediaMarker() {
        File root = new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR);
        if (!root.exists() && !root.mkdirs()) {
            return false;
        }
        File marker = new File(root, NOMEDIA_FILE);
        if (marker.exists()) {
            return false;
        }
        try {
            // 内容留空即可，扫描器只判断文件是否存在
            if (marker.createNewFile()) {
                Log.i(TAG, "已创建 " + marker.getAbsolutePath() + "，媒体扫描将跳过下载目录");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "创建 .nomedia 失败", e);
        }
        return false;
    }

    /** 下载根目录下是否已有 {@link #NOMEDIA_FILE} 标记。 */
    public static boolean hasNoMediaMarker() {
        return new File(new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR),
                NOMEDIA_FILE).exists();
    }

    /** 下载根目录（供媒体扫描刷新等外部逻辑使用）。 */
    public static File getDownloadRoot() {
        return new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR);
    }

    /**
     * 构造音频文件名，沿用「曲名 - 曲师」的命名风格。
     *
     * <p>早期版本无论什么歌都存成 {@code song.mp3} / {@code song.flac}，
     * 拷到别的播放器里完全看不出是哪首歌。现在直接用歌曲信息命名，
     * 与所在的下载子目录保持一致。</p>
     *
     * <p>扩展名仍按下载 URL 推断：FLAC 流带 {@code .flac} 后缀，其余按 mp3 处理
     * （与改动前的行为一致）。</p>
     */
    private static String buildAudioFileName(Song song, String url) {
        return buildAudioBaseName(song) + (isFlacStream(url) ? ".flac" : ".mp3");
    }

    /**
     * 「曲名 - 曲师」的文件名主干（不含扩展名）。
     * 迁移旧下载时复用这里，保证新旧命名规则永远一致。
     */
    private static String buildAudioBaseName(Song song) {
        String name = sanitizeFileName(song.getName());
        String artist = sanitizeFileName(song.getArtist());
        if (name.isEmpty()) {
            name = song.isBilibili() && song.getCid() > 0
                    ? "bilibili_audio_" + song.getCid()
                    : "unknown";
        }

        String baseName = artist.isEmpty() ? name : name + NAME_ARTIST_SEPARATOR + artist;
        if (baseName.length() > MAX_AUDIO_BASE_NAME_LENGTH) {
            baseName = baseName.substring(0, MAX_AUDIO_BASE_NAME_LENGTH).trim();
        }
        return baseName;
    }

    /** 判断下载 URL 指向的是不是 FLAC 流（只看路径扩展名，忽略查询参数）。 */
    private static boolean isFlacStream(String url) {
        if (url == null) {
            return false;
        }
        String path = url;
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) {
            path = path.substring(0, qIdx);
        }
        return path.toLowerCase().endsWith(".flac");
    }

    /**
     * Load song metadata from info.json in a download folder.
     * @return Song with real id, name, artist, album set; or null on failure
     */
    public static Song loadSongInfo(File songDir) {
        if (songDir == null || !songDir.isDirectory()) {
            return null;
        }
        File infoFile = new File(songDir, INFO_FILE);
        if (!infoFile.exists()) {
            // 没有 info.json 的目录（手动拷进来的音乐、或早期版本留下的残留）：
            // 改动前这里直接返回 null，整个下载会被列表忽略；现在退回按名字解析。
            return buildSongFromNames(songDir);
        }
        try {
            FileInputStream fis = new FileInputStream(infoFile);
            InputStreamReader reader = new InputStreamReader(fis, "UTF-8");
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            reader.close();
            fis.close();

            JSONObject obj = new JSONObject(sb.toString());
            Song song = new Song(
                    obj.optLong("id", 0),
                    obj.optString("name", ""),
                    obj.optString("artist", ""),
                    obj.optString("album", "")
            );
            song.setSource(obj.optString("source", null));
            song.setBvid(obj.optString("bvid", ""));
            song.setCid(obj.optLong("cid", 0));
            // Load cover URL: prefer local cover.jpg, then info.json stored URL
            File localCover = new File(songDir, COVER_FILE);
            if (localCover.exists()) {
                song.setCoverUrl("file://" + localCover.getAbsolutePath());
            } else {
                String storedCoverUrl = obj.optString("coverUrl", "");
                if (!storedCoverUrl.isEmpty()) {
                    song.setCoverUrl(storedCoverUrl);
                }
            }
            LocalTrackInfo bestTrack = getBestStructuredTrack(songDir);
            if (bestTrack != null) {
                song.setUrl(bestTrack.audioFile.getAbsolutePath());
                song.setLocalQuality(bestTrack.quality);
            } else {
                File audioFile = findAudioFileInDir(songDir);
                if (audioFile != null) {
                    song.setUrl(audioFile.getAbsolutePath());
                    song.setLocalQuality(null);
                }
            }
            return song;
        } catch (Exception e) {
            Log.w(TAG, "Error loading song info from " + songDir, e);
            return buildSongFromNames(songDir);
        }
    }

    /**
     * 没有（或读不出）info.json 时的兜底：直接按音频文件名 / 目录名解析「曲名 - 曲师」。
     *
     * <p>覆盖两种以前会被列表整个跳过的情况：手动拷进 Download 目录的音乐，
     * 以及缺 info.json 的残留目录。</p>
     *
     * <p>解析优先级：音频文件名 → 所在目录名。目录名一直是「曲名 - 曲师」格式，
     * 所以即使音频文件还叫旧的 {@code song.mp3} 也能还原出歌名。</p>
     *
     * @return 解析出的歌曲（id 为 0，因为拿不到网易云 ID）；目录里没有音频时返回 null
     */
    private static Song buildSongFromNames(File songDir) {
        LocalTrackInfo track = getBestStructuredTrack(songDir);
        File audioFile = track != null ? track.audioFile : findAudioFileInDir(songDir);
        if (audioFile == null) {
            return null;
        }

        String displayName = stripAudioExtension(audioFile.getName());
        String artist = "";
        int separator = displayName.indexOf(NAME_ARTIST_SEPARATOR);
        if (separator > 0) {
            artist = displayName.substring(separator + NAME_ARTIST_SEPARATOR.length());
            displayName = displayName.substring(0, separator);
        } else {
            // 音频文件名不是「曲名 - 曲师」（例如旧的 song.mp3），退回用目录名。
            String dirName = songDir.getName();
            int dirSeparator = dirName.indexOf(NAME_ARTIST_SEPARATOR);
            if (dirSeparator > 0) {
                artist = dirName.substring(dirSeparator + NAME_ARTIST_SEPARATOR.length());
                displayName = dirName.substring(0, dirSeparator);
            } else if (!dirName.isEmpty()) {
                displayName = dirName;
            }
        }

        Song song = new Song(0, displayName, artist, "");
        song.setUrl(audioFile.getAbsolutePath());
        song.setLocalQuality(track != null
                ? track.quality
                : detectLocalQualityFromPath(audioFile.getAbsolutePath()));

        File localCover = new File(songDir, COVER_FILE);
        if (localCover.exists()) {
            song.setCoverUrl("file://" + localCover.getAbsolutePath());
        }
        return song;
    }

    /**
     * Get the subfolder for a song inside the download directory.
     */
    private static File getSongDir(Song song) {
        File dir = new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR);
        if (song.isBilibili()) {
            String safeVideoTitle = sanitizeFileName(
                    !TextUtils.isEmpty(song.getAlbum()) ? song.getAlbum() : song.getName());
            String safePartTitle = sanitizeFileName(
                    !TextUtils.isEmpty(song.getName()) ? song.getName() : song.getAlbum());
            String folderName = safeVideoTitle;
            if (!safePartTitle.isEmpty() && !safePartTitle.equals(safeVideoTitle)) {
                folderName = safeVideoTitle + "_" + safePartTitle;
            }
            if (folderName.isEmpty()) {
                folderName = "bilibili_audio_" + song.getCid();
            }
            return new File(dir, folderName);
        }
        String safeName = song.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        String safeArtist = song.getArtist().replaceAll("[\\\\/:*?\"<>|]", "_");
        String folderName = safeName + " - " + safeArtist;
        return new File(dir, folderName);
    }

    private static String sanitizeFileName(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static File getLegacyFlatFile(File dir, Song song) {
        String safeName = song.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        String safeArtist = song.getArtist().replaceAll("[\\\\/:*?\"<>|]", "_");
        String fileName = safeName + " - " + safeArtist + ".mp3";
        File legacy = new File(dir, fileName);
        try {
            String rootPath = dir.getCanonicalPath();
            String filePath = legacy.getCanonicalPath();
            if (filePath.startsWith(rootPath + File.separator)) {
                return legacy;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error validating legacy download path", e);
        }
        return null;
    }

    /**
     * Get list of downloaded song directories from /sdcard/163Music/Download/
     *
     * <p><b>性能注意：</b>这里对每个目录只取「目录自身 + info.json」的最新 mtime 用于排序。
     * 早先版本会对每个目录递归扫出所有音轨再取 mtime（整棵下载树递归扫描），
     * 相当于把整个下载树统计两遍 —— 本机 58 个目录实测整轮要 ~4.8 秒，进页面要等十几秒
     * 才出列表。下载流程写完音频与 info.json 后目录 mtime 必然更新，用它排序结果一致。</p>
     */
    public static List<File> getDownloadedSongDirs() {
        List<File> dirs = new ArrayList<>();
        File dir = new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR);
        if (dir.exists() && dir.isDirectory()) {
            File[] listing = dir.listFiles();
            if (listing != null) {
                for (File f : listing) {
                    if (f.isDirectory() && (findAudioFileInDir(f) != null || getBestStructuredTrack(f) != null)) {
                        dirs.add(f);
                    }
                }
            }
        }
        Map<File, Long> modifiedTimes = new HashMap<>();
        for (File songDir : dirs) {
            modifiedTimes.put(songDir, quickModifiedTime(songDir));
        }
        dirs.sort((left, right) -> Long.compare(
                modifiedTimes.get(right),
                modifiedTimes.get(left)));
        return dirs;
    }

    /**
     * 取目录的「最近改动」时间：只 stat 目录本身与 info.json，不做递归扫描。
     *
     * <p>用于列表排序。下载流程写完音频与 info.json 后目录 mtime 必然更新，
     * 结果与递归统计一致，但只需一次 stat。</p>
     */
    private static long quickModifiedTime(File songDir) {
        long latest = songDir.lastModified();
        File infoFile = new File(songDir, INFO_FILE);
        if (infoFile.exists()) {
            latest = Math.max(latest, infoFile.lastModified());
        }
        return latest;
    }

    /**
     * 直接躺在 Download 根目录下的散装音频文件（早期版本的平铺 .mp3，
     * 以及用户手动拷进来的音乐）。名字仍按「曲名 - 曲师」解析。
     */
    public static List<File> getDownloadedFiles() {
        List<File> files = new ArrayList<>();
        File dir = new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR);
        if (dir.exists() && dir.isDirectory()) {
            File[] listing = dir.listFiles();
            if (listing != null) {
                for (File f : listing) {
                    if (isAudioFile(f)) {
                        files.add(f);
                    }
                }
            }
        }
        files.sort((left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        return files;
    }

    // ==================== 旧下载改名 ====================

    /**
     * 把早期下载里固定命名的音频（{@code song.mp3} / {@code song.flac}）改成
     * 「曲名 - 曲师」，与现在的下载规则统一。
     *
     * <p><b>这是会改动文件的破坏性操作，调用方必须先向用户确认。</b>
     * 因为只匹配那两个固定名，所以完全不会碰已经按新规则命名的文件，
     * 更不会动用户自己放进目录里的音乐。</p>
     *
     * <p>安全约束：只在 {@code /sdcard/163Music/Download/} 白名单目录内操作；
     * 目标文件已存在时跳过而不是覆盖；单首失败继续处理下一首而不是中断。</p>
     */
    public static void migrateLegacyFileNames(MigrateCallback callback) {
        if (callback == null) {
            return;
        }
        executor.execute(() -> {
            try {
                // 先收集一遍，才能给出确定的总数用于进度展示
                Map<File, List<File>> targets = new LinkedHashMap<>();
                int total = 0;
                for (File songDir : getDownloadedSongDirs()) {
                    List<File> legacyFiles = findLegacyAudioFiles(songDir);
                    if (!legacyFiles.isEmpty()) {
                        targets.put(songDir, legacyFiles);
                        total += legacyFiles.size();
                    }
                }
                if (total == 0) {
                    mainHandler.post(() -> callback.onFinished(0, 0, 0));
                    return;
                }

                final int totalCount = total;
                int done = 0;
                int renamed = 0;
                int skipped = 0;
                int failed = 0;

                for (Map.Entry<File, List<File>> entry : targets.entrySet()) {
                    final File songDir = entry.getKey();
                    final List<File> legacyFiles = entry.getValue();

                    // 改名会刷新父目录的 mtime，而下载列表按 mtime 排序，
                    // 所以先记下来，处理完再还原，避免「整理完顺序全乱了」。
                    long songDirModified = songDir.lastModified();

                    // 复用 loadSongInfo：info.json 缺失时会按目录名兜底，因此这里总能拿到曲名
                    Song song = loadSongInfo(songDir);
                    if (song == null) {
                        skipped += legacyFiles.size();
                        done += legacyFiles.size();
                        continue;
                    }

                    for (File legacy : legacyFiles) {
                        done++;
                        String extension = legacy.getName().toLowerCase().endsWith(".flac")
                                ? ".flac" : ".mp3";
                        File parent = legacy.getParentFile();
                        File target = parent == null ? null
                                : new File(parent, buildAudioBaseName(song) + extension);

                        if (target == null || target.equals(legacy) || target.exists()) {
                            skipped++;
                        } else if (legacy.renameTo(target)) {
                            renamed++;
                        } else {
                            Log.w(TAG, "Failed to rename " + legacy.getAbsolutePath());
                            failed++;
                        }

                        final int progressDone = done;
                        final String displayName = song.getName();
                        mainHandler.post(() ->
                                callback.onProgress(progressDone, totalCount, displayName));
                    }

                    songDir.setLastModified(songDirModified);
                }

                final int finalRenamed = renamed;
                final int finalSkipped = skipped;
                final int finalFailed = failed;
                mainHandler.post(() -> callback.onFinished(finalRenamed, finalSkipped, finalFailed));
            } catch (Exception e) {
                Log.w(TAG, "Error migrating legacy download file names", e);
                mainHandler.post(() -> callback.onFinished(0, 0, -1));
            }
        });
    }

    /**
     * 找出一个下载目录里还在用旧固定名的音频。
     * 覆盖 {@code songs/<quality>/song.mp3} 以及音频直接放在歌曲目录下的情况。
     */
    private static List<File> findLegacyAudioFiles(File songDir) {
        List<File> legacy = new ArrayList<>();
        if (!isManagedDownloadDir(songDir)) {
            return legacy;
        }
        File[] qualityDirs = new File(songDir, SONGS_DIR).listFiles();
        if (qualityDirs != null) {
            for (File qualityDir : qualityDirs) {
                if (qualityDir != null && qualityDir.isDirectory()) {
                    addLegacyAudioIfPresent(qualityDir, legacy);
                }
            }
        }
        addLegacyAudioIfPresent(songDir, legacy);
        return legacy;
    }

    private static void addLegacyAudioIfPresent(File dir, List<File> out) {
        for (String name : LEGACY_AUDIO_FILE_NAMES) {
            File candidate = new File(dir, name);
            if (candidate.isFile()) {
                out.add(candidate);
            }
        }
    }

    /**
     * Get the download directory path
     */
    public static String getDownloadDirPath() {
        return new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR).getAbsolutePath();
    }

    /**
     * Get the mp3 file path for a downloaded song.
     * Returns null if not downloaded.
     */
    public static String getDownloadedMp3Path(Song song) {
        File songDir = getSongDir(song);
        LocalTrackInfo bestTrack = getBestStructuredTrack(songDir);
        if (bestTrack != null) {
            song.setLocalQuality(bestTrack.quality);
            return bestTrack.audioFile.getAbsolutePath();
        }
        File audioFile = findAudioFileInDir(songDir);
        if (audioFile != null) {
            song.setLocalQuality(null);
            return audioFile.getAbsolutePath();
        }
        File dir = new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR);
        File legacy = getLegacyFlatFile(dir, song);
        if (legacy != null && legacy.exists()) {
            song.setLocalQuality(null);
            return legacy.getAbsolutePath();
        }
        return null;
    }

    public static String getDownloadedPathForQuality(Song song, String quality) {
        LocalTrackInfo track = getTrackForQuality(getSongDir(song), quality);
        return track != null ? track.audioFile.getAbsolutePath() : null;
    }

    public static boolean hasDownloadedQuality(Song song, String quality) {
        return getDownloadedPathForQuality(song, quality) != null;
    }

    public static List<String> getAvailableLocalQualities(Song song) {
        List<String> qualities = new ArrayList<>();
        for (LocalTrackInfo track : getStructuredTracks(getSongDir(song))) {
            qualities.add(track.quality);
        }
        return qualities;
    }

    public static String getBestDownloadedQuality(Song song) {
        LocalTrackInfo bestTrack = getBestStructuredTrack(getSongDir(song));
        return bestTrack != null ? bestTrack.quality : null;
    }

    public static String detectLocalQualityFromPath(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        String marker = "/" + SONGS_DIR + "/";
        int markerIndex = normalized.lastIndexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        String remainder = normalized.substring(markerIndex + marker.length());
        int slashIndex = remainder.indexOf('/');
        if (slashIndex <= 0) {
            return null;
        }
        return remainder.substring(0, slashIndex);
    }

    /**
     * Check if a song is already downloaded
     */
    public static boolean isDownloaded(Song song) {
        return getDownloadedMp3Path(song) != null;
    }

    /**
     * Delete a downloaded song (removes the entire song directory or legacy file).
     * @return true if deletion was successful
     */
    public static boolean deleteDownload(Song song) {
        File dir = new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR);
        File songDir = getSongDir(song);
        if (songDir.exists() && songDir.isDirectory()) {
            return deleteDir(songDir);
        }
        File legacy = getLegacyFlatFile(dir, song);
        if (legacy != null && legacy.exists()) {
            return legacy.delete();
        }
        return false;
    }

    /**
     * Recursively delete a directory and its contents.
     */
    private static boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDir(child);
                }
            }
        }
        return dir.delete();
    }
}
