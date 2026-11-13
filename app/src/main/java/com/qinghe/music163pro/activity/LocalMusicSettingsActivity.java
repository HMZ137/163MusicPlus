package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.LoudnessBalance;
import com.qinghe.music163pro.util.LoudnessScan;
import com.qinghe.music163pro.util.WatchBackButton;

/**
 * T1：「本地歌曲设置」页 —— 只影响**本地文件**的设置集中在这里。
 *
 * <p>按文档 T1 的归类原则（以「用户会去哪里找这个设置」为准，而非代码归属），
 * 本页承载：黑名单（F3）、响度平衡开关与响度分析（F5）。后续 L6 的扫描范围、F4 的内嵌、
 * F6 的导出目标也应落到本页。</p>
 *
 * <p><b>为什么现在才建：</b>F3/F5 落地时先在 {@code SettingsActivity} 里临时挂了入口
 * （配置页当时还没有），本页建好后把这些条目**原样迁过来**，行 id 与接线逻辑都不变，
 * 设置页只留一个「本地歌曲设置」入口。</p>
 */
public class LocalMusicSettingsActivity extends BaseWatchActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local_music_settings);

        // 与 SettingsActivity 同口径：本页 4 处说明此前被截断却点不开，统一接上折叠展开。
        com.qinghe.music163pro.util.UiKit.makeDescriptionsCollapsible(
                findViewById(android.R.id.content));

        View titleBar = findViewById(R.id.layout_local_music_settings_title_bar);
        WatchBackButton.applyTitleBarInset(this, titleBar);

        // ==================== F5：响度平衡总开关 ====================
        MaterialSwitch swLoudnessBalance = findViewById(R.id.switch_loudness_balance);
        if (swLoudnessBalance != null) {
            swLoudnessBalance.setChecked(LoudnessBalance.isEnabled(this));
            swLoudnessBalance.setOnCheckedChangeListener((buttonView, isChecked) ->
                    LoudnessBalance.setEnabled(this, isChecked));
        }

        // ==================== F5：响度分析（手动触发；再点一次=中断） ====================
        View btnLoudness = findViewById(R.id.btn_settings_loudness);
        final TextView tvLoudnessValue = findViewById(R.id.tv_settings_loudness_value);
        if (btnLoudness != null) {
            btnLoudness.setOnClickListener(v -> {
                if (LoudnessScan.isRunning()) {
                    LoudnessScan.cancel();
                    com.qinghe.music163pro.util.UiKit.toast(this, "正在中断（已完成的部分已保存）");
                    return;
                }
                boolean started = LoudnessScan.start(getApplicationContext(),
                        new LoudnessScan.Listener() {
                            @Override
                            public void onProgress(int done, int total, int analyzed, int cached,
                                                   int skipped, String name) {
                                runOnUiThread(() -> {
                                    if (isFinishing() || tvLoudnessValue == null) {
                                        return;
                                    }
                                    tvLoudnessValue.setText(done + "/" + total);
                                });
                            }

                            @Override
                            public void onFinished(int analyzed, int cached, int skipped,
                                                   boolean cancelled, long ms) {
                                runOnUiThread(() -> {
                                    if (isFinishing()) {
                                        return;
                                    }
                                    if (tvLoudnessValue != null) {
                                        tvLoudnessValue.setText("");
                                    }
                                    Toast.makeText(LocalMusicSettingsActivity.this,
                                            (cancelled ? "已中断：" : "完成：") + "新分析 " + analyzed
                                                    + " 首，命中缓存 " + cached + " 首，跳过 " + skipped + " 首",
                                            Toast.LENGTH_LONG).show();
                                });
                            }
                        });
                Toast.makeText(this, started ? "开始分析响度（后台）…" : "已有分析在跑",
                        Toast.LENGTH_SHORT).show();
            });
        }


        // ==================== L6：自定义扫描目录管理 ====================
        View btnScanDirs = findViewById(R.id.btn_local_settings_scan_dirs);
        if (btnScanDirs != null) {
            btnScanDirs.setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, ScanDirsActivity.class)));
        }
        // ==================== F3：本地黑名单 ====================
        View btnHiddenTracks = findViewById(R.id.btn_settings_hidden);
        if (btnHiddenTracks != null) {
            btnHiddenTracks.setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, HiddenTracksActivity.class)));
        }

        // ==================== 2026-10-17：本地歌曲去重 ====================
        View btnDedupe = findViewById(R.id.btn_settings_dedupe);
        final TextView tvDedupeValue = findViewById(R.id.tv_settings_dedupe_value);
        if (btnDedupe != null) {
            btnDedupe.setOnClickListener(v -> promptDedupe(tvDedupeValue));
        }

        // ==================== F4：内嵌封面/歌词 ====================
        View btnEmbed = findViewById(R.id.btn_settings_embed);
        final TextView tvEmbedValue = findViewById(R.id.tv_settings_embed_value);
        if (btnEmbed != null) {
            btnEmbed.setOnClickListener(v -> promptEmbed(tvEmbedValue));
        }
    }

    // ==================== F4：内嵌封面/歌词 ====================

    /** 内嵌任务是否在跑（避免连点重复起线程）。 */
    private boolean embedRunning;

    /** F4：确认浮窗（带「强制覆盖已内嵌项」复选）→ 后台把 cover.jpg / lyrics.lrc 写进音频文件标签。 */
    private void promptEmbed(final TextView tvValue) {
        if (embedRunning) {
            com.qinghe.music163pro.util.UiKit.toast(this, "正在内嵌，请稍候…");
            return;
        }
        final com.qinghe.music163pro.util.WatchPopup popup =
                com.qinghe.music163pro.util.WatchPopup.create(this)
                        .title("内嵌封面歌词")
                        .description("把 cover.jpg / lyrics.lrc 写进音频文件标签；原文件备份为 .bak，"
                                + "已内嵌过的默认跳过")
                        .checkbox("强制覆盖已内嵌项", false)
                        .cancelButton();
        popup.primaryButton("开始", text -> startEmbed(tvValue, popup.isCheckboxChecked()));
        popup.show();
    }

    /** 后台跑 {@link com.qinghe.music163pro.util.AudioTagEmbedder}，逐目录进度与结果回写行副标题。 */
    private void startEmbed(final TextView tvValue, final boolean force) {
        if (embedRunning) {
            return;
        }
        embedRunning = true;
        final java.io.File root = new java.io.File(
                android.os.Environment.getExternalStorageDirectory(), "163Music/Download");
        if (tvValue != null) {
            tvValue.setText("内嵌中…");
        }
        new Thread(() -> {
            final com.qinghe.music163pro.util.AudioTagEmbedder.Result result;
            try {
                result = com.qinghe.music163pro.util.AudioTagEmbedder.embedAll(
                        root, force, (done, total, folder) -> runOnUiThread(() -> {
                            if (isFinishing() || tvValue == null) {
                                return;
                            }
                            tvValue.setText("内嵌中 " + done + "/" + total);
                        }));
            } catch (Throwable t) {
                // 2026-10-17：以前 OOM 会直接把进程打崩（表现为「重启并退回上一页」）。
                // AudioTagEmbedder 已改流式处理，这里再兜一层，任何异常都只提示、不崩。
                com.qinghe.music163pro.util.MusicLog.w("F4-embed",
                        "内嵌任务异常终止: " + t);
                runOnUiThread(() -> {
                    embedRunning = false;
                    if (isFinishing()) {
                        return;
                    }
                    if (tvValue != null) {
                        tvValue.setText("内嵌失败");
                    }
                    Toast.makeText(LocalMusicSettingsActivity.this,
                            "内嵌失败：" + t.getClass().getSimpleName() + "（详见日志）",
                            Toast.LENGTH_LONG).show();
                });
                return;
            }
            runOnUiThread(() -> {
                embedRunning = false;
                if (isFinishing()) {
                    return;
                }
                if (result.total == 0) {
                    if (tvValue != null) {
                        tvValue.setText("写入歌曲标签");
                    }
                    com.qinghe.music163pro.util.UiKit.toast(LocalMusicSettingsActivity.this, "下载目录为空，没有可内嵌的歌曲");
                    return;
                }
                if (tvValue != null) {
                    // 副标题单行，只放最关键的「内嵌数/总数」；跳过与失败的明细用 Toast 给全。
                    tvValue.setText("完成 " + result.embedded + "/" + result.total);
                }
                Toast.makeText(LocalMusicSettingsActivity.this, result.summary(),
                        Toast.LENGTH_LONG).show();
            });
        }, "f4-embed").start();
    }

    // ==================== 2026-10-17：本地歌曲去重 ====================

    /** 扫描/搬运是否在跑（避免连点重复起线程）。 */
    private boolean dedupeRunning;

    /**
     * 本地歌曲去重（需求方 2026-10-17 深夜）。
     *
     * <p><b>规则</b>：只把「文件大小 + MD5 完全相同」的副本移入
     * {@code 163Music/DuplicatesTrash/<时间戳>/}（每组保留一份、保留目录结构、写 report.txt，
     * 可随时找回）。因此 {@code A专辑歌曲(1)/(2)/(3)} 这种**内容不同**的歌不会被误删。</p>
     */
    private void promptDedupe(final TextView tvValue) {
        if (dedupeRunning) {
            com.qinghe.music163pro.util.UiKit.toast(this, "正在处理，请稍候…");
            return;
        }
        final com.qinghe.music163pro.util.WatchPopup popup =
                com.qinghe.music163pro.util.WatchPopup.create(this)
                        .title("本地歌曲去重")
                        .description("只清理「文件大小 + MD5 完全相同」的副本（每组保留一份），"
                                + "移到 163Music/DuplicatesTrash 可找回；"
                                + "A专辑歌曲(1)(2)(3) 这类内容不同的歌不会动。")
                        .cancelButton();
        popup.primaryButton("扫描", text -> scanDedupe(tvValue));
        popup.show();
    }

    private void scanDedupe(final TextView tvValue) {
        if (dedupeRunning) {
            return;
        }
        dedupeRunning = true;
        if (tvValue != null) {
            tvValue.setText("扫描中…");
        }
        new Thread(() -> {
            final com.qinghe.music163pro.util.LocalDedupe.Plan plan =
                    com.qinghe.music163pro.util.LocalDedupe.scan(
                            com.qinghe.music163pro.util.LocalDedupe.defaultMusicDir(), null);
            runOnUiThread(() -> {
                dedupeRunning = false;
                if (isFinishing()) {
                    return;
                }
                if (plan.groups.isEmpty()) {
                    if (tvValue != null) {
                        tvValue.setText("没有发现重复歌曲");
                    }
                    Toast.makeText(LocalMusicSettingsActivity.this,
                            "没有发现内容完全相同的重复歌曲（已扫描 " + plan.scannedFiles + " 个文件）",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                final int dupCount = plan.duplicateCount();
                final long bytes = plan.duplicateBytes();
                if (tvValue != null) {
                    tvValue.setText("可清理 " + dupCount + " 个 · " + fmtSize(bytes));
                }
                final com.qinghe.music163pro.util.WatchPopup confirm =
                        com.qinghe.music163pro.util.WatchPopup.create(this)
                                .title("发现 " + plan.groups.size() + " 组重复")
                                .description("共 " + dupCount + " 个内容相同的副本（约 " + fmtSize(bytes)
                                        + "）。移入 163Music/DuplicatesTrash 后可随时找回，确定清理？")
                                .cancelButton();
                confirm.dangerButton("移入回收站", () -> doDedupe(plan, tvValue));
                confirm.show();
            });
        }, "local-dedupe-scan").start();
    }

    private void doDedupe(final com.qinghe.music163pro.util.LocalDedupe.Plan plan,
                          final TextView tvValue) {
        if (dedupeRunning) {
            return;
        }
        dedupeRunning = true;
        if (tvValue != null) {
            tvValue.setText("正在移入回收站…");
        }
        new Thread(() -> {
            final com.qinghe.music163pro.util.LocalDedupe.Result result =
                    com.qinghe.music163pro.util.LocalDedupe.moveToTrash(plan,
                            com.qinghe.music163pro.util.LocalDedupe.defaultMusicDir(),
                            com.qinghe.music163pro.util.LocalDedupe.defaultTrashDir());
            runOnUiThread(() -> {
                dedupeRunning = false;
                if (isFinishing()) {
                    return;
                }
                String msg = "已移入回收站 " + result.moved.size() + " 个（" + fmtSize(result.bytes) + "）";
                if (tvValue != null) {
                    tvValue.setText(msg);
                }
                com.qinghe.music163pro.util.UiKit.toastLong(LocalMusicSettingsActivity.this, msg + "，可在 163Music/DuplicatesTrash 找回");
                // 让下一次进「本机音乐」重新扫描（缓存里还留着被移走的文件）
                com.qinghe.music163pro.util.LocalLibraryCache.clear();
            });
        }, "local-dedupe-move").start();
    }

    private static String fmtSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }
}
