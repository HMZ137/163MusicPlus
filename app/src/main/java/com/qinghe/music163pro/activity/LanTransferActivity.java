package com.qinghe.music163pro.activity;

import com.qinghe.music163pro.util.UiKit;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.service.LanTransferService;
import com.qinghe.music163pro.util.LanTransferServer;
import com.qinghe.music163pro.util.LocalLibraryCache;
import com.qinghe.music163pro.util.WatchBackButton;

import java.lang.ref.WeakReference;

/**
 * 局域网传歌：开一个临时 HTTP 服务，同一 WiFi 下的电脑打开 {@code http://<手表IP>:<端口>}
 * 就能把文件传到手表：音乐落到 {@code /sdcard/Music}，其它文件落到 {@code /sdcard/TransFiles}。
 *
 * <p>服务跑在 {@link LanTransferService}（前台服务）里，关掉本页不会立刻中断传输；
 * 但**用完记得回来点停止** —— 一直监听端口没必要，也不安全。</p>
 */
public class LanTransferActivity extends BaseWatchActivity {

    private static final int REQ_STORAGE = 5201;

    private TextView tvStatus;
    private TextView tvUrl;
    private TextView tvCount;
    private TextView btnToggle;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocalLibraryCache.init(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(com.qinghe.music163pro.util.UiKit.COLOR_BG_SURFACE);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(10), px(8), px(10), px(14));
        scroll.addView(root);
        setContentView(scroll);

        // 标题栏：预留左上角浮动返回按钮的占位（与其它页面一致）
        android.widget.RelativeLayout titleBar = new android.widget.RelativeLayout(this);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        WatchBackButton.applyTitleBarInset(this, titleBar);

        TextView title = new TextView(this);
        title.setText("局域网传歌");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.watch_title_text_size));
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        android.widget.RelativeLayout.LayoutParams titleParams =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
titleParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        title.setLayoutParams(titleParams);
        titleBar.addView(title);
        // G5：标题居左；避让由统一入口按返回按钮占位动态补齐（勿手写像素）
        WatchBackButton.alignTitleStart(this, title);
        root.addView(titleBar);

        tvStatus = bodyText(com.qinghe.music163pro.util.UiKit.COLOR_TEXT_MUTED, 13);
        root.addView(tvStatus);

        // ── 地址卡片：大号地址 + 点击复制（信息分区，借鉴 ZeroMusic 的 Server 页） ──
        LinearLayout addrCard = new LinearLayout(this);
        addrCard.setOrientation(LinearLayout.VERTICAL);
        addrCard.setGravity(Gravity.CENTER);
        addrCard.setPadding(px(12), px(12), px(12), px(12));
        addrCard.setBackground(UiKit.card(this));
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addrLp.setMargins(0, px(8), 0, px(8));
        addrCard.setLayoutParams(addrLp);

        TextView addrLabel = bodyText(0xFF9A9A9A, 11);
        addrLabel.setText("电脑 / 手机浏览器打开");
        addrCard.addView(addrLabel);

        tvUrl = bodyText(com.qinghe.music163pro.util.UiKit.COLOR_ACCENT, 19);
        tvUrl.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvUrl.setTextIsSelectable(true);
        tvUrl.setPadding(px(4), px(6), px(4), px(6));
        addrCard.addView(tvUrl);

        TextView addrTip = bodyText(0xFF757575, 11);
        addrTip.setText("点击地址复制到剪贴板");
        addrCard.addView(addrTip);
        addrCard.setOnClickListener(v -> copyUrl());
        root.addView(addrCard);

        tvCount = bodyText(0xFF9A9A9A, 12);
        root.addView(tvCount);

        // ── 三步指引 ──
        TextView steps = bodyText(0xFF8A8A8A, 11);
        steps.setGravity(Gravity.START);
        steps.setText("① 手表与电脑连同一个 Wi-Fi\n"
                + "② 电脑浏览器打开上方地址\n"
                + "③ 选文件上传：音乐→ Music，其它→ TransFiles\n"
                + "④ 用完回这里点「停止」");
        root.addView(steps);

        root.addView(makeSpacer(px(8)));

        // 2026-11-28 M3E：主按钮改用 MaterialButton（胶囊 + 主色 + 涟漪），不再手搓 GradientDrawable
        btnToggle = UiKit.m3Button(this, "开始", true);
        btnToggle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        btnToggle.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(0, px(6), 0, px(6));
        btnToggle.setLayoutParams(btnLp);
        root.addView(btnToggle);

        // 2026-11-28：传完的文件（尤其 APK）在手表上没法打开，给一个极简浏览器入口
        TextView btnBrowse = new TextView(this);
        // 2026-11-28：按钮风格统一走「设置页那种行」：左标题 + 灰色小字说明 + 右侧箭头
        addActionRow(root, "浏览 TransFiles", "传过来的文件都在这里",
                () -> startActivity(new android.content.Intent(this, TransFileBrowserActivity.class)));
        addActionRow(root, "发送文件到其它设备", "扫描同一 Wi-Fi 的手表，用系统选择器挑文件",
                () -> startActivity(new android.content.Intent(this, LanSendActivity.class)));

        root.addView(bodyText(0xFF757575, 10), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((TextView) root.getChildAt(root.getChildCount() - 1))
                .setGravity(Gravity.CENTER);
        ((TextView) root.getChildAt(root.getChildCount() - 1))
                .setText("音乐保存于 /sdcard/Music，其它文件在 /sdcard/TransFiles");
    }

    /** 点地址卡片：复制到剪贴板，省去手动抄 IP。 */
    private void copyUrl() {
        String url = tvUrl.getText() != null ? tvUrl.getText().toString() : "";
        if (!url.startsWith("http")) {
            android.widget.Toast.makeText(this, "服务未启动，暂无地址可复制",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
            android.widget.Toast.makeText(this, "地址已复制",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private TextView bodyText(int color, int sizeSp) {
        TextView tv = new TextView(this);
        tv.setTextColor(color);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(sizeSp));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(px(4), px(4), px(4), px(4));
        return tv;
    }

    private View makeSpacer(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height));
        return v;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshing = true;
        mainHandler.post(new RefreshTick(this));
        // 兜底：安装器返回、或页面切回来时把队列里剩下的接着问（支持连续装多个）
        promptPendingApk();
    }

    @Override
    protected void onPause() {
        refreshing = false;
        super.onPause();
    }

    /** 定时刷新状态（上传计数会变），用弱引用避免 Activity 泄漏。 */
    private static final class RefreshTick implements Runnable {
        private final WeakReference<LanTransferActivity> ref;

        RefreshTick(LanTransferActivity a) {
            ref = new WeakReference<>(a);
        }

        @Override
        public void run() {
            LanTransferActivity a = ref.get();
            if (a == null || !a.refreshing) {
                return;
            }
            a.refresh();
            a.mainHandler.postDelayed(this, 1000);
        }
    }

    private void toggle() {
        LanTransferServer server = LanTransferService.getServer();
        if (server != null && server.isRunning()) {
            stopService(new Intent(this, LanTransferService.class));
            refresh();
            return;
        }
        if (!hasStoragePermission()) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE);
            return;
        }
        startServer();
    }

    private void startServer() {
        // 需求方 2026-11-15：只在 WiFi 下开端口，禁止走移动数据
        if (!com.qinghe.music163pro.util.LanNetwork.isWifiConnected(this)) {
            com.qinghe.music163pro.util.UiKit.toast(this,
                    "请连接 WiFi 后再开启局域网传歌（不使用移动数据）");
            tvStatus.setText("未连接 WiFi，已禁止开启端口");
            return;
        }
        Intent intent = new Intent(this, LanTransferService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        // 服务 onCreate→onStartCommand 有一点延迟，稍后再刷一次状态
        mainHandler.postDelayed(this::refresh, 400);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startServer();
        } else if (requestCode == REQ_STORAGE) {
            tvStatus.setText("需要存储权限才能保存上传的音乐");
        }
    }

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 设置页风格的动作行：整行卡片 + 左标题 + 右小字 + 箭头。
     * 局域网传歌页的两个二级入口用它，和「设置」保持同一套观感。
     */
    private void addActionRow(LinearLayout root, String title, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(16), px(9), px(16), px(9));
        row.setMinimumHeight(px(48));
        row.setBackgroundResource(R.drawable.bg_card_tile);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(6);
        row.setLayoutParams(lp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        t.setSingleLine(true);
        textCol.addView(t);

        TextView subView = new TextView(this);
        subView.setText(sub);
        subView.setTextColor(getResources().getColor(R.color.text_secondary));
        subView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        subView.setSingleLine(true);
        subView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subView.setPadding(0, px(2), 0, 0);
        textCol.addView(subView);
        row.addView(textCol);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(0xFF6B6577);
        arrow.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        arrow.setPadding(px(6), 0, 0, 0);
        row.addView(arrow);

        row.setOnClickListener(v -> action.run());
        root.addView(row);
    }

    private void refresh() {
        LanTransferServer server = LanTransferService.getServer();
        boolean on = server != null && server.isRunning();
        if (on) {
            String ip = LanTransferServer.getLanAddress(this);
            tvStatus.setText("● 服务运行中");
            tvStatus.setTextColor(0xFF7EE2A8);
            tvUrl.setText(ip != null
                    ? "http://" + ip + ":" + server.getPort()
                    : "未获取到局域网 IP（请确认已连 WiFi）");
            int count = server.getUploadedCount();
            tvCount.setText(count > 0
                    ? "本次已接收 " + count + " 个文件 · 最近：" + server.getLastFileName()
                    : "尚未接收到文件，传一个试试");
            btnToggle.setText("停止");
        } else {
            tvStatus.setText("○ 服务已停止");
            tvStatus.setTextColor(0xFF9A9A9A);
            tvUrl.setText("—");
            tvCount.setText("点击下面的按钮开始");
            btnToggle.setText("开始");
        }
    }

    // ==================== 收到 APK：提示安装（2026-11-28） ====================

    /** 正在弹「是否安装」，避免监听回调与 onResume 兜底同时弹两个框。 */
    private boolean apkPromptShowing;

    @Override
    protected void onStart() {
        super.onStart();
        // 只在本页可见时接 APK 回调；离开页面就取消，避免持有已销毁的 Activity
        com.qinghe.music163pro.util.LanTransferServer.setApkReceivedListener(
                apk -> promptPendingApk());
    }

    @Override
    protected void onStop() {
        com.qinghe.music163pro.util.LanTransferServer.setApkReceivedListener(null);
        super.onStop();
    }

    /**
     * 从队列里取一个 APK 问用户是否安装。
     *
     * <p>装完回到本页会再走一次 onResume → 继续问下一个，直到队列空。</p>
     */
    private void promptPendingApk() {
        if (apkPromptShowing || isFinishing()) {
            return;
        }
        final java.io.File apk = com.qinghe.music163pro.util.LanTransferServer.pollPendingApk();
        if (apk == null) {
            return;
        }
        apkPromptShowing = true;
        int remain = com.qinghe.music163pro.util.LanTransferServer.pendingApkCount();
        final com.qinghe.music163pro.util.WatchPopup popup =
                com.qinghe.music163pro.util.WatchPopup.create(this)
                        .title("检测到安装包")
                        .content("收到「" + apk.getName() + "」，是否立即安装？"
                                + (remain > 0 ? "\n装完还有 " + remain + " 个待安装" : ""));
        popup.button("跳过", com.qinghe.music163pro.util.WatchPopup.STYLE_SECONDARY, () -> {
            apkPromptShowing = false;
            promptPendingApk();     // 跳过这个，接着问下一个
        });
        popup.button("安装", com.qinghe.music163pro.util.WatchPopup.STYLE_PRIMARY, () -> {
            apkPromptShowing = false;
            com.qinghe.music163pro.util.ApkInstallHelper.install(this, apk);
            // 装完从安装器返回会走 onResume，继续问队列里剩下的
        });
        popup.show();
    }
}
