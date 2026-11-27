package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;


import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.CacheManager;
import com.qinghe.music163pro.util.UpdateChecker;
import com.qinghe.music163pro.util.WatchConfirmDialog;

/**
 * Settings activity - flat tile list style matching MoreActivity.
 * Contains: 设置（五类分页首页）, 检测更新, 关于
 */
public class SettingsActivity extends BaseWatchActivity {

    private static final String PREFS_NAME = "music163_settings";

    /** 「缓存管理」那一行的占用文案。 */
    private TextView tvCacheValue;
    /** 「导出下载的音乐」那一行的进度文案。 */
    private TextView tvExportValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // 设置项说明「过长先省略 → 点击展开」：本页此前整页没接入，
        // 说明被 ellipsize 截断却点不开。注册放在 setContentView 之后（视图树已稳定）。
        com.qinghe.music163pro.util.UiKit.makeDescriptionsCollapsible(
                findViewById(android.R.id.content));

        // 标题栏要撑到浮动返回按钮的占位高度。这一页原来只剩约 1px 余量 ——
        // 第一行的图标顶边落在 y≈54，而按钮底边在 y=53，字号稍一变化就会压上去。
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(
                this, findViewById(R.id.tv_settings_title));

        // Apply keep screen on setting
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // T1：设置入口页按分区组织（常用 / 页面与外观 / 系统与关于）
        View btnToggle = findViewById(R.id.btn_settings_toggle);
        addSectionHeader(btnToggle, "常用");
        addSectionHeader(findViewById(R.id.btn_settings_edit_more), "页面与外观");
        View btnEditMore = findViewById(R.id.btn_settings_edit_more);
        // T1：「本地歌曲设置」页 —— 只影响本地文件的设置（黑名单 / 响度分析 / 响度平衡 / 后续扫描范围）
        View btnLocalMusicSettings = findViewById(R.id.btn_settings_local_music_settings);
        if (btnLocalMusicSettings != null) {
            btnLocalMusicSettings.setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, LocalMusicSettingsActivity.class)));
        }
        // 高级歌词翻译：与「设置」同级的独立设置页（AI 词性着色 / 翻译）
        findViewById(R.id.btn_settings_advanced_lyrics).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, LanguageLearningActivity.class)));
        addSectionHeader(findViewById(R.id.btn_settings_check_update), "系统与关于");
        View btnCheckUpdate = findViewById(R.id.btn_settings_check_update);
        View btnHelp = findViewById(R.id.btn_settings_help);
        View btnAbout = findViewById(R.id.btn_settings_about);
        View btnOpenSource = findViewById(R.id.btn_settings_open_source);
        View btnXtcModule = findViewById(R.id.btn_settings_xtc_module);
        View btnBackground = findViewById(R.id.btn_settings_background);
        View btnCache = findViewById(R.id.btn_settings_cache);
        tvCacheValue = findViewById(R.id.tv_settings_cache_value);

        // 需求 2026-11-21：设置项按类拆分，入口改成新的设置首页（五类分页）
        btnToggle.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsHomeActivity.class)));

        btnEditMore.setOnClickListener(v ->
                startActivity(new Intent(this, EditMoreActivity.class)));

        btnCheckUpdate.setOnClickListener(v -> checkUpdateManually());

        btnHelp.setOnClickListener(v ->
                startActivity(new Intent(this, HelpActivity.class)));

        btnAbout.setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));

        btnOpenSource.setOnClickListener(v ->
                startActivity(new Intent(this, OpenSourceActivity.class)));

        btnXtcModule.setOnClickListener(v ->
                startActivity(new Intent(this, XtcModuleActivity.class)));

        btnBackground.setOnClickListener(v ->
                startActivity(new Intent(this, BackgroundHubActivity.class)));

        if (btnCache != null) {
            btnCache.setOnClickListener(v -> confirmClearCache());
        }

        View btnStats = findViewById(R.id.btn_settings_stats);
        if (btnStats != null) {
            btnStats.setOnClickListener(v ->
                    startActivity(new Intent(this, PlaybackStatsActivity.class)));
        }

        View btnExport = findViewById(R.id.btn_settings_export);
        tvExportValue = findViewById(R.id.tv_settings_export_value);
        if (btnExport != null) {
            // 2026-11-27：导出流程抽到 util/ExportHelper，下载管理页的「导出音乐」共用同一份
            btnExport.setOnClickListener(v ->
                    com.qinghe.music163pro.util.ExportHelper.chooseTarget(this,
                            new com.qinghe.music163pro.util.ExportHelper.Callback() {
                                @Override
                                public void onStart() {
                                    if (tvExportValue != null) {
                                        tvExportValue.setText("导出中…");
                                    }
                                }

                                @Override
                                public void onProgress(int done, int total, String currentName) {
                                    if (isFinishing() || tvExportValue == null) {
                                        return;
                                    }
                                    tvExportValue.setText("导出中 " + done + "/" + total);
                                }

                                @Override
                                public void onFinished(String message) {
                                    if (tvExportValue != null) {
                                        tvExportValue.setText("");
                                    }
                                }
                            }));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCacheSize();
    }

    /** 后台统计缓存占用，避免在遍历目录时卡住界面。 */
    private void refreshCacheSize() {
        if (tvCacheValue == null) {
            return;
        }
        tvCacheValue.setText("统计中…");
        new Thread(() -> {
            final CacheManager.CacheStat stat = CacheManager.getStat(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || tvCacheValue == null) {
                    return;
                }
                tvCacheValue.setText(CacheManager.formatSize(stat.getTotalBytes())
                        + " · " + stat.fileCount + " 个文件");
            });
        }).start();
    }


    /**
     * T1：设置项归类 —— 在既有页面内插入「分区小标题」（不新建页面、不改任何 prefs 键）。
     *
     * <p>需求方口径（0.3-A5）：「分析现有全部设置项，然后分门别类合理归类到各自的页面，
     * 而不是全部塞到一个页面里」，且「页面不要分的太多」。因此这里按「用户会去哪里找」
     * 在现有页面里分区，而不是再拆新页。</p>
     */
    private void addSectionHeader(android.view.View anchor, String title) {
        if (anchor == null || !(anchor.getParent() instanceof android.view.ViewGroup)) {
            return;
        }
        android.view.ViewGroup content = (android.view.ViewGroup) anchor.getParent();
        int index = content.indexOfChild(anchor);
        if (index < 0) {
            return;
        }
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(0x80FFFFFF);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 11));
        tv.setPadding(com.qinghe.music163pro.util.WatchUiUtils.px(this, 4),
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 10),
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 4),
                com.qinghe.music163pro.util.WatchUiUtils.px(this, 2));
        content.addView(tv, index);
    }

    private void confirmClearCache() {
        WatchConfirmDialog.show(this, "清理缓存",
                "清空封面等临时文件？\n不会影响已下载的音乐、收藏和歌单。",
                this::clearCache, WatchConfirmDialog.Options.defaultOptions());
    }

    private void clearCache() {
        com.qinghe.music163pro.util.UiKit.toast(this, "正在清理…");
        new Thread(() -> {
            // 清理前后各统计一次，才能报出「释放了多少」而不是「剩下多少」
            final long before = CacheManager.getStat(getApplicationContext()).getTotalBytes();
            final boolean ok = CacheManager.clearAll(getApplicationContext());
            final long after = CacheManager.getStat(getApplicationContext()).getTotalBytes();
            final long freed = Math.max(0, before - after);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                Toast.makeText(SettingsActivity.this,
                        ok ? "已清理 " + CacheManager.formatSize(freed)
                                : "部分文件未能清理（已清理 " + CacheManager.formatSize(freed) + "）",
                        Toast.LENGTH_SHORT).show();
                refreshCacheSize();
            });
        }).start();
    }

    private void checkUpdateManually() {
        com.qinghe.music163pro.util.UiKit.toast(this, "正在检测更新...");
        UpdateChecker.checkVersionInfo(this, new UpdateChecker.VersionCheckCallback() {
            @Override
            public void onResult(UpdateChecker.VersionInfo versionInfo) {
                if (versionInfo.isLatest()) {
                    com.qinghe.music163pro.util.UiKit.toast(SettingsActivity.this, "当前已是最新版本");
                } else {
                    Intent intent = new Intent(SettingsActivity.this, UpdateActivity.class);
                    if (versionInfo.getVersionName() != null
                            && !versionInfo.getVersionName().trim().isEmpty()) {
                        intent.putExtra("target_version_name", versionInfo.getVersionName().trim());
                    }
                    startActivity(intent);
                }
            }

            @Override
            public void onError(String error) {
                com.qinghe.music163pro.util.UiKit.toast(SettingsActivity.this, "检测失败: " + error);
            }
        });
    }
}
