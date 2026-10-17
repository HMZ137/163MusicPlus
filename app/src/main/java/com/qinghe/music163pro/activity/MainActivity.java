package com.qinghe.music163pro.activity;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.RelativeLayout;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.manager.FavoritesManager;
import com.qinghe.music163pro.manager.HistoryManager;
import com.qinghe.music163pro.manager.RingtoneManagerHelper;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.service.MusicPlaybackService;
import com.qinghe.music163pro.util.LyricsTint;
import com.qinghe.music163pro.util.MusicLog;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.UpdateChecker;
import com.qinghe.music163pro.util.WatchConfirmDialog;
import com.qinghe.music163pro.util.BackgroundUtil;
import com.qinghe.music163pro.util.LocalTracks;
import com.qinghe.music163pro.util.LyricSettings;
import com.qinghe.music163pro.util.PlaybackMode;
import com.qinghe.music163pro.util.UiScale;
import com.qinghe.music163pro.util.UiKit;
import com.qinghe.music163pro.util.WatchUiUtils;
import com.qinghe.music163pro.util.PinyinUtils;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements MusicPlayerManager.PlayerCallback {

    private static final String TAG = "MainActivity";
    private static final String HEART_OUTLINE = "\u2661";
    private static final String HEART_FILLED = "\u2665";
    private static final int STORAGE_PERMISSION_REQUEST = 100;
    private static final int VOLUME_INDICATOR_SIDE_MARGIN_DP = 20;
    private static final int VOLUME_INDICATOR_MIN_WIDTH_DP = 132;
    private static final int VOLUME_INDICATOR_COMPACT_BREAKPOINT_DP = 152;
    private static final int VOLUME_INDICATOR_MAX_WIDTH_DP = 208;
    private static final int VOLUME_INDICATOR_TOP_MARGIN_DP = 10;
    /** M1：音量浮层自动收起延时（浮层升级为可交互后，比原来的 1500ms 放宽）。 */
    private static final long VOLUME_DISMISS_DELAY_MS = 2500L;
    private static final int VOLUME_INDICATOR_ANIM_DURATION_MS = 160;
    private static final float VOLUME_INDICATOR_INITIAL_SCALE = 0.96f;
    private static final int SAFE_VOLUME_PERCENT = 60;
    private static final int LYRIC_MODE_FOLLOW = 0;
    private static final int LYRIC_MODE_BLOCK = 1;
    private static final String QUALITY_TIER_UNAVAILABLE = "暂无";
    private static final String QUALITY_TIER_DOWNLOADED = "已下载";

    private TextView tvSongName;
    private TextView tvArtist;
    private ImageView btnPlay;
    private ImageView btnFuncMore;
    private SeekBar seekBar;
    private View chorusMarkerDot;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private MusicPlayerManager playerManager;
    private FavoritesManager favoritesManager;
    private AudioManager audioManager;
    private final Handler seekHandler = new Handler();
    private boolean isUserSeeking = false;
    private boolean serviceStarted = false;

    // M3：原左上角歌单按钮（btnPlaylistIndicator）已移除，
    // 其能力迁到「更多功能」菜单的「播放列表」项（openCurrentPlaylistOrQueue）。

    // Functions overlay
    private FrameLayout overlayContainer;
    private Handler overlayTimerHandler;
    private Runnable overlayTimerRunnable;

    // Volume indicator
    private View volumeIndicator;
    /** M1：底部「播放模式」按钮（图标随当前模式变化）。 */
    private ImageView btnPlayMode;
    private ProgressBar volumeProgressBar;
    private TextView volumePercentView;
    private final Handler volumeHandler = new Handler();

    // OLED 防烧屏（设置页开关）：主页面「背景层 + 内容块」一起周期性轻微移位，避免长时间静止灼屏
    private View mainContentBlock;
    /**
     * 背景层：铺满全屏、**略微放大**的独立一层，插在内容之下。
     *
     * <p>为什么背景要单独一层：原先背景直接设在 {@code android.R.id.content}（整屏容器）上，
     * 一动就会把整屏挪出去露边，所以历史上**从来没有参与移位** —— 结果最静止的模糊封面背景
     * 反而成了最容易烧屏的那一层。放到独立层上并预先放大，就能在留出的余量内自由移位。</p>
     */
    private View mainBgLayer;
    private static final String TAG_BG_LAYER = "main_bg_layer";
    private final Handler oledShiftHandler = new Handler();
    private int oledShiftIndex = 0;
    /** 内容块移位轨迹（px）：先归位再四方向微移，肉眼几乎无感但足够让像素轮换。 */
    private static final int[][] OLED_SHIFT_STEPS = {{0, 0}, {9, 5}, {0, 10}, {-9, 5}};
    /**
     * 背景层移位轨迹（px）。**必须比内容块幅度大**：背景是大片静止色块，最容易烧屏。
     * 背景层预先放大 {@link #OLED_BG_SCALE} 倍，留出的余量必须大于这里的最大位移，
     * 否则移位后会露出边缘 —— 378×496 屏上 6% 余量 ≈ 横向 11px / 纵向 15px。
     */
    private static final int[][] OLED_BG_SHIFT_STEPS = {{0, 0}, {9, 8}, {0, 11}, {-9, 8}};
    /** 背景层预放大倍数：给上面的移位留余量，同时保证不会有空隙。 */
    private static final float OLED_BG_SCALE = 1.06f;
    private static final long OLED_SHIFT_INTERVAL_MS = 30_000L;
    private final Runnable oledShiftTick = new Runnable() {
        @Override
        public void run() {
            if (!isOledBurnProtectionOn() || (mainContentBlock == null && mainBgLayer == null)) {
                return;
            }
            oledShiftIndex = (oledShiftIndex + 1) % OLED_SHIFT_STEPS.length;
            if (mainContentBlock != null) {
                mainContentBlock.setTranslationX(OLED_SHIFT_STEPS[oledShiftIndex][0]);
                mainContentBlock.setTranslationY(OLED_SHIFT_STEPS[oledShiftIndex][1]);
            }
            if (mainBgLayer != null) {
                mainBgLayer.setTranslationX(OLED_BG_SHIFT_STEPS[oledShiftIndex][0]);
                mainBgLayer.setTranslationY(OLED_BG_SHIFT_STEPS[oledShiftIndex][1]);
            }
            oledShiftHandler.postDelayed(this, OLED_SHIFT_INTERVAL_MS);
        }
    };

    /**
     * 建（或复用）背景层，并挂在 {@code android.R.id.content} 的**索引 0**（所有内容之下）。
     *
     * <p>用 tag 记住它，避免 Activity 重建时反复叠加出一堆背景层。</p>
     */
    private void installBackgroundLayer() {
        View content = getWindow().getDecorView().findViewById(android.R.id.content);
        if (!(content instanceof android.view.ViewGroup)) {
            return;
        }
        android.view.ViewGroup parent = (android.view.ViewGroup) content;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (TAG_BG_LAYER.equals(child.getTag())) {
                mainBgLayer = child; // 已存在（Activity 重建），直接复用
                return;
            }
        }
        View layer = new View(this);
        layer.setTag(TAG_BG_LAYER);
        layer.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        // 略微放大：给防烧屏移位留余量，移位后也不会露出边缘
        layer.setScaleX(OLED_BG_SCALE);
        layer.setScaleY(OLED_BG_SCALE);
        parent.addView(layer, 0);
        mainBgLayer = layer;
    }

    /** 把背景画到背景层上；拿不到背景层时退回整屏容器（老行为）。 */
    private void applyMainBackground() {
        View target = mainBgLayer != null
                ? mainBgLayer
                : getWindow().getDecorView().findViewById(android.R.id.content);
        BackgroundUtil.applyBackground(this, target);
    }

    private boolean isOledBurnProtectionOn() {
        return getSharedPreferences("music163_settings", MODE_PRIVATE)
                .getBoolean("oled_burn_protection", false);
    }

    /** onResume 时调用：按开关状态启动/停止周期移位；关闭时复位到原位。 */
    private void applyOledBurnProtection() {
        oledShiftHandler.removeCallbacks(oledShiftTick);
        if (mainContentBlock == null && mainBgLayer == null) {
            return;
        }
        if (isOledBurnProtectionOn()) {
            oledShiftHandler.postDelayed(oledShiftTick, OLED_SHIFT_INTERVAL_MS);
        } else {
            if (mainContentBlock != null) {
                mainContentBlock.setTranslationX(0);
                mainContentBlock.setTranslationY(0);
            }
            if (mainBgLayer != null) {
                mainBgLayer.setTranslationX(0);
                mainBgLayer.setTranslationY(0);
            }
            oledShiftIndex = 0;
        }
    }

    // Headphone volume protection: confirmed once above the safe threshold,
    // reset when volume drops back below it.
    private boolean volumeSafeConfirmed = false;

    // Song id whose cover is currently being fetched for the background (-1 = none)
    private long coverFetchInFlightId = -1;

    // Activity-level gesture detector for swipe handling
    private GestureDetector activityGestureDetector;

    // Page indicator (player/lyrics tabs at bottom)
    private android.widget.LinearLayout pageIndicatorLayout;
    private android.widget.ImageView ivDotPlayer;
    private android.widget.ImageView ivDotLyrics;

    // Main player content view - used to slide left/right when paging lyrics
    private View mainPlayerContentView;

    // Lyrics overlay state
    private boolean lyricsOverlayShowing = false;
    // M2/M5：主界面 ViewPager2（第 0 页 = 播放页，第 1 页 = 歌词页）
    private androidx.viewpager2.widget.ViewPager2 vpMainPages;
    /** 歌词页顶栏左侧的「歌名」（与设置按钮同一行）。 */
    private TextView tvLyricsTitle;
    /** 歌词数据当前对应的歌曲 key；切歌或首次进入歌词页时据此判断要不要重新拉歌词。 */
    private String loadedLyricsSongKey;

    // 播放列表浮层：仅对「显示」做首字母排序（不动真实队列）。
    // playlistOverlayMap 把「显示位置 → 真实队列下标」映射出来，点歌 / 长按都用真实下标。
    private ScrollView playlistOverlayScroll;
    private LinearLayout playlistOverlayList;
    private java.util.List<Song> playlistOverlaySongs;
    private int[] playlistOverlayMap;
    private boolean playlistOverlaySorted = false;
    private TextView playlistOverlaySortBtn;
    /** 打开「首字母查找」页（LetterPickerActivity）的请求码。 */
    private static final int REQ_PLAYLIST_AZ = 7302;
    private final java.util.List<LyricLine> lyricLines = new java.util.ArrayList<>();
    private final java.util.List<TextView> lyricViews = new java.util.ArrayList<>();
    private int currentHighlightIndex = -1;
    private ScrollView lyricsScrollView;
    private LinearLayout lyricsContainer;
    private final Handler lyricsScrollHandler = new Handler();
    private Runnable lyricsScrollRunnable;
    private GestureDetector lyricsGestureDetector;
    private int lyricScrollMode = 0;
    private int lyricResumeIntervalMs = 3000;
    private boolean lyricsUserScrolled = false;
    private long lyricsLastUserScrollTime = 0L;
    private boolean lyricsTouchStartedInScrollView = false;
    private float lyricsTouchDownRawX = 0f;
    private float lyricsTouchDownRawY = 0f;
    private int lyricsTouchSlop = 0;
    private TextView tvLyricsSongLabel;
    private TextView tvLyricsTimeRef;
    // Translation lyrics
    private String currentTlyricText; // Raw translated LRC text for current song
    private boolean translationEnabled; // Whether translation is currently showing
    private TextView btnTranslationToggle; // Toggle button in lyrics overlay
    private final java.util.Map<Long, String> translationMap = new java.util.HashMap<>(); // timeMs -> translated text
    // Romanized (音译 / 音) lyrics
    private String currentRomalrcText; // Raw romanized LRC text for current song
    private boolean romanizationEnabled; // Whether romanization is currently showing

    // ── AI 歌词（语言学习模式）：词性着色 + 无翻译歌曲的 AI 翻译 ──
    /** 右上角「AI」按钮（与「音」「译」同一行）。 */
    private TextView btnAiToggle;
    /** 当前歌曲的 AI 分析结果（内存缓存；磁盘缓存在 AiLyricCache）。 */
    private com.qinghe.music163pro.model.AiLyricResult aiLyricResult;
    /** 本次是否启用 AI 着色（开启模式时自动启用；关闭模式时点按钮单次启用）。 */
    private boolean aiLyricActive;
    private boolean aiLyricLoading;
    private String aiLyricSongKey;
    private TextView btnRomanizationToggle; // 「音」toggle button in lyrics overlay
    private final java.util.Map<Long, String> romanizationMap = new java.util.HashMap<>(); // timeMs -> romanized text
    private long currentChorusSongId = -1L;
    private long currentChorusStartMs = -1L;
    private long currentChorusEndMs = -1L;
    private long chorusLoadingSongId = -1L;
    private boolean currentChorusLoaded = false;

    private static class LyricLine {
        long timeMs;
        String text;
        String translation; // Optional translated text
        String romanization; // Optional romanized (音译) text
        LyricLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    @Override
    /**
     * 接入全局界面缩放（根因修复）。
     *
     * <p>主页面此前直接继承 {@code AppCompatActivity}、没有走 {@code BaseWatchActivity}，
     * 于是 {@link UiScale} 的缩放对它完全不生效 —— 你把子页面放大时，主页面被排除在外，
     * 看起来就特别小。补上和 {@code BaseWatchActivity} 一样的 wrap 后，主页面也参与同一套
     * 缩放：XML 里的 dp/sp 与代码里的 {@link #dp} 都会跟着放大。</p>
     */
    // 注意：这里刻意不加 @Override。本工具链的 javac 对这条 attachBaseContext 的
    // @Override 会误报「Override 不是可重复的注释类型」，但签名与 AppCompatActivity
    // 的 attachBaseContext(Context) 完全一致（BaseWatchActivity 的同款写法可编译），
    // 运行时仍由框架多态调用到本方法，缩放照常生效。
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(UiScale.wrap(newBase));
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainPlayerContentView = findViewById(R.id.main_player_layout);
        // 背景画到「可移位的背景层」上（防烧屏要连背景一起动），见 installBackgroundLayer
        installBackgroundLayer();
        applyMainBackground();

        // Initialize file logging
        MusicLog.init(new File("/sdcard/163Music"));

        // 让相册别再收下载下来的封面：在下载根目录放 .nomedia，
        // 并清掉**已经**被收录进相册的历史封面记录。放后台线程，不拖启动。
        new Thread(this::applyMediaVisibilityFix).start();

        // 本地封面缓存自动回收（上限 20MB）：放后台线程，别拖慢启动。
        // 以前这里只增不减，实机一度涨到 95MB —— 用户反馈的「缓存动辄百 M」就是它。
        new Thread(() -> com.qinghe.music163pro.util.LocalMetadata
                .trimCoverCache(getApplicationContext())).start();

        tvSongName = findViewById(R.id.tv_song_name);
        tvArtist = findViewById(R.id.tv_artist);
        btnPlay = findViewById(R.id.btn_play);
        btnFuncMore = findViewById(R.id.btn_favorite);
        seekBar = findViewById(R.id.seek_bar);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        ImageView btnPrev = findViewById(R.id.btn_prev);
        ImageView btnNext = findViewById(R.id.btn_next);
        // M1：两个音量键（btn_vol_down / btn_vol_up）已合并为一个入口 `btn_volume`；
        // 底部中间新增「播放模式」按钮（对应参考图底部中间那个箭头，**不是分享**）。
        ImageView btnVolume = findViewById(R.id.btn_volume);
        btnPlayMode = findViewById(R.id.btn_play_mode);
        // 注意：这里**不能**立刻 updatePlayModeIcon() —— 此时 playerManager 还没初始化
        // （实测 NPE：getPlayMode() on a null object reference）。图标在 onCreate 末尾统一刷新。
        if (btnVolume != null) {
            btnVolume.setOnClickListener(v -> showVolumeIndicator());
        }
        if (btnPlayMode != null) {
            btnPlayMode.setOnClickListener(v -> {
                onFuncCyclePlayMode();
                updatePlayModeIcon();
            });
        }
        ImageView btnMore = findViewById(R.id.btn_more);
        mainContentBlock = findViewById(R.id.main_content_block);
        // 圆角屏：顶栏两端图标避让物理圆角（原 4dp 边距会落进圆角切除区被切掉）
        int corner = WatchUiUtils.cornerInset(this);
        applyCornerInset(btnMore, corner, true);
        ensureChorusMarkerDot();

        playerManager = MusicPlayerManager.getInstance();
        playerManager.setContext(this);
        favoritesManager = new FavoritesManager(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Load saved cookie
        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        String cookie = prefs.getString("cookie", "");
        playerManager.setCookie(cookie);

        // Apply keep screen on setting
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Load or generate persistent device ID
        String savedDeviceId = prefs.getString("device_id", "");
        if (savedDeviceId.isEmpty()) {
            savedDeviceId = MusicApiHelper.getDeviceId();
            prefs.edit().putString("device_id", savedDeviceId).apply();
        }
        MusicApiHelper.setDeviceId(savedDeviceId);

        // Load saved play mode
        String playModeStr = prefs.getString("play_mode", "LIST_LOOP");
        try {
            playerManager.setPlayMode(MusicPlayerManager.PlayMode.valueOf(playModeStr));
        } catch (Exception e) {
            playerManager.setPlayMode(MusicPlayerManager.PlayMode.LIST_LOOP);
        }

        // Load saved speed mode (0=音调不变, 1=音调改变但速度不变, 2=音调改变且速度改变)
        playerManager.setSpeedMode(prefs.getInt("speed_mode", 0));

        // 自绘跑马灯：不再依赖系统 ellipsize=marquee（它经一次布局就把偏移重置回行首），
        // 改由 MainActivity 每帧 scrollTo 驱动，见 setupMarquee()。
        setupMarquee();
        // 歌名用系统 marquee：时间标签固定宽度（40dp），进度刷新不再触发布局，
        // marquee 不会被重置，因此无需监听布局变化。

        btnPlay.setOnClickListener(v -> {
            if (playerManager.isPlaying()) {
                playerManager.pause();
            } else if (playerManager.getCurrentSong() != null) {
                if (playerManager.getDuration() > 0) {
                    // Song was loaded but paused, resume it
                    playerManager.resume();
                } else {
                    // Song info restored but never played, start playback
                    playerManager.playCurrent();
                }
            }
        });

        btnPrev.setOnClickListener(v -> playerManager.previous());
        btnNext.setOnClickListener(v -> playerManager.next());

        // M1：音量不再有「-」「+」两个键 —— 统一由 `btn_volume` 弹出可拖拽音量条完成，
        // 安全音量保护移到音量条里（见 applyUserVolume）。

        // Changed: "more functions" overlay instead of toggle favorite
        btnFuncMore.setOnClickListener(v -> showFunctionsOverlay());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    int duration = playerManager.getDuration();
                    if (duration > 0) {
                        tvCurrentTime.setText(formatTime((int) ((long) progress * duration / 1000)));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                isUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                int duration = playerManager.getDuration();
                if (duration > 0) {
                    int seekPos = (int) ((long) bar.getProgress() * duration / 1000);
                    playerManager.seekTo(seekPos);
                }
                isUserSeeking = false;
            }
        });

        btnMore.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, MoreActivity.class)));

        // Playlist indicator: click to open playlist detail
        // M3：左上角歌单按钮已移除；其能力由「更多功能」(⋯) 菜单的「播放列表」项承载，
        // 逻辑见 openCurrentPlaylistOrQueue()（原实现未重写，只搬了入口）。

        playerManager.setCallback(this);

        // Restore last played song (display only, no auto-play)
        if (playerManager.getCurrentSong() == null) {
            playerManager.restorePlaybackState();
        }

        updateUI();

        // M1：底部「播放模式」按钮的图标 —— 到这里 playerManager 已初始化、模式也已还原，
        // 是唯一安全的刷新点（放在前面的 findViewById 附近会 NPE，实测过）。
        updatePlayModeIcon();

        // Request storage permission for saving favorites to /sdcard/163Music/
        requestStoragePermission();

        lyricsTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        // Check for updates once per day on first launch
        checkUpdateIfNeeded();

        // Activity-level gesture detector:
        // - Right swipe: dismiss overlay if one is open; exit app on main player screen
        // - Left swipe: open lyrics overlay (only when no overlay is showing)
        activityGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float diffX = e2.getX() - e1.getX();
                float diffY = Math.abs(e2.getY() - e1.getY());

                if (Math.abs(diffX) > 80 && diffY < 200 && Math.abs(velocityX) > 200) {
                    if (diffX > 0) {
                        // 右滑：有浮层先关浮层；歌词页交给 ViewPager 返回播放页；播放页右滑退出应用
                        if (overlayContainer != null) {
                            dismissOverlay();
                        } else if (!lyricsOverlayShowing) {
                            finish();
                        }
                        return true;
                    }
                    // 左滑：交给 ViewPager2 翻页（播放页 ←→ 歌词页）。
                    // M2 起旧的「左滑打开歌词浮层」手势已移除，统一由 ViewPager2 承载。
                }
                return false;
            }
        });

        // Create persistent page indicator (player/lyrics) at the bottom
        ensurePageIndicator();

        // M2/M5：把现有播放页与新建的歌词页装进 ViewPager2
        // （第 0 页 = 播放页，第 1 页 = 歌词页；左右滑切换）
        installMainPager();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        handleLyricsOverlayTouch(event);
        if (activityGestureDetector != null) {
            activityGestureDetector.onTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    private void handleLyricsOverlayTouch(MotionEvent event) {
        if (!lyricsOverlayShowing || lyricsScrollView == null || lyricLines.isEmpty()) {
            return;
        }

        if (lyricsGestureDetector != null) {
            lyricsGestureDetector.onTouchEvent(event);
        }

        float rawX = event.getRawX();
        float rawY = event.getRawY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lyricsTouchStartedInScrollView = isPointInsideView(rawX, rawY, lyricsScrollView);
                lyricsTouchDownRawX = rawX;
                lyricsTouchDownRawY = rawY;
                break;
            case MotionEvent.ACTION_MOVE:
                if (lyricScrollMode == LYRIC_MODE_BLOCK && lyricsTouchStartedInScrollView) {
                    float diffX = Math.abs(rawX - lyricsTouchDownRawX);
                    float diffY = Math.abs(rawY - lyricsTouchDownRawY);
                    if (diffY > lyricsTouchSlop && diffY >= diffX) {
                        lyricsUserScrolled = true;
                        lyricsLastUserScrollTime = System.currentTimeMillis();
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                lyricsTouchStartedInScrollView = false;
                break;
            default:
                break;
        }
    }

    private boolean isPointInsideView(float rawX, float rawY, View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0]
                && rawX <= location[0] + view.getWidth()
                && rawY >= location[1]
                && rawY <= location[1] + view.getHeight();
    }

    private int findOverlayLyricIndexAtRawY(float rawY) {
        if (lyricsContainer == null) {
            return -1;
        }
        int nearestIndex = -1;
        float nearestDistance = Float.MAX_VALUE;
        int[] location = new int[2];
        for (int i = 0; i < lyricsContainer.getChildCount() && i < lyricLines.size(); i++) {
            View row = lyricsContainer.getChildAt(i);
            if (row == null || row.getVisibility() != View.VISIBLE) {
                continue;
            }
            row.getLocationOnScreen(location);
            float top = location[1];
            float bottom = top + row.getHeight();
            if (rawY >= top && rawY <= bottom) {
                return i;
            }
            float center = top + row.getHeight() / 2f;
            float distance = Math.abs(rawY - center);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = i;
            }
        }
        return nearestIndex;
    }

    private void scrollOverlayToLine(int index) {
        if (lyricsScrollView == null || lyricsContainer == null
                || index < 0 || index >= lyricsContainer.getChildCount()) {
            return;
        }
        View row = lyricsContainer.getChildAt(index);
        if (row == null) {
            return;
        }
        row.post(() -> {
            if (lyricsScrollView == null) {
                return;
            }
            int scrollViewHeight = lyricsScrollView.getHeight();
            int targetTop = row.getTop();
            int targetHeight = row.getHeight();
            int scrollTo = targetTop - (scrollViewHeight / 2) + (targetHeight / 2);
            lyricsScrollView.smoothScrollTo(0, Math.max(0, scrollTo));
        });
    }

    private void clearCurrentLyricHighlight() {
        if (currentHighlightIndex >= 0 && currentHighlightIndex < lyricViews.size()) {
            TextView currentView = lyricViews.get(currentHighlightIndex);
            currentView.setTextColor(0xB3FFFFFF);
            currentView.setTextSize(LyricSettings.getOriginalFontSize(this));
            rebuildAiLineText(currentView, currentHighlightIndex, false);
        }
        currentHighlightIndex = -1;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                    }, STORAGE_PERMISSION_REQUEST);
        }
    }

    private void checkUpdateIfNeeded() {
        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        String today = new java.text.SimpleDateFormat("yyyyMMdd",
                java.util.Locale.getDefault()).format(new java.util.Date());
        String lastCheck = prefs.getString("last_update_check_date", "");
        if (today.equals(lastCheck)) return;
        prefs.edit().putString("last_update_check_date", today).apply();
        UpdateChecker.checkVersionInfo(this, new UpdateChecker.VersionCheckCallback() {
            @Override
            public void onResult(UpdateChecker.VersionInfo versionInfo) {
                if (!versionInfo.isLatest()) {
                    // 只提示，不再自动跳到更新页、更不下载安装 ——
                    // 装官方新版会覆盖掉本修改版累积的所有改动。
                    Toast.makeText(MainActivity.this,
                            "发现新版本 " + versionInfo.getVersionName()
                                    + "（当前为修改版，不自动更新）",
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(String error) {
                // Silently ignore auto-check errors
            }
        });
    }

    /**
     * 打开更新页查看版本信息。
     *
     * <p>自动检查已经不再调用它（改成只 Toast 提示），保留是为了以后需要
     * 恢复「跳转更新页」时有个现成的入口。</p>
     */
    private void openUpdateActivity(String targetVersionName) {
        Intent intent = new Intent(this, UpdateActivity.class);
        if (targetVersionName != null && !targetVersionName.trim().isEmpty()) {
            intent.putExtra("target_version_name", targetVersionName.trim());
        }
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        playerManager.setCallback(this);
        // OLED 防烧屏：重新读开关（设置页改完回来立即生效）
        applyOledBurnProtection();
        // Reload settings in case they changed
        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        playerManager.setCookie(prefs.getString("cookie", ""));
        String playModeStr = prefs.getString("play_mode", "LIST_LOOP");
        try {
            playerManager.setPlayMode(MusicPlayerManager.PlayMode.valueOf(playModeStr));
        } catch (Exception e) {
            playerManager.setPlayMode(MusicPlayerManager.PlayMode.LIST_LOOP);
        }
        // Reapply keep screen on setting
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        // Reload speed mode setting
        playerManager.setSpeedMode(prefs.getInt("speed_mode", 0));
        // Reapply custom background（画到可移位的背景层上）
        applyMainBackground();
        // Keep the cover background in sync with the current song
        updateCoverBackground();
        // Preload cloud liked IDs cache so overlay shows correct favorite state
        if (prefs.getBoolean("fav_mode_cloud", false)) {
            refreshCloudLikedIds();
        }
        updateUI();
        if (playerManager.isPlaying()) {
            startSeekBarUpdate();
        }
        // Restart lyrics scroll sync if lyrics overlay is visible
        if (lyricsOverlayShowing && !lyricLines.isEmpty() && tvLyricsTimeRef != null) {
            startLyricsScrollSync(tvLyricsTimeRef);
        }
        // 自绘跑马灯计时器：前台运行
        startMarquee();
    }

    // ==================== Functions Overlay ====================

    private void showFunctionsOverlay() {
        Song song = playerManager.getCurrentSong();
        if (song == null) {
            Toast.makeText(this, "暂无歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        if (song.isBilibili()) {
            showBilibiliFunctionsOverlay(song);
            return;
        }

        // Create overlay container
        FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);

        overlayContainer = new FrameLayout(this);
        overlayContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayContainer.setBackgroundColor(0xCC000000); // Gray mask

        // Swipe right to dismiss + click to dismiss
        addSwipeToDismiss(overlayContainer);

        // Content layout - centered, scrollable.
        // 必须 MATCH_PARENT：用 WRAP_CONTENT 时 ScrollView 高度等于内容高度，
        // 在 378×496 的小屏上「播放列表」(第 5 行) 会被挤到屏幕外且完全没有滚动余量，
        // 导致点不到。MATCH_PARENT 才有滚动区间把底部项露出来。
        ScrollView scrollView = new ScrollView(this);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        scrollParams.gravity = Gravity.CENTER;
        scrollView.setLayoutParams(scrollParams);
        scrollView.setOnClickListener(v -> { /* consume click */ });

        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER);
        contentLayout.setPadding(dp(16), dp(12), dp(16), dp(12));

        // Title bar with close button on the right
        contentLayout.addView(createOverlayTitleBar("更多功能"));

        // Function grid - 2 per row
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        row1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Row 1: 收藏 + 下载
        //
        // 本地音乐（下载来的 / 自己拷进来的）用不了云端那一套：没有网易云 id 就查不到音质、
        // 评论、歌单，下载与设铃声也没有意义。所以按 localOnly 把不可用的项去掉，
        // 并把「收藏」换成只写本地库、带本地标签的「收藏本地音乐」。
        final boolean localOnly = LocalTracks.isLocalOnly(song);
        // 本地播放模式下整机都不该再出现云端动作 —— 即使当前这首是云歌
        // （比如切模式之前就排在队列里的），下载 / 评论 / 歌单 / 音质 也一样点不通。
        // 两者合并成 cloudBlocked 之后，下面原有的 if (!localOnly) 分支全部复用，
        // 不必为「模式」再写一套平行的菜单逻辑。
        final boolean localMode = PlaybackMode.isLocalOnly(this);
        final boolean cloudBlocked = localOnly || localMode;
        final long localFavId = LocalTracks.idForPath(song.getUrl());

        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        boolean isCloudMode = prefs.getBoolean("fav_mode_cloud", false);
        boolean isFav;
        if (localOnly) {
            // 本地收藏用负数 id 当键，和云端收藏互不干扰
            isFav = localFavId != 0 && favoritesManager.isFavorite(localFavId);
        } else if (isCloudMode) {
            // In cloud mode, check cached cloud liked IDs
            isFav = isCloudLiked(song.getId());
        } else {
            isFav = favoritesManager.isFavorite(song.getId());
        }
        // 本地播放模式下，云歌没有「能收藏进去的地方」—— 本地收藏库是按文件路径存键的，
        // 云歌的流地址会过期，存进去就是一条查不回来的死记录。所以这种情况直接不显示收藏，
        // 而不是让它去写一个没有意义的负数 id。
        if (localOnly || !localMode) {
            row1.addView(createFuncItem(isFav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border,
                    localOnly ? (isFav ? "取消收藏" : "收藏本地音乐") : (isFav ? "取消收藏" : "收藏"),
                    v -> {
                        if (localOnly) {
                            onFuncFavoriteLocal(song);
                        } else {
                            onFuncFavorite(song);
                        }
                    }));
        }
        if (!cloudBlocked) {
            row1.addView(createFuncItem(R.drawable.ic_get_app, "下载",
                    v -> onFuncDownload(song)));
        }
        contentLayout.addView(row1);

        // Row 2: 设为铃声 + 定时关闭
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        row2.setPadding(0, dp(4), 0, 0);
        row2.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (!cloudBlocked) {
            row2.addView(createFuncItem(R.drawable.ic_notifications, "设为铃声",
                    v -> onFuncSetRingtone(song)));
        }

        // Sleep timer - show remaining time if active, with live updates
        LinearLayout timerItem = createFuncItem(R.drawable.ic_timer,
                playerManager.isSleepTimerActive() ? "定时..." : "定时关闭",
                v -> onFuncSleepTimer());
        row2.addView(timerItem);

        // Find the label view in timerItem (second child) for live updates
        final TextView timerLabelView = (TextView) timerItem.getChildAt(1);
        if (playerManager.isSleepTimerActive()) {
            overlayTimerHandler = new Handler();
            overlayTimerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (overlayContainer == null) return;
                    long remainMs = playerManager.getSleepTimerRemainingMs();
                    if (remainMs > 0) {
                        int totalSec = (int) (remainMs / 1000);
                        int min = totalSec / 60;
                        int sec = totalSec % 60;
                        timerLabelView.setText(String.format("定时 %d:%02d", min, sec));
                        overlayTimerHandler.postDelayed(this, 1000);
                    } else {
                        timerLabelView.setText("定时关闭");
                    }
                }
            };
            overlayTimerRunnable.run();
        }
        contentLayout.addView(row2);

        // Row 3: 播放模式 + 倍速播放
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setGravity(Gravity.CENTER);
        row3.setPadding(0, dp(4), 0, 0);
        row3.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        int playModeIconRes;
        String playModeLabel;
        switch (playerManager.getPlayMode()) {
            case SINGLE_REPEAT:
                playModeIconRes = R.drawable.ic_repeat_one;
                playModeLabel = "单曲循环";
                break;
            case RANDOM:
                playModeIconRes = R.drawable.ic_shuffle;
                playModeLabel = "随机播放";
                break;
            case LIST_LOOP:
            default:
                playModeIconRes = R.drawable.ic_repeat;
                playModeLabel = "列表循环";
                break;
        }
        row3.addView(createFuncItem(playModeIconRes, playModeLabel,
                v -> onFuncCyclePlayMode()));
        float currentSpeed = playerManager.getPlaybackSpeed();
        String speedLabel = currentSpeed == 1.0f ? "倍速播放" : String.format("%.1fx", currentSpeed);
        row3.addView(createFuncItem(R.drawable.ic_speed, speedLabel,
                v -> onFuncPlaybackSpeed()));
        contentLayout.addView(row3);

        // Row 4: 音乐信息 + 评论
        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        row4.setGravity(Gravity.CENTER);
        row4.setPadding(0, dp(4), 0, 0);
        row4.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (!cloudBlocked) {
            row4.addView(createFuncItem(R.drawable.ic_info, "音乐信息",
                    v -> onFuncSongInfo(song)));
            row4.addView(createFuncItem(R.drawable.ic_comment, "评论",
                    v -> onFuncComments(song)));
            contentLayout.addView(row4);
        }

        // Row 5: 播放列表 + 添加到歌单
        LinearLayout row5 = new LinearLayout(this);
        row5.setOrientation(LinearLayout.HORIZONTAL);
        row5.setGravity(Gravity.CENTER);
        row5.setPadding(0, dp(4), 0, 0);
        row5.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // M3：「播放列表」这一项现在承载原来左上角歌单按钮的全部能力
        // （B 站视频列表 / 当前来源歌单详情 / 本地歌的当前队列），见 openCurrentPlaylistOrQueue()。
        row5.addView(createFuncItem(R.drawable.ic_queue_music, "播放列表",
                v -> openCurrentPlaylistOrQueue()));
        if (!cloudBlocked) {
            row5.addView(createFuncItem(R.drawable.ic_add_box, "添加到歌单",
                    v -> onFuncAddToPlaylist(song)));
        }
        // 心动模式：只有「正在播放某个歌单」时才出现。
        // 官方智能播放接口 /playmode/intelligence/list 以播放列表为上下文来推曲，
        // 单曲来源（搜索结果、排行、专辑里直接播）没有可用的歌单种子，推出的一串
        // 曲目和"随机听点相似的"没区别 —— 那种场景用「相似歌曲」页更合适。
        // 条件与播放列表浮层里的同名动作（showPlaylistItemActions）保持一致。
        final boolean canHeartbeat = !cloudBlocked
                && playerManager.hasSourcePlaylist()
                && playerManager.getSourcePlaylistId() > 0;
        if (canHeartbeat) {
            // 开启后入口变成「退出心动模式」——它是个模式，不该只能进不能出
            row5.addView(createFuncItem(R.drawable.ic_favorite,
                    playerManager.isHeartbeatMode() ? "退出心动模式" : "心动模式",
                    v -> onFuncHeartbeatMode(song)));
        }
        contentLayout.addView(row5);

        // Row 6: 音质
        LinearLayout row6 = new LinearLayout(this);
        row6.setOrientation(LinearLayout.HORIZONTAL);
        row6.setGravity(Gravity.CENTER);
        row6.setPadding(0, dp(4), 0, 0);
        row6.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        SharedPreferences qualityPrefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        String curQuality = getCurrentQualityLabel(song,
                qualityPrefs.getString("preferred_quality", "exhigh"));
        String qualityLabel = "音质: " + getQualityShortName(curQuality);
        if (!cloudBlocked) {
            row6.addView(createFuncItem(R.drawable.ic_audio_quality, qualityLabel,
                    v -> onFuncSelectQuality()));
        }
        row6.addView(createFuncItem(R.drawable.ic_info, "播放器信息",
                v -> onFuncPlayerInfo()));
        contentLayout.addView(row6);

        // Row 7: 查看专辑
        if (song.getId() > 0) {
            LinearLayout row7 = new LinearLayout(this);
            row7.setOrientation(LinearLayout.HORIZONTAL);
            row7.setGravity(Gravity.START);
            row7.setPadding(0, dp(4), 0, 0);
            row7.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row7.addView(createFuncItem(R.drawable.ic_album, "查看专辑",
                    v -> onFuncViewAlbum(song)));
            contentLayout.addView(row7);
        }

        // 统一重排成「每行两个」：分组拼行时（收藏/下载 一行、铃声/定时 一行…）在播放本地歌
        // 这种「云端功能被整体隐藏」的场景下会出现一行只剩一个按钮，看着歪。见 rebalanceOverlayRows()。
        rebalanceOverlayRows(contentLayout);

        scrollView.addView(contentLayout);
        overlayContainer.addView(scrollView);
        rootView.addView(overlayContainer);
    }

    /**
     * 把浮层里「一行行拼出来」的功能项统一重排成**每行两个**。
     *
     * <p>「更多功能」是按功能分组逐行构建的，但每一行里可见的项数会随模式变化
     * （播放本地歌时「下载 / 设为铃声 / 音乐信息 / 评论」等云端项被隐藏），于是出现
     * 「一行只剩一个按钮」的参差感。这里在所有行都加进 contentLayout 之后统一摊平、再两两成行，
     * 不足一行时用等宽占位补齐，保证左对齐、宽度一致。</p>
     *
     * <p>只重排「行」这一层：第 0 个子视图（标题栏）原样保留在最前面。</p>
     */
    private void rebalanceOverlayRows(LinearLayout contentLayout) {
        if (contentLayout == null || contentLayout.getChildCount() <= 1) {
            return;
        }
        java.util.List<View> keep = new java.util.ArrayList<>();
        java.util.List<View> items = new java.util.ArrayList<>();
        for (int i = 0; i < contentLayout.getChildCount(); i++) {
            View child = contentLayout.getChildAt(i);
            // 第 0 个是标题栏，不参与重排
            if (i == 0) {
                keep.add(child);
                continue;
            }
            if (child instanceof LinearLayout
                    && ((LinearLayout) child).getOrientation() == LinearLayout.HORIZONTAL) {
                LinearLayout row = (LinearLayout) child;
                int n = row.getChildCount();
                for (int j = 0; j < n; j++) {
                    items.add(row.getChildAt(j));
                }
                // 关键：先把这一行的子 View 摘下来，否则它们仍挂在旧行上，
                // 后面 addView 到新行会抛 "The specified child already has a parent"（实机崩溃过）
                row.removeAllViews();
            } else {
                keep.add(child);
            }
        }
        if (items.isEmpty()) {
            return;
        }
        contentLayout.removeAllViews();
        for (View v : keep) {
            contentLayout.addView(v);
        }
        final int perRow = 2;
        for (int i = 0; i < items.size(); i += perRow) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                rowParams.topMargin = dp(4);
            }
            row.setLayoutParams(rowParams);
            int count = 0;
            for (int j = 0; j < perRow && i + j < items.size(); j++) {
                row.addView(items.get(i + j));
                count++;
            }
            if (count == 1) {
                // 补一个等宽空占位，让单独那一项保持左对齐、不再是「居中一个大按钮」。
                // 高度写死为 1px：裸 View 若给 WRAP_CONTENT，在 AT_MOST 下会撑满可用高度，
                // 把这一行（进而整个浮层）顶高。
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, dp(1), 1));
                row.addView(spacer);
            }
            contentLayout.addView(row);
        }
    }

    private void showBilibiliFunctionsOverlay(Song song) {
        FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);

        overlayContainer = new FrameLayout(this);
        overlayContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayContainer.setBackgroundColor(0xCC000000);
        addSwipeToDismiss(overlayContainer);

        ScrollView scrollView = new ScrollView(this);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.gravity = Gravity.CENTER;
        scrollView.setLayoutParams(scrollParams);
        scrollView.setOnClickListener(v -> { });

        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER);
        contentLayout.setPadding(dp(16), dp(12), dp(16), dp(12));
        contentLayout.addView(createOverlayTitleBar("B站功能"));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        row1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row1.addView(createFuncItem(R.drawable.ic_queue_music, "视频列表",
                v -> onFuncShowBilibiliPlaylist(song)));
        row1.addView(createFuncItem(R.drawable.ic_get_app, "下载",
                v -> onFuncDownload(song)));
        contentLayout.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        row2.setPadding(0, dp(4), 0, 0);
        row2.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout timerItem = createFuncItem(R.drawable.ic_timer,
                playerManager.isSleepTimerActive() ? "定时..." : "定时关闭",
                v -> onFuncSleepTimer());
        row2.addView(timerItem);

        final TextView timerLabelView = (TextView) timerItem.getChildAt(1);
        if (playerManager.isSleepTimerActive()) {
            overlayTimerHandler = new Handler();
            overlayTimerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (overlayContainer == null) return;
                    long remainMs = playerManager.getSleepTimerRemainingMs();
                    if (remainMs > 0) {
                        int totalSec = (int) (remainMs / 1000);
                        int min = totalSec / 60;
                        int sec = totalSec % 60;
                        timerLabelView.setText(String.format("定时 %d:%02d", min, sec));
                        overlayTimerHandler.postDelayed(this, 1000);
                    } else {
                        timerLabelView.setText("定时关闭");
                    }
                }
            };
            overlayTimerRunnable.run();
        }
        int playModeIconRes;
        String playModeLabel;
        switch (playerManager.getPlayMode()) {
            case SINGLE_REPEAT:
                playModeIconRes = R.drawable.ic_repeat_one;
                playModeLabel = "单曲循环";
                break;
            case RANDOM:
                playModeIconRes = R.drawable.ic_shuffle;
                playModeLabel = "随机播放";
                break;
            case LIST_LOOP:
            default:
                playModeIconRes = R.drawable.ic_repeat;
                playModeLabel = "列表循环";
                break;
        }
        row2.addView(createFuncItem(playModeIconRes, playModeLabel,
                v -> onFuncCyclePlayMode()));
        contentLayout.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setGravity(Gravity.CENTER);
        row3.setPadding(0, dp(4), 0, 0);
        row3.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        float currentSpeed = playerManager.getPlaybackSpeed();
        String speedLabel = currentSpeed == 1.0f ? "倍速播放" : String.format("%.1fx", currentSpeed);
        row3.addView(createFuncItem(R.drawable.ic_speed, speedLabel,
                v -> onFuncPlaybackSpeed()));
        row3.addView(createFuncItem(R.drawable.ic_info, "播放器信息",
                v -> onFuncPlayerInfo()));
        contentLayout.addView(row3);

        scrollView.addView(contentLayout);
        overlayContainer.addView(scrollView);
        rootView.addView(overlayContainer);
    }

    /**
     * 居中标题 + 可选左侧操作 + 可选右侧操作（A-Z 之类）+ 关闭，供各类浮层复用。
     * 标题两侧留白取左右两集群里较宽的一侧，保证标题视觉仍居中、
     * 且文字不会伸到任一侧按钮底下被压住。
     */
    private FrameLayout createOverlayTitleBar(String titleText) {
        return createOverlayTitleBar(titleText, null, null);
    }

    /**
     * 三参版本：标题居中，leftAction 贴左（如播放列表浮层的「排序」），
     * rightAction 贴右（如「A-Z」首字母查找），最右是关闭按钮。
     */
    private FrameLayout createOverlayTitleBar(String titleText,
                                              android.view.View leftAction,
                                              android.view.View rightAction) {
        FrameLayout titleBar = new FrameLayout(this);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.bottomMargin = dp(8);
        titleBar.setLayoutParams(barParams);

        int closeSize = dp(26);
        int closeHit = dp(32);
        int closeMarginEnd = Math.max(dp(4), WatchUiUtils.cornerInset(this));

        // 右侧集群（A-Z + 关闭）占位
        int azWidth = (rightAction != null) ? dp(46) : 0;
        int azGap = (rightAction != null) ? dp(8) : 0;
        int rightClusterWidth = closeHit + azGap + azWidth;

        // 左侧集群（排序之类）占位
        int leftWidth = (leftAction != null) ? dp(46) : 0;
        int leftGap = (leftAction != null) ? dp(8) : 0;
        int leftClusterWidth = leftWidth + leftGap;

        // 标题两侧留白取左右较宽一侧 + 关闭外边距，保证不被任一侧压住。
        int sideReserve = Math.max(leftClusterWidth, rightClusterWidth)
                + closeMarginEnd + dp(4);

        // Centered title
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(sideReserve, 0, sideReserve, 0);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        titleParams.gravity = Gravity.CENTER;
        title.setLayoutParams(titleParams);
        titleBar.addView(title);

        // 左侧集群（可选）
        if (leftAction != null) {
            LinearLayout leftCluster = new LinearLayout(this);
            leftCluster.setOrientation(LinearLayout.HORIZONTAL);
            leftCluster.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            leftParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            leftParams.setMarginStart(closeMarginEnd);
            leftCluster.setLayoutParams(leftParams);
            leftAction.setClickable(true);
            leftAction.setFocusable(true);
            leftCluster.addView(leftAction);
            titleBar.addView(leftCluster);
        }

        // 右侧集群：A-Z（可选）+ 关闭，整体贴右、竖直居中。
        LinearLayout rightCluster = new LinearLayout(this);
        rightCluster.setOrientation(LinearLayout.HORIZONTAL);
        rightCluster.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams clusterParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        clusterParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        clusterParams.setMarginEnd(closeMarginEnd);
        rightCluster.setLayoutParams(clusterParams);

        if (rightAction != null) {
            rightAction.setClickable(true);
            rightAction.setFocusable(true);
            LinearLayout.LayoutParams raParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            raParams.setMarginEnd(azGap);
            rightAction.setLayoutParams(raParams);
            rightCluster.addView(rightAction);
        }

        ImageView btnClose = new ImageView(this);
        btnClose.setImageResource(R.drawable.ic_close);
        btnClose.setColorFilter(0xFFFFFFFF);
        btnClose.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(closeHit, closeHit);
        closeLp.gravity = Gravity.CENTER_VERTICAL;
        btnClose.setLayoutParams(closeLp);
        btnClose.setOnClickListener(v -> dismissOverlay());
        rightCluster.addView(btnClose);

        titleBar.addView(rightCluster);
        return titleBar;
    }

    /**
     * Add swipe-right-to-dismiss gesture to an overlay container.
     */
    private void addSwipeToDismiss(FrameLayout container) {
        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true; // Required for onFling to work
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 != null && e2 != null) {
                    float diffX = e2.getX() - e1.getX();
                    float diffY = Math.abs(e2.getY() - e1.getY());
                    if (diffX > 80 && diffY < 200 && Math.abs(velocityX) > 200) {
                        dismissOverlay();
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                dismissOverlay();
                return true;
            }
        });
        container.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private LinearLayout createFuncItem(int iconRes, String label, View.OnClickListener listener) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        item.setLayoutParams(itemParams);
        item.setClickable(true);
        item.setFocusable(true);

        ImageView iconView = new ImageView(this);
        iconView.setImageResource(iconRes);
        int iconSize = dp(24);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        iconView.setLayoutParams(iconParams);
        item.addView(iconView);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(0xFFFFFFFF);
        labelView.setTextSize(12);
        labelView.setGravity(Gravity.CENTER);
        labelView.setPadding(0, dp(4), 0, 0);
        item.addView(labelView);

        item.setOnClickListener(listener);
        return item;
    }

    private void dismissOverlay() {
        stopRingtonePreview();
        playlistOverlayScroll = null;
        playlistOverlayList = null;
        playlistOverlaySongs = null;
        playlistOverlayMap = null;
        playlistOverlaySorted = false;
        playlistOverlaySortBtn = null;
        if (overlayTimerHandler != null) {
            overlayTimerHandler.removeCallbacksAndMessages(null);
            overlayTimerHandler = null;
            overlayTimerRunnable = null;
        }
        if (overlayContainer != null) {
            FrameLayout rootView = (FrameLayout) getWindow().getDecorView()
                    .findViewById(android.R.id.content);
            rootView.removeView(overlayContainer);
            overlayContainer = null;
        }
    }

    /**
     * 「首字母查找」页（LetterPickerActivity）返回：把播放列表浮层滚到所选字母的第一首。
     * 返回的是「显示位置」，直接拿去滚即可，无需关心真实队列下标。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PLAYLIST_AZ && resultCode == RESULT_OK && data != null) {
            int target = data.getIntExtra(LetterPickerActivity.EXTRA_RESULT_INDEX, -1);
            if (target >= 0 && playlistOverlayScroll != null && playlistOverlayList != null) {
                final int t = target;
                playlistOverlayScroll.post(() -> {
                    View child = playlistOverlayList.getChildAt(t);
                    if (child != null) {
                        playlistOverlayScroll.smoothScrollTo(0, child.getTop());
                    }
                });
            }
        }
    }

    private boolean isHeadphonesConnected() {
        try {
            return audioManager.isWiredHeadsetOn() || audioManager.isBluetoothA2dpOn();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * M1：按当前播放模式刷新底部「播放模式」按钮的图标。
     *
     * <p>与浮层里那一项的图标口径保持一致（单曲循环 / 随机播放 / 列表循环）。</p>
     */
    private void updatePlayModeIcon() {
        if (btnPlayMode == null || playerManager == null) {
            return;
        }
        int icon;
        switch (playerManager.getPlayMode()) {
            case SINGLE_REPEAT:
                icon = R.drawable.ic_repeat_one;
                break;
            case RANDOM:
                icon = R.drawable.ic_shuffle;
                break;
            case LIST_LOOP:
            default:
                icon = R.drawable.ic_repeat;
                break;
        }
        btnPlayMode.setImageResource(icon);
    }

    private void adjustVolumeUp() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
        showVolumeIndicator();
    }

    /**
     * F1：表冠滚动调音量（一格一步），复用 M1 的落地函数与指示条 ——
     * 因而**继承原有的耳机安全音量保护**（超过 
     * {@link #SAFE_VOLUME_PERCENT}% 且插着耳机时仍会先确认）。
     */
    /** F1：表冠事件探针只打前 5 条（MainActivity 不继承 BaseWatchActivity，故自持一份分发）。 */
    private static int sCrownProbeLogged = 0;

    /**
     * F1：表冠旋转。Wear 上以 {@code ACTION_SCROLL} + {@code AXIS_SCROLL} 送达
     * （源为 {@code SOURCE_ROTARY_ENCODER}）；前 5 条打日志（TAG=Crown）作为
     * 「表冠到底送不送事件」的**可观测探针** —— adb 无法模拟表冠旋转，只能人工确认。
     */
    @Override
    public boolean onGenericMotionEvent(android.view.MotionEvent event) {
        if (event != null) {
            boolean rotary = event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER);
            boolean scroll = event.getAction() == android.view.MotionEvent.ACTION_SCROLL;
            float axis = event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL);
            if ((rotary || scroll) && sCrownProbeLogged < 5) {
                sCrownProbeLogged++;
                MusicLog.i("Crown", "表冠/滚动事件 #" + sCrownProbeLogged
                        + ": action=" + event.getAction()
                        + " source=0x" + Integer.toHexString(event.getSource())
                        + " rotary=" + rotary + " AXIS_SCROLL=" + axis + " 页面=MainActivity");
            }
            if ((rotary || scroll) && Math.abs(axis) > 0.01f && handleCrownScroll(axis)) {
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    /** F1：表冠调音量本体（return true = 已消费）。 */
    private boolean handleCrownScroll(float delta) {
        android.media.AudioManager am =
                (android.media.AudioManager) getSystemService(android.content.Context.AUDIO_SERVICE);
        if (am == null) {
            return false;
        }
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int target = Math.max(0, Math.min(max, cur + (delta > 0 ? 1 : -1)));
        MusicLog.i("Crown", "表冠调音量: axis=" + delta + " 音量 " + cur + " -> " + target
                + " (max=" + max + ")");
        if (target != cur) {
            applyUserVolume(target, max);   // 复用 M1：含耳机安全音量确认
        }
        showVolumeIndicator();
        return true;
    }

    private void showVolumeIndicator() {
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        boolean hasVolumeInfo = max > 0;
        int percent = hasVolumeInfo ? Math.round(current * 100f / max) : 0;
        ensureVolumeIndicator(hasVolumeInfo, current, max, percent);
        updateVolumeIndicator(hasVolumeInfo, current, max, percent);
        // M1：浮层已升级为**可交互**（拖动 / 点击改音量），故给足收起时间；
        // 拖动期间由 onStartTrackingTouch 暂停计时，不会在手指按着时消失。
        scheduleVolumeDismiss();
    }

    /** M1：延时自动收起音量浮层（拖动中不调用它）。 */
    private void scheduleVolumeDismiss() {
        volumeHandler.removeCallbacksAndMessages(null);
        volumeHandler.postDelayed(this::dismissVolumeIndicator, VOLUME_DISMISS_DELAY_MS);
    }

    /**
     * M1：用户拖动 / 点击音量条后**写入系统音量**。
     *
     * <p>保留原有耳机安全音量保护：超过 {@code SAFE_VOLUME_PERCENT} 且插着耳机、尚未确认过时，
     * 先把浮层回滚到当前系统值，再弹确认框；确认后才真正调大。</p>
     */
    private void applyUserVolume(int value, int max) {
        int target = Math.max(0, Math.min(value, Math.max(max, 1)));
        int percent = max > 0 ? Math.round(target * 100f / max) : 0;
        if (percent >= SAFE_VOLUME_PERCENT && isHeadphonesConnected() && !volumeSafeConfirmed) {
            // 回滚显示，避免"条已经拉到那么大"的错觉
            applyVolumeToSystem(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
            WatchConfirmDialog.show(this, "音量保护",
                    "当前音量已达 " + percent + "%，继续调大可能损伤听力。确定继续调大吗？",
                    () -> {
                        volumeSafeConfirmed = true;
                        applyVolumeToSystem(target);
                    },
                    WatchConfirmDialog.Options.defaultOptions());
            return;
        }
        if (percent < SAFE_VOLUME_PERCENT) {
            volumeSafeConfirmed = false;
        }
        applyVolumeToSystem(target);
    }

    /** 把音量写进系统，并把浮层刷新到最新值。 */
    private void applyVolumeToSystem(int value) {
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = Math.max(0, Math.min(value, Math.max(max, 1)));
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        } catch (Exception e) {
            com.qinghe.music163pro.util.MusicLog.w("Volume", "setStreamVolume 失败: " + e);
        }
        int percent = max > 0 ? Math.round(target * 100f / max) : 0;
        updateVolumeIndicator(max > 0, target, max, percent);
        scheduleVolumeDismiss();
    }

    private void ensureVolumeIndicator(boolean hasVolumeInfo, int current, int max, int percent) {
        if (volumeIndicator != null) {
            return;
        }

        FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);
        int availableWidth = Math.max(
                getResources().getDisplayMetrics().widthPixels - dp(VOLUME_INDICATOR_SIDE_MARGIN_DP * 2),
                dp(VOLUME_INDICATOR_MIN_WIDTH_DP));
        int popupWidth = availableWidth > dp(VOLUME_INDICATOR_COMPACT_BREAKPOINT_DP)
                ? Math.min(availableWidth, dp(VOLUME_INDICATOR_MAX_WIDTH_DP))
                : availableWidth;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_volume_indicator));
        card.setElevation(dp(6));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setClickable(false);
        card.setFocusable(false);

        // M1：由「只读进度条」升级为**可拖拽 / 可点击**的 SeekBar（拖动即改音量）。
        SeekBar progressBar = new SeekBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                0, dp(24), 1f);
        progressBar.setLayoutParams(progressParams);
        // M1b：按 音量条示例.png —— 胶囊条（白填充 / 灰底）+ 橙色告警阈值竖线；
        // 不画 thumb（参考图只有胶囊与数值），但 SeekBar 仍负责拖动 / 点击交互。
        progressBar.setProgressDrawable(new com.qinghe.music163pro.util.VolumeCapsuleDrawable(
                SAFE_VOLUME_PERCENT, dp(2)));
        progressBar.setThumb(new android.graphics.drawable.ColorDrawable(0x00000000));
        progressBar.setThumbOffset(0);
        progressBar.setPadding(dp(2), 0, dp(2), 0);
        progressBar.setMax(hasVolumeInfo ? Math.max(max, 1) : 1);
        progressBar.setProgress(hasVolumeInfo ? current : 0);
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) {
                    applyUserVolume(value, bar.getMax());
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                // 手指按着时不允许自动收起
                volumeHandler.removeCallbacksAndMessages(null);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                scheduleVolumeDismiss();
            }
        });

        // M1b：右侧大号数字（参考图为「25」）。颜色规则：默认白，**超过阈值才橙色**。
        TextView percentView = new TextView(this);
        LinearLayout.LayoutParams percentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        percentParams.leftMargin = dp(10);
        percentView.setLayoutParams(percentParams);
        percentView.setTextColor(hasVolumeInfo && percent >= SAFE_VOLUME_PERCENT
                ? 0xFFFFA726 : 0xFFFFFFFF);
        percentView.setTextSize(20);
        percentView.setTypeface(percentView.getTypeface(), android.graphics.Typeface.BOLD);
        percentView.setText(hasVolumeInfo ? String.valueOf(percent) : "--");

        card.addView(progressBar);
        card.addView(percentView);

        // 悬浮居中：原来贴顶（TOP + 10dp）在圆角表上看着像「粘在边框上」，
        // 改成屏幕正中悬浮，四周都留出余量。
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                popupWidth, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER;
        card.setLayoutParams(params);

        rootView.addView(card);
        // ⚠️ 原来这里是 `setAlpha(0f)` 起做淡入 —— 实测在本机（手表）上**浮层会永远停在透明状态**：
        // 视图树里查得到（dumpsys 里 SeekBar 由 1 变 2），但截图与 uiautomator 都看不到。
        // 改成「直接可见 + 只做缩放动画」，不再拿 alpha 当入场。
        card.setAlpha(1f);
        card.setScaleX(VOLUME_INDICATOR_INITIAL_SCALE);
        card.setScaleY(VOLUME_INDICATOR_INITIAL_SCALE);
        card.animate().scaleX(1f).scaleY(1f)
                .setDuration(VOLUME_INDICATOR_ANIM_DURATION_MS)
                .start();
        com.qinghe.music163pro.util.MusicLog.i("Volume",
                "音量浮层已加入 content: w=" + popupWidth + " hasInfo=" + hasVolumeInfo);

        volumeIndicator = card;
        volumeProgressBar = progressBar;
        volumePercentView = percentView;
    }

    private void updateVolumeIndicator(boolean hasVolumeInfo, int current, int max, int percent) {
        if (volumeProgressBar == null || volumePercentView == null) {
            return;
        }
        volumeProgressBar.setMax(hasVolumeInfo ? max : 1);
        volumeProgressBar.setProgress(hasVolumeInfo ? current : 0);
        volumePercentView.setText(hasVolumeInfo ? String.valueOf(percent) : "--");
        volumePercentView.setTextColor(hasVolumeInfo && percent >= SAFE_VOLUME_PERCENT
                ? 0xFFFFA726 : 0xFFFFFFFF);
    }

    private void dismissVolumeIndicator() {
        if (volumeIndicator == null) {
            return;
        }
        if (volumeIndicator.getParent() instanceof FrameLayout) {
            ((FrameLayout) volumeIndicator.getParent()).removeView(volumeIndicator);
        }
        volumeIndicator = null;
        volumeProgressBar = null;
        volumePercentView = null;
    }

    private void ensureChorusLoaded(Song song) {
        if (song == null) {
            clearChorusInfo();
            return;
        }
        if (song.getId() == currentChorusSongId && currentChorusLoaded) {
            updateChorusMarker(playerManager.getDuration());
            return;
        }
        if (song.getId() == chorusLoadingSongId) {
            return;
        }
        currentChorusSongId = song.getId();
        currentChorusStartMs = -1L;
        currentChorusEndMs = -1L;
        currentChorusLoaded = false;
        chorusLoadingSongId = song.getId();
        updateChorusMarker(playerManager.getDuration());
        MusicApiHelper.getSongChorus(song.getId(), playerManager.getCookie(), new MusicApiHelper.ChorusCallback() {
            @Override
            public void onResult(MusicApiHelper.ChorusInfo chorusInfo) {
                Song currentSong = playerManager.getCurrentSong();
                if (chorusLoadingSongId == song.getId()) {
                    chorusLoadingSongId = -1L;
                }
                if (currentSong == null || currentSong.getId() != song.getId()) {
                    return;
                }
                if (chorusInfo == null) {
                    currentChorusStartMs = -1L;
                    currentChorusEndMs = -1L;
                } else {
                    currentChorusStartMs = chorusInfo.startMs;
                    currentChorusEndMs = chorusInfo.endMs;
                }
                currentChorusLoaded = true;
                updateChorusMarker(playerManager.getDuration());
            }

            @Override
            public void onError(String message) {
                Song currentSong = playerManager.getCurrentSong();
                if (chorusLoadingSongId == song.getId()) {
                    chorusLoadingSongId = -1L;
                }
                if (currentSong == null || currentSong.getId() != song.getId()) {
                    return;
                }
                currentChorusStartMs = -1L;
                currentChorusEndMs = -1L;
                currentChorusLoaded = true;
                updateChorusMarker(playerManager.getDuration());
            }
        });
    }

    /**
     * 让下载目录对系统媒体库「隐身」，把封面从相册里清出去。
     *
     * <p><b>要解决的问题：</b>每首下载的歌都会在
     * {@code /sdcard/163Music/Download/<歌名>/cover.jpg} 存一份专辑封面（App 自己展示用），
     * 但 Android 媒体扫描器只认扩展名，会把这几十张方形封面当成照片收进相册，
     * 翻照片时被它们淹没。</p>
     *
     * <p><b>两步处理：</b></p>
     * <ol>
     *   <li>在下载根目录放 {@code .nomedia} —— 扫描器会跳过该目录及其整棵子树，
     *       以后新下载的封面不再被收录；</li>
     *   <li>若本次是新创建标记（说明升级前已有一批封面被收录），再<b>主动删除</b>
     *       媒体库里指向下载目录下图片的记录。之所以不偷懒只发一次目录扫描通知：
     *       各家 ROM 的扫描器对「目录路径 + .nomedia」的处理并不一致，有的只刷一层、
     *       有的干脆忽略目录参数；直接按路径前缀删记录，效果确定。</li>
     * </ol>
     *
     * <p>只做一次（靠 {@code .nomedia} 是否存在判断），后续启动直接短路。
     * 整个过程在后台线程执行。</p>
     */
    private void applyMediaVisibilityFix() {
        try {
            boolean created = DownloadManager.ensureNoMediaMarker();
            if (!created) {
                if (!DownloadManager.hasNoMediaMarker()) {
                    MusicLog.w("MediaFix", "无法创建 .nomedia，相册仍会收录封面");
                }
                return;   // 标记已存在，历史封面也早已清过，不必重复扫库
            }
            File root = DownloadManager.getDownloadRoot();
            String rootPath = root.getAbsolutePath();
            MusicLog.i("MediaFix", "已放置 .nomedia，开始清理相册里的历史封面: " + rootPath);
            int removed = purgeImagesUnder(rootPath);
            MusicLog.i("MediaFix", "媒体库中已移除下载目录下的图片记录 " + removed + " 条");
            // 兜底：再让系统重扫一次该目录（新机型能借此把缩略图缓存一并刷新）
            android.media.MediaScannerConnection.scanFile(
                    getApplicationContext(), new String[]{rootPath}, null, null);
        } catch (Throwable t) {
            MusicLog.w("MediaFix", "媒体可见性处理失败: " + t);
        }
    }

    /**
     * 删除媒体库中所有位于 {@code pathPrefix} 之下的图片记录（只动库里的索引，不删文件）。
     *
     * <p>查询走 {@code DATA LIKE} —— 它是唯一能按「路径前缀」过滤的字段。
     * API 29 起 {@code DATA} 虽然被标记废弃，但读写仍然有效（只是不再保证与
     * 真实路径一致），对这里「筛出下载目录下的图」够用；拿到的 id 再逐条 delete，
     * 失败不会抛到调用方。</p>
     *
     * @return 实际删除的记录条数
     */
    private int purgeImagesUnder(String pathPrefix) {
        int removed = 0;
        try {
            android.content.ContentResolver cr = getContentResolver();
            android.net.Uri collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {android.provider.MediaStore.Images.Media._ID,
                    android.provider.MediaStore.Images.Media.DATA};
            String selection = android.provider.MediaStore.Images.Media.DATA + " LIKE ?";
            String[] args = {pathPrefix + "/%"};
            try (android.database.Cursor cursor = cr.query(
                    collection, projection, selection, args, null)) {
                if (cursor == null) {
                    return 0;
                }
                List<Long> ids = new ArrayList<>();
                int dataIdx = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DATA);
                int idIdx = cursor.getColumnIndex(android.provider.MediaStore.Images.Media._ID);
                if (idIdx < 0) {
                    return 0;
                }
                while (cursor.moveToNext()) {
                    String data = dataIdx >= 0 ? cursor.getString(dataIdx) : null;
                    // LIKE 只保证前缀匹配，这里再核对一次，避免误删同前缀的其它目录
                    if (data != null && data.startsWith(pathPrefix + "/")) {
                        ids.add(cursor.getLong(idIdx));
                    }
                }
                for (Long id : ids) {
                    android.net.Uri uri = android.content.ContentUris.withAppendedId(collection, id);
                    try {
                        if (cr.delete(uri, null, null) > 0) {
                            removed++;
                        }
                    } catch (Throwable ignored) {
                        // 单条删不掉（不是本应用拥有的记录）就跳过，别中断整批
                    }
                }
            }
        } catch (Throwable t) {
            MusicLog.w("MediaFix", "清理媒体库记录失败: " + t);
        }
        return removed;
    }

    private void clearChorusInfo() {
        currentChorusSongId = -1L;
        currentChorusStartMs = -1L;
        currentChorusEndMs = -1L;
        chorusLoadingSongId = -1L;
        currentChorusLoaded = false;
        updateChorusMarker(0);
    }

    private void ensureChorusMarkerDot() {
        if (chorusMarkerDot != null) {
            return;
        }
        FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);
        View dot = new View(this);
        int dotSize = dp(5);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dotSize, dotSize);
        dot.setLayoutParams(params);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(ContextCompat.getColor(this, R.color.colorAccent));
        dot.setBackground(background);
        dot.setVisibility(View.GONE);
        dot.setClickable(false);
        dot.setFocusable(false);
        rootView.addView(dot);
        chorusMarkerDot = dot;
    }

    private void updateChorusMarker(int durationMs) {
        if (chorusMarkerDot == null) {
            return;
        }
        if (durationMs <= 0 || currentChorusStartMs < 0L) {
            chorusMarkerDot.setVisibility(View.GONE);
            return;
        }
        seekBar.post(() -> positionChorusMarker(durationMs));
    }

    private void positionChorusMarker(int durationMs) {
        if (chorusMarkerDot == null || seekBar == null || durationMs <= 0 || currentChorusStartMs < 0L) {
            return;
        }
        int seekWidth = seekBar.getWidth();
        int seekHeight = seekBar.getHeight();
        if (seekWidth <= 0 || seekHeight <= 0) {
            chorusMarkerDot.setVisibility(View.GONE);
            return;
        }
        FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);
        int[] rootLocation = new int[2];
        int[] seekLocation = new int[2];
        rootView.getLocationInWindow(rootLocation);
        seekBar.getLocationInWindow(seekLocation);

        float fraction = Math.min(1f, Math.max(0f, (float) currentChorusStartMs / (float) durationMs));
        float trackLeft = seekLocation[0] - rootLocation[0] + seekBar.getPaddingLeft();
        float trackRight = seekLocation[0] - rootLocation[0] + seekWidth - seekBar.getPaddingRight();
        float markerCenterX = trackLeft + (trackRight - trackLeft) * fraction;
        float markerCenterY = seekLocation[1] - rootLocation[1] + seekHeight / 2f;
        chorusMarkerDot.setX(markerCenterX - chorusMarkerDot.getWidth() / 2f);
        chorusMarkerDot.setY(markerCenterY - chorusMarkerDot.getHeight() / 2f);
        chorusMarkerDot.setVisibility(View.VISIBLE);
    }

    private boolean hasChorusRange() {
        return currentChorusStartMs >= 0L && currentChorusEndMs > currentChorusStartMs;
    }

    private int millisToFloorSeconds(long ms) {
        return (int) Math.max(0L, ms / 1000L);
    }

    private int millisToCeilSeconds(long ms) {
        return (int) Math.max(0L, (ms + 999L) / 1000L);
    }

    private boolean applySmartClipRange(int totalSec,
                                        int[] startSec,
                                        int[] endSec,
                                        SeekBar sbStart,
                                        SeekBar sbEnd,
                                        TextView tvStart,
                                        TextView tvEnd,
                                        Runnable updateDuration,
                                        @androidx.annotation.Nullable TextView tvChorusRange) {
        if (!hasChorusRange()) {
            Toast.makeText(this, "暂无高潮数据", Toast.LENGTH_SHORT).show();
            return false;
        }
        int smartStart = millisToFloorSeconds(currentChorusStartMs);
        int smartEnd = Math.min(totalSec, millisToCeilSeconds(currentChorusEndMs));
        if (smartEnd <= smartStart) {
            smartEnd = Math.min(totalSec, smartStart + 1);
        }
        if (smartEnd <= smartStart) {
            Toast.makeText(this, "高潮范围无效", Toast.LENGTH_SHORT).show();
            return false;
        }
        startSec[0] = smartStart;
        endSec[0] = smartEnd;
        sbStart.setProgress(smartStart);
        sbEnd.setProgress(smartEnd);
        tvStart.setText("起始: " + smartStart + "s");
        tvEnd.setText("结束: " + smartEnd + "s");
        if (tvChorusRange != null) {
            tvChorusRange.setText("高潮: " + smartStart + "s - " + smartEnd + "s");
        }
        updateDuration.run();
        return true;
    }

    private MaterialButton createOverlayActionButton(String text, int backgroundColor, boolean primary) {
        MaterialButton button = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setTextColor(ContextCompat.getColor(this, R.color.white));
        button.setCornerRadius(UiKit.dp(this, UiKit.DP_BUTTON_RADIUS));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinimumHeight(dp(40));
        button.setPadding(dp(10), dp(12), dp(10), dp(12));
        button.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
        if (!primary) {
            button.setStrokeWidth(0);
        }
        return button;
    }

    /**
     * 收藏 / 取消收藏一首本地音乐。
     *
     * <p>本地文件没有网易云 id，云端收藏无从谈起，所以这里**强制只写本地收藏库**，
     * 并且用「文件路径派生的负数 id」当键 —— 负数 id 本身就是「这是本地收藏」的标签，
     * 后续在收藏列表里能据此打标，也能据此挡掉「添加到歌单」这类需要网易云 id 的操作。</p>
     */
    private void onFuncFavoriteLocal(Song song) {
        String path = song.getUrl();
        long localId = LocalTracks.idForPath(path);
        if (localId == 0) {
            Toast.makeText(this, "无法收藏：拿不到文件路径", Toast.LENGTH_SHORT).show();
            dismissOverlay();
            return;
        }

        // 保存的条目跟原曲同名同姓，但 id 换成负数、url 指向本地文件，
        // 这样播放时直接走本地播放，不会再去找网易云要链接。
        Song entry = new Song(localId, song.getName(), song.getArtist(), song.getAlbum());
        entry.setUrl(path);
        entry.setForceLocalPlayback(true);

        if (favoritesManager.isFavorite(localId)) {
            favoritesManager.removeFavorite(entry);
            Toast.makeText(this, "已取消本地收藏", Toast.LENGTH_SHORT).show();
        } else {
            favoritesManager.addFavorite(entry);
            Toast.makeText(this, "已加入本地收藏", Toast.LENGTH_SHORT).show();
        }
        dismissOverlay();
    }

    private void onFuncFavorite(Song song) {
        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        boolean isCloud = prefs.getBoolean("fav_mode_cloud", false);

        if (isCloud) {
            // Cloud mode: use API to like/unlike
            boolean isCurrentlyLiked = isCloudLiked(song.getId());
            String cookie = playerManager.getCookie();
            if (cookie == null || cookie.isEmpty()) {
                Toast.makeText(this, "请先登录以使用云端收藏", Toast.LENGTH_SHORT).show();
                dismissOverlay();
                return;
            }
            MusicApiHelper.likeTrack(song.getId(), !isCurrentlyLiked, cookie,
                    new MusicApiHelper.LikeCallback() {
                @Override
                public void onResult(boolean success) {
                    if (success) {
                        // Update cache
                        if (cloudLikedIds == null) {
                            cloudLikedIds = new java.util.HashSet<>();
                        }
                        if (isCurrentlyLiked) {
                            cloudLikedIds.remove(song.getId());
                        } else {
                            cloudLikedIds.add(song.getId());
                        }
                        cloudLikedCacheTime = System.currentTimeMillis();
                        Toast.makeText(MainActivity.this,
                                isCurrentlyLiked ? "已取消云端收藏" : "已云端收藏",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "操作失败", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override
                public void onError(String message) {
                    Toast.makeText(MainActivity.this, "操作失败: " + message,
                            Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            // Local mode
            if (favoritesManager.isFavorite(song.getId())) {
                favoritesManager.removeFavorite(song);
                Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show();
            } else {
                favoritesManager.addFavorite(song);
                Toast.makeText(this, "已收藏", Toast.LENGTH_SHORT).show();
            }
        }
        dismissOverlay();
    }

    private void onFuncDownload(Song song) {
        dismissOverlay();
        if (song.isBilibili() && DownloadManager.isDownloaded(song)) {
            Toast.makeText(this, "歌曲已下载", Toast.LENGTH_SHORT).show();
            return;
        }
        if (song.isBilibili()) {
            // Bilibili doesn't have quality tiers, download directly
            String cookie = getDownloadCookieForSong(song);
            Toast.makeText(this, "开始下载...", Toast.LENGTH_SHORT).show();
            DownloadManager.downloadSong(song, cookie, new DownloadManager.DownloadCallback() {
                @Override
                public void onSuccess(String filePath) {
                    Toast.makeText(MainActivity.this, "下载完成: " + filePath, Toast.LENGTH_LONG).show();
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            // Show quality selector before downloading
            showDownloadQualityOptions(song);
        }
    }

    private void showDownloadQualityOptions(Song song) {
        showQualityOptionsInternal(false, song, selectedQuality -> {
            String cookie = getDownloadCookieForSong(song);
            Toast.makeText(this, "开始下载...", Toast.LENGTH_SHORT).show();
            DownloadManager.downloadSong(song, cookie, selectedQuality,
                    new DownloadManager.DownloadCallback() {
                @Override
                public void onSuccess(String filePath) {
                    Toast.makeText(MainActivity.this, "下载完成: " + filePath, Toast.LENGTH_LONG).show();
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void onFuncCyclePlayMode() {
        MusicPlayerManager.PlayMode current = playerManager.getPlayMode();
        MusicPlayerManager.PlayMode next;
        switch (current) {
            case LIST_LOOP:
                next = MusicPlayerManager.PlayMode.SINGLE_REPEAT;
                break;
            case SINGLE_REPEAT:
                next = MusicPlayerManager.PlayMode.RANDOM;
                break;
            case RANDOM:
            default:
                next = MusicPlayerManager.PlayMode.LIST_LOOP;
                break;
        }
        playerManager.setPlayMode(next);
        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        prefs.edit().putString("play_mode", next.name()).apply();
        dismissOverlay();
        String modeName;
        switch (next) {
            case SINGLE_REPEAT:
                modeName = "单曲循环";
                break;
            case RANDOM:
                modeName = "随机播放";
                break;
            case LIST_LOOP:
            default:
                modeName = "列表循环";
                break;
        }
        Toast.makeText(this, "播放模式: " + modeName, Toast.LENGTH_SHORT).show();
    }

    private void onFuncLyrics() {
        dismissOverlay();
        showLyricsOverlay();
    }

    private void onFuncSongInfo(Song song) {
        dismissOverlay();
        Intent intent = new Intent(this, SongInfoActivity.class);
        intent.putExtra("song_id", song.getId());
        intent.putExtra("song_name", song.getName());
        intent.putExtra("artist_name", song.getArtist());
        intent.putExtra("artist_id", 0L); // Will be extracted from wiki API
        intent.putExtra("cookie", playerManager.getCookie());
        startActivity(intent);
    }

    /**
     * 主页面浮层里的「心动模式」（只在正在播放歌单时才有这个入口）。
     * 先收浮层再生成队列 —— 生成期间会弹「正在生成心动队列…」提示，
     * 浮层压在上面会把它盖住。
     */
    private void onFuncHeartbeatMode(Song song) {
        dismissOverlay();
        startIntelligenceMode(song);
    }

    private void onFuncViewAlbum(Song song) {
        dismissOverlay();
        if (song.getAlbumId() > 0) {
            // Open album detail directly with known albumId
            Intent intent = new Intent(this, AlbumDetailActivity.class);
            intent.putExtra("album_id", song.getAlbumId());
            intent.putExtra("album_name", song.getAlbum());
            intent.putExtra("album_cover_url", song.getCoverUrl());
            startActivity(intent);
        } else {
            // No albumId stored: fetch song detail first to get albumId
            Toast.makeText(this, "正在获取专辑信息...", Toast.LENGTH_SHORT).show();
            String cookie = playerManager.getCookie();
            MusicApiHelper.getSongDetail(song.getId(), cookie, new MusicApiHelper.SongDetailCallback() {
                @Override
                public void onResult(JSONObject songDetail) {
                    JSONObject al = songDetail.optJSONObject("al");
                    if (al == null) al = songDetail.optJSONObject("album");
                    if (al != null) {
                        long albumId = al.optLong("id", 0);
                        String albumName = al.optString("name", song.getAlbum());
                        String albumCoverUrl = al.optString("picUrl", song.getCoverUrl() != null ? song.getCoverUrl() : "");
                        if (albumId > 0) {
                            song.setAlbumId(albumId);
                            Intent intent = new Intent(MainActivity.this, AlbumDetailActivity.class);
                            intent.putExtra("album_id", albumId);
                            intent.putExtra("album_name", albumName);
                            intent.putExtra("album_cover_url", albumCoverUrl);
                            startActivity(intent);
                        } else {
                            Toast.makeText(MainActivity.this, "无法获取专辑信息", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(MainActivity.this, "无法获取专辑信息", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(MainActivity.this, "获取专辑信息失败: " + message, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void onFuncComments(Song song) {
        dismissOverlay();
        Intent intent = new Intent(this, CommentActivity.class);
        intent.putExtra("song_id", song.getId());
        intent.putExtra("song_name", song.getName());
        intent.putExtra("cookie", playerManager.getCookie());
        startActivity(intent);
    }

    private void onFuncPlayerInfo() {
        dismissOverlay();
        FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);

        overlayContainer = new FrameLayout(this);
        overlayContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayContainer.setBackgroundColor(0xCC000000);
        addSwipeToDismiss(overlayContainer);

        ScrollView scrollView = new ScrollView(this);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.gravity = Gravity.CENTER;
        scrollView.setLayoutParams(scrollParams);

        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER);
        contentLayout.setPadding(dp(16), dp(12), dp(16), dp(12));
        contentLayout.addView(createOverlayTitleBar("播放器信息"));

        for (String line : playerManager.getCurrentPlayerInfoLines()) {
            TextView textView = new TextView(this);
            textView.setText(line);
            textView.setTextColor(0xFFFFFFFF);
            textView.setTextSize(12);
            textView.setPadding(dp(10), dp(8), dp(10), dp(8));
            textView.setBackgroundColor(0xFF2D2D2D);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(4);
            textView.setLayoutParams(params);
            contentLayout.addView(textView);
        }

        scrollView.addView(contentLayout);
        overlayContainer.addView(scrollView);
        rootView.addView(overlayContainer);
    }

    /**
     * Add current song to a user-created playlist (not "我喜欢的音乐").
     * Fetches user playlists, filters to only user-created (non-liked), shows picker.
     */
    private void onFuncAddToPlaylist(Song song) {
        String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在加载歌单...", Toast.LENGTH_SHORT).show();
        MusicApiHelper.getUid(cookie, new MusicApiHelper.AccountCallback() {
            @Override
            public void onResult(org.json.JSONObject uidJson) {
                long myUid = uidJson.optLong("uid", -1);
                MusicApiHelper.getUserPlaylists(cookie, new MusicApiHelper.UserPlaylistsCallback() {
                    @Override
                    public void onResult(java.util.List<com.qinghe.music163pro.model.PlaylistInfo> playlists) {
                        // Filter: only my created playlists, exclude "我喜欢的音乐"
                        java.util.List<com.qinghe.music163pro.model.PlaylistInfo> eligible = new java.util.ArrayList<>();
                        for (com.qinghe.music163pro.model.PlaylistInfo p : playlists) {
                            if (p.getUserId() == myUid && !p.isLikedPlaylist()) {
                                eligible.add(p);
                            }
                        }
                        if (eligible.isEmpty()) {
                            Toast.makeText(MainActivity.this, "没有可用的自建歌单", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showPlaylistPicker(eligible, song);
                    }
                    @Override
                    public void onError(String message) {
                        Toast.makeText(MainActivity.this, "获取歌单失败: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, "获取用户信息失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showPlaylistPicker(java.util.List<com.qinghe.music163pro.model.PlaylistInfo> playlists, Song song) {
        String[] names = new String[playlists.size()];
        for (int i = 0; i < playlists.size(); i++) {
            names[i] = playlists.get(i).getName() + " (" + playlists.get(i).getTrackCount() + "首)";
        }
        com.qinghe.music163pro.util.WatchMenuDialog.show(this, "添加到歌单", names, which -> {
                    com.qinghe.music163pro.model.PlaylistInfo selected = playlists.get(which);
                    addSongToPlaylist(song, selected);
                });
    }

    private void addSongToPlaylist(Song song, com.qinghe.music163pro.model.PlaylistInfo playlist) {
        String cookie = playerManager.getCookie();
        MusicApiHelper.playlistTracks("add", playlist.getId(), new long[]{song.getId()},
                cookie, new MusicApiHelper.PlaylistActionCallback() {
            @Override
            public void onResult(boolean success) {
                if (success) {
                    Toast.makeText(MainActivity.this,
                            "已添加到「" + playlist.getName() + "」", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "添加失败", Toast.LENGTH_SHORT).show();
                }
            }
            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, "添加失败: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== Lyrics Overlay ====================

    /**
     * Show lyrics as an inline overlay on the player screen.
     * Left-swipe on player opens this; right-swipe (via dispatchTouchEvent) closes it.
     */
    /** M2：进入歌词页（保留旧方法名兼容「功能菜单 → 歌词」等调用点）。 */
    private void showLyricsOverlay() {
        dismissOverlay();
        gotoLyricsPage();
    }

    private void gotoLyricsPage() {
        if (vpMainPages != null) {
            vpMainPages.setCurrentItem(1, true);
        }
    }

    private void gotoPlayerPage() {
        if (vpMainPages != null) {
            vpMainPages.setCurrentItem(0, false);
        }
    }

    /**
     * M2/M5：主界面装 ViewPager2。
     *
     * <p>第 0 页 = 现有播放页（{@code activity_main} 的根布局）**原样复用**，既有布局与逻辑一行不改；
     * 第 1 页 = 新歌词页（{@link #createLyricsPageView()}）。
     * 之所以用「先 setContentView、最后再把根视图搬进 pager」的方式：MainActivity 有 5500 行、
     * 初始化顺序很长，中途换 setContentView 风险大；最后一次性 re-parent 对既有初始化零影响。</p>
     */
    private void installMainPager() {
        FrameLayout contentRoot = (FrameLayout) getWindow().getDecorView()
                .findViewById(android.R.id.content);
        if (contentRoot == null) {
            return;
        }
        // ❗ content 根的第一个子 View 是「OLED 防烧屏背景层」（installBackgroundLayer 加的），
        // 不是播放页。必须按 id 找 main_player_layout，再往上找到它在 content 下的直接父/祖先整体搬走；
        // 早先误用 getChildAt(0) 会把背景层塞进 pager 当第 0 页，播放页反而留在 pager 之上，
        // 表现就是「滑到歌词页时播放页元素还叠在上面」。
        View playerPage = contentRoot.findViewById(R.id.main_player_layout);
        if (playerPage == null) {
            return;
        }
        View movedChild = playerPage;
        android.view.ViewParent parent = movedChild.getParent();
        while (parent instanceof ViewGroup && parent != contentRoot) {
            movedChild = (View) parent;
            parent = movedChild.getParent();
        }
        if (parent != contentRoot) {
            return;
        }
        contentRoot.removeView(movedChild);

        FrameLayout pagerRoot = new FrameLayout(this);
        vpMainPages = new androidx.viewpager2.widget.ViewPager2(this);
        pagerRoot.addView(vpMainPages, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // 插入位置：背景层之后、页码指示器之前（背景层要留在 pager 下面继续当背景）
        int insertIndex = 0;
        if (mainBgLayer != null && contentRoot.indexOfChild(mainBgLayer) >= 0) {
            insertIndex = contentRoot.indexOfChild(mainBgLayer) + 1;
        }
        contentRoot.addView(pagerRoot, insertIndex, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        com.qinghe.music163pro.util.MusicLog.i("Vp2", "installMainPager: content="
                + contentRoot.getChildCount() + " moved=" + movedChild.getClass().getSimpleName()
                + " insert=" + insertIndex);

        vpMainPages.setAdapter(new MainPagerAdapter(movedChild, createLyricsPageView()));
        vpMainPages.setOffscreenPageLimit(1);
        vpMainPages.registerOnPageChangeCallback(
                new androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(int position) {
                        onMainPageSelected(position);
                    }
                });
        onMainPageSelected(0);
    }

    /** M2/M5：页面切换后的收尾（页码指示器 / 歌词加载与同步 / 与浮层互斥）。 */
    private void onMainPageSelected(int position) {
        boolean lyricsPage = position == 1;
        com.qinghe.music163pro.util.MusicLog.i("Vp2", "主界面滑到 page=" + position);
        lyricsOverlayShowing = lyricsPage;
        updatePageIndicator(lyricsPage);
        if (!lyricsPage) {
            // 离开歌词页：停掉同步计时器（歌词数据保留，下次进来直接复用）
            lyricsScrollHandler.removeCallbacksAndMessages(null);
            lyricsScrollRunnable = null;
            return;
        }
        // 歌词页是全屏内容，其它浮层不应盖在上面
        if (overlayContainer != null) {
            dismissOverlay();
        }
        Song song = playerManager.getCurrentSong();
        if (song == null) {
            showNoLyricsInOverlay();
            return;
        }
        if (tvLyricsTitle != null) {
            tvLyricsTitle.setText(song.getDisplayName());
        }
        if (tvLyricsSongLabel != null) {
            tvLyricsSongLabel.setText(song.getDisplayName() + " - " + song.getArtist());
        }
        String key = songKeyOf(song);
        if (!key.equals(loadedLyricsSongKey)) {
            loadedLyricsSongKey = key;
            lyricLines.clear();
            lyricViews.clear();
            currentHighlightIndex = -1;
            lyricsScrollHandler.removeCallbacksAndMessages(null);
            lyricsScrollRunnable = null;
            loadLyricsForOverlay(song, tvLyricsTimeRef);
        } else if (!lyricLines.isEmpty() && tvLyricsTimeRef != null) {
            startLyricsScrollSync(tvLyricsTimeRef);
        }
    }

    /**
     * M2：新歌词页布局 —— 顶栏「歌名 + 设置」**同一行**；下面时间行 + 歌词滚动区。
     *
     * <p>歌词渲染链路完全复用原有实现（{@code displayLyricsInOverlay} /
     * {@code startLyricsScrollSync} / F2 着色 / 双击跳转行），只是把入口从「全屏浮层」改成
     * ViewPager 的一页，并把原先的「音 / 译 / AI / 全屏」按钮收进「设置」浮层。</p>
     */
    private View createLyricsPageView() {
        FrameLayout page = new FrameLayout(this);
        // 透明：主界面的背景（含封面/自定义图）由 content 根布局统一绘制，歌词页沿用同一背景
        page.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        page.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ── 顶栏：左 = 歌名，右 = 设置按钮（同一行） ──
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(14), dp(4), dp(4), dp(2));

        tvLyricsTitle = new TextView(this);
        tvLyricsTitle.setTextColor(LyricsTint.textColor(0xFF));   // F2
        tvLyricsTitle.setTextSize(16);
        tvLyricsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvLyricsTitle.setSingleLine(true);
        tvLyricsTitle.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        tvLyricsTitle.setSelected(true);
        titleRow.addView(tvLyricsTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ImageView btnLyricSettings = new android.widget.ImageView(this);
        btnLyricSettings.setImageResource(R.drawable.ic_settings);
        btnLyricSettings.setColorFilter(UiKit.COLOR_ACCENT);
        btnLyricSettings.setPadding(dp(8), dp(8), dp(8), dp(8));
        btnLyricSettings.setClickable(true);
        btnLyricSettings.setFocusable(true);
        btnLyricSettings.setContentDescription("歌词设置");
        btnLyricSettings.setOnClickListener(v -> showLyricSettingsMenu());
        titleRow.addView(btnLyricSettings, new LinearLayout.LayoutParams(dp(40), dp(40)));
        column.addView(titleRow);

        // ── 时间行（小字，左对齐）：保留原有「播放进度」信息 ──
        tvLyricsTimeRef = new TextView(this);
        tvLyricsTimeRef.setTextColor(LyricsTint.textColor(0x80));   // F2
        tvLyricsTimeRef.setTextSize(10);
        tvLyricsTimeRef.setGravity(Gravity.START);
        tvLyricsTimeRef.setPadding(dp(16), 0, dp(8), dp(2));
        tvLyricsTimeRef.setSingleLine(true);
        tvLyricsTimeRef.setEllipsize(android.text.TextUtils.TruncateAt.END);
        column.addView(tvLyricsTimeRef);

        // ── 歌词滚动区 ──
        lyricsScrollView = new ScrollView(this);
        lyricsScrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        lyricsScrollView.setFadingEdgeLength(dp(20));
        lyricsScrollView.setVerticalFadingEdgeEnabled(true);
        lyricsScrollView.setScrollBarSize(0);

        lyricsContainer = new LinearLayout(this);
        lyricsContainer.setOrientation(LinearLayout.VERTICAL);
        lyricsContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        lyricsContainer.setPadding(dp(12), dp(40), dp(12), dp(56));
        lyricsScrollView.addView(lyricsContainer);
        column.addView(lyricsScrollView);

        // 双击歌词行跳转（沿用原歌词浮层的交互）
        lyricsGestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (lyricScrollMode != LYRIC_MODE_BLOCK
                                || !isPointInsideView(e.getRawX(), e.getRawY(), lyricsScrollView)) {
                            return false;
                        }
                        int index = findOverlayLyricIndexAtRawY(e.getRawY());
                        if (index < 0 || index >= lyricLines.size()) {
                            return false;
                        }
                        int seekMs = LyricSettings.seekPositionForLine(MainActivity.this,
                                lyricLines.get(index).timeMs);
                        playerManager.seekTo(seekMs);
                        lyricsUserScrolled = false;
                        lyricsLastUserScrollTime = 0L;
                        clearCurrentLyricHighlight();
                        scrollOverlayToLine(index);
                        return true;
                    }
                });
        return page;
    }

    /**
     * M2：歌词页「设置」浮层 —— AI 着色 / 音译 / 翻译 三个开关，双向绑定 {@link LyricSettings}
     * 既有键（AI 走当前歌曲的 AI 歌词激活态）；按设置项保留「全屏歌词」入口。
     * 每点一项立即生效，并重新打开浮层以刷新开/关状态。
     */
    private void showLyricSettingsMenu() {
        final boolean aiOn = aiLyricActive;
        final boolean romaOn = LyricSettings.isRomanizationEnabled(this);
        final boolean transOn = LyricSettings.isTranslationEnabled(this);
        final boolean fullscreen = LyricSettings.isFullscreenButtonEnabled(this);
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add("AI 着色：" + (aiOn ? "开" : "关"));
        items.add("音译：" + (romaOn ? "开" : "关"));
        items.add("翻译：" + (transOn ? "开" : "关"));
        if (fullscreen) {
            items.add("全屏歌词");
        }
        final String[] entries = items.toArray(new String[0]);
        com.qinghe.music163pro.util.WatchMenuDialog.show(this, "歌词设置", entries, which -> {
            if (which == 0) {
                onAiToggleClicked();
            } else if (which == 1) {
                toggleLyricsRomanization();
            } else if (which == 2) {
                toggleLyricsTranslation();
            } else if (fullscreen && which == 3) {
                startActivity(new Intent(this, LyricsActivity.class));
            }
            // 开关状态变化后重新弹出，让用户看到「开 / 关」已更新
            showLyricSettingsMenu();
        });
    }

    /** ViewPager2 的固定两页适配器（复用已 inflated 的两个页面视图）。 */
    private static final class MainPagerHolder
            extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
        MainPagerHolder(View itemView) {
            super(itemView);
        }
    }

    private final class MainPagerAdapter
            extends androidx.recyclerview.widget.RecyclerView.Adapter<MainPagerHolder> {
        private final View playerPage;
        private final View lyricsPage;

        MainPagerAdapter(View playerPage, View lyricsPage) {
            this.playerPage = playerPage;
            this.lyricsPage = lyricsPage;
        }

        @Override
        public MainPagerHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View page = viewType == 0 ? playerPage : lyricsPage;
            // ViewPager2/RecyclerView 要求 item 的 LayoutParams 是 RecyclerView.LayoutParams
            page.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new MainPagerHolder(page);
        }

        @Override
        public void onBindViewHolder(MainPagerHolder holder, int position) {
        }

        @Override
        public int getItemCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }
    }

    private void loadLyricsForOverlay(Song song, TextView tvLyricsTime) {
        // Try local .lrc file first
        String localLrc = loadLocalLrc(song);
        if (localLrc != null && !localLrc.isEmpty()) {
            // 本地下载时会把翻译 / 音译一起存下来，这里一并用上
            applyLoadedLyrics(localLrc, loadLocalTlyric(song), loadLocalRomalrc(song), tvLyricsTime);
            return;
        }

        // Fetch from API (with translation)
        if (song.getId() <= 0) {
            showNoLyricsInOverlay();
            return;
        }

        // Show loading
        lyricsContainer.removeAllViews();
        TextView tvLoading = new TextView(this);
        tvLoading.setText("加载歌词中...");
        tvLoading.setTextColor(LyricsTint.textColor(0xB3));   // F2
        tvLoading.setTextSize(13);
        tvLoading.setGravity(Gravity.CENTER);
        lyricsContainer.addView(tvLoading);

        String cookie = playerManager.getCookie();
        MusicApiHelper.getLyricsWithTranslation(song.getId(), cookie, new MusicApiHelper.LyricsFullCallback() {
            @Override
            public void onResult(String lrcText, String tlyricText, String romalrcText) {
                if (lrcText == null || lrcText.isEmpty()) {
                    showNoLyricsInOverlay();
                    return;
                }
                applyLoadedLyrics(lrcText, tlyricText, romalrcText, tvLyricsTime);
            }

            @Override
            public void onError(String message) {
                showNoLyricsInOverlay();
            }
        });
    }

    /**
     * 歌词装载的统一收尾：解析原文 / 翻译 / 音译，决定两个开关的显隐，然后渲染并开始同步。
     *
     * <p>本地 .lrc 与接口返回都走这里。之前两条路径各写一份，结果本地分支漏了
     * 「没有翻译时隐藏『译』按钮」这一步，导致没翻译的歌也会显示一个点了没反应的按钮。</p>
     */
    private void applyLoadedLyrics(String lrcText, String tlyricText, String romalrcText,
                                   TextView tvLyricsTime) {
        currentTlyricText = tlyricText;
        currentRomalrcText = romalrcText;
        parseLrc(lrcText);

        boolean hasTranslation = tlyricText != null && !tlyricText.isEmpty();
        if (hasTranslation) {
            parseTranslationLrc(tlyricText);
        } else {
            translationMap.clear();
        }

        boolean hasRomanization = romalrcText != null && !romalrcText.isEmpty();
        if (hasRomanization) {
            parseRomanizationLrc(romalrcText);
        } else {
            romanizationMap.clear();
        }

        applyTranslationsToLyrics();

        // AI 歌词：装载完成后按开关/缓存准备数据（不在这里发请求，除非总开关开着）
        prepareAiLyrics();

        if (btnTranslationToggle != null) {
            btnTranslationToggle.setVisibility(hasTranslation ? View.VISIBLE : View.GONE);
        }
        if (btnRomanizationToggle != null) {
            // 没有音译歌词就不显示（保持原设计：按钮只在该歌确实有时才出现）
            btnRomanizationToggle.setVisibility(hasRomanization ? View.VISIBLE : View.GONE);
        }

        displayLyricsInOverlay();
        startLyricsScrollSync(tvLyricsTime);
    }

    private String loadLocalLrc(Song song) {
        try {
            String safeName = song.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
            String safeArtist = song.getArtist().replaceAll("[\\\\/:*?\"<>|]", "_");
            String folderName = safeName + " - " + safeArtist;
            java.io.File lrcFile = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "163Music/Download/" + folderName + "/lyrics.lrc"
            );
            if (!lrcFile.exists()) return null;

            try (java.io.FileInputStream fis = new java.io.FileInputStream(lrcFile);
                 java.io.InputStreamReader reader = new java.io.InputStreamReader(fis, "UTF-8")) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[1024];
                int len;
                while ((len = reader.read(buf)) != -1) {
                    sb.append(buf, 0, len);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Load translated lyrics from local download folder (tlyrics.lrc).
     */
    private String loadLocalTlyric(Song song) {
        return readLocalLyricFile(song, "tlyrics.lrc");
    }

    /** 本地音译歌词（下载时一并保存，见 DownloadManager.downloadLyrics）。 */
    private String loadLocalRomalrc(Song song) {
        return readLocalLyricFile(song, "romalrc.lrc");
    }

    /**
     * 读取下载目录里的某个歌词文件（lyrics.lrc / tlyrics.lrc / romalrc.lrc）。
     */
    private String readLocalLyricFile(Song song, String fileName) {
        try {
            String safeName = song.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
            String safeArtist = song.getArtist().replaceAll("[\\\\/:*?\"<>|]", "_");
            String folderName = safeName + " - " + safeArtist;
            java.io.File lyricFile = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "163Music/Download/" + folderName + "/" + fileName
            );
            if (!lyricFile.exists()) return null;

            try (java.io.FileInputStream fis = new java.io.FileInputStream(lyricFile);
                 java.io.InputStreamReader reader = new java.io.InputStreamReader(fis, "UTF-8")) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[1024];
                int len;
                while ((len = reader.read(buf)) != -1) {
                    sb.append(buf, 0, len);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern LRC_PATTERN =
            java.util.regex.Pattern.compile("\\[(\\d{1,3}):(\\d{2})\\.?(\\d{0,3})\\](.*)");

    private void parseLrc(String lrcText) {
        lyricLines.clear();
        translationMap.clear();
        String[] lines = lrcText.split("\n");
        for (String line : lines) {
            java.util.regex.Matcher matcher = LRC_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                int min = Integer.parseInt(matcher.group(1));
                int sec = Integer.parseInt(matcher.group(2));
                String msStr = matcher.group(3);
                int ms = 0;
                if (msStr != null && !msStr.isEmpty()) {
                    int parsed = Integer.parseInt(msStr.substring(0, Math.min(msStr.length(), 3)));
                    if (msStr.length() == 1) ms = parsed * 100;
                    else if (msStr.length() == 2) ms = parsed * 10;
                    else ms = parsed;
                }
                long timeMs = (long) min * 60 * 1000 + (long) sec * 1000 + ms;
                String text = matcher.group(4).trim();
                if (!text.isEmpty()) {
                    lyricLines.add(new LyricLine(timeMs, text));
                }
            }
        }
    }

    /**
     * Parse translated lyrics LRC into the translationMap (timeMs -> translated text).
     */
    private void parseTranslationLrc(String tlyricText) {
        parseTimedLrcInto(tlyricText, translationMap);
    }

    /**
     * Parse romanized (音译) lyrics LRC into the romanizationMap (timeMs -> romanized text).
     */
    private void parseRomanizationLrc(String romalrcText) {
        parseTimedLrcInto(romalrcText, romanizationMap);
    }

    /**
     * 把「时间戳 -> 文本」形态的 LRC（翻译 / 音译）解析进给定 map。
     * 两类歌词共用这一份解析，避免两套逻辑慢慢走岔。
     */
    private void parseTimedLrcInto(String lrcText, java.util.Map<Long, String> target) {
        target.clear();
        if (lrcText == null || lrcText.isEmpty()) return;
        String[] lines = lrcText.split("\n");
        for (String line : lines) {
            java.util.regex.Matcher matcher = LRC_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                int min = Integer.parseInt(matcher.group(1));
                int sec = Integer.parseInt(matcher.group(2));
                String msStr = matcher.group(3);
                int ms = 0;
                if (msStr != null && !msStr.isEmpty()) {
                    int parsed = Integer.parseInt(msStr.substring(0, Math.min(msStr.length(), 3)));
                    if (msStr.length() == 1) ms = parsed * 100;
                    else if (msStr.length() == 2) ms = parsed * 10;
                    else ms = parsed;
                }
                long timeMs = (long) min * 60 * 1000 + (long) sec * 1000 + ms;
                String text = matcher.group(4).trim();
                if (!text.isEmpty()) {
                    target.put(timeMs, text);
                }
            }
        }
    }

    /**
     * Apply translations / romanizations from the maps onto each lyricLine.
     */
    private void applyTranslationsToLyrics() {
        for (LyricLine line : lyricLines) {
            String trans = translationMap.get(line.timeMs);
            line.translation = trans; // may be null if no translation for this line
            line.romanization = romanizationMap.get(line.timeMs);
        }
    }

    /**
     * Toggle lyrics translation display on/off.
     * Saves preference persistently.
     */
    private void toggleLyricsTranslation() {
        if (translationEnabled && translationMap.isEmpty()
                && (aiLyricResult == null || aiLyricResult.lines.isEmpty())) {
            Toast.makeText(this, "这首歌没有翻译（可开 AI 生成）", Toast.LENGTH_SHORT).show();
            return;
        }
        translationEnabled = !translationEnabled;
        LyricSettings.setTranslationEnabled(this, translationEnabled);
        if (btnTranslationToggle != null) {
            applyLyricToggleStyle(btnTranslationToggle, translationEnabled ? "译✓" : "译",
                    translationEnabled);
        }
        refreshLyricsAfterToggle();
    }

    /**
     * 切换「音」（音译 / 罗马音）歌词显示。
     * 与「译」互相独立，可以同时打开 —— 日文歌会同时显示 原文 / 发音 / 翻译 三行。
     */
    private void toggleLyricsRomanization() {
        if (romanizationEnabled && romanizationMap.isEmpty()) {
            // 当前是关→开，但这首歌没有音译歌词
            Toast.makeText(this, "这首歌没有音译歌词", Toast.LENGTH_SHORT).show();
            return;
        }
        romanizationEnabled = !romanizationEnabled;
        LyricSettings.setRomanizationEnabled(this, romanizationEnabled);
        if (btnRomanizationToggle != null) {
            applyLyricToggleStyle(btnRomanizationToggle, romanizationEnabled ? "音✓" : "音",
                    romanizationEnabled);
        }
        refreshLyricsAfterToggle();
    }

    /** 开关切换后重绘歌词，并重新开始高亮同步。 */
    private void refreshLyricsAfterToggle() {
        displayLyricsInOverlay();
        if (tvLyricsTimeRef != null) {
            lyricsScrollHandler.removeCallbacksAndMessages(null);
            currentHighlightIndex = -1;
            startLyricsScrollSync(tvLyricsTimeRef);
        }
    }

    /**
     * 歌词文字的底衬：半透明黑色圆角块。
     *
     * <p>底衬打在**整行容器**上而不是每个 TextView 上，这样原文 / 音译 / 翻译三行
     * 是同一个色块，不会切成三条；也避免逐行嵌套背景导致的额外层级。</p>
     */
    /**
     * 歌词层的开关按钮（音 / 译 / AI / 全屏）统一用项目的按钮体系，和主界面保持一致：
     * <ul>
     *   <li>开启：主色实心（{@code bg_btn_accent}）+ 深色文字</li>
     *   <li>关闭：主色描边（{@code bg_btn_outline}）+ 主色文字</li>
     * </ul>
     * 圆角 10dp 与全局一致；不再自造第三种"淡色胶囊"。
     */
    private TextView makeLyricToggleButton(String text) {
        TextView btn = new TextView(this);
        btn.setTextSize(12);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(6), dp(4), dp(6), dp(4));
        btn.setClickable(true);
        btn.setFocusable(true);
        applyLyricToggleStyle(btn, text, false);
        return btn;
    }

    /**
     * 歌词层开关按钮的样式：沿用原有设计 —— <b>纯文字、无背景</b>，
     * 只用文字颜色区分开关态（开启主色 / 关闭半透明白）。
     *
     * <p>不加胶囊/描边背景的原因：带背景后按钮变宽，同一行的「播放进度」会被挤成竖排。</p>
     */
    private void applyLyricToggleStyle(TextView btn, String text, boolean on) {
        if (btn == null) {
            return;
        }
        btn.setText(text);
        btn.setBackground(null);
        btn.setTextColor(on ? UiKit.COLOR_ACCENT : LyricsTint.textColor(0x80));   // F2
    }

    private android.graphics.drawable.Drawable buildLyricBackdrop() {
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(LyricSettings.getBackdropColor());
        background.setCornerRadius(0);  // 整屏背景不参与圆角化（圆角会让四角露出下层底色）
        return background;
    }

    private void displayLyricsInOverlay() {
        if (lyricsContainer == null) return;
        lyricsContainer.removeAllViews();
        lyricViews.clear();
        currentHighlightIndex = -1;

        if (lyricLines.isEmpty()) {
            showNoLyricsInOverlay();
            return;
        }

        // 三类歌词各有一个由用户设定的字号（设置页可调）
        final int originalFontSize = LyricSettings.getOriginalFontSize(this);
        final int romanizationFontSize = LyricSettings.getRomanizationFontSize(this);
        final int translationFontSize = LyricSettings.getTranslationFontSize(this);
        // 可选的文字底衬：在浅色或花哨的自定义背景上，白字经常会看不清
        final boolean backdropEnabled = LyricSettings.isBackdropEnabled(this);
        final int backdropPadding = backdropEnabled ? dp(8) : 0;

        int aiLineIndex = 0;
        for (LyricLine line : lyricLines) {
            final com.qinghe.music163pro.model.AiLyricResult.Line aiLine =
                    (aiLyricActive && aiLyricResult != null)
                            ? aiLyricResult.lineAt(aiLineIndex) : null;
            aiLineIndex++;
            // Container for each lyric line (original + optional translation)
            LinearLayout lineLayout = new LinearLayout(this);
            lineLayout.setOrientation(LinearLayout.VERTICAL);
            lineLayout.setGravity(Gravity.CENTER_HORIZONTAL);
            // 底衬打开时左右留出内边距，否则文字会贴着色块边缘
            lineLayout.setPadding(backdropPadding, dp(9), backdropPadding, dp(9));
            if (backdropEnabled) {
                lineLayout.setBackground(buildLyricBackdrop());
            }

            // Original lyrics text（AI 开启时按词块上色）
            TextView tv = new TextView(this);
            if (aiLine != null) {
                tv.setText(com.qinghe.music163pro.util.LyricRichText.buildOriginal(
                        line.text, aiLine, LyricsTint.textColor(0xB3), originalFontSize, this));   // F2
            } else {
                tv.setText(line.text);
            }
            tv.setTextColor(LyricsTint.textColor(0xB3));   // F2
            tv.setTextSize(originalFontSize);
            tv.setGravity(Gravity.CENTER);
            lineLayout.addView(tv);
            lyricViews.add(tv);

            // 音译（音）— 紧跟原文，读法比翻译更贴近原文位置
            if (romanizationEnabled && line.romanization != null && !line.romanization.isEmpty()) {
                TextView tvRoma = new TextView(this);
                tvRoma.setText(line.romanization);
                tvRoma.setTextColor(0x99BB86FC);
                tvRoma.setTextSize(romanizationFontSize);
                tvRoma.setGravity(Gravity.CENTER);
                tvRoma.setPadding(0, dp(1), 0, 0);
                lineLayout.addView(tvRoma);
            }

            // Translation text (if available and enabled)；AI 开启且无官方翻译时用 AI 译文
            String effectiveTranslation = line.translation;
            if ((effectiveTranslation == null || effectiveTranslation.isEmpty())
                    && aiLine != null && aiLine.translation != null
                    && !aiLine.translation.isEmpty()) {
                effectiveTranslation = aiLine.translation;
            }
            if (translationEnabled && effectiveTranslation != null
                    && !effectiveTranslation.isEmpty()) {
                TextView tvTrans = new TextView(this);
                if (aiLine != null) {
                    tvTrans.setText(com.qinghe.music163pro.util.LyricRichText.buildTranslation(
                            effectiveTranslation, aiLine, 0x8AFFFFFF, translationFontSize, this));
                } else {
                    tvTrans.setText(effectiveTranslation);
                }
                tvTrans.setTextColor(LyricsTint.textColor(0x61));   // F2
                tvTrans.setTextSize(translationFontSize);
                tvTrans.setGravity(Gravity.CENTER);
                tvTrans.setPadding(0, dp(1), 0, 0);
                lineLayout.addView(tvTrans);
            }

            lyricsContainer.addView(lineLayout);
        }
    }

    // ==================== AI 歌词（语言学习模式） ====================

    /**
     * 歌词装载完成后准备 AI 数据。
     *
     * <ul>
     *   <li>总开关<b>开</b>：直接走分析流程（命中缓存则零消耗，未命中才请求）；</li>
     *   <li>总开关<b>关</b>：只读本地缓存，<b>绝不发请求</b>；用户可点「AI」按钮单次激活。</li>
     * </ul>
     */
    private void prepareAiLyrics() {
        aiLyricActive = false;
        aiLyricResult = null;
        aiLyricLoading = false;
        com.qinghe.music163pro.util.AiLyricAnalyzer.cancel();   // 切歌：丢弃上一首的结果回调
        updateAiButton();

        Song song = playerManager != null ? playerManager.getCurrentSong() : null;
        if (song == null || lyricLines.isEmpty()) {
            return;
        }
        aiLyricSongKey = songKeyOf(song);

        if (com.qinghe.music163pro.util.AiLyricSettings.isEnabled(this)) {
            aiLyricLoading = true;
            updateAiButton();
            runAiAnalysis(true);
            return;
        }

        // 关闭模式：只在后台查一次缓存（不消耗 token）
        com.qinghe.music163pro.util.MusicLog.i("AiLyric", "准备 AI 数据 songKey=" + aiLyricSongKey
                + " 缓存路径=" + com.qinghe.music163pro.util.AiLyricCache.fileFor(
                        aiLyricSongKey,
                        com.qinghe.music163pro.util.AiLyricAnalyzer.joinLyrics(plainLyricLines())));
        final String key = aiLyricSongKey;
        final String lyricsKey = com.qinghe.music163pro.util.AiLyricAnalyzer.joinLyrics(plainLyricLines());
        new Thread(() -> {
            final com.qinghe.music163pro.model.AiLyricResult cached =
                    com.qinghe.music163pro.model.AiLyricResult.fromJson(
                            com.qinghe.music163pro.util.AiLyricCache.read(key, lyricsKey));
            if (cached != null) {
                runOnUiThread(() -> {
                    aiLyricResult = cached;
                    updateAiButton();
                });
            }
        }, "ai-lyric-cache").start();
    }

    /** 发起 AI 分析（优先缓存）。 */
    private void runAiAnalysis(final boolean autoActivate) {
        final Song song = playerManager != null ? playerManager.getCurrentSong() : null;
        if (song == null) {
            aiLyricLoading = false;
            updateAiButton();
            return;
        }
        com.qinghe.music163pro.util.AiLyricAnalyzer.analyze(this, aiLyricSongKey,
                song.getName(), song.getArtist(), song.getAlbum(),
                plainLyricLines(), officialTranslationLines(),
                new com.qinghe.music163pro.util.AiLyricAnalyzer.Callback() {
                    @Override
                    public void onResult(com.qinghe.music163pro.model.AiLyricResult result,
                                         boolean fromCache) {
                        aiLyricLoading = false;
                        aiLyricResult = result;
                        if (autoActivate) {
                            aiLyricActive = true;
                        }
                        updateAiButton();
                        if (aiLyricActive) {
                            displayLyricsInOverlay();
                            reapplyCurrentHighlight();
                        }
                        if (!fromCache && autoActivate) {
                            Toast.makeText(MainActivity.this, "AI 歌词已就绪",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        aiLyricLoading = false;
                        updateAiButton();
                        if (autoActivate && com.qinghe.music163pro.util.AiLyricSettings.isEnabled(MainActivity.this)) {
                            // 总开关开着但失败：提示一次，不打扰阅读
                            Toast.makeText(MainActivity.this, "AI 歌词失败：" + message,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    /** 长按 AI：删除本曲缓存并重新分析。 */
    private void reanalyzeCurrentSong() {
        if (aiLyricLoading) {
            Toast.makeText(this, "AI 正在分析…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!com.qinghe.music163pro.util.AiLyricSettings.hasApiKey(this)) {
            Toast.makeText(this, "请先填写 DeepSeek API Key", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, LanguageLearningActivity.class));
            return;
        }
        aiLyricActive = false;
        aiLyricResult = null;
        if (aiLyricSongKey != null && !lyricLines.isEmpty()) {
            java.io.File cache = com.qinghe.music163pro.util.AiLyricCache.fileFor(
                    aiLyricSongKey,
                    com.qinghe.music163pro.util.AiLyricAnalyzer.joinLyrics(plainLyricLines()));
            if (cache != null && cache.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cache.delete();
            }
        }
        aiLyricLoading = true;
        updateAiButton();
        runAiAnalysis(true);
        Toast.makeText(this, "已清除本曲缓存，正在重新分析…", Toast.LENGTH_SHORT).show();
    }

    /** 「AI」按钮：启用 / 取消；未分析过则先分析。 */
    private void onAiToggleClicked() {
        if (aiLyricLoading) {
            Toast.makeText(this, "AI 正在分析…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (aiLyricActive) {
            aiLyricActive = false;
            updateAiButton();
            displayLyricsInOverlay();
            reapplyCurrentHighlight();
            return;
        }
        if (aiLyricResult != null) {
            aiLyricActive = true;
            updateAiButton();
            displayLyricsInOverlay();
            reapplyCurrentHighlight();
            Toast.makeText(this, "已启用 AI 歌词", Toast.LENGTH_SHORT).show();
            return;
        }
        // 没有内存结果：检查 Key → 要么去设置页填写，要么现在分析
        if (!com.qinghe.music163pro.util.AiLyricSettings.hasApiKey(this)) {
            Toast.makeText(this, "请先填写 DeepSeek API Key", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, LanguageLearningActivity.class));
            return;
        }
        aiLyricLoading = true;
        updateAiButton();
        runAiAnalysis(true);
    }

    /** 刷新「AI」按钮的三态：AI / AI… / AI✓。 */
    private void updateAiButton() {
        if (btnAiToggle == null) {
            return;
        }
        if (lyricLines.isEmpty()) {
            btnAiToggle.setVisibility(View.GONE);
            return;
        }
        btnAiToggle.setVisibility(View.VISIBLE);
        if (aiLyricLoading) {
            applyLyricToggleStyle(btnAiToggle, "AI…", false);
        } else if (aiLyricActive) {
            applyLyricToggleStyle(btnAiToggle, "AI✓", true);
        } else {
            // 未激活：统一"关闭态"描边样式（有缓存与无缓存的区别由点击行为体现，样式不额外区分）
            applyLyricToggleStyle(btnAiToggle, "AI", false);
        }
    }

    /** 缓存键：网易云的用歌曲 ID，本地歌用路径派生，B 站用 bvid。 */
    private String songKeyOf(Song song) {
        if (song == null) {
            return "unknown";
        }
        if (song.isBilibili() && song.getBvid() != null && !song.getBvid().isEmpty()) {
            return "bili_" + song.getBvid();
        }
        if (song.getId() > 0) {
            return String.valueOf(song.getId());
        }
        String url = song.getUrl();
        return "local_" + com.qinghe.music163pro.util.AiLyricCache.shortHash(
                url == null ? song.getName() : url);
    }

    private java.util.List<String> plainLyricLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (LyricLine line : lyricLines) {
            out.add(line.text == null ? "" : line.text);
        }
        return out;
    }

    private java.util.List<String> officialTranslationLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (LyricLine line : lyricLines) {
            out.add(line.translation == null ? "" : line.translation);
        }
        return out;
    }

    /**
     * 重新构建某行的词块文本（高亮切换时字号会变，span 里的字号必须跟着重建，
     * 否则会出现「同一行里词块字比普通字小」的观感）。
     */
    private void rebuildAiLineText(TextView view, int lineIndex, boolean highlighted) {
        if (!aiLyricActive || aiLyricResult == null || view == null
                || lineIndex < 0 || lineIndex >= lyricLines.size()) {
            return;
        }
        com.qinghe.music163pro.model.AiLyricResult.Line aiLine = aiLyricResult.lineAt(lineIndex);
        if (aiLine == null) {
            return;
        }
        float sizeSp = LyricSettings.getOriginalFontSize(this) + (highlighted ? 1 : 0);
        view.setText(com.qinghe.music163pro.util.LyricRichText.buildOriginal(
                lyricLines.get(lineIndex).text, aiLine,
                highlighted ? 0xFFFFFFFF : 0xB3FFFFFF, sizeSp, this));
    }

    /** 重绘歌词后恢复当前行高亮（与滚动同步里的内联逻辑保持一致）。 */
    private void reapplyCurrentHighlight() {
        if (currentHighlightIndex < 0 || currentHighlightIndex >= lyricViews.size()) {
            clearCurrentLyricHighlight();
            return;
        }
        TextView currentView = lyricViews.get(currentHighlightIndex);
        currentView.setTextColor(LyricsTint.textColor(0xFF));   // F2
        // 高亮行文字放大 1sp：词块底衬里的字要一起放大，否则会显得比旁边的字小
        currentView.setTextSize(LyricSettings.getOriginalFontSize(this) + 1);
        rebuildAiLineText(currentView, currentHighlightIndex, true);
        if (lyricScrollMode == LYRIC_MODE_FOLLOW || !lyricsUserScrolled) {
            scrollOverlayToLine(currentHighlightIndex);
        }
    }

    private void showNoLyricsInOverlay() {
        if (lyricsContainer == null) return;
        lyricsContainer.removeAllViews();
        lyricViews.clear();
        TextView tv = new TextView(this);
        tv.setText("暂无歌词");
        tv.setTextColor(LyricsTint.textColor(0xB3));   // F2
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(40), 0, 0);
        lyricsContainer.addView(tv);
    }

    private void startLyricsScrollSync(final TextView tvLyricsTime) {
        if (lyricLines.isEmpty()) return;

        lyricsScrollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!lyricsOverlayShowing || lyricsContainer == null) return;
                if (playerManager.isPlaying() || playerManager.getCurrentPosition() > 0) {
                    int currentPos = playerManager.getCurrentPosition();
                    int duration = playerManager.getDuration();

                    tvLyricsTime.setText(formatTime(currentPos) + " / " + formatTime(duration));

                    if (lyricScrollMode == LYRIC_MODE_BLOCK && lyricsUserScrolled) {
                        if (System.currentTimeMillis() - lyricsLastUserScrollTime >= lyricResumeIntervalMs) {
                            lyricsUserScrolled = false;
                        }
                    }

                    int newIndex = -1;
                    // 歌词偏移只作用在这个比较上，解析出来的时间戳保持原样 ——
                    // 否则原文 / 音译 / 翻译三份歌词之间靠时间戳对齐的映射会被破坏。
                    int lyricRefPos = LyricSettings.referencePosition(MainActivity.this, currentPos);
                    for (int i = 0; i < lyricLines.size(); i++) {
                        if (lyricLines.get(i).timeMs <= lyricRefPos) {
                            newIndex = i;
                        } else {
                            break;
                        }
                    }

                    if (newIndex != currentHighlightIndex && newIndex >= 0) {
                        clearCurrentLyricHighlight();
                        currentHighlightIndex = newIndex;
                        if (currentHighlightIndex < lyricViews.size()) {
                            TextView currentView = lyricViews.get(currentHighlightIndex);
                            currentView.setTextColor(LyricsTint.textColor(0xFF));   // F2
                            // 高亮行比常态大 1sp，跟着用户设定的原文字号走
                            currentView.setTextSize(
                                    LyricSettings.getOriginalFontSize(MainActivity.this) + 1);

                            if (lyricScrollMode == LYRIC_MODE_FOLLOW || !lyricsUserScrolled) {
                                scrollOverlayToLine(currentHighlightIndex);
                            }
                        }
                    }
                }
                lyricsScrollHandler.postDelayed(this, 300);
            }
        };
        lyricsScrollHandler.post(lyricsScrollRunnable);
    }

    private void stopLyricsScrollSync() {
        lyricsScrollHandler.removeCallbacksAndMessages(null);
        lyricsScrollRunnable = null;
        lyricsOverlayShowing = false;
        lyricLines.clear();
        lyricViews.clear();
        translationMap.clear();
        currentTlyricText = null;
        btnTranslationToggle = null;
        romanizationMap.clear();
        currentRomalrcText = null;
        btnRomanizationToggle = null;
        currentHighlightIndex = -1;
        lyricsGestureDetector = null;
        lyricsUserScrolled = false;
        lyricsLastUserScrollTime = 0L;
        lyricsTouchStartedInScrollView = false;
        lyricsScrollView = null;
        lyricsContainer = null;
        tvLyricsSongLabel = null;
        tvLyricsTimeRef = null;
    }

    // ==================== Playback Speed ====================

    private void onFuncPlaybackSpeed() {
        dismissOverlay();
        showSpeedOptions();
    }

    private void onFuncShowPlaylist() {
        dismissOverlay();
        showPlaylistOverlay();
    }

    private void onFuncShowBilibiliPlaylist(Song song) {
        dismissOverlay();
        openBilibiliPlaylist(song);
    }

    private void openBilibiliPlaylist(Song song) {
        if (song == null || !song.isBilibili()) {
            return;
        }
        Intent intent = new Intent(this, BilibiliPlaylistActivity.class);
        intent.putExtra("bvid", song.getBvid());
        intent.putExtra("video_title", song.getAlbum());
        intent.putExtra("owner_name", song.getArtist());
        startActivity(intent);
    }

    /**
     * Show the current playlist as an overlay with song list.
     * Tapping a song plays it and dismisses the overlay.
     */
    private void showPlaylistOverlay() {
        java.util.List<Song> playlist = playerManager.getPlaylist();
        if (playlist == null || playlist.isEmpty()) {
            Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show();
            return;
        }

        FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);

        overlayContainer = new FrameLayout(this);
        overlayContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayContainer.setBackgroundColor(0xCC000000);
        addSwipeToDismiss(overlayContainer);

        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(dp(8), dp(8), dp(8), dp(8));
        contentLayout.setBackgroundColor(0xFF1E1E1E);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        contentLayout.setLayoutParams(contentParams);
        contentLayout.setOnClickListener(v -> { /* consume click */ });

        // Title bar：左侧「排序」开关（仅对浮层显示做首字母排序，不动真实队列），
        // 右侧「A-Z」首字母查找页按钮（位置不变）。
        TextView btnSort = makeOverlaySortButton();
        playlistOverlaySortBtn = btnSort;
        btnSort.setOnClickListener(v -> togglePlaylistSort());

        TextView btnAz = makeOverlayAzButton();
        btnAz.setOnClickListener(v -> openPlaylistLetterPicker());

        contentLayout.addView(createOverlayTitleBar(
                "播放列表 (" + playlist.size() + "首)", btnSort, btnAz));

        // Song list in a ScrollView
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listLayout);

        // 记下容器与歌曲引用，供排序后重渲染与滚动定位。
        playlistOverlaySongs = playlist;
        playlistOverlayScroll = scrollView;
        playlistOverlayList = listLayout;
        buildPlaylistMap();
        renderPlaylistItems();

        contentLayout.addView(scrollView);

        // 心动模式开启时，底部给一行状态 + 一键退出。
        // 为什么放底部而不是塞进标题栏：标题栏已经有「排序」「A-Z」两个按钮，
        // 再加第三个会把它们挤到点不中；底部这行只在心动开启时出现，平时不占空间。
        if (playerManager.isHeartbeatMode()) {
            contentLayout.addView(buildHeartbeatStatusRow());
        }
        overlayContainer.addView(contentLayout);

        // Scroll to current song（按「显示位置」定位，而非真实队列下标）
        int curDisplay = displayPositionOf(playerManager.getCurrentIndex());
        if (curDisplay >= 0) {
            scrollView.post(() -> {
                View child = listLayout.getChildAt(curDisplay);
                if (child != null) {
                    scrollView.smoothScrollTo(0, child.getTop());
                }
            });
        }

        rootView.addView(overlayContainer);
    }

    /**
     * 心动模式状态行：「已续 N 首 · 歌单播完后自动接着放　[退出]」。
     * 点「退出」清掉已追加的心动曲目、队列回到原歌单。
     */
    private LinearLayout buildHeartbeatStatusRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackgroundColor(0xFF26262B);

        TextView tv = new TextView(this);
        tv.setText("心动续播中 · 已续 " + playerManager.getHeartbeatCount() + " 首");
        tv.setTextColor(0xFFBB86FC);
        tv.setTextSize(11);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.setMarginEnd(dp(6));
        tv.setLayoutParams(tvLp);
        row.addView(tv);

        TextView btnExit = new TextView(this);
        btnExit.setText("退出");
        btnExit.setTextColor(0xFFFFFFFF);
        btnExit.setTextSize(12);
        btnExit.setPadding(dp(12), dp(4), dp(12), dp(4));
        btnExit.setBackgroundResource(R.drawable.bg_letter_cell);
        btnExit.setClickable(true);
        btnExit.setFocusable(true);
        btnExit.setOnClickListener(v -> {
            int removed = playerManager.exitHeartbeat();
            Toast.makeText(this, "已退出心动模式"
                    + (removed > 0 ? "，移除 " + removed + " 首" : ""),
                    Toast.LENGTH_SHORT).show();
            dismissOverlay();
            showPlaylistOverlay();
        });
        row.addView(btnExit);
        return row;
    }

    /**
     * 播放列表浮层右上角的「A-Z」按钮：紫色文字 + 卡片底，热区放大到可稳定点中。
     * 点击打开「首字母查找」页（LetterPickerActivity），仅做跳转、不动真实队列。
     */
    private TextView makeOverlayAzButton() {
        TextView btn = new TextView(this);
        btn.setText("A-Z");
        btn.setTextColor(0xFFBB86FC);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(16));
        btn.setPadding(dp(12), dp(6), dp(12), dp(6));
        btn.setMinWidth(dp(40));
        btn.setMinHeight(dp(34));
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundResource(R.drawable.bg_letter_cell);
        return btn;
    }

    /**
     * 播放列表浮层左上角的「排序」按钮：在「播放顺序」与「按歌名 A→Z」两种显示间切换。
     * 仅改浮层显示、不动真实播放队列；标签在「排序 / 原序」间翻转以提示当前状态。
     */
    private TextView makeOverlaySortButton() {
        TextView btn = new TextView(this);
        btn.setText("排序");
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(16));
        btn.setPadding(dp(12), dp(6), dp(12), dp(6));
        btn.setMinWidth(dp(40));
        btn.setMinHeight(dp(34));
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundResource(R.drawable.bg_letter_cell);
        return btn;
    }

    /**
     * 构造「显示位置 → 真实队列下标」映射。未排序时为恒等映射；
     * 排序时按歌名拼音首字母（中文走 PinyinUtils，其余按 Collator）A→Z 排。
     */
    private void buildPlaylistMap() {
        int n = playlistOverlaySongs.size();
        playlistOverlayMap = new int[n];
        if (!playlistOverlaySorted) {
            for (int i = 0; i < n; i++) {
                playlistOverlayMap[i] = i;
            }
            return;
        }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        final java.text.Collator collator = java.text.Collator.getInstance(java.util.Locale.CHINA);
        java.util.Arrays.sort(idx, (a, b) -> {
            Song sa = playlistOverlaySongs.get(a);
            Song sb = playlistOverlaySongs.get(b);
            char ia = PinyinUtils.initialOfWord(sa.getName());
            char ib = PinyinUtils.initialOfWord(sb.getName());
            if (ia != ib) {
                if (ia == PinyinUtils.OTHER) return 1;
                if (ib == PinyinUtils.OTHER) return -1;
                return Character.compare(ia, ib);
            }
            return collator.compare(sa.getName(), sb.getName());
        });
        for (int i = 0; i < n; i++) {
            playlistOverlayMap[i] = idx[i];
        }
    }

    /**
     * 按 playlistOverlayMap 重渲染浮层列表。显示位置 d → 真实队列下标 playlistOverlayMap[d]，
     * 点歌 / 长按都走真实下标，所以排序只改「显示」、不动真实播放队列与「下一首播放」。
     */
    private void renderPlaylistItems() {
        if (playlistOverlayList == null || playlistOverlaySongs == null
                || playlistOverlayMap == null) {
            return;
        }
        playlistOverlayList.removeAllViews();
        int curReal = playerManager.getCurrentIndex();
        int n = playlistOverlaySongs.size();
        for (int d = 0; d < n; d++) {
            final int realIndex = playlistOverlayMap[d];
            Song song = playlistOverlaySongs.get(realIndex);

            LinearLayout itemLayout = new LinearLayout(this);
            itemLayout.setOrientation(LinearLayout.HORIZONTAL);
            itemLayout.setPadding(dp(8), dp(6), dp(8), dp(6));
            itemLayout.setClickable(true);
            itemLayout.setFocusable(true);

            if (realIndex == curReal) {
                itemLayout.setBackgroundColor(0xFF2D2D2D);
            }

            ImageView ivCover = new ImageView(this);
            int coverSize = dp(36);
            LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(coverSize, coverSize);
            coverLp.gravity = Gravity.CENTER_VERTICAL;
            ivCover.setLayoutParams(coverLp);
            ivCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            android.graphics.drawable.GradientDrawable coverBg = new android.graphics.drawable.GradientDrawable();
            coverBg.setColor(0xFF333333);
            coverBg.setCornerRadius(UiKit.cardRadiusPx(this));
            ivCover.setBackground(coverBg);
            NetworkImageLoader.load(ivCover, song.getCoverUrl());
            itemLayout.addView(ivCover);

            LinearLayout textLayout = new LinearLayout(this);
            textLayout.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            textLp.gravity = Gravity.CENTER_VERTICAL;
            textLp.setMarginStart(dp(8));
            textLayout.setLayoutParams(textLp);

            TextView tvName = new TextView(this);
            String prefix = (realIndex == curReal) ? "▶ " : (d + 1) + ". ";
            tvName.setText(prefix + song.getDisplayName());
            tvName.setTextColor(realIndex == curReal ? 0xFFBB86FC : 0xFFFFFFFF);
            tvName.setTextSize(13);
            tvName.setSingleLine(true);
            tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textLayout.addView(tvName);

            TextView tvArtist = new TextView(this);
            tvArtist.setText(song.getArtist());
            tvArtist.setTextColor(0x80FFFFFF);
            tvArtist.setTextSize(11);
            tvArtist.setSingleLine(true);
            tvArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textLayout.addView(tvArtist);

            itemLayout.addView(textLayout);

            // 心动模式追加进来的曲目打个标记，让用户知道「这些歌不在原歌单里」
            if (playerManager.isHeartbeatSong(song)) {
                TextView tag = new TextView(this);
                tag.setText("心动");
                tag.setTextColor(0xFF121212);
                tag.setTextSize(9);
                tag.setPadding(dp(5), dp(2), dp(5), dp(2));
                tag.setBackgroundResource(R.drawable.bg_btn_accent);
                LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                tagLp.gravity = Gravity.CENTER_VERTICAL;
                tag.setLayoutParams(tagLp);
                itemLayout.addView(tag);
            }

            // 「下一首播放」角标（按 Song 身份判定，与显示顺序无关）
            int queuedPos = playerManager.queuedNextPosition(song);
            if (queuedPos > 0) {
                TextView badge = new TextView(this);
                badge.setText(queuedPos > 1 ? "下一首 " + queuedPos : "下一首");
                badge.setTextColor(0xFF1D1D1D);
                badge.setTextSize(10);
                badge.setPadding(dp(5), dp(2), dp(5), dp(2));
                android.graphics.drawable.GradientDrawable badgeBg =
                        new android.graphics.drawable.GradientDrawable();
                badgeBg.setColor(0xFFBB86FC);
                badgeBg.setCornerRadius(UiKit.dp(this, 8));
                badge.setBackground(badgeBg);
                LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                badgeLp.gravity = Gravity.CENTER_VERTICAL;
                badgeLp.setMarginStart(dp(4));
                badge.setLayoutParams(badgeLp);
                itemLayout.addView(badge);
            }

            itemLayout.setOnClickListener(v -> {
                playerManager.playFromCurrentPlaylist(realIndex);
                dismissOverlay();
            });

            // 长按弹出队列操作菜单（下一首播放 / 从队列移除）
            itemLayout.setOnLongClickListener(v -> {
                showPlaylistItemActions(realIndex);
                return true;
            });

            playlistOverlayList.addView(itemLayout);
        }
    }

    /**
     * 真实队列下标 → 当前显示位置；找不到返回 -1。
     */
    private int displayPositionOf(int realIndex) {
        if (playlistOverlayMap == null || realIndex < 0) {
            return -1;
        }
        for (int d = 0; d < playlistOverlayMap.length; d++) {
            if (playlistOverlayMap[d] == realIndex) {
                return d;
            }
        }
        return -1;
    }

    /**
     * 「排序」按钮：在「播放顺序」与「按歌名 A→Z」两种显示间切换。仅改浮层显示，
     * 不动真实播放队列，所以「下一首播放」、当前播放位置都不受影响。
     */
    private void togglePlaylistSort() {
        setPlaylistSorted(!playlistOverlaySorted);
    }

    /** 设置浮层是否按歌名首字母排序显示（仅显示，不动真实队列），并刷新按钮标签。 */
    private void setPlaylistSorted(boolean sorted) {
        playlistOverlaySorted = sorted;
        buildPlaylistMap();
        renderPlaylistItems();
        updateSortButtonLabel();
    }

    /** 刷新「排序 / 原序」按钮的标签与配色，提示当前是否处于首字母排序态。 */
    private void updateSortButtonLabel() {
        if (playlistOverlaySortBtn != null) {
            playlistOverlaySortBtn.setText(playlistOverlaySorted ? "原序" : "排序");
            playlistOverlaySortBtn.setTextColor(
                    playlistOverlaySorted ? 0xFFBB86FC : 0xFFFFFFFF);
        }
    }

    /**
     * 打开「首字母查找」页（独立一屏大格子）。首字母跳转只有在「按首字母排序」后才成立，
     * 所以进来若还是播放原序，先排好再开页；返回时把列表滚到所选字母的第一首。
     */
    private void openPlaylistLetterPicker() {
        if (playlistOverlaySongs == null || playlistOverlayMap == null) {
            return;
        }
        // 首字母查找依赖「按首字母排好序」的显示，原序下先排好再开页。
        if (!playlistOverlaySorted) {
            setPlaylistSorted(true);
        }
        int n = playlistOverlaySongs.size();
        java.util.LinkedHashMap<Character, Integer> firstIndex =
                new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<Character, Integer> counts =
                new java.util.LinkedHashMap<>();
        for (int d = 0; d < n; d++) {
            Song song = playlistOverlaySongs.get(playlistOverlayMap[d]);
            char initial = PinyinUtils.initialOfWord(song.getName());
            if (!firstIndex.containsKey(initial)) {
                firstIndex.put(initial, d);
            }
            Integer c = counts.get(initial);
            counts.put(initial, c == null ? 1 : c + 1);
        }
        if (firstIndex.isEmpty()) {
            Toast.makeText(this, "没有可跳转的字母", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder letters = new StringBuilder(firstIndex.size());
        int[] starts = new int[firstIndex.size()];
        int[] groupCounts = new int[firstIndex.size()];
        int idx = 0;
        for (Character key : firstIndex.keySet()) {
            letters.append(key.charValue());
            starts[idx] = firstIndex.get(key);
            Integer c = counts.get(key);
            groupCounts[idx] = c == null ? 0 : c;
            idx++;
        }
        Intent intent = new Intent(this, LetterPickerActivity.class);
        intent.putExtra(LetterPickerActivity.EXTRA_LETTERS, letters.toString());
        intent.putExtra(LetterPickerActivity.EXTRA_STARTS, starts);
        intent.putExtra(LetterPickerActivity.EXTRA_COUNTS, groupCounts);
        intent.putExtra(LetterPickerActivity.EXTRA_CURRENT,
                displayPositionOf(playerManager.getCurrentIndex()));
        startActivityForResult(intent, REQ_PLAYLIST_AZ);
    }

    /**
     * 长按播放队列某一项时弹出的操作菜单。
     *
     * <p>用统一的 {@link WatchConfirmDialog#showChoice} 弹窗，样式与其它确认框一致；
     * 移除是破坏性操作，所以再套一层二次确认。</p>
     */
    private void showPlaylistItemActions(int index) {
        java.util.List<Song> queue = playerManager.getPlaylist();
        if (index < 0 || index >= queue.size()) {
            return;
        }
        Song song = queue.get(index);
        String title = (song.getName() != null && !song.getName().isEmpty())
                ? song.getName() : "歌曲";
        final boolean isCurrent = (index == playerManager.getCurrentIndex());
        final boolean queued = playerManager.queuedNextPosition(song) > 0;

        // 心动模式只在"正在播放某个歌单"时出现：官方智能播放接口以歌单为上下文，
        // 单曲来源（搜索/排行直接播）没有可用场景。
        final boolean inPlaylist = playerManager.hasSourcePlaylist()
                && playerManager.getSourcePlaylistId() > 0;
        java.util.List<String> labelList = new java.util.ArrayList<>();
        java.util.List<Runnable> actionList = new java.util.ArrayList<>();

        labelList.add(queued ? "取消下一首播放" : "下一首播放");
        actionList.add(() -> {
            if (queued) {
                playerManager.cancelQueuedNext(song);
                Toast.makeText(this, "已取消下一首播放", Toast.LENGTH_SHORT).show();
                dismissOverlay();
                showPlaylistOverlay();
                return;
            }
            if (playerManager.queueNextInPlaylist(index)) {
                int pos = playerManager.queuedNextPosition(song);
                Toast.makeText(this,
                        pos > 1 ? "已排入下一首播放（第 " + pos + " 首）" : "已设为下一首播放",
                        Toast.LENGTH_SHORT).show();
                dismissOverlay();
                showPlaylistOverlay();
            } else {
                Toast.makeText(this, "这一首正在播放", Toast.LENGTH_SHORT).show();
            }
        });

        if (inPlaylist) {
            labelList.add("心动模式");
            actionList.add(() -> startIntelligenceMode(song));
        }

        labelList.add("从队列移除");
        actionList.add(() -> WatchConfirmDialog.show(this, "确认移除",
                isCurrent ? "移除正在播放的这首歌？" : "从播放队列移除「" + title + "」？",
                () -> {
                    playerManager.removeFromPlaylist(index);
                    Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show();
                    dismissOverlay();
                    showPlaylistOverlay();
                },
                WatchConfirmDialog.Options.destructiveOptions()));

        WatchConfirmDialog.showChoice(this, title, "队列操作",
                labelList.toArray(new String[0]),
                actionList.toArray(new Runnable[0]),
                WatchConfirmDialog.Options.defaultOptions());
    }

    /**
     * 心动模式（智能续播）：以当前歌曲 + 所在歌单为种子生成一串相似曲目，直接接管播放队列。
     */
    private void startIntelligenceMode(Song seed) {
        // 再次点击 = 退出：清掉已追加的心动曲目，歌单回到原来的样子
        if (playerManager.isHeartbeatMode()) {
            int removed = playerManager.exitHeartbeat();
            Toast.makeText(this, "已退出心动模式" + (removed > 0 ? "，移除 " + removed + " 首" : ""),
                    Toast.LENGTH_SHORT).show();
            dismissOverlay();
            return;
        }
        if (seed == null || seed.getId() <= 0) {
            Toast.makeText(this, "这首歌无法作为起点", Toast.LENGTH_SHORT).show();
            return;
        }
        // 不再替换队列、也不再抢播：正在听的这首照常播完，推荐曲安静地排到队尾
        Toast.makeText(this, "正在准备心动曲目…", Toast.LENGTH_SHORT).show();
        playerManager.startHeartbeat(seed, added -> {
            dismissOverlay();
            if (added > 0) {
                Toast.makeText(MainActivity.this,
                        "心动模式已开启 · 已续上 " + added + " 首，歌单播完后自动接着放",
                        Toast.LENGTH_SHORT).show();
            } else {
                playerManager.setHeartbeatModeOff();
                Toast.makeText(MainActivity.this, "没有拿到心动曲目", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showSpeedOptions() {
        FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);

        overlayContainer = new FrameLayout(this);
        overlayContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayContainer.setBackgroundColor(0xCC000000);
        addSwipeToDismiss(overlayContainer);

        ScrollView scrollView = new ScrollView(this);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.gravity = Gravity.CENTER;
        scrollView.setLayoutParams(scrollParams);
        scrollView.setOnClickListener(v -> { /* consume click */ });

        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER);
        contentLayout.setPadding(dp(20), dp(20), dp(20), dp(20));

        // Title bar with close button
        contentLayout.addView(createOverlayTitleBar("倍速播放"));

        // Preset speed options
        float[] speeds = {0.8f, 0.9f, 1.0f, 1.1f, 1.2f};
        float currentSpeed = playerManager.getPlaybackSpeed();
        for (float speed : speeds) {
            TextView btn = new TextView(this);
            String label = String.format("%.1fx", speed);
            if (speed == 1.0f) label = "1.0x (正常)";
            btn.setText(label);
            btn.setTextColor(0xFFFFFFFF);
            btn.setTextSize(14);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(0, dp(10), 0, dp(10));
            btn.setBackgroundColor(Math.abs(currentSpeed - speed) < 0.01f ? 0xFFBB86FC : 0xFF2D2D2D);
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnParams.bottomMargin = dp(4);
            btn.setLayoutParams(btnParams);
            btn.setClickable(true);
            btn.setFocusable(true);
            float finalSpeed = speed;
            btn.setOnClickListener(v -> {
                playerManager.setPlaybackSpeed(finalSpeed);
                Toast.makeText(this, String.format("播放速度: %.1fx", finalSpeed), Toast.LENGTH_SHORT).show();
                dismissOverlay();
            });
            contentLayout.addView(btn);
        }

        // Custom speed input
        TextView customLabel = new TextView(this);
        customLabel.setText("自定义（0.1-5.0）");
        customLabel.setTextColor(0xB3FFFFFF);
        customLabel.setTextSize(13);
        customLabel.setGravity(Gravity.CENTER);
        customLabel.setPadding(0, dp(12), 0, dp(4));
        contentLayout.addView(customLabel);

        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        customRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText etSpeed = new EditText(this);
        etSpeed.setHint("倍速");
        etSpeed.setTextColor(0xFFFFFFFF);
        etSpeed.setHintTextColor(0xFF888888);
        etSpeed.setTextSize(14);
        etSpeed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etSpeed.setBackgroundColor(0xFF2D2D2D);
        etSpeed.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        etParams.rightMargin = dp(4);
        etSpeed.setLayoutParams(etParams);
        customRow.addView(etSpeed);

        TextView btnApply = new TextView(this);
        btnApply.setText("应用");
        btnApply.setTextColor(0xFFFFFFFF);
        btnApply.setTextSize(14);
        btnApply.setGravity(Gravity.CENTER);
        btnApply.setPadding(dp(12), dp(8), dp(12), dp(8));
        btnApply.setBackgroundColor(0xFFBB86FC);
        btnApply.setClickable(true);
        btnApply.setFocusable(true);
        btnApply.setOnClickListener(v -> {
            String input = etSpeed.getText().toString().trim();
            if (input.isEmpty()) {
                Toast.makeText(this, "请输入倍速值", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                float speed = Float.parseFloat(input);
                if (speed < 0.1f || speed > 5.0f) {
                    Toast.makeText(this, "请输入0.1-5.0之间的值", Toast.LENGTH_SHORT).show();
                    return;
                }
                playerManager.setPlaybackSpeed(speed);
                Toast.makeText(this, String.format("播放速度: %.1fx", speed), Toast.LENGTH_SHORT).show();
                dismissOverlay();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
            }
        });
        customRow.addView(btnApply);

        contentLayout.addView(customRow);

        scrollView.addView(contentLayout);
        overlayContainer.addView(scrollView);
        rootView.addView(overlayContainer);
    }

    // ==================== Audio Quality ====================

    interface QualitySelectCallback {
        void onSelected(String quality);
    }

    private void onFuncSelectQuality() {
        dismissOverlay();
        Song song = playerManager.getCurrentSong();
        showQualityOptionsInternal(true, song, quality -> {
            getSharedPreferences("music163_settings", MODE_PRIVATE)
                    .edit().putString("preferred_quality", quality).apply();
            if (song != null && !song.isBilibili()) {
                playerManager.switchCurrentSongQuality(quality);
            }
            Toast.makeText(this, "音质已设置: " + getQualityDisplayName(quality),
                    Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Show the quality selection overlay, fetching availability from the API.
     * @param saveAsPreferred  true = save selection to SharedPreferences (playback quality),
     *                         false = just call callback for one-time use (download quality)
     * @param song             the song to check quality availability for (may be null)
     */
    private void showQualityOptionsInternal(boolean saveAsPreferred, Song song,
                                             QualitySelectCallback callback) {
        FrameLayout rootView = (FrameLayout) getWindow().getDecorView()
                .findViewById(android.R.id.content);

        overlayContainer = new FrameLayout(this);
        overlayContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayContainer.setBackgroundColor(0xCC000000);
        addSwipeToDismiss(overlayContainer);

        ScrollView scrollView = new ScrollView(this);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.gravity = Gravity.CENTER;
        scrollView.setLayoutParams(scrollParams);
        scrollView.setOnClickListener(v -> { /* consume click */ });

        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER);
        contentLayout.setPadding(dp(20), dp(16), dp(20), dp(16));

        String title = saveAsPreferred ? "播放音质" : "下载音质";
        contentLayout.addView(createOverlayTitleBar(title));

        // Placeholder for quality rows — filled once API returns
        LinearLayout qualityListLayout = new LinearLayout(this);
        qualityListLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.addView(qualityListLayout);

        // Loading indicator
        android.widget.ProgressBar loading = new android.widget.ProgressBar(this);
        LinearLayout.LayoutParams loadParams = new LinearLayout.LayoutParams(
                dp(32), dp(32));
        loadParams.gravity = Gravity.CENTER_HORIZONTAL;
        loadParams.topMargin = dp(8);
        loadParams.bottomMargin = dp(8);
        loading.setLayoutParams(loadParams);
        qualityListLayout.addView(loading);

        scrollView.addView(contentLayout);
        overlayContainer.addView(scrollView);
        rootView.addView(overlayContainer);

        String currentQuality = getSharedPreferences("music163_settings", MODE_PRIVATE)
                .getString("preferred_quality", "exhigh");
        if (saveAsPreferred && song != null && song.isForceLocalPlayback()) {
            qualityListLayout.removeAllViews();
            buildLocalQualityRows(qualityListLayout, song, currentQuality, callback);
            return;
        }

        // Fetch quality info from API (only for NetEase songs with a valid ID)
        long songId = (song != null && !song.isBilibili() && song.getId() > 0) ? song.getId() : 0;
        String cookie = playerManager.getCookie();

        if (songId > 0) {
            MusicApiHelper.getSongQualityInfo(songId, cookie, new MusicApiHelper.SongQualityCallback() {
                @Override
                public void onResult(MusicApiHelper.SongQualityInfo info) {
                    runOnUiThread(() -> {
                        qualityListLayout.removeAllViews();
                        buildQualityRows(qualityListLayout, song, info, currentQuality,
                                saveAsPreferred, callback);
                    });
                }
                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        qualityListLayout.removeAllViews();
                        // Fallback: show all levels without availability info
                        buildQualityRows(qualityListLayout, song, null, currentQuality,
                                saveAsPreferred, callback);
                    });
                }
            });
        } else {
            qualityListLayout.removeAllViews();
            buildQualityRows(qualityListLayout, song, null, currentQuality, saveAsPreferred, callback);
        }
    }

    private void buildLocalQualityRows(LinearLayout container, Song song, String currentQuality,
                                       QualitySelectCallback callback) {
        java.util.List<String> localQualities = DownloadManager.getAvailableLocalQualities(song);
        String selectedLocalQuality = DownloadManager.detectLocalQualityFromPath(song.getUrl());
        if (selectedLocalQuality == null) {
            selectedLocalQuality = DownloadManager.getBestDownloadedQuality(song);
        }
        if (localQualities.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无本地音质列表");
            empty.setTextColor(0x80FFFFFF);
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(8), 0, dp(8));
            container.addView(empty);
            return;
        }
        for (String quality : localQualities) {
            boolean isSelected = quality.equals(selectedLocalQuality);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            row.setBackgroundColor(isSelected ? 0x33BB86FC : 0xFF2D2D2D);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(4);
            row.setLayoutParams(rowParams);
            row.setClickable(true);
            row.setFocusable(true);

            LinearLayout leftCol = new LinearLayout(this);
            leftCol.setOrientation(LinearLayout.VERTICAL);
            leftCol.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView tvName = new TextView(this);
            tvName.setText(getQualityShortName(quality));
            tvName.setTextColor(isSelected ? 0xFFBB86FC : 0xFFFFFFFF);
            tvName.setTextSize(14);
            leftCol.addView(tvName);

            TextView tvDesc = new TextView(this);
            tvDesc.setText("本地已下载");
            tvDesc.setTextColor(0x80FFFFFF);
            tvDesc.setTextSize(11);
            leftCol.addView(tvDesc);
            row.addView(leftCol);

            TextView badge = new TextView(this);
            badge.setText("本地");
            badge.setTextColor(0xFF4CAF50);
            badge.setTextSize(11);
            badge.setPadding(dp(6), dp(2), dp(6), dp(2));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(UiKit.dp(this, 8));
            bg.setColor(0x22FFFFFF);
            badge.setBackground(bg);
            row.addView(badge);

            final String selectedQuality = quality;
            row.setOnClickListener(v -> {
                callback.onSelected(selectedQuality);
                dismissOverlay();
            });
            container.addView(row);
        }
    }

    /**
     * Build quality option rows into the given layout.
     * @param info          song-specific quality info from API; null = show all without availability
     */
    private void buildQualityRows(LinearLayout container,
                                   Song song,
                                    MusicApiHelper.SongQualityInfo info,
                                    String currentQuality,
                                    boolean saveAsPreferred,
                                    QualitySelectCallback callback) {
        java.util.List<String> downloadedQualities = !saveAsPreferred && song != null
                ? DownloadManager.getAvailableLocalQualities(song)
                : java.util.Collections.emptyList();
        // level, shortName, bitrate description
        String[][] qualities = {
            {"standard", "标准",     "128K MP3"},
            {"higher",   "较高",     "192K MP3"},
            {"exhigh",   "极高",     "320K MP3"},
            {"lossless", "无损",     "FLAC"},
            {"hires",    "Hi-Res",   "Hi-Res FLAC"},
            {"jyeffect", "臻品声场", "高清环绕声"},
            {"sky",      "全景声",   "沉浸环绕声"},
            {"jymaster", "臻品母带", "超清母带"},
        };

        for (String[] q : qualities) {
            String level   = q[0];
            String name    = q[1];
            String bitrate = q[2];

            boolean isSelected = saveAsPreferred && level.equals(currentQuality);
            boolean alreadyDownloaded = !saveAsPreferred
                    && downloadedQualities.contains(level);

            // Determine tier badge from API info
            String tier;
            if (info == null) {
                // No API data — show static defaults based on commonly free levels
                int rank = MusicApiHelper.qualityLevelRank(level);
                if (rank <= 3) tier = "免费";        // standard(1)/higher(2)/exhigh(3)
                else tier = "VIP";
            } else {
                String t = info.getTier(level);
                switch (t) {
                    case "free":        tier = "免费"; break;
                    case "vip":         tier = "VIP";  break;
                    case "unavailable": tier = QUALITY_TIER_UNAVAILABLE; break;
                    default:            tier = "VIP";  break;
                }
            }

            if (alreadyDownloaded) {
                tier = QUALITY_TIER_DOWNLOADED;
            }

            boolean disabledForSelection = QUALITY_TIER_UNAVAILABLE.equals(tier)
                    || QUALITY_TIER_DOWNLOADED.equals(tier);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            int bgColor = disabledForSelection ? 0xFF222222 : (isSelected ? 0x33BB86FC : 0xFF2D2D2D);
            row.setBackgroundColor(bgColor);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(4);
            row.setLayoutParams(rowParams);
            if (!disabledForSelection) {
                row.setClickable(true);
                row.setFocusable(true);
            }

            // Left column: name + bitrate
            LinearLayout leftCol = new LinearLayout(this);
            leftCol.setOrientation(LinearLayout.VERTICAL);
            leftCol.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView tvName = new TextView(this);
            tvName.setText(name);
            int nameColor = disabledForSelection ? 0x60FFFFFF : (isSelected ? 0xFFBB86FC : 0xFFFFFFFF);
            tvName.setTextColor(nameColor);
            tvName.setTextSize(14);
            leftCol.addView(tvName);

            TextView tvBitrate = new TextView(this);
            tvBitrate.setText(bitrate);
            tvBitrate.setTextColor(disabledForSelection ? 0x30FFFFFF : 0x80FFFFFF);
            tvBitrate.setTextSize(11);
            leftCol.addView(tvBitrate);

            row.addView(leftCol);

            // Right: tier badge
            int tierColor;
            switch (tier) {
                case "VIP":  tierColor = 0xFFFFAA00; break;
                case QUALITY_TIER_UNAVAILABLE: tierColor = 0x60FFFFFF; break;
                case QUALITY_TIER_DOWNLOADED: tierColor = 0xFF4CAF50; break;
                default:     tierColor = 0xFF4CAF50; break; // 免费
            }
            TextView tvTier = new TextView(this);
            tvTier.setText(tier);
            tvTier.setTextColor(tierColor);
            tvTier.setTextSize(11);
            tvTier.setPadding(dp(6), dp(2), dp(6), dp(2));
            GradientDrawable tierBg = new GradientDrawable();
            tierBg.setShape(GradientDrawable.RECTANGLE);
            tierBg.setCornerRadius(UiKit.dp(this, 8));
            tierBg.setColor(0x22FFFFFF);
            tvTier.setBackground(tierBg);
            row.addView(tvTier);

            if (!disabledForSelection) {
                row.setOnClickListener(v -> {
                    callback.onSelected(level);
                    dismissOverlay();
                });
            }

            container.addView(row);
        }
    }

    /** Short display name for current quality (shown on the button label). */
    private String getCurrentQualityLabel(Song song, String fallbackQuality) {
        if (song != null && (song.isForceLocalPlayback()
                || (song.getUrl() != null && song.getUrl().startsWith("/")))) {
            String localQuality = DownloadManager.detectLocalQualityFromPath(song.getUrl());
            if (localQuality == null) {
                localQuality = DownloadManager.getBestDownloadedQuality(song);
            }
            if (localQuality != null) {
                return localQuality;
            }
        }
        return fallbackQuality;
    }

    /** Short display name for current quality (shown on the button label). */
    private String getQualityShortName(String level) {
        switch (level) {
            case "standard": return "标准";
            case "higher":   return "较高";
            case "exhigh":   return "极高";
            case "lossless": return "无损";
            case "hires":    return "Hi-Res";
            case "jyeffect": return "臻品声场";
            case "sky":      return "全景声";
            case "jymaster": return "臻品母带";
            default:         return level;
        }
    }

    /** Full display name for quality (shown in toasts). */
    private String getQualityDisplayName(String level) {
        switch (level) {
            case "standard": return "标准 (128K)";
            case "higher":   return "较高 (192K)";
            case "exhigh":   return "极高 (320K)";
            case "lossless": return "无损 (FLAC)";
            case "hires":    return "Hi-Res";
            case "jyeffect": return "臻品声场";
            case "sky":      return "臻品全景声";
            case "jymaster": return "臻品母带";
            default:         return level;
        }
    }

    // ==================== Cloud Favorites Status Cache ====================

    private java.util.Set<Long> cloudLikedIds = null;
    private long cloudLikedCacheTime = 0;
    private static final long CLOUD_CACHE_TTL = 5 * 60 * 1000; // 5 minutes

    /**
     * Check if a song is liked in cloud mode, using cached IDs.
     */
    private boolean isCloudLiked(long songId) {
        if (cloudLikedIds != null && System.currentTimeMillis() - cloudLikedCacheTime < CLOUD_CACHE_TTL) {
            return cloudLikedIds.contains(songId);
        }
        // If cache is stale/empty, refresh asynchronously
        refreshCloudLikedIds();
        return cloudLikedIds != null && cloudLikedIds.contains(songId);
    }

    /**
     * Refresh the cached set of cloud liked song IDs.
     */
    private void refreshCloudLikedIds() {
        String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) return;
        MusicApiHelper.getCloudLikedIds(cookie, new MusicApiHelper.CloudLikedIdsCallback() {
            @Override
            public void onResult(java.util.Set<Long> ids) {
                cloudLikedIds = ids;
                cloudLikedCacheTime = System.currentTimeMillis();
            }
            @Override
            public void onError(String message) {
                // Ignore errors silently; cache remains stale
            }
        });
    }

    private void onFuncSetRingtone(Song song) {
        dismissOverlay();
        // Check if song is downloaded first
        String mp3Path = DownloadManager.getDownloadedMp3Path(song);

        if (mp3Path != null) {
            // Already downloaded locally — use it directly
            showRingtoneClipOverlay(new File(mp3Path), song.getName());
        } else {
            // Cache audio to a temp file (don't save to downloads)
            Toast.makeText(this, "正在缓存歌曲...", Toast.LENGTH_SHORT).show();
            String cookie = getDownloadCookieForSong(song);
            SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
            String quality = prefs.getString("preferred_quality", "exhigh");

            File cacheDir = new File(android.os.Environment.getExternalStorageDirectory(),
                    "163Music/cache");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File tempFile = new File(cacheDir, "ringtone_temp_" + song.getId() + ".mp3");

            DownloadManager.downloadSongToFile(song, cookie, quality, tempFile,
                    new DownloadManager.DownloadCallback() {
                @Override
                public void onSuccess(String filePath) {
                    runOnUiThread(() -> showRingtoneClipOverlay(new File(filePath), song.getName(),
                            true /* deleteAfterSet */));
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(MainActivity.this, "缓存失败，无法设为铃声", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private MediaPlayer ringtonePreviewPlayer;

    private String getDownloadCookieForSong(Song song) {
        if (song != null && song.isBilibili()) {
            SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
            return prefs.getString("bilibili_cookie", "");
        }
        return playerManager.getCookie();
    }

    private void showRingtoneClipOverlay(File file, String songTitle) {
        showRingtoneClipOverlay(file, songTitle, false);
    }

    private void showRingtoneClipOverlay(File file, String songTitle, boolean deleteTempAfterSet) {
        // Get song duration
        int durationMs;
        try {
            MediaPlayer tmp = new MediaPlayer();
            tmp.setDataSource(file.getAbsolutePath());
            tmp.prepare();
            durationMs = tmp.getDuration();
            tmp.release();
        } catch (Exception e) {
            Toast.makeText(this, "无法读取歌曲时长", Toast.LENGTH_SHORT).show();
            return;
        }
        final int totalSec = durationMs / 1000;
        if (totalSec <= 0) {
            Toast.makeText(this, "歌曲时长无效", Toast.LENGTH_SHORT).show();
            return;
        }

        FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);

        overlayContainer = new FrameLayout(this);
        overlayContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayContainer.setBackgroundColor(0xCC000000);
        addSwipeToDismiss(overlayContainer);

        // Wrap content in ScrollView for small watch screens
        ScrollView scrollView = new ScrollView(this);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        scrollView.setLayoutParams(scrollParams);
        scrollView.setFillViewport(true);

        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER);
        contentLayout.setPadding(dp(16), dp(12), dp(16), dp(12));
        contentLayout.setOnClickListener(v -> { /* consume click */ });

        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        contentLayout.setLayoutParams(contentParams);

        // Title bar with close button
        contentLayout.addView(createOverlayTitleBar("节选铃声"));

        // Song name
        TextView tvSong = new TextView(this);
        tvSong.setText(songTitle);
        tvSong.setTextColor(0xB3FFFFFF);
        tvSong.setTextSize(12);
        tvSong.setGravity(Gravity.CENTER);
        tvSong.setPadding(0, 0, 0, dp(8));
        contentLayout.addView(tvSong);

        // Start seekbar
        final int[] startSec = {0};
        final int[] endSec = {Math.min(totalSec, 30)};

        TextView tvStart = new TextView(this);
        tvStart.setText("起始: " + startSec[0] + "s");
        tvStart.setTextColor(0xFFFFFFFF);
        tvStart.setTextSize(12);
        tvStart.setPadding(0, dp(4), 0, 0);
        contentLayout.addView(tvStart);

        SeekBar sbStart = new SeekBar(this);
        sbStart.setMax(totalSec);
        sbStart.setProgress(startSec[0]);
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sbStart.setLayoutParams(seekParams);
        contentLayout.addView(sbStart);

        // End seekbar
        TextView tvEnd = new TextView(this);
        tvEnd.setText("结束: " + endSec[0] + "s");
        tvEnd.setTextColor(0xFFFFFFFF);
        tvEnd.setTextSize(12);
        tvEnd.setPadding(0, dp(4), 0, 0);
        contentLayout.addView(tvEnd);

        SeekBar sbEnd = new SeekBar(this);
        sbEnd.setMax(totalSec);
        sbEnd.setProgress(endSec[0]);
        sbEnd.setLayoutParams(seekParams);
        contentLayout.addView(sbEnd);

        // Duration display
        TextView tvDuration = new TextView(this);
        tvDuration.setText("节选: " + startSec[0] + "s - " + endSec[0] + "s (" + (endSec[0] - startSec[0]) + "秒)");
        tvDuration.setTextColor(0xB3FFFFFF);
        tvDuration.setTextSize(12);
        tvDuration.setGravity(Gravity.CENTER);
        tvDuration.setPadding(0, dp(8), 0, dp(8));
        contentLayout.addView(tvDuration);

        TextView tvChorusRange = new TextView(this);
        tvChorusRange.setText(hasChorusRange()
                ? "高潮: " + millisToFloorSeconds(currentChorusStartMs) + "s - " + millisToCeilSeconds(currentChorusEndMs) + "s"
                : "高潮: 暂无数据");
        tvChorusRange.setTextColor(ContextCompat.getColor(this,
                hasChorusRange() ? R.color.colorAccent : R.color.text_secondary));
        tvChorusRange.setTextSize(11);
        tvChorusRange.setGravity(Gravity.CENTER);
        tvChorusRange.setPadding(0, 0, 0, dp(8));
        contentLayout.addView(tvChorusRange);

        Runnable updateDuration = () -> {
            int dur = endSec[0] - startSec[0];
            tvDuration.setText("节选: " + startSec[0] + "s - " + endSec[0] + "s (" + dur + "秒)");
        };

        sbStart.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    if (progress >= endSec[0]) progress = endSec[0] - 1;
                    if (progress < 0) progress = 0;
                    seekBar.setProgress(progress);
                    startSec[0] = progress;
                    tvStart.setText("起始: " + progress + "s");
                    updateDuration.run();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbEnd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    if (progress <= startSec[0]) progress = startSec[0] + 1;
                    if (progress > totalSec) progress = totalSec;
                    seekBar.setProgress(progress);
                    endSec[0] = progress;
                    tvEnd.setText("结束: " + progress + "s");
                    updateDuration.run();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        int grayButtonColor = ContextCompat.getColor(this, R.color.surface_elevated);
        int primaryButtonColor = ContextCompat.getColor(this, R.color.colorPrimary);

        LinearLayout topButtonRow = new LinearLayout(this);
        topButtonRow.setOrientation(LinearLayout.HORIZONTAL);
        topButtonRow.setGravity(Gravity.CENTER);
        topButtonRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        MaterialButton btnSmartClip = createOverlayActionButton("智能截取", grayButtonColor, false);
        LinearLayout.LayoutParams smartClipParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        smartClipParams.rightMargin = dp(4);
        btnSmartClip.setLayoutParams(smartClipParams);
        btnSmartClip.setOnClickListener(v -> applySmartClipRange(totalSec, startSec, endSec, sbStart, sbEnd,
                tvStart, tvEnd, updateDuration, tvChorusRange));
        topButtonRow.addView(btnSmartClip);

        MaterialButton btnPreview = createOverlayActionButton("试听", grayButtonColor, false);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        previewParams.leftMargin = dp(4);
        btnPreview.setLayoutParams(previewParams);
        btnPreview.setOnClickListener(v -> {
            if (endSec[0] <= startSec[0]) {
                Toast.makeText(this, "请选择有效的时间范围", Toast.LENGTH_SHORT).show();
                return;
            }
            previewRingtoneClip(file, startSec[0] * 1000, endSec[0] * 1000);
        });
        topButtonRow.addView(btnPreview);
        contentLayout.addView(topButtonRow);

        MaterialButton btnConfirm = createOverlayActionButton("确认", primaryButtonColor, true);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        confirmParams.topMargin = dp(8);
        btnConfirm.setLayoutParams(confirmParams);
        btnConfirm.setOnClickListener(v -> {
            if (endSec[0] <= startSec[0]) {
                Toast.makeText(this, "请选择有效的时间范围", Toast.LENGTH_SHORT).show();
                return;
            }
            stopRingtonePreview();
            String clipTitle = songTitle + " (" + startSec[0] + "s-" + endSec[0] + "s)";
            setRingtoneFromFile(file, clipTitle, startSec[0], endSec[0]);
            if (deleteTempAfterSet) {
                // Delete temp cached audio file after setting ringtone
                try { file.delete(); } catch (Exception ignored) {}
            }
            dismissOverlay();
        });
        contentLayout.addView(btnConfirm);

        scrollView.addView(contentLayout);
        overlayContainer.addView(scrollView);
        rootView.addView(overlayContainer);
    }

    private void previewRingtoneClip(File file, int startMs, int endMs) {
        stopRingtonePreview();
        // Pause current music playback if playing
        if (playerManager.isPlaying()) {
            playerManager.pause();
        }
        try {
            ringtonePreviewPlayer = new MediaPlayer();
            ringtonePreviewPlayer.setDataSource(file.getAbsolutePath());
            ringtonePreviewPlayer.prepare();
            ringtonePreviewPlayer.seekTo(startMs);
            ringtonePreviewPlayer.start();
            // Stop at endMs
            final Handler previewHandler = new Handler();
            previewHandler.postDelayed(() -> stopRingtonePreview(), endMs - startMs);
        } catch (Exception e) {
            Toast.makeText(this, "试听失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRingtonePreview() {
        if (ringtonePreviewPlayer != null) {
            try {
                if (ringtonePreviewPlayer.isPlaying()) {
                    ringtonePreviewPlayer.stop();
                }
                ringtonePreviewPlayer.release();
            } catch (Exception ignored) {}
            ringtonePreviewPlayer = null;
        }
    }

    private void setRingtoneFromFile(File file, String title, int startSec, int endSec) {
        try {
            // Check WRITE_SETTINGS permission on Android M+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.System.canWrite(this)) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    Toast.makeText(this, "请授予修改系统设置权限后重试", Toast.LENGTH_LONG).show();
                    return;
                }
            }

            // Create a clipped copy if valid range, otherwise use original
            File clipFile = file;
            if (endSec > startSec) {
                File clipped = createClippedAudio(file, startSec * 1000, endSec * 1000, title);
                if (clipped != null) {
                    clipFile = clipped;
                }
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DATA, clipFile.getAbsolutePath());
            values.put(MediaStore.MediaColumns.TITLE, title);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");
            values.put(MediaStore.Audio.Media.IS_RINGTONE, true);
            values.put(MediaStore.Audio.Media.IS_NOTIFICATION, false);
            values.put(MediaStore.Audio.Media.IS_ALARM, false);
            values.put(MediaStore.Audio.Media.IS_MUSIC, false);

            Uri uri = MediaStore.Audio.Media.getContentUriForPath(clipFile.getAbsolutePath());

            // Delete existing entry if any
            getContentResolver().delete(uri,
                    MediaStore.MediaColumns.DATA + "=?",
                    new String[]{clipFile.getAbsolutePath()});

            Uri newUri = getContentResolver().insert(uri, values);
            if (newUri != null) {
                RingtoneManager.setActualDefaultRingtoneUri(this,
                        RingtoneManager.TYPE_RINGTONE, newUri);
                // Save ringtone info for management
                RingtoneManagerHelper ringtoneHelper = new RingtoneManagerHelper(this);
                ringtoneHelper.addRingtone(title, clipFile.getAbsolutePath(), startSec, endSec);
                Toast.makeText(this, "已设为铃声", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "设置铃声失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "设置铃声失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Create a clipped audio file using MediaExtractor.
     * For MP3 files, writes raw frames directly (MediaMuxer doesn't support MP3 in MPEG4 container).
     * For other formats (AAC/M4A), uses MediaMuxer with MPEG4 container.
     * Returns null if clipping fails.
     */
    private File createClippedAudio(File sourceFile, int startMs, int endMs, String title) {
        try {
            File ringtoneDir = new File(android.os.Environment.getExternalStorageDirectory(),
                    "163Music/Ringtones");
            if (!ringtoneDir.exists()) ringtoneDir.mkdirs();

            // Sanitize title for filename
            String safeName = title.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5()\\-_ ]", "_");

            android.media.MediaExtractor extractor = new android.media.MediaExtractor();
            extractor.setDataSource(sourceFile.getAbsolutePath());

            int audioTrack = -1;
            String mime = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat format = extractor.getTrackFormat(i);
                mime = format.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    break;
                }
            }

            if (audioTrack < 0) {
                extractor.release();
                return null;
            }

            extractor.selectTrack(audioTrack);

            // For MP3 audio, write raw frames directly since MediaMuxer MPEG4 doesn't support MP3
            if ("audio/mpeg".equals(mime)) {
                return createClippedMp3Raw(extractor, safeName, ringtoneDir, startMs, endMs);
            }

            // For other formats (AAC etc.), use MediaMuxer with M4A container
            android.media.MediaFormat format = extractor.getTrackFormat(audioTrack);
            File outputFile = new File(ringtoneDir, safeName + ".m4a");

            android.media.MediaMuxer muxer = new android.media.MediaMuxer(
                    outputFile.getAbsolutePath(),
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerTrack = muxer.addTrack(format);
            muxer.start();

            // Seek to start position
            extractor.seekTo(startMs * 1000L, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024 * 256);
            android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();

            while (true) {
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs > endMs * 1000L) break;

                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = sampleTimeUs - startMs * 1000L;
                bufferInfo.flags = extractor.getSampleFlags();

                muxer.writeSampleData(muxerTrack, buffer, bufferInfo);
                extractor.advance();
            }

            muxer.stop();
            muxer.release();
            extractor.release();

            return outputFile;
        } catch (Exception e) {
            Log.w(TAG, "Audio clipping failed", e);
            return null;
        }
    }

    /**
     * Create a clipped MP3 file by writing raw audio frames directly.
     * MP3 frames are self-contained, so concatenating them produces a valid MP3 file.
     */
    private File createClippedMp3Raw(android.media.MediaExtractor extractor,
                                      String safeName, File ringtoneDir,
                                      int startMs, int endMs) {
        File outputFile = new File(ringtoneDir, safeName + ".mp3");
        try {
            extractor.seekTo(startMs * 1000L, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024 * 256);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outputFile);

            while (true) {
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs > endMs * 1000L) break;

                byte[] data = new byte[sampleSize];
                buffer.position(0);
                buffer.get(data, 0, sampleSize);
                fos.write(data);
                extractor.advance();
            }

            fos.close();
            extractor.release();
            return outputFile;
        } catch (Exception e) {
            Log.w(TAG, "MP3 raw clipping failed", e);
            extractor.release();
            if (outputFile.exists()) outputFile.delete();
            return null;
        }
    }

    // Keep the old method for backward compatibility
    private void setRingtoneFromFile(File file, String title) {
        setRingtoneFromFile(file, title, 0, 0);
    }

    // ==================== Sleep Timer ====================

    private void onFuncSleepTimer() {
        dismissOverlay();
        if (playerManager.isSleepTimerActive()) {
            // Timer is active - show remaining time and option to cancel
            showSleepTimerStatus();
        } else {
            // No timer - show options to set one
            showSleepTimerOptions();
        }
    }

    /** 定时关闭模式的短标签。 */
    private static String sleepTimerModeLabel(int mode) {
        switch (mode) {
            case 1:
                return "播放净时长";
            case 2:
                return "放完当前曲";
            default:
                return "总时长";
        }
    }

    /** 模式的补充说明（切换时用 Toast 说明差别，避免「净时长 / 放完当前曲」看不明白）。 */
    private static String sleepTimerModeHint(int mode) {
        switch (mode) {
            case 1:
                return "（只在播放时计时，暂停不计）";
            case 2:
                return "（到点后等这首放完再停）";
            default:
                return "（到点立刻停）";
        }
    }

    private void showSleepTimerOptions() {
        FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);

        overlayContainer = new FrameLayout(this);
        overlayContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayContainer.setBackgroundColor(0xCC000000);
        addSwipeToDismiss(overlayContainer);

        ScrollView scrollView = new ScrollView(this);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.gravity = Gravity.CENTER;
        scrollView.setLayoutParams(scrollParams);
        scrollView.setOnClickListener(v -> { /* consume click */ });

        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER);
        contentLayout.setPadding(dp(20), dp(20), dp(20), dp(20));

        // Title bar with close button
        contentLayout.addView(createOverlayTitleBar("定时关闭"));

        // 模式行：直接在这里切「总时长 / 播放净时长 / 放完当前曲」——
        // 以前只能在设置页改，菜单里看不到当前模式，很容易「以为选了放完当前曲、其实是总时长」。
        final SharedPreferences timerPrefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        final TextView modeBtn = new TextView(this);
        modeBtn.setTextColor(0xFFBB86FC);
        modeBtn.setTextSize(13);
        modeBtn.setGravity(Gravity.CENTER);
        modeBtn.setPadding(0, dp(10), 0, dp(10));
        modeBtn.setBackgroundColor(0xFF2D2D2D);
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        modeParams.bottomMargin = dp(8);
        modeBtn.setLayoutParams(modeParams);
        modeBtn.setClickable(true);
        modeBtn.setFocusable(true);
        final java.util.function.IntSupplier modeSupplier = () ->
                timerPrefs.getInt("sleep_timer_mode", 0);
        modeBtn.setText("模式：" + sleepTimerModeLabel(modeSupplier.getAsInt()) + "（点按切换）");
        modeBtn.setOnClickListener(v -> {
            int next = (modeSupplier.getAsInt() + 1) % 3;
            timerPrefs.edit().putInt("sleep_timer_mode", next).apply();
            modeBtn.setText("模式：" + sleepTimerModeLabel(next) + "（点按切换）");
            Toast.makeText(this, "定时模式：" + sleepTimerModeLabel(next)
                    + sleepTimerModeHint(next), Toast.LENGTH_LONG).show();
        });
        contentLayout.addView(modeBtn);

        // Preset timer options
        int[] minutes = {5, 10, 20, 30};
        for (int min : minutes) {
            TextView btn = new TextView(this);
            btn.setText(min + " 分钟");
            btn.setTextColor(0xFFFFFFFF);
            btn.setTextSize(14);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(0, dp(10), 0, dp(10));
            btn.setBackgroundColor(0xFF2D2D2D);
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnParams.bottomMargin = dp(4);
            btn.setLayoutParams(btnParams);
            btn.setClickable(true);
            btn.setFocusable(true);
            int finalMin = min;
            btn.setOnClickListener(v -> {
                playerManager.startSleepTimer(finalMin);
                Toast.makeText(this, finalMin + "分钟后自动停止播放", Toast.LENGTH_SHORT).show();
                dismissOverlay();
            });
            contentLayout.addView(btn);
        }

        // Custom seconds label
        TextView customLabel = new TextView(this);
        customLabel.setText("自定义（秒）");
        customLabel.setTextColor(0xB3FFFFFF);
        customLabel.setTextSize(13);
        customLabel.setGravity(Gravity.CENTER);
        customLabel.setPadding(0, dp(12), 0, dp(4));
        contentLayout.addView(customLabel);

        // Custom seconds input row
        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        customRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText etSeconds = new EditText(this);
        etSeconds.setHint("秒数");
        etSeconds.setTextColor(0xFFFFFFFF);
        etSeconds.setHintTextColor(0xFF888888);
        etSeconds.setTextSize(14);
        etSeconds.setInputType(InputType.TYPE_CLASS_NUMBER);
        etSeconds.setBackgroundColor(0xFF2D2D2D);
        etSeconds.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        etParams.rightMargin = dp(4);
        etSeconds.setLayoutParams(etParams);
        customRow.addView(etSeconds);

        TextView btnCustom = new TextView(this);
        btnCustom.setText("开始");
        btnCustom.setTextColor(0xFFFFFFFF);
        btnCustom.setTextSize(14);
        btnCustom.setGravity(Gravity.CENTER);
        btnCustom.setPadding(dp(12), dp(8), dp(12), dp(8));
        btnCustom.setBackgroundColor(0xFFBB86FC);
        btnCustom.setClickable(true);
        btnCustom.setFocusable(true);
        btnCustom.setOnClickListener(v -> {
            String input = etSeconds.getText().toString().trim();
            if (input.isEmpty()) {
                Toast.makeText(this, "请输入秒数", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                int seconds = Integer.parseInt(input);
                if (seconds <= 0) {
                    Toast.makeText(this, "请输入大于0的秒数", Toast.LENGTH_SHORT).show();
                    return;
                }
                playerManager.startSleepTimerSeconds(seconds);
                Toast.makeText(this, seconds + "秒后自动停止播放", Toast.LENGTH_SHORT).show();
                dismissOverlay();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
            }
        });
        customRow.addView(btnCustom);

        contentLayout.addView(customRow);

        scrollView.addView(contentLayout);
        overlayContainer.addView(scrollView);
        rootView.addView(overlayContainer);
    }

    private void showSleepTimerStatus() {
        FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);

        overlayContainer = new FrameLayout(this);
        overlayContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayContainer.setBackgroundColor(0xCC000000);
        addSwipeToDismiss(overlayContainer);

        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER);
        contentLayout.setPadding(dp(20), dp(20), dp(20), dp(20));
        contentLayout.setOnClickListener(v -> { /* consume click */ });

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        contentParams.gravity = Gravity.CENTER;
        contentLayout.setLayoutParams(contentParams);

        // Title bar with close button
        contentLayout.addView(createOverlayTitleBar("定时关闭"));

        // Remaining time display
        TextView tvRemaining = new TextView(this);
        tvRemaining.setTextColor(0xFFFFFFFF);
        tvRemaining.setTextSize(20);
        tvRemaining.setGravity(Gravity.CENTER);
        tvRemaining.setPadding(0, dp(8), 0, dp(4));
        contentLayout.addView(tvRemaining);

        // 当前模式 + 「放完当前曲」的待停状态
        int timerMode = getSharedPreferences("music163_settings", MODE_PRIVATE)
                .getInt("sleep_timer_mode", 0);
        TextView tvTimerMode = new TextView(this);
        tvTimerMode.setTextColor(0xB3FFFFFF);
        tvTimerMode.setTextSize(12);
        tvTimerMode.setGravity(Gravity.CENTER);
        tvTimerMode.setPadding(0, 0, 0, dp(12));
        tvTimerMode.setText("模式：" + sleepTimerModeLabel(timerMode)
                + (playerManager.isSleepTimerStopAfterCurrentPending()
                        ? " · 本曲结束后停止" : ""));
        contentLayout.addView(tvTimerMode);

        // Update remaining time every second
        final Handler timerDisplayHandler = new Handler();
        Runnable timerDisplayRunnable = new Runnable() {
            @Override
            public void run() {
                if (overlayContainer == null) return;
                long remainMs = playerManager.getSleepTimerRemainingMs();
                if (remainMs > 0) {
                    int totalSec = (int) (remainMs / 1000);
                    int min = totalSec / 60;
                    int sec = totalSec % 60;
                    tvRemaining.setText(String.format("剩余 %d:%02d", min, sec));
                    timerDisplayHandler.postDelayed(this, 1000);
                } else {
                    tvRemaining.setText("定时已结束");
                }
            }
        };
        timerDisplayRunnable.run();

        // Cancel button
        TextView btnCancel = new TextView(this);
        btnCancel.setText("取消定时");
        btnCancel.setTextColor(0xFFFFFFFF);
        btnCancel.setTextSize(14);
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setPadding(0, dp(10), 0, dp(10));
        btnCancel.setBackgroundColor(0xFFBB86FC);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnCancel.setLayoutParams(cancelParams);
        btnCancel.setClickable(true);
        btnCancel.setFocusable(true);
        btnCancel.setOnClickListener(v -> {
            playerManager.cancelSleepTimer();
            timerDisplayHandler.removeCallbacksAndMessages(null);
            Toast.makeText(this, "已取消定时", Toast.LENGTH_SHORT).show();
            dismissOverlay();
        });
        contentLayout.addView(btnCancel);

        overlayContainer.addView(contentLayout);
        rootView.addView(overlayContainer);
    }

    // ==================== UI Updates ====================

    private void updateUI() {
        Song song = playerManager.getCurrentSong();
        if (song != null) {
            tvSongName.setText(song.getDisplayName());
            tvArtist.setText(song.getArtist());
            ensureChorusLoaded(song);
        } else {
            tvSongName.setText(R.string.no_song);
            tvArtist.setText("");
            clearChorusInfo();
        }
        btnPlay.setImageResource(playerManager.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        // btnFuncMore image is static in layout
        // Update playlist indicator visibility
        updatePlaylistIndicator();
        updateChorusMarker(playerManager.getDuration());
        adjustTitleGravity();
    }

    // ==================== 主界面歌名「自绘跑马灯」 ====================
    //
    // 为什么放弃系统 ellipsize="marquee"：
    //   系统 marquee 的滚动偏移由 TextView 内部持有，**只要经历一次布局就会被重置回行首**。
    //   主界面在播放时，进度条与时间标签每 500ms 刷新一次（setText 必然 requestLayout），
    //   于是歌名「滚一小段就跳回开头」—— 实机抓帧已证实：时间在走时歌名反复回弹到开头，
    //   时间冻结（暂停）时则能一路平滑滚完。
    // 修法：
    //   自己持有偏移并用 scrollTo() 每帧推进。布局把 scrollX 冲掉也无所谓，下一帧就补回来。
    //   文本放得下时仍用 gravity=center 居中（短歌名不滚动）。

    /** 每帧间隔（≈60fps）。 */
    private static final int MARQUEE_TICK_MS = 16;
    /** 滚动速度（像素/秒），与系统 marquee 观感接近（实机测约 40px/s）。 */
    private static final float MARQUEE_SPEED_PX_PER_SEC = 40f;
    /** 首轮开始前的停顿，让人先看清歌名开头。 */
    private static final long MARQUEE_HOLD_BEFORE_START_MS = 1200L;

    private final Handler marqueeHandler = new Handler(Looper.getMainLooper());
    private Runnable marqueeRunnable;
    /** 当前滚动偏移（可负：负值表示文本从右侧尚未进场）。自持，不被布局冲掉。 */
    private float marqueeX = 0f;
    /** 文本超出可视宽度的像素数；<=0 表示放得下、无需滚动。 */
    private int marqueeOverflow = 0;
    private long marqueeLastTick = 0L;
    private long marqueeHoldUntil = 0L;

    /**
     * 主界面歌名用**系统 marquee**（单行滚动显示完整歌名）。
     *
     * <p>它唯一的"敌人"是重新布局：{@code TextView} 宽度为 {@code wrap_content} 时每次
     * {@code setText} 都会 requestLayout，而 marquee 的偏移量会被布局重置回行首
     * （播放中时间标签每 500ms 刷新一次 → 歌名"滚一小段跳回开头"）。</p>
     *
     * <p>配套改动：时间标签换成 {@code widget/StableWidthTextView} —— 文本宽度没变时
     * 只重绘、不冒泡布局，因此 marquee 不会被重置；同时它仍是自适应宽度，不会挤压进度条。</p>
     */
    private void setupMarquee() {
        if (tvSongName == null) {
            return;
        }
        tvSongName.setSingleLine(true);
        tvSongName.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        tvSongName.setMarqueeRepeatLimit(-1);
        tvSongName.setSelected(true);   // marquee 需要 selected/聚焦状态才会滚
        // D7 观测（低噪声金丝雀）：**系统 marquee 只在发生布局时才会把偏移重置回行首**，
        // 所以这里只在「真的发生布局」时记一行 —— 播放期间若一行都没有，就说明每秒刷新
        // （进度条 / 时间标签）没有触发布局，歌名不会跳回开头。
        // 正常情况几首歌下来都不应该出现；一旦出现，说明又有东西在周期性 requestLayout。
        tvSongName.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) ->
                com.qinghe.music163pro.util.MusicLog.i("Marquee",
                        "歌名发生布局（会重置 marquee 偏移）: w=" + (r - l)));
    }

    /** 启动跑马灯（进入前台时）。系统 marquee 全靠 selected 状态。 */
    private void startMarquee() {
        if (tvSongName != null) {
            tvSongName.setSelected(true);
        }
    }

    /** 停止跑马灯（离开前台时）：取消选中，避免后台空转重绘。 */
    private void stopMarquee() {
        if (tvSongName != null) {
            tvSongName.setSelected(false);
        }
    }

    /** 歌名变化 / 重新进入页面时：重新触发一次 marquee（从行首开始滚）。 */
    private void refreshMarquee() {
        if (tvSongName == null) {
            return;
        }
        tvSongName.setSelected(false);
        tvSongName.post(() -> tvSongName.setSelected(true));
    }

    /** 旧入口保留：语义已改为「重算并重置跑马灯」。 */
    private void adjustTitleGravity() {
        refreshMarquee();
    }

    /**
     * M3：原「左上角歌单按钮」的能力入口（现由「更多功能」菜单的「播放列表」项调用）。
     *
     * <p>三种来源各走各的，与改造前左上角按钮的行为**完全一致**，只是入口换到了菜单里：</p>
     * <ul>
     *   <li>B 站视频 → 打开该视频自带的列表；</li>
     *   <li>有来源歌单（云端歌单 / 专辑 / 排行榜等）→ 打开该歌单详情页；</li>
     *   <li>其余（本地歌、无来源歌单的云歌）→ 展示当前播放队列。</li>
     * </ul>
     *
     * <p>最后那条是**新增的兜底**：原来左上角按钮在这两种情况下什么都不做，
     * 但菜单项点了没反应会显得坏了，所以统一退化成「当前播放队列」。</p>
     */
    private void openCurrentPlaylistOrQueue() {
        dismissOverlay();
        Song currentSong = playerManager.getCurrentSong();
        if (currentSong != null && currentSong.isBilibili()) {
            openBilibiliPlaylist(currentSong);
            return;
        }
        if (playerManager.hasSourcePlaylist()) {
            Intent plIntent = new Intent(MainActivity.this, PlaylistDetailActivity.class);
            plIntent.putExtra("playlist_id", playerManager.getSourcePlaylistId());
            plIntent.putExtra("playlist_name", playerManager.getSourcePlaylistName());
            plIntent.putExtra("track_count", playerManager.getSourcePlaylistTrackCount());
            plIntent.putExtra("creator", playerManager.getSourcePlaylistCreator());
            plIntent.putExtra("creator_user_id", playerManager.getSourcePlaylistCreatorUserId());
            plIntent.putExtra("is_liked_playlist", playerManager.getSourcePlaylistIsLiked());
            startActivity(plIntent);
            return;
        }
        // 本地歌 / 无来源歌单：退回「当前播放队列」
        showPlaylistOverlay();
    }

    private void updatePlaylistIndicator() {
        // M3：左上角歌单按钮已移除，显隐逻辑随之作废（能力迁到 openCurrentPlaylistOrQueue）。
    }

    @Override
    public void onSongChanged(Song song) {
        tvSongName.setText(song.getDisplayName());
        tvArtist.setText(song.getArtist());
        // B 站曲目开播也记一次历史：从收藏 / 历史 直接点播时不经过 BilibiliPlaylistActivity，
        // 只在那里记会漏掉这些路径。BV 号去重，重复打开只会刷新时间与标题。
        if (song != null && song.isBilibili()
                && !android.text.TextUtils.isEmpty(song.getBvid())) {
            com.qinghe.music163pro.manager.BilibiliHistoryManager
                    .record(this, song.getBvid(), song.getName(), song.getArtist());
        }
        adjustTitleGravity();
        ensureChorusLoaded(song);
        startPlaybackService(song.getName(), song.getArtist(), song.getCoverUrl(), true);
        // Save to play history
        HistoryManager.getInstance().addToHistory(song);
        // M2：歌词页顶栏歌名随歌曲切换更新；标记「歌词需要重拉」
        if (tvLyricsTitle != null) {
            tvLyricsTitle.setText(song.getDisplayName());
        }
        loadedLyricsSongKey = null;
        // 正在显示歌词页时：重拉新歌的歌词
        if (lyricsOverlayShowing && lyricsContainer != null && tvLyricsTimeRef != null) {
            // Stop current sync
            lyricsScrollHandler.removeCallbacksAndMessages(null);
            lyricsScrollRunnable = null;
            lyricLines.clear();
            lyricViews.clear();
            translationMap.clear();
            currentTlyricText = null;
            currentHighlightIndex = -1;
            // Update song name label
            if (tvLyricsSongLabel != null) {
                tvLyricsSongLabel.setText(song.getDisplayName() + " - " + song.getArtist());
            }
            // Hide translation button until we know if new song has translation
            if (btnTranslationToggle != null) {
                btnTranslationToggle.setVisibility(View.GONE);
            }
            // Reload lyrics for new song
            loadLyricsForOverlay(song, tvLyricsTimeRef);
        }
        updateCoverBackground();
    }

    /**
     * In MODE_COVER the background always follows the current song's album
     * cover (kept in memory, never saved to disk). It is (re)loaded whenever
     * the screen is shown, regardless of playback state; if the cover cannot
     * be fetched the default background is used.
     */
    private void updateCoverBackground() {
        if (!BackgroundUtil.isCoverMode(BackgroundUtil.getMode(this))) {
            return;
        }
        final View root = getWindow().getDecorView().findViewById(android.R.id.content);
        Song song = playerManager.getBackgroundSong();
        final String cover = song != null && song.getCoverUrl() != null ? song.getCoverUrl() : "";
        if (!cover.isEmpty()) {
            if (BackgroundUtil.hasCoverBackground(cover)) {
                BackgroundUtil.applyBackground(this, root);
            } else {
                // 封面优先模式：封面还在异步加载时，先用自定义图（若存在）垫着，
                // 避免空窗；封面就绪后会再调一次 applyBackground 顶上来。
                if (BackgroundUtil.MODE_COVER_OR_CUSTOM.equals(BackgroundUtil.getMode(this))) {
                    BackgroundUtil.applyBackground(this, root);
                }
                BackgroundUtil.loadCoverBackground(this, cover,
                        () -> BackgroundUtil.applyBackground(this, root));
            }
            return;
        }
        // No cover url yet: try to fetch it from the API by song id.
        if (song != null && song.getId() > 0) {
            fetchCoverForBackground(song);
        } else {
            BackgroundUtil.clearCoverBackground();
            BackgroundUtil.applyBackground(this, root);
        }
    }

    private void fetchCoverForBackground(final Song song) {
        final long songId = song.getId();
        if (coverFetchInFlightId == songId) {
            return;
        }
        coverFetchInFlightId = songId;
        final View root = getWindow().getDecorView().findViewById(android.R.id.content);
        MusicApiHelper.fetchSongsDetails(java.util.Collections.singletonList(songId),
                playerManager.getCookie(), new MusicApiHelper.BatchSongDetailsCallback() {
                    @Override
                    public void onResult(java.util.Map<Long, Song> songMap) {
                        coverFetchInFlightId = -1;
                        Song detail = songMap != null ? songMap.get(songId) : null;
                        String fetched = detail != null ? detail.getCoverUrl() : null;
                        if (fetched != null && !fetched.isEmpty()) {
                            song.setCoverUrl(fetched);
                            playerManager.savePlaybackState();
                            updateCoverBackground();
                        } else {
                            BackgroundUtil.clearCoverBackground();
                            BackgroundUtil.applyBackground(MainActivity.this, root);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        coverFetchInFlightId = -1;
                        BackgroundUtil.clearCoverBackground();
                        BackgroundUtil.applyBackground(MainActivity.this, root);
                    }
                });
    }

    @Override
    public void onPlayStateChanged(boolean isPlaying) {
        btnPlay.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        if (isPlaying) {
            startSeekBarUpdate();
        } else {
            stopSeekBarUpdate();
        }
        // Always update notification with current play state
        Song song = playerManager.getCurrentSong();
        String name = song != null ? song.getName() : "";
        String artist = song != null ? song.getArtist() : "";
        String coverUrl = song != null ? song.getCoverUrl() : "";
        startPlaybackService(name, artist, coverUrl, isPlaying);
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onSleepTimerTriggered(boolean exitApp) {
        Toast.makeText(this,
                exitApp ? "定时结束，已退出应用" : "定时结束，已停止播放",
                Toast.LENGTH_SHORT).show();
        if (exitApp) {
            dismissOverlay();
            moveTaskToBack(true);
            finishAffinity();
        }
    }

    @Override
    public void onSleepTimerPendingStop() {
        // 「放完当前曲再停」：倒计时到点了，但这一首还没结束
        Toast.makeText(this, "定时已到，本曲结束后将停止播放", Toast.LENGTH_LONG).show();
    }

    private final Runnable seekBarUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isUserSeeking && playerManager.isPlaying()) {
                int current = playerManager.getCurrentPosition();
                int duration = playerManager.getDuration();
                if (duration > 0) {
                    seekBar.setMax(1000);
                    seekBar.setProgress((int) (1000L * current / duration));
                    tvCurrentTime.setText(formatTime(current));
                    tvTotalTime.setText(formatTime(duration));
                    updateChorusMarker(duration);
                    // 播放满 30 秒给服务端打卡（每曲一次），让听歌记录/排行与账号同步
                    playerManager.reportPlayIfNeeded();
                }
            }
            seekHandler.postDelayed(this, 500);
        }
    };

    private void startSeekBarUpdate() {
        seekHandler.removeCallbacks(seekBarUpdateRunnable);
        seekHandler.post(seekBarUpdateRunnable);
    }

    private void stopSeekBarUpdate() {
        seekHandler.removeCallbacks(seekBarUpdateRunnable);
    }

    private void startPlaybackService(String songName, String artist, String coverUrl, boolean isPlaying) {
        Intent serviceIntent = new Intent(this, MusicPlaybackService.class);
        serviceIntent.putExtra("song_name", songName);
        serviceIntent.putExtra("artist", artist);
        serviceIntent.putExtra("cover_url", coverUrl != null ? coverUrl : "");
        serviceIntent.putExtra("is_playing", isPlaying);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        serviceStarted = true;
    }

    private String formatTime(int ms) {
        int totalSeconds = ms / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + String.format("%02d", seconds);
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 让贴在某条屏幕边上的 View 避让物理圆角：把它离该边的外边距撑到 {@code corner} 以上。
     * 用于主页面左上 / 右上两个图标（原 4dp 边距会落进圆角切除区被切掉）。
     *
     * <p>左右侧由调用方<b>显式声明</b>（{@code alignEnd}）：不要再查
     * {@code getRule(ALIGN_PARENT_END)} —— RelativeLayout 布局时会把 END 规则归一化，
     * 运行时查不到，导致右边图标走了左侧分支、边距加错边，两角图标看起来不对称。</p>
     */
    private void applyCornerInset(View v, int corner, boolean alignEnd) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof RelativeLayout.LayoutParams)) {
            return;
        }
        RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) lp;
        p.topMargin = Math.max(p.topMargin, corner);
        if (alignEnd) {
            p.rightMargin = Math.max(p.rightMargin, corner);
        } else {
            p.leftMargin = Math.max(p.leftMargin, corner);
        }
        v.setLayoutParams(p);
    }

    // ==================== Page Indicator ====================

    private void ensurePageIndicator() {
        if (pageIndicatorLayout != null) return;
        FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);

        pageIndicatorLayout = new android.widget.LinearLayout(this);
        pageIndicatorLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        pageIndicatorLayout.setGravity(Gravity.CENTER);
        pageIndicatorLayout.setClickable(false);
        pageIndicatorLayout.setFocusable(false);

        int iconSize = dp(9);
        int margin = dp(4);

        ivDotPlayer = new android.widget.ImageView(this);
        ivDotPlayer.setImageResource(R.drawable.ic_indicator_player);
        android.widget.LinearLayout.LayoutParams pParams =
                new android.widget.LinearLayout.LayoutParams(iconSize, iconSize);
        pParams.setMarginEnd(margin);
        ivDotPlayer.setLayoutParams(pParams);
        ivDotPlayer.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        pageIndicatorLayout.addView(ivDotPlayer);

        ivDotLyrics = new android.widget.ImageView(this);
        ivDotLyrics.setImageResource(R.drawable.ic_indicator_lyrics);
        ivDotLyrics.setLayoutParams(new android.widget.LinearLayout.LayoutParams(iconSize, iconSize));
        ivDotLyrics.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        pageIndicatorLayout.addView(ivDotLyrics);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(4);
        pageIndicatorLayout.setLayoutParams(params);

        rootView.addView(pageIndicatorLayout);
        updatePageIndicator(false);
    }

    private void updatePageIndicator(boolean lyricsPage) {
        if (ivDotPlayer == null || ivDotLyrics == null) return;
        if (lyricsPage) {
            ivDotPlayer.setColorFilter(0x55FFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);  // gray = inactive
            ivDotLyrics.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);  // white = active
        } else {
            ivDotPlayer.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);  // white = active
            ivDotLyrics.setColorFilter(0x55FFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);  // gray = inactive
        }
    }

    /**
     * Override onBackPressed to intercept the system back gesture on watches.
     * On 小天才 watches, right-swipe triggers onBackPressed. We only allow exit
     * when no overlay is showing (i.e., on the main player screen).
     * When an overlay (lyrics, functions, etc.) is visible, dismiss it instead.
     */
    @Override
    public void onBackPressed() {
        if (overlayContainer != null) {
            dismissOverlay();
            return;
        }
        // M2：歌词页上按返回键先回播放页（ViewPager 第 0 页），不直接退出应用
        if (lyricsOverlayShowing) {
            gotoPlayerPage();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // M5：任何入口（FLAG_ACTIVITY_CLEAR_TOP | SINGLE_TOP）跳回主界面都落在播放页（第 0 页）
        gotoPlayerPage();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRingtonePreview();
        // 后台不移位；回前台 onResume 会按开关重新调度
        oledShiftHandler.removeCallbacks(oledShiftTick);
        lyricsScrollHandler.removeCallbacksAndMessages(null);
        // 自绘跑马灯计时器：后台停掉，避免空转
        stopMarquee();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRingtonePreview();
        stopSeekBarUpdate();
        lyricsScrollHandler.removeCallbacksAndMessages(null);
        volumeHandler.removeCallbacksAndMessages(null);
        dismissVolumeIndicator();
        stopMarquee();
        NetworkImageLoader.cancelAll();
    }
}
