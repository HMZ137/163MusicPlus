package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.ThemeManager;
import com.qinghe.music163pro.util.UiKit;

/**
 * 设置分类页（需求 2026-11-21 重构）：一个页面按 {@link #EXTRA_PAGE} 渲染五类之一，
 * 避免为每类复制一整页代码。
 *
 * <p>page 取值：appearance / playback / interaction / data / about。</p>
 */
public class SettingsPageActivity extends BaseWatchActivity {

    public static final String EXTRA_PAGE = "settings_page";

    private LinearLayout container;
    private String page;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        page = getIntent().getStringExtra(EXTRA_PAGE);
        if (page == null) page = "appearance";
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        buildStartMs = android.os.SystemClock.uptimeMillis();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getResources().getColor(R.color.bg_dark));
        scroll.setFillViewport(true);
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(16));
        scroll.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        addTitle(titleOf(page));
        // 2026-11-22：分类引导简介 —— 完整显示，不截断
        addTitleIntro(introOf(page));
        if ("appearance".equals(page)) {
            buildAppearance();
        } else if ("playback".equals(page)) {
            buildPlayback();
        } else if ("interaction".equals(page)) {
            buildInteraction();
        } else if ("data".equals(page)) {
            buildData();
        } else {
            buildAbout();
        }
        // 2026-11-22：首次进入设置，提示简介可展开（5 秒内不可关闭）
        showExpandHintOnce();
        // 2026-11-22：卡顿定位 —— 项数越多越卡，先量化整段构建耗时
        com.qinghe.music163pro.util.MusicLog.i("SettingsPerf",
                "page=" + page + " 构建完成，rows=" + container.getChildCount()
                        + " 耗时=" + (android.os.SystemClock.uptimeMillis() - buildStartMs) + "ms");
    }

    private String titleOf(String p) {
        switch (p) {
            case "playback": return "播放与音质";
            case "interaction": return "交互与浮层";
            case "data": return "数据与工具";
            case "about": return "关于";
            default: return "外观与显示";
        }
    }

    // ==================== 存储键（2026-11-22 从 ToggleSettingsActivity 迁移） ====================

    private static final String PREFS_NAME = "music163_settings";
    private static final String PREF_RECOGNITION_MODE = "song_recognition_mode";
    private static final String PREF_LYRIC_SCROLL_MODE = "lyric_scroll_mode";
    private static final String PREF_LYRIC_RESUME_INTERVAL = "lyric_resume_interval";
    private static final String PREF_SLEEP_TIMER_EXIT_APP = "sleep_timer_exit_app";
    private static final String PREF_OLED_BURN_PROTECTION = "oled_burn_protection";
    private static final String PREF_SLEEP_TIMER_MODE = "sleep_timer_mode";
    private static final String PREF_FAV_MODE_CLOUD = "fav_mode_cloud";
    private static final String PREF_USE_WATCH_API = "use_watch_parse";
    private static final String PREF_SKIP_TRIAL_SONGS = "skip_trial_songs";
    private static final String PREF_RANDOM_SKIP_RANDOM = "random_skip_random";
    private static final int MODE_MANUAL = 0;
    private static final int MODE_AUTO = 1;
    private static final int LYRIC_MODE_FOLLOW = 0;
    private static final int LYRIC_MODE_BLOCK = 1;
    private static final int SLEEP_TIMER_TOTAL = com.qinghe.music163pro.player.MusicPlayerManager.SLEEP_TIMER_MODE_TOTAL;
    private static final int SLEEP_TIMER_NET = com.qinghe.music163pro.player.MusicPlayerManager.SLEEP_TIMER_MODE_NET;
    private static final int SLEEP_TIMER_FINISH_TRACK = com.qinghe.music163pro.player.MusicPlayerManager.SLEEP_TIMER_MODE_FINISH_TRACK;

    private android.content.SharedPreferences prefs;
    /** 2026-11-22：性能定位 —— 分类页整段构建耗时。 */
    private long buildStartMs;
    /** 2026-11-27：说明文字短于此字数就按「单行数据」处理（不折叠、不拦点击）。 */
    private static final int SHORT_DESC_MAX = UiKit.SHORT_DESC_MAX;

    // ==================== 外观与显示 ====================

    private void buildAppearance() {
        addSection("主题");
        addRow("主题", ThemeManager.name(this), () ->
                startActivity(new Intent(this, ThemeActivity.class)));
        addRow("背景", "封面 / 自定义图 / 网页 / 默认封面", () ->
                startActivity(new Intent(this, BackgroundHubActivity.class)));

        addSection("界面");
        addRow("界面缩放", com.qinghe.music163pro.util.UiScale.getDpi(this) + " dpi", () -> {
            com.qinghe.music163pro.util.WatchPopup.input(this, "界面缩放（dpi）",
                    "320 为原始大小；确认后自动保存并生效", "dpi",
                    String.valueOf(com.qinghe.music163pro.util.UiScale.getDpi(this)), "确定", text -> {
                        try {
                            int v = Math.max(160, Math.min(640, Integer.parseInt(text.trim())));
                            com.qinghe.music163pro.util.UiScale.setDpi(this, v);
                            recreate();
                        } catch (NumberFormatException ignored) {
                        }
                    });
        });
        addRow("页面字号", com.qinghe.music163pro.util.UiScale.getFontPercent(this) + "%", () -> {
            com.qinghe.music163pro.util.WatchPopup.input(this, "页面字号（%）",
                    "100 为原始大小；确认后自动保存并生效", "%",
                    String.valueOf(com.qinghe.music163pro.util.UiScale.getFontPercent(this)), "确定", text -> {
                        try {
                            int v = Math.max(80, Math.min(200, Integer.parseInt(text.trim())));
                            com.qinghe.music163pro.util.UiScale.setFontPercent(this, v);
                            recreate();
                        } catch (NumberFormatException ignored) {
                        }
                    });
        });

        // 自定义字体（需求 2026-11-21）：入口暂时隐藏（功能代码保留，恢复时取消下面两行注释即可）
        // addRow("自定义字体",
        //         com.qinghe.music163pro.util.CustomFont.has(this) ? "已启用" : "系统默认",
        //         () -> startActivity(new Intent(this, FontSettingsActivity.class)));

        addSection("屏幕");
        addSwitchRow("屏幕常亮", "保持屏幕不熄灭",
                prefs.getBoolean("keep_screen_on", false),
                (btn, on) -> {
                    prefs.edit().putBoolean("keep_screen_on", on).apply();
                    if (on) {
                        getWindow().addFlags(
                                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } else {
                        getWindow().clearFlags(
                                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                });
        addSwitchRow("OLED 防烧屏", "主页面内容周期移位",
                prefs.getBoolean(PREF_OLED_BURN_PROTECTION, false),
                (btn, on) -> prefs.edit().putBoolean(PREF_OLED_BURN_PROTECTION, on).apply());

        addSection("播放页歌名");
        addSwitchRow("去曲师", "隐藏歌名末尾的「 / 曲师」",
                prefs.getBoolean(com.qinghe.music163pro.util.SongDisplay.PREF_HIDE_ARTIST, false),
                (btn, on) -> prefs.edit().putBoolean(
                        com.qinghe.music163pro.util.SongDisplay.PREF_HIDE_ARTIST, on).apply());
        addSwitchRow("去括号内容", "隐藏歌名里的「(Remix)」等括号内容",
                prefs.getBoolean(com.qinghe.music163pro.util.SongDisplay.PREF_STRIP_BRACKETS, false),
                (btn, on) -> prefs.edit().putBoolean(
                        com.qinghe.music163pro.util.SongDisplay.PREF_STRIP_BRACKETS, on).apply());

        addSection("歌词显示");
        final TextView originalFont = addValueRow("歌词字号·原文",
                com.qinghe.music163pro.util.LyricSettings.getOriginalFontSize(this) + " sp");
        bindFontInput(originalFont, 0);
        final TextView romajiFont = addValueRow("歌词字号·音译",
                com.qinghe.music163pro.util.LyricSettings.getRomanizationFontSize(this) + " sp");
        bindFontInput(romajiFont, 1);
        final TextView translationFont = addValueRow("歌词字号·翻译",
                com.qinghe.music163pro.util.LyricSettings.getTranslationFontSize(this) + " sp");
        bindFontInput(translationFont, 2);
        addSwitchRow("歌词底衬", "歌词文字后面的深色衬底",
                com.qinghe.music163pro.util.LyricSettings.isBackdropEnabled(this),
                (btn, on) -> com.qinghe.music163pro.util.LyricSettings.setBackdropEnabled(this, on));
        addRow("高级歌词翻译", "AI 词性着色 / 自动翻译", () ->
                startActivity(new Intent(this, LanguageLearningActivity.class)));
    }

    /** 2026-11-22：点击行弹输入框直接填字号（9~22 sp），不再「点一下加一、长按减一」。 */
    private void bindFontInput(TextView valueRow, final int which) {
        ((LinearLayout) valueRow.getParent()).setOnClickListener(v ->
                com.qinghe.music163pro.util.WatchPopup.input(this, "歌词字号（9~22 sp）",
                        "直接输入目标字号，确定后生效", "sp",
                        String.valueOf(fontSizeOf(which)), "确定", text -> {
                            try {
                                int target = Integer.parseInt(text.trim());
                                int guard = 0;
                                while (fontSizeOf(which) != target && guard++ < 30) {
                                    com.qinghe.music163pro.util.LyricSettings.stepFontSize(
                                            this, which, target > fontSizeOf(which) ? 1 : -1);
                                }
                                valueRow.setText(fontSizeOf(which) + " sp");
                            } catch (NumberFormatException ignored) {
                            }
                        }));
    }

    private int fontSizeOf(int which) {
        if (which == 1) {
            return com.qinghe.music163pro.util.LyricSettings.getRomanizationFontSize(this);
        }
        if (which == 2) {
            return com.qinghe.music163pro.util.LyricSettings.getTranslationFontSize(this);
        }
        return com.qinghe.music163pro.util.LyricSettings.getOriginalFontSize(this);
    }

    // ==================== 播放与音质 ====================

    private void buildPlayback() {
        addSection("音效");
        addRow("均衡器",
                com.qinghe.music163pro.util.EqualizerController.isEnabled(this) ? "已开启" : "已关闭",
                () -> startActivity(new Intent(this, EqualizerActivity.class)));

        addSection("表冠");
        addSwitchRow("表冠调音量", "关闭后表冠不再调整音量",
                com.qinghe.music163pro.util.CrownVolumePrefs.isEnabled(this),
                (btn, checked) ->
                        com.qinghe.music163pro.util.CrownVolumePrefs.setEnabled(this, checked));
        final TextView sens = addValueRow("表冠灵敏度",
                "×" + com.qinghe.music163pro.util.CrownVolumePrefs.getCustomSensitivity(this));
        ((LinearLayout) sens.getParent()).setOnClickListener(v ->
                com.qinghe.music163pro.util.WatchPopup.input(this,
                        "表冠灵敏度倍率", "1.0 为标准，0.1~5.0；越大转同样角度音量变化越多",
                        "×",
                        String.valueOf(com.qinghe.music163pro.util.CrownVolumePrefs
                                .getCustomSensitivity(this)),
                        "确定", text -> {
                            try {
                                float mult = Math.max(0.1f,
                                        Math.min(5.0f, Float.parseFloat(text.trim())));
                                com.qinghe.music163pro.util.CrownVolumePrefs
                                        .setCustomSensitivity(this, mult);
                                sens.setText("×" + mult);
                            } catch (NumberFormatException ignored) {
                            }
                        }));
        final TextView cap = addValueRow("表冠音量上限",
                com.qinghe.music163pro.util.CrownVolumePrefs.getMaxPercent(this) + "%");
        ((LinearLayout) cap.getParent()).setOnClickListener(v ->
                com.qinghe.music163pro.util.WatchPopup.input(this, "表冠音量上限（%）",
                        "10~100；到达后表冠不再加大音量（减小不受限）", "%",
                        String.valueOf(com.qinghe.music163pro.util.CrownVolumePrefs.getMaxPercent(this)),
                        "确定", text -> {
                            try {
                                int percent = Math.max(10, Math.min(100, Integer.parseInt(text.trim())));
                                com.qinghe.music163pro.util.CrownVolumePrefs.setMaxPercent(this, percent);
                                cap.setText(percent + "%");
                            } catch (NumberFormatException ignored) {
                            }
                        }));

        addSection("播放行为");
        addSwitchRow("随机切歌", "随机播放模式下用表冠/按键随机切序",
                prefs.getBoolean(PREF_RANDOM_SKIP_RANDOM, false),
                (btn, on) -> prefs.edit().putBoolean(PREF_RANDOM_SKIP_RANDOM, on).apply());
        addSwitchRow("跳过试听歌曲", "VIP 歌曲只播试听片段时自动跳下一首",
                prefs.getBoolean(PREF_SKIP_TRIAL_SONGS, false),
                (btn, on) -> prefs.edit().putBoolean(PREF_SKIP_TRIAL_SONGS, on).apply());
        addSwitchRow("使用手表解析接口", "关闭后改用手机解析（默认开）",
                prefs.getBoolean(PREF_USE_WATCH_API, true),
                (btn, on) -> prefs.edit().putBoolean(PREF_USE_WATCH_API, on).apply());
        addSwitchRow("定时结束后退出 App", "倒计时结束时关闭应用",
                prefs.getBoolean(PREF_SLEEP_TIMER_EXIT_APP, false),
                (btn, on) -> prefs.edit().putBoolean(PREF_SLEEP_TIMER_EXIT_APP, on).apply());

        addSection("模式选择");
        final TextView modeRow = addValueRow("播放模式", playbackModeLabel());
        ((LinearLayout) modeRow.getParent()).setOnClickListener(v -> {
            boolean localOnly = prefs.getBoolean(
                    com.qinghe.music163pro.util.PlaybackMode.PREF_LOCAL_ONLY, false);
            boolean onlineOnly = prefs.getBoolean(
                    com.qinghe.music163pro.util.PlaybackMode.PREF_ONLINE_ONLY, false);
            boolean nextLocalOnly = !localOnly && !onlineOnly;
            boolean nextOnlineOnly = localOnly;
            prefs.edit()
                    .putBoolean(com.qinghe.music163pro.util.PlaybackMode.PREF_LOCAL_ONLY, nextLocalOnly)
                    .putBoolean(com.qinghe.music163pro.util.PlaybackMode.PREF_ONLINE_ONLY, nextOnlineOnly)
                    .apply();
            modeRow.setText(playbackModeLabel());
            com.qinghe.music163pro.util.UiKit.toast(this, "播放模式："
                    + playbackModeLabel());
        });
        final TextView favRow = addValueRow("收藏模式",
                prefs.getBoolean(PREF_FAV_MODE_CLOUD, false) ? "云端" : "本地");
        ((LinearLayout) favRow.getParent()).setOnClickListener(v -> {
            boolean next = !prefs.getBoolean(PREF_FAV_MODE_CLOUD, false);
            prefs.edit().putBoolean(PREF_FAV_MODE_CLOUD, next).apply();
            favRow.setText(next ? "云端" : "本地");
        });
        final TextView speedRow = addValueRow("变速模式", speedModeLabel());
        ((LinearLayout) speedRow.getParent()).setOnClickListener(v -> {
            int next = (prefs.getInt("speed_mode", 0) + 1) % 3;
            prefs.edit().putInt("speed_mode", next).apply();
            speedRow.setText(speedModeLabel());
        });
        final TextView sleepModeRow = addValueRow("定时计时口径", sleepTimerModeLabel());
        ((LinearLayout) sleepModeRow.getParent()).setOnClickListener(v -> {
            int cur = prefs.getInt(PREF_SLEEP_TIMER_MODE, SLEEP_TIMER_TOTAL);
            int next = cur == SLEEP_TIMER_TOTAL ? SLEEP_TIMER_NET
                    : cur == SLEEP_TIMER_NET ? SLEEP_TIMER_FINISH_TRACK : SLEEP_TIMER_TOTAL;
            prefs.edit().putInt(PREF_SLEEP_TIMER_MODE, next).apply();
            sleepModeRow.setText(sleepTimerModeLabel());
        });
    }

    private String playbackModeLabel() {
        boolean localOnly = prefs.getBoolean(
                com.qinghe.music163pro.util.PlaybackMode.PREF_LOCAL_ONLY, false);
        boolean onlineOnly = prefs.getBoolean(
                com.qinghe.music163pro.util.PlaybackMode.PREF_ONLINE_ONLY, false);
        if (localOnly) return "仅本机";
        if (onlineOnly) return "仅在线";
        return "本地与在线";
    }

    private String speedModeLabel() {
        switch (prefs.getInt("speed_mode", 0)) {
            case 1: return "慢速优先";
            case 2: return "关闭";
            default: return "自动";
        }
    }

    private String sleepTimerModeLabel() {
        switch (prefs.getInt(PREF_SLEEP_TIMER_MODE, SLEEP_TIMER_TOTAL)) {
            case SLEEP_TIMER_NET: return "播放净时长";
            case SLEEP_TIMER_FINISH_TRACK: return "放完当前曲";
            default: return "总时长";
        }
    }

    // ==================== 交互与浮层 ====================

    private void buildInteraction() {
        addSection("手势与返回");
        addSwitchRow("应用内左滑返回", "任意子页面从左往右滑返回上一页",
                prefs.getBoolean(com.qinghe.music163pro.util.BackGesture.KEY_ENABLED, true),
                (btn, on) -> prefs.edit().putBoolean(
                        com.qinghe.music163pro.util.BackGesture.KEY_ENABLED, on).apply());
        addSwitchRow("显示全局返回按钮", "左上角常驻的圆形返回按钮",
                prefs.getBoolean("show_back_button", true),
                (btn, on) -> prefs.edit().putBoolean("show_back_button", on).apply());

        addSection("听歌识曲");
        final TextView recRow = addValueRow("识曲模式", recognitionModeLabel());
        ((LinearLayout) recRow.getParent()).setOnClickListener(v -> {
            int next = prefs.getInt(PREF_RECOGNITION_MODE, MODE_AUTO) == MODE_MANUAL
                    ? MODE_AUTO : MODE_MANUAL;
            prefs.edit().putInt(PREF_RECOGNITION_MODE, next).apply();
            recRow.setText(recognitionModeLabel());
        });

        addSection("歌词行为");
        final TextView scrollRow = addValueRow("滚动模式", lyricScrollModeLabel());
        ((LinearLayout) scrollRow.getParent()).setOnClickListener(v -> {
            int next = prefs.getInt(PREF_LYRIC_SCROLL_MODE, LYRIC_MODE_BLOCK) == LYRIC_MODE_BLOCK
                    ? LYRIC_MODE_FOLLOW : LYRIC_MODE_BLOCK;
            prefs.edit().putInt(PREF_LYRIC_SCROLL_MODE, next).apply();
            scrollRow.setText(lyricScrollModeLabel());
        });
        final TextView offsetRow = addValueRow("歌词偏移",
                prefs.getInt("lyric_offset_ms", 0) + " ms");
        ((LinearLayout) offsetRow.getParent()).setOnClickListener(v ->
                com.qinghe.music163pro.util.WatchPopup.input(this, "歌词偏移（毫秒）",
                        "正数让歌词提前，负数让歌词延后", "ms",
                        String.valueOf(prefs.getInt("lyric_offset_ms", 0)),
                        "确定", text -> {
                            try {
                                int offsetMs = Integer.parseInt(text.trim());
                                prefs.edit().putInt("lyric_offset_ms", offsetMs).apply();
                                offsetRow.setText(offsetMs + " ms");
                            } catch (NumberFormatException ignored) {
                            }
                        }));
        final TextView resumeRow = addValueRow("歌词恢复间隔",
                prefs.getInt(PREF_LYRIC_RESUME_INTERVAL, 3) + " 秒");
        ((LinearLayout) resumeRow.getParent()).setOnClickListener(v ->
                com.qinghe.music163pro.util.WatchPopup.input(this, "歌词恢复间隔（秒）",
                        "暂停/拖动后多久恢复自动滚动", "秒",
                        String.valueOf(prefs.getInt(PREF_LYRIC_RESUME_INTERVAL, 3)),
                        "确定", text -> {
                            try {
                                int s = Math.max(1, Math.min(30, Integer.parseInt(text.trim())));
                                prefs.edit().putInt(PREF_LYRIC_RESUME_INTERVAL, s).apply();
                                resumeRow.setText(s + " 秒");
                            } catch (NumberFormatException ignored) {
                            }
                        }));
    }

    private String recognitionModeLabel() {
        return prefs.getInt(PREF_RECOGNITION_MODE, MODE_AUTO) == MODE_MANUAL
                ? "手动" : "自动";
    }

    private String lyricScrollModeLabel() {
        return prefs.getInt(PREF_LYRIC_SCROLL_MODE, LYRIC_MODE_BLOCK) == LYRIC_MODE_FOLLOW
                ? "跟随" : "分块";
    }

    // ==================== 数据与工具 ====================

    private void buildData() {
        addSection("存储");
        addRow("清理缓存", "元信息索引 / 本地音乐列表缓存", () ->
                com.qinghe.music163pro.util.WatchMenuDialog.show(this, "清理缓存",
                        new String[]{"清理元信息索引", "清理本地音乐列表缓存", "全部清理"},
                        index -> {
                            if (index == 0 || index == 2) {
                                com.qinghe.music163pro.util.LocalMetadataIndex.clear(this);
                            }
                            if (index == 1 || index == 2) {
                                com.qinghe.music163pro.util.LocalMusicCache.invalidate(this);
                                com.qinghe.music163pro.util.LocalLibraryCache.clear();
                            }
                            Toast.makeText(this, "已清理，下次进入本地音乐会重新扫描",
                                    Toast.LENGTH_SHORT).show();
                        }));
        addRow("编辑更多页面", "自定义「更多」页显示项", () ->
                startActivity(new Intent(this, EditMoreActivity.class)));
        addRow("其它工具", "导出音乐 / 小天才模块", () ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    // ==================== 关于 ====================

    private void buildAbout() {
        addSection("应用");
        addRow("检测更新", "检查是否有新版本", () ->
                startActivity(new Intent(this, UpdateActivity.class)));
        addRow("帮助", "使用说明", () ->
                startActivity(new Intent(this, HelpActivity.class)));
        addRow("关于", "版本信息与作者", () ->
                startActivity(new Intent(this, AboutActivity.class)));
        addRow("开源信息", "第三方库许可", () ->
                startActivity(new Intent(this, OpenSourceActivity.class)));
    }

    /** 首次进入设置时弹一次「简介可展开」的提示；5 秒倒计时内不可关闭。 */
    private void showExpandHintOnce() {
        try {
            if (prefs.getBoolean("settings_expand_hint_shown", false)) {
                return;
            }
            prefs.edit().putBoolean("settings_expand_hint_shown", true).apply();
            getWindow().getDecorView().postDelayed(this::showExpandHintDialog, 600L);
        } catch (Throwable ignored) {
        }
    }

    /** 提示弹窗：5 秒倒计时结束后才出现「知道了」并允许关闭。 */
    private void showExpandHintDialog() {
        if (isFinishing()) {
            return;
        }
        final android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_Translucent_NoTitleBar);
        final android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setBackground(com.qinghe.music163pro.util.UiKit.dialogCard(this));
        int pad = dpToPx(16);
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("小提示");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(title);

        TextView body = new TextView(this);
        body.setText("较长的说明会折叠成一行，点灰色小字本身即可展开完整说明；"
                + "短说明（含数值）直接显示一行，不折叠。");
        body.setTextColor(0xFFAAAAAA);
        body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        android.widget.LinearLayout.LayoutParams bodyLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dpToPx(8);
        card.addView(body, bodyLp);

        final TextView countdown = new TextView(this);
        countdown.setText("5 秒后可关闭");
        countdown.setTextColor(0xFF888888);
        countdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        countdown.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams cLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dpToPx(10);
        card.addView(countdown, cLp);

        final TextView ok = new TextView(this);
        ok.setText("知道了");
        ok.setTextColor(com.qinghe.music163pro.util.UiKit.COLOR_ACCENT);
        ok.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        ok.setGravity(android.view.Gravity.CENTER);
        ok.setPadding(0, dpToPx(10), 0, dpToPx(10));
        ok.setVisibility(android.view.View.GONE);
        android.widget.LinearLayout.LayoutParams okLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        okLp.topMargin = dpToPx(8);
        card.addView(ok, okLp);
        ok.setOnClickListener(v -> dialog.dismiss());

        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(this);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.min((int) (screenW * 0.9f), screenW - dpToPx(12));
        android.widget.FrameLayout.LayoutParams cardLp = new android.widget.FrameLayout.LayoutParams(
                cardW, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = android.view.Gravity.CENTER;
        wrapper.addView(card, cardLp);
        dialog.setContentView(wrapper);
        // 5 秒内：无按钮、返回键无效、点外部无效
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();

        final android.os.Handler handler = new android.os.Handler(
                android.os.Looper.getMainLooper());
        final int[] remain = {5};
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                remain[0]--;
                if (remain[0] > 0) {
                    countdown.setText(remain[0] + " 秒后可关闭");
                    handler.postDelayed(this, 1000L);
                } else {
                    countdown.setVisibility(android.view.View.GONE);
                    ok.setVisibility(android.view.View.VISIBLE);
                    dialog.setCancelable(true);
                    dialog.setCanceledOnTouchOutside(true);
                }
            }
        };
        handler.postDelayed(tick, 1000L);
    }

    // ==================== 行构建 ====================

    private void addTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, dpToPx(4), 0, dpToPx(10));
        // 2026-11-22：页标题同样自适应（「外观与显示」这类四字标题在窄屏上也会被裁）
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            t.setAutoSizeTextTypeUniformWithConfiguration(11, 15, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        container.addView(t);
    }

    /**
     * 2026-11-22：分类下的引导性简介（告诉用户这一页有哪些设置项）。
     * <b>不截断</b>：完整显示，字号也随内容自适应。
     */
    private void addTitleIntro(String intro) {
        TextView t = new TextView(this);
        t.setText(intro);
        t.setTextColor(getResources().getColor(R.color.text_secondary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        t.setPadding(dpToPx(4), 0, dpToPx(4), dpToPx(10));
        container.addView(t);
    }

    /** 各分类的引导简介文案。 */
    private String introOf(String p) {
        switch (p) {
            case "playback": return "均衡器、表冠调音量、随机切歌、试听跳过、解析接口、定时退出、播放/收藏/变速/计时模式";
            case "interaction": return "左滑返回、全局返回按钮、识曲模式、歌词滚动模式与偏移校准";
            case "data": return "缓存清理、导出、Cookie、开源信息";
            case "about": return "版本信息、检测更新、开源许可";
            default: return "主题、背景、界面缩放与字号、屏幕常亮与防烧屏、播放页歌名显示、歌词字号与底衬";
        }
    }

    /** 小标题（需求 2026-11-21：设置页冗余标题已清理，不再渲染；方法保留避免改调用点）。 */
    private void addSection(String name) {
        // no-op
    }

    private void addRow(String title, String sub, Runnable action) {
        LinearLayout row = buildRow(title, sub);
        row.setOnClickListener(v -> action.run());
        container.addView(row);
    }

    /** 「标题 + 副标题 + MD3 开关」行（需求 2026-11-21：纯开关不再用「开/关」文字指示）。 */
    private void addSwitchRow(String title, String sub, boolean initial,
                              android.widget.CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(7), dpToPx(16), dpToPx(7));
        row.setMinimumHeight(dpToPx(48));
        row.setBackground(rippleBackground());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dpToPx(4);
        row.setLayoutParams(rp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        t.setSingleLine(true);
        // 2026-11-22：开关行的标题也要自适应 —— 手表窄屏上开关占了宽度，长标题会被裁出半个字
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            t.setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        textCol.addView(t);
        textCol.addView(makeDescriptionText(sub));
        row.addView(textCol);

        com.google.android.material.materialswitch.MaterialSwitch sw =
                new com.google.android.material.materialswitch.MaterialSwitch(this);
        sw.setTextOn("");
        sw.setTextOff("");
        sw.setScaleX(0.8f);
        sw.setScaleY(0.8f);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);

        row.setOnClickListener(v -> sw.toggle());
        container.addView(row);
    }

    private TextView addValueRow(String title, String initialValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(7), dpToPx(16), dpToPx(7));
        row.setMinimumHeight(dpToPx(46));
        row.setBackground(rippleBackground());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dpToPx(4);
        row.setLayoutParams(rp);
        row.setClickable(true);
        row.setFocusable(true);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        t.setSingleLine(true);
        row.addView(t);

        TextView value = new TextView(this);
        value.setText(initialValue);
        value.setTextColor(ThemeManager.accentColor(this));
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        value.setSingleLine(true);
        row.addView(value);

        container.addView(row);
        return value;
    }

    private LinearLayout buildRow(String title, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(7), dpToPx(16), dpToPx(7));
        row.setMinimumHeight(dpToPx(48));
        row.setBackground(rippleBackground());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dpToPx(4);
        row.setLayoutParams(rp);
        row.setClickable(true);
        row.setFocusable(true);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        t.setSingleLine(true);
        // 2026-11-22：标题超宽时自动缩小字号，避免出现「半个汉字」的裁切
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            t.setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        row.addView(t);

        TextView s = makeDescriptionText(sub);
        row.addView(s);
        return row;
    }

    /**
     * 设置项下方的灰色说明文字（2026-11-27 需求）。
     *
     * <p>「轻触展开」上线后有明显副作用：说明文字默认是整行宽（LinearLayout 里
     * match_parent），点行内下半部分全被它吃掉，设置项本体反而不好点。现在按长度分流：</p>
     * <ul>
     *   <li><b>短文案</b>（少于 {@link #SHORT_DESC_MAX} 字，含「320 dpi」这类纯数值）：
     *       单行显示，<b>不折叠、也不设点击监听</b> —— 点击照旧落到整行按钮上；</li>
     *   <li><b>长文案</b>：保持「一行省略 → 点开完整说明」，但可点区域收窄到
     *       文字本身的宽度（WRAP_CONTENT），行内其余位置仍然点得动。</li>
     * </ul>
     */
    private TextView makeDescriptionText(String sub) {
        String text = sub == null ? "" : sub;
        TextView s = new TextView(this);
        s.setText(text);
        s.setTextColor(getResources().getColor(R.color.text_secondary));
        s.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        s.setPadding(0, dpToPx(2), 0, 0);
        if (text.length() < SHORT_DESC_MAX) {
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            return s;
        }
        s.setMaxLines(1);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // 宽度只包住文字：否则 WRAP 之外的行内区域也会进这个 TextView 的点击区
        s.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        s.setOnClickListener(v -> {
            boolean expanded = s.getMaxLines() != Integer.MAX_VALUE;
            s.setMaxLines(expanded ? Integer.MAX_VALUE : 1);
            s.setEllipsize(expanded ? null : android.text.TextUtils.TruncateAt.END);
        });
        return s;
    }

    /** 2026-11-22：行背景缓存 —— 缓存 ConstantState，每行 newDrawable()：
     *  各行共享内部常量（省内存省构造），但状态彼此独立（涟漪各自正常）。
     *  之前直接缓存 Drawable 实例 + mutate() 是错的：mutate() 只复制一次，
     *  后续所有行拿到的是同一个对象，涟漪永远只出现在最后设置的行上。 */
    private android.graphics.drawable.Drawable.ConstantState rowBackgroundState;

    private android.graphics.drawable.Drawable rippleBackground() {
        if (rowBackgroundState != null) {
            return rowBackgroundState.newDrawable(getResources());
        }
        android.graphics.drawable.GradientDrawable body = new android.graphics.drawable.GradientDrawable();
        body.setColor(0xFF1E1F24);
        body.setCornerRadius(dpToPx(14));
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dpToPx(14));
        android.graphics.drawable.RippleDrawable ripple =
                new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(
                                UiKit.withAlpha(ThemeManager.accentColor(this), 0x33)),
                        body, mask);
        rowBackgroundState = ripple.getConstantState();
        return ripple;
    }
}
