package com.qinghe.music163pro.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.BilibiliApiHelper;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.BackgroundUtil;
import com.qinghe.music163pro.util.LyricSettings;
import com.qinghe.music163pro.util.LyricsCopyController;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Displays scrolling lyrics synchronized with music playback.
 * Reads lyrics from local .lrc file if available, otherwise fetches from API.
 *
 * Supports two scroll modes (configurable in settings):
 *  - 每行 (LYRIC_MODE_FOLLOW=0): always auto-scrolls to current line
 *  - 阻塞 (LYRIC_MODE_BLOCK=1): pauses auto-scroll when user scrolls;
 *    resumes after configured interval seconds; double-tap to seek to that line
 */
public class LyricsActivity extends BaseWatchActivity {

    private static final String PREFS_NAME = "music163_settings";
    private static final Pattern LRC_PATTERN = Pattern.compile("\\[(\\d{1,3}):(\\d{2})\\.?(\\d{0,3})\\](.*)");

    /** 沉浸式歌词字号：常态 15sp，当前行 17sp，译文 12sp。 */
    private static final float LYRIC_FONT_NORMAL = 15f;
    private static final float LYRIC_FONT_HIGHLIGHT = 17f;
    private static final float LYRIC_FONT_TRANSLATION = 12f;

    private static final int LYRIC_MODE_FOLLOW = 0;
    private static final int LYRIC_MODE_BLOCK  = 1;

    private ScrollView svLyrics;
    private LinearLayout llContainer;
    private TextView tvSongName;
    private TextView tvTime;
    private MusicPlayerManager playerManager;

    // AI 歌词（高级歌词翻译）：全屏页跟主播放页用同一套着色/缓存
    private TextView btnAiToggle;
    private com.qinghe.music163pro.model.AiLyricResult aiLyricResult;
    private boolean aiLyricActive;
    private boolean aiLyricLoading;
    private boolean aiPrepared;
    private String aiLyricSongKey;

    private final List<LyricLine> lyricLines = new ArrayList<>();
    private final List<TextView> lyricViews = new ArrayList<>();
    private int currentHighlightIndex = -1;
    /** 歌词页根容器（RelativeLayout）：复制模式的操作条挂到它顶部。 */
    private RelativeLayout lyricsRoot;
    /** 长按 1 秒进入的歌词多选复制控制器（全屏歌词页实例）。 */
    private LyricsCopyController lyricsCopy;

    private final Handler scrollHandler = new Handler();
    private Runnable scrollRunnable;
    private GestureDetector lyricsGestureDetector;
    private int touchSlop;
    private boolean touchStartedInLyricsView = false;
    private float touchDownRawX = 0f;
    private float touchDownRawY = 0f;

    // Blocking mode fields
    private int lyricScrollMode = LYRIC_MODE_BLOCK;
    private int lyricResumeIntervalMs = 3000;
    private boolean userScrolled = false;
    private long lastUserScrollTime = 0L;

    private static class LyricLine {
        long timeMs;
        String text;
        /** 官方译文（沉浸页也显示译文；没有则为空）。 */
        String translation;

        LyricLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lyrics);
        View rootView = findViewById(R.id.lyrics_root);
        lyricsRoot = (RelativeLayout) rootView;
        BackgroundUtil.applyBackground(this, rootView);

        // Apply keep screen on setting
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Read lyric scroll settings
        lyricScrollMode = prefs.getInt("lyric_scroll_mode", LYRIC_MODE_BLOCK);
        int intervalSec = prefs.getInt("lyric_resume_interval", 3);
        if (intervalSec < 1) intervalSec = 1;
        lyricResumeIntervalMs = intervalSec * 1000;

        svLyrics = findViewById(R.id.sv_lyrics);
        llContainer = findViewById(R.id.ll_lyrics_container);
        tvSongName = findViewById(R.id.tv_lyrics_song_name);
        btnAiToggle = findViewById(R.id.btn_ai_toggle);
        btnAiToggle.setOnClickListener(v -> onAiToggleClicked());
        // 长按 = 丢掉本曲缓存并重新分析（与主播放页歌词层一致）
        btnAiToggle.setOnLongClickListener(v -> {
            reanalyzeCurrentSong();
            return true;
        });
        tvTime = findViewById(R.id.tv_lyrics_time);

        // 长按 1 秒进入歌词复制态：操作条盖在顶部歌名行上（ALIGN_PARENT_TOP，
        // 后添加的子 View 绘制在上层）。只观察触摸、不消费事件，双击与滚动照旧。
        lyricsCopy = new LyricsCopyController(this, svLyrics, llContainer,
                new LyricsCopyController.BarHost() {
                    @Override
                    public void attach(View bar) {
                        if (lyricsRoot == null) return;
                        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                                RelativeLayout.LayoutParams.MATCH_PARENT,
                                RelativeLayout.LayoutParams.WRAP_CONTENT);
                        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                        lyricsRoot.addView(bar, lp);
                    }

                    @Override
                    public void detach(View bar) {
                        if (lyricsRoot != null) {
                            lyricsRoot.removeView(bar);
                        }
                    }
                });

        tvSongName.setSelected(true); // enable marquee

        playerManager = MusicPlayerManager.getInstance();
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        Song song = playerManager.getCurrentSong();
        if (song == null) {
            com.qinghe.music163pro.util.UiKit.toast(this, "暂无歌曲");
            finish();
            return;
        }

        tvSongName.setText(com.qinghe.music163pro.util.SongDisplay.mainTitle(this, song) + " - " + song.getArtist());

        if (lyricScrollMode == LYRIC_MODE_BLOCK) {
            setupBlockingModeDetection();
        }

        loadLyrics(song);
    }

    private void setupBlockingModeDetection() {
        lyricsGestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (!isPointInsideView(e.getRawX(), e.getRawY(), svLyrics)) {
                            return false;
                        }
                        int idx = findLyricViewAtRawY(e.getRawY());
                        if (idx < 0 || idx >= lyricLines.size()) {
                            return false;
                        }
                        int seekMs = LyricSettings.seekPositionForLine(LyricsActivity.this,
                                lyricLines.get(idx).timeMs);
                        playerManager.seekTo(seekMs);
                        userScrolled = false;
                        lastUserScrollTime = 0L;
                        clearCurrentLyricHighlight();
                        scrollToLine(idx);
                        return true;
                    }
                });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (lyricsCopy != null) {
            lyricsCopy.onTouch(event);
        }
        // 复制态下跳过双击 detector 与「锁定模式滚动跟踪」，避免点选行时跳进度
        if (lyricsCopy == null || !lyricsCopy.isActive()) {
            handleBlockingModeTouch(event);
        }
        return super.dispatchTouchEvent(event);
    }

    private void handleBlockingModeTouch(MotionEvent event) {
        if (svLyrics == null || lyricLines.isEmpty()) {
            return;
        }

        // 2026-10-18（需求方）：双击歌词跳进度在任何滚动模式下都要生效 ——
        // 先把手势喂给 detector，再只为「锁定」模式处理滚动跟踪。
        if (lyricsGestureDetector != null) {
            lyricsGestureDetector.onTouchEvent(event);
        }

        if (lyricScrollMode != LYRIC_MODE_BLOCK) {
            return;
        }

        float rawX = event.getRawX();
        float rawY = event.getRawY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartedInLyricsView = isPointInsideView(rawX, rawY, svLyrics);
                touchDownRawX = rawX;
                touchDownRawY = rawY;
                break;
            case MotionEvent.ACTION_MOVE:
                if (touchStartedInLyricsView) {
                    float diffX = Math.abs(rawX - touchDownRawX);
                    float diffY = Math.abs(rawY - touchDownRawY);
                    if (diffY > touchSlop && diffY >= diffX) {
                        userScrolled = true;
                        lastUserScrollTime = System.currentTimeMillis();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchStartedInLyricsView = false;
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

    private int findLyricViewAtRawY(float rawY) {
        int nearestIndex = -1;
        float nearestDistance = Float.MAX_VALUE;
        int[] location = new int[2];
        for (int i = 0; i < lyricViews.size(); i++) {
            TextView tv = lyricViews.get(i);
            tv.getLocationOnScreen(location);
            float top = location[1];
            float bottom = top + tv.getHeight();
            if (rawY >= top && rawY <= bottom) {
                return i;
            }
            float center = top + tv.getHeight() / 2f;
            float distance = Math.abs(rawY - center);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = i;
            }
        }
        return nearestIndex;
    }

    /** 复制态优先消费返回：只退出复制态，不关闭全屏歌词页。 */
    @Override
    public void onBackPressed() {
        if (lyricsCopy != null && lyricsCopy.onBack()) return;
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        lyricsGestureDetector = null;
        scrollHandler.removeCallbacksAndMessages(null);
        if (lyricsCopy != null) lyricsCopy.release();
    }

    private void loadLyrics(Song song) {
        // Try to load from local .lrc file first
        String localLrc = loadLocalLrc(song);
        if (localLrc != null && !localLrc.isEmpty()) {
            parseLrc(localLrc);
            displayLyrics();
            startScrollSync();
            return;
        }

        // Handle Bilibili songs - fetch subtitle from Bilibili API
        if (song.isBilibili()) {
            loadBilibiliSubtitle(song);
            return;
        }

        // Fetch from API
        if (song.getId() <= 0) {
            showNoLyrics();
            return;
        }

        String cookie = playerManager.getCookie();
        MusicApiHelper.getLyricsWithTranslation(song.getId(), cookie,
                new MusicApiHelper.LyricsFullCallback() {
            @Override
            public void onResult(String lrcText, String tlyricText, String romalrcText) {
                if (lrcText == null || lrcText.isEmpty()) {
                    showNoLyrics();
                    return;
                }
                parseLrc(lrcText);
                parseTranslationLrc(tlyricText);
                displayLyrics();
                startScrollSync();
            }

            @Override
            public void onError(String message) {
                showNoLyrics();
            }
        });
    }

    /**
     * Load subtitle for a Bilibili video.
     */
    private void loadBilibiliSubtitle(Song song) {
        String bilibiliCookie = getSharedPreferences("music163_settings", MODE_PRIVATE)
                .getString("bilibili_cookie", "");

        BilibiliApiHelper.getSubtitleList(song.getBvid(), song.getCid(), bilibiliCookie,
                new BilibiliApiHelper.SubtitleListCallback() {
                    @Override
                    public void onResult(List<BilibiliApiHelper.SubtitleInfo> subtitles) {
                        if (subtitles.isEmpty()) {
                            showNoLyrics();
                            return;
                        }

                        BilibiliApiHelper.SubtitleInfo chosen = subtitles.get(0);
                        for (BilibiliApiHelper.SubtitleInfo info : subtitles) {
                            if (info.lan.startsWith("zh")) {
                                chosen = info;
                                break;
                            }
                        }

                        BilibiliApiHelper.getSubtitle(chosen.subtitleUrl,
                                new BilibiliApiHelper.SubtitleCallback() {
                                    @Override
                                    public void onResult(String lrcText) {
                                        if (lrcText == null || lrcText.isEmpty()) {
                                            showNoLyrics();
                                            return;
                                        }
                                        parseLrc(lrcText);
                                        displayLyrics();
                                        startScrollSync();
                                    }

                                    @Override
                                    public void onError(String message) {
                                        showNoLyrics();
                                    }
                                });
                    }

                    @Override
                    public void onError(String message) {
                        showNoLyrics();
                    }
                });
    }

    private String loadLocalLrc(Song song) {
        // 2026-10-17：优先读「与音频同目录、同名」的 sidecar .lrc（LAN 传歌上传歌词合并时生成）
        String sidecar = readSidecarLrc(song);
        if (sidecar != null && !sidecar.isEmpty()) {
            return sidecar;
        }
        try {
            String safeName = song.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
            String safeArtist = song.getArtist().replaceAll("[\\\\/:*?\"<>|]", "_");
            String folderName = safeName + " - " + safeArtist;
            File lrcFile = new File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "163Music/Download/" + folderName + "/lyrics.lrc"
            );
            if (!lrcFile.exists()) return null;

            try (FileInputStream fis = new FileInputStream(lrcFile);
                 InputStreamReader reader = new InputStreamReader(fis, "UTF-8")) {
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

    /** 读取与音频同目录同名的 sidecar .lrc（LAN 传歌上传歌词时生成）。 */
    private String readSidecarLrc(Song song) {
        try {
            if (song == null) {
                return null;
            }
            String path = song.getUrl();
            if (path == null || !path.startsWith("/") || path.contains("://")) {
                return null;
            }
            java.io.File audio = new java.io.File(path);
            if (!audio.isFile()) {
                return null;
            }
            String name = audio.getName();
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            java.io.File lrc = new java.io.File(audio.getParentFile(), stem + ".lrc");
            if (!lrc.isFile()) {
                return null;
            }
            try (java.io.FileInputStream fis = new java.io.FileInputStream(lrc);
                 java.io.InputStreamReader reader = new java.io.InputStreamReader(fis, "UTF-8")) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[2048];
                int len;
                while ((len = reader.read(buf)) != -1) {
                    sb.append(buf, 0, len);
                }
                return sb.toString().replace("\uFEFF", "");
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void parseLrc(String lrcText) {
        lyricLines.clear();
        String[] lines = lrcText.split("\n");
        for (String line : lines) {
            Matcher matcher = LRC_PATTERN.matcher(line.trim());
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

    /** 把翻译歌词按时间戳贴到对应行上（沉浸页也显示译文）。 */
    private void parseTranslationLrc(String tlyricText) {
        if (tlyricText == null || tlyricText.isEmpty()) {
            return;
        }
        java.util.Map<Long, String> map = new java.util.HashMap<>();
        for (String raw : tlyricText.split("\r?\n")) {
            java.util.regex.Matcher matcher = LRC_PATTERN.matcher(raw.trim());
            if (!matcher.find()) {
                continue;
            }
            try {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                String frac = matcher.group(3);
                long ms = minutes * 60_000 + seconds * 1000;
                if (frac != null && !frac.isEmpty()) {
                    ms += (long) (Double.parseDouble("0." + frac) * 1000);
                }
                String text = matcher.group(4) == null ? "" : matcher.group(4).trim();
                if (!text.isEmpty()) {
                    map.put(ms, text);
                }
            } catch (Exception ignored) {
            }
        }
        for (LyricLine line : lyricLines) {
            String translated = map.get(line.timeMs);
            if (translated != null) {
                line.translation = translated;
            }
        }
    }

    private void displayLyrics() {
        // 行要重建了：先退出复制态，否则选中下标会打到新行上
        if (lyricsCopy != null) lyricsCopy.exit();
        llContainer.removeAllViews();
        lyricViews.clear();

        if (lyricLines.isEmpty()) {
            showNoLyrics();
            return;
        }

        // 可选的文字底衬：在浅色或花哨的自定义背景上，白字经常会看不清
        final boolean backdropEnabled = LyricSettings.isBackdropEnabled(this);
        final int backdropPadding = backdropEnabled ? dp(8) : 0;

        for (LyricLine line : lyricLines) {
            // 沉浸式大字：每行 = 原文（大字）+ 译文（小字、更淡），底衬打在整个行容器上
            LinearLayout lineBox = new LinearLayout(this);
            lineBox.setOrientation(LinearLayout.VERTICAL);
            lineBox.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            lineBox.setPadding(backdropPadding, dp(6), backdropPadding, dp(6));
            if (backdropEnabled) {
                lineBox.setBackground(buildLyricBackdrop());
            }

            com.qinghe.music163pro.model.AiLyricResult.Line aiLine =
                    (aiLyricActive && aiLyricResult != null)
                            ? aiLyricResult.lineAt(lyricViews.size()) : null;

            TextView tv = new TextView(this);
            tv.setTextColor(0xB3FFFFFF);
            tv.setTextSize(LYRIC_FONT_NORMAL);
            tv.setGravity(android.view.Gravity.CENTER);
            if (aiLine != null) {
                tv.setText(com.qinghe.music163pro.util.LyricRichText.buildOriginal(
                        line.text, aiLine, 0xB3FFFFFF, LYRIC_FONT_NORMAL, this));
            } else {
                tv.setText(line.text);
            }
            lineBox.addView(tv);

            // 译文：官方优先；AI 开启且官方没有时用 AI 译文
            String effective = line.translation;
            if ((effective == null || effective.isEmpty()) && aiLine != null) {
                effective = aiLine.translation;
            }
            if (effective != null && !effective.isEmpty()) {
                TextView tvTrans = new TextView(this);
                tvTrans.setTextSize(LYRIC_FONT_TRANSLATION);
                tvTrans.setTextColor(0x8AFFFFFF);
                tvTrans.setGravity(android.view.Gravity.CENTER);
                tvTrans.setPadding(0, dp(2), 0, 0);
                if (aiLine != null) {
                    tvTrans.setText(com.qinghe.music163pro.util.LyricRichText.buildTranslation(
                            effective, aiLine, 0x8AFFFFFF, LYRIC_FONT_TRANSLATION, this));
                } else {
                    tvTrans.setText(effective);
                }
                lineBox.addView(tvTrans);
            }

            // No per-TextView touch listeners: double-tap and scroll detection
            // are handled at the ScrollView level in setupBlockingModeDetection().
            llContainer.addView(lineBox);
            lyricViews.add(tv);
        }
        // 行重建完成后刷新可复制文本（原文行，与屏幕主文本一致）
        if (lyricsCopy != null) lyricsCopy.setLines(aiPlainLyricLines());
        ensureAiPrepared();
    }

    /**
     * 歌词文字的底衬：半透明黑色圆角块。
     *
     * <p>这个页面每行就是一个独立的 TextView（不像播放页那样有外层容器），
     * 所以底衬直接打在 TextView 上。</p>
     */
    // ==================== AI 歌词（高级歌词翻译） ====================

    /** 歌词显示完成后准备 AI 数据：总开关开→分析（缓存优先）；关→只读缓存，绝不发请求。 */
    private void ensureAiPrepared() {
        if (aiPrepared || lyricLines.isEmpty()) {
            return;
        }
        aiPrepared = true;
        Song song = playerManager != null ? playerManager.getCurrentSong() : null;
        if (song == null) {
            return;
        }
        aiLyricSongKey = aiLyricSongKeyOf(song);
        // 先把按钮摆出来（默认「AI」灰字），缓存命中/分析完成后会更新状态；
        // 之前只在缓存命中时才刷状态，没缓存时按钮一直是 GONE，用户点不到。
        updateAiButton();
        final String lyricsKey = com.qinghe.music163pro.util.AiLyricAnalyzer.joinLyrics(
                aiPlainLyricLines());

        if (com.qinghe.music163pro.util.AiLyricSettings.isEnabled(this)) {
            aiLyricLoading = true;
            updateAiButton();
            runAiAnalysis(true);
            return;
        }
        new Thread(() -> {
            final com.qinghe.music163pro.model.AiLyricResult cached =
                    com.qinghe.music163pro.model.AiLyricResult.fromJson(
                            com.qinghe.music163pro.util.AiLyricCache.read(aiLyricSongKey, lyricsKey));
            if (cached != null) {
                runOnUiThread(() -> {
                    aiLyricResult = cached;
                    updateAiButton();
                });
            }
        }, "ai-lyric-cache-full").start();
    }

    private void runAiAnalysis(final boolean autoActivate) {
        Song song = playerManager != null ? playerManager.getCurrentSong() : null;
        if (song == null) {
            aiLyricLoading = false;
            updateAiButton();
            return;
        }
        com.qinghe.music163pro.util.AiLyricAnalyzer.analyze(this, aiLyricSongKey,
                song.getName(), song.getArtist(), song.getAlbum(),
                aiPlainLyricLines(), aiOfficialTranslations(),
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
                            redrawWithAi();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        aiLyricLoading = false;
                        updateAiButton();
                        if (autoActivate && com.qinghe.music163pro.util.AiLyricSettings.isEnabled(LyricsActivity.this)) {
                            Toast.makeText(LyricsActivity.this, "AI 歌词失败：" + message,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    /** 长按 AI：删除本曲缓存并重新分析。 */
    private void reanalyzeCurrentSong() {
        if (aiLyricLoading) {
            com.qinghe.music163pro.util.UiKit.toast(this, "AI 正在分析…");
            return;
        }
        if (!com.qinghe.music163pro.util.AiLyricSettings.hasApiKey(this)) {
            com.qinghe.music163pro.util.UiKit.toast(this, "请先填写 DeepSeek API Key");
            startActivity(new android.content.Intent(this, LanguageLearningActivity.class));
            return;
        }
        aiLyricActive = false;
        aiLyricResult = null;
        if (aiLyricSongKey != null && !lyricLines.isEmpty()) {
            java.io.File cache = com.qinghe.music163pro.util.AiLyricCache.fileFor(
                    aiLyricSongKey,
                    com.qinghe.music163pro.util.AiLyricAnalyzer.joinLyrics(aiPlainLyricLines()));
            if (cache != null && cache.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cache.delete();
            }
        }
        aiLyricLoading = true;
        updateAiButton();
        runAiAnalysis(true);
        com.qinghe.music163pro.util.UiKit.toast(this, "已清除本曲缓存，正在重新分析…");
    }

    private void onAiToggleClicked() {
        if (aiLyricLoading) {
            com.qinghe.music163pro.util.UiKit.toast(this, "AI 正在分析…");
            return;
        }
        if (aiLyricActive) {
            aiLyricActive = false;
            updateAiButton();
            redrawWithAi();
            return;
        }
        if (aiLyricResult != null) {
            aiLyricActive = true;
            updateAiButton();
            redrawWithAi();
            com.qinghe.music163pro.util.UiKit.toast(this, "已启用 AI 歌词");
            return;
        }
        if (!com.qinghe.music163pro.util.AiLyricSettings.hasApiKey(this)) {
            com.qinghe.music163pro.util.UiKit.toast(this, "请先填写 DeepSeek API Key");
            startActivity(new android.content.Intent(this, LanguageLearningActivity.class));
            return;
        }
        aiLyricLoading = true;
        updateAiButton();
        runAiAnalysis(true);
    }

    /** 重绘歌词并恢复当前行高亮。 */
    private void redrawWithAi() {
        int highlight = currentHighlightIndex;
        displayLyrics();
        if (highlight >= 0 && highlight < lyricViews.size()) {
            TextView view = lyricViews.get(highlight);
            view.setTextColor(0xFFFFFFFF);
            view.setTextSize(LYRIC_FONT_HIGHLIGHT);
            rebuildAiLineText(view, highlight, true);
            currentHighlightIndex = highlight;
        }
    }

    private void updateAiButton() {
        if (btnAiToggle == null) {
            return;
        }
        if (lyricLines.isEmpty()) {
            btnAiToggle.setVisibility(android.view.View.GONE);
            return;
        }
        btnAiToggle.setVisibility(android.view.View.VISIBLE);
        btnAiToggle.setTextSize(12);
        btnAiToggle.setPadding(dp(6), dp(4), dp(6), dp(4));
        if (aiLyricLoading) {
            applyAiButtonStyle("AI…", false);
        } else if (aiLyricActive) {
            applyAiButtonStyle("AI✓", true);
        } else {
            applyAiButtonStyle("AI", false);
        }
    }

    /** 与主播放页歌词层一致：纯文字按钮，只用颜色区分开关态。 */
    private void applyAiButtonStyle(String text, boolean on) {
        if (btnAiToggle == null) {
            return;
        }
        btnAiToggle.setText(text);
        btnAiToggle.setBackground(null);
        btnAiToggle.setTextColor(on ? com.qinghe.music163pro.util.UiKit.COLOR_ACCENT : 0x80FFFFFF);
    }

    /** 高亮切换时重建该行词块（字号 13/14 变化，span 内的字号必须跟着重建）。 */
    private void rebuildAiLineText(TextView view, int lineIndex, boolean highlighted) {
        if (!aiLyricActive || aiLyricResult == null || view == null
                || lineIndex < 0 || lineIndex >= lyricLines.size()) {
            return;
        }
        com.qinghe.music163pro.model.AiLyricResult.Line aiLine = aiLyricResult.lineAt(lineIndex);
        if (aiLine == null) {
            return;
        }
        view.setText(com.qinghe.music163pro.util.LyricRichText.buildOriginal(
                lyricLines.get(lineIndex).text, aiLine,
                highlighted ? 0xFFFFFFFF : 0xB3FFFFFF,
                highlighted ? LYRIC_FONT_HIGHLIGHT : LYRIC_FONT_NORMAL, this));
    }

    private String aiLyricSongKeyOf(Song song) {
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

    private java.util.List<String> aiPlainLyricLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (LyricLine line : lyricLines) {
            out.add(line.text == null ? "" : line.text);
        }
        return out;
    }

    /**
     * 全屏歌词页的 LyricLine 不带官方翻译（本页只显示原文），
     * 因此不向 AI 提供官方译文参考，返回 null 即可。
     */
    private java.util.List<String> aiOfficialTranslations() {
        return null;
    }

    private android.graphics.drawable.Drawable buildLyricBackdrop() {
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(LyricSettings.getBackdropColor());
        background.setCornerRadius(0);  // 整屏背景不参与圆角化（圆角会让四角露出下层底色）
        return background;
    }

    private void showNoLyrics() {
        // 「暂无歌词」占位行不是歌词行，复制态必须收掉并清空可复制文本
        if (lyricsCopy != null) {
            lyricsCopy.exit();
            lyricsCopy.setLines(null);
        }
        llContainer.removeAllViews();
        TextView tv = new TextView(this);
        tv.setText("暂无歌词");
        tv.setTextColor(0xB3FFFFFF);
        tv.setTextSize(14);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(0, dp(40), 0, 0);
        llContainer.addView(tv);
    }

    private void startScrollSync() {
        if (lyricLines.isEmpty()) return;

        scrollRunnable = new Runnable() {
            @Override
            public void run() {
                // 复制态下只保活计时器：不换高亮、不自动滚动，避免用户点选时歌词跳走
                if (lyricsCopy != null && lyricsCopy.isActive()) {
                    scrollHandler.postDelayed(this, 300);
                    return;
                }
                if (playerManager.isPlaying() || playerManager.getCurrentPosition() > 0) {
                    int currentPos = playerManager.getCurrentPosition();
                    int duration = playerManager.getDuration();

                    tvTime.setText(formatTime(currentPos) + " / " + formatTime(duration));

                    // In blocking mode, auto-unblock after the configured interval
                    if (lyricScrollMode == LYRIC_MODE_BLOCK && userScrolled) {
                        if (System.currentTimeMillis() - lastUserScrollTime >= lyricResumeIntervalMs) {
                            userScrolled = false;
                        }
                    }

                    int newIndex = findCurrentLyricIndex(currentPos);
                    if (newIndex != currentHighlightIndex && newIndex >= 0) {
                        // Unhighlight previous
                        clearCurrentLyricHighlight();
                        // Highlight current
                        currentHighlightIndex = newIndex;
                        if (currentHighlightIndex < lyricViews.size()) {
                            TextView currentView = lyricViews.get(currentHighlightIndex);
                            currentView.setTextColor(0xFFFFFFFF);
                            currentView.setTextSize(LYRIC_FONT_HIGHLIGHT);
                            rebuildAiLineText(currentView, newIndex, true);

                            // Auto-scroll: always in follow mode; only when not blocked in block mode
                            if (lyricScrollMode == LYRIC_MODE_FOLLOW || !userScrolled) {
                                scrollToLine(currentHighlightIndex);
                            }
                        }
                    }
                }
                scrollHandler.postDelayed(this, 300);
            }
        };
        scrollHandler.post(scrollRunnable);
    }

    /**
     * 找出当前该高亮的歌词下标。
     *
     * <p>歌词偏移在这里生效：传入的是原始播放进度，内部换算成参考点再比较。
     * 时间戳本身保持原样，这样与音译 / 翻译歌词按时间戳对齐的映射不会被破坏。</p>
     */
    private int findCurrentLyricIndex(int positionMs) {
        int index = -1;
        int referencePos = LyricSettings.referencePosition(this, positionMs);
        for (int i = 0; i < lyricLines.size(); i++) {
            if (lyricLines.get(i).timeMs <= referencePos) {
                index = i;
            } else {
                break;
            }
        }
        return index;
    }

    private void scrollToLine(int index) {
        if (index < 0 || index >= lyricViews.size()) return;
        final TextView target = lyricViews.get(index);
        target.post(() -> {
            int scrollViewHeight = svLyrics.getHeight();
            int targetTop = target.getTop();
            int targetHeight = target.getHeight();
            int scrollTo = targetTop - (scrollViewHeight / 2) + (targetHeight / 2);
            svLyrics.smoothScrollTo(0, Math.max(0, scrollTo));
        });
    }

    private void clearCurrentLyricHighlight() {
        if (currentHighlightIndex >= 0 && currentHighlightIndex < lyricViews.size()) {
            TextView currentView = lyricViews.get(currentHighlightIndex);
            currentView.setTextColor(0xB3FFFFFF);
            currentView.setTextSize(LYRIC_FONT_NORMAL);
            rebuildAiLineText(currentView, currentHighlightIndex, false);
        }
        currentHighlightIndex = -1;
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

    @Override
    protected void onResume() {
        super.onResume();
        if (scrollRunnable != null) {
            scrollHandler.post(scrollRunnable);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        scrollHandler.removeCallbacksAndMessages(null);
    }

}
