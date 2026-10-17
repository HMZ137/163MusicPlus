package com.qinghe.music163pro.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.qinghe.music163pro.api.BilibiliApiHelper;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.api.NeteaseWatchApi;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.util.MusicLog;
import com.qinghe.music163pro.util.LocalLibraryCache;
import com.qinghe.music163pro.util.LocalTracks;
import com.qinghe.music163pro.util.PlaybackStats;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class MusicPlayerManager {

    private static final String TAG = "MusicPlayer";
    private static final String PREFS_NAME = "music163_playback_state";
    private static final String KEY_CURRENT_SONG_JSON = "current_song_json";
    private static final String KEY_PLAYLIST_JSON = "playlist_json";
    private static final String KEY_CURRENT_INDEX = "current_index";
    private static final String KEY_SOURCE_PLAYLIST_ID = "source_playlist_id";
    private static final String KEY_SOURCE_PLAYLIST_NAME = "source_playlist_name";
    private static final String KEY_SOURCE_PLAYLIST_TRACK_COUNT = "source_playlist_track_count";
    private static final String KEY_SOURCE_PLAYLIST_CREATOR = "source_playlist_creator";
    private static final String KEY_SOURCE_PLAYLIST_CREATOR_USER_ID = "source_playlist_creator_user_id";
    private static final String KEY_SOURCE_PLAYLIST_IS_LIKED = "source_playlist_is_liked";
    private static final String KEY_FORCE_LOCAL_PLAYBACK = "force_local_playback";
    private static final String KEY_PERSONAL_FM_MODE = "personal_fm_mode";
    private static final String KEY_PERSONAL_FM_MODE_STR = "personal_fm_mode_str";
    private static final String KEY_PERSONAL_FM_SUBMODE_STR = "personal_fm_submode_str";

    public enum PlayMode {
        LIST_LOOP,      // 列表循环
        SINGLE_REPEAT,  // 单曲循环
        RANDOM          // 随机播放
    }

    public interface PlayerCallback {
        void onSongChanged(Song song);
        void onPlayStateChanged(boolean isPlaying);
        void onError(String message);
        default void onSleepTimerTriggered(boolean exitApp) {}

        /**
         * 定时关闭到点了，但模式是「放完当前曲再停」，所以此刻还没真正停止。
         * 默认空实现，不影响既有回调方。
         */
        default void onSleepTimerPendingStop() {}
    }

    private static MusicPlayerManager instance;
    private MediaPlayer mediaPlayer;
    private final List<Song> playlist = new ArrayList<>();
    private int currentIndex = -1;

    /**
     * 「下一首播放」队列（可叠加）。
     *
     * <p>长按播放列表里的一首选「下一首播放」，就把它排进来。连选多首会按选择顺序
     * 依次排在当前歌之后（而不是互相顶替，也不是倒序）。</p>
     *
     * <p>同时维护两处：这里存 Song 引用（用于显示角标、判重、随机模式下也能优先播），
     * {@link #playlist} 里则把这些歌**物理挪到** {@code currentIndex+1} 之后的连续位置 ——
     * 后者保证顺序播放在队列放完之后能自然接回「原本的下一首」。</p>
     */
    private final List<Song> playNextQueue = new ArrayList<>();

    // ---- 心动模式（末尾续播） ----
    /**
     * 心动模式：<b>不替换</b>当前队列，而是把推荐曲<b>追加到队尾</b>；原歌单播完后接着往下听。
     *
     * <p>为什么是「追加」而不是「换一批」：换一批会把用户正在听的歌顶掉、把歌单上下文整个丢掉，
     * 用过一次就不想再用（实测反馈）。而模式的意义本来就该是「不打扰地持续生效」——
     * 用户听自己的歌单，听到末尾自动续上口味相近的下一批，才是网易云心动模式本来的行为。</p>
     *
     * <p>联动关系：与 {@link #personalFmMode} 同一套机制 —— 队列走到尽头时取下一批、
     * {@link #appendUniqueSongs} 去重追加、然后继续推进。差别只在种子：
     * 私人漫游用固定模式，心动模式用<b>队列最后一首</b>当种子，所以会「越听越对味」。</p>
     *
     * <p>已插入的曲目 id 记在 {@link #heartbeatIds}，供界面标记与一键清除。</p>
     */
    private final java.util.Set<Long> heartbeatIds = new java.util.LinkedHashSet<>();
    private boolean heartbeatMode = false;
    private boolean heartbeatLoading = false;
    private boolean isPlaying = false;
    private PlayerCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PlayMode playMode = PlayMode.LIST_LOOP;
    private float playbackSpeed = 1.0f;
    /**
     * Speed mode:
     *  0 = 音调不变 (time-stretch: speed changes, pitch preserved)
     *  1 = 音调改变且速度改变 (sample rate: both speed and pitch change)
     *  2 = 音调改变但速度不变 (pitch-shift only: pitch changes, speed stays 1.0)
     */
    private int speedMode = 0;
    private final Random random = new Random();
    /**
     * 随机播放的洗牌顺序，元素是 {@link #playlist} 的下标。
     * 用它而不是每次「随机抽一首」，是为了让「下一首 / 上一首」在随机模式下
     * 也有一致的次序，并且不会刚听完又立刻抽到同一首。
     */
    private final List<Integer> shuffleOrder = new ArrayList<>();
    /** playlist 结构变动后置位，下一次随机播放前重建洗牌顺序。 */
    private boolean shuffleOrderDirty = true;
    private long currentlyPlayingSongId = -1;
    /** 防重入：同一次「播放结束」只处理一次，详见 {@link #onSongCompleted()}。 */
    private boolean handlingSongCompleted = false;
    private Context appContext;
    private long bilibiliRetrySongId = -1;
    private int bilibiliRetryCount = 0;
    /**
     * 1016 合并：MediaPlayer 出错重试的保护 —— 同一首歌最多自动重取一次 URL。
     * 没有这个保护时，死链会无限循环（error → getSongUrl → 同一个死链 → error…），
     * 每 ~300ms 打一次接口。
     */
    private long errorRetrySongId = -1;

    // ExoPlayer for Bilibili audio (better seek/buffering than MediaPlayer)
    private ExoPlayer exoPlayer;
    private boolean usingExoPlayer = false;
    /** F5：当前播放源路径（本地歌曲即文件绝对路径），用于查响度索引。 */
    private String currentSourcePath;
    /** F5：实时响度增益；未启用时为 null（原路径完全不变）。 */
    private android.media.audiofx.LoudnessEnhancer loudnessEnhancer;

    // Bilibili CDN URL pre-refresh timer (URLs expire after ~30 min)
    private static final long BILIBILI_REFRESH_INTERVAL_MS = 25 * 60 * 1000; // 25 minutes
    private Runnable bilibiliRefreshRunnable;
    private String prefetchedBilibiliUrl;

    // Playlist source tracking: set when playing from a playlist
    private long sourcePlaylistId = -1;
    private String sourcePlaylistName;
    private int sourcePlaylistTrackCount;
    private String sourcePlaylistCreator;
    private long sourcePlaylistCreatorUserId;
    private boolean sourcePlaylistIsLiked;
    private boolean personalFmMode = false;
    private boolean personalFmLoading = false;
    private String personalFmModeStr = "DEFAULT";
    private String personalFmSubModeStr = "";

    private MusicPlayerManager() {}

    public static synchronized MusicPlayerManager getInstance() {
        if (instance == null) {
            instance = new MusicPlayerManager();
        }
        return instance;
    }

    public void setContext(Context context) {
        this.appContext = context.getApplicationContext();
        // 本地音乐库缓存：状态恢复时要靠它把「本地歌 → 路径」找回来
        LocalLibraryCache.init(this.appContext);
    }

    public void setCallback(PlayerCallback callback) {
        this.callback = callback;
    }

    public void setPlaylist(List<Song> songs, int startIndex) {
        playlist.clear();
        playlist.addAll(songs);
        // 整条队列被换掉，「下一首播放」随之作废
        playNextQueue.clear();
        currentIndex = startIndex;
        clearSourcePlaylistInfo();
        personalFmMode = false;
        personalFmLoading = false;
        // 队列被整体换掉，此前追加的心动曲目与标记都不再成立
        resetHeartbeat();
        invalidateShuffleOrder();
        savePlaybackState();
    }

    /**
     * Set playlist with source playlist info (for tracking which playlist is being played).
     */
    public void setPlaylistFromSource(List<Song> songs, int startIndex,
                                       long playlistId, String playlistName,
                                       int trackCount, String creator,
                                       long creatorUserId, boolean isLiked) {
        playlist.clear();
        playlist.addAll(songs);
        // 整条队列被换掉，「下一首播放」随之作废
        playNextQueue.clear();
        currentIndex = startIndex;
        sourcePlaylistId = playlistId;
        sourcePlaylistName = playlistName;
        sourcePlaylistTrackCount = trackCount;
        sourcePlaylistCreator = creator;
        sourcePlaylistCreatorUserId = creatorUserId;
        sourcePlaylistIsLiked = isLiked;
        personalFmMode = false;
        personalFmLoading = false;
        // 队列被整体换掉，此前追加的心动曲目与标记都不再成立
        resetHeartbeat();
        invalidateShuffleOrder();
        savePlaybackState();
    }

    public void setPersonalFmPlaylist(List<Song> songs, int startIndex) {
        setPersonalFmPlaylist(songs, startIndex, "DEFAULT", "");
    }

    public void setPersonalFmPlaylist(List<Song> songs, int startIndex, String mode, String subMode) {
        playlist.clear();
        playlist.addAll(songs);
        // 整条队列被换掉，「下一首播放」随之作废
        playNextQueue.clear();
        currentIndex = startIndex;
        clearSourcePlaylistInfo();
        personalFmMode = true;
        personalFmLoading = false;
        // 私人漫游是另一套续播机制，心动模式不与之并存
        resetHeartbeat();
        personalFmModeStr = (mode != null && !mode.isEmpty()) ? mode : "DEFAULT";
        personalFmSubModeStr = (subMode != null) ? subMode : "";
        invalidateShuffleOrder();
        savePlaybackState();
    }

    public long getSourcePlaylistId() { return sourcePlaylistId; }
    public String getSourcePlaylistName() { return sourcePlaylistName; }
    public int getSourcePlaylistTrackCount() { return sourcePlaylistTrackCount; }
    public String getSourcePlaylistCreator() { return sourcePlaylistCreator; }
    public long getSourcePlaylistCreatorUserId() { return sourcePlaylistCreatorUserId; }
    public boolean getSourcePlaylistIsLiked() { return sourcePlaylistIsLiked; }

    public boolean hasSourcePlaylist() { return sourcePlaylistId > 0; }

    public boolean isPersonalFmMode() {
        return personalFmMode;
    }

    public List<Song> getPlaylist() {
        return playlist;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public Song getCurrentSong() {
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            return playlist.get(currentIndex);
        }
        return null;
    }

    /**
     * Best song to derive the background cover from: the current song if it has
     * a cover, otherwise the first playlist song with a cover (so the background
     * still loads when nothing is playing yet).
     *
     * <p><b>本地歌不走「退而取队列里别的封面」那条路。</b>云歌的封面是异步从接口取的，
     * 当前这首暂时为空时随便拿一张顶上，比空白背景好；但本地歌的封面在扫描时就已确定
     * （文件里有没有内嵌封面，{@link LocalTracks} 一路同步可知），没有就是没有。
     * 若照样兜底，播一首没内嵌封面的本地歌会显示成<b>队列里另一首歌的封面</b> ——
     * 那不是「缺失」，而是「错误」，比空背景更容易误导。</p>
     */
    public Song getBackgroundSong() {
        Song current = getCurrentSong();
        if (current != null && current.getCoverUrl() != null && !current.getCoverUrl().isEmpty()) {
            return current;
        }
        if (current != null && LocalTracks.isLocalOnly(current)) {
            return current;
        }
        for (Song song : playlist) {
            if (song != null && song.getCoverUrl() != null && !song.getCoverUrl().isEmpty()) {
                return song;
            }
        }
        return current;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void setPlayMode(PlayMode mode) {
        this.playMode = mode;
    }

    public PlayMode getPlayMode() {
        return playMode;
    }

    /**
     * Set playback speed. Requires API 23+ (Marshmallow).
     * @param speed playback speed multiplier (0.1 - 5.0)
     */
    public void setPlaybackSpeed(float speed) {
        this.playbackSpeed = speed;
        applyPlaybackSpeed();
    }

    public float getPlaybackSpeed() {
        return playbackSpeed;
    }

    /**
     * Set speed mode.
     * @param speedMode 0=音调不变, 1=音调改变且速度改变, 2=音调改变但速度不变
     */
    public void setSpeedMode(int speedMode) {
        this.speedMode = speedMode;
        applyPlaybackSpeed();
    }

    public int getSpeedMode() {
        return speedMode;
    }

    private void applyPlaybackSpeed() {
        // ExoPlayer speed control
        if (usingExoPlayer && exoPlayer != null) {
            try {
                float speed, pitch;
                if (speedMode == 1) {
                    speed = playbackSpeed;
                    pitch = playbackSpeed;
                } else if (speedMode == 2) {
                    speed = 1.0f;
                    pitch = playbackSpeed;
                } else {
                    speed = playbackSpeed;
                    pitch = 1.0f;
                }
                exoPlayer.setPlaybackParameters(new PlaybackParameters(speed, pitch));
            } catch (Exception e) {
                Log.w(TAG, "Error setting ExoPlayer playback speed", e);
            }
            return;
        }
        // MediaPlayer speed control
        if (mediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                boolean wasPlaying = mediaPlayer.isPlaying();
                PlaybackParams params = mediaPlayer.getPlaybackParams();
                if (speedMode == 1) {
                    // 音调改变且速度改变: sample rate mode, both change
                    params.setSpeed(playbackSpeed);
                    params.setPitch(playbackSpeed);
                } else if (speedMode == 2) {
                    // 音调改变但速度不变: pitch shifts, tempo stays at 1.0
                    params.setSpeed(1.0f);
                    params.setPitch(playbackSpeed);
                } else {
                    // 音调不变 (mode 0): time-stretch, pitch preserved
                    params.setSpeed(playbackSpeed);
                    params.setPitch(1.0f);
                }
                mediaPlayer.setPlaybackParams(params);
                // Workaround: setPlaybackParams may auto-start a paused MediaPlayer
                if (!wasPlaying && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error setting playback speed", e);
            }
        }
    }

    public void play(String url) {
        play(url, 0, true);
    }

    private void play(String url, int resumePositionMs, boolean shouldPlayWhenReady) {
        stop();
        cancelBilibiliRefreshTimer();
        mediaPlayer = new MediaPlayer();
        try {
            if (appContext != null) {
                mediaPlayer.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK);
            }
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build());
            mediaPlayer.setDataSource(url);
            currentSourcePath = url;
            mediaPlayer.setOnPreparedListener(mp -> {
                // 已经被替换掉的旧播放器的迟到回调，直接忽略。
                // 少了这个判断会出现：新歌已经起播，旧实例的 onPrepared 才回来，
                // 于是又去 start() 一次已 release 的 MediaPlayer → IllegalStateException 崩溃。
                if (mp != mediaPlayer) return;
                if (resumePositionMs > 0) {
                    try {
                        mp.seekTo(resumePositionMs);
                    } catch (Exception e) {
                        Log.w(TAG, "Error restoring playback position", e);
                    }
                }
                applyPlaybackSpeed();
                if (shouldPlayWhenReady) {
                    mp.start();
                    isPlaying = true;
                    notifyPlayStateChanged(true);
                } else {
                    isPlaying = false;
                    notifyPlayStateChanged(false);
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                // 同上：只有当前播放器的完成事件才算数。
                // 否则「把进度条拖到结尾」时，旧实例的完成回调会再切一次歌，
                // 表现为连跳两首、或新歌刚起播就被下一轮切歌打断（听感像自动暂停）。
                if (mp != mediaPlayer) return;
                onSongCompleted();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                // 旧播放器的迟到错误必须忽略：它内部用的是 getCurrentSong()，
                // 而此时当前歌已经是下一首了 —— 会把新歌的 URL 清掉并重新拉流，
                // 于是刚起播的新歌被打断，看起来就是「下一首自动暂停」。
                if (mp != mediaPlayer) return true;
                // what==-38 is ENOSYS ("function not implemented") fired asynchronously
                // by setPlaybackParams() on watch devices (Android 7-8.1) that lack
                // time-stretch support.  Playback is unaffected; silently ignore it.
                if (what == -38) return true;
                isPlaying = false;
                notifyPlayStateChanged(false);
                Song song = getCurrentSong();
                if (song != null) {
                    song.setUrl(null);
                }
                // Retry without cached URL —— 但每首歌只重试一次：
                // 没有这个 guard 时死链会无限循环（error → getSongUrl → 同一个死链 → error…），
                // 每 ~300ms 打一次接口。
                if (song != null && song.getId() != errorRetrySongId) {
                    errorRetrySongId = song.getId();
                    String cookie = getCookie();
                    MusicApiHelper.getSongUrl(song.getId(), cookie, false,
                            new MusicApiHelper.UrlCallback() {
                                @Override
                                public void onResult(String retryUrl) {
                                    if (retryUrl != null) {
                                        song.setUrl(retryUrl);
                                        play(retryUrl, resumePositionMs, shouldPlayWhenReady);
                                    } else if (callback != null) {
                                        mainHandler.post(() -> callback.onError(
                                                "播放错误: " + what));
                                    }
                                }

                                @Override
                                public void onError(String message) {
                                    if (callback != null) {
                                        mainHandler.post(() -> callback.onError(
                                                "播放错误: " + what));
                                    }
                                }
                            });
                } else if (callback != null) {
                    mainHandler.post(() -> callback.onError("播放错误: " + what));
                }
                return true;
            });
            mediaPlayer.prepareAsync();
            probeLoudnessFeasibility(false);   // F5/R4 探针
            applyLoudnessBalance();
        } catch (Exception e) {
            if (callback != null) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }
    }

    /**
     * Play a local file directly. Unlike play(), the error handler doesn't
     * try to fetch a URL from NetEase API, preventing unnecessary API calls
     * for downloaded songs (including Bilibili downloads).
     */
    private void playLocalFile(String localPath, Song song) {
        playLocalFile(localPath, song, 0, true);
    }

    private void playLocalFile(String localPath, Song song, int resumePositionMs,
                               boolean shouldPlayWhenReady) {
        stop();
        cancelBilibiliRefreshTimer();
        mediaPlayer = new MediaPlayer();
        try {
            if (appContext != null) {
                mediaPlayer.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK);
            }
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build());
            mediaPlayer.setDataSource(localPath);
            currentSourcePath = localPath;
            mediaPlayer.setOnPreparedListener(mp -> {
                // 已经被替换掉的旧播放器的迟到回调，直接忽略。
                // 少了这个判断会出现：新歌已经起播，旧实例的 onPrepared 才回来，
                // 于是又去 start() 一次已 release 的 MediaPlayer → IllegalStateException 崩溃。
                if (mp != mediaPlayer) return;
                if (resumePositionMs > 0) {
                    try {
                        mp.seekTo(resumePositionMs);
                    } catch (Exception e) {
                        Log.w(TAG, "Error restoring local playback position", e);
                    }
                }
                applyPlaybackSpeed();
                if (shouldPlayWhenReady) {
                    mp.start();
                    isPlaying = true;
                    notifyPlayStateChanged(true);
                } else {
                    isPlaying = false;
                    notifyPlayStateChanged(false);
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                // 同上：只有当前播放器的完成事件才算数。
                // 否则「把进度条拖到结尾」时，旧实例的完成回调会再切一次歌，
                // 表现为连跳两首、或新歌刚起播就被下一轮切歌打断（听感像自动暂停）。
                if (mp != mediaPlayer) return;
                onSongCompleted();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                // 旧播放器的迟到错误必须忽略：它内部用的是 getCurrentSong()，
                // 而此时当前歌已经是下一首了 —— 会把新歌的 URL 清掉并重新拉流，
                // 于是刚起播的新歌被打断，看起来就是「下一首自动暂停」。
                if (mp != mediaPlayer) return true;
                // what==-38 is ENOSYS ("function not implemented") fired asynchronously
                // by setPlaybackParams() on watch devices (Android 7-8.1) that lack
                // time-stretch support.  Playback is unaffected; silently ignore it.
                if (what == -38) return true;
                isPlaying = false;
                notifyPlayStateChanged(false);
                if (callback != null) {
                    mainHandler.post(() -> callback.onError("本地文件播放错误: " + what));
                }
                return true;
            });
            mediaPlayer.prepareAsync();
            probeLoudnessFeasibility(false);   // F5/R4 探针
            applyLoudnessBalance();
        } catch (Exception e) {
            if (callback != null) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }
    }

    public void pause() {
        if (usingExoPlayer && exoPlayer != null) {
            exoPlayer.pause();
            isPlaying = false;
            notifyPlayStateChanged(false);
        } else if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            notifyPlayStateChanged(false);
        }
    }

    // ==================== F5 限音器可行性探针（文档风险 R4） ====================
    // R4：实时增益需要播放器的 audio session，文档要求「优先验证 LoudnessEnhancer」。
    // 一次性探针：挂上 LoudnessEnhancer、读回增益、立刻释放 —— 只写日志，不改播放行为。
    private boolean loudnessProbedMediaPlayer = false;
    private boolean loudnessProbedExoPlayer = false;

    /** 供限音器（F5）后续使用：当前播放器的 audio session id；拿不到时返回 0。 */
    public int getAudioSessionId() {
        try {
            if (usingExoPlayer && exoPlayer != null) {
                return exoPlayer.getAudioSessionId();
            }
            if (mediaPlayer != null) {
                return mediaPlayer.getAudioSessionId();
            }
        } catch (Throwable t) {
            MusicLog.w("Loudness", "取 audio session 失败: " + t);
        }
        return 0;
    }

    /** 一次性探针：双路各探一次，便于分别下结论。 */
    private void probeLoudnessFeasibility(boolean exoPath) {
        if (exoPath ? loudnessProbedExoPlayer : loudnessProbedMediaPlayer) {
            return;
        }
        if (exoPath) {
            loudnessProbedExoPlayer = true;
        } else {
            loudnessProbedMediaPlayer = true;
        }
        int session = getAudioSessionId();
        MusicLog.i("Loudness", "探针(" + (exoPath ? "ExoPlayer" : "MediaPlayer")
                + "): audioSession=" + session + " usingExo=" + usingExoPlayer);
        if (session == 0) {
            MusicLog.w("Loudness", "探针结论: 拿不到 audio session => 该路无法用 LoudnessEnhancer");
            return;
        }
        android.media.audiofx.LoudnessEnhancer enh = null;
        try {
            enh = new android.media.audiofx.LoudnessEnhancer(session);
            enh.setTargetGain(600);
            float readBack = enh.getTargetGain();
            enh.setEnabled(true);
            MusicLog.i("Loudness", "探针结论: LoudnessEnhancer 可用 OK setTargetGain(600mB) 读回=" + readBack
                    + " enabled=" + enh.getEnabled() + " => 风险 R4 解除（该路可做实时增益）");
        } catch (Throwable t) {
            MusicLog.w("Loudness", "探针结论: LoudnessEnhancer 挂载失败 => 该路不可用: " + t);
        } finally {
            if (enh != null) {
                try {
                    enh.setEnabled(false);
                } catch (Throwable ignored) {
                }
                try {
                    enh.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** F5：释放响度增益（换歌/停止/关闭开关时都要走到）。 */
    private void releaseLoudnessEnhancer() {
        if (loudnessEnhancer == null) {
            return;
        }
        try {
            loudnessEnhancer.setEnabled(false);
        } catch (Throwable ignored) {
        }
        try {
            loudnessEnhancer.release();
        } catch (Throwable ignored) {
        }
        loudnessEnhancer = null;
    }

    /**
     * F5：按当前曲目施加响度增益。
     *
     * <p>开关关闭 / 无索引 / 无该曲目条目时增益为 0 ⇒ **直接返回，播放路径完全不变**
     * （文档 F5 改动要点 5：关闭后行为恢复原状）。</p>
     */
    private void applyLoudnessBalance() {
        releaseLoudnessEnhancer();
        float gainDb = com.qinghe.music163pro.util.LoudnessBalance.gainDbFor(appContext, currentSourcePath);
        if (gainDb == 0f) {
            MusicLog.i("Loudness", "本曲不施加增益（未启用/无有效索引）: " + currentSourcePath);
            return;
        }
        int session = getAudioSessionId();
        if (session == 0) {
            MusicLog.w("Loudness", "无 audio session，无法施加增益");
            return;
        }
        try {
            loudnessEnhancer = new android.media.audiofx.LoudnessEnhancer(session);
            int mB = Math.round(gainDb * 100f);
            loudnessEnhancer.setTargetGain(mB);
            loudnessEnhancer.setEnabled(true);
            String name = currentSourcePath == null ? "?" : new java.io.File(currentSourcePath).getName();
            MusicLog.i("Loudness", "已施加响度增益 " + String.format(java.util.Locale.US, "%+.2f", gainDb)
                    + "dB (targetGain=" + mB + "mB) session=" + session + " 曲目=" + name);
        } catch (Throwable t) {
            MusicLog.w("Loudness", "施加增益失败（保持原样）: " + t);
            releaseLoudnessEnhancer();
        }
    }

    public void resume() {
        cancelPendingSleepStopOnManualAction();
        if (usingExoPlayer && exoPlayer != null) {
            exoPlayer.play();
            isPlaying = true;
            notifyPlayStateChanged(true);
        } else if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            isPlaying = true;
            notifyPlayStateChanged(true);
        }
    }

    public void stop() {
        releaseLoudnessEnhancer();
        cancelBilibiliRefreshTimer();
        if (exoPlayer != null) {
            ExoPlayer player = exoPlayer;
            // 先清字段再 release：release 过程中若还有回调进来，
            // 监听器里的身份判断（player != exoPlayer）会直接把它挡掉。
            exoPlayer = null;
            try {
                player.release();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping ExoPlayer", e);
            }
        }
        if (mediaPlayer != null) {
            MediaPlayer player = mediaPlayer;
            mediaPlayer = null;
            try {
                // 主动摘掉监听器，避免 release 之后再收到迟到回调
                player.setOnPreparedListener(null);
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
                if (player.isPlaying()) {
                    player.stop();
                }
                player.release();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping player", e);
            }
        }
        isPlaying = false;
        currentlyPlayingSongId = -1;
        usingExoPlayer = false;
    }

    public int getCurrentPosition() {
        if (usingExoPlayer && exoPlayer != null) {
            try {
                return (int) exoPlayer.getCurrentPosition();
            } catch (Exception e) {
                return 0;
            }
        }
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public int getDuration() {
        if (usingExoPlayer && exoPlayer != null) {
            try {
                long dur = exoPlayer.getDuration();
                return dur == C.TIME_UNSET ? 0 : (int) dur;
            } catch (Exception e) {
                return 0;
            }
        }
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getDuration();
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public void seekTo(int positionMs) {
        int target = Math.max(0, positionMs);
        // 进度条拖到最右端时，上层算出来的就是 duration。这里夹到 duration-1，
        // 避免把播放器停在「位置 == 总长」的边界状态上（不同 ROM 对这个边界的处理不一致）。
        int duration = getDuration();
        if (duration > 0 && target >= duration) {
            target = duration - 1;
        }
        if (usingExoPlayer && exoPlayer != null) {
            try {
                exoPlayer.seekTo(target);
            } catch (Exception e) {
                Log.w(TAG, "Error seeking ExoPlayer", e);
            }
        } else if (mediaPlayer != null) {
            try {
                mediaPlayer.seekTo(target);
            } catch (Exception e) {
                Log.w(TAG, "Error seeking", e);
            }
        }
    }

    /**
     * Called when current song finishes playing.
     * Behavior depends on play mode.
     */
    /**
     * 播放结束的统一入口。
     *
     * <p>加了防重入：把进度条直接拖到结尾时，MediaPlayer 的 onCompletion 与
     * ExoPlayer 的 STATE_ENDED 有可能在同一个调用栈里都被送到（其中一条来自
     * stop()/release() 引发的同步回调），那样一次结束会连着切两首歌，
     * 听感上就是「新歌刚起播就被打断」。</p>
     */
    private void onSongCompleted() {
        if (handlingSongCompleted) {
            return;
        }
        handlingSongCompleted = true;
        try {
            handleSongCompleted();
        } finally {
            handlingSongCompleted = false;
        }
    }

    /**
     * 「放完当前曲再停」已到点时，**任何「往下切歌」的动作都要改成停在这里**。
     *
     * <p>以前这个判断只挂在「自然播完」这一条路径上，于是只要切歌是从别的入口进来的
     * （顺序/随机的推进、通知栏的下一首、外部 next()），到点的定时就被绕过去了 ——
     * 这正是「播完当前不生效」的根因。现在把判断收口在这里，所有自动切歌入口统一拦一次。</p>
     *
     * @return true 表示已经停止播放，调用方**必须直接返回**，不要再切歌
     */
    private boolean stopForPendingSleepTimer() {
        if (!sleepTimerStopAfterCurrent) {
            return false;
        }
        sleepTimerStopAfterCurrent = false;
        boolean exitApp = shouldExitAfterSleepTimer();
        Log.i(TAG, "sleepTimer(finish-track) fired: stop playback, exitApp=" + exitApp);
        if (exitApp) {
            stop();
        } else {
            pause();
        }
        if (callback != null) {
            mainHandler.post(() -> callback.onSleepTimerTriggered(exitApp));
        }
        return true;
    }

    /**
     * 用户**手动**切歌 / 主动继续播放时，撤掉「放完当前曲再停」的待停状态。
     *
     * <p>手动动作代表「我还想继续听」，此时如果还留着待停标志，会在下一首结束时
     * 突然停掉，看起来像 bug。定时本身（若是总时长/净时长模式）不受影响。</p>
     */
    private void cancelPendingSleepStopOnManualAction() {
        if (sleepTimerStopAfterCurrent) {
            sleepTimerStopAfterCurrent = false;
            Log.i(TAG, "sleepTimer(finish-track): canceled by manual action");
        }
    }

    private void handleSongCompleted() {
        isPlaying = false;
        notifyPlayStateChanged(false);

        // 「放完当前曲再停」：定时已到点，这一首就是最后一首，不再往下切
        if (stopForPendingSleepTimer()) {
            return;
        }

        if (playlist.isEmpty()) return;
        // 「下一首播放」优先于播放模式（含单曲循环）：用户明确指定了下一首是什么
        if (consumeQueuedNext()) return;
        switch (playMode) {
            case SINGLE_REPEAT:
                // Replay current song
                playCurrent();
                break;
            case RANDOM:
                // 沿洗牌顺序播放，与手动「下一首」共用同一套次序，
                // 避免自动播完和手动切歌在随机模式下走成两条不同的随机序列。
                if (personalFmMode) {
                    playNextSequential();
                } else {
                    playShuffle(true);
                }
                break;
            case LIST_LOOP:
            default:
                playNextSequential();
                break;
        }
    }

    /**
     * 手动切到下一首。
     *
     * <p>随机模式下沿洗牌顺序前进；私人漫游是一路追加的无限流，仍走顺序逻辑以保留
     * 「到底自动加载更多」的行为。单曲循环只作用于「自动播完」，手动点下一首照常切歌，
     * 与主流播放器一致。</p>
     */
    /** 听歌打卡门槛：播放满多少毫秒才上报（跟着网易的习惯，避免划歌也记一笔）。 */
    private static final int SCROBBLE_MIN_MS = 30_000;
    private long scrobbledSongId = -1L;

    /**
     * 播放满 30 秒给服务端打一次卡（每首歌只报一次）。
     * 由界面的进度定时器调用，因此不额外引入定时器。
     */
    public void reportPlayIfNeeded() {
        Song song = getCurrentSong();
        if (song == null || song.getId() <= 0 || !isPlaying()) {
            return;
        }
        if (scrobbledSongId == song.getId()) {
            return;
        }
        if (getCurrentPosition() < SCROBBLE_MIN_MS) {
            return;
        }
        scrobbledSongId = song.getId();
        long sourceId = sourcePlaylistId > 0 ? sourcePlaylistId : song.getId();
        android.util.Log.i("Scrobble", "触发打卡: songId=" + song.getId()
                + " pos=" + getCurrentPosition() + " sourceId=" + sourceId);
        MusicApiHelper.scrobble(song.getId(), sourceId, getCookie());
    }

    /** 取不到播放地址时最多连续自动跳过几首（防整列表灰歌时无限跳）。 */
    private static final int MAX_AUTO_SKIP = 3;
    private int autoSkipCount = 0;

    public void next() {
        if (playlist.isEmpty()) return;
        // 手动切歌 = 还想继续听 → 撤销「放完当前曲再停」，避免下一首结束又莫名其妙停掉
        cancelPendingSleepStopOnManualAction();
        // 「下一首播放」优先：用户明确指定了接下来听什么，随机模式下也让位于它
        if (consumeQueuedNext()) return;
        if (playMode == PlayMode.RANDOM && !personalFmMode) {
            // 1016：随机播放模式下，上下键可按设置走「随机切歌」或「列表顺序」
            if (isRandomSkipRandom()) {
                playShuffle(true);
            } else {
                playNextSequential();
            }
        } else {
            playNextSequential();
        }
    }

    /**
     * 手动切到上一首。随机模式下沿洗牌顺序后退，因此能真正回到刚才那首，
     * 而不是又随机抽一首。
     */
    public void previous() {
        if (playlist.isEmpty()) return;
        cancelPendingSleepStopOnManualAction();
        if (playMode == PlayMode.RANDOM && !personalFmMode) {
            // 1016：随机切歌开关关掉时，上一首按列表顺序回退
            if (isRandomSkipRandom()) {
                playShuffle(false);
            } else {
                currentIndex = (currentIndex - 1 + playlist.size()) % playlist.size();
                playCurrent();
            }
        } else {
            currentIndex = (currentIndex - 1 + playlist.size()) % playlist.size();
            playCurrent();
        }
    }

    public void playFromCurrentPlaylist(int index) {
        if (index < 0 || index >= playlist.size()) return;
        currentIndex = index;
        playCurrent();
    }

    /**
     * 从播放队列里移除一首。
     *
     * <p>移除后会自动修正 {@link #currentIndex}：移除的是当前曲目之前的项，
     * 或移除后下标越界，都会跟着调整。若移除的正是当前播放的那首，则接着播新的当前曲；
     * 队列被清空则停止播放。</p>
     *
     * <p>⚠️ 队列结构变了，必须 {@link #invalidateShuffleOrder()}，
     * 否则随机模式下原来缓存的洗牌下标会错位。</p>
     *
     * @return true 表示确实移除了
     */
    public boolean removeFromPlaylist(int index) {
        if (index < 0 || index >= playlist.size()) {
            return false;
        }
        boolean removedCurrent = (index == currentIndex);
        Song removed = playlist.remove(index);
        // 被删掉的歌若在「下一首播放」队列里，标记一并清除。
        // （这里手写倒序循环而不是 List#removeIf —— 后者是 Java 8 默认方法，minSdk 23 上没有）
        for (int i = playNextQueue.size() - 1; i >= 0; i--) {
            if (isSameSong(playNextQueue.get(i), removed)) {
                playNextQueue.remove(i);
            }
        }

        if (playlist.isEmpty()) {
            currentIndex = -1;
            invalidateShuffleOrder();
            stop();
            return true;
        }

        if (currentIndex >= playlist.size()) {
            currentIndex = playlist.size() - 1;
        } else if (index < currentIndex) {
            currentIndex--;
        }
        invalidateShuffleOrder();

        if (removedCurrent) {
            playCurrent();
        } else {
            savePlaybackState();
        }
        return true;
    }

    // ==================== 「下一首播放」队列（可叠加） ====================

    /** 已排入「下一首播放」的歌（按顺序，返回副本）。 */
    public List<Song> getPlayNextQueue() {
        return new ArrayList<>(playNextQueue);
    }

    /** 这首歌是否已排入「下一首播放」。 */
    public boolean isQueuedNext(Song song) {
        return indexOfQueuedNext(song) >= 0;
    }

    /** 这首歌在「下一首播放」队列里的序号（1 起）；未排入返回 0。 */
    public int queuedNextPosition(Song song) {
        int i = indexOfQueuedNext(song);
        return i < 0 ? 0 : i + 1;
    }

    private int indexOfQueuedNext(Song song) {
        if (song == null) {
            return -1;
        }
        for (int i = 0; i < playNextQueue.size(); i++) {
            if (isSameSong(playNextQueue.get(i), song)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 同一首歌的判据：先比引用，再比「id + url」。
     *
     * <p>播放列表里可能出现 id 相同但来源不同的条目（云端 / 本地），带上 url 才区分得开。</p>
     */
    private boolean isSameSong(Song a, Song b) {
        if (a == null || b == null) {
            return false;
        }
        if (a == b) {
            return true;
        }
        if (a.getId() != b.getId()) {
            return false;
        }
        String ua = a.getUrl();
        String ub = b.getUrl();
        return ua == null ? ub == null : ua.equals(ub);
    }

    private int indexOfSongInPlaylist(Song song) {
        for (int i = 0; i < playlist.size(); i++) {
            if (isSameSong(playlist.get(i), song)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把队列里的某一首排到「下一首播放」（可叠加）。
     *
     * <p>插入位置是「当前歌之后、已排入的那些之后」，所以连选多首会**按选择顺序**
     * 依次播放。原实现每次都插到 {@code currentIndex+1}，连选两首会互相顶替、
     * 播出倒序 —— 这就是「不能叠加」的原因。</p>
     *
     * @return true 表示确实排入了；点当前正在播放的那首、或已经排过的，返回 false
     */
    public boolean queueNextInPlaylist(int index) {
        if (index < 0 || index >= playlist.size() || index == currentIndex) {
            return false;
        }
        Song song = playlist.get(index);
        if (isQueuedNext(song)) {
            return false;
        }
        playlist.remove(index);
        // 抽走的是当前曲目之前的项时，当前下标要跟着前移一位
        if (index < currentIndex) {
            currentIndex--;
        }
        // 追加在已排入的歌之后，保证叠加顺序 = 选择顺序
        int insertAt = Math.min(currentIndex + 1 + playNextQueue.size(), playlist.size());
        playlist.add(insertAt, song);
        playNextQueue.add(song);
        invalidateShuffleOrder();
        savePlaybackState();
        return true;
    }

    /**
     * 取消「下一首播放」：去掉标记，并把它挪到队尾（不再插队）。
     *
     * <p>不去还原「最初的位置」—— 排入时已经把它从原位置抽走，之后再增删会让原位置
     * 失去意义。挪到队尾是歧义最小、也最符合「取消下次优先」直觉的做法。</p>
     *
     * @return true 表示确实取消了
     */
    public boolean cancelQueuedNext(Song song) {
        int qi = indexOfQueuedNext(song);
        if (qi < 0) {
            return false;
        }
        playNextQueue.remove(qi);
        int idx = indexOfSongInPlaylist(song);
        if (idx >= 0) {
            Song s = playlist.remove(idx);
            if (idx < currentIndex) {
                currentIndex--;
            }
            playlist.add(s);
        }
        invalidateShuffleOrder();
        savePlaybackState();
        return true;
    }

    /**
     * 切下一首时优先消费「下一首播放」队列。
     *
     * @return true 表示已切到队列里的歌；队列为空（或里面那些已不在播放列表）时返回 false
     */
    private boolean consumeQueuedNext() {
        while (!playNextQueue.isEmpty()) {
            Song head = playNextQueue.remove(0);
            int idx = indexOfSongInPlaylist(head);
            if (idx >= 0) {
                currentIndex = idx;
                playCurrent();
                return true;
            }
        }
        return false;
    }

    public void playCurrent() {
        Song song = getCurrentSong();
        if (song == null) return;

        notifySongChanged(song);
        savePlaybackState();

        if (song.isForceLocalPlayback()) {
            // 下载列表给出的 url 就是音频文件的绝对路径，优先直接用它：
            // 当目录名与「曲名 - 曲师」对不上时（手动拷进来的音乐、缺 info.json 的下载），
            // 按名字反查会找不到文件。
            String localPath = song.getUrl();
            if (TextUtils.isEmpty(localPath) || !new File(localPath).exists()) {
                // 手上的路径不可用（状态里没存 / 文件被移走 / SD 卡还没挂载好）：
                // 先按 id 查本地音乐库缓存，查不到再退回下载目录反查。
                String cachedPath = LocalLibraryCache.pathFor(song.getId());
                localPath = cachedPath != null ? cachedPath
                        : DownloadManager.getDownloadedMp3Path(song);
            }
            if (TextUtils.isEmpty(localPath)) {
                // 最后兜底：不校验存在性再要一次。SD 卡尚未挂载完成时 exists() 会一律
                // 返回 false，但缓存里那条路径其实是对的 —— 交给播放器去真正打开文件，
                // 而不是在这里就判成「找不到」。
                localPath = LocalLibraryCache.pathForUnchecked(song.getId());
            }
            if (!TextUtils.isEmpty(localPath)) {
                song.setUrl(localPath);
                startLocalPlayback(song, localPath);
                return;
            }
            notifyLocalPlaybackMissing(song);
            return;
        }

        // Handle Bilibili songs (stream from network)
        if (song.isBilibili()) {
            playBilibiliSong(song);
            return;
        }

        // url 指向本地文件：收藏列表里读出来的本地歌曲只有 url 是本地路径、
        // 没有 forceLocalPlayback 标记，不接住它就会拿负数 id 去问网易云要链接然后失败。
        String existingUrl = song.getUrl();
        if (TextUtils.isEmpty(existingUrl)
                || !LocalTracks.isLocalPath(existingUrl)
                || !new File(existingUrl).exists()) {
            // 本地歌但手上的路径不可用 → 用缓存补一次（收藏列表里读出来的本地歌常遇到）
            String repaired = LocalLibraryCache.pathFor(song.getId());
            if (repaired != null) {
                existingUrl = repaired;
            }
        }
        if (LocalTracks.isLocalPath(existingUrl) && new File(existingUrl).exists()) {
            startLocalPlayback(song, existingUrl);
            return;
        }

        // 用户主动播放（或上下键切歌）时，重新允许这首歌再做一次错误重试。
        // MediaPlayer 错误循环走的是 play() 内部重试、不经过这里，所以不会重新打开死循环。
        errorRetrySongId = -1;

        // Always fetch a fresh URL to avoid expired URL issues.
        // NetEase song URLs are time-limited, so cached URLs may not work.
        String cookie = getCookie();
        // Read preferred quality from SharedPreferences
        String preferredQuality = "exhigh";
        if (appContext != null) {
            android.content.SharedPreferences prefs = appContext.getSharedPreferences(
                    "music163_settings", android.content.Context.MODE_PRIVATE);
            preferredQuality = prefs.getString("preferred_quality", "exhigh");
        }
        String qualityForLambda = preferredQuality;
        MusicApiHelper.getSongUrlInfo(song.getId(), cookie, qualityForLambda,
                new MusicApiHelper.SongUrlInfoCallback() {
            @Override
            public void onResult(String freshUrl, boolean trialOnly) {
                autoSkipCount = 0;   // 成功播放后清零"连续跳过"计数
                // 1016：主接口给出试听片段、或只给了外链直连兜底（外层 /song/media/outer/url）时，
                // 交给手表网关再要一次完整链接。后一种情况在未登录时很常见：匿名 eapi 对 VIP 歌
                // 直接 404（没有 freeTrialInfo），不路由到网关就只能听到外链跳转后的试听。
                boolean needsGateway = trialOnly
                        || (freshUrl != null && freshUrl.contains("/song/media/outer/url"));
                if (needsGateway && isWatchParseEnabled()) {
                    resolveViaWatchGateway(song, freshUrl, cookie);
                    return;
                }
                finishPlay(song, freshUrl, trialOnly);
            }

            @Override
            public void onError(String message) {
                // Fallback: try cached URL if available
                if (song.getUrl() != null && !song.getUrl().isEmpty()) {
                    currentlyPlayingSongId = song.getId();
                    play(song.getUrl());
                } else if (autoSkipCount < MAX_AUTO_SKIP) {
                    // 真的取不到播放地址（无版权/下架/VIP 限制）→ 明确提示并自动跳下一首，
                    // 避免用户点了没反应；连续跳过有上限，防止整列表都是灰歌时无限跳。
                    autoSkipCount++;
                    if (appContext != null) {
                        mainHandler.post(() -> android.widget.Toast.makeText(appContext,
                                "该歌曲无法播放，已自动跳过",
                                android.widget.Toast.LENGTH_SHORT).show());
                    }
                    mainHandler.post(MusicPlayerManager.this::next);
                } else {
                    autoSkipCount = 0;
                    if (callback != null) {
                        mainHandler.post(() -> callback.onError("无法获取歌曲链接: " + message));
                    }
                }
            }
        });
    }

    // ==================== 1016：手表网关（游客/试听兜底） ====================

    /**
     * 拿到试听链接且「使用手表解析接口」开着时：找手表网关（NeteaseWatchApi，eapi trialMode）
     * 要完整链接。
     *   - 网关给出完整链接 → 播它
     *   - 网关没有（空/仍是试听）→ 回退原始试听链接，再走原来的「跳过试听」判断
     */
    private void resolveViaWatchGateway(Song song, String trialUrl, String sessionCookie) {
        MusicLog.i("MusicPlayerManager", "试听链接，手表网关解析 songId=" + song.getId());
        boolean loggedIn = sessionCookie != null && sessionCookie.contains("MUSIC_U");
        if (loggedIn) {
            // 已登录：用登录态 cookie 认证网关
            NeteaseWatchApi.setSessionCookie(sessionCookie);
            requestWatchPlayUrl(song, trialUrl);
            return;
        }
        // 未登录：复用首次使用时的匿名「游客」cookie（长期保存、任何情况都不删除，
        // 只给 NeteaseWatchApi 的取 URL 请求用，绝不用于主接口）。
        String guest = readPersistedGuestCookie();
        if (!guest.isEmpty()) {
            NeteaseWatchApi.setSessionCookie(guest);
            requestWatchPlayUrl(song, trialUrl);
            return;
        }
        // 首次使用：通过游客接口铸一个匿名会话
        NeteaseWatchApi.fetchGuestSession(new NeteaseWatchApi.GuestSessionCallback() {
            @Override
            public void onResult(String guestCookie) {
                if (!guestCookie.isEmpty()) {
                    persistGuestCookie(guestCookie);
                    NeteaseWatchApi.setSessionCookie(guestCookie);
                    MusicLog.i("MusicPlayerManager", "已获取并保存游客会话");
                }
                requestWatchPlayUrl(song, trialUrl);
            }

            @Override
            public void onError(String message) {
                MusicLog.w("MusicPlayerManager", "获取游客会话失败: " + message);
                requestWatchPlayUrl(song, trialUrl);
            }
        });
    }

    /** 游客 cookie (watch_guest_cookie) —— 长期存储，任何情况都不删除。 */
    private static final String PREF_WATCH_GUEST_COOKIE = "watch_guest_cookie";

    private String readPersistedGuestCookie() {
        return appContext == null ? "" : appContext.getSharedPreferences("music163_settings",
                Context.MODE_PRIVATE).getString(PREF_WATCH_GUEST_COOKIE, "");
    }

    private void persistGuestCookie(String guestCookie) {
        if (appContext == null || guestCookie == null || guestCookie.isEmpty()) return;
        appContext.getSharedPreferences("music163_settings", Context.MODE_PRIVATE)
                .edit().putString(PREF_WATCH_GUEST_COOKIE, guestCookie).apply();
    }

    /** resolveViaWatchGateway 的后半段：发网关请求并处理回退。 */
    private void requestWatchPlayUrl(Song song, String trialUrl) {
        NeteaseWatchApi.getPlayUrl(song.getId(), new NeteaseWatchApi.WatchUrlCallback() {
            @Override
            public void onResult(String url, boolean trialOnly) {
                if (!trialOnly && url != null && !url.isEmpty()) {
                    MusicLog.i("MusicPlayerManager", "手表网关返回完整链接 songId=" + song.getId());
                    finishPlay(song, url, false);
                } else {
                    // 网关也没给完整链接：回退试听链接
                    finishPlay(song, trialUrl, true);
                }
            }

            @Override
            public void onError(String message) {
                MusicLog.w("MusicPlayerManager", "手表网关解析失败，回退试听: " + message);
                finishPlay(song, trialUrl, true);
            }
        });
    }

    /** playCurrent 的共同收尾：先按「跳过试听」开关判断，然后真正开始播放。 */
    private void finishPlay(Song song, String url, boolean trialOnly) {
        if (trialOnly && isSkipTrialEnabled()) {
            onTrialOnlySong(song);
            return;
        }
        trialSkipStreak = 0;
        song.setUrl(url);
        currentlyPlayingSongId = song.getId();
        play(url);
    }

    /** 开关选项：是否使用手表解析接口（默认开）。 */
    private boolean isWatchParseEnabled() {
        return appContext == null || appContext.getSharedPreferences("music163_settings",
                Context.MODE_PRIVATE).getBoolean("use_watch_parse", true);
    }

    /** 开关选项：随机播放模式下上下键随机切歌（默认关 = 按列表顺序）。 */
    private boolean isRandomSkipRandom() {
        return appContext != null && appContext.getSharedPreferences("music163_settings",
                Context.MODE_PRIVATE).getBoolean("random_skip_random", false);
    }

    // ==================== 1016：跳过试听歌曲 ====================

    private int trialSkipStreak = 0;

    /** 开关选项：仅可试听的歌曲自动跳过（默认关）。 */
    private boolean isSkipTrialEnabled() {
        return appContext != null && appContext.getSharedPreferences("music163_settings",
                Context.MODE_PRIVATE).getBoolean("skip_trial_songs", false);
    }

    /**
     * URL 接口只给了试听片段、且用户开了「跳过试听歌曲」：静默跳到下一首。
     *
     * <p>跳完一整圈还没有可播的歌就停下（而不是无限循环）；私人 FM 到最后一首时走顺序推进。</p>
     */
    private void onTrialOnlySong(Song song) {
        trialSkipStreak++;
        // 静默跳过（不再 Toast「已跳过试听歌曲」）
        Log.d(TAG, "试听歌曲跳过: " + song.getDisplayName());
        if (playlist.isEmpty()) {
            return;
        }
        if (trialSkipStreak >= playlist.size()) {
            // 整圈都没有可播的歌：停止，避免无限循环
            trialSkipStreak = 0;
            if (callback != null) {
                mainHandler.post(() -> callback.onError("列表歌曲仅可试听，已停止"));
            }
            stop();
            notifyPlayStateChanged(false);
            return;
        }
        if (personalFmMode && currentIndex == playlist.size() - 1) {
            playNextSequential();
            return;
        }
        if (playMode == PlayMode.RANDOM && playlist.size() > 1) {
            int newIndex;
            do {
                newIndex = random.nextInt(playlist.size());
            } while (newIndex == currentIndex);
            currentIndex = newIndex;
        } else {
            currentIndex = (currentIndex + 1) % playlist.size();
        }
        playCurrent();
    }

    /**
     * Play a Bilibili song by fetching the audio stream URL.
     * Bilibili audio URLs are time-limited, so always fetch fresh.
     */
    private void playBilibiliSong(Song song) {
        bilibiliRetrySongId = song.getId();
        bilibiliRetryCount = 0;
        String bilibiliCookie = getBilibiliCookie();
        BilibiliApiHelper.getAudioStreamUrl(song.getBvid(), song.getCid(), bilibiliCookie,
                new BilibiliApiHelper.AudioStreamCallback() {
                    @Override
                    public void onResult(String audioUrl) {
                        song.setUrl(audioUrl);
                        currentlyPlayingSongId = song.getId();
                        playWithHeaders(song, audioUrl, 0);
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) {
                            mainHandler.post(() -> callback.onError("B站音频获取失败: " + message));
                        }
                    }
                });
    }

    /**
     * Play audio with Bilibili-specific headers (Referer required).
     */
    private void playWithHeaders(String url) {
        playWithHeaders(getCurrentSong(), url, 0);
    }

    /**
     * Play Bilibili audio using ExoPlayer with custom buffer settings.
     * ExoPlayer provides much better seek performance and buffering than
     * MediaPlayer for HTTP streaming, especially on watch devices with
     * limited resources - preventing stuttering at large seek positions.
     */
    @OptIn(markerClass = UnstableApi.class)
    private void playWithHeaders(Song song, String url, int resumePositionMs) {
        stop();
        cancelBilibiliRefreshTimer();
        prefetchedBilibiliUrl = null;
        usingExoPlayer = true;

        if (appContext == null) {
            if (callback != null) {
                mainHandler.post(() -> callback.onError("播放器上下文未初始化"));
            }
            return;
        }

        try {
            // Buffer settings optimized for watch devices:
            // - Larger buffers to prevent stuttering at far seek positions
            // - Generous rebuffer threshold to avoid repeated micro-buffers
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            30_000,   // minBufferMs: 30 seconds
                            120_000,  // maxBufferMs: 2 minutes
                            5_000,    // bufferForPlaybackMs: 5s before starting
                            15_000    // bufferForPlaybackAfterRebufferMs: 15s after rebuffer
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();

            // HTTP DataSource with Bilibili headers
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://www.bilibili.com");
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(headers)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(30_000)
                    .setAllowCrossProtocolRedirects(true);

            DefaultDataSource.Factory dataSourceFactory =
                    new DefaultDataSource.Factory(appContext, httpFactory);

            final ExoPlayer player = new ExoPlayer.Builder(appContext)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                    .build();
            exoPlayer = player;

            exoPlayer.setWakeMode(C.WAKE_MODE_NETWORK);
            exoPlayer.setAudioAttributes(
                    new androidx.media3.common.AudioAttributes.Builder()
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .setUsage(C.USAGE_MEDIA)
                            .build(),
                    true  // handleAudioFocus
            );

            final Song targetSong = song;
            exoPlayer.addListener(new Player.Listener() {
                private boolean prepared = false;

                @Override
                public void onPlaybackStateChanged(int state) {
                    // 已被替换（release 过）的旧 ExoPlayer 的迟到回调，一律忽略。
                    // 否则它的 STATE_ENDED 会让刚起播的新歌被当成「播完了」而再切一次。
                    if (player != exoPlayer) return;
                    if (state == Player.STATE_READY && !prepared) {
                        prepared = true;
                        applyPlaybackSpeed();
                        isPlaying = true;
                        if (targetSong != null && targetSong.isBilibili()) {
                            bilibiliRetrySongId = targetSong.getId();
                            startBilibiliRefreshTimer(targetSong);
                        }
                        notifyPlayStateChanged(true);
                    } else if (state == Player.STATE_ENDED) {
                        isPlaying = false;
                        cancelBilibiliRefreshTimer();
                        onSongCompleted();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    if (player != exoPlayer) return;
                    isPlaying = false;
                    notifyPlayStateChanged(false);
                    cancelBilibiliRefreshTimer();

                    Song currentSong = getCurrentSong();
                    // Allow up to 3 retries for Bilibili CDN URL expiration
                    if (currentSong != null
                            && currentSong.isBilibili()
                            && currentSong.getId() == bilibiliRetrySongId
                            && bilibiliRetryCount < 3) {
                        bilibiliRetryCount++;
                        int pos = getCurrentPosition();
                        // Use prefetched URL if available (faster recovery)
                        if (prefetchedBilibiliUrl != null) {
                            String freshUrl = prefetchedBilibiliUrl;
                            prefetchedBilibiliUrl = null;
                            currentSong.setUrl(freshUrl);
                            currentlyPlayingSongId = currentSong.getId();
                            playWithHeaders(currentSong, freshUrl, Math.max(pos, 0));
                        } else {
                            currentSong.setUrl(null);
                            retryBilibiliPlayback(currentSong, pos);
                        }
                        return;
                    }
                    if (callback != null) {
                        String errMsg = error.getMessage() != null ? error.getMessage() : "unknown";
                        mainHandler.post(() -> callback.onError("B站播放错误: " + errMsg));
                    }
                }
            });

            exoPlayer.setMediaItem(MediaItem.fromUri(url));
            exoPlayer.prepare();
            probeLoudnessFeasibility(true);   // F5/R4 探针
            applyLoudnessBalance();

            if (resumePositionMs > 0) {
                exoPlayer.seekTo(resumePositionMs);
            }
            exoPlayer.setPlayWhenReady(true);
        } catch (Exception e) {
            if (callback != null) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }
    }

    private void retryBilibiliPlayback(Song song, int resumePositionMs) {
        String bilibiliCookie = getBilibiliCookie();
        BilibiliApiHelper.getAudioStreamUrl(song.getBvid(), song.getCid(), bilibiliCookie,
                new BilibiliApiHelper.AudioStreamCallback() {
                    @Override
                    public void onResult(String audioUrl) {
                        song.setUrl(audioUrl);
                        currentlyPlayingSongId = song.getId();
                        playWithHeaders(song, audioUrl, Math.max(resumePositionMs, 0));
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) {
                            mainHandler.post(() -> callback.onError("B站播放重连失败: " + message));
                        }
                    }
                });
    }

    /**
     * Start a periodic timer that pre-fetches a fresh Bilibili audio URL
     * before the current one expires (~30 min CDN limit).
     * The prefetched URL is stored and used for seamless recovery.
     */
    private void startBilibiliRefreshTimer(Song song) {
        cancelBilibiliRefreshTimer();
        bilibiliRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                Song currentSong = getCurrentSong();
                if (currentSong == null || !currentSong.isBilibili()
                        || currentSong.getId() != song.getId()) {
                    return;
                }
                Log.d(TAG, "Bilibili URL pre-refresh timer fired");
                String cookie = getBilibiliCookie();
                BilibiliApiHelper.getAudioStreamUrl(
                        currentSong.getBvid(), currentSong.getCid(), cookie,
                        new BilibiliApiHelper.AudioStreamCallback() {
                            @Override
                            public void onResult(String audioUrl) {
                                prefetchedBilibiliUrl = audioUrl;
                                Log.d(TAG, "Bilibili URL pre-fetched successfully");
                                // Schedule proactive switch after 3 minutes if not used
                                mainHandler.postDelayed(() -> {
                                    Song cs = getCurrentSong();
                                    if (cs != null && cs.isBilibili()
                                            && cs.getId() == song.getId()
                                            && prefetchedBilibiliUrl != null
                                            && isPlaying && exoPlayer != null) {
                                        int pos = getCurrentPosition();
                                        if (pos > 60000) {
                                            String freshUrl = prefetchedBilibiliUrl;
                                            prefetchedBilibiliUrl = null;
                                            bilibiliRetryCount = 0;
                                            Log.d(TAG, "Proactive Bilibili URL switch at " + pos + "ms");
                                            cs.setUrl(freshUrl);
                                            currentlyPlayingSongId = cs.getId();
                                            playWithHeaders(cs, freshUrl, pos);
                                        }
                                    }
                                }, 3 * 60 * 1000);
                                // Re-schedule for next refresh cycle
                                mainHandler.postDelayed(bilibiliRefreshRunnable,
                                        BILIBILI_REFRESH_INTERVAL_MS);
                            }

                            @Override
                            public void onError(String message) {
                                Log.w(TAG, "Bilibili URL pre-refresh failed: " + message);
                                // Retry after 5 minutes
                                mainHandler.postDelayed(bilibiliRefreshRunnable,
                                        5 * 60 * 1000);
                            }
                        });
            }
        };
        mainHandler.postDelayed(bilibiliRefreshRunnable, BILIBILI_REFRESH_INTERVAL_MS);
    }

    /**
     * Cancel the Bilibili URL refresh timer.
     */
    private void cancelBilibiliRefreshTimer() {
        if (bilibiliRefreshRunnable != null) {
            mainHandler.removeCallbacks(bilibiliRefreshRunnable);
            bilibiliRefreshRunnable = null;
        }
    }

    /**
     * Get Bilibili cookie from SharedPreferences.
     */
    private String getBilibiliCookie() {
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences("music163_settings",
                    Context.MODE_PRIVATE);
            return prefs.getString("bilibili_cookie", "");
        }
        return "";
    }

    public boolean isUsingExoPlayer() {
        return usingExoPlayer;
    }

    public void switchCurrentSongQuality(String quality) {
        Song song = getCurrentSong();
        if (song == null || song.isBilibili()) {
            return;
        }
        int resumePositionMs = getCurrentPosition();
        boolean wasPlaying = isPlaying;

        if (song.isForceLocalPlayback()) {
            String localPath = DownloadManager.getDownloadedPathForQuality(song, quality);
            if (localPath != null) {
                song.setUrl(localPath);
                song.setLocalQuality(quality);
                currentlyPlayingSongId = song.getId();
                playLocalFile(localPath, song, resumePositionMs, wasPlaying);
            } else if (callback != null) {
                mainHandler.post(() -> callback.onError("下载列表歌曲仅支持本地音质，请先下载该音质"));
            }
            return;
        }

        String cookie = getCookie();
        MusicApiHelper.getSongUrlWithQuality(song.getId(), cookie, quality,
                new MusicApiHelper.UrlCallback() {
                    @Override
                    public void onResult(String freshUrl) {
                        song.setUrl(freshUrl);
                        song.setLocalQuality(null);
                        currentlyPlayingSongId = song.getId();
                        play(freshUrl, resumePositionMs, wasPlaying);
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) {
                            mainHandler.post(() -> callback.onError("切换音质失败: " + message));
                        }
                    }
                });
    }

    public List<String> getCurrentPlayerInfoLines() {
        List<String> lines = new ArrayList<>();
        Song song = getCurrentSong();
        if (song == null) {
            lines.add("暂无播放信息");
            return lines;
        }
        String resolvedPath = song.getUrl();
        boolean isLocal = resolvedPath != null && resolvedPath.startsWith("/");
        lines.add("来源平台: " + (song.isBilibili() ? "Bilibili" : "网易云"));
        lines.add("播放方式: " + (isLocal ? "本地音频" : "URL播放"));
        lines.add("播放器内核: " + (usingExoPlayer ? "ExoPlayer" : "MediaPlayer"));

        String localQuality = song.getLocalQuality();
        if (localQuality == null && isLocal) {
            localQuality = DownloadManager.detectLocalQualityFromPath(resolvedPath);
        }
        if (!TextUtils.isEmpty(localQuality)) {
            lines.add("当前本地音质: " + formatQualityLabel(localQuality));
        } else if (!song.isBilibili() && appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences("music163_settings",
                    Context.MODE_PRIVATE);
            lines.add("当前目标音质: "
                    + formatQualityLabel(prefs.getString("preferred_quality", "exhigh")));
        }

        if (song.isBilibili()) {
            if (!TextUtils.isEmpty(song.getBvid())) {
                lines.add("BV号: " + song.getBvid());
            }
            if (song.getCid() > 0) {
                lines.add("CID: " + song.getCid());
            }
        } else if (song.getId() > 0) {
            lines.add("歌曲ID: " + song.getId());
        }

        if (sourcePlaylistId > 0 && !TextUtils.isEmpty(sourcePlaylistName)) {
            lines.add("播放来源歌单: " + sourcePlaylistName);
        }
        if (!TextUtils.isEmpty(resolvedPath)) {
            lines.add((isLocal ? "本地路径: " : "播放地址: ") + resolvedPath);
        }
        return lines;
    }

    private String formatQualityLabel(String quality) {
        if (quality == null) {
            return "";
        }
        switch (quality) {
            case "standard": return "标准";
            case "higher": return "较高";
            case "exhigh": return "极高";
            case "lossless": return "无损";
            case "hires": return "Hi-Res";
            case "jyeffect": return "臻品声场";
            case "sky": return "全景声";
            case "jymaster": return "臻品母带";
            default: return quality;
        }
    }

    private String cookieValue = "";

    public void setCookie(String cookie) {
        this.cookieValue = cookie != null ? cookie : "";
    }

    public String getCookie() {
        return cookieValue;
    }

    private void notifySongChanged(Song song) {
        // 播放统计挂在这里：这是「歌曲变了」的唯一出口，不会漏事件
        PlaybackStats.onSongStarted(appContext, song);
        if (callback != null) {
            mainHandler.post(() -> callback.onSongChanged(song));
        }
    }

    private void notifyPlayStateChanged(boolean playing) {
        // 同理，「播放状态变了」也只从这里出去，统计的起表 / 结算挂在这一处即可
        PlaybackStats.onPlayStateChanged(appContext, playing);
        if (callback != null) {
            mainHandler.post(() -> callback.onPlayStateChanged(playing));
        }
    }

    // ==================== Sleep Timer ====================

    /** 定时关闭模式（settings: sleep_timer_mode）：按总时长倒计时。 */
    public static final int SLEEP_TIMER_MODE_TOTAL = 0;
    /** 定时关闭模式：只累计实际播放时间。 */
    public static final int SLEEP_TIMER_MODE_NET = 1;
    /** 定时关闭模式：到点后不立刻停，等当前这首放完再停。 */
    public static final int SLEEP_TIMER_MODE_FINISH_TRACK = 2;

    private long sleepTimerEndMs = 0;
    private Runnable sleepTimerRunnable;

    // Net playback timer (counts only while playing)
    private long sleepTimerNetRemainingMs = 0;
    private boolean sleepTimerNetMode = false;
    private Runnable sleepTimerNetRunnable;

    /**
     * 「等这首放完再停」已经武装待命。
     *
     * <p>对应 {@link #SLEEP_TIMER_MODE_FINISH_TRACK}：倒计时到点时只置这个标志，
     * 真正的停止发生在 {@link #onSongCompleted()}，这样不会把歌掐在中间。</p>
     */
    private boolean sleepTimerStopAfterCurrent = false;

    /**
     * Start sleep timer. Stops playback after the specified number of minutes.
     * @param minutes number of minutes until auto-stop
     */
    public void startSleepTimer(int minutes) {
        startSleepTimerSeconds(minutes * 60);
    }

    /**
     * Start sleep timer using the current mode from settings.
     *
     * @param seconds total seconds for the timer
     */
    public void startSleepTimerSeconds(int seconds) {
        int mode = SLEEP_TIMER_MODE_TOTAL;
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences("music163_settings",
                    Context.MODE_PRIVATE);
            mode = prefs.getInt("sleep_timer_mode", SLEEP_TIMER_MODE_TOTAL);
        }
        if (mode == SLEEP_TIMER_MODE_NET) {
            startSleepTimerNetSeconds(seconds);
        } else {
            // 总时长与「放完当前曲」共用同一套倒计时，区别只在于到点后的动作
            startSleepTimerTotalSeconds(seconds, mode == SLEEP_TIMER_MODE_FINISH_TRACK);
        }
    }

    /**
     * Start total-duration sleep timer (normal countdown regardless of playback state).
     *
     * @param finishCurrentTrack 到点后是否等当前这首放完再停
     */
    private void startSleepTimerTotalSeconds(int seconds, boolean finishCurrentTrack) {
        cancelSleepTimer();
        long delayMs = (long) seconds * 1000;
        sleepTimerEndMs = System.currentTimeMillis() + delayMs;
        sleepTimerNetMode = false;
        sleepTimerRunnable = () -> {
            sleepTimerEndMs = 0;
            if (finishCurrentTrack) {
                // 只武装标志，等这首自然放完；期间定时器算「仍然生效」
                sleepTimerStopAfterCurrent = true;
                if (callback != null) {
                    mainHandler.post(() -> callback.onSleepTimerPendingStop());
                }
                return;
            }
            boolean exitApp = shouldExitAfterSleepTimer();
            if (exitApp) {
                stop();
            } else {
                pause();
            }
            if (callback != null) {
                mainHandler.post(() -> callback.onSleepTimerTriggered(exitApp));
            }
        };
        mainHandler.postDelayed(sleepTimerRunnable, delayMs);
    }

    /**
     * Start net playback sleep timer (counts down only while music is playing).
     */
    private void startSleepTimerNetSeconds(int seconds) {
        cancelSleepTimer();
        sleepTimerNetRemainingMs = (long) seconds * 1000;
        sleepTimerNetMode = true;
        sleepTimerEndMs = 1; // mark as active (non-zero)
        scheduleNetTimerTick();
    }

    private void scheduleNetTimerTick() {
        if (sleepTimerNetRunnable != null) {
            mainHandler.removeCallbacks(sleepTimerNetRunnable);
        }
        sleepTimerNetRunnable = () -> {
            if (!sleepTimerNetMode || sleepTimerNetRemainingMs <= 0) return;
            if (isPlaying) {
                sleepTimerNetRemainingMs -= 500;
            }
            if (sleepTimerNetRemainingMs <= 0) {
                sleepTimerNetRemainingMs = 0;
                sleepTimerEndMs = 0;
                sleepTimerNetMode = false;
                boolean exitApp = shouldExitAfterSleepTimer();
                if (exitApp) {
                    stop();
                } else {
                    pause();
                }
                if (callback != null) {
                    mainHandler.post(() -> callback.onSleepTimerTriggered(exitApp));
                }
                return;
            }
            scheduleNetTimerTick();
        };
        mainHandler.postDelayed(sleepTimerNetRunnable, 500);
    }

    /**
     * Cancel an active sleep timer.
     */
    public void cancelSleepTimer() {
        if (sleepTimerRunnable != null) {
            mainHandler.removeCallbacks(sleepTimerRunnable);
            sleepTimerRunnable = null;
        }
        if (sleepTimerNetRunnable != null) {
            mainHandler.removeCallbacks(sleepTimerNetRunnable);
            sleepTimerNetRunnable = null;
        }
        sleepTimerEndMs = 0;
        sleepTimerNetRemainingMs = 0;
        sleepTimerNetMode = false;
        sleepTimerStopAfterCurrent = false;
    }

    /**
     * Check if a sleep timer is active.
     */
    /**
     * 「放完当前曲再停」是否已到点、正在等这一首结束。
     *
     * <p>界面用它显示「本曲结束后停止」这类状态文字；{@link #isSleepTimerActive()} 也已把
     * 这种状态算作生效。</p>
     */
    public boolean isSleepTimerStopAfterCurrentPending() {
        return sleepTimerStopAfterCurrent;
    }

    public boolean isSleepTimerActive() {
        if (sleepTimerStopAfterCurrent) {
            // 「放完当前曲再停」已到点、正在等这首结束，仍然算生效
            return true;
        }
        if (sleepTimerNetMode) {
            return sleepTimerNetRemainingMs > 0;
        }
        return sleepTimerEndMs > 0 && System.currentTimeMillis() < sleepTimerEndMs;
    }

    /** 定时是否已到点、正在等当前这首放完。 */
    public boolean isSleepTimerWaitingForTrackEnd() {
        return sleepTimerStopAfterCurrent;
    }

    /**
     * Get remaining milliseconds on the sleep timer.
     * @return remaining ms, or 0 if no timer is active
     */
    public long getSleepTimerRemainingMs() {
        if (sleepTimerNetMode) {
            return sleepTimerNetRemainingMs > 0 ? sleepTimerNetRemainingMs : 0;
        }
        if (sleepTimerEndMs > 0) {
            long remaining = sleepTimerEndMs - System.currentTimeMillis();
            return remaining > 0 ? remaining : 0;
        }
        return 0;
    }

    private boolean shouldExitAfterSleepTimer() {
        if (appContext == null) {
            return false;
        }
        SharedPreferences prefs = appContext.getSharedPreferences("music163_settings",
                Context.MODE_PRIVATE);
        return prefs.getBoolean("sleep_timer_exit_app", false);
    }

    // ==================== Save / Restore Playback State ====================

    /**
     * Save current song and playlist to SharedPreferences for restore on next launch.
     */
    public void savePlaybackState() {
        if (appContext == null) return;
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            Song current = getCurrentSong();
            if (current != null) {
                JSONObject songJson = new JSONObject();
                songJson.put("id", current.getId());
                songJson.put("name", current.getName());
                songJson.put("artist", current.getArtist());
                songJson.put("album", current.getAlbum());
                if (current.getSource() != null) songJson.put("source", current.getSource());
                if (current.getBvid() != null) songJson.put("bvid", current.getBvid());
                if (current.getCid() != 0) songJson.put("cid", current.getCid());
                if (current.getCoverUrl() != null && !current.getCoverUrl().isEmpty()) {
                    songJson.put("coverUrl", current.getCoverUrl());
                }
                if (current.getAlbumId() > 0) {
                    songJson.put("albumId", current.getAlbumId());
                }
                if (current.isForceLocalPlayback()) {
                    songJson.put(KEY_FORCE_LOCAL_PLAYBACK, true);
                }
                // 持久化本地文件绝对路径：重开后才能直接拿真实路径播放，
                // 否则 forceLocalPlayback=true 而 url 为空，手动拷入的本地歌
                // 经 getDownloadedMp3Path 反查会失败 -> 提示「找不到文件」。
                //
                // 判据用「url 是不是本地路径」而不是只看 forceLocalPlayback ——
                // 收藏列表里读出来的本地歌只有本地路径、没有该标记（见 playCurrent 的注释），
                // 漏掉它们重开后同样播不了。
                // 云歌的 url 是限时直链（会过期，见 playCurrent「Always fetch a fresh URL」），
                // 存了反而是坏地址，所以只存本地路径。
                if (LocalTracks.isLocalPath(current.getUrl())) {
                    songJson.put("url", current.getUrl());
                }
                editor.putString(KEY_CURRENT_SONG_JSON, songJson.toString());
            } else {
                editor.remove(KEY_CURRENT_SONG_JSON);
            }

            // Save playlist
            JSONArray playlistArr = new JSONArray();
            for (Song s : playlist) {
                JSONObject obj = new JSONObject();
                obj.put("id", s.getId());
                obj.put("name", s.getName());
                obj.put("artist", s.getArtist());
                obj.put("album", s.getAlbum());
                if (s.getSource() != null) obj.put("source", s.getSource());
                if (s.getBvid() != null) obj.put("bvid", s.getBvid());
                if (s.getCid() != 0) obj.put("cid", s.getCid());
                if (s.getCoverUrl() != null && !s.getCoverUrl().isEmpty()) {
                    obj.put("coverUrl", s.getCoverUrl());
                }
                if (s.getAlbumId() > 0) {
                    obj.put("albumId", s.getAlbumId());
                }
                if (s.isForceLocalPlayback()) {
                    obj.put(KEY_FORCE_LOCAL_PLAYBACK, true);
                }
                // 同 current：只存本地路径，云歌限时直链不存（恢复后也是过期地址）。
                // 整条 playlist 每一项都要存 —— 恢复后「当前歌」= playlist[currentIndex]，
                // 只补当前歌的话，列表里其它本地歌重开依旧找不到文件。
                if (LocalTracks.isLocalPath(s.getUrl())) {
                    obj.put("url", s.getUrl());
                }
                playlistArr.put(obj);
            }
            editor.putString(KEY_PLAYLIST_JSON, playlistArr.toString());
            editor.putInt(KEY_CURRENT_INDEX, currentIndex);

            // Save source playlist info
            editor.putLong(KEY_SOURCE_PLAYLIST_ID, sourcePlaylistId);
            editor.putString(KEY_SOURCE_PLAYLIST_NAME, sourcePlaylistName);
            editor.putInt(KEY_SOURCE_PLAYLIST_TRACK_COUNT, sourcePlaylistTrackCount);
            editor.putString(KEY_SOURCE_PLAYLIST_CREATOR, sourcePlaylistCreator);
            editor.putLong(KEY_SOURCE_PLAYLIST_CREATOR_USER_ID, sourcePlaylistCreatorUserId);
            editor.putBoolean(KEY_SOURCE_PLAYLIST_IS_LIKED, sourcePlaylistIsLiked);
            editor.putBoolean(KEY_PERSONAL_FM_MODE, personalFmMode);
            editor.putString(KEY_PERSONAL_FM_MODE_STR, personalFmModeStr);
            editor.putString(KEY_PERSONAL_FM_SUBMODE_STR, personalFmSubModeStr);

            editor.apply();
        } catch (Exception e) {
            Log.w(TAG, "Error saving playback state", e);
        }
    }

    /**
     * Restore playlist and current song from SharedPreferences.
     * Does NOT start playback - just restores state for UI display.
     * @return true if state was restored successfully
     */
    public boolean restorePlaybackState() {
        if (appContext == null) return false;
        // Don't restore if we already have a playlist loaded
        if (!playlist.isEmpty()) return false;
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String playlistJson = prefs.getString(KEY_PLAYLIST_JSON, "[]");
            int savedIndex = prefs.getInt(KEY_CURRENT_INDEX, -1);

            JSONArray arr = new JSONArray(playlistJson);
            if (arr.length() == 0) return false;

            List<Song> restoredList = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Song song = new Song(
                        obj.getLong("id"),
                        obj.optString("name", ""),
                        obj.optString("artist", ""),
                        obj.optString("album", "")
                );
                String source = obj.optString("source", null);
                if (source != null && !source.isEmpty()) {
                    song.setSource(source);
                }
                String bvid = obj.optString("bvid", null);
                if (bvid != null && !bvid.isEmpty()) {
                    song.setBvid(bvid);
                }
                long cid = obj.optLong("cid", 0);
                if (cid != 0) {
                    song.setCid(cid);
                }
                String cover = obj.optString("coverUrl", null);
                if (cover != null && !cover.isEmpty()) {
                    song.setCoverUrl(cover);
                }
                long albumId = obj.optLong("albumId", 0);
                if (albumId > 0) {
                    song.setAlbumId(albumId);
                }
                song.setForceLocalPlayback(obj.optBoolean(KEY_FORCE_LOCAL_PLAYBACK, false));
                // 恢复本地文件的绝对路径，让 playCurrent 能直接拿到真实路径
                // （forceLocalPlayback 分支与「本地路径 url」分支都吃这个 url），
                // 避免重开后「找不到文件」。
                // 只接受本地路径：云歌的 url 是限时直链，恢复了也是过期地址，
                // 必须让它回落到按 id 重新取链接。
                String restoredUrl = obj.optString("url", null);
                if (LocalTracks.isLocalPath(restoredUrl)) {
                    song.setUrl(restoredUrl);
                } else if (song.isForceLocalPlayback() || LocalTracks.isLocalId(song.getId())) {
                    // 这份状态里没有路径 —— 老版本写下的就是如此，装上新版后第一次启动
                    // 照样解析不出。用本地音乐库缓存补救：只要这首本地歌曾被扫到过或被播过，
                    // 就能按 id 把路径找回来（缓存会自己校验文件是否还在）。
                    String cachedPath = LocalLibraryCache.pathFor(song.getId());
                    if (cachedPath != null) {
                        song.setUrl(cachedPath);
                    }
                }
                restoredList.add(song);
            }

            playlist.clear();
            playlist.addAll(restoredList);
            if (savedIndex >= 0 && savedIndex < playlist.size()) {
                currentIndex = savedIndex;
            } else {
                currentIndex = 0;
            }
            invalidateShuffleOrder();

            // Restore source playlist info
            sourcePlaylistId = prefs.getLong(KEY_SOURCE_PLAYLIST_ID, -1);
            sourcePlaylistName = prefs.getString(KEY_SOURCE_PLAYLIST_NAME, null);
            sourcePlaylistTrackCount = prefs.getInt(KEY_SOURCE_PLAYLIST_TRACK_COUNT, 0);
            sourcePlaylistCreator = prefs.getString(KEY_SOURCE_PLAYLIST_CREATOR, null);
            sourcePlaylistCreatorUserId = prefs.getLong(KEY_SOURCE_PLAYLIST_CREATOR_USER_ID, 0);
            sourcePlaylistIsLiked = prefs.getBoolean(KEY_SOURCE_PLAYLIST_IS_LIKED, false);
            personalFmMode = prefs.getBoolean(KEY_PERSONAL_FM_MODE, false);
            personalFmModeStr = prefs.getString(KEY_PERSONAL_FM_MODE_STR, "DEFAULT");
            personalFmSubModeStr = prefs.getString(KEY_PERSONAL_FM_SUBMODE_STR, "");
            personalFmLoading = false;

            return true;
        } catch (Exception e) {
            Log.w(TAG, "Error restoring playback state", e);
            return false;
        }
    }

    private void clearSourcePlaylistInfo() {
        sourcePlaylistId = -1;
        sourcePlaylistName = null;
        sourcePlaylistTrackCount = 0;
        sourcePlaylistCreator = null;
        sourcePlaylistCreatorUserId = -1;
        sourcePlaylistIsLiked = false;
    }

    private void startLocalPlayback(Song song, String localPath) {
        song.setLocalQuality(DownloadManager.detectLocalQualityFromPath(localPath));
        currentlyPlayingSongId = song.getId();
        // 起播即入缓存：即使这次的播放状态没能存下 url（老版本写过的那份就没有），
        // 下次启动也能按 id 把这条路径找回来。这是「重开找不到文件」的补救路径。
        LocalLibraryCache.put(song);
        playLocalFile(localPath, song);
    }

    private void notifyLocalPlaybackMissing(Song song) {
        if (callback == null) {
            return;
        }
        String songName = song != null && !TextUtils.isEmpty(song.getName()) ? song.getName() : "当前歌曲";
        mainHandler.post(() -> callback.onError(
                songName + " 仅支持本地播放，但本地文件未找到。请在下载列表中重新下载"));
    }

    // ==================== 随机播放 ====================

    /**
     * 沿洗牌顺序前进 / 后退一首。
     *
     * <p>原实现里 {@code next()} 直接调 {@link #playNextSequential()}，随机模式只在
     * 「一首自动播完」时生效，所以点「下一首」仍然是列表顺序 —— 这就是随机播放
     * 看起来没生效的原因。现在手动切歌与自动播完都走同一条路径。</p>
     *
     * @param forward true = 下一首，false = 上一首
     */
    private void playShuffle(boolean forward) {
        if (stopForPendingSleepTimer()) return;
        if (playlist.isEmpty()) return;
        if (playlist.size() == 1) {
            playCurrent();
            return;
        }
        ensureShuffleOrder();

        int position = shuffleOrder.indexOf(currentIndex);
        if (position < 0) {
            // 当前歌曲不在洗牌表里（列表刚被整体换过），重建后再定位
            rebuildShuffleOrder();
            position = shuffleOrder.indexOf(currentIndex);
            if (position < 0) position = 0;
        }

        int target = position + (forward ? 1 : -1);
        if (target < 0) {
            target = shuffleOrder.size() - 1;
        } else if (target >= shuffleOrder.size()) {
            // 一轮放完：重新洗牌。刚播完的歌会被留在队首，于是下一首从新的第 2 位开始，
            // 保证「随机」不会退化成每一轮都固定的顺序，也不会立刻重复同一首。
            rebuildShuffleOrder();
            target = shuffleOrder.size() > 1 ? 1 : 0;
        }

        currentIndex = shuffleOrder.get(target);
        playCurrent();
    }

    /** 洗牌顺序失效、或长度与 playlist 对不上时重建。 */
    private void ensureShuffleOrder() {
        if (shuffleOrderDirty || shuffleOrder.size() != playlist.size()) {
            rebuildShuffleOrder();
        }
    }

    /** 重建洗牌顺序，并把当前歌曲挪到队首，保证「下一首」不会立刻又抽到它。 */
    private void rebuildShuffleOrder() {
        shuffleOrder.clear();
        for (int i = 0; i < playlist.size(); i++) {
            shuffleOrder.add(i);
        }
        Collections.shuffle(shuffleOrder, random);
        int position = shuffleOrder.indexOf(currentIndex);
        if (position > 0) {
            shuffleOrder.remove(position);
            shuffleOrder.add(0, currentIndex);
        }
        shuffleOrderDirty = false;
    }

    /** playlist 结构变动后调用，让下一次随机播放重新洗牌。 */
    private void invalidateShuffleOrder() {
        shuffleOrderDirty = true;
    }

    private void playNextSequential() {
        if (stopForPendingSleepTimer()) return;
        if (playlist.isEmpty()) return;
        if (personalFmMode && currentIndex == playlist.size() - 1) {
            loadMorePersonalFmAndAdvance();
            return;
        }
        // 心动模式：连最后追加的那几首都听完了 → 再取一批接着放。
        // 取不到就照常循环回歌单开头，绝不因为心动模式卡住播放。
        if (heartbeatMode && currentIndex == playlist.size() - 1) {
            if (loadMoreHeartbeatAndAdvance()) {
                return;
            }
        }
        currentIndex = (currentIndex + 1) % playlist.size();
        playCurrent();
    }

    // ==================== 心动模式 ====================

    /** 心动模式是否开启。 */
    public boolean isHeartbeatMode() {
        return heartbeatMode;
    }

    /** 只关掉心动模式、保留已追加的曲目（首批没取到时用：没有曲目可留，等于直接关）。 */
    public void setHeartbeatModeOff() {
        heartbeatMode = false;
    }

    /** 队列里已追加的心动曲目数。 */
    public int getHeartbeatCount() {
        return heartbeatIds.size();
    }

    /** 这首歌是不是心动模式追加进来的（界面据此打标记）。 */
    public boolean isHeartbeatSong(Song song) {
        return song != null && song.getId() > 0 && heartbeatIds.contains(song.getId());
    }

    /**
     * 开启心动模式：<b>不动当前队列、不打断正在播放的这首</b>，
     * 先补一批推荐到队尾，之后每播完一批自动续下一批。
     *
     * @param seed 首批推荐的种子（一般是当前正在播的这首）
     * @param onResult 回调给界面用：已追加首数；失败时 message 非空
     */
    public void startHeartbeat(Song seed, HeartbeatCallback onResult) {
        if (seed == null || seed.getId() <= 0) {
            notifyHeartbeat(onResult, 0, "这首歌无法作为起点");
            return;
        }
        heartbeatMode = true;
        fetchHeartbeatBatch(seed, false, onResult);
    }

    /**
     * 关闭心动模式并把追加过的心动曲目从队列里移除（「一键清」）。
     *
     * <p>正在播放的那首如果本身就是心动曲目，会先被移出，此时接着播当前位置的新曲目；
     * 队列被清空则停止。</p>
     *
     * @return 被移除的曲目数
     */
    public int exitHeartbeat() {
        int removed = clearHeartbeatSongs();
        heartbeatMode = false;
        return removed;
    }

    /** 只清掉已追加的心动曲目（不改开关状态）。 */
    public int clearHeartbeatSongs() {
        if (heartbeatIds.isEmpty()) {
            return 0;
        }
        boolean removedCurrent = false;
        int removed = 0;
        Song current = getCurrentSong();
        java.util.Iterator<Song> it = playlist.iterator();
        while (it.hasNext()) {
            Song s = it.next();
            if (s != null && heartbeatIds.contains(s.getId())) {
                if (current != null && s.getId() == current.getId()) {
                    removedCurrent = true;
                }
                it.remove();
                removed++;
            }
        }
        heartbeatIds.clear();
        invalidateShuffleOrder();
        if (playlist.isEmpty()) {
            currentIndex = -1;
            stop();
            return removed;
        }
        // 正在播的那首被清掉了：接着播同一位置的新曲目（末位则回退一首）
        if (removedCurrent) {
            currentIndex = Math.max(0, Math.min(currentIndex, playlist.size() - 1));
            playCurrent();
        } else if (currentIndex >= playlist.size()) {
            currentIndex = playlist.size() - 1;
        }
        savePlaybackState();
        return removed;
    }

    private void resetHeartbeat() {
        heartbeatMode = false;
        heartbeatLoading = false;
        heartbeatIds.clear();
    }

    /**
     * 取一批心动曲并追加到队尾。
     *
     * @param advance 取到之后是否立刻推进播放（队列到头自动续播时为 true）
     * @return true 表示已推进（调用方据此决定要不要 return）
     */
    private boolean loadMoreHeartbeatAndAdvance() {
        Song last = playlist.isEmpty() ? getCurrentSong()
                : playlist.get(playlist.size() - 1);
        if (last == null || heartbeatLoading) {
            return false;
        }
        fetchHeartbeatBatch(last, true, null);
        return true;
    }

    private void fetchHeartbeatBatch(Song seed, boolean advance, HeartbeatCallback onResult) {
        if (heartbeatLoading) {
            return;
        }
        heartbeatLoading = true;
        MusicApiHelper.getIntelligenceList(seed.getId(), getSourcePlaylistId(), getCookie(),
                new MusicApiHelper.SongListCallback() {
                    @Override
                    public void onResult(List<Song> songs) {
                        heartbeatLoading = false;
                        int added = 0;
                        if (songs != null) {
                            for (Song s : songs) {
                                if (s == null || s.getId() <= 0) {
                                    continue;
                                }
                                heartbeatIds.add(s.getId());
                            }
                            added = appendUniqueSongs(songs);
                        }
                        final int addedFinal = added;
                        // 打点：确认「只追加、不替换、不打断」——队列长度变化 + 当前播放不变
                        android.util.Log.i("Heartbeat", "追加心动曲 " + addedFinal + " 首，队列 "
                                + (playlist.size() - addedFinal) + " -> " + playlist.size()
                                + "，当前仍播放: "
                                + (getCurrentSong() == null ? "-" : getCurrentSong().getName()));
                        mainHandler.post(() -> {
                            if (addedFinal > 0) {
                                savePlaybackState();
                                if (onResult != null) {
                                    onResult.onAdded(addedFinal);
                                }
                            } else if (onResult != null) {
                                onResult.onAdded(0);
                            }
                            if (advance && addedFinal > 0) {
                                currentIndex = (currentIndex + 1) % playlist.size();
                                playCurrent();
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        heartbeatLoading = false;
                        if (onResult != null) {
                            onResult.onAdded(0);
                        }
                    }
                });
    }

    private void notifyHeartbeat(HeartbeatCallback cb, int added, String message) {
        if (cb != null) {
            mainHandler.post(() -> cb.onAdded(added));
        }
    }

    /** 心动模式开启/续播的结果回调。 */
    public interface HeartbeatCallback {
        /** 已追加到队尾的首数；0 表示没取到。 */
        void onAdded(int count);
    }

    private void loadMorePersonalFmAndAdvance() {
        if (personalFmLoading) return;
        String cookie = getCookie();
        personalFmLoading = true;
        MusicApiHelper.getPersonalFMWithMode(personalFmModeStr, personalFmSubModeStr, cookie, new MusicApiHelper.PersonalFMCallback() {
            @Override
            public void onResult(List<Song> songs) {
                personalFmLoading = false;
                appendUniqueSongs(songs);
                currentIndex = (currentIndex + 1) % playlist.size();
                savePlaybackState();
                playCurrent();
            }

            @Override
            public void onError(String message) {
                personalFmLoading = false;
                if (callback != null) {
                    mainHandler.post(() -> callback.onError("刷新私人漫游失败: " + message));
                }
                currentIndex = (currentIndex + 1) % playlist.size();
                playCurrent();
            }
        });
    }

    private int appendUniqueSongs(List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return 0;
        }
        HashSet<Long> existingIds = new HashSet<>();
        for (Song song : playlist) {
            existingIds.add(song.getId());
        }
        int appended = 0;
        for (Song song : songs) {
            if (song == null || existingIds.contains(song.getId())) {
                continue;
            }
            existingIds.add(song.getId());
            playlist.add(song);
            appended++;
        }
        if (appended > 0) {
            // 列表变长后原来的洗牌下标会错位
            invalidateShuffleOrder();
        }
        return appended;
    }
}
