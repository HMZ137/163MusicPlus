package com.qinghe.music163pro.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.qinghe.music163pro.model.AlbumInfo;
import com.qinghe.music163pro.model.CloudItem;
import com.qinghe.music163pro.model.MvInfo;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.model.SoundInfo;
import com.qinghe.music163pro.util.MusicLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Music API helper that calls NetEase Cloud Music APIs directly
 * using weapi encryption (ported from NeteaseCloudMusicApiBackup).
 * No external API server needed.
 */
public class MusicApiHelper {

    private static final String TAG = "MusicApiHelper";

    private static final String WEAPI_BASE = "https://music.163.com/weapi";
    private static final String DOMAIN = "https://music.163.com";
    private static final String API_DOMAIN = "https://interface.music.163.com";
    private static final String SONG_COMMENT_THREAD_PREFIX = "R_SO_4_";
    private static final String CLOUD_UPLOAD_BUCKET = "jd-musicrep-privatecloud-audio-public";
    private static final String DEFAULT_CLOUD_BITRATE = "999000";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0";

    // Mobile User-Agent for SMS login to avoid "环境不安全" error
    private static final String MOBILE_USER_AGENT =
            "NeteaseMusic/9.0.90 (Android 13; Pixel 6)";

    // Device/version info to prevent "version too old" errors
    private static final String OS_VER = "16.2";
    private static final String APP_VER = "9.0.90";
    private static final String VERSION_CODE = "140";
    private static final String CHANNEL = "distribution";
    private static final String OS_TYPE = "iPhone OS";
    private static String deviceId = UUID.randomUUID().toString().replace("-", "");

    /**
     * Set a persistent device ID (should be called at app startup from SharedPreferences).
     */
    public static void setDeviceId(String id) {
        if (id != null && !id.isEmpty()) {
            deviceId = id;
        }
    }

    public static String getDeviceId() {
        return deviceId;
    }

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 15000;

    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface SearchCallback {
        void onResult(List<Song> songs);
        void onError(String message);
    }

    public interface UrlCallback {
        void onResult(String url);
        void onError(String message);
    }

    public interface QrKeyCallback {
        void onResult(String key);
        void onError(String message);
    }

    public interface QrCreateCallback {
        /** Returns the QR URL (to be encoded as QR image by the caller) */
        void onResult(String qrUrl);
        void onError(String message);
    }

    public interface QrCheckCallback {
        void onResult(int code, String message, String cookie);
        void onError(String message);
    }

    public interface SmsCallback {
        void onResult(boolean success, String message);
        void onError(String message);
    }

    public interface LoginCallback {
        void onResult(int code, String message, String cookie);
        void onError(String message);
        /** Called when server requires CAPTCHA verification (code -462). Open verifyUrl in a WebView. */
        default void onVerificationRequired(String verifyUrl) {}
    }

    public interface LyricsCallback {
        void onResult(String lrcText);
        void onError(String message);
    }

    public interface CloudFavoritesCallback {
        void onResult(List<Song> songs);
        void onError(String message);
    }

    public interface LikeCallback {
        void onResult(boolean success);
        void onError(String message);
    }

    public interface CloudLikedIdsCallback {
        void onResult(java.util.Set<Long> ids);
        void onError(String message);
    }

    public interface AccountCallback {
        void onResult(JSONObject accountJson);
        void onError(String message);
    }

    public interface VipInfoCallback {
        void onResult(JSONObject vipJson);
        void onError(String message);
    }

    public interface TopListCallback {
        void onResult(JSONArray listArray);
        void onError(String message);
    }

    public interface PlaylistDetailCallback {
        void onResult(List<Song> songs);
        void onError(String message);
    }

    public interface PersonalFMCallback {
        void onResult(List<Song> songs);
        void onError(String message);
    }

    public interface RecognitionCallback {
        void onResult(List<Song> songs);
        void onError(String message);
    }

    public interface SongWikiCallback {
        void onResult(JSONObject wikiJson);
        void onError(String message);
    }

    public interface SongDetailCallback {
        void onResult(JSONObject songDetail);
        void onError(String message);
    }

    public static class ChorusInfo {
        public final long startMs;
        public final long endMs;

        public ChorusInfo(long startMs, long endMs) {
            this.startMs = Math.max(0L, startMs);
            this.endMs = Math.max(this.startMs, endMs);
        }
    }

    public interface ChorusCallback {
        void onResult(ChorusInfo chorusInfo);
        void onError(String message);
    }

    /**
     * Quality levels in ascending order (matches NetEase API level strings).
     * The index of a level in this array is its "rank" for comparison.
     */
    public static final String[] QUALITY_LEVELS_ORDERED =
            {"none", "standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "sky", "jymaster"};

    /** Returns the rank index of a quality level string, or 0 for null/"none", -1 for unknown. */
    public static int qualityLevelRank(String level) {
        if (level == null) return 0; // null maps to "none" rank (0)
        for (int i = 0; i < QUALITY_LEVELS_ORDERED.length; i++) {
            if (QUALITY_LEVELS_ORDERED[i].equals(level)) return i;
        }
        return -1;
    }

    /**
     * Holds quality privilege info for a single song obtained from /api/v3/song/detail.
     * <ul>
     *   <li>{@code flLevel} – max quality playable for free (no VIP login)</li>
     *   <li>{@code plLevel} – max quality playable with the current login session</li>
     *   <li>{@code dlLevel} – max quality downloadable with the current login session</li>
     *   <li>{@code maxBrLevel} – highest quality the song itself has been encoded at</li>
     * </ul>
     */
    public static class SongQualityInfo {
        public final String flLevel;    // free listening max level
        public final String plLevel;    // paid/vip listening max level
        public final String dlLevel;    // download max level
        public final String maxBrLevel; // song's own max quality

        public SongQualityInfo(String flLevel, String plLevel, String dlLevel, String maxBrLevel) {
            this.flLevel = flLevel    != null ? flLevel    : "standard";
            this.plLevel = plLevel    != null ? plLevel    : "exhigh";
            this.dlLevel = dlLevel    != null ? dlLevel    : "none";
            this.maxBrLevel = maxBrLevel != null ? maxBrLevel : "exhigh";
        }

        /** Whether a given quality level is available at all for this song. */
        public boolean isLevelAvailable(String level) {
            return qualityLevelRank(level) <= qualityLevelRank(maxBrLevel);
        }

        /**
         * Returns the "tier" required for a level:
         *  "free" = playable without VIP, "vip" = needs VIP/login, "unavailable" = song doesn't have it.
         */
        public String getTier(String level) {
            int rank = qualityLevelRank(level);
            if (rank < 0) return "unavailable";
            if (rank > qualityLevelRank(maxBrLevel)) return "unavailable";
            if (rank <= qualityLevelRank(flLevel)) return "free";
            return "vip";
        }
    }

    public interface SongQualityCallback {
        void onResult(SongQualityInfo info);
        void onError(String message);
    }

    public interface ArtistDescCallback {
        void onResult(String briefDesc, JSONArray introduction);
        void onError(String message);
    }

    public interface CommentCallback {
        void onResult(JSONObject commentData);
        void onError(String message);
    }

    public interface CommentActionCallback {
        void onResult(boolean success);
        void onError(String message);
    }

    public interface LyricsFullCallback {
        /**
         * @param lrcText     原文歌词
         * @param tlyricText  翻译歌词，可能为空
         * @param romalrcText 音译 / 罗马音歌词（日文歌的发音），可能为空
         */
        void onResult(String lrcText, String tlyricText, String romalrcText);
        void onError(String message);
    }

    public interface SimiSongCallback {
        void onResult(JSONArray songs);
        void onError(String message);
    }

    public interface SimiPlaylistCallback {
        void onResult(JSONArray playlists);
        void onError(String message);
    }

    public interface SearchPlaylistCallback {
        void onResult(List<PlaylistInfo> playlists);
        void onError(String message);
    }

    public interface SearchMvCallback {
        void onResult(List<MvInfo> mvs);
        void onError(String message);
    }

    /** S2：专辑搜索结果回调（cloudsearch type=10）。 */
    public interface SearchAlbumCallback {
        void onResult(List<AlbumInfo> albums);
        void onError(String message);
    }

    /** S2：声音（电台节目）搜索结果回调（cloudsearch type=2000）。 */
    public interface SearchSoundCallback {
        void onResult(List<SoundInfo> sounds);
        void onError(String message);
    }

    public interface MvDetailCallback {
        void onResult(JSONObject mvDetail);
        void onError(String message);
    }

    public interface UserPlaylistsCallback {
        void onResult(List<PlaylistInfo> playlists);
        void onError(String message);
    }

    public interface PlaylistActionCallback {
        void onResult(boolean success);
        void onError(String message);
    }

    public interface PlaylistCreateCallback {
        void onResult(long playlistId, String name);
        void onError(String message);
    }

    /** Callback for lightweight playlist metadata fetch */
    public interface PlaylistMetaCallback {
        void onResult(int trackCount, String creator, long creatorUserId, int specialType, boolean subscribed);
        void onError(String message);
    }

    /** Callback for lightweight playlist metadata fetch, also returns coverUrl */
    public interface PlaylistMetaWithCoverCallback {
        void onResult(int trackCount, String creator, long creatorUserId, int specialType, boolean subscribed, String coverUrl);
        void onError(String message);
    }

    /** Callback for playlist detail that also returns metadata */
    public interface PlaylistDetailWithMetaCallback {
        void onResult(List<Song> songs, int trackCount, String creator, long creatorUserId, int specialType, boolean subscribed);
        void onError(String message);
    }

    public interface DailyRecommendPlaylistCallback {
        void onResult(PlaylistInfo playlist);
        void onError(String message);
    }

    public interface AlbumDetailCallback {
        void onResult(String albumName, String coverUrl, List<Song> songs);
        void onError(String message);
    }

    public interface BatchSongDetailsCallback {
        void onResult(java.util.Map<Long, Song> songMap);
        void onError(String message);
    }

    public interface CloudItemsCallback {
        void onResult(List<CloudItem> items);
        void onError(String message);
    }

    public interface CloudItemCallback {
        void onResult(CloudItem item);
        void onError(String message);
    }

    public interface UploadProgressCallback {
        void onProgress(int progress, String message);
        void onSuccess(String message);
        void onError(String message);
    }

    // ==================== Search ====================

    public static void searchSongs(String keyword, String cookie, SearchCallback callback) {
        searchSongs(keyword, 0, cookie, callback);
    }

    public static void searchSongs(String keyword, int offset, String cookie, SearchCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "搜索歌曲", "keyword=" + keyword + " offset=" + offset);
                List<Song> songs = searchDirect(keyword, offset, cookie);
                MusicLog.d(TAG, "搜索结果: " + songs.size() + " 首");
                mainHandler.post(() -> callback.onResult(songs));
            } catch (Exception e) {
                MusicLog.w(TAG, "搜索失败: " + keyword, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * 按歌曲 ID 取详情（2026-11-28 新增）：搜索框里输入纯 ID / 网易云链接时，
     * 用它把这首歌放到搜索结果第一位。
     *
     * <p>走 {@code /api/v3/song/detail}，字段与 {@link #searchDirect} 的 cloudsearch
     * 结果同构（{@code ar}/{@code al}），便于直接混进「搜索」列表。</p>
     */
    public static void getSongById(long songId, String cookie, SearchCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "按ID取歌", "id=" + songId);
                JSONObject data = new JSONObject();
                data.put("c", "[{\"id\":" + songId + "}]");
                data.put("csrf_token", extractCsrfToken(cookie));
                String response = weapiPost("/api/v3/song/detail", data.toString(), cookie);

                List<Song> songs = new ArrayList<>();
                JSONObject json = new JSONObject(response);
                JSONArray arr = json.optJSONArray("songs");
                if (arr != null && arr.length() > 0) {
                    JSONObject s = arr.getJSONObject(0);
                    String name = s.optString("name", "");
                    String artist = "";
                    JSONArray ar = s.optJSONArray("ar");
                    if (ar != null && ar.length() > 0) {
                        artist = ar.getJSONObject(0).optString("name", "");
                    }
                    String album = "";
                    String coverUrl = "";
                    JSONObject al = s.optJSONObject("al");
                    if (al != null) {
                        album = al.optString("name", "");
                        coverUrl = al.optString("picUrl", "");
                    }
                    Song song = new Song(s.optLong("id", songId), name, artist, album);
                    if (!coverUrl.isEmpty()) {
                        song.setCoverUrl(coverUrl);
                    }
                    songs.add(song);
                }
                mainHandler.post(() -> callback.onResult(songs));
            } catch (Exception e) {
                MusicLog.w(TAG, "按ID取歌失败: " + songId, e);
                mainHandler.post(() -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Search via weapi/cloudsearch/pc (same as NeteaseCloudMusicApiBackup module/cloudsearch.js)
     */
    private static List<Song> searchDirect(String keyword, int offset, String cookie) throws Exception {
        JSONObject data = new JSONObject();
        data.put("s", keyword);
        data.put("type", 1);      // 1 = songs
        data.put("limit", 20);
        data.put("offset", offset);
        data.put("total", true);

        String csrfToken = extractCsrfToken(cookie);
        data.put("csrf_token", csrfToken);

        String response = weapiPost("/api/cloudsearch/pc", data.toString(), cookie);
        return parseCloudsearchResult(response);
    }

    private static List<Song> parseCloudsearchResult(String response) throws Exception {
        JSONObject json = new JSONObject(response);
        List<Song> songs = new ArrayList<>();
        JSONObject result = json.optJSONObject("result");
        if (result != null) {
            JSONArray songsArray = result.optJSONArray("songs");
            if (songsArray != null) {
                for (int i = 0; i < songsArray.length(); i++) {
                    JSONObject s = songsArray.getJSONObject(i);
                    long id = s.getLong("id");
                    String name = s.getString("name");
                    String artist = "";
                    // cloudsearch uses "ar" for artists
                    JSONArray ar = s.optJSONArray("ar");
                    if (ar != null && ar.length() > 0) {
                        artist = ar.getJSONObject(0).optString("name", "");
                    }
                    String album = "";
                    String coverUrl = "";
                    // cloudsearch uses "al" for album
                    JSONObject al = s.optJSONObject("al");
                    if (al != null) {
                        album = al.optString("name", "");
                        coverUrl = al.optString("picUrl", "");
                    }
                    Song song = new Song(id, name, artist, album);
                    song.setTransName(pickTransName(s));
                    song.setFee(s.optInt("fee", 0));
                    if (!coverUrl.isEmpty()) {
                        song.setCoverUrl(coverUrl);
                    }
                    songs.add(song);
                }
            }
        }
        return songs;
    }

    // ==================== Search Playlists ====================

    /**
     * Search playlists via cloudsearch (type=1000).
     * Supports pagination with offset.
     */
    public static void searchPlaylists(String keyword, int offset, String cookie, SearchPlaylistCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "搜索歌单", "keyword=" + keyword + " offset=" + offset);
                JSONObject data = new JSONObject();
                data.put("s", keyword);
                data.put("type", 1000);  // 1000 = playlists
                data.put("limit", 20);
                data.put("offset", offset);
                data.put("total", true);

                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/cloudsearch/pc", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                List<PlaylistInfo> playlists = new ArrayList<>();
                JSONObject result = json.optJSONObject("result");
                if (result != null) {
                    JSONArray playlistsArray = result.optJSONArray("playlists");
                    if (playlistsArray != null) {
                        for (int i = 0; i < playlistsArray.length(); i++) {
                            JSONObject p = playlistsArray.getJSONObject(i);
                            long id = p.getLong("id");
                            String name = p.getString("name");
                            int trackCount = p.optInt("trackCount", 0);
                            String creator = "";
                            long creatorUserId = 0;
                            JSONObject creatorObj = p.optJSONObject("creator");
                            if (creatorObj != null) {
                                creator = creatorObj.optString("nickname", "");
                                creatorUserId = creatorObj.optLong("userId", 0);
                            }
                            int specialType = p.optInt("specialType", 0);
                            boolean subscribed = p.optBoolean("subscribed", false);
                            String coverUrl = pickFirstNonEmpty(p, "coverImgUrl", "picUrl", "cover");
                            PlaylistInfo playlistInfo = new PlaylistInfo(id, name, trackCount, creator,
                                    creatorUserId, subscribed, String.valueOf(specialType));
                            if (!coverUrl.isEmpty()) {
                                playlistInfo.setCoverUrl(coverUrl);
                            }
                            playlists.add(playlistInfo);
                        }
                    }
                }
                MusicLog.d(TAG, "搜索歌单结果: " + playlists.size() + " 个");
                mainHandler.post(() -> callback.onResult(playlists));
            } catch (Exception e) {
                MusicLog.w(TAG, "搜索歌单失败: " + keyword, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Search MVs ====================

    /**
     * Search MVs via cloudsearch (type=1004).
     * Supports pagination with offset.
     */
    public static void searchMvs(String keyword, int offset, String cookie, SearchMvCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "搜索MV", "keyword=" + keyword + " offset=" + offset);
                JSONObject data = new JSONObject();
                data.put("s", keyword);
                data.put("type", 1004);
                data.put("limit", 20);
                data.put("offset", offset);
                data.put("total", true);

                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/cloudsearch/pc", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                List<MvInfo> mvs = new ArrayList<>();
                JSONObject result = json.optJSONObject("result");
                if (result != null) {
                    JSONArray mvsArray = result.optJSONArray("mvs");
                    if (mvsArray == null) {
                        mvsArray = result.optJSONArray("mvps");
                    }
                    if (mvsArray != null) {
                        for (int i = 0; i < mvsArray.length(); i++) {
                            JSONObject mv = mvsArray.optJSONObject(i);
                            if (mv == null) {
                                continue;
                            }
                            long id = mv.optLong("id", mv.optLong("mvid", 0));
                            String name = mv.optString("name", "");
                            String artist = mv.optString("artistName", "");
                            if (artist.isEmpty()) {
                                artist = joinArtistNames(mv.optJSONArray("artists"));
                            }
                            String coverUrl = pickFirstNonEmpty(mv, "cover", "coverUrl", "imgurl16v9", "picUrl");
                            long duration = mv.optLong("duration", mv.optLong("durationms", 0));
                            long playCount = mv.optLong("playCount", 0);
                            mvs.add(new MvInfo(id, name, artist, coverUrl, duration, playCount));
                        }
                    }
                }
                MusicLog.d(TAG, "搜索MV结果: " + mvs.size() + " 个");
                mainHandler.post(() -> callback.onResult(mvs));
            } catch (Exception e) {
                MusicLog.w(TAG, "搜索MV失败: " + keyword, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== S2：Search Albums / Sounds ====================

    /**
     * S2（任务 5-1）：搜索专辑（cloudsearch type=10）。
     *
     * <p>与 {@link #searchPlaylists} / {@link #searchMvs} 同一套请求范式，只换 {@code type}；
     * 结果由搜索页「歌单」标签与歌单混排展示，点击进 {@code AlbumDetailActivity}。</p>
     */
    public static void searchAlbums(String keyword, int offset, String cookie,
                                    SearchAlbumCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "搜索专辑", "keyword=" + keyword + " offset=" + offset);
                JSONObject data = new JSONObject();
                data.put("s", keyword);
                data.put("type", 10);   // 10 = albums
                data.put("limit", 20);
                data.put("offset", offset);
                data.put("total", true);
                data.put("csrf_token", extractCsrfToken(cookie));

                String response = weapiPost("/api/cloudsearch/pc", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                List<AlbumInfo> albums = new ArrayList<>();
                JSONObject result = json.optJSONObject("result");
                if (result != null) {
                    JSONArray arr = result.optJSONArray("albums");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject a = arr.optJSONObject(i);
                            if (a == null) {
                                continue;
                            }
                            long id = a.optLong("id", 0);
                            String name = a.optString("name", "");
                            String artist = "";
                            JSONObject artistObj = a.optJSONObject("artist");
                            if (artistObj != null) {
                                artist = artistObj.optString("name", "");
                            }
                            if (artist.isEmpty()) {
                                artist = joinArtistNames(a.optJSONArray("artists"));
                            }
                            String coverUrl = pickFirstNonEmpty(a, "picUrl", "coverUrl", "cover");
                            int trackCount = a.optInt("size", a.optInt("trackCount", 0));
                            albums.add(new AlbumInfo(id, name, artist, coverUrl, trackCount));
                        }
                    }
                }
                MusicLog.d(TAG, "搜索专辑结果: " + albums.size() + " 个");
                mainHandler.post(() -> callback.onResult(albums));
            } catch (Exception e) {
                MusicLog.w(TAG, "搜索专辑失败: " + keyword, e);
                mainHandler.post(() -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * S2（任务 5-2）：搜索声音（cloudsearch type=2000，电台节目）。
     *
     * <p>声音条目字段随接口版本略有差异，这里对 {@code sounds / djprograms / programs}
     * 三种数组名都做兼容；主歌信息取 {@code mainSong}，点击时用它构造可播放的 {@link Song}。</p>
     */
    public static void searchSounds(String keyword, int offset, String cookie,
                                    SearchSoundCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "搜索声音", "keyword=" + keyword + " offset=" + offset);
                JSONObject data = new JSONObject();
                data.put("s", keyword);
                // 需求文档写的是 type=2000，但实测本机该 type 在 /api/cloudsearch/pc 上返回
                // 空 result（{"result":{}}，0 条）；cloudsearch 的声音（电台节目）类型是 1018。
                // 这里按实测取 1018，注释保留差异原因，避免下次又踩回 2000。
                data.put("type", 1018);   // 1018 = 声音（电台节目 djprogram）
                data.put("limit", 20);
                data.put("offset", offset);
                data.put("total", true);
                data.put("csrf_token", extractCsrfToken(cookie));

                String response = weapiPost("/api/cloudsearch/pc", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                List<SoundInfo> sounds = new ArrayList<>();
                JSONObject result = json.optJSONObject("result");
                if (result != null) {
                    JSONArray arr = result.optJSONArray("sounds");
                    if (arr == null) {
                        arr = result.optJSONArray("djprograms");
                    }
                    if (arr == null) {
                        arr = result.optJSONArray("programs");
                    }
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject sObj = arr.optJSONObject(i);
                            if (sObj == null) {
                                continue;
                            }
                            long id = sObj.optLong("id", 0);
                            String name = sObj.optString("name", "");
                            String coverUrl = pickFirstNonEmpty(sObj, "coverUrl", "picUrl", "cover");
                            String djName = "";
                            JSONObject dj = sObj.optJSONObject("dj");
                            if (dj != null) {
                                djName = dj.optString("nickname", "");
                            }
                            long mainSongId = 0;
                            String mainSongName = "";
                            String mainSongArtist = "";
                            JSONObject mainSong = sObj.optJSONObject("mainSong");
                            if (mainSong != null) {
                                mainSongId = mainSong.optLong("id", 0);
                                mainSongName = mainSong.optString("name", "");
                                mainSongArtist = joinArtistNames(mainSong.optJSONArray("artists"));
                                if (coverUrl.isEmpty()) {
                                    JSONObject al = mainSong.optJSONObject("al");
                                    if (al != null) {
                                        coverUrl = al.optString("picUrl", "");
                                    }
                                }
                            }
                            long duration = sObj.optLong("duration", 0);
                            sounds.add(new SoundInfo(id, name, coverUrl, djName,
                                    mainSongId, mainSongName, mainSongArtist, duration));
                        }
                    } else {
                        // 字段名不匹配时把 code 与 result 打出来（截断），便于按实测调整
                        String raw = result.toString();
                        MusicLog.w(TAG, "搜索声音无已知数组，code=" + json.optInt("code", -999)
                                + " result=" + (raw.length() > 300 ? raw.substring(0, 300) : raw));
                    }
                }
                MusicLog.d(TAG, "搜索声音结果: " + sounds.size() + " 个");
                mainHandler.post(() -> callback.onResult(sounds));
            } catch (Exception e) {
                MusicLog.w(TAG, "搜索声音失败: " + keyword, e);
                mainHandler.post(() -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Get MV detail by MV ID.
     * Endpoint: /api/v1/mv/detail
     */
    public static void getMvDetail(long mvId, String cookie, MvDetailCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取MV详情", "mvId=" + mvId);
                JSONObject data = new JSONObject();
                data.put("id", mvId);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/v1/mv/detail", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONObject detail = json.optJSONObject("data");
                if (detail == null) {
                    mainHandler.post(() -> callback.onError("获取MV详情失败"));
                    return;
                }
                mainHandler.post(() -> callback.onResult(detail));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取MV详情失败: " + mvId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Get MV playback URL by MV ID.
     * Endpoint: /api/song/enhance/play/mv/url
     */
    public static void getMvUrl(long mvId, String cookie, UrlCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取MV播放地址", "mvId=" + mvId);
                JSONObject data = new JSONObject();
                data.put("id", mvId);
                data.put("r", 480);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/song/enhance/play/mv/url", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONObject mvData = json.optJSONObject("data");
                String url = mvData != null ? mvData.optString("url", "") : "";
                if (url == null || url.isEmpty()) {
                    mainHandler.post(() -> callback.onError("未获取到MV地址"));
                    return;
                }
                mainHandler.post(() -> callback.onResult(url));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取MV播放地址失败: " + mvId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== User Playlists ====================

    /**
     * Get user's playlists from the cloud.
     * Returns all playlists (created + subscribed).
     */
    public static void getUserPlaylists(String cookie, UserPlaylistsCallback callback) {
        executor.execute(() -> {
            try {
                long uid = extractUidFromCookie(cookie);
                if (uid <= 0) {
                    mainHandler.post(() -> callback.onError("请先登录"));
                    return;
                }
                MusicLog.op(TAG, "获取用户歌单", "uid=" + uid);

                String csrfToken = extractCsrfToken(cookie);
                List<PlaylistInfo> allPlaylists = new ArrayList<>();

                // Fetch playlists in pages
                int offset = 0;
                int limit = 50;
                boolean hasMore = true;
                while (hasMore) {
                    JSONObject plData = new JSONObject();
                    plData.put("uid", uid);
                    plData.put("limit", limit);
                    plData.put("offset", offset);
                    plData.put("csrf_token", csrfToken);

                    String plResponse = weapiPost("/api/user/playlist", plData.toString(), cookie);
                    JSONObject plJson = new JSONObject(plResponse);
                    JSONArray playlists = plJson.optJSONArray("playlist");

                    if (playlists == null || playlists.length() == 0) {
                        break;
                    }

                    for (int i = 0; i < playlists.length(); i++) {
                        JSONObject p = playlists.getJSONObject(i);
                        long id = p.getLong("id");
                        String name = p.getString("name");
                        int trackCount = p.optInt("trackCount", 0);
                        String creator = "";
                        long creatorId = 0;
                        JSONObject creatorObj = p.optJSONObject("creator");
                        if (creatorObj != null) {
                            creator = creatorObj.optString("nickname", "");
                            creatorId = creatorObj.optLong("userId", 0);
                        }
                        boolean subscribed = p.optBoolean("subscribed", false);
                        int specialType = p.optInt("specialType", 0);
                        String coverUrl = p.optString("coverImgUrl", p.optString("picUrl", ""));
                        PlaylistInfo pl = new PlaylistInfo(id, name, trackCount, creator,
                                creatorId, subscribed, String.valueOf(specialType));
                        pl.setCoverUrl(coverUrl);
                        allPlaylists.add(pl);
                    }

                    hasMore = plJson.optBoolean("more", false);
                    offset += playlists.length();
                }

                MusicLog.d(TAG, "用户歌单: " + allPlaylists.size() + " 个");
                mainHandler.post(() -> callback.onResult(allPlaylists));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取用户歌单失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Song URL ====================

    /**
     * Quality levels supported by NetEase API, in descending order of preference for free fallback.
     * Levels above "exhigh" require VIP/SVIP membership.
     */
    public static final String[] FREE_FALLBACK_LEVELS = {"exhigh", "higher", "standard"};

    public static void getSongUrl(long songId, String cookie, UrlCallback callback) {
        getSongUrlWithQuality(songId, cookie, "exhigh", callback);
    }

    public static void getSongUrl(long songId, String cookie, boolean tryVip, UrlCallback callback) {
        getSongUrlWithQuality(songId, cookie, tryVip ? "exhigh" : "standard", callback);
    }

    /**
     * Fetch song URL with a preferred quality level. If the preferred level is unavailable,
     * falls back through exhigh → higher → standard → direct link.
     *
     * @param preferredLevel desired quality: standard / higher / exhigh / lossless / hires /
     *                       jyeffect / sky / jymaster
     */
    public static void getSongUrlWithQuality(long songId, String cookie,
                                             String preferredLevel, UrlCallback callback) {
        getSongUrlInfo(songId, cookie, preferredLevel, new SongUrlInfoCallback() {
            @Override
            public void onResult(String url, boolean trialOnly) {
                callback.onResult(url);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    public interface SongUrlInfoCallback {
        void onResult(String url, boolean trialOnly);

        void onError(String message);
    }

    /**
     * Fetch song URL with quality, also reporting whether the returned link is a
     * free-trial clip (freeTrialInfo present in the player/url response). Songs
     * unlocked by watching ads on the phone return a full link with no freeTrialInfo.
     *
     * <p><b>1016 合并要点</b>：即使 cookie 为空也要走一次 eapi —— 匿名 eapi 会返回
     * 试听片段，这个 trialOnly 标记正是后续路由到「手表网关（游客会话）」的判据；
     * 跳过它会直接落到底部直连兜底（trialOnly=false），网关就永远不会被调用。</p>
     */
    public static void getSongUrlInfo(long songId, String cookie,
                                      String preferredLevel, SongUrlInfoCallback callback) {
        executor.execute(() -> {
            try {
                String url = null;
                boolean trialOnly = false;
                MusicLog.op(TAG, "获取歌曲URL", "songId=" + songId + " level=" + preferredLevel);

                // Try preferred level first (may need VIP/SVIP for higher tiers).
                // Runs even with an EMPTY cookie on purpose: anonymous eapi
                // returns the trial clip, and that trial flag is what routes
                // the song through the watch gateway (guest session) below.
                // Skipping here would fall through to the direct-link fallback
                // with trialOnly=false and the gateway would never run.
                try {
                    JSONObject item = fetchSongUrlData(songId, cookie, preferredLevel);
                    url = extractUrlFromItem(item);
                    trialOnly = url != null && isTrialUrlItem(item);
                    if (url != null) {
                        MusicLog.d(TAG, "获取" + preferredLevel + " URL成功: " + songId
                                + (trialOnly ? " (试听)" : ""));
                    }
                } catch (Exception e) {
                    MusicLog.w(TAG, "eapi " + preferredLevel + " 失败: " + songId, e);
                }

                // Fallback through free quality levels
                if (url == null) {
                    for (String level : FREE_FALLBACK_LEVELS) {
                        if (level.equals(preferredLevel)) continue; // already tried
                        try {
                            JSONObject item = fetchSongUrlData(songId, cookie, level);
                            url = extractUrlFromItem(item);
                            if (url != null) {
                                trialOnly = isTrialUrlItem(item);
                                MusicLog.d(TAG, "回落到" + level + " URL成功: " + songId
                                        + (trialOnly ? " (试听)" : ""));
                                break;
                            }
                        } catch (Exception e) {
                            MusicLog.w(TAG, "eapi " + level + " 失败: " + songId, e);
                        }
                    }
                }

                // Last resort: direct link
                if (url == null) {
                    url = "https://music.163.com/song/media/outer/url?id=" + songId + ".mp3";
                    trialOnly = false;
                    MusicLog.w(TAG, "使用直连兜底URL: " + songId);
                }

                String finalUrl = url;
                boolean finalTrial = trialOnly;
                mainHandler.post(() -> callback.onResult(finalUrl, finalTrial));
            } catch (Exception e) {
                MusicLog.e(TAG, "getSongUrl 异常: " + songId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Fetch quality privilege info for a song using /api/v3/song/detail.
     * Corresponds to NeteaseCloudMusicApiBackup module/song_detail.js.
     *
     * The returned {@link SongQualityInfo} contains:
     * <ul>
     *   <li>flLevel  – max level free users can play</li>
     *   <li>plLevel  – max level the current login can play</li>
     *   <li>dlLevel  – max level the current login can download</li>
     *   <li>maxBrLevel – highest quality the song has</li>
     * </ul>
     */
    public static void getSongQualityInfo(long songId, String cookie,
                                          SongQualityCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("c", "[{\"id\":" + songId + "}]");

                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/v3/song/detail", data.toString(), cookie);
                JSONObject json = new JSONObject(response);

                JSONArray privileges = json.optJSONArray("privileges");
                if (privileges == null || privileges.length() == 0) {
                    mainHandler.post(() -> callback.onError("无权限数据"));
                    return;
                }
                JSONObject priv = privileges.getJSONObject(0);
                String flLevel    = priv.optString("flLevel", "standard");
                String plLevel    = priv.optString("plLevel", "exhigh");
                String dlLevel    = priv.optString("dlLevel", "none");
                String maxBrLevel = priv.optString("maxBrLevel", "exhigh");

                SongQualityInfo info = new SongQualityInfo(flLevel, plLevel, dlLevel, maxBrLevel);
                MusicLog.d(TAG, "音质信息: songId=" + songId
                        + " fl=" + flLevel + " pl=" + plLevel + " max=" + maxBrLevel);
                mainHandler.post(() -> callback.onResult(info));
            } catch (Exception e) {
                MusicLog.w(TAG, "getSongQualityInfo 失败: " + songId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }
    /**
     * Fetch song URL using eapi crypto, which is required by /api/song/enhance/player/url/v1
     * for lossless and higher quality tiers (matches NeteaseCloudMusicApiBackup song_url_v1.js).
     *
     * <p>返回原始 item（而不是只返回 url）：里面的 {@code freeTrialInfo} 决定这首歌是不是
     * 「只能试听」—— 上层据此决定是否走手表网关拿完整链接。</p>
     */
    private static JSONObject fetchSongUrlData(long songId, String cookie, String level)
            throws Exception {
        JSONObject data = new JSONObject();
        data.put("ids", "[" + songId + "]");
        data.put("level", level);
        data.put("encodeType", "flac");
        if ("sky".equals(level)) {
            data.put("immerseType", "c51");
        }

        String csrfToken = extractCsrfToken(cookie);
        data.put("csrf_token", csrfToken);

        String response = eapiPost("/api/song/enhance/player/url/v1", data.toString(), cookie);
        JSONObject json = new JSONObject(response);
        JSONArray dataArray = json.optJSONArray("data");
        if (dataArray != null && dataArray.length() > 0) {
            return dataArray.getJSONObject(0);
        }
        return null;
    }

    private static String extractUrlFromItem(JSONObject item) {
        if (item == null) {
            return null;
        }
        if (item.optInt("code", -1) != 200) {
            return null;
        }
        String url = item.optString("url", null);
        if (url != null && !"null".equals(url) && !url.isEmpty()) {
            return url;
        }
        return null;
    }

    /** freeTrialInfo present means the URL only contains the free trial clip. */
    private static boolean isTrialUrlItem(JSONObject item) {
        return item != null && item.optJSONObject("freeTrialInfo") != null;
    }

    // ==================== 歌手页 ====================

    /** 歌手详情（含热门 50 首）。 */
    public static class ArtistDetail {
        public long id;
        public String name = "";
        public String picUrl = "";
        public String briefDesc = "";
        public int musicSize;
        public int albumSize;
        public final java.util.List<Song> hotSongs = new java.util.ArrayList<>();
    }

    public interface ArtistDetailCallback {
        void onResult(ArtistDetail detail);

        void onError(String message);
    }

    /** 歌手详情 + 热门 50 首（weapi /api/v1/artist/{id}）。 */
    public static void getArtistDetail(long artistId, String cookie,
                                       ArtistDetailCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("id", artistId);
                String response = weapiPost("/api/v1/artist/" + artistId, data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                ArtistDetail detail = new ArtistDetail();
                detail.id = artistId;
                JSONObject artist = json.optJSONObject("artist");
                if (artist != null) {
                    detail.name = artist.optString("name", "");
                    detail.picUrl = artist.optString("picUrl", "");
                    detail.briefDesc = artist.optString("briefDesc", "");
                    if (detail.briefDesc.isEmpty()) {
                        detail.briefDesc = artist.optString("description", "");
                    }
                    detail.musicSize = artist.optInt("musicSize", 0);
                    detail.albumSize = artist.optInt("albumSize", 0);
                }
                detail.hotSongs.addAll(parseSongArray(json.optJSONArray("hotSongs")));
                mainHandler.post(() -> callback.onResult(detail));
            } catch (Exception e) {
                MusicLog.w(TAG, "歌手详情失败: " + artistId, e);
                mainHandler.post(() -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    public interface ArtistSongsCallback {
        void onResult(java.util.List<Song> songs);

        void onError(String message);
    }

    /** 歌手全部歌曲（weapi /api/v1/artist/songs，order=hot|time）。 */
    public static void getArtistSongs(long artistId, int offset, int limit, String order,
                                      String cookie, ArtistSongsCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("id", artistId);
                data.put("offset", offset);
                data.put("limit", limit);
                data.put("order", order == null ? "hot" : order);
                String response = weapiPost("/api/v1/artist/songs", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                java.util.List<Song> songs = parseSongArray(json.optJSONArray("songs"));
                mainHandler.post(() -> callback.onResult(songs));
            } catch (Exception e) {
                MusicLog.w(TAG, "歌手歌曲失败: " + artistId, e);
                mainHandler.post(() -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    public interface ArtistSubCallback {
        void onResult(boolean subscribed);

        void onError(String message);
    }

    /** 收藏 / 取消收藏歌手（weapi /api/artist/sub，t=1 收藏，t=0 取消）。 */
    public static void subArtist(long artistId, boolean subscribe, String cookie,
                                 ArtistSubCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("artistId", artistId);
                data.put("artistIds", "[" + artistId + "]");
                data.put("t", subscribe ? 1 : 0);
                String response = weapiPost("/api/artist/sub", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", 200);
                if (code != 200) {
                    String msg = json.optString("message", "操作失败");
                    mainHandler.post(() -> callback.onError(msg));
                    return;
                }
                mainHandler.post(() -> callback.onResult(subscribe));
            } catch (Exception e) {
                MusicLog.w(TAG, "收藏歌手失败: " + artistId, e);
                mainHandler.post(() -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * 解析歌曲数组（兼容新版 ar/al 与旧版 artists/album 两套字段）。
     * 歌手页、心动模式等都复用它。
     */
    static java.util.List<Song> parseSongArray(JSONArray arr) {
        java.util.List<Song> out = new java.util.ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) {
                continue;
            }
            long id = s.optLong("id", 0);
            if (id <= 0) {
                continue;
            }
            String name = s.optString("name", "");
            JSONArray ar = s.optJSONArray("ar");
            if (ar == null) {
                ar = s.optJSONArray("artists");
            }
            StringBuilder artistSb = new StringBuilder();
            if (ar != null) {
                for (int j = 0; j < ar.length(); j++) {
                    JSONObject a = ar.optJSONObject(j);
                    if (a == null) {
                        continue;
                    }
                    if (artistSb.length() > 0) {
                        artistSb.append(" / ");
                    }
                    artistSb.append(a.optString("name", ""));
                }
            }
            JSONObject al = s.optJSONObject("al");
            if (al == null) {
                al = s.optJSONObject("album");
            }
            String album = al != null ? al.optString("name", "") : "";
            String cover = al != null ? al.optString("picUrl", "") : "";
            long albumId = al != null ? al.optLong("id", 0) : 0;
            Song song = new Song(id, name, artistSb.toString(), album);
            song.setFee(s.optInt("fee", 0));
            if (!cover.isEmpty()) {
                song.setCoverUrl(cover);
            }
            if (albumId > 0) {
                song.setAlbumId(albumId);
            }
            out.add(song);
        }
        return out;
    }

    // ==================== 打卡 / 听歌排行 / 心动模式 ====================

    /** 听歌打卡的重试次数。慢网下单次偶发读超时，不重试就会丢一次记录。 */
    private static final int SCROBBLE_MAX_ATTEMPTS = 2;

    /**
     * 听歌打卡（weapi {@code /api/feedback/weblog}）：让账号里的听歌记录、排行与手表同步。
     *
     * <p>网易的「听歌打卡」实际走 {@code /api/feedback/weblog}，数据体是 logs 数组 +
     * action/json（与 NeteaseCloudMusicApi 的 scrobble 一致）；早先用 {@code /api/scrobble}
     * 会返回 404「接口未找到」。</p>
     *
     * <p><b>为什么会重试</b>：实测手表网络 RTT 可到 ~800ms，单次请求偶发读超时
     * （{@code java.net.SocketException: Socket closed}），而打卡是「每曲一次」的，
     * 失败就永久丢一条。这里失败后隔 1.2s 重试一次，尽量把记录补上。</p>
     */
    public static void scrobble(long songId, long sourceId, String cookie) {
        executor.execute(() -> {
            final String body;
            try {
                JSONObject inner = new JSONObject();
                inner.put("download", 0);
                inner.put("end", "playend");
                inner.put("id", songId);
                inner.put("sourceId", sourceId > 0 ? sourceId : songId);
                inner.put("time", 60);
                inner.put("type", "song");
                inner.put("wifi", 0);
                inner.put("source", "list");

                JSONObject log = new JSONObject();
                log.put("action", "play");
                log.put("json", inner);

                JSONArray logs = new JSONArray();
                logs.put(log);

                JSONObject data = new JSONObject();
                data.put("logs", logs.toString());
                data.put("csrf_token", extractCsrfToken(cookie));
                body = data.toString();
            } catch (Exception e) {
                MusicLog.w(TAG, "听歌打卡参数构造失败: " + songId, e);
                return;
            }

            for (int attempt = 1; attempt <= SCROBBLE_MAX_ATTEMPTS; attempt++) {
                try {
                    String response = weapiPost("/api/feedback/weblog", body, cookie);
                    android.util.Log.i("Scrobble",
                            "服务端响应(第" + attempt + "次): " + response);
                    if (response != null && response.contains("\"code\":200")) {
                        return;
                    }
                } catch (Exception e) {
                    MusicLog.w(TAG, "听歌打卡失败(第" + attempt + "/" + SCROBBLE_MAX_ATTEMPTS
                            + "次): " + songId, e);
                }
                if (attempt < SCROBBLE_MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            MusicLog.w(TAG, "听歌打卡最终失败: " + songId);
        });
    }

    /** 听歌排行里的一条：歌曲 + 播放次数。 */
    public static class RecordItem {
        public final Song song;
        public final int playCount;

        public RecordItem(Song song, int playCount) {
            this.song = song;
            this.playCount = playCount;
        }
    }

    public interface UserRecordCallback {
        void onResult(java.util.List<RecordItem> items);

        void onError(String message);
    }

    /** 听歌排行（weapi /api/v1/play/record，type=1 本周 / 0 全部时间）。 */
    public static void getUserRecord(long uid, int type, String cookie,
                                     UserRecordCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("uid", uid);
                data.put("type", type);
                String response = weapiPost("/api/v1/play/record", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONArray arr = type == 1
                        ? json.optJSONArray("weekData") : json.optJSONArray("allData");
                java.util.List<RecordItem> out = new java.util.ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.optJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        JSONObject songJson = item.optJSONObject("song");
                        if (songJson == null) {
                            continue;
                        }
                        java.util.List<Song> one = parseSongArray(new JSONArray().put(songJson));
                        if (one.isEmpty()) {
                            continue;
                        }
                        out.add(new RecordItem(one.get(0), item.optInt("playCount", 0)));
                    }
                }
                mainHandler.post(() -> callback.onResult(out));
            } catch (Exception e) {
                MusicLog.w(TAG, "听歌排行失败: uid=" + uid, e);
                mainHandler.post(() -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /** 通用「歌曲列表」回调（心动模式等复用）。 */
    public interface SongListCallback {
        void onResult(java.util.List<Song> songs);

        void onError(String message);
    }

    /**
     * 心动模式（weapi /api/playmode/intelligence/list）：
     * 以当前歌曲 + 当前歌单为种子，生成一串「越听越对味」的相似曲目队列。
     */
    public static void getIntelligenceList(long songId, long playlistId, String cookie,
                                           SongListCallback callback) {
        executor.execute(() -> {
            try {
                // 注意参数名：官方实现是 songId / type / playlistId / startMusicId / count
                // （早先用 id/pid/sid 会拿到空的 data，且不传 count 时默认只给 1 首）
                JSONObject data = new JSONObject();
                data.put("songId", songId);
                data.put("type", playlistId > 0 ? "fromPlayOne" : "fromPage");
                data.put("playlistId", playlistId > 0 ? playlistId : songId);
                data.put("startMusicId", songId);
                data.put("count", 30);
                String response = weapiPost("/api/playmode/intelligence/list",
                        data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONArray arr = json.optJSONArray("data");
                java.util.List<Song> out = new java.util.ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.optJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        JSONObject songJson = item.optJSONObject("songInfo");
                        if (songJson == null) {
                            songJson = item;
                        }
                        java.util.List<Song> one = parseSongArray(new JSONArray().put(songJson));
                        if (!one.isEmpty()) {
                            out.add(one.get(0));
                        }
                    }
                }
                // 兜底：心动模式本是为「歌单内智能续播」设计的 —— 实测上游
                // /api/playmode/intelligence/list 目前直接回
                // {"success":false,"code":"500"}（歌单上下文给全也一样，2026-10-03 实测），
                // 所以这里基本总会退化成「相似歌曲」，保证按钮始终有反应。
                if (out.isEmpty()) {
                    JSONArray fallbackSongs = fetchSimiRaw(SIMI_KIND_SONG, songId, cookie);
                    out.addAll(parseSongArray(fallbackSongs));
                    android.util.Log.i("Intelligence",
                            "智能列表为空 → 相似歌曲兜底 n=" + out.size());
                }
                final java.util.List<Song> finalOut = out;
                mainHandler.post(() -> callback.onResult(finalOut));
            } catch (Exception e) {
                MusicLog.w(TAG, "心动模式失败: " + songId, e);
                mainHandler.post(() -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    public interface DefaultKeywordCallback {
        void onResult(String keyword);

        void onError(String message);
    }

    /**
     * 默认搜索关键词（weapi /api/search/defaultkeyword/get）。
     *
     * <p>手表上打字很难，启动搜索页时先拿一个官方默认词填进输入框提示，
     * 用户不用输入也能直接搜。</p>
     */
    public static void getDefaultSearchKeyword(String cookie, DefaultKeywordCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                String response = weapiPost("/api/search/defaultkeyword/get", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONObject d = json.optJSONObject("data");
                String keyword = "";
                if (d != null) {
                    // realkeyword 是可直接搜索的词；showKeyword 可能带 emoji / 后缀文案
                    keyword = d.optString("realkeyword", "");
                    if (keyword.isEmpty()) {
                        JSONObject style = d.optJSONObject("styleKeyword");
                        if (style != null) {
                            keyword = style.optString("keyWord", "");
                        }
                    }
                    if (keyword.isEmpty()) {
                        keyword = d.optString("showKeyword", "");
                    }
                }
                final String out = keyword == null ? "" : keyword.trim();
                mainHandler.post(() -> callback.onResult(out));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取默认搜索词失败", e);
                mainHandler.post(() -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== 歌单动态 ====================

    public interface PlaylistDynamicCallback {
        void onResult(long playCount, long subscribedCount, long commentCount, long shareCount);

        void onError(String message);
    }

    /** 歌单动态信息：播放量 / 收藏数 / 评论数 / 分享数（weapi /api/playlist/detail/dynamic）。 */
    public static void getPlaylistDynamic(long playlistId, String cookie,
                                          PlaylistDynamicCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("id", playlistId);
                String response = weapiPost("/api/playlist/detail/dynamic", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                long play = json.optLong("playCount", 0);
                long sub = json.optLong("subscribedCount", 0);
                long comment = json.optLong("commentCount", 0);
                long share = json.optLong("shareCount", 0);
                mainHandler.post(() -> callback.onResult(play, sub, comment, share));
            } catch (Exception e) {
                MusicLog.w(TAG, "歌单动态失败: " + playlistId, e);
                mainHandler.post(() -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== QR Login ====================

    /**
     * Step 1: Get QR login key via weapi
     * (same as NeteaseCloudMusicApiBackup module/login_qr_key.js)
     */
    public static void loginQrKey(QrKeyCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("type", 3);

                String response = weapiPost("/api/login/qrcode/unikey", data.toString(), null);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                String unikey = json.optString("unikey", "");
                if (code == 200 && !unikey.isEmpty()) {
                    mainHandler.post(() -> callback.onResult(unikey));
                } else {
                    mainHandler.post(() -> callback.onError("获取二维码Key失败: code=" + code));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /**
     * Step 2: Build QR URL from key (no server call needed)
     * (same as NeteaseCloudMusicApiBackup module/login_qr_create.js)
     */
    public static void loginQrCreate(String key, QrCreateCallback callback) {
        String qrUrl = "https://music.163.com/login?codekey=" + key;
        mainHandler.post(() -> callback.onResult(qrUrl));
    }

    /**
     * Step 3: Check QR login status via weapi
     * (same as NeteaseCloudMusicApiBackup module/login_qr_check.js)
     * Captures Set-Cookie headers to build the cookie string.
     */
    public static void loginQrCheck(String key, QrCheckCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("key", key);
                data.put("type", 3);

                String[] encrypted = NeteaseApiCrypto.weapi(data.toString());

                String postBody = "params=" + URLEncoder.encode(encrypted[0], "UTF-8")
                        + "&encSecKey=" + URLEncoder.encode(encrypted[1], "UTF-8");

                String urlStr = WEAPI_BASE + "/login/qrcode/client/login";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Referer", DOMAIN);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Cookie", buildAnonymousCookie(""));
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setInstanceFollowRedirects(false);

                try {
                    OutputStream os = conn.getOutputStream();
                    os.write(postBody.getBytes("UTF-8"));
                    os.close();

                    // Read response body
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    int code = json.optInt("code", -1);
                    String message = json.optString("message", "");

                    // Extract cookies from Set-Cookie headers
                    String cookieStr = "";
                    if (code == 803) {
                        cookieStr = extractSetCookies(conn);
                    }

                    final String finalCookie = cookieStr;
                    mainHandler.post(() -> callback.onResult(code, message, finalCookie));
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    // ==================== SMS Login ====================

    /**
     * Send SMS verification code to phone number
     * (same as NeteaseCloudMusicApiBackup module/captcha_sent.js)
     * Uses os=android cookie to avoid "环境不安全" error.
     */
    public static void sendSmsCode(String phone, String ctcode, SmsCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("ctcode", ctcode != null && !ctcode.isEmpty() ? ctcode : "86");
                data.put("cellphone", phone);

                String response = weapiPostMobile("/api/sms/captcha/sent", data.toString(), null);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                if (code == 200) {
                    mainHandler.post(() -> callback.onResult(true, "验证码已发送"));
                } else {
                    String msg = json.optString("message", "发送失败: code=" + code);
                    mainHandler.post(() -> callback.onResult(false, msg));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /**
     * Login with phone number and SMS captcha.
     * Per NeteaseCloudMusic_PythonSDK: login_cellphone(phone, captcha=captcha, countrycode=ctcode)
     * → POST /weapi/w/login/cellphone with {type:'1', https:'true', phone, countrycode, captcha, rememberLogin:'true'}
     */
    public static void loginByCellphone(String phone, String captcha, String ctcode,
                                         LoginCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "短信登录", "phone=" + maskPhone(phone));

                JSONObject data = new JSONObject();
                data.put("type", "1");
                data.put("https", "true");
                data.put("phone", phone);
                data.put("countrycode", ctcode != null && !ctcode.isEmpty() ? ctcode : "86");
                data.put("captcha", captcha);
                data.put("rememberLogin", "true");

                String[] encrypted = NeteaseApiCrypto.weapi(data.toString());
                String postBody = "params=" + URLEncoder.encode(encrypted[0], "UTF-8")
                        + "&encSecKey=" + URLEncoder.encode(encrypted[1], "UTF-8");

                String urlStr = DOMAIN + "/weapi/w/login/cellphone";
                MusicLog.i(TAG, "[REQ] POST(sms-login) " + urlStr + "\n  请求体(原文): " + data);
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("User-Agent", MOBILE_USER_AGENT);
                conn.setRequestProperty("Referer", DOMAIN);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Cookie", buildMobileCookie(""));
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setInstanceFollowRedirects(false);

                try {
                    OutputStream os = conn.getOutputStream();
                    os.write(postBody.getBytes("UTF-8"));
                    os.close();

                    int httpCode = conn.getResponseCode();

                    BufferedReader reader;
                    if (httpCode >= 200 && httpCode < 400) {
                        reader = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    } else {
                        java.io.InputStream errStream = conn.getErrorStream();
                        if (errStream != null) {
                            reader = new BufferedReader(
                                    new InputStreamReader(errStream, "UTF-8"));
                        } else {
                            String errMsg = "HTTP " + httpCode;
                            MusicLog.api(TAG, "POST(sms-login)", urlStr, httpCode, errMsg);
                            mainHandler.post(() -> callback.onResult(httpCode, errMsg, ""));
                            return;
                        }
                    }
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    String responseBody = sb.toString();
                    MusicLog.api(TAG, "POST(sms-login)", urlStr, httpCode, responseBody);

                    JSONObject json = new JSONObject(responseBody);
                    int code = json.optInt("code", -1);
                    String message = json.optString("message", json.optString("msg", ""));
                    if (message.isEmpty()) message = "code=" + code;

                    // -462 = needs CAPTCHA verification before login can proceed
                    if (code == -462) {
                        JSONObject dataObj = json.optJSONObject("data");
                        String verifyUrl = dataObj != null ? dataObj.optString("url", dataObj.optString("verifyUrl", "")) : "";
                        MusicLog.w(TAG, "短信登录需要滑块验证，verifyUrl=" + verifyUrl);
                        MusicLog.d(TAG, "短信登录 -462 Set-Cookie: " + extractSetCookies(conn));
                        final String finalVerifyUrl = verifyUrl;
                        mainHandler.post(() -> callback.onVerificationRequired(finalVerifyUrl));
                        return;
                    }

                    String cookieStr = "";
                    if (code == 200) {
                        cookieStr = extractSetCookies(conn);
                        MusicLog.d(TAG, "短信登录成功，Set-Cookie: " + (cookieStr.isEmpty() ? "(空)" : "已获取"));
                        // Fallback: if Set-Cookie headers are empty, try token from JSON body
                        if ((cookieStr == null || cookieStr.isEmpty() || !cookieStr.contains("MUSIC_U"))
                                && json.has("token")) {
                            String token = json.optString("token", "");
                            if (!token.isEmpty()) {
                                cookieStr = "MUSIC_U=" + token;
                                MusicLog.d(TAG, "短信登录: 从token字段提取Cookie");
                            }
                        }
                        // Also try cookie field in JSON body
                        if ((cookieStr == null || cookieStr.isEmpty() || !cookieStr.contains("MUSIC_U"))
                                && json.has("cookie")) {
                            String bodyCookie = json.optString("cookie", "");
                            if (!bodyCookie.isEmpty() && bodyCookie.contains("MUSIC_U")) {
                                cookieStr = bodyCookie;
                                MusicLog.d(TAG, "短信登录: 从cookie字段提取Cookie");
                            }
                        }
                    } else {
                        MusicLog.w(TAG, "短信登录失败: code=" + code + " message=" + message);
                    }

                    final String finalCookie = cookieStr;
                    final String finalMsg = message;
                    mainHandler.post(() -> callback.onResult(code, finalMsg, finalCookie));
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                MusicLog.e(TAG, "短信登录异常", e);
                String errMsg = e.getMessage() != null ? e.getMessage() : "网络连接失败";
                mainHandler.post(() -> callback.onError(errMsg));
            }
        });
    }

    // ==================== Logout（1016 合并） ====================

    /**
     * Real server-side logout.
     * (NeteaseCloudMusicApiBackup module/logout.js: request('/api/logout', {}, createOption(query)))
     * Weapi POST /api/logout with the user cookie (__csrf included).
     * The caller clears the local cookie afterwards regardless of the result.
     */
    public static void logout(String cookie, LikeCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "退出登录", null);
                JSONObject data = new JSONObject();
                data.put("csrf_token", extractCsrfToken(cookie));

                String response = weapiPost("/api/logout", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                boolean ok = code == 200;
                if (!ok) {
                    MusicLog.w(TAG, "退出登录返回 code=" + code);
                }
                mainHandler.post(() -> callback.onResult(ok));
            } catch (Exception e) {
                // Network failure must not block local logout; report and let caller decide
                MusicLog.e(TAG, "退出登录失败", e);
                mainHandler.post(() -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "网络连接失败"));
            }
        });
    }

    // ==================== Password Login ====================

    /**
     * Login with phone number and password.
     * (same as NeteaseCloudMusicApiBackup module/login_cellphone.js with password mode)
     * Uses mobile cookie and weapi encryption, same as SMS login.
     */
    public static void loginByPassword(String phone, String password, String ctcode,
                                        LoginCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "密码登录", "phone=" + maskPhone(phone));

                // MD5 hash the password
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(password.getBytes("UTF-8"));
                StringBuilder hexString = new StringBuilder();
                for (byte b : digest) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                String md5Password = hexString.toString();

                JSONObject data = new JSONObject();
                data.put("type", "1");
                data.put("https", "true");
                data.put("phone", phone);
                data.put("countrycode", ctcode != null && !ctcode.isEmpty() ? ctcode : "86");
                data.put("password", md5Password);
                data.put("rememberLogin", "true");

                String[] encrypted = NeteaseApiCrypto.weapi(data.toString());
                String postBody = "params=" + URLEncoder.encode(encrypted[0], "UTF-8")
                        + "&encSecKey=" + URLEncoder.encode(encrypted[1], "UTF-8");

                String urlStr = DOMAIN + "/weapi/w/login/cellphone";
                // Log request (hide actual MD5 hash for security)
                MusicLog.i(TAG, "[REQ] POST(pwd-login) " + urlStr + "\n  请求体(原文): {phone=" + maskPhone(phone) + ", countrycode=86, password=[MD5已隐藏], rememberLogin=true}");
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("User-Agent", MOBILE_USER_AGENT);
                conn.setRequestProperty("Referer", DOMAIN);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Cookie", buildMobileCookie(""));
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setInstanceFollowRedirects(false);

                try {
                    OutputStream os = conn.getOutputStream();
                    os.write(postBody.getBytes("UTF-8"));
                    os.close();

                    int httpCode = conn.getResponseCode();

                    BufferedReader reader;
                    if (httpCode >= 200 && httpCode < 400) {
                        reader = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    } else {
                        java.io.InputStream errStream = conn.getErrorStream();
                        if (errStream != null) {
                            reader = new BufferedReader(
                                    new InputStreamReader(errStream, "UTF-8"));
                        } else {
                            String errMsg = "HTTP " + httpCode;
                            MusicLog.api(TAG, "POST(pwd-login)", urlStr, httpCode, errMsg);
                            mainHandler.post(() -> callback.onResult(httpCode, errMsg, ""));
                            return;
                        }
                    }
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    String responseBody = sb.toString();
                    MusicLog.api(TAG, "POST(pwd-login)", urlStr, httpCode, responseBody);

                    JSONObject json = new JSONObject(responseBody);
                    int code = json.optInt("code", -1);
                    String message = json.optString("message", json.optString("msg", ""));
                    if (message.isEmpty()) message = "code=" + code;

                    String cookieStr = "";
                    if (code == 200) {
                        cookieStr = extractSetCookies(conn);
                        MusicLog.d(TAG, "密码登录成功，Set-Cookie: " + (cookieStr.isEmpty() ? "(空)" : "已获取"));
                        // Fallback: if Set-Cookie headers are empty, try token from JSON body
                        if ((cookieStr == null || cookieStr.isEmpty() || !cookieStr.contains("MUSIC_U"))
                                && json.has("token")) {
                            String token = json.optString("token", "");
                            if (!token.isEmpty()) {
                                cookieStr = "MUSIC_U=" + token;
                                MusicLog.d(TAG, "密码登录: 从token字段提取Cookie");
                            }
                        }
                        // Also try cookie field in JSON body
                        if ((cookieStr == null || cookieStr.isEmpty() || !cookieStr.contains("MUSIC_U"))
                                && json.has("cookie")) {
                            String bodyCookie = json.optString("cookie", "");
                            if (!bodyCookie.isEmpty() && bodyCookie.contains("MUSIC_U")) {
                                cookieStr = bodyCookie;
                                MusicLog.d(TAG, "密码登录: 从cookie字段提取Cookie");
                            }
                        }
                    } else {
                        MusicLog.w(TAG, "密码登录失败: code=" + code + " message=" + message);
                    }

                    final String finalCookie = cookieStr;
                    final String finalMsg = message;
                    mainHandler.post(() -> callback.onResult(code, finalMsg, finalCookie));
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                MusicLog.e(TAG, "密码登录异常", e);
                String errMsg = e.getMessage() != null ? e.getMessage() : "网络连接失败";
                mainHandler.post(() -> callback.onError(errMsg));
            }
        });
    }

    // ==================== Lyrics ====================

    /**
     * Fetch lyrics for a song by its ID.
     * (same as NeteaseCloudMusicApiBackup module/lyric_new.js)
     */
    public static void getLyrics(long songId, String cookie, LyricsCallback callback) {
        executor.execute(() -> {
            try {
                String lrc = fetchLyrics(songId, cookie);
                mainHandler.post(() -> callback.onResult(lrc));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取歌词失败: " + songId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Fetch lyrics synchronously (for use in download).
     */
    public static String fetchLyricsSync(long songId, String cookie) {
        try {
            return fetchLyrics(songId, cookie);
        } catch (Exception e) {
            MusicLog.w(TAG, "歌词同步获取失败: " + songId, e);
            return null;
        }
    }

    private static String fetchLyrics(long songId, String cookie) throws Exception {
        JSONObject data = new JSONObject();
        data.put("id", songId);
        data.put("cp", false);
        data.put("tv", 0);
        data.put("lv", 0);
        data.put("rv", 0);
        data.put("kv", 0);

        String csrfToken = extractCsrfToken(cookie);
        data.put("csrf_token", csrfToken);

        String response = weapiPost("/api/song/lyric/v1", data.toString(), cookie);
        JSONObject json = new JSONObject(response);

        // Extract LRC lyrics
        JSONObject lrcObj = json.optJSONObject("lrc");
        if (lrcObj != null) {
            String lyric = lrcObj.optString("lyric", "");
            if (!lyric.isEmpty()) {
                return lyric;
            }
        }
        return "";
    }

    // ==================== Cloud Favorites ====================

    /**
     * Get the user's "liked songs" playlist from the cloud.
     * Uses the user's liked playlist (first playlist from /api/user/playlist)
     * and fetches tracks via /api/v6/playlist/detail which returns them
     * in the correct time order (most recently liked first).
     */
    public static void getCloudFavorites(String cookie, CloudFavoritesCallback callback) {
        executor.execute(() -> {
            try {
                long uid = extractUidFromCookie(cookie);
                if (uid <= 0) {
                    mainHandler.post(() -> callback.onError("请先登录"));
                    return;
                }
                MusicLog.op(TAG, "获取云端收藏", "uid=" + uid);

                String csrfToken = extractCsrfToken(cookie);

                // Step 1: Get user's liked playlist ID (first playlist)
                JSONObject plData = new JSONObject();
                plData.put("uid", uid);
                plData.put("limit", 1);
                plData.put("offset", 0);
                plData.put("csrf_token", csrfToken);

                String plResponse = weapiPost("/api/user/playlist", plData.toString(), cookie);
                JSONObject plJson = new JSONObject(plResponse);
                JSONArray playlists = plJson.optJSONArray("playlist");

                if (playlists == null || playlists.length() == 0) {
                    // Fallback: use legacy method
                    getCloudFavoritesLegacy(cookie, uid, csrfToken, callback);
                    return;
                }

                long likedPlaylistId = playlists.getJSONObject(0).getLong("id");

                // Step 2: Get playlist detail - tracks array is limited to ~n tracks,
                // but trackIds contains ALL song IDs. Use trackIds to fetch all songs.
                JSONObject detailData = new JSONObject();
                detailData.put("id", likedPlaylistId);
                detailData.put("n", 0);  // Don't need full tracks, we'll use trackIds
                detailData.put("csrf_token", csrfToken);

                String detailResponse = weapiPost("/api/v6/playlist/detail", detailData.toString(), cookie);
                JSONObject detailJson = new JSONObject(detailResponse);
                JSONObject playlist = detailJson.optJSONObject("playlist");

                if (playlist == null) {
                    getCloudFavoritesLegacy(cookie, uid, csrfToken, callback);
                    return;
                }

                JSONArray trackIds = playlist.optJSONArray("trackIds");
                if (trackIds == null || trackIds.length() == 0) {
                    mainHandler.post(() -> callback.onResult(new ArrayList<>()));
                    return;
                }

                // Fetch song details in batches of 200 using trackIds
                List<Song> songs = fetchSongDetailsByTrackIds(trackIds, csrfToken, cookie);
                mainHandler.post(() -> callback.onResult(songs));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取云端收藏失败", e);
                mainHandler.post(() -> callback.onError("获取云端收藏失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误")));
            }
        });
    }

    /**
     * Legacy fallback: get cloud favorites via /api/song/like/get + /api/v3/song/detail.
     * Used when the playlist API is unavailable.
     */
    private static void getCloudFavoritesLegacy(String cookie, long uid, String csrfToken,
                                                  CloudFavoritesCallback callback) {
        try {
            JSONObject likeData = new JSONObject();
            likeData.put("uid", uid);
            likeData.put("csrf_token", csrfToken);

            String likeResponse = weapiPost("/api/song/like/get", likeData.toString(), cookie);
            JSONObject likeJson = new JSONObject(likeResponse);

            JSONArray idsArray = likeJson.optJSONArray("ids");
            if (idsArray == null || idsArray.length() == 0) {
                mainHandler.post(() -> callback.onResult(new ArrayList<>()));
                return;
            }

            // Fetch all songs in batches of 200
            int total = idsArray.length();
            java.util.Map<Long, Song> songMap = new java.util.LinkedHashMap<>();
            for (int batchStart = 0; batchStart < total; batchStart += 200) {
                int batchEnd = Math.min(batchStart + 200, total);
                JSONArray songIds = new JSONArray();
                for (int i = batchStart; i < batchEnd; i++) {
                    JSONObject idObj = new JSONObject();
                    idObj.put("id", idsArray.getLong(i));
                    songIds.put(idObj);
                }

                JSONObject detailData = new JSONObject();
                detailData.put("c", songIds.toString());
                detailData.put("csrf_token", csrfToken);

                String detailResponse = weapiPost("/api/v3/song/detail", detailData.toString(), cookie);
                JSONObject detailJson = new JSONObject(detailResponse);
                JSONArray songsArray = detailJson.optJSONArray("songs");

                if (songsArray != null) {
                    for (int i = 0; i < songsArray.length(); i++) {
                        JSONObject s = songsArray.getJSONObject(i);
                        Song song = parseSongFromJson(s);
                        if (song != null && song.getId() > 0) {
                            songMap.put(song.getId(), song);
                        }
                    }
                }
            }

            List<Song> songs = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                long id = idsArray.getLong(i);
                Song song = songMap.get(id);
                if (song != null) {
                    songs.add(song);
                }
            }
            mainHandler.post(() -> callback.onResult(songs));
        } catch (Exception e) {
            MusicLog.w(TAG, "获取云端收藏(legacy)失败", e);
            mainHandler.post(() -> callback.onError("获取云端收藏失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误")));
        }
    }

    /**
     * Fetch song details in batches of 200 using trackIds from a playlist.
     * trackIds is a JSONArray of objects with at least an "id" field.
     * Returns songs in the same order as trackIds.
     */
    private static List<Song> fetchSongDetailsByTrackIds(JSONArray trackIds, String csrfToken, String cookie) throws Exception {
        int total = trackIds.length();
        java.util.Map<Long, Song> songMap = new java.util.LinkedHashMap<>();

        for (int batchStart = 0; batchStart < total; batchStart += 200) {
            int batchEnd = Math.min(batchStart + 200, total);
            JSONArray batchIds = new JSONArray();
            for (int i = batchStart; i < batchEnd; i++) {
                JSONObject idObj = new JSONObject();
                long songId = trackIds.getJSONObject(i).getLong("id");
                idObj.put("id", songId);
                batchIds.put(idObj);
            }

            JSONObject detailData = new JSONObject();
            detailData.put("c", batchIds.toString());
            detailData.put("csrf_token", csrfToken);

            String detailResponse = weapiPost("/api/v3/song/detail", detailData.toString(), cookie);
            JSONObject detailJson = new JSONObject(detailResponse);
            JSONArray songsArray = detailJson.optJSONArray("songs");

            if (songsArray != null) {
                    for (int i = 0; i < songsArray.length(); i++) {
                        JSONObject s = songsArray.getJSONObject(i);
                        Song song = parseSongFromJson(s);
                        if (song != null && song.getId() > 0) {
                            songMap.put(song.getId(), song);
                        }
                    }
                }
        }

        // Preserve original order from trackIds
        List<Song> result = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            long id = trackIds.getJSONObject(i).getLong("id");
            Song song = songMap.get(id);
            if (song != null) {
                result.add(song);
            }
        }
        return result;
    }

    /**
     * Like or unlike a song on the cloud.
     * (same as NeteaseCloudMusicApiBackup module/like.js)
     */
    public static void likeTrack(long trackId, boolean like, String cookie, LikeCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, like ? "收藏歌曲" : "取消收藏", "trackId=" + trackId);
                JSONObject data = new JSONObject();
                data.put("trackId", trackId);
                data.put("like", like);
                data.put("alg", "itembased");
                data.put("time", 3);

                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/radio/like", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                mainHandler.post(() -> callback.onResult(code == 200));
            } catch (Exception e) {
                MusicLog.w(TAG, "收藏操作失败: " + trackId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Get the set of liked song IDs from the cloud (for checking like status).
     */
    public static void getCloudLikedIds(String cookie, CloudLikedIdsCallback callback) {
        executor.execute(() -> {
            try {
                long uid = extractUidFromCookie(cookie);
                if (uid <= 0) {
                    mainHandler.post(() -> callback.onError("请先登录"));
                    return;
                }

                JSONObject likeData = new JSONObject();
                likeData.put("uid", uid);
                String csrfToken = extractCsrfToken(cookie);
                likeData.put("csrf_token", csrfToken);

                String likeResponse = weapiPost("/api/song/like/get", likeData.toString(), cookie);
                JSONObject likeJson = new JSONObject(likeResponse);

                JSONArray idsArray = likeJson.optJSONArray("ids");
                java.util.Set<Long> idSet = new java.util.HashSet<>();
                if (idsArray != null) {
                    for (int i = 0; i < idsArray.length(); i++) {
                        idSet.add(idsArray.getLong(i));
                    }
                }
                mainHandler.post(() -> callback.onResult(idSet));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取云端收藏ID失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== User Account ====================

    /**
     * Get user account info including profile and VIP details.
     */
    public static void getUserAccount(String cookie, AccountCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取用户账号信息", null);
                JSONObject data = new JSONObject();
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/w/nuser/account/get", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                mainHandler.post(() -> callback.onResult(json));
            } catch (Exception e) {
                MusicLog.e(TAG, "获取账号信息失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    public interface UserLevelCallback {
        /**
         * @param data 上游 {@code data} 节点（可能为空对象）：level / progress /
         *             nowPlayCount / nextPlayCount / nowLoginCount / nextLoginCount / info
         */
        void onResult(JSONObject data);

        void onError(String message);
    }

    /**
     * 用户等级信息（weapi /api/user/level）。
     *
     * <p>返回 {@code data} 节点，字段：{@code level} 当前等级、{@code progress} 当前等级进度
     * (0~1)、{@code nowPlayCount}/{@code nextPlayCount} 已听/升级所需听歌数、
     * {@code nowLoginCount}/{@code nextLoginCount} 登录天数、{@code info} 权益说明。</p>
     */
    public static void getUserLevel(String cookie, UserLevelCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取用户等级", null);
                JSONObject data = new JSONObject();
                data.put("csrf_token", extractCsrfToken(cookie));
                String response = weapiPost("/api/user/level", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONObject d = json.optJSONObject("data");
                final JSONObject out = d != null ? d : new JSONObject();
                mainHandler.post(() -> callback.onResult(out));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取用户等级失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    public interface UserSubcountCallback {
        /**
         * @param data 上游原始 JSON：artistCount / createdPlaylistCount /
         *             subPlaylistCount / mvCount / djRadioCount / programCount …
         */
        void onResult(JSONObject data);

        void onError(String message);
    }

    /**
     * 用户统计（weapi /api/subcount）：收藏歌手数、自建歌单数、收藏歌单数、
     * MV / 电台 / 节目数。
     *
     * <p>注意上游路径是 {@code /weapi/subcount}，不是 {@code /weapi/user/subcount}
     * —— 后者回 {@code {"code":404,"message":"接口未找到！"}}（2026-10-03 实测）。</p>
     */
    public static void getUserSubcount(String cookie, UserSubcountCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取用户统计", null);
                JSONObject data = new JSONObject();
                data.put("csrf_token", extractCsrfToken(cookie));
                String response = weapiPost("/api/subcount", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                mainHandler.post(() -> callback.onResult(json));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取用户统计失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Get VIP user info including expiry time.
     * (NeteaseCloudMusicApiBackup module/vip_info.js)
     * Endpoint: /api/music-vip-membership/front/vip/info
     */
    public static void getVipInfo(String cookie, VipInfoCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取VIP信息", null);
                JSONObject data = new JSONObject();
                data.put("userId", "");
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/music-vip-membership/front/vip/info", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                mainHandler.post(() -> callback.onResult(json));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取VIP信息失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Top List ====================

    /**
     * Update current user profile fields (nickname, gender, signature, birthday).
     * (matches NeteaseCloudMusicApiBackup module/user_update.js)
     */
    public static void updateUserProfile(JSONObject params, String cookie, CommentActionCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                java.util.Iterator<String> keys = params.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    data.put(key, params.get(key));
                }
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                String response = weapiPost("/api/user/profile/update", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                if (json.optInt("code", -1) == 200) {
                    mainHandler.post(() -> callback.onResult(true));
                } else {
                    mainHandler.post(() -> callback.onError(json.optString("message", "更新失败")));
                }
            } catch (Exception e) {
                MusicLog.w(TAG, "更新用户信息失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Upload a new avatar image and apply it to the current account.
     */
    public static void uploadAvatar(File file, String cookie, CommentActionCallback callback) {
        executor.execute(() -> {
            try {
                if (file == null || !file.exists() || file.length() <= 0) {
                    mainHandler.post(() -> callback.onError("文件无效"));
                    return;
                }
                // 1. Request NOS upload token (matches avatar_upload.js uploadPlugin)
                JSONObject tokenData = new JSONObject();
                tokenData.put("bucket", "yyimgs");
                tokenData.put("ext", "jpg");
                tokenData.put("filename", file.getName());
                tokenData.put("local", false);
                tokenData.put("nos_product", 0);
                tokenData.put("return_body", "{\"code\":200,\"size\":\"$(ObjectSize)\"}");
                tokenData.put("type", "other");
                tokenData.put("csrf_token", extractCsrfToken(cookie));
                String tokenResponse = weapiPost("/api/nos/token/alloc", tokenData.toString(), cookie);
                JSONObject tokenResult = new JSONObject(tokenResponse).optJSONObject("result");
                if (tokenResult == null) {
                    mainHandler.post(() -> callback.onError("上传凭证获取失败"));
                    return;
                }
                String objectKey = tokenResult.optString("objectKey", "");
                String token = tokenResult.optString("token", "");
                String docId = tokenResult.optString("docId", "");

                // 2. Upload image bytes to NOS
                String uploadUrl = "https://nosup-hz1.127.net/yyimgs/" + objectKey
                        + "?offset=0&complete=true&version=1.0";
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(uploadUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("x-nos-token", token);
                conn.setRequestProperty("Content-Type", "image/jpeg");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(60000);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(file.length());
                try (InputStream in = new java.io.FileInputStream(file);
                     java.io.OutputStream out = conn.getOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    out.flush();
                }
                int uploadCode = conn.getResponseCode();
                conn.disconnect();
                if (uploadCode < 200 || uploadCode >= 300) {
                    mainHandler.post(() -> callback.onError("图片上传失败(" + uploadCode + ")"));
                    return;
                }

                // 3. Apply the uploaded image as avatar (imgid = result.docId)
                JSONObject updateData = new JSONObject();
                updateData.put("imgid", docId);
                updateData.put("csrf_token", extractCsrfToken(cookie));
                String updateResponse = weapiPost("/api/user/avatar/upload/v1", updateData.toString(), cookie);
                JSONObject updateJson = new JSONObject(updateResponse);
                if (updateJson.optInt("code", -1) == 200) {
                    mainHandler.post(() -> callback.onResult(true));
                } else {
                    mainHandler.post(() -> callback.onError(updateJson.optString("message", "头像更新失败")));
                }
            } catch (Exception e) {
                MusicLog.w(TAG, "上传头像失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Get all top/chart lists.
     * (same as NeteaseCloudMusicApiBackup module/toplist.js)
     */
    public static void getTopList(String cookie, TopListCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/toplist", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONArray list = json.optJSONArray("list");
                if (list != null) {
                    mainHandler.post(() -> callback.onResult(list));
                } else {
                    mainHandler.post(() -> callback.onError("获取排行榜失败"));
                }
            } catch (Exception e) {
                MusicLog.w(TAG, "获取排行榜失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Get playlist detail (tracks) by playlist ID.
     */
    public static void getPlaylistDetail(long playlistId, String cookie, PlaylistDetailCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("id", playlistId);
                data.put("n", 0);  // Don't need full tracks in response, we'll use trackIds
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/v6/playlist/detail", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONObject playlist = json.optJSONObject("playlist");
                if (playlist == null) {
                    mainHandler.post(() -> callback.onError("获取歌单详情失败"));
                    return;
                }

                JSONArray trackIds = playlist.optJSONArray("trackIds");
                if (trackIds == null || trackIds.length() == 0) {
                    mainHandler.post(() -> callback.onResult(new ArrayList<>()));
                    return;
                }

                // Fetch all song details in batches using trackIds
                List<Song> songs = fetchSongDetailsByTrackIds(trackIds, csrfToken, cookie);
                mainHandler.post(() -> callback.onResult(songs));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取歌单详情失败: " + playlistId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Get playlist detail with metadata (tracks + creator/specialType info).
     * Used by PlaylistDetailActivity to self-correct metadata from API.
     */
    public static void getPlaylistDetailWithMeta(long playlistId, String cookie, PlaylistDetailWithMetaCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("id", playlistId);
                data.put("n", 0);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/v6/playlist/detail", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONObject playlist = json.optJSONObject("playlist");
                if (playlist == null) {
                    mainHandler.post(() -> callback.onError("获取歌单详情失败"));
                    return;
                }

                // Extract metadata
                int apiTrackCount = playlist.optInt("trackCount", 0);
                String creatorName = "";
                long creatorUserId = 0;
                JSONObject creatorObj = playlist.optJSONObject("creator");
                if (creatorObj != null) {
                    creatorName = creatorObj.optString("nickname", "");
                    creatorUserId = creatorObj.optLong("userId", 0);
                }
                int specialType = playlist.optInt("specialType", 0);
                boolean subscribed = playlist.optBoolean("subscribed", false);

                // Extract songs
                JSONArray trackIds = playlist.optJSONArray("trackIds");
                List<Song> songs;
                if (trackIds == null || trackIds.length() == 0) {
                    songs = new ArrayList<>();
                } else {
                    songs = fetchSongDetailsByTrackIds(trackIds, csrfToken, cookie);
                }

                final String cn = creatorName;
                final long cuid = creatorUserId;
                mainHandler.post(() -> callback.onResult(songs, apiTrackCount, cn, cuid, specialType, subscribed));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取歌单详情失败: " + playlistId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Get playlist metadata only (lightweight, no tracks).
     * Used by FavoritesListActivity to refresh track counts for locally saved playlists.
     */
    public static void getPlaylistMeta(long playlistId, String cookie, PlaylistMetaCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("id", playlistId);
                data.put("n", 0);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/v6/playlist/detail", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONObject playlist = json.optJSONObject("playlist");
                if (playlist == null) {
                    mainHandler.post(() -> callback.onError("获取歌单信息失败"));
                    return;
                }

                int trackCount = playlist.optInt("trackCount", 0);
                String creatorName = "";
                long creatorUserId = 0;
                JSONObject creatorObj = playlist.optJSONObject("creator");
                if (creatorObj != null) {
                    creatorName = creatorObj.optString("nickname", "");
                    creatorUserId = creatorObj.optLong("userId", 0);
                }
                int specialType = playlist.optInt("specialType", 0);
                boolean subscribed = playlist.optBoolean("subscribed", false);
                String coverUrl = playlist.optString("coverImgUrl", playlist.optString("picUrl", ""));

                final String cn = creatorName;
                final long cuid = creatorUserId;
                final String cu = coverUrl;
                mainHandler.post(() -> callback.onResult(trackCount, cn, cuid, specialType, subscribed));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    public static void getPlaylistMetaWithCover(long playlistId, String cookie, PlaylistMetaWithCoverCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("id", playlistId);
                data.put("n", 0);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/v6/playlist/detail", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONObject playlist = json.optJSONObject("playlist");
                if (playlist == null) {
                    mainHandler.post(() -> callback.onError("获取歌单信息失败"));
                    return;
                }

                int trackCount = playlist.optInt("trackCount", 0);
                String creatorName = "";
                long creatorUserId = 0;
                JSONObject creatorObj = playlist.optJSONObject("creator");
                if (creatorObj != null) {
                    creatorName = creatorObj.optString("nickname", "");
                    creatorUserId = creatorObj.optLong("userId", 0);
                }
                int specialType = playlist.optInt("specialType", 0);
                boolean subscribed = playlist.optBoolean("subscribed", false);
                String coverUrl = playlist.optString("coverImgUrl", playlist.optString("picUrl", ""));

                final String cn = creatorName;
                final long cuid = creatorUserId;
                final String cu = coverUrl;
                mainHandler.post(() -> callback.onResult(trackCount, cn, cuid, specialType, subscribed, cu));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Daily Recommend ====================

    public static void getDailyRecommendSongs(String cookie, SearchCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("csrf_token", extractCsrfToken(cookie));
                String response = weapiPost("/api/v3/discovery/recommend/songs", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONObject dataObj = json.optJSONObject("data");
                JSONArray songsArray = dataObj != null ? dataObj.optJSONArray("dailySongs") : null;
                if (songsArray == null) {
                    songsArray = json.optJSONArray("recommend");
                }
                List<Song> songs = new ArrayList<>();
                if (songsArray != null) {
                    for (int i = 0; i < songsArray.length(); i++) {
                        Song song = parseSongFromJson(songsArray.optJSONObject(i));
                        if (song != null) {
                            songs.add(song);
                        }
                    }
                }
                mainHandler.post(() -> callback.onResult(songs));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取每日推荐歌曲失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    public static void getRadarPlaylist(String cookie, DailyRecommendPlaylistCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("csrf_token", extractCsrfToken(cookie));
                String response = weapiPost("/api/v1/discovery/recommend/resource", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONArray recommend = json.optJSONArray("recommend");
                if (recommend == null || recommend.length() == 0) {
                    mainHandler.post(() -> callback.onError("暂无推荐歌单"));
                    return;
                }
                PlaylistInfo fallback = null;
                PlaylistInfo radar = null;
                for (int i = 0; i < recommend.length(); i++) {
                    JSONObject item = recommend.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    PlaylistInfo playlistInfo = parsePlaylistInfo(item);
                    if (playlistInfo == null) {
                        continue;
                    }
                    if (fallback == null) {
                        fallback = playlistInfo;
                    }
                    String name = playlistInfo.getName();
                    String copywriter = item.optString("copywriter", "");
                    if ((name != null && name.contains("雷达"))
                            || (copywriter != null && copywriter.contains("雷达"))) {
                        radar = playlistInfo;
                        break;
                    }
                }
                PlaylistInfo result = radar != null ? radar : fallback;
                if (result == null) {
                    mainHandler.post(() -> callback.onError("暂无推荐歌单"));
                } else {
                    mainHandler.post(() -> callback.onResult(result));
                }
            } catch (Exception e) {
                MusicLog.w(TAG, "获取雷达歌单失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Music Cloud ====================

    public static void getCloudItems(String cookie, CloudItemsCallback callback) {
        executor.execute(() -> {
            try {
                List<CloudItem> result = new ArrayList<>();
                int offset = 0;
                boolean hasMore = true;
                while (hasMore) {
                    JSONObject data = new JSONObject();
                    data.put("limit", 200);
                    data.put("offset", offset);
                    String response = weapiPost("/api/v1/cloud/get", data.toString(), cookie);
                    JSONObject json = new JSONObject(response);
                    JSONArray items = json.optJSONArray("data");
                    if (items == null || items.length() == 0) {
                        break;
                    }
                    for (int i = 0; i < items.length(); i++) {
                        CloudItem cloudItem = parseCloudItem(items.optJSONObject(i));
                        if (cloudItem != null) {
                            result.add(cloudItem);
                        }
                    }
                    hasMore = json.optBoolean("hasMore", false);
                    offset += items.length();
                }
                mainHandler.post(() -> callback.onResult(result));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取音乐云盘失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    public static void getCloudItemDetail(long cloudSongId, String cookie, CloudItemCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("songIds", new org.json.JSONArray().put(cloudSongId));
                String response = weapiPost("/api/v1/cloud/get/byids", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONArray list = json.optJSONArray("data");
                CloudItem item = (list != null && list.length() > 0)
                        ? parseCloudItem(list.optJSONObject(0)) : null;
                if (item == null) {
                    mainHandler.post(() -> callback.onError("未找到云盘文件"));
                } else {
                    mainHandler.post(() -> callback.onResult(item));
                }
            } catch (Exception e) {
                MusicLog.w(TAG, "获取云盘详情失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    public static void deleteCloudItem(long cloudSongId, String cookie, PlaylistActionCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("songIds", new org.json.JSONArray().put(cloudSongId));
                String response = weapiPost("/api/cloud/del", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                mainHandler.post(() -> callback.onResult(code == 200));
            } catch (Exception e) {
                MusicLog.w(TAG, "删除云盘文件失败: " + cloudSongId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    public static void uploadCloudFile(File uploadFile, String originalName, boolean musicFile,
                                       String cookie, UploadProgressCallback callback) {
        executor.execute(() -> {
            try {
                if (uploadFile == null || !uploadFile.exists() || uploadFile.length() <= 0) {
                    mainHandler.post(() -> callback.onError("文件无效"));
                    return;
                }
                updateUploadProgress(callback, 5, "正在校验文件...");
                String ext = getFileExtension(originalName);
                String md5 = md5(uploadFile);
                long size = uploadFile.length();
                String filenameBase = buildUploadFilename(originalName, ext);

                JSONObject checkData = new JSONObject();
                checkData.put("bitrate", DEFAULT_CLOUD_BITRATE);
                checkData.put("ext", "");
                checkData.put("length", size);
                checkData.put("md5", md5);
                checkData.put("songId", "0");
                checkData.put("version", 1);
                String uploadCheckResponse = eapiPost("/api/cloud/upload/check", checkData.toString(), cookie);
                JSONObject checkJson = new JSONObject(uploadCheckResponse);
                if (checkJson.optInt("code", -1) != 200) {
                    mainHandler.post(() -> callback.onError(checkJson.optString("message", "上传校验失败")));
                    return;
                }

                updateUploadProgress(callback, 20, "正在申请上传凭证...");
                JSONObject tokenData = new JSONObject();
                tokenData.put("bucket", CLOUD_UPLOAD_BUCKET);
                tokenData.put("ext", ext);
                tokenData.put("filename", filenameBase);
                tokenData.put("local", false);
                tokenData.put("nos_product", 3);
                tokenData.put("type", "audio");
                tokenData.put("md5", md5);
                String uploadTokenResponse = weapiPost("/api/nos/token/alloc", tokenData.toString(), cookie);
                JSONObject tokenJson = new JSONObject(uploadTokenResponse);
                JSONObject tokenResult = tokenJson.optJSONObject("result");
                if (tokenResult == null) {
                    mainHandler.post(() -> callback.onError("上传凭证获取失败"));
                    return;
                }

                updateUploadProgress(callback, 30, "正在连接上传节点...");
                String lbsResponse = getRaw("https://wanproxy.127.net/lbs?version=1.0&bucketname=" + CLOUD_UPLOAD_BUCKET);
                JSONObject lbsJson = new JSONObject(lbsResponse);
                JSONArray uploadHosts = lbsJson.optJSONArray("upload");
                if (uploadHosts == null || uploadHosts.length() == 0) {
                    mainHandler.post(() -> callback.onError("上传节点获取失败"));
                    return;
                }

                String objectKey = tokenResult.optString("objectKey", "").replace("/", "%2F");
                String uploadUrl = uploadHosts.optString(0, "") + "/" + CLOUD_UPLOAD_BUCKET + "/" + objectKey
                        + "?offset=0&complete=true&version=1.0";
                uploadFileToNos(uploadUrl, tokenResult.optString("token", ""), md5, uploadFile, callback);

                updateUploadProgress(callback, 92, "正在提交云盘信息...");
                JSONObject infoData = new JSONObject();
                infoData.put("md5", md5);
                infoData.put("songid", checkJson.optString("songId", "0"));
                infoData.put("filename", originalName);
                infoData.put("song", stripExtension(originalName));
                infoData.put("album", "未知专辑");
                infoData.put("artist", "未知艺术家");
                infoData.put("bitrate", DEFAULT_CLOUD_BITRATE);
                infoData.put("resourceId", tokenResult.optString("resourceId", ""));
                String infoResponse = eapiPost("/api/upload/cloud/info/v2", infoData.toString(), cookie);
                JSONObject infoJson = new JSONObject(infoResponse);
                if (infoJson.optInt("code", -1) != 200) {
                    mainHandler.post(() -> callback.onError(infoJson.optString("message", "云盘信息提交失败")));
                    return;
                }
                long songId = infoJson.optLong("songId", 0);

                JSONObject pubData = new JSONObject();
                pubData.put("songid", songId);
                String pubResponse = eapiPost("/api/cloud/pub/v2", pubData.toString(), cookie);
                JSONObject pubJson = new JSONObject(pubResponse);
                int code = pubJson.optInt("code", -1);
                if (code == 200) {
                    mainHandler.post(() -> callback.onSuccess("上传成功"));
                } else {
                    mainHandler.post(() -> callback.onError(pubJson.optString("message", "上传失败")));
                }
            } catch (Exception e) {
                MusicLog.w(TAG, "上传云盘文件失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Personal FM ====================

    /**
     * Get one personal FM batch using a specific mode/subMode.
     * (matches NeteaseCloudMusicApiBackup module/personal_fm_mode.js)
     * Valid modes: DEFAULT, FAMILIAR, EXPLORE, SCENE_RCMD, aidj
     * Valid subModes for SCENE_RCMD: EXERCISE, FOCUS, NIGHT_EMO
     * Each request returns about 3 songs.
     */
    public static void getPersonalFMWithMode(String mode, String subMode, String cookie, PersonalFMCallback callback) {
        executor.execute(() -> {
            try {
                String csrfToken = extractCsrfToken(cookie);
                List<Song> songs = new ArrayList<>();
                java.util.Set<Long> seenIds = new java.util.HashSet<>();

                JSONObject data = new JSONObject();
                data.put("csrf_token", csrfToken);
                if (mode != null && !mode.isEmpty()) {
                    data.put("mode", mode);
                }
                if (subMode != null && !subMode.isEmpty()) {
                    data.put("subMode", subMode);
                }
                data.put("limit", 3);

                String response = weapiPost("/api/v1/radio/get", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONArray dataArr = json.optJSONArray("data");
                if (dataArr != null) {
                    for (int i = 0; i < dataArr.length(); i++) {
                        JSONObject s = dataArr.getJSONObject(i);
                        long id = s.getLong("id");
                        if (seenIds.contains(id)) continue;
                        seenIds.add(id);
                        String name = s.getString("name");
                        String artist = "";
                        JSONArray artists = s.optJSONArray("artists");
                        if (artists != null && artists.length() > 0) {
                            artist = artists.getJSONObject(0).optString("name", "");
                        }
                        String album = "";
                        JSONObject albumObj = s.optJSONObject("album");
                        if (albumObj != null) {
                            album = albumObj.optString("name", "");
                        }
                        Song fmSong = new Song(id, name, artist, album);
                        fmSong.setFee(s.optInt("fee", 0));
                        songs.add(fmSong);
                    }
                }

                final List<Song> result = songs;
                MusicLog.d(TAG, "私人漫游(" + mode + ")获取完成: " + result.size() + " 首");
                mainHandler.post(() -> callback.onResult(result));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取私人漫游失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Get one personal FM batch (default mode).
     * (same as NeteaseCloudMusicApiBackup module/personal_fm.js)
     * Each request returns about 3 songs.
     * Callers should request another batch when they need to extend the FM queue.
     */
    public static void getPersonalFM(String cookie, PersonalFMCallback callback) {
        executor.execute(() -> {
            try {
                String csrfToken = extractCsrfToken(cookie);
                List<Song> songs = new ArrayList<>();
                java.util.Set<Long> seenIds = new java.util.HashSet<>();

                JSONObject data = new JSONObject();
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/v1/radio/get", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONArray dataArr = json.optJSONArray("data");
                if (dataArr != null) {
                    for (int i = 0; i < dataArr.length(); i++) {
                        JSONObject s = dataArr.getJSONObject(i);
                        long id = s.getLong("id");
                        if (seenIds.contains(id)) continue;
                        seenIds.add(id);
                        String name = s.getString("name");
                        String artist = "";
                        JSONArray artists = s.optJSONArray("artists");
                        if (artists != null && artists.length() > 0) {
                            artist = artists.getJSONObject(0).optString("name", "");
                        }
                        String album = "";
                        JSONObject albumObj = s.optJSONObject("album");
                        if (albumObj != null) {
                            album = albumObj.optString("name", "");
                        }
                        Song fmSong = new Song(id, name, artist, album);
                        fmSong.setFee(s.optInt("fee", 0));
                        songs.add(fmSong);
                    }
                }

                final List<Song> result = songs;
                MusicLog.d(TAG, "私人漫游获取完成: " + result.size() + " 首");
                mainHandler.post(() -> callback.onResult(result));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取私人漫游失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Extract user ID from cookie string (MUSIC_U contains user session).
     * Uses /api/w/nuser/account/get to get the user's account info.
     */
    private static long extractUidFromCookie(String cookie) {
        if (cookie == null || cookie.isEmpty()) return -1;
        try {
            JSONObject data = new JSONObject();
            String csrfToken = extractCsrfToken(cookie);
            data.put("csrf_token", csrfToken);

            String response = weapiPost("/api/w/nuser/account/get", data.toString(), cookie);
            JSONObject json = new JSONObject(response);
            JSONObject account = json.optJSONObject("account");
            if (account != null) {
                return account.optLong("id", -1);
            }
        } catch (Exception e) {
            MusicLog.w(TAG, "提取UID失败", e);
        }
        return -1;
    }

    // ==================== Song Detail ====================

    /**
     * Get detailed song info via /api/v3/song/detail.
     * Returns full song metadata including duration, album info, artist info, etc.
     */
    public static void getSongDetail(long songId, String cookie, SongDetailCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取歌曲详情", "songId=" + songId);
                JSONArray songIds = new JSONArray();
                JSONObject idObj = new JSONObject();
                idObj.put("id", songId);
                songIds.put(idObj);

                JSONObject data = new JSONObject();
                data.put("c", songIds.toString());
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/v3/song/detail", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONArray songs = json.optJSONArray("songs");
                if (songs != null && songs.length() > 0) {
                    mainHandler.post(() -> callback.onResult(songs.optJSONObject(0)));
                } else {
                    mainHandler.post(() -> callback.onError("未找到歌曲信息"));
                }
            } catch (Exception e) {
                MusicLog.w(TAG, "获取歌曲详情失败: " + songId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    public static void getSongChorus(long songId, String cookie, ChorusCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取副歌时间", "songId=" + songId);
                JSONObject data = new JSONObject();
                data.put("ids", new JSONArray().put(songId).toString());
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/song/chorus", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                ChorusInfo chorusInfo = parseChorusInfo(json);
                mainHandler.post(() -> callback.onResult(chorusInfo));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取副歌时间失败: " + songId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Song Wiki ====================

    public static void getSongWikiSummary(long songId, String cookie, SongWikiCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取歌曲百科", "songId=" + songId);
                JSONObject data = new JSONObject();
                data.put("songId", songId);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                String response = weapiPost("/api/song/play/about/block/page", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                mainHandler.post(() -> callback.onResult(json));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取歌曲百科失败: " + songId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    private static ChorusInfo parseChorusInfo(Object source) {
        if (source == null) {
            return null;
        }
        if (source instanceof JSONObject) {
            JSONObject json = (JSONObject) source;
            ChorusInfo direct = extractChorusInfoFromObject(json);
            if (direct != null) {
                return direct;
            }
            JSONArray names = json.names();
            if (names == null) {
                return null;
            }
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                ChorusInfo nested = parseChorusInfo(json.opt(key));
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (source instanceof JSONArray) {
            JSONArray array = (JSONArray) source;
            for (int i = 0; i < array.length(); i++) {
                ChorusInfo nested = parseChorusInfo(array.opt(i));
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static ChorusInfo extractChorusInfoFromObject(JSONObject json) {
        long startMs = firstLong(json,
                "startTime", "startMs", "chorusStartTime", "chorusStart", "start", "beginTime", "begin");
        if (startMs < 0L) {
            return null;
        }
        long endMs = firstLong(json,
                "endTime", "endMs", "chorusEndTime", "chorusEnd", "end", "stopTime", "stop");
        if (endMs < startMs) {
            long durationMs = firstLong(json, "duration", "durationMs", "length", "segmentDuration");
            if (durationMs >= 0L) {
                endMs = startMs + durationMs;
            }
        }
        if (endMs < startMs) {
            return null;
        }
        return new ChorusInfo(startMs, endMs);
    }

    private static long firstLong(JSONObject json, String... keys) {
        for (String key : keys) {
            if (!json.has(key) || json.isNull(key)) {
                continue;
            }
            Object value = json.opt(key);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            if (value instanceof String) {
                try {
                    return Long.parseLong((String) value);
                } catch (Exception ignored) {
                }
            }
        }
        return -1L;
    }

    // ==================== Artist Description ====================

    public static void getArtistDesc(long artistId, String cookie, ArtistDescCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取歌手百科", "artistId=" + artistId);
                JSONObject data = new JSONObject();
                data.put("id", artistId);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                String response = weapiPost("/api/artist/introduction", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                String briefDesc = json.optString("briefDesc", "");
                JSONArray introduction = json.optJSONArray("introduction");
                mainHandler.post(() -> callback.onResult(briefDesc, introduction != null ? introduction : new JSONArray()));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取歌手简介失败: " + artistId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Similar Songs & Playlists ====================

    /** simi 系列里「相似歌曲 / 相似歌单 / 相似歌手」三种。 */
    private static final int SIMI_KIND_SONG = 0;
    private static final int SIMI_KIND_PLAYLIST = 1;
    private static final int SIMI_KIND_ARTIST = 2;

    private static final int SIMI_STYLE_UNKNOWN = 0;
    private static final int SIMI_STYLE_BODY = 1;
    private static final int SIMI_STYLE_QUERY = 2;

    /**
     * simi 系列（相似歌曲/歌单/歌手）在当前网络下该把 id 放哪：
     * 0 未知 / 1 请求体 / 2 URL。
     *
     * <p><b>为什么需要这个</b>：这三个接口对「参数放哪」挑得很怪 —— 同一个账号，
     * 从 PC 出口只认 query（{@code ?songid=}／{@code ?artistid=}），从手表的网络出口
     * 用 query 会<b>一直挂着不返回</b>（15s 读超时），换成请求体才正常
     * （2026-10-03 两个出口各测数轮，稳定复现）。</p>
     *
     * <p><b>为什么记下来</b>：试错代价不对称 —— 请求体不被接受时上游秒回
     * {@code 参数错误}，而 query 不被接受时会挂满 15s。所以①未知时先试请求体
     * （试错便宜），②一旦确认哪种可用就只发那一种，不让用户每次白等一次超时。</p>
     */
    private static volatile int simiParamStyle = SIMI_STYLE_UNKNOWN;

    /** 这个 kind 的响应里放数组的字段名。 */
    private static String simiArrayKey(int kind) {
        switch (kind) {
            case SIMI_KIND_PLAYLIST:
                return "playlists";
            case SIMI_KIND_ARTIST:
                return "artists";
            case SIMI_KIND_SONG:
            default:
                return "songs";
        }
    }

    /** id 放请求体（不同接口的字段名不一样，相似歌手要字符串型的 {@code id}）。 */
    private static JSONArray simiViaBody(int kind, long id, String cookie) {
        try {
            JSONObject data = new JSONObject();
            switch (kind) {
                case SIMI_KIND_PLAYLIST:
                    data.put("songid", id);
                    data.put("limit", 10);
                    data.put("offset", 0);
                    break;
                case SIMI_KIND_ARTIST:
                    data.put("id", String.valueOf(id));
                    break;
                case SIMI_KIND_SONG:
                default:
                    data.put("songid", id);
                    data.put("limit", 30);
                    data.put("offset", 0);
                    break;
            }
            data.put("csrf_token", extractCsrfToken(cookie));
            String response = weapiPost(simiPath(kind, false, id), data.toString(), cookie);
            return new JSONObject(response).optJSONArray(simiArrayKey(kind));
        } catch (Exception e) {
            MusicLog.w(TAG, "相似内容(请求体传参)失败 kind=" + kind + " id=" + id, e);
            return null;
        }
    }

    /** id 放 URL。 */
    private static JSONArray simiViaQuery(int kind, long id, String cookie) {
        try {
            JSONObject data = new JSONObject();
            data.put("csrf_token", extractCsrfToken(cookie));
            String response = weapiPost(simiPath(kind, true, id), data.toString(), cookie);
            return new JSONObject(response).optJSONArray(simiArrayKey(kind));
        } catch (Exception e) {
            MusicLog.w(TAG, "相似内容(query 传参)失败 kind=" + kind + " id=" + id, e);
            return null;
        }
    }

    /** 路径拼装；{@code withQuery} 决定 id 是拼在 URL 上还是留给请求体。 */
    private static String simiPath(int kind, boolean withQuery, long id) {
        switch (kind) {
            case SIMI_KIND_PLAYLIST:
                return withQuery
                        ? "/api/discovery/simiPlaylist?songid=" + id
                        : "/api/discovery/simiPlaylist";
            case SIMI_KIND_ARTIST:
                return withQuery
                        ? "/api/discovery/simiArtist?artistid=" + id
                        : "/api/discovery/simiArtist";
            case SIMI_KIND_SONG:
            default:
                return withQuery
                        ? "/api/v1/discovery/simiSong?songid=" + id
                        : "/api/v1/discovery/simiSong";
        }
    }

    /**
     * 按 {@link #simiParamStyle} 的记忆拉一次；未知（或记住的那种这次也没成）时
     * 请求体、URL 各试一遍。都失败返回 null（不写记忆，下次重新试）。
     */
    private static JSONArray fetchSimiRaw(int kind, long id, String cookie) {
        final int style = simiParamStyle;
        if (style == SIMI_STYLE_BODY) {
            JSONArray a = simiViaBody(kind, id, cookie);
            if (a != null) {
                return a;
            }
        } else if (style == SIMI_STYLE_QUERY) {
            JSONArray a = simiViaQuery(kind, id, cookie);
            if (a != null) {
                return a;
            }
        }
        JSONArray viaBody = simiViaBody(kind, id, cookie);
        if (viaBody != null) {
            simiParamStyle = SIMI_STYLE_BODY;
            return viaBody;
        }
        JSONArray viaQuery = simiViaQuery(kind, id, cookie);
        if (viaQuery != null) {
            simiParamStyle = SIMI_STYLE_QUERY;
            return viaQuery;
        }
        return null;
    }

    /**
     * Get similar songs via /api/v1/discovery/simiSong.
     * Returns JSONArray of song objects with id, name, artists[], album{}, duration, etc.
     */
    public static void getSimiSong(long songId, String cookie, SimiSongCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取相似歌曲", "songId=" + songId + " style=" + simiParamStyle);
                JSONArray songs = fetchSimiRaw(SIMI_KIND_SONG, songId, cookie);
                final JSONArray out = songs != null ? songs : new JSONArray();
                mainHandler.post(() -> callback.onResult(out));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取相似歌曲失败: " + songId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /** 相似歌曲（weapi），直接给可播放的 Song 列表。 */
    public static void getSimiSongList(long songId, String cookie, SongListCallback callback) {
        getSimiSong(songId, cookie, new SimiSongCallback() {
            @Override
            public void onResult(JSONArray songs) {
                callback.onResult(parseSongArray(songs));
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    /**
     * Get similar playlists via /api/discovery/simiPlaylist.
     * Returns JSONArray of playlist objects with id, name, playCount, trackCount, coverImgUrl, etc.
     */
    public static void getSimiPlaylist(long songId, String cookie, SimiPlaylistCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取相似歌单", "songId=" + songId);
                JSONArray playlists = fetchSimiRaw(SIMI_KIND_PLAYLIST, songId, cookie);
                final JSONArray out = playlists != null ? playlists : new JSONArray();
                mainHandler.post(() -> callback.onResult(out));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取相似歌单失败: " + songId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /** 相似歌手条目。 */
    public static class ArtistBrief {
        public long id;
        public String name = "";
        public String picUrl = "";
        /** 别名，多个用 " / " 连接；没有则为空。 */
        public String alias = "";
        public int musicSize;
        public int albumSize;
    }

    public interface SimiArtistCallback {
        void onResult(List<ArtistBrief> artists);

        void onError(String message);
    }

    /**
     * 相似歌手（weapi /api/discovery/simiArtist）。
     *
     * <p>参数位置同样两种都试 —— 详见 {@link #fetchSimiRaw}。URL 形式用全小写的
     * {@code ?artistid=}（用 {@code id} / {@code artistId} 会回参数错误）。</p>
     */
    public static void getSimiArtistList(long artistId, String cookie, SimiArtistCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取相似歌手", "artistId=" + artistId + " style=" + simiParamStyle);
                JSONArray arr = fetchSimiRaw(SIMI_KIND_ARTIST, artistId, cookie);
                List<ArtistBrief> out = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject a = arr.optJSONObject(i);
                        if (a == null) {
                            continue;
                        }
                        long id = a.optLong("id", 0);
                        if (id <= 0) {
                            continue;
                        }
                        ArtistBrief brief = new ArtistBrief();
                        brief.id = id;
                        brief.name = a.optString("name", "");
                        brief.picUrl = a.optString("picUrl", "");
                        brief.musicSize = a.optInt("musicSize", 0);
                        brief.albumSize = a.optInt("albumSize", 0);
                        JSONArray aliasArr = a.optJSONArray("alias");
                        if (aliasArr != null && aliasArr.length() > 0) {
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < aliasArr.length(); j++) {
                                String one = aliasArr.optString(j, "");
                                if (one.isEmpty()) {
                                    continue;
                                }
                                if (sb.length() > 0) {
                                    sb.append(" / ");
                                }
                                sb.append(one);
                            }
                            brief.alias = sb.toString();
                        }
                        out.add(brief);
                    }
                }
                mainHandler.post(() -> callback.onResult(out));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取相似歌手失败: " + artistId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Comments ====================

    /**
     * Get comments for a song.
     * sortType: 99=recommended, 2=hot, 3=newest
     */
    public static void getComments(long songId, int pageNo, int pageSize, int sortType,
                                    String cursor, String cookie, CommentCallback callback) {
        executor.execute(() -> {
            try {
                String threadId = SONG_COMMENT_THREAD_PREFIX + songId;
                JSONObject data = new JSONObject();
                data.put("threadId", threadId);
                data.put("pageNo", pageNo);
                data.put("showInner", true);
                data.put("pageSize", pageSize);
                data.put("sortType", sortType);
                // Build cursor based on sortType
                String cursorVal;
                if (cursor != null && !cursor.isEmpty()) {
                    cursorVal = cursor;
                } else {
                    switch (sortType) {
                        case 2:
                            cursorVal = "normalHot#" + ((pageNo - 1) * pageSize);
                            break;
                        case 3:
                            cursorVal = "0";
                            break;
                        case 99:
                        default:
                            cursorVal = String.valueOf((pageNo - 1) * pageSize);
                            break;
                    }
                }
                data.put("cursor", cursorVal);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                String response = weapiPost("/api/v2/resource/comments", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                mainHandler.post(() -> callback.onResult(json));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取评论失败: " + songId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Post a new comment on a song.
     */
    public static void postComment(long songId, String content, String cookie, CommentActionCallback callback) {
        executor.execute(() -> {
            try {
                String threadId = SONG_COMMENT_THREAD_PREFIX + songId;
                JSONObject data = new JSONObject();
                data.put("threadId", threadId);
                data.put("content", content);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                String response = weapiPost("/api/resource/comments/add", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                mainHandler.post(() -> callback.onResult(code == 200));
            } catch (Exception e) {
                MusicLog.w(TAG, "发送评论失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Reply to a comment on a song.
     */
    public static void replyComment(long songId, long commentId, String content, String cookie, CommentActionCallback callback) {
        executor.execute(() -> {
            try {
                String threadId = SONG_COMMENT_THREAD_PREFIX + songId;
                JSONObject data = new JSONObject();
                data.put("threadId", threadId);
                data.put("commentId", commentId);
                data.put("content", content);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                String response = weapiPost("/api/resource/comments/reply", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                mainHandler.post(() -> callback.onResult(code == 200));
            } catch (Exception e) {
                MusicLog.w(TAG, "回复评论失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Like or unlike a comment.
     * @param like true to like, false to unlike
     */
    public static void likeComment(long songId, long commentId, boolean like, String cookie, CommentActionCallback callback) {
        executor.execute(() -> {
            try {
                String threadId = SONG_COMMENT_THREAD_PREFIX + songId;
                String action = like ? "like" : "unlike";
                JSONObject data = new JSONObject();
                data.put("threadId", threadId);
                data.put("commentId", commentId);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                String response = weapiPost("/api/v1/comment/" + action, data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                mainHandler.post(() -> callback.onResult(code == 200));
            } catch (Exception e) {
                MusicLog.w(TAG, "点赞评论失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Delete a comment on a song.
     * Based on NeteaseCloudMusicApiBackup: /api/resource/comments/delete
     */
    public static void deleteComment(long songId, long commentId, String cookie, CommentActionCallback callback) {
        executor.execute(() -> {
            try {
                String threadId = SONG_COMMENT_THREAD_PREFIX + songId;
                JSONObject data = new JSONObject();
                data.put("threadId", threadId);
                data.put("commentId", commentId);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                MusicLog.op(TAG, "删除评论", "songId=" + songId + ", commentId=" + commentId);
                String response = weapiPost("/api/resource/comments/delete", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                mainHandler.post(() -> callback.onResult(code == 200));
            } catch (Exception e) {
                MusicLog.w(TAG, "删除评论失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Get floor (sub) comments for a parent comment.
     */
    public static void getFloorComments(long songId, long parentCommentId, int limit, long time,
                                         String cookie, CommentCallback callback) {
        executor.execute(() -> {
            try {
                String threadId = SONG_COMMENT_THREAD_PREFIX + songId;
                JSONObject data = new JSONObject();
                data.put("parentCommentId", parentCommentId);
                data.put("threadId", threadId);
                data.put("time", time);
                data.put("limit", limit);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                String response = weapiPost("/api/resource/comment/floor/get", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                mainHandler.post(() -> callback.onResult(json));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取楼层评论失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Lyrics with Translation ====================

    private static JSONObject buildLyricRequestData(long songId, String cookie) throws Exception {
        JSONObject data = new JSONObject();
        data.put("id", songId);
        data.put("cp", false);
        data.put("tv", 0);
        data.put("lv", 0);
        data.put("rv", 0);
        data.put("kv", 0);
        data.put("yv", 0);
        data.put("ytv", 0);
        data.put("yrv", 0);
        data.put("csrf_token", extractCsrfToken(cookie));
        return data;
    }

    /**
     * Fetch lyrics with translation for a song by its ID.
     * Returns original LRC, translated LRC (tlyric) and romanized LRC (romalrc).
     *
     * <p>音译歌词（romalrc，日文歌的发音）走同一个 /api/song/lyric/v1 接口。
     * {@link #buildLyricRequestData} 里的 {@code rv=0} 不需要改 —— 既有的 {@code tv=0}
     * 一样能拿到 tlyric，说明这些标志位 0 表示「按默认返回」而不是禁用。</p>
     */
    public static void getLyricsWithTranslation(long songId, String cookie, LyricsFullCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject data = buildLyricRequestData(songId, cookie);
                String response = weapiPost("/api/song/lyric/v1", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                String lrc = "";
                JSONObject lrcObj = json.optJSONObject("lrc");
                if (lrcObj != null) {
                    lrc = lrcObj.optString("lyric", "");
                }
                String tlyric = "";
                JSONObject tlyricObj = json.optJSONObject("tlyric");
                if (tlyricObj != null) {
                    tlyric = tlyricObj.optString("lyric", "");
                }
                String romalrc = "";
                JSONObject romalrcObj = json.optJSONObject("romalrc");
                if (romalrcObj != null) {
                    romalrc = romalrcObj.optString("lyric", "");
                }
                final String finalLrc = lrc;
                final String finalTlyric = tlyric;
                final String finalRomalrc = romalrc;
                mainHandler.post(() -> callback.onResult(finalLrc, finalTlyric, finalRomalrc));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取歌词(含翻译)失败: " + songId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Fetch translated lyrics synchronously (for downloads).
     * Returns the tlyric LRC text, or null if unavailable.
     */
    public static String fetchTranslatedLyricsSync(long songId, String cookie) {
        try {
            JSONObject data = buildLyricRequestData(songId, cookie);
            String response = weapiPost("/api/song/lyric/v1", data.toString(), cookie);
            JSONObject json = new JSONObject(response);
            JSONObject tlyricObj = json.optJSONObject("tlyric");
            if (tlyricObj != null) {
                String tlyric = tlyricObj.optString("lyric", "");
                if (!tlyric.isEmpty()) return tlyric;
            }
            return null;
        } catch (Exception e) {
            MusicLog.w(TAG, "翻译歌词同步获取失败: " + songId, e);
            return null;
        }
    }

    /**
     * Fetch romanized lyrics synchronously (used when downloading, so the
     * 音译歌词 can be played offline too). Returns null when unavailable.
     */
    public static String fetchRomajiLyricsSync(long songId, String cookie) {
        try {
            JSONObject data = buildLyricRequestData(songId, cookie);
            String response = weapiPost("/api/song/lyric/v1", data.toString(), cookie);
            JSONObject json = new JSONObject(response);
            JSONObject romalrcObj = json.optJSONObject("romalrc");
            if (romalrcObj != null) {
                String romalrc = romalrcObj.optString("lyric", "");
                if (!romalrc.isEmpty()) return romalrc;
            }
            return null;
        } catch (Exception e) {
            MusicLog.w(TAG, "音译歌词同步获取失败: " + songId, e);
            return null;
        }
    }

    // ==================== weapi POST ====================

    /**
     * Send a weapi-encrypted POST request to NetEase.
     * @param apiPath The API path (e.g. "/api/cloudsearch/pc")
     * @param jsonData The JSON data to encrypt
     * @param cookie The user cookie string (can be null)
     * @return The response body as string
     */
    private static String weapiPost(String apiPath, String jsonData, String cookie) throws Exception {
        String[] encrypted = NeteaseApiCrypto.weapi(jsonData);

        String postBody = "params=" + URLEncoder.encode(encrypted[0], "UTF-8")
                + "&encSecKey=" + URLEncoder.encode(encrypted[1], "UTF-8");

        // weapi URL: replace /api/ with /weapi/
        String weapiPath = apiPath.replaceFirst("^/api/", "/weapi/");
        String urlStr = DOMAIN + weapiPath;
        MusicLog.i(TAG, "[REQ] POST " + urlStr + "\n  请求体(原文): " + jsonData);

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Referer", DOMAIN);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Cookie", buildAnonymousCookie(cookie));
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);

        try {
            OutputStream os = conn.getOutputStream();
            os.write(postBody.getBytes("UTF-8"));
            os.close();

            int responseCode = conn.getResponseCode();
            BufferedReader reader;
            if (responseCode >= 200 && responseCode < 400) {
                reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
            } else {
                java.io.InputStream errStream = conn.getErrorStream();
                reader = new BufferedReader(
                        new InputStreamReader(errStream != null ? errStream : conn.getInputStream(), "UTF-8"));
            }
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            String responseBody = sb.toString();
            MusicLog.api(TAG, "POST", urlStr, responseCode, responseBody);
            return responseBody;
        } finally {
            conn.disconnect();
        }
    }

    // ==================== Utility ====================

    /**
     * Send a weapi-encrypted POST request using mobile device identity.
     * Used for SMS login operations to avoid "环境不安全" error.
     */
    private static String weapiPostMobile(String apiPath, String jsonData, String cookie) throws Exception {
        String[] encrypted = NeteaseApiCrypto.weapi(jsonData);

        String postBody = "params=" + URLEncoder.encode(encrypted[0], "UTF-8")
                + "&encSecKey=" + URLEncoder.encode(encrypted[1], "UTF-8");

        String weapiPath = apiPath.replaceFirst("^/api/", "/weapi/");
        String urlStr = DOMAIN + weapiPath;
        MusicLog.i(TAG, "[REQ] POST(mobile) " + urlStr + "\n  请求体(原文): " + jsonData);

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("User-Agent", MOBILE_USER_AGENT);
        conn.setRequestProperty("Referer", DOMAIN);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Cookie", buildMobileCookie(cookie));
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);

        try {
            OutputStream os = conn.getOutputStream();
            os.write(postBody.getBytes("UTF-8"));
            os.close();

            int responseCode = conn.getResponseCode();
            BufferedReader reader;
            if (responseCode >= 200 && responseCode < 400) {
                reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
            } else {
                java.io.InputStream errStream = conn.getErrorStream();
                reader = new BufferedReader(
                        new InputStreamReader(errStream != null ? errStream : conn.getInputStream(), "UTF-8"));
            }
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            String responseBody = sb.toString();
            MusicLog.api(TAG, "POST(mobile)", urlStr, responseCode, responseBody);
            return responseBody;
        } finally {
            conn.disconnect();
        }
    }

    private static String eapiPost(String apiPath, String jsonData, String cookie) throws Exception {
        JSONObject data = new JSONObject(jsonData);
        JSONObject header = buildEapiHeader(cookie);
        data.put("header", header);
        data.put("e_r", false);

        String params = NeteaseApiCrypto.eapi(apiPath, data.toString());
        String postBody = "params=" + URLEncoder.encode(params, "UTF-8");

        String eapiPath = apiPath.replaceFirst("^/api/", "/eapi/");
        String urlStr = API_DOMAIN + eapiPath;
        MusicLog.i(TAG, "[REQ] POST(eapi) " + urlStr + "\n  请求体(原文): " + jsonData);

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("User-Agent", "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)");
        conn.setRequestProperty("Referer", DOMAIN);
        conn.setRequestProperty("Origin", DOMAIN);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Cookie", buildEapiCookie(cookie, header));
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);

        try {
            OutputStream os = conn.getOutputStream();
            os.write(postBody.getBytes("UTF-8"));
            os.close();

            int responseCode = conn.getResponseCode();
            BufferedReader reader;
            if (responseCode >= 200 && responseCode < 400) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            } else {
                InputStream errStream = conn.getErrorStream();
                reader = new BufferedReader(new InputStreamReader(
                        errStream != null ? errStream : conn.getInputStream(), "UTF-8"));
            }
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            String responseBody = sb.toString();
            MusicLog.api(TAG, "POST(eapi)", urlStr, responseCode, responseBody);
            return responseBody;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Build a cookie string with Android mobile device identity.
     * Used for SMS login to avoid "环境不安全" error.
     * Based on NeteaseCloudMusicApiBackup util/request.js default cookies.
     */
    private static String buildMobileCookie(String existingCookie) {
        StringBuilder sb = new StringBuilder();
        if (existingCookie != null && !existingCookie.isEmpty()) {
            sb.append(existingCookie);
            if (!existingCookie.endsWith("; ")) {
                sb.append("; ");
            }
        }
        sb.append("__remember_me=true; ");
        sb.append("ntes_kaola_ad=1; ");
        sb.append("WEVNSM=1.0.0; ");
        sb.append("NMTID=").append(generateHexId(16)).append("; ");
        sb.append("_ntes_nuid=").append(generateHexId(16)).append("; ");
        sb.append("osver=13; ");
        sb.append("deviceId=").append(deviceId).append("; ");
        sb.append("os=android; ");
        sb.append("channel=").append(CHANNEL).append("; ");
        sb.append("appver=").append(APP_VER);
        return sb.toString();
    }

    /**
     * Generate a random hex string of the specified byte length.
     */
    private static String generateHexId(int byteLength) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        byte[] bytes = new byte[byteLength];
        random.nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Build a cookie string with proper version/device info.
     * This prevents "version too old" errors from the NetEase server.
     * Based on NeteaseCloudMusicApiBackup util/request.js cookie construction.
     */
    private static String buildAnonymousCookie(String existingCookie) {
        StringBuilder sb = new StringBuilder();
        // Preserve existing cookie values
        if (existingCookie != null && !existingCookie.isEmpty()) {
            sb.append(existingCookie);
            if (!existingCookie.endsWith("; ")) {
                sb.append("; ");
            }
        }
        // Add version/device info that the server expects
        sb.append("__remember_me=true; ");
        sb.append("ntes_kaola_ad=1; ");
        sb.append("WEVNSM=1.0.0; ");
        sb.append("osver=").append(URLEncoder_safe(OS_VER)).append("; ");
        sb.append("deviceId=").append(deviceId).append("; ");
        sb.append("os=").append(URLEncoder_safe(OS_TYPE)).append("; ");
        sb.append("channel=").append(CHANNEL).append("; ");
        sb.append("appver=").append(APP_VER);
        return sb.toString();
    }

    private static JSONObject buildEapiHeader(String existingCookie) throws org.json.JSONException {
        JSONObject header = new JSONObject();
        JSONObject cookieMap = parseCookieString(existingCookie);
        header.put("osver", cookieMap.optString("osver", OS_VER));
        header.put("deviceId", cookieMap.optString("deviceId", deviceId));
        header.put("os", cookieMap.optString("os", OS_TYPE));
        header.put("appver", cookieMap.optString("appver", APP_VER));
        header.put("versioncode", cookieMap.optString("versioncode", VERSION_CODE));
        header.put("mobilename", cookieMap.optString("mobilename", ""));
        header.put("buildver", cookieMap.optString("buildver", String.valueOf(System.currentTimeMillis() / 1000L)));
        header.put("resolution", cookieMap.optString("resolution", "320x360"));
        header.put("__csrf", cookieMap.optString("__csrf", extractCsrfToken(existingCookie)));
        header.put("channel", cookieMap.optString("channel", CHANNEL));
        header.put("requestId", buildRequestId());
        String musicU = cookieMap.optString("MUSIC_U", "");
        if (!musicU.isEmpty()) {
            header.put("MUSIC_U", musicU);
        }
        String musicA = cookieMap.optString("MUSIC_A", "");
        if (!musicA.isEmpty()) {
            header.put("MUSIC_A", musicA);
        }
        return header;
    }

    private static String buildEapiCookie(String existingCookie, JSONObject header) {
        StringBuilder sb = new StringBuilder();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add("__remember_me");
        keys.add("ntes_kaola_ad");
        keys.add("WEVNSM");
        keys.add("osver");
        keys.add("deviceId");
        keys.add("os");
        keys.add("appver");
        keys.add("versioncode");
        keys.add("mobilename");
        keys.add("buildver");
        keys.add("resolution");
        keys.add("__csrf");
        keys.add("channel");
        keys.add("requestId");
        keys.add("MUSIC_U");
        keys.add("MUSIC_A");

        for (String key : keys) {
            String value;
            switch (key) {
                case "__remember_me":
                    value = "true";
                    break;
                case "ntes_kaola_ad":
                    value = "1";
                    break;
                case "WEVNSM":
                    value = "1.0.0";
                    break;
                default:
                    value = header.optString(key, "");
                    break;
            }
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(URLEncoder_safe(key)).append("=").append(URLEncoder_safe(value));
        }
        return sb.toString();
    }

    private static JSONObject parseCookieString(String cookie) {
        JSONObject result = new JSONObject();
        if (cookie == null || cookie.trim().isEmpty()) {
            return result;
        }
        String[] parts = cookie.split(";");
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int index = trimmed.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String key = trimmed.substring(0, index).trim();
            String value = trimmed.substring(index + 1).trim();
            try {
                result.put(key, value);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static String buildRequestId() {
        return System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
    }

    private static String URLEncoder_safe(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * Mask phone number for safe logging (show first 3 and last 4 digits only).
     */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        int maskCount = Math.max(0, phone.length() - 7);
        StringBuilder masks = new StringBuilder();
        for (int i = 0; i < maskCount; i++) masks.append('*');
        return phone.substring(0, 3) + masks + phone.substring(phone.length() - 4);
    }

    /**
     * Extract __csrf token from cookie string
     */
    private static String extractCsrfToken(String cookie) {
        if (cookie == null || cookie.isEmpty()) return "";
        String[] parts = cookie.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("__csrf=")) {
                return trimmed.substring("__csrf=".length());
            }
        }
        return "";
    }

    private static String joinArtistNames(JSONArray artists) {
        if (artists == null || artists.length() == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) {
                continue;
            }
            String name = artist.optString("name", "");
            if (name.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(name);
        }
        return builder.toString();
    }

    private static String pickFirstNonEmpty(JSONObject jsonObject, String... keys) {
        if (jsonObject == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            String value = jsonObject.optString(key, "");
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    /**
     * Extract Set-Cookie headers and build a simplified cookie string
     * containing the important values (MUSIC_U, __csrf, etc.)
     */
    private static String extractSetCookies(HttpURLConnection conn) {
        StringBuilder cookieBuilder = new StringBuilder();
        Map<String, List<String>> headers = conn.getHeaderFields();
        List<String> setCookies = headers.get("Set-Cookie");
        if (setCookies == null) {
            setCookies = headers.get("set-cookie");
        }
        if (setCookies != null) {
            for (String setCookie : setCookies) {
                // Each Set-Cookie contains "name=value; path=...; ..."
                // Extract just the name=value part
                String nameValue = setCookie.split(";")[0].trim();
                if (cookieBuilder.length() > 0) {
                    cookieBuilder.append("; ");
                }
                cookieBuilder.append(nameValue);
            }
        }
        return cookieBuilder.toString();
    }

    // ==================== Song Recognition ====================

    /**
     * Recognize songs from a NetEase audio fingerprint generated on-device.
     *
     * @param fingerprintBase64 base64 fingerprint generated from 8k mono float PCM
     * @param durationSec audio duration in seconds
     * @param cookie user cookie (may be null/empty)
     * @param callback result callback
     */
    public static void recognizeSong(String fingerprintBase64, int durationSec, String cookie,
                                     RecognitionCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "听歌识曲", "duration=" + durationSec + "s");

                String apiUrl = "https://interface.music.163.com/api/music/audio/match";
                String postBody = "sessionId=" + UUID.randomUUID().toString().replace("-", "")
                        + "&algorithmCode=shazam_v2"
                        + "&duration=" + Math.max(1, durationSec)
                        + "&rawdata=" + URLEncoder.encode(fingerprintBase64, "UTF-8")
                        + "&times=1"
                        + "&decrypt=1";
                MusicLog.i(TAG, "[REQ] POST audio/match duration=" + durationSec + "s");

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Referer", DOMAIN);
                conn.setRequestProperty("Origin", DOMAIN);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                if (cookie != null && !cookie.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookie);
                }
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(60000); // audio recognition may take longer
                conn.setDoOutput(true);

                try {
                    OutputStream os = conn.getOutputStream();
                    os.write(postBody.getBytes("UTF-8"));
                    os.close();

                    int responseCode = conn.getResponseCode();
                    BufferedReader reader;
                    if (responseCode >= 200 && responseCode < 400) {
                        reader = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    } else {
                        java.io.InputStream errStream = conn.getErrorStream();
                        reader = new BufferedReader(new InputStreamReader(
                                errStream != null ? errStream : conn.getInputStream(), "UTF-8"));
                    }
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    String response = sb.toString();
                    MusicLog.api(TAG, "POST", "audio/match", responseCode, response);

                    JSONObject json = new JSONObject(response);
                    JSONObject dataObj = json.optJSONObject("data");
                    JSONArray resultArray = dataObj != null ? dataObj.optJSONArray("result") : null;
                    List<Song> matches = new ArrayList<>();
                    LinkedHashSet<Long> seenSongIds = new LinkedHashSet<>();

                    if (resultArray != null) {
                        for (int i = 0; i < resultArray.length(); i++) {
                            JSONObject matchObj = resultArray.optJSONObject(i);
                            if (matchObj == null) continue;
                            Song song = parseRecognitionSong(matchObj.optJSONObject("song"));
                            if (song == null) {
                                song = parseRecognitionSong(matchObj);
                            }
                            if (song == null) continue;
                            long songId = song.getId();
                            if (songId > 0 && !seenSongIds.add(songId)) {
                                continue;
                            }
                            matches.add(song);
                        }
                    } else {
                        Song single = parseRecognitionSong(dataObj != null ? dataObj.optJSONObject("result") : null);
                        if (single != null) {
                            matches.add(single);
                        }
                    }

                    if (matches.isEmpty()) {
                        String message = json.optString("message", "未识别到歌曲");
                        mainHandler.post(() -> callback.onError(message));
                        return;
                    }

                    MusicLog.i(TAG, "听歌识曲成功: matches=" + matches.size());
                    mainHandler.post(() -> callback.onResult(matches));
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                MusicLog.e(TAG, "听歌识曲异常", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "识别失败，请重试"));
            }
        });
    }

    /**
     * 取歌名译名：网易云歌曲对象里的 {@code tns}（translated names）数组，取第一个非空项。
     * 老接口可能不给该字段，返回空串。
     */
    private static String pickTransName(JSONObject songObj) {
        if (songObj == null) {
            return "";
        }
        JSONArray tns = songObj.optJSONArray("tns");
        if (tns == null || tns.length() == 0) {
            return "";
        }
        for (int i = 0; i < tns.length(); i++) {
            String t = tns.optString(i, "");
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "";
    }

    private static Song parseSongFromJson(JSONObject songObj) {
        if (songObj == null) {
            return null;
        }
        long songId = songObj.optLong("id", songObj.optLong("songId", 0));
        String songName = pickFirstNonEmpty(songObj, "name", "songName");
        JSONArray artists = songObj.optJSONArray("ar");
        if (artists == null) {
            artists = songObj.optJSONArray("artists");
        }
        if (artists == null) {
            artists = songObj.optJSONArray("artist");
        }
        String artist = joinArtistNames(artists);
        if (artist.isEmpty()) {
            artist = pickFirstNonEmpty(songObj, "artist", "artistName");
        }
        JSONObject albumObj = songObj.optJSONObject("al");
        if (albumObj == null) {
            albumObj = songObj.optJSONObject("album");
        }
        String album = albumObj != null ? albumObj.optString("name", "") : "";
        String coverUrl = albumObj != null ? albumObj.optString("picUrl", "") : "";
        long albumId = albumObj != null ? albumObj.optLong("id", 0) : 0;
        if (songName.isEmpty()) {
            songName = pickFirstNonEmpty(songObj, "fileName");
        }
        if (songName.isEmpty()) {
            return null;
        }
        Song parsedSong = new Song(songId, songName, artist, album);
        parsedSong.setFee(songObj.optInt("fee", 0));
        parsedSong.setTransName(pickTransName(songObj));
        if (!coverUrl.isEmpty()) {
            parsedSong.setCoverUrl(coverUrl);
        }
        if (albumId > 0) {
            parsedSong.setAlbumId(albumId);
        }
        return parsedSong;
    }

    private static PlaylistInfo parsePlaylistInfo(JSONObject playlistObj) {
        if (playlistObj == null) {
            return null;
        }
        long playlistId = playlistObj.optLong("id", 0);
        if (playlistId <= 0) {
            return null;
        }
        String name = playlistObj.optString("name", "");
        int trackCount = playlistObj.optInt("trackCount", 0);
        String creator = "";
        long creatorUserId = 0;
        JSONObject creatorObj = playlistObj.optJSONObject("creator");
        if (creatorObj != null) {
            creator = creatorObj.optString("nickname", "");
            creatorUserId = creatorObj.optLong("userId", 0);
        }
        boolean subscribed = playlistObj.optBoolean("subscribed", false);
        String specialType = String.valueOf(playlistObj.optInt("specialType", 0));
        String coverUrl = pickFirstNonEmpty(playlistObj, "coverImgUrl", "picUrl", "cover");
        PlaylistInfo info = new PlaylistInfo(playlistId, name, trackCount, creator, creatorUserId, subscribed, specialType);
        if (!coverUrl.isEmpty()) {
            info.setCoverUrl(coverUrl);
        }
        return info;
    }

    private static CloudItem parseCloudItem(JSONObject itemObj) {
        if (itemObj == null) {
            return null;
        }
        CloudItem item = new CloudItem();
        long cloudSongId = itemObj.optLong("songId", itemObj.optLong("id", 0));
        item.setCloudSongId(cloudSongId);
        item.setFileName(pickFirstNonEmpty(itemObj, "fileName", "songName", "name"));
        item.setFileSize(itemObj.optLong("fileSize", itemObj.optLong("size", 0)));
        item.setDownloadUrl(firstNonEmptyUrl(itemObj,
                "url", "downloadUrl", "simpleSong.url"));

        JSONObject simpleSong = itemObj.optJSONObject("simpleSong");
        boolean hasSimpleSong = simpleSong != null && simpleSong.length() > 0;
        Song parsedSong = parseSongFromJson(simpleSong != null ? simpleSong : itemObj);
        if (parsedSong != null && hasSimpleSong) {
            item.setSongId(parsedSong.getId());
            item.setSongName(parsedSong.getName());
            item.setArtist(parsedSong.getArtist());
            item.setAlbum(parsedSong.getAlbum());
        }
        boolean classifyAsMusic = hasSimpleSong;
        if (classifyAsMusic && (item.getSongName() == null || item.getSongName().isEmpty())
                && item.getFileName() != null && !item.getFileName().isEmpty()) {
            item.setSongName(stripExtension(item.getFileName()));
        }
        String extension = getFileExtension(item.getFileName());
        item.setFileExtension(extension);
        item.setMusic(classifyAsMusic);
        if (item.getDownloadUrl() == null || item.getDownloadUrl().isEmpty()) {
            item.setDownloadUrl(firstNonEmptyUrl(simpleSong,
                    "url", "downloadUrl", "mp3Url"));
        }
        return item;
    }

    private static String firstNonEmptyUrl(JSONObject jsonObject, String... keys) {
        if (jsonObject == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            String value = optNestedString(jsonObject, key);
            if (value != null && !value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return "";
    }

    private static String optNestedString(JSONObject jsonObject, String path) {
        if (jsonObject == null || path == null || path.isEmpty()) {
            return "";
        }
        String normalizedPath = path.trim();
        if (normalizedPath.isEmpty()) {
            return "";
        }
        if (!normalizedPath.contains(".")) {
            return jsonObject.optString(normalizedPath, "");
        }
        String[] parts = normalizedPath.split("\\.");
        if (parts.length == 0) {
            return "";
        }
        JSONObject current = jsonObject;
        for (int i = 0; i < parts.length - 1; i++) {
            current = current.optJSONObject(parts[i]);
            if (current == null) {
                return "";
            }
        }
        return current.optString(parts[parts.length - 1], "");
    }

    private static void updateUploadProgress(UploadProgressCallback callback, int progress, String message) {
        mainHandler.post(() -> callback.onProgress(progress, message));
    }

    private static void uploadFileToNos(String uploadUrl, String token, String md5, File uploadFile,
                                        UploadProgressCallback callback) throws Exception {
        URL url = new URL(uploadUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("x-nos-token", token);
        conn.setRequestProperty("Content-MD5", md5);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setRequestProperty("Content-Length", String.valueOf(uploadFile.length()));
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(uploadFile.length());
        try (InputStream inputStream = new FileInputStream(uploadFile);
             OutputStream outputStream = conn.getOutputStream()) {
            byte[] buffer = new byte[8192];
            long uploaded = 0;
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
                uploaded += len;
                int progress = 30 + (int) ((uploaded * 60f) / Math.max(1L, uploadFile.length()));
                updateUploadProgress(callback, Math.min(progress, 90), "正在上传文件...");
            }
            outputStream.flush();
            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IllegalStateException("文件上传失败(" + responseCode + ")");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String getRaw(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        try {
            int responseCode = conn.getResponseCode();
            InputStream responseStream = responseCode >= 200 && responseCode < 400
                    ? conn.getInputStream() : conn.getErrorStream();
            if (responseStream == null) {
                responseStream = conn.getInputStream();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String md5(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                digest.update(buffer, 0, len);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    private static String stripExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }

    private static String buildUploadFilename(String fileName, String ext) {
        String base = stripExtension(fileName)
                .replaceAll("\\s+", "")
                .replace(".", "_");
        if (base.isEmpty()) {
            base = "upload_" + System.currentTimeMillis();
        }
        return base;
    }

    private static Song parseRecognitionSong(JSONObject songObj) {
        if (songObj == null) {
            return null;
        }

        long songId = songObj.optLong("id", songObj.optLong("songId", 0));
        String songName = songObj.optString("name", songObj.optString("songName", ""));
        JSONArray artists = songObj.optJSONArray("artist");
        if (artists == null) {
            artists = songObj.optJSONArray("artists");
        }
        String artist = "未知歌手";
        if (artists != null && artists.length() > 0) {
            JSONObject artistObj = artists.optJSONObject(0);
            if (artistObj != null) {
                artist = artistObj.optString("name", artist);
            }
        } else if (!songObj.optString("artistName").isEmpty()) {
            artist = songObj.optString("artistName");
        }
        JSONObject albumObj = songObj.optJSONObject("album");
        if (albumObj == null) {
            albumObj = songObj.optJSONObject("al");
        }
        String album = albumObj != null ? albumObj.optString("name", "") : "";
        if (songName.isEmpty()) {
            return null;
        }
        Song parsed = new Song(songId, songName, artist, album);
        parsed.setFee(songObj.optInt("fee", 0));
        return parsed;
    }

    // ==================== Playlist Subscribe / Unsubscribe ====================

    /**
     * Subscribe (collect) or unsubscribe a playlist.
     * @param subscribe true to subscribe, false to unsubscribe
     */
    public static void subscribePlaylist(long playlistId, boolean subscribe, String cookie,
                                          PlaylistActionCallback callback) {
        executor.execute(() -> {
            try {
                String action = subscribe ? "subscribe" : "unsubscribe";
                MusicLog.op(TAG, "歌单" + (subscribe ? "收藏" : "取消收藏"), "id=" + playlistId);
                JSONObject data = new JSONObject();
                data.put("id", playlistId);
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                String response = weapiPost("/api/playlist/" + action, data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                mainHandler.post(() -> callback.onResult(code == 200));
            } catch (Exception e) {
                MusicLog.w(TAG, "歌单收藏操作失败: " + playlistId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Playlist Create ====================

    /**
     * Create a new playlist.
     * @param name playlist name
     * @param privacy 0=normal, 10=private
     */
    public static void createPlaylist(String name, int privacy, String cookie,
                                       PlaylistCreateCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "创建歌单", "name=" + name);
                JSONObject data = new JSONObject();
                data.put("name", name);
                data.put("privacy", String.valueOf(privacy));
                data.put("type", "NORMAL");
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                String response = weapiPost("/api/playlist/create", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                if (code == 200) {
                    JSONObject pl = json.optJSONObject("playlist");
                    long newId = pl != null ? pl.optLong("id", 0) : 0;
                    String newName = pl != null ? pl.optString("name", name) : name;
                    mainHandler.post(() -> callback.onResult(newId, newName));
                } else {
                    String msg = json.optString("message", "创建失败");
                    mainHandler.post(() -> callback.onError(msg));
                }
            } catch (Exception e) {
                MusicLog.w(TAG, "创建歌单失败: " + name, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Playlist Delete ====================

    /**
     * Delete a playlist (completely remove from cloud).
     */
    public static void deletePlaylist(long playlistId, String cookie,
                                       PlaylistActionCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "删除歌单", "id=" + playlistId);
                JSONObject data = new JSONObject();
                data.put("ids", "[" + playlistId + "]");
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                String response = weapiPost("/api/playlist/remove", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                mainHandler.post(() -> callback.onResult(code == 200));
            } catch (Exception e) {
                MusicLog.w(TAG, "删除歌单失败: " + playlistId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Playlist Rename ====================

    /**
     * Update (rename) a playlist's name via the batch API.
     * Per NeteaseCloudMusicApiBackup/module/playlist_update.js:
     *   POST /api/batch with body {"/api/playlist/update/name": '{"id":ID,"name":"NAME"}'}
     */
    public static void updatePlaylistName(long playlistId, String newName, String cookie,
                                           PlaylistActionCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "重命名歌单", "id=" + playlistId + " name=" + newName);
                // Inner data for the name update endpoint
                JSONObject nameData = new JSONObject();
                nameData.put("id", playlistId);
                nameData.put("name", newName);
                // Batch request body: key = inner endpoint, value = inner JSON string
                JSONObject batchData = new JSONObject();
                batchData.put("/api/playlist/update/name", nameData.toString());
                String csrfToken = extractCsrfToken(cookie);
                batchData.put("csrf_token", csrfToken);
                String response = weapiPost("/api/batch", batchData.toString(), cookie);
                JSONObject json = new JSONObject(response);
                // Batch response: {"/api/playlist/update/name": {"code": 200, ...}}
                int code = -1;
                if (json.has("/api/playlist/update/name")) {
                    code = json.getJSONObject("/api/playlist/update/name").optInt("code", -1);
                } else {
                    code = json.optInt("code", -1);
                }
                final boolean success = code == 200;
                mainHandler.post(() -> callback.onResult(success));
            } catch (Exception e) {
                MusicLog.w(TAG, "重命名歌单失败: " + playlistId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== Add/Remove Tracks to Playlist ====================

    /**
     * Add or remove tracks from a playlist.
     * @param op "add" or "del"
     * @param playlistId target playlist ID
     * @param trackIds song IDs to add/remove
     */
    public static void playlistTracks(String op, long playlistId, long[] trackIds,
                                       String cookie, PlaylistActionCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "歌单曲目操作", "op=" + op + " pid=" + playlistId);
                JSONObject data = new JSONObject();
                data.put("op", op);
                data.put("pid", playlistId);
                // Build trackIds JSON array string
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < trackIds.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(trackIds[i]);
                }
                sb.append("]");
                data.put("trackIds", sb.toString());
                data.put("imme", "true");
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);
                String response = weapiPost("/api/playlist/manipulate/tracks", data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", -1);
                if (code == 200) {
                    mainHandler.post(() -> callback.onResult(true));
                } else if (code == 512) {
                    // Retry with duplicated trackIds (NeteaseCloudMusic workaround)
                    StringBuilder sb2 = new StringBuilder("[");
                    for (int i = 0; i < trackIds.length; i++) {
                        if (i > 0) sb2.append(",");
                        sb2.append(trackIds[i]);
                    }
                    for (long tid : trackIds) {
                        sb2.append(",").append(tid);
                    }
                    sb2.append("]");
                    data.put("trackIds", sb2.toString());
                    String response2 = weapiPost("/api/playlist/manipulate/tracks", data.toString(), cookie);
                    JSONObject json2 = new JSONObject(response2);
                    int code2 = json2.optInt("code", -1);
                    mainHandler.post(() -> callback.onResult(code2 == 200));
                } else {
                    String msg = json.optString("message", "操作失败");
                    mainHandler.post(() -> callback.onError(msg));
                }
            } catch (Exception e) {
                MusicLog.w(TAG, "歌单曲目操作失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Get UID from cookie (public access for use in activities).
     */
    public static void getUid(String cookie, AccountCallback callback) {
        executor.execute(() -> {
            try {
                long uid = extractUidFromCookie(cookie);
                JSONObject result = new JSONObject();
                result.put("uid", uid);
                mainHandler.post(() -> callback.onResult(result));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Get album detail by album ID.
     * Returns album name, cover URL, and track list.
     * Endpoint: /api/v1/album (weapi)
     */
    public static void getAlbumDetail(long albumId, String cookie, AlbumDetailCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取专辑详情", "albumId=" + albumId);
                JSONObject data = new JSONObject();
                String csrfToken = extractCsrfToken(cookie);
                data.put("csrf_token", csrfToken);

                String response = weapiPost("/api/v1/album/" + albumId, data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                JSONObject albumObj = json.optJSONObject("album");
                if (albumObj == null) {
                    mainHandler.post(() -> callback.onError("获取专辑详情失败"));
                    return;
                }

                String albumName = albumObj.optString("name", "");
                String coverUrl = albumObj.optString("picUrl", "");

                List<Song> songs = new ArrayList<>();
                JSONArray songsArr = json.optJSONArray("songs");
                if (songsArr == null) songsArr = albumObj.optJSONArray("songs");
                if (songsArr != null) {
                    for (int i = 0; i < songsArr.length(); i++) {
                        JSONObject s = songsArr.optJSONObject(i);
                        if (s == null) continue;
                        Song song = parseSongFromJson(s);
                        if (song != null) {
                            if (song.getCoverUrl() == null || song.getCoverUrl().isEmpty()) {
                                song.setCoverUrl(coverUrl);
                            }
                            songs.add(song);
                        }
                    }
                }

                final String finalAlbumName = albumName;
                final String finalCoverUrl = coverUrl;
                mainHandler.post(() -> callback.onResult(finalAlbumName, finalCoverUrl, songs));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取专辑详情失败: " + albumId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    // ==================== 专辑收藏 ====================

    /** {@link #isAlbumSubscribed} 最多翻多少页收藏专辑列表。 */
    private static final int ALBUM_SUB_SCAN_PAGES = 5;

    /** 收藏专辑列表里的专辑条目。 */
    public static class AlbumBrief {
        public long id;
        public String name = "";
        public String picUrl = "";
        /** 歌手名，多个用 " / " 连接。 */
        public String artist = "";
        /** 专辑曲目数。 */
        public int size;
        public long subTime;
    }

    public interface AlbumSubCallback {
        void onResult(boolean subscribed);

        void onError(String message);
    }

    /**
     * 收藏 / 取消收藏专辑（weapi /api/album/sub 与 /api/album/unsub）。
     *
     * <p>两个坑（2026-10-03 实测）：① 收藏与取消是<strong>两个不同路径</strong>，
     * 不是靠 {@code t} 参数区分；② {@code id} 必须放在 <strong>URL</strong> 上，
     * 放进请求体上游只回 {@code {"msg":"参数错误","code":400}}。</p>
     */
    public static void subAlbum(long albumId, boolean subscribe, String cookie,
                                AlbumSubCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, subscribe ? "收藏专辑" : "取消收藏专辑", "albumId=" + albumId);
                JSONObject data = new JSONObject();
                data.put("csrf_token", extractCsrfToken(cookie));
                String path = (subscribe ? "/api/album/sub?id=" : "/api/album/unsub?id=") + albumId;
                String response = weapiPost(path, data.toString(), cookie);
                JSONObject json = new JSONObject(response);
                int code = json.optInt("code", 200);
                if (code != 200) {
                    String msg = json.optString("message", "操作失败");
                    mainHandler.post(() -> callback.onError(msg));
                    return;
                }
                mainHandler.post(() -> callback.onResult(subscribe));
            } catch (Exception e) {
                MusicLog.w(TAG, "收藏专辑失败: " + albumId, e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    public interface AlbumSubStateCallback {
        void onResult(boolean subscribed);

        void onError(String message);
    }

    /**
     * 判断某张专辑是否已收藏 —— 用「收藏专辑列表」逐页找，而不是
     * {@code /api/album/detail/dynamic}。
     *
     * <p>原因：实机上网关对 {@code album/detail/dynamic} 会一直不返回（连续两次都
     * 15s 读超时），而 {@code album/sublist} 稳定 ~0.7s 返回（2026-10-03 实测）。
     * 同一个接口在 PC 上却正常，所以这不是参数问题，换成稳定的那条路更划算。</p>
     *
     * <p>最多翻 {@value #ALBUM_SUB_SCAN_PAGES} 页（每页 100），够覆盖一般账号。</p>
     */
    public static void isAlbumSubscribed(long albumId, String cookie,
                                         AlbumSubStateCallback callback) {
        executor.execute(() -> {
            final int pageSize = 100;
            for (int page = 0; page < ALBUM_SUB_SCAN_PAGES; page++) {
                try {
                    JSONObject data = new JSONObject();
                    data.put("limit", pageSize);
                    data.put("offset", page * pageSize);
                    data.put("total", true);
                    data.put("csrf_token", extractCsrfToken(cookie));
                    String response = weapiPost("/api/album/sublist", data.toString(), cookie);
                    JSONObject json = new JSONObject(response);
                    JSONArray arr = json.optJSONArray("data");
                    if (arr == null) {
                        arr = json.optJSONArray("albums");
                    }
                    int count = arr == null ? 0 : arr.length();
                    for (int i = 0; i < count; i++) {
                        JSONObject a = arr.optJSONObject(i);
                        if (a != null && a.optLong("id", 0) == albumId) {
                            mainHandler.post(() -> callback.onResult(true));
                            return;
                        }
                    }
                    boolean hasMore = json.optBoolean("hasMore", false);
                    if (!hasMore || count < pageSize) {
                        mainHandler.post(() -> callback.onResult(false));
                        return;
                    }
                } catch (Exception e) {
                    MusicLog.w(TAG, "查询专辑收藏状态失败: " + albumId, e);
                    final String msg = e.getMessage() != null ? e.getMessage() : "未知错误";
                    mainHandler.post(() -> callback.onError(msg));
                    return;
                }
            }
            // 翻到上限仍没找到，按「未收藏」处理（按钮点下去上游会纠正）
            mainHandler.post(() -> callback.onResult(false));
        });
    }

    public interface SubscribedAlbumsCallback {
        /**
         * @param total   收藏专辑总数
         * @param hasMore 是否还有下一页
         * @param albums  本页专辑
         */
        void onResult(int total, boolean hasMore, List<AlbumBrief> albums);

        void onError(String message);
    }

    /**
     * 已收藏专辑列表（weapi /api/album/sublist）。
     *
     * <p>注意数组键是 {@code data}，不是 {@code albums} —— 老实现按 {@code albums}
     * 取会永远得到空列表（2026-10-03 实测）。</p>
     */
    public static void getSubscribedAlbums(int limit, int offset, String cookie,
                                           SubscribedAlbumsCallback callback) {
        executor.execute(() -> {
            try {
                MusicLog.op(TAG, "获取收藏专辑列表", "limit=" + limit + " offset=" + offset);
                JSONObject data = new JSONObject();
                data.put("limit", limit);
                data.put("offset", offset);
                data.put("total", true);
                data.put("csrf_token", extractCsrfToken(cookie));
                String response = weapiPost("/api/album/sublist", data.toString(), cookie);
                // 2026-11-22：count 恒为 0 说明数组字段名/层级还是不对，打出原始响应定位
                MusicLog.i(TAG, "sublist resp: "
                        + (response.length() > 400 ? response.substring(0, 400) + "..." : response));
                JSONObject json = new JSONObject(response);
                // 2026-11-27：/api/album/sublist 的数组就在**顶层 data**，同级还有 count / hasMore：
                //   {"code":200,"count":10,"hasMore":false,"data":[{"id":..,"name":..},…]}
                // 11-22 那次「改成 data.data」把它改坏了 —— 顶层 data 是数组、data 当对象取自然为 null，
                // 于是日志里一直是 count=0 / total=10，收藏专辑永远进不了歌单页。
                // 现在按「顶层 data 数组 → data.data 数组 → albums 数组」的顺序兜底，
                // 三种结构都能吃下来（换接口版本也不会再静默变空）。
                JSONArray arr = json.optJSONArray("data");
                JSONObject dataObj = json.optJSONObject("data");
                int totalCount = json.optInt("count", -1);
                boolean hasMoreFlag = json.optBoolean("hasMore", false);
                if (arr == null && dataObj != null) {
                    arr = dataObj.optJSONArray("data");
                    if (arr == null) {
                        arr = dataObj.optJSONArray("albums");
                    }
                    if (totalCount < 0) {
                        totalCount = dataObj.optInt("total", -1);
                    }
                    if (!hasMoreFlag) {
                        hasMoreFlag = dataObj.optBoolean("hasMore", false);
                    }
                }
                if (arr == null) {
                    arr = json.optJSONArray("albums");
                }
                // arr 为 null 只会出现在真的没有任何专辑时；此时按空列表返回，不算失败。
                List<AlbumBrief> out = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject a = arr.optJSONObject(i);
                        if (a == null) {
                            continue;
                        }
                        long id = a.optLong("id", 0);
                        if (id <= 0) {
                            continue;
                        }
                        AlbumBrief brief = new AlbumBrief();
                        brief.id = id;
                        brief.name = a.optString("name", "");
                        brief.picUrl = a.optString("picUrl", "");
                        brief.size = a.optInt("size", 0);
                        brief.subTime = a.optLong("subTime", 0L);
                        brief.artist = joinArtistNames(a.optJSONArray("artists"));
                        out.add(brief);
                    }
                }
                final int total = totalCount >= 0 ? totalCount : json.optInt("count", out.size());
                final boolean hasMore = totalCount >= 0
                        ? hasMoreFlag : json.optBoolean("hasMore", false);
                MusicLog.op(TAG, "收藏专辑解析", "count=" + out.size() + " total=" + total);
                mainHandler.post(() -> callback.onResult(total, hasMore, out));
            } catch (Exception e) {
                MusicLog.w(TAG, "获取收藏专辑列表失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }

    /**
     * Batch-fetch song details (name, artist, album, coverUrl) for a list of song IDs.
     * Used by DownloadListActivity to fill missing covers for legacy downloads.
     */
    public static void fetchSongsDetails(List<Long> songIds, String cookie, BatchSongDetailsCallback callback) {
        if (songIds == null || songIds.isEmpty()) {
            mainHandler.post(() -> callback.onResult(new java.util.HashMap<>()));
            return;
        }
        executor.execute(() -> {
            try {
                java.util.Map<Long, Song> resultMap = new java.util.HashMap<>();
                String csrfToken = extractCsrfToken(cookie);
                int total = songIds.size();
                for (int batchStart = 0; batchStart < total; batchStart += 200) {
                    int batchEnd = Math.min(batchStart + 200, total);
                    JSONArray batchIds = new JSONArray();
                    for (int i = batchStart; i < batchEnd; i++) {
                        JSONObject idObj = new JSONObject();
                        idObj.put("id", songIds.get(i));
                        batchIds.put(idObj);
                    }
                    JSONObject data = new JSONObject();
                    data.put("c", batchIds.toString());
                    data.put("csrf_token", csrfToken);
                    String response = weapiPost("/api/v3/song/detail", data.toString(), cookie);
                    JSONObject json = new JSONObject(response);
                    JSONArray songsArr = json.optJSONArray("songs");
                    if (songsArr != null) {
                        for (int i = 0; i < songsArr.length(); i++) {
                            JSONObject s = songsArr.optJSONObject(i);
                            if (s == null) continue;
                            Song song = parseSongFromJson(s);
                            if (song != null && song.getId() > 0) {
                                resultMap.put(song.getId(), song);
                            }
                        }
                    }
                }
                mainHandler.post(() -> callback.onResult(resultMap));
            } catch (Exception e) {
                MusicLog.w(TAG, "批量获取歌曲详情失败", e);
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }
}
