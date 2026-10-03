package com.qinghe.music163pro.util;

import android.content.Context;

import com.qinghe.music163pro.model.Song;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 播放统计：累计播放时长、播放次数、最常听的歌曲。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>不额外加定时器。</b>时长靠「会话起止时间」累计 —— 播放开始时记下起点，
 *       暂停 / 停止 / 切歌时把这一段补进总时长。既有代码里已经有 500ms 的定时器
 *       和进度条刷新，再加一个纯属浪费电。</li>
 *   <li><b>挂载点是两个已有的私有回调</b>：{@code notifySongChanged} 与
 *       {@code notifyPlayStateChanged}。它们本来就是「歌曲变了」「播放状态变了」的唯一出口，
 *       在这里累计不会漏事件，也不用往播放逻辑里插代码。</li>
 *   <li>数据落在**应用私有目录**（不需要存储权限，也不会占用户卡里的空间）；
 *       写入按 {@link #SAVE_THROTTLE_MS} 节流，避免频繁切歌时反复写盘。</li>
 *   <li>只按歌曲 id 记录（id≤0 的本地文件没有稳定身份，只进总时长不记单曲）。</li>
 * </ul>
 */
public final class PlaybackStats {

    private static final String FILE_NAME = "playback_stats.json";
    /** 最多保留多少首的单曲统计，防止长期使用后文件无限膨胀。 */
    private static final int MAX_TRACKED_SONGS = 300;
    /** 写盘节流，避免频繁切歌时反复 IO。 */
    private static final long SAVE_THROTTLE_MS = 5000L;

    private static final Object LOCK = new Object();

    private static boolean loaded;
    private static long totalPlayMs;
    private static int totalPlayCount;
    private static final Map<Long, MutableTrack> tracks = new LinkedHashMap<>();

    /**
     * 当前这次播放的起点。
     *
     * <p>用 {@link #SESSION_IDLE} 表示「没在计时」，**不要用 0** —— 这里存的是
     * epoch 毫秒，拿 0 当哨兵值等于把「没计时」和「时钟异常返回 0」混为一谈。</p>
     */
    private static final long SESSION_IDLE = -1L;
    private static long sessionStartMs = SESSION_IDLE;
    private static long lastSaveMs;

    private PlaybackStats() {
    }

    /** 单曲统计的只读快照。 */
    public static final class TrackStat {
        public final long songId;
        public final String name;
        public final String artist;
        public final int playCount;
        /** 歌名译名（可能为空），供「原名（译名）」显示。 */
        public final String transName;

        TrackStat(long songId, String name, String artist, int playCount, String transName) {
            this.songId = songId;
            this.name = name;
            this.artist = artist;
            this.playCount = playCount;
            this.transName = transName == null ? "" : transName;
        }
    }

    /** 汇总快照。 */
    public static final class Summary {
        public final long totalPlayMs;
        public final int totalPlayCount;
        public final int trackedSongCount;

        Summary(long totalPlayMs, int totalPlayCount, int trackedSongCount) {
            this.totalPlayMs = totalPlayMs;
            this.totalPlayCount = totalPlayCount;
            this.trackedSongCount = trackedSongCount;
        }
    }

    private static final class MutableTrack {
        /** 歌名译名（网易云 tns），用于统计页显示「原名（译名）」。 */
        final long songId;
        String name;
        String artist;
        int playCount;

        String transName;

        MutableTrack(long songId, String name, String artist) {
            this.songId = songId;
            this.name = name;
            this.artist = artist;
        }
    }

    // ==================== 记录 ====================

    /** 一首歌开始播放。会把上一段的时间先结算掉，再给这首的播放次数 +1。 */
    public static void onSongStarted(Context context, Song song) {
        if (song == null) {
            return;
        }
        synchronized (LOCK) {
            ensureLoaded(context);
            flushSessionLocked();

            totalPlayCount++;
            if (song.getId() > 0) {
                MutableTrack track = tracks.get(song.getId());
                if (track == null) {
                    track = new MutableTrack(song.getId(), song.getName(), song.getArtist());
                    track.transName = song.getTransName();
                    tracks.put(song.getId(), track);
                    trimIfNeededLocked();
                } else {
                    // 曲名 / 曲师可能被编辑过，以最新为准
                    track.name = song.getName();
                    track.transName = song.getTransName();
                    track.artist = song.getArtist();
                }
                track.playCount++;
            }

            sessionStartMs = System.currentTimeMillis();
            saveLocked(context, false);
        }
    }

    /** 播放状态变化。开始播就起表，暂停 / 停止就结算这一段。 */
    public static void onPlayStateChanged(Context context, boolean playing) {
        synchronized (LOCK) {
            ensureLoaded(context);
            if (playing) {
                if (sessionStartMs < 0) {
                    sessionStartMs = System.currentTimeMillis();
                }
            } else {
                flushSessionLocked();
                saveLocked(context, true);
            }
        }
    }

    /** 结算当前这一段播放时长。 */
    private static void flushSessionLocked() {
        if (sessionStartMs < 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - sessionStartMs;
        // 明显异常的间隔（设备休眠、时间跳变）不计入
        if (elapsed > 0 && elapsed < 12 * 60 * 60 * 1000L) {
            totalPlayMs += elapsed;
        }
        sessionStartMs = SESSION_IDLE;
    }

    // ==================== 读取 ====================

    public static Summary getSummary(Context context) {
        synchronized (LOCK) {
            ensureLoaded(context);
            return new Summary(totalPlayMs + currentSessionElapsedLocked(),
                    totalPlayCount, tracks.size());
        }
    }

    /** 按播放次数倒序取前 N 首。 */
    public static List<TrackStat> getTopTracks(Context context, int limit) {
        synchronized (LOCK) {
            ensureLoaded(context);
            List<TrackStat> all = new ArrayList<>();
            for (MutableTrack track : tracks.values()) {
                all.add(new TrackStat(track.songId, track.name, track.artist, track.playCount,
                        track.transName));
            }
            Collections.sort(all, (left, right) -> Integer.compare(right.playCount, left.playCount));
            if (limit > 0 && all.size() > limit) {
                return new ArrayList<>(all.subList(0, limit));
            }
            return all;
        }
    }

    private static long currentSessionElapsedLocked() {
        if (sessionStartMs < 0) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - sessionStartMs;
        return elapsed > 0 ? elapsed : 0;
    }

    /** 清空全部统计。 */
    public static void clear(Context context) {
        synchronized (LOCK) {
            ensureLoaded(context);
            totalPlayMs = 0;
            totalPlayCount = 0;
            tracks.clear();
            sessionStartMs = SESSION_IDLE;
            saveLocked(context, true);
        }
    }

    /** 把毫秒格式化成「1 小时 23 分」。 */
    public static String formatDuration(long ms) {
        long totalMinutes = ms / 60000;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分";
        }
        return minutes + " 分";
    }

    // ==================== 持久化 ====================

    private static void trimIfNeededLocked() {
        while (tracks.size() > MAX_TRACKED_SONGS) {
            // LinkedHashMap 保序，直接丢最早插入的一条（近似「最久没更新的」）
            Long oldestKey = null;
            int smallest = Integer.MAX_VALUE;
            for (Map.Entry<Long, MutableTrack> entry : tracks.entrySet()) {
                if (entry.getValue().playCount < smallest) {
                    smallest = entry.getValue().playCount;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                return;
            }
            tracks.remove(oldestKey);
        }
    }

    private static File getFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static void ensureLoaded(Context context) {
        if (loaded || context == null) {
            return;
        }
        loaded = true;
        try {
            File file = getFile(context);
            if (!file.exists()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (FileInputStream fis = new FileInputStream(file);
                 InputStreamReader reader = new InputStreamReader(fis, "UTF-8")) {
                char[] buf = new char[1024];
                int len;
                while ((len = reader.read(buf)) != -1) {
                    sb.append(buf, 0, len);
                }
            }
            JSONObject json = new JSONObject(sb.toString());
            totalPlayMs = json.optLong("totalPlayMs", 0);
            totalPlayCount = json.optInt("totalPlayCount", 0);
            JSONArray array = json.optJSONArray("tracks");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    long id = item.optLong("id", 0);
                    if (id <= 0) {
                        continue;
                    }
                    MutableTrack track = new MutableTrack(id,
                            item.optString("name", ""), item.optString("artist", ""));
                    track.transName = item.optString("transName", "");
                    track.playCount = item.optInt("playCount", 0);
                    tracks.put(id, track);
                }
            }
        } catch (Exception e) {
            MusicLog.w("PlaybackStats", "读取播放统计失败", e);
            // 读坏了就从空开始，不要让统计把整个功能带崩
            totalPlayMs = 0;
            totalPlayCount = 0;
            tracks.clear();
        }
    }

    private static void saveLocked(Context context, boolean force) {
        if (context == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastSaveMs < SAVE_THROTTLE_MS) {
            return;
        }
        lastSaveMs = now;
        try {
            JSONObject json = new JSONObject();
            json.put("totalPlayMs", totalPlayMs + currentSessionElapsedLocked());
            json.put("totalPlayCount", totalPlayCount);
            JSONArray array = new JSONArray();
            for (MutableTrack track : tracks.values()) {
                JSONObject item = new JSONObject();
                item.put("id", track.songId);
                item.put("name", track.name);
                item.put("transName", track.transName == null ? "" : track.transName);
                item.put("artist", track.artist);
                item.put("playCount", track.playCount);
                array.put(item);
            }
            json.put("tracks", array);

            try (FileOutputStream fos = new FileOutputStream(getFile(context));
                 OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8")) {
                writer.write(json.toString());
                writer.flush();
            }
        } catch (Exception e) {
            MusicLog.w("PlaybackStats", "保存播放统计失败", e);
        }
    }
}
