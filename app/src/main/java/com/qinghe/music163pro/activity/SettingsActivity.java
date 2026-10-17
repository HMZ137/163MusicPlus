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
    /** F6：强制覆盖（一次性行为选项，用后自动复位）。 */
    private boolean exportForceOverwrite = false;

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
        // 高级歌词翻译：与「开关选项」同级的独立设置页（AI 词性着色 / 翻译）
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
        // F6：新增「直接导出到 Music 文件夹」；Environment 的公共 Music 目录（API30 旧存储模型下可直接写）
        String musicDir = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
                .getAbsolutePath();
        final String[] targets = {
                musicDir,
                new java.io.File(external, "Music/163Music导出").getAbsolutePath(),
                new java.io.File(external, "Download/163Music导出").getAbsolutePath(),
                new java.io.File(external, "163Music/导出").getAbsolutePath(),
        };
        // 条目单行显示：标签改短，保证「目录名 / 强制覆盖开/关状态」在 1 行内不被省略号吃掉
        // （完整目标路径由下一步的确认浮窗「导出到：<路径>」给出）。
        String[] labels = {"Music 文件夹", "Music/163Music", "Download 目录",
                "163Music/导出",
                exportForceOverwrite ? "强制覆盖：已开" : "强制覆盖：已关",
                "自定义路径…"};
        Runnable[] actions = {
                () -> confirmExport(targets[0]),
                () -> confirmExport(targets[1]),
                () -> confirmExport(targets[2]),
                () -> confirmExport(targets[3]),
                () -> {
                    exportForceOverwrite = !exportForceOverwrite;   // F6：一次性行为选项
                    chooseExportTarget();                          // 重画菜单以显示新状态
                },
                this::promptCustomExportTarget
        };
        // 需求方 2026-10-17：按钮太多了、选项被挤出去（6 个竖排按钮超出手表可视区）。
        // 改为可滚动的统一列表浮窗（WatchMenuDialog）：条目单行、超出可滑，不再把选项挤出屏幕。
        // 标题同样收短到单行可容（原「（复制一份，原下载不受影响）」会被省略号截断）。
        com.qinghe.music163pro.util.WatchMenuDialog.show(this, "导出到文件夹",
                labels, which -> {
                    if (which >= 0 && which < actions.length && actions[which] != null) {
                        actions[which].run();
                    }
                });
    }

    private void promptCustomExportTarget() {
        // P5：统一浮窗（说明在上、输入栏带 ×）
        String initial = new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                "163Music/导出").getAbsolutePath();
        com.qinghe.music163pro.util.WatchPopup.input(this, "目标文件夹",
                "输入完整路径，例如 /sdcard/Music/我的导出",
                "/sdcard/…", initial, "确定", path -> {
                    if (path.isEmpty()) {
                        Toast.makeText(this, "路径不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    confirmExport(path);
                });
    }

    private void confirmExport(final String targetDir) {
        // P5 浮窗高度有限：115% 界面缩放下把 /storage/emulated/0/... 全路径折成 4 行，
        // 会把底部「取消/确定」挤出屏幕。这里统一用 /sdcard 别名 + 压缩成两行。
        String shown = targetDir;
        String root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        if (root != null && !root.isEmpty() && shown != null && shown.startsWith(root)) {
            shown = "/sdcard" + shown.substring(root.length());
        }
        WatchConfirmDialog.show(this, "导出确认",
                "目标：" + shown + "\n"
                        + (exportForceOverwrite ? "同名文件：覆盖" : "同名文件：跳过不覆盖"),
                () -> runExport(targetDir), WatchConfirmDialog.Options.defaultOptions());
    }

    private void runExport(final String targetDir) {
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show();
        if (tvExportValue != null) {
            tvExportValue.setText("导出中…");
        }
        final boolean force = exportForceOverwrite;
        exportForceOverwrite = false;   // 一次性：下次进来回到默认关
        new Thread(() -> ExportManager.exportAll(getApplicationContext(), targetDir, force,
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
