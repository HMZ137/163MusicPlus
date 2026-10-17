package com.qinghe.music163pro.activity;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


import com.google.android.material.switchmaterial.SwitchMaterial;
import com.qinghe.music163pro.R;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.LyricSettings;
import com.qinghe.music163pro.util.PlaybackMode;
import com.qinghe.music163pro.util.UiScale;

/**
 * Toggle settings activity - contains boolean/toggle options:
 * - Keep screen on (SwitchMaterial)
 * - Favorites mode local/cloud (cyclic selection)
 * - Sleep timer mode total/net playback (cyclic selection)
 * - Speed mode 3-value cyclic row (tap to cycle)
 */
public class ToggleSettingsActivity extends BaseWatchActivity {

    private static final String PREFS_NAME = "music163_settings";
    private static final String PREF_RECOGNITION_MODE = "song_recognition_mode";
    private static final String PREF_LYRIC_SCROLL_MODE = "lyric_scroll_mode";
    private static final String PREF_LYRIC_RESUME_INTERVAL = "lyric_resume_interval";
    private static final String PREF_SLEEP_TIMER_EXIT_APP = "sleep_timer_exit_app";
    /** 主页面 OLED 防烧屏：开启后内容周期轻微移位（MainActivity 读取）。 */
    private static final String PREF_OLED_BURN_PROTECTION = "oled_burn_protection";
    private static final String PREF_SLEEP_TIMER_MODE = "sleep_timer_mode";
    private static final String PREF_FAV_MODE_CLOUD = "fav_mode_cloud";
    private static final int MODE_MANUAL = 0;
    private static final int MODE_AUTO = 1;
    // Lyric scroll modes: 0=每行 (follow current line), 1=阻塞 (blocked by user scroll)
    private static final int LYRIC_MODE_FOLLOW = 0;
    private static final int LYRIC_MODE_BLOCK = 1;
    // 定时关闭模式的取值统一由 MusicPlayerManager 定义，避免两处各维护一套
    private static final int SLEEP_TIMER_TOTAL = MusicPlayerManager.SLEEP_TIMER_MODE_TOTAL;
    private static final int SLEEP_TIMER_NET = MusicPlayerManager.SLEEP_TIMER_MODE_NET;
    private static final int SLEEP_TIMER_FINISH_TRACK =
            MusicPlayerManager.SLEEP_TIMER_MODE_FINISH_TRACK;
    /** 定时模式的可选文案，循环切换与回显共用同一份，避免顺序对不上。 */
    private static final String[] SLEEP_TIMER_LABELS = {"总时长", "播放净时长", "放完当前曲"};
    // Fav modes: 0=本地, 1=云端
    private static final int FAV_MODE_LOCAL = 0;
    private static final int FAV_MODE_CLOUD = 1;

    private SwitchMaterial switchKeepScreenOn;
    private SwitchMaterial switchSleepTimerExitApp;
    private SwitchMaterial switchOledBurnProtection;
    private SwitchMaterial switchLocalOnlyMode;
    private SwitchMaterial switchOnlineOnlyMode;
    private TextView tvFavModeValue;
    private TextView tvSleepTimerModeValue;
    private TextView tvSpeedModeValue;
    private TextView tvRecognitionModeValue;
    private TextView tvLyricScrollModeValue;
    private TextView tvLyricResumeIntervalValue;
    private TextView tvLyricOffsetValue;
    private LinearLayout rowLyricResumeInterval;
    private SharedPreferences prefs;

    /** Suppress listener callbacks while programmatically setting switch state. */
    private boolean updatingSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_toggle_settings);

        // 标题栏要撑到浮动返回按钮的占位高度：本页第一项就是「本地播放模式」开关，
        // 原来它的文字顶边落在 y≈50，按钮底边在 y=53 —— 已经被压到一点了
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(
                this, findViewById(R.id.tv_toggle_settings_title));

        // 设置项下方的说明：过长先省略（…），点一下展开全文、再点收起。
        // 状态不持久化 ⇒ 重新进入本页自动回到折叠态（需求方要求「重进再次折叠」）。
        com.qinghe.music163pro.util.UiKit.makeDescriptionsCollapsible(
                findViewById(R.id.layout_toggle_settings_content));

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Apply keep screen on setting
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        switchKeepScreenOn = findViewById(R.id.switch_keep_screen_on);
        switchSleepTimerExitApp = findViewById(R.id.switch_sleep_timer_exit_app);
        switchOledBurnProtection = findViewById(R.id.switch_oled_burn_protection);
        switchLocalOnlyMode = findViewById(R.id.switch_local_only_mode);
        switchOnlineOnlyMode = findViewById(R.id.switch_online_only_mode);
        tvFavModeValue = findViewById(R.id.tv_fav_mode_value);
        tvSleepTimerModeValue = findViewById(R.id.tv_sleep_timer_mode_value);
        tvSpeedModeValue = findViewById(R.id.tv_speed_mode_value);
        tvRecognitionModeValue = findViewById(R.id.tv_recognition_mode_value);
        tvLyricScrollModeValue = findViewById(R.id.tv_lyric_scroll_mode_value);
        tvLyricResumeIntervalValue = findViewById(R.id.tv_lyric_resume_interval_value);
        LinearLayout rowSpeedMode = findViewById(R.id.row_speed_mode);
        LinearLayout rowRecognitionMode = findViewById(R.id.row_recognition_mode);
        LinearLayout rowLyricScrollMode = findViewById(R.id.row_lyric_scroll_mode);
        LinearLayout rowFavMode = findViewById(R.id.row_fav_mode);
        LinearLayout rowSleepTimerMode = findViewById(R.id.row_sleep_timer_mode);
        rowLyricResumeInterval = findViewById(R.id.row_lyric_resume_interval);

        // 歌词偏移：± 各 0.5s，用于歌词整体偏快/偏慢时的人工校正
        tvLyricOffsetValue = findViewById(R.id.tv_lyric_offset_value);
        TextView btnLyricOffsetMinus = findViewById(R.id.btn_lyric_offset_minus);
        TextView btnLyricOffsetPlus = findViewById(R.id.btn_lyric_offset_plus);
        if (btnLyricOffsetMinus != null) {
            btnLyricOffsetMinus.setOnClickListener(v -> changeLyricOffset(-1));
        }
        if (btnLyricOffsetPlus != null) {
            btnLyricOffsetPlus.setOnClickListener(v -> changeLyricOffset(1));
        }
        updateLyricOffsetValue();

        // Initialise switch states from prefs
        syncSwitchStates();

        // Keep screen on: use checked-change listener
        switchKeepScreenOn.setOnCheckedChangeListener((btn, isChecked) -> {
            if (updatingSwitch) return;
            prefs.edit().putBoolean("keep_screen_on", isChecked).apply();
            if (isChecked) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });

        // 本地播放模式 / 云音乐模式
        if (switchLocalOnlyMode != null) {
            switchLocalOnlyMode.setOnCheckedChangeListener((btn, isChecked) -> {
                if (updatingSwitch) return;
                // 必须走 PlaybackMode.setLocalOnly —— 它同时会关掉/还原「收藏走云端」，
                // 直接写 pref 会漏掉那一步
                PlaybackMode.setLocalOnly(this, isChecked);
                // 两种模式互斥：开本地就把「纯在线」关掉（setLocalOnly 内部也会清掉那个键）
                syncModeSwitches(!isChecked, false);
                // 收藏模式的值在本地模式下是「锁定」的，切完模式要重新回显一次
                updateFavModeValue();
                Toast.makeText(this, isChecked
                                ? "已切换到本地播放模式，云端功能已隐藏"
                                : "已切换到云音乐模式",
                        Toast.LENGTH_SHORT).show();
            });
        }

        if (switchOnlineOnlyMode != null) {
            switchOnlineOnlyMode.setOnCheckedChangeListener((btn, isChecked) -> {
                if (updatingSwitch) return;
                PlaybackMode.setOnlineOnly(this, isChecked);
                syncModeSwitches(false, isChecked);
                updateFavModeValue();
                Toast.makeText(this, isChecked
                                ? "已切换到纯在线模式，本地功能已隐藏（下载管理保留）"
                                : "已退出纯在线模式",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // OLED 防烧屏：主页面内容周期性轻微移位（MainActivity onResume 时读取生效）
        if (switchOledBurnProtection != null) {
            switchOledBurnProtection.setOnCheckedChangeListener((btn, isChecked) -> {
                if (updatingSwitch) return;
                prefs.edit().putBoolean(PREF_OLED_BURN_PROTECTION, isChecked).apply();
                Toast.makeText(this,
                        isChecked ? "已开启 OLED 防烧屏，主页面内容会周期轻微移位"
                                : "已关闭 OLED 防烧屏",
                        Toast.LENGTH_SHORT).show();
            });
        }

        switchSleepTimerExitApp.setOnCheckedChangeListener((btn, isChecked) -> {
            if (updatingSwitch) return;
            prefs.edit().putBoolean(PREF_SLEEP_TIMER_EXIT_APP, isChecked).apply();
            Toast.makeText(this,
                    isChecked ? "定时结束后将退出应用" : "定时结束后仅停止播放",
                    Toast.LENGTH_SHORT).show();
        });

        // Favorites mode: tap to cycle (本地 / 云端)
        if (rowFavMode != null) rowFavMode.setOnClickListener(v -> cycleFavMode());

        // Sleep timer mode: tap to cycle (总时长 / 播放净时长)
        if (rowSleepTimerMode != null) rowSleepTimerMode.setOnClickListener(v -> cycleSleepTimerMode());

        // Speed mode: tap row to cycle through 3 values
        rowSpeedMode.setOnClickListener(v -> cycleSpeedMode());
        rowRecognitionMode.setOnClickListener(v -> cycleRecognitionMode());
        rowLyricScrollMode.setOnClickListener(v -> cycleLyricScrollMode());
        rowLyricResumeInterval.setOnClickListener(v -> editLyricResumeInterval());

        // 界面缩放与歌词字号：这几项结构完全一样（标题 + 数值 + −/+ 或循环切换），
        // 用代码生成，免得在布局里堆四份重复的 XML。
        buildDisplaySettings();
    }

    /** 界面缩放 + 原文/音译/翻译 三类歌词字号。 */
    private void buildDisplaySettings() {
        LinearLayout container = findViewById(R.id.layout_toggle_settings_content);
        if (container == null) {
            return;
        }
        container.addView(buildSectionTitle("显示"));

        // 页面缩放：循环切换档位。改完需要页面重建才会生效，所以切换后立刻 recreate。
        final TextView scaleValue = buildStepperRow(container, "界面缩放",
                "100%",
                () -> {
                    UiScale.cycle(this);
                    recreate();
                },
                () -> {
                    UiScale.cycle(this);
                    recreate();
                });
        scaleValue.setText(UiScale.getPercent(this) + "%");
        // 循环档位用整行点击更顺手，−/+ 只是备用入口
        scaleValue.setOnClickListener(v -> {
            int percent = UiScale.cycle(this);
            Toast.makeText(this, "界面缩放: " + percent + "%（重新进入页面后生效）",
                    Toast.LENGTH_SHORT).show();
            recreate();
        });

        // 歌词字号：音 / 词 / 译 各一个
        final TextView originalValue = buildStepperRow(container, "歌词字号·原文",
                "", () -> stepFont(0, -1), () -> stepFont(0, 1));
        final TextView romajiValue = buildStepperRow(container, "歌词字号·音译",
                "", () -> stepFont(1, -1), () -> stepFont(1, 1));
        final TextView translationValue = buildStepperRow(container, "歌词字号·翻译",
                "", () -> stepFont(2, -1), () -> stepFont(2, 1));

        // 歌词底衬：用真开关，因为它就是一个二值项，用「−/+」反而别扭
        LinearLayout backdropRow = new LinearLayout(this);
        backdropRow.setOrientation(LinearLayout.HORIZONTAL);
        backdropRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        backdropRow.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        // 直角卡片底（与开关行一致）；minHeight 防止放大字号后两行文字被截断
        backdropRow.setBackground(getResources().getDrawable(R.drawable.bg_card_tile));
        backdropRow.setMinimumHeight(dpToPx(48));
        backdropRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout backdropText = new LinearLayout(this);
        backdropText.setOrientation(LinearLayout.VERTICAL);
        backdropText.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView backdropTitle = new TextView(this);
        backdropTitle.setText("歌词底衬");
        backdropTitle.setTextColor(getResources().getColor(R.color.text_primary));
        backdropTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        backdropText.addView(backdropTitle);

        TextView backdropHint = new TextView(this);
        backdropHint.setText("给歌词/音/译加半透明底，避免背景太亮看不清");
        backdropHint.setTextColor(getResources().getColor(R.color.text_secondary));
        backdropHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        backdropText.addView(backdropHint);

        backdropRow.addView(backdropText);

        SwitchMaterial backdropSwitch = new SwitchMaterial(this);
        backdropSwitch.setChecked(LyricSettings.isBackdropEnabled(this));
        backdropSwitch.setOnCheckedChangeListener((button, checked) ->
                LyricSettings.setBackdropEnabled(this, checked));
        backdropRow.addView(backdropSwitch);

        container.addView(backdropRow);

        // 全屏歌词：控制歌词层右上角要不要出现「全屏」按钮（以及那个沉浸页的入口）
        LinearLayout fullscreenRow = new LinearLayout(this);
        fullscreenRow.setOrientation(LinearLayout.HORIZONTAL);
        fullscreenRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        fullscreenRow.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        fullscreenRow.setBackground(getResources().getDrawable(
                com.qinghe.music163pro.R.drawable.bg_card_tile));
        fullscreenRow.setMinimumHeight(dpToPx(48));
        fullscreenRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout fullscreenText = new LinearLayout(this);
        fullscreenText.setOrientation(LinearLayout.VERTICAL);
        fullscreenText.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView fullscreenTitle = new TextView(this);
        fullscreenTitle.setText("全屏歌词按钮");
        fullscreenTitle.setTextColor(getResources().getColor(R.color.text_primary));
        fullscreenTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        fullscreenText.addView(fullscreenTitle);
        TextView fullscreenHint = new TextView(this);
        fullscreenHint.setText("歌词层显示「全屏」按钮，可进入沉浸式大字歌词");
        fullscreenHint.setTextColor(getResources().getColor(R.color.text_secondary));
        fullscreenHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        fullscreenText.addView(fullscreenHint);
        fullscreenRow.addView(fullscreenText);
        com.google.android.material.switchmaterial.SwitchMaterial fullscreenSwitch =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        fullscreenSwitch.setChecked(LyricSettings.isFullscreenButtonEnabled(this));
        fullscreenSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingSwitch) {
                return;
            }
            LyricSettings.setFullscreenButtonEnabled(this, checked);
        });
        fullscreenRow.addView(fullscreenSwitch);
        fullscreenRow.setOnClickListener(v -> fullscreenSwitch.toggle());
        container.addView(fullscreenRow);

        // 语言学习模式入口：AI 歌词（词性着色 + 无翻译歌曲的 AI 翻译）
        LinearLayout languageRow = new LinearLayout(this);
        languageRow.setOrientation(LinearLayout.HORIZONTAL);
        languageRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        languageRow.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        languageRow.setBackground(getResources().getDrawable(
                com.qinghe.music163pro.R.drawable.bg_card_tile));
        languageRow.setMinimumHeight(dpToPx(48));
        languageRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout languageText = new LinearLayout(this);
        languageText.setOrientation(LinearLayout.VERTICAL);
        languageText.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView languageTitle = new TextView(this);
        languageTitle.setText("高级歌词翻译");
        languageTitle.setTextColor(getResources().getColor(R.color.text_primary));
        languageTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        languageText.addView(languageTitle);
        TextView languageHint = new TextView(this);
        languageHint.setText("歌词词性着色 / 自动翻译；设置在「设置 → 高级歌词翻译」");
        languageHint.setTextColor(getResources().getColor(R.color.text_secondary));
        languageHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        languageText.addView(languageHint);
        languageRow.addView(languageText);
        // 功能开关留在这里；详细设置（Key / 模型 / 缓存 / 图例）在「设置 → 高级歌词翻译」
        com.google.android.material.switchmaterial.SwitchMaterial languageSwitch =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        languageSwitch.setChecked(
                com.qinghe.music163pro.util.AiLyricSettings.isEnabled(this));
        languageSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingSwitch) {
                return;
            }
            if (checked && !com.qinghe.music163pro.util.AiLyricSettings.hasApiKey(this)) {
                btn.setChecked(false);
                Toast.makeText(this, "请先在「设置 → 高级歌词翻译」填写 DeepSeek API Key",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            com.qinghe.music163pro.util.AiLyricSettings.setEnabled(this, checked);
            Toast.makeText(this, checked
                    ? "已开启高级歌词翻译：歌词按词性着色，缺翻译的歌交给 AI"
                    : "已关闭（歌词页仍可用「AI」按钮单次激活）", Toast.LENGTH_SHORT).show();
        });
        languageRow.addView(languageSwitch);
        languageRow.setOnClickListener(v -> languageSwitch.toggle());
        container.addView(languageRow);

        lyricFontValues = new TextView[]{originalValue, romajiValue, translationValue};
        refreshLyricFontValues();
    }

    /** 原文 / 音译 / 翻译 三个字号数值控件，用来在调整后刷新显示。 */
    private TextView[] lyricFontValues;

    private void stepFont(int which, int direction) {
        LyricSettings.stepFontSize(this, which, direction);
        refreshLyricFontValues();
    }

    private void refreshLyricFontValues() {
        if (lyricFontValues == null) {
            return;
        }
        if (lyricFontValues[0] != null) {
            lyricFontValues[0].setText(LyricSettings.getOriginalFontSize(this) + "sp");
        }
        if (lyricFontValues[1] != null) {
            lyricFontValues[1].setText(LyricSettings.getRomanizationFontSize(this) + "sp");
        }
        if (lyricFontValues[2] != null) {
            lyricFontValues[2].setText(LyricSettings.getTranslationFontSize(this) + "sp");
        }
    }

    /** 分组小标题。 */
    private TextView buildSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0x80FFFFFF);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(4));
        return tv;
    }

    /**
     * 生成一行「标题 + 数值 + −/+」的设置项。
     *
     * @return 显示数值的 TextView，交给调用方刷新
     */
    private TextView buildStepperRow(LinearLayout container, String title, String initialValue,
                                     Runnable onMinus, Runnable onPlus) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        // 直角卡片底（与开关行一致）；高度用 minHeight 防止放大字号后两行文字被截断
        row.setBackground(getResources().getDrawable(R.drawable.bg_card_tile));
        row.setMinimumHeight(dpToPx(48));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(getResources().getColor(R.color.text_primary));
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        textColumn.addView(tvTitle);

        TextView tvValue = new TextView(this);
        tvValue.setText(initialValue);
        tvValue.setTextColor(getResources().getColor(R.color.colorPrimary));
        tvValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        textColumn.addView(tvValue);
        row.addView(textColumn);

        row.addView(buildStepperButton("−", onMinus));
        row.addView(buildStepperButton("+", onPlus));

        container.addView(row);
        return tvValue;
    }

    private TextView buildStepperButton(String label, Runnable action) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(getResources().getColor(R.color.text_primary));
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncSwitchStates();
    }

    private void syncSwitchStates() {
        updatingSwitch = true;
        switchKeepScreenOn.setChecked(prefs.getBoolean("keep_screen_on", false));
        switchSleepTimerExitApp.setChecked(prefs.getBoolean(PREF_SLEEP_TIMER_EXIT_APP, false));
        if (switchOledBurnProtection != null) {
            switchOledBurnProtection.setChecked(prefs.getBoolean(PREF_OLED_BURN_PROTECTION, false));
        }
        if (switchLocalOnlyMode != null) {
            switchLocalOnlyMode.setChecked(PlaybackMode.isLocalOnly(this));
            if (switchOnlineOnlyMode != null) {
                switchOnlineOnlyMode.setChecked(PlaybackMode.isOnlineOnly(this));
            }
        }
        updatingSwitch = false;
        updateFavModeValue();
        updateSleepTimerModeValue();
        updateSpeedModeValue();
        updateRecognitionModeValue();
        updateLyricScrollModeValue();
        updateLyricResumeIntervalValue();
    }

    private void cycleFavMode() {
        // 本地播放模式下这里没有可切的东西：收藏被强制走本地库
        if (PlaybackMode.isLocalOnly(this)) {
            Toast.makeText(this, "本地播放模式下收藏固定在本地", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean isCloud = prefs.getBoolean(PREF_FAV_MODE_CLOUD, false);
        boolean newCloud = !isCloud;
        prefs.edit().putBoolean(PREF_FAV_MODE_CLOUD, newCloud).apply();
        updateFavModeValue();
        Toast.makeText(this, "收藏模式已切换为: " + (newCloud ? "云端" : "本地"), Toast.LENGTH_SHORT).show();
    }

    /** 同步两个模式开关的勾选状态（互斥）：一次性设对并用 updatingSwitch 挡住互相触发。 */
    private void syncModeSwitches(boolean localOnly, boolean onlineOnly) {
        updatingSwitch = true;
        if (switchLocalOnlyMode != null) {
            switchLocalOnlyMode.setChecked(localOnly);
        }
        if (switchOnlineOnlyMode != null) {
            switchOnlineOnlyMode.setChecked(onlineOnly);
        }
        updatingSwitch = false;
    }

    private void updateFavModeValue() {
        if (tvFavModeValue == null) return;
        if (PlaybackMode.isLocalOnly(this)) {
            // 显示「锁定」而不是偷偷沿用上次的值 —— 否则用户会以为云端收藏还有效
            tvFavModeValue.setText("本地（锁定）");
            return;
        }
        boolean isCloud = prefs.getBoolean(PREF_FAV_MODE_CLOUD, false);
        tvFavModeValue.setText(isCloud ? "云端" : "本地");
    }

    private void cycleSleepTimerMode() {
        int current = prefs.getInt(PREF_SLEEP_TIMER_MODE, SLEEP_TIMER_TOTAL);
        int next = (current + 1) % SLEEP_TIMER_LABELS.length;
        prefs.edit().putInt(PREF_SLEEP_TIMER_MODE, next).apply();
        updateSleepTimerModeValue();
        Toast.makeText(this, "定时关闭模式: " + SLEEP_TIMER_LABELS[next], Toast.LENGTH_SHORT).show();
    }

    private void updateSleepTimerModeValue() {
        if (tvSleepTimerModeValue == null) return;
        int mode = prefs.getInt(PREF_SLEEP_TIMER_MODE, SLEEP_TIMER_TOTAL);
        if (mode < 0 || mode >= SLEEP_TIMER_LABELS.length) {
            mode = SLEEP_TIMER_TOTAL;
        }
        tvSleepTimerModeValue.setText(SLEEP_TIMER_LABELS[mode]);
    }

    private void cycleSpeedMode() {
        int current = prefs.getInt("speed_mode", 0);
        int next = (current + 1) % 3;
        prefs.edit().putInt("speed_mode", next).apply();
        MusicPlayerManager.getInstance().setSpeedMode(next);
        updateSpeedModeValue();
        String[] modeNames = {"音调不变", "音调改变且速度改变", "音调改变但速度不变"};
        Toast.makeText(this, "变速模式: " + modeNames[next], Toast.LENGTH_SHORT).show();
        if (next == 2) {
            Toast.makeText(this, "注意：此模式变速幅度不能太大，否则播放可能会出问题（如有杂音等）", Toast.LENGTH_LONG).show();
        }
    }

    private void updateSpeedModeValue() {
        int mode = prefs.getInt("speed_mode", 0);
        String[] labels = {"音调不变", "音调改变且速度改变", "音调改变但速度不变"};
        tvSpeedModeValue.setText(labels[mode]);
    }

    private void cycleRecognitionMode() {
        int current = prefs.getInt(PREF_RECOGNITION_MODE, MODE_AUTO);
        int next = (current + 1) % 2;
        prefs.edit().putInt(PREF_RECOGNITION_MODE, next).apply();
        updateRecognitionModeValue();
        String[] labels = {"手动暂停", "自动识别"};
        Toast.makeText(this, "听歌识曲模式: " + labels[next], Toast.LENGTH_SHORT).show();
    }

    private void updateRecognitionModeValue() {
        int mode = prefs.getInt(PREF_RECOGNITION_MODE, MODE_AUTO);
        String[] labels = {"手动暂停", "自动识别"};
        tvRecognitionModeValue.setText(labels[mode]);
    }

    /**
     * 调整歌词时间轴偏移（direction 为 +1 / -1），每步 0.5 秒。
     *
     * <p>正值表示歌词整体延后出现，负值表示提前。生效方式见 {@link LyricSettings}。</p>
     */
    private void changeLyricOffset(int direction) {
        int offset = LyricSettings.stepOffsetMs(this, direction);
        updateLyricOffsetValue();
        Toast.makeText(this, "歌词偏移: " + LyricSettings.formatOffset(offset),
                Toast.LENGTH_SHORT).show();
    }

    private void updateLyricOffsetValue() {
        if (tvLyricOffsetValue == null) {
            return;
        }
        tvLyricOffsetValue.setText(LyricSettings.formatOffset(LyricSettings.getOffsetMs(this)));
    }

    private void cycleLyricScrollMode() {
        int current = prefs.getInt(PREF_LYRIC_SCROLL_MODE, LYRIC_MODE_BLOCK);
        int next = (current + 1) % 2;
        prefs.edit().putInt(PREF_LYRIC_SCROLL_MODE, next).apply();
        updateLyricScrollModeValue();
        String[] labels = {"每行", "阻塞"};
        Toast.makeText(this, "歌词滚动模式: " + labels[next], Toast.LENGTH_SHORT).show();
    }

    private void updateLyricScrollModeValue() {
        int mode = prefs.getInt(PREF_LYRIC_SCROLL_MODE, LYRIC_MODE_BLOCK);
        String[] labels = {"每行", "阻塞"};
        tvLyricScrollModeValue.setText(labels[mode]);
        // Only show resume interval row in 阻塞 mode
        if (rowLyricResumeInterval != null) {
            rowLyricResumeInterval.setVisibility(mode == LYRIC_MODE_BLOCK
                    ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    private void editLyricResumeInterval() {
        // P5：统一浮窗（输入栏带 ×）
        int current = prefs.getInt(PREF_LYRIC_RESUME_INTERVAL, 3);
        com.qinghe.music163pro.util.WatchPopup.input(this, "歌词恢复间隔（秒）",
                "1~60 秒；无操作超过该时长后恢复跟随", "秒数", String.valueOf(current),
                "确定", text -> {
                    int seconds = 3;
                    try {
                        seconds = Integer.parseInt(text);
                        if (seconds < 1) seconds = 1;
                        if (seconds > 60) seconds = 60;
                    } catch (NumberFormatException ignored) {
                    }
                    prefs.edit().putInt(PREF_LYRIC_RESUME_INTERVAL, seconds).apply();
                    updateLyricResumeIntervalValue();
                    Toast.makeText(this, "歌词恢复间隔: " + seconds + "秒", Toast.LENGTH_SHORT).show();
                });
    }

    private void updateLyricResumeIntervalValue() {
        int seconds = prefs.getInt(PREF_LYRIC_RESUME_INTERVAL, 3);
        tvLyricResumeIntervalValue.setText(seconds + "秒无操作后恢复跟随");
    }
}
