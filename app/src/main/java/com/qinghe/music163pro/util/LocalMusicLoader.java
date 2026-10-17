package com.qinghe.music163pro.util;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.model.Song;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 4-3 步骤①：本地音乐的**扫描与两阶段加载**，从 {@code LocalMusicActivity} 里抽出来。
 *
 * <p><b>为什么抽</b>：4-3 要把本地音乐迁进 Fragment，好落到 G4 的 ViewPager2 容器里。
 * 原实现把「数据（扫描/索引/元信息）」与「视图（列表/状态行/搜索/排序）」全塞在一个 1300 行的
 * Activity 里，不先把数据侧抽出来就搬不动。本类**只负责数据**，UI 通过 {@link Callback} 回调。</p>
 *
 * <p><b>行为必须与改造前逐字一致</b>（这是本步的验收方式）：</p>
 * <ol>
 *   <li>阶段①（缓存首屏）：用 {@link LocalMetadataIndex} 的上次快照拼列表 → 立刻回调
 *       {@link Callback#onFirstPaint}，让列表秒开；</li>
 *   <li>阶段②（走目录）：拿到「此刻真实存在」的文件 → 回调 {@link Callback#onWalkDone}，
 *       并把结果写进 {@link LocalLibraryCache}、剪掉索引里已不存在的条目；</li>
 *   <li>阶段③（补元信息）：只对**没有命中缓存**的文件开 {@link android.media.MediaMetadataRetriever}，
 *       边跑边分批回调 {@link Callback#onMetadataProgress}，最后 {@link Callback#onFinished}。</li>
 * </ol>
 *
 * <p>取消条件与原来一致：生成号变了（又发起了一轮扫描）或页面已关（{@link Callback#isCancelled()}）。</p>
 *
 * <p><b>耗时日志格式保持不变</b>（它是本步唯一的客观验收指标）：
 * {@code 本地扫描耗时: 缓存首屏=Xms 走目录=Yms 共N首 需读标签=M首 收尾=Zms}</p>
 */
public final class LocalMusicLoader {

    private static final String TAG = "LocalMusic";
    /** 每补多少首通知一次界面。 */
    private static final int METADATA_NOTIFY_EVERY = 12;

    public interface Callback {
        /** 阶段①：缓存首屏可用（**已在主线程**）。 */
        void onFirstPaint(List<Song> cachedSongs);

        /** 阶段②：目录走查完成（**已在主线程**）。 */
        void onWalkDone(List<Song> songs, int misses);

        /** 阶段③：元信息补齐进度（**已在主线程**）。 */
        void onMetadataProgress(int processed, int total, boolean readingFromDisk);

        /** 全部收尾（**已在主线程**）：{@code changed} 表示曲名/曲师/封面有变化，需要重排。 */
        void onFinished(boolean changed);

        /** 本轮是否已作废（换了一轮扫描 / 页面已关）。 */
        boolean isCancelled();

        /** 3-11：有自定义目录不可用被跳过时回调一次（**后台线程**，实现方自行 post 到 UI）。 */
        void onSkippedDirs(List<String> skipped);
    }

    private final Context appContext;
    private final int generation;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 走目录时收集到的真实路径（用于剪索引）。 */
    private final List<String> foundPaths = new ArrayList<>();
    /** 本轮需要真读标签的文件路径。 */
    private final List<String> missPaths = new ArrayList<>();
    private int cacheMisses = 0;

    public LocalMusicLoader(Context context, int generation, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.generation = generation;
        this.callback = callback;
    }

    /** 在后台线程跑完整流程（调用方负责起线程）。 */
    public void runBlocking() {
        final long tStart = SystemClock.elapsedRealtime();
        final List<Song> cachedSongs = readCachedSongs();
        final long tCached = SystemClock.elapsedRealtime();
        if (!cachedSongs.isEmpty() && !cancelled()) {
            mainHandler.post(() -> {
                if (cancelled()) {
                    return;
                }
                callback.onFirstPaint(cachedSongs);
            });
        }

        final List<Song> found = walk();
        final int total = found.size();
        final int misses = cacheMisses;
        // 索引与真实文件对齐：这轮**走完了**才剪，否则会误删没轮到的部分（库里超过上限时）
        if (foundPaths.size() < LocalScanScope.MAX_TRACKS) {
            LocalMetadataIndex.retain(new HashSet<>(foundPaths));
        }
        final long tWalk = SystemClock.elapsedRealtime();
        if (cancelled()) {
            return;
        }
        mainHandler.post(() -> {
            if (cancelled()) {
                return;
            }
            callback.onWalkDone(found, misses);
        });

        readMetadata(found, total);

        MusicLog.i(TAG, "本地扫描耗时: 缓存首屏=" + (tCached - tStart)
                + "ms 走目录=" + (tWalk - tCached)
                + "ms 共" + total + "首 需读标签=" + misses + "首 收尾="
                + (SystemClock.elapsedRealtime() - tWalk) + "ms");
    }

    /** 阶段①：用索引快照拼列表（不读标签、不 stat，见原实现的取舍说明）。 */
    private List<Song> readCachedSongs() {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            return new ArrayList<>();
        }
        Set<String> hidden = LocalMusicHides.getHidden(appContext);
        List<Song> cached = new ArrayList<>();
        for (LocalMetadataIndex.Entry e : LocalMetadataIndex.snapshot()) {
            if (e == null || TextUtils.isEmpty(e.path) || hidden.contains(e.path)) {
                continue;
            }
            File f = new File(e.path);
            NameGuess guess = guessFromFileName(f);
            Song song = new Song(LocalTracks.idForPath(e.path),
                    LocalTracks.safeName(guess.name), guess.artist, "");
            song.setUrl(e.path);
            song.setForceLocalPlayback(true);
            applyEntry(song, e, false);
            cached.add(song);
        }
        return cached;
    }

    /** 阶段②：走一遍扫描范围，拿到此刻真实存在的音频文件。 */
    private List<Song> walk() {
        List<Song> result = new ArrayList<>();
        Set<String> hidden = new HashSet<>(LocalMusicHides.getHidden(appContext));
        cacheMisses = 0;
        missPaths.clear();
        foundPaths.clear();
        for (File root : LocalScanScope.getScanRoots(appContext)) {
            collectAudio(root, 0, result, hidden);
            if (result.size() >= LocalScanScope.MAX_TRACKS) {
                break;
            }
        }
        warnSkippedScanDirs();
        return result;
    }

    private void warnSkippedScanDirs() {
        List<String> skipped = LocalScanScope.getSkippedDirs(appContext);
        if (skipped.isEmpty()) {
            return;
        }
        MusicLog.w(TAG, "已跳过 " + skipped.size() + " 个不可用的自定义扫描目录: "
                + TextUtils.join(", ", skipped));
        if (!cancelled()) {
            callback.onSkippedDirs(skipped);
        }
    }

    /** 递归收集音频；跳过 {@code 163Music/Download} 子树与隐藏名单。 */
    private void collectAudio(File dir, int depth, List<Song> out, Set<String> hidden) {
        if (dir == null || !dir.isDirectory() || depth > LocalScanScope.MAX_DEPTH
                || out.size() >= LocalScanScope.MAX_TRACKS) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child == null || out.size() >= LocalScanScope.MAX_TRACKS) {
                return;
            }
            if (child.isDirectory()) {
                if (LocalScanScope.isDownloadSubtree(child)) {
                    continue;
                }
                collectAudio(child, depth + 1, out, hidden);
            } else if (DownloadManager.isAudioFile(child)) {
                if (!hidden.contains(child.getAbsolutePath())) {
                    out.add(fileToSong(child));
                }
            }
        }
    }

    /** 从文件名猜出的「曲名 / 曲师」。 */
    static final class NameGuess {
        final String name;
        final String artist;

        NameGuess(String name, String artist) {
            this.name = name;
            this.artist = artist;
        }
    }

    /** 按「曲名 - 曲师」拆文件名；拆不出曲师时用所在目录名兜底。 */
    static NameGuess guessFromFileName(File file) {
        String base = DownloadManager.stripAudioExtension(file.getName());
        String name = base;
        String artist = "";
        int separator = base.indexOf(" - ");
        if (separator > 0) {
            name = base.substring(0, separator);
            artist = base.substring(separator + 3);
        } else {
            File parent = file.getParentFile();
            if (parent != null && !parent.getName().isEmpty()) {
                artist = parent.getName();
            }
        }
        return new NameGuess(name, artist);
    }

    /** 先用文件名建 Song；命中索引则顺手贴好标签（不开 MediaMetadataRetriever）。 */
    private Song fileToSong(File file) {
        NameGuess guess = guessFromFileName(file);
        String path = file.getAbsolutePath();
        foundPaths.add(path);
        Song song = new Song(LocalTracks.idForPath(path),
                LocalTracks.safeName(guess.name), guess.artist, "");
        song.setUrl(path);
        song.setForceLocalPlayback(true);
        if (applyCachedMetadata(song, file)) {
            return song;
        }
        missPaths.add(path);
        cacheMisses++;
        return song;
    }

    private boolean applyCachedMetadata(Song song, File file) {
        LocalMetadataIndex.Entry e = LocalMetadataIndex.get(appContext, file);
        if (e == null) {
            return false;
        }
        applyEntry(song, e, true);
        return true;
    }

    /** 把索引条目的标签/封面贴到 song 上。 */
    private void applyEntry(Song song, LocalMetadataIndex.Entry e, boolean verifyCover) {
        if (!TextUtils.isEmpty(e.title)) {
            song.setName(LocalTracks.safeName(e.title));
        }
        if (!TextUtils.isEmpty(e.artist)) {
            song.setArtist(e.artist);
        }
        if (e.hasCover) {
            File cover = LocalMetadata.cachedCoverFile(appContext, e.path);
            if (cover != null && (!verifyCover || cover.isFile())) {
                song.setCoverUrl("file://" + cover.getAbsolutePath());
            }
        }
    }

    /** 阶段③：只为没命中缓存的文件读标签，边跑边分批回调。 */
    private void readMetadata(List<Song> songs, int total) {
        boolean changed = false;
        int processed = 0;
        int readFromDisk = 0;
        for (Song song : songs) {
            if (cancelled()) {
                return;
            }
            processed++;
            if (!missPaths.contains(song.getUrl())) {
                continue;
            }
            File audio = new File(song.getUrl());
            LocalMetadata.Info info = LocalMetadataIndex.infoFor(appContext, audio);
            if (info == null) {
                info = LocalMetadata.read(appContext, audio);
                LocalMetadataIndex.put(appContext, audio, info);
                readFromDisk++;
            }
            NameGuess guess = guessFromFileName(audio);
            String name = info.hasTitle() ? info.title : guess.name;
            String artist = info.hasArtist() ? info.artist : guess.artist;
            String newName = LocalTracks.safeName(name);
            String newArtist = artist == null ? "" : artist;
            if (!newName.equals(song.getName())) {
                song.setName(newName);
                changed = true;
            }
            if (!newArtist.equals(song.getArtist())) {
                song.setArtist(newArtist);
                changed = true;
            }
            if (info.cover != null && !info.cover.getAbsolutePath().isEmpty()) {
                String coverUrl = "file://" + info.cover.getAbsolutePath();
                if (!coverUrl.equals(song.getCoverUrl())) {
                    song.setCoverUrl(coverUrl);
                    changed = true;
                }
            }
            if (processed % METADATA_NOTIFY_EVERY == 0 || processed == total) {
                final int p = processed;
                final boolean readingFromDisk = readFromDisk > 0;
                mainHandler.post(() -> {
                    if (cancelled()) {
                        return;
                    }
                    callback.onMetadataProgress(p, total, readingFromDisk);
                });
            }
        }
        final boolean changedFinal = changed;
        mainHandler.post(() -> {
            if (cancelled()) {
                return;
            }
            callback.onFinished(changedFinal);
            // 扫描会为每个文件落一份封面缓存，扫完把总量压回上限（磁盘 IO，放后台）
            new Thread(() -> LocalMetadata.trimCoverCache(appContext)).start();
        });
    }

    private boolean cancelled() {
        return callback == null || callback.isCancelled();
    }
}
