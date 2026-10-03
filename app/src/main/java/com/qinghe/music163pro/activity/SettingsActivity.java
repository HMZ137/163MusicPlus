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
import com.qinghe.music163pro.util.ExportManager;
import com.qinghe.music163pro.util.UpdateChecker;
import com.qinghe.music163pro.util.WatchConfirmDialog;

/**
 * Settings activity - flat tile list style matching MoreActivity.
 * Contains: 开关选项, 检测更新, 关于
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

        // 标题栏要撑到浮动返回按钮的占位高度。这一页原来只剩约 1px 余量 ——
        // 第一行的图标顶边落在 y≈54，而按钮底边在 y=53，字号稍一变化就会压上去。
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(
                this, findViewById(R.id.tv_settings_title));

        // Apply keep screen on setting
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        View btnToggle = findViewById(R.id.btn_settings_toggle);
        View btnEditMore = findViewById(R.id.btn_settings_edit_more);
        // 高级歌词翻译：与「开关选项」同级的独立设置页（AI 词性着色 / 翻译）
        findViewById(R.id.btn_settings_advanced_lyrics).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, LanguageLearningActivity.class)));
        View btnCheckUpdate = findViewById(R.id.btn_settings_check_update);
        View btnHelp = findViewById(R.id.btn_settings_help);
        View btnAbout = findViewById(R.id.btn_settings_about);
        View btnOpenSource = findViewById(R.id.btn_settings_open_source);
        View btnXtcModule = findViewById(R.id.btn_settings_xtc_module);
        View btnBackground = findViewById(R.id.btn_settings_background);
        View btnCache = findViewById(R.id.btn_settings_cache);
        tvCacheValue = findViewById(R.id.tv_settings_cache_value);

        btnToggle.setOnClickListener(v ->
                startActivity(new Intent(this, ToggleSettingsActivity.class)));

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
                startActivity(new Intent(this, BackgroundSettingsActivity.class)));

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
            btnExport.setOnClickListener(v -> chooseExportTarget());
        }
    }

    // ==================== 导出下载的音乐 ====================

    /** 先选目标文件夹：给几个常见位置，也允许自定义完整路径。 */
    private void chooseExportTarget() {
        java.io.File external = android.os.Environment.getExternalStorageDirectory();
        final String[] targets = {
                new java.io.File(external, "Music/163Music导出").getAbsolutePath(),
                new java.io.File(external, "Download/163Music导出").getAbsolutePath(),
                new java.io.File(external, "163Music/导出").getAbsolutePath(),
        };
        String[] labels = {"Music/163Music导出", "Download/163Music导出", "163Music/导出", "自定义…"};
        Runnable[] actions = {
                () -> confirmExport(targets[0]),
                () -> confirmExport(targets[1]),
                () -> confirmExport(targets[2]),
                this::promptCustomExportTarget
        };
        WatchConfirmDialog.showChoice(this, "导出到文件夹",
                "把已下载的歌曲复制一份到目标文件夹，原始下载不受影响。",
                labels, actions, WatchConfirmDialog.Options.defaultOptions());
    }

    private void promptCustomExportTarget() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                "163Music/导出").getAbsolutePath());
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("目标文件夹")
                .setMessage("输入完整路径，例如 /sdcard/Music/我的导出")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    String path = input.getText() == null ? "" : input.getText().toString().trim();
                    if (path.isEmpty()) {
                        Toast.makeText(this, "路径不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    confirmExport(path);
                })
                .show();
    }

    private void confirmExport(final String targetDir) {
        WatchConfirmDialog.show(this, "导出下载的音乐",
                "导出到：\n" + targetDir + "\n\n同名文件会跳过，不会被覆盖。",
                () -> runExport(targetDir), WatchConfirmDialog.Options.defaultOptions());
    }

    private void runExport(final String targetDir) {
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show();
        if (tvExportValue != null) {
            tvExportValue.setText("导出中…");
        }
        new Thread(() -> ExportManager.exportAll(getApplicationContext(), targetDir,
                new ExportManager.ExportCallback() {
                    @Override
                    public void onProgress(int done, int total, String currentName) {
                        runOnUiThread(() -> {
                            if (isFinishing() || tvExportValue == null) return;
                            tvExportValue.setText("导出中 " + done + "/" + total);
                        });
                    }

                    @Override
                    public void onFinished(int copied, int skipped, int failed) {
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            if (tvExportValue != null) {
                                tvExportValue.setText("");
                            }
                            String message;
                            if (failed < 0) {
                                message = "导出失败：无法创建目标文件夹";
                            } else {
                                message = "已导出 " + copied + " 个文件";
                                if (skipped > 0) {
                                    message += "，跳过 " + skipped + " 个";
                                }
                                if (failed > 0) {
                                    message += "，失败 " + failed + " 个";
                                }
                            }
                            Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                        });
                    }
                })).start();
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

    private void confirmClearCache() {
        WatchConfirmDialog.show(this, "清理缓存",
                "清空封面等临时文件？\n不会影响已下载的音乐、收藏和歌单。",
                this::clearCache, WatchConfirmDialog.Options.defaultOptions());
    }

    private void clearCache() {
        Toast.makeText(this, "正在清理…", Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, "正在检测更新...", Toast.LENGTH_SHORT).show();
        UpdateChecker.checkVersionInfo(this, new UpdateChecker.VersionCheckCallback() {
            @Override
            public void onResult(UpdateChecker.VersionInfo versionInfo) {
                if (versionInfo.isLatest()) {
                    Toast.makeText(SettingsActivity.this, "当前已是最新版本", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(SettingsActivity.this, "检测失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
