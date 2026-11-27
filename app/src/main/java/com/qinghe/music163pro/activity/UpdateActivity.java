package com.qinghe.music163pro.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.qinghe.music163pro.api.ImoowApiHelper;
import com.qinghe.music163pro.util.UpdateChecker;

import java.io.File;
import java.net.URL;
import java.util.List;

/**
 * Shows a "new version available" dialog asking the user to update.
 * Launched when a newer version is detected.
 * Fetches available download sources and lets the user choose one.
 * Handles APK download and installation.
 */
public class UpdateActivity extends BaseWatchActivity {

    private static final String APK_SAVE_PATH =
            Environment.getExternalStorageDirectory() + "/163Music/update.apk";
    private static final int STORAGE_PERMISSION_REQUEST = 200;
    private static final String UPDATE_BULLET = "•";

    private TextView tvProgress;
    private ProgressBar progressBar;
    private LinearLayout sourcesContainer;
    private TextView tvSourceHint;
    private TextView tvVersionInfo;
    private TextView tvUpdateLog;
    private boolean downloading = false;
    private String selectedUrl = null;
    private String targetVersionName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            android.content.SharedPreferences prefs =
                    getSharedPreferences("music163_settings", MODE_PRIVATE);
            if (prefs.getBoolean("keep_screen_on", false)) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Exception ignored) {}

        targetVersionName = getIntent().getStringExtra("target_version_name");
        buildUI();
        loadTargetVersionInfo();
        loadSources();
    }

    private void buildUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF212121);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(px(16), px(24), px(16), px(20));

        TextView tvTitle = makeText("发现新版本", 0xFFFFFFFF, px(20), true, Gravity.CENTER);
        root.addView(tvTitle);

        root.addView(makeSpacer(px(10)));

        TextView tvMsg = makeText("请选择下载源进行更新", 0xFFCCCCCC, px(15), false, Gravity.CENTER);
        root.addView(tvMsg);

        root.addView(makeSpacer(px(6)));

        tvVersionInfo = makeText("最新版本：获取中...", 0xFFAAAAAA, px(13), false, Gravity.CENTER);
        root.addView(tvVersionInfo);

        // 2026-11-21：更新源已改为自有仓库，恢复下载安装能力
        root.addView(makeText("选择下载源后可直接下载并安装更新",
                0xFF888888, px(12), false, Gravity.CENTER));

        root.addView(makeSpacer(px(10)));

        TextView tvUpdateTitle = makeText("更新日志", 0xFFFFFFFF, px(16), true, Gravity.START);
        root.addView(tvUpdateTitle);

        root.addView(makeSpacer(px(4)));

        tvUpdateLog = makeText("正在获取更新日志...", 0xFFAAAAAA, px(13), false, Gravity.START);
        root.addView(tvUpdateLog);

        root.addView(makeSpacer(px(10)));

        // Hint
        tvSourceHint = makeText("选择下载源开始下载",
                0xFF888888, px(12), false, Gravity.CENTER);
        tvSourceHint.setVisibility(android.view.View.GONE);
        root.addView(tvSourceHint);

        root.addView(makeSpacer(px(12)));

        // Sources list container
        sourcesContainer = new LinearLayout(this);
        sourcesContainer.setOrientation(LinearLayout.VERTICAL);
        sourcesContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(sourcesContainer);

        // Loading placeholder
        TextView tvLoading = makeText("正在获取下载源...", 0xFF888888, px(14), false, Gravity.CENTER);
        tvLoading.setTag("loading");
        sourcesContainer.addView(tvLoading);

        root.addView(makeSpacer(px(12)));

        // Progress bar (hidden initially)
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(8));
        progressBar.setLayoutParams(pbParams);
        progressBar.setVisibility(android.view.View.GONE);
        root.addView(progressBar);

        root.addView(makeSpacer(px(4)));

        // Progress text (hidden initially)
        tvProgress = makeText("", 0xFF888888, px(13), false, Gravity.CENTER);
        tvProgress.setVisibility(android.view.View.GONE);
        root.addView(tvProgress);

        root.addView(makeSpacer(px(14)));

        // Cancel button
        TextView btnCancel = new TextView(this);
        btnCancel.setText("取消");
        btnCancel.setTextColor(0xB3FFFFFF);
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15));
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setBackgroundColor(0xFF424242);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnCancel.setLayoutParams(cancelParams);
        btnCancel.setPadding(0, px(12), 0, px(12));
        btnCancel.setClickable(true);
        btnCancel.setFocusable(true);
        btnCancel.setOnClickListener(v -> finish());
        root.addView(btnCancel);

        scrollView.addView(root);
        setContentView(scrollView);
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, tvTitle);
    }

    private void loadTargetVersionInfo() {
        if (targetVersionName != null && !targetVersionName.trim().isEmpty()) {
            targetVersionName = targetVersionName.trim();
            tvVersionInfo.setText("最新版本：" + targetVersionName);
            loadUpdateLog();
            return;
        }
        UpdateChecker.checkVersionInfo(this, new UpdateChecker.VersionCheckCallback() {
            @Override
            public void onResult(UpdateChecker.VersionInfo versionInfo) {
                targetVersionName = versionInfo.getVersionName();
                if (targetVersionName == null) {
                    targetVersionName = "";
                }
                targetVersionName = targetVersionName.trim();
                if (targetVersionName.isEmpty()) {
                    tvVersionInfo.setText("最新版本：获取失败");
                    tvUpdateLog.setText("获取失败");
                    return;
                }
                tvVersionInfo.setText("最新版本：" + targetVersionName);
                loadUpdateLog();
            }

            @Override
            public void onError(String error) {
                tvVersionInfo.setText("最新版本：获取失败");
                tvUpdateLog.setText("获取失败");
            }
        });
    }

    private void loadUpdateLog() {
        // 2026-11-21：更新说明改从自己的 update.json 的 notes 字段读取（不再依赖第三方 API）
        new Thread(() -> {
            final org.json.JSONObject obj = UpdateChecker.fetchManifest();
            runOnUiThread(() -> {
                String notes = obj == null ? "" : obj.optString("notes", "").trim();
                tvUpdateLog.setText(notes.isEmpty() ? "暂无更新说明" : notes);
            });
        }, "update-notes").start();
    }

    private String formatUpdateLog(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "获取失败";
        }
        StringBuilder builder = new StringBuilder();
        for (String item : items) {
            if (item == null || item.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(item.startsWith(UPDATE_BULLET)
                    ? item : UPDATE_BULLET + " " + item);
        }
        return builder.length() == 0 ? "获取失败" : builder.toString();
    }

    private void loadSources() {
        UpdateChecker.fetchSources(new UpdateChecker.SourcesCallback() {
            @Override
            public void onResult(List<String> urls) {
                showSources(urls);
            }

            @Override
            public void onError(String error) {
                sourcesContainer.removeAllViews();
                TextView tvErr = makeText("获取下载源失败: " + error, 0xFFFF6666, px(13), false, Gravity.CENTER);
                sourcesContainer.addView(tvErr);
            }
        });
    }

    private void showSources(List<String> urls) {
        sourcesContainer.removeAllViews();
        if (urls == null || urls.isEmpty()) {
            TextView tvErr = makeText("暂无可用下载源", 0xFF888888, px(14), false, Gravity.CENTER);
            sourcesContainer.addView(tvErr);
            return;
        }

        tvSourceHint.setVisibility(android.view.View.VISIBLE);

        for (int i = 0; i < urls.size(); i++) {
            final String downloadUrl = urls.get(i);
            final int index = i;

            String domain = extractDomain(downloadUrl);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(0xFF2D2D2D);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, px(6));
            row.setLayoutParams(rowParams);
            row.setPadding(px(12), px(10), px(12), px(10));
            row.setClickable(true);
            row.setFocusable(true);

            TextView tvIndex = new TextView(this);
            tvIndex.setText(String.valueOf(index + 1));
            tvIndex.setTextColor(com.qinghe.music163pro.util.UiKit.COLOR_ACCENT);
            tvIndex.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
            tvIndex.setTypeface(tvIndex.getTypeface(), Typeface.BOLD);
            tvIndex.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams idxParams = new LinearLayout.LayoutParams(px(24), LinearLayout.LayoutParams.WRAP_CONTENT);
            tvIndex.setLayoutParams(idxParams);

            TextView tvDomain = new TextView(this);
            tvDomain.setText(domain);
            tvDomain.setTextColor(0xFFFFFFFF);
            tvDomain.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
            tvDomain.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            LinearLayout.LayoutParams domainParams = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            domainParams.setMarginStart(px(8));
            tvDomain.setLayoutParams(domainParams);

            TextView tvDownload = new TextView(this);
            tvDownload.setText("仅提示");
            tvDownload.setTextColor(com.qinghe.music163pro.util.UiKit.COLOR_ACCENT);
            tvDownload.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
            tvDownload.setGravity(Gravity.CENTER);

            row.addView(tvIndex);
            row.addView(tvDomain);
            row.addView(tvDownload);

            row.setOnClickListener(v -> {
                if (!downloading) {
                    selectedUrl = downloadUrl;
                    requestPermissionAndDownload();
                }
            });

            sourcesContainer.addView(row);
        }
    }

    private String extractDomain(String rawUrl) {
        try {
            URL u = new URL(rawUrl);
            return u.getHost();
        } catch (Exception e) {
            return rawUrl;
        }
    }

    /**
     * 已停用「下载并安装更新」。
     *
     * <p>本项目是修改版：装上官方新版会把它整个覆盖掉，返回按钮、多选批量操作、
     * 随机播放修复、下载命名等改动会一起丢失。所以这里改成只提示，
     * 不再申请存储权限、也不再下载 APK。</p>
     */
    private void requestPermissionAndDownload() {
        // 2026-11-21：更新源已改为自有仓库，恢复下载安装
        if (selectedUrl == null || selectedUrl.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "请先选择下载源");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST);
            return;
        }
        startDownload();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDownload();
            } else {
                com.qinghe.music163pro.util.UiKit.toast(this, "需要存储权限才能下载更新");
            }
        }
    }

    private void startDownload() {
        if (downloading || selectedUrl == null || selectedUrl.isEmpty()) {
            return;
        }
        downloading = true;
        final File target = new File(getExternalFilesDir(null), "163MusicPlus_update.apk");
        if (tvProgress != null) {
            tvProgress.setVisibility(android.view.View.VISIBLE);
            tvProgress.setText("开始下载…");
        }
        // 复用通用下载器（内部已处理超时、进度回调切主线程）
        com.qinghe.music163pro.util.UpdateChecker.downloadUpdate(
                selectedUrl, target.getAbsolutePath(),
                new com.qinghe.music163pro.util.UpdateChecker.DownloadCallback() {
                    @Override
                    public void onProgress(int percent) {
                        if (tvProgress != null) {
                            tvProgress.setText("正在下载… " + percent + "%");
                        }
                    }

                    @Override
                    public void onComplete(String filePath) {
                        downloading = false;
                        if (tvProgress != null) {
                            tvProgress.setText("下载完成，正在打开安装界面…");
                        }
                        installApk(filePath);
                    }

                    @Override
                    public void onError(String error) {
                        downloading = false;
                        resetDownloadState();
                        if (tvProgress != null) {
                            tvProgress.setText("下载失败：" + error);
                        }
                        com.qinghe.music163pro.util.UiKit.toastLong(
                                UpdateActivity.this, "下载失败：" + error);
                    }
                });
    }

    private void resetDownloadState() {
        downloading = false;
        for (int i = 0; i < sourcesContainer.getChildCount(); i++) {
            sourcesContainer.getChildAt(i).setClickable(true);
        }
    }

    /**
     * 拉起系统安装器（2026-11-27 修「下载完装不了」）。
     *
     * <p>原来的实现只做了 FileProvider 包装，没校验文件、也没区分失败原因，
     * 结果三种常见故障都只回一句含糊的「安装失败」：</p>
     * <ol>
     *   <li>APK 下到 {@code getExternalFilesDir()}，而 file_provider_paths.xml 没声明
     *       那个 root → {@code getUriForFile()} 抛 IllegalArgumentException，界面拉不起来
     *       （本次同时补了 xml 里的 external-files-path）；</li>
     *   <li>下载被中断，文件 0 字节 / 只有半个包 → 安装器提示「解析软件包时出现问题」；</li>
     *   <li>手表 ROM 没装安装器 Activity → ActivityNotFoundException。</li>
     * </ol>
     * <p>现在分别给出可执行的提示（含 APK 路径），而不是一句「安装失败」。</p>
     */
    private void installApk(String filePath) {
        try {
            File apkFile = new File(filePath);
            if (!apkFile.exists() || apkFile.length() <= 0) {
                resetDownloadState();
                com.qinghe.music163pro.util.UiKit.toastLong(this,
                        "安装包不完整（" + (apkFile.exists() ? "0 字节" : "文件不存在")
                                + "），请重新下载");
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Uri apkUri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", apkFile);
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                intent.setDataAndType(Uri.fromFile(apkFile),
                        "application/vnd.android.package-archive");
            }
            startActivity(intent);
        } catch (IllegalArgumentException e) {
            // FileProvider 找不到匹配的 root（路径没在 file_provider_paths.xml 里声明）
            resetDownloadState();
            com.qinghe.music163pro.util.MusicLog.w("Update", "FileProvider 路径未声明: " + filePath, e);
            com.qinghe.music163pro.util.UiKit.toastLong(this,
                    "安装失败：安装包路径未被授权，请把本页截图反馈给开发者");
        } catch (android.content.ActivityNotFoundException e) {
            resetDownloadState();
            com.qinghe.music163pro.util.UiKit.toastLong(this,
                    "安装失败：系统没有安装器，请用文件管理器打开\n" + filePath);
        } catch (Exception e) {
            resetDownloadState();
            com.qinghe.music163pro.util.UiKit.toastLong(this,
                    "安装失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private TextView makeText(String text, int color, int sizePx, boolean bold, int gravity) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        if (bold) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setGravity(gravity);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private android.view.View makeSpacer(int heightPx) {
        android.view.View spacer = new android.view.View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        return spacer;
    }

}
