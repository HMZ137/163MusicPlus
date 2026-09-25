package com.qinghe.music163pro.activity;

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
 * 就能把音乐传到手表（默认落到 {@code /sdcard/Music}）。
 *
 * <p>服务跑在 {@link LanTransferService}（前台服务）里，关掉本页不会立刻中断传输；
 * 但**用完记得回来点停止** —— 一直监听端口没必要，也不安全。</p>
 */
public class LanTransferActivity extends BaseWatchActivity {

    private static final int REQ_STORAGE = 5201;

    private TextView tvStatus;
    private TextView tvUrl;
    private TextView tvDir;
    private TextView tvCount;
    private TextView btnToggle;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocalLibraryCache.init(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF121212);
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
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        android.widget.RelativeLayout.LayoutParams titleParams =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT);
        title.setLayoutParams(titleParams);
        titleBar.addView(title);
        root.addView(titleBar);

        tvStatus = bodyText(0xFFCCCCCC, 14);
        root.addView(tvStatus);

        tvUrl = bodyText(0xFFBB86FC, 15);
        tvUrl.setTextIsSelectable(true);
        root.addView(tvUrl);

        tvDir = bodyText(0xFF9A9A9A, 12);
        root.addView(tvDir);

        tvCount = bodyText(0xFF9A9A9A, 12);
        root.addView(tvCount);

        root.addView(makeSpacer(px(8)));

        btnToggle = new TextView(this);
        btnToggle.setTextColor(0xFF1D1D1D);
        btnToggle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        btnToggle.setGravity(Gravity.CENTER);
        btnToggle.setPadding(px(10), px(10), px(10), px(10));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFBB86FC);
        bg.setCornerRadius(dpToPx(8));
        btnToggle.setBackground(bg);
        btnToggle.setClickable(true);
        btnToggle.setFocusable(true);
        btnToggle.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(0, px(6), 0, px(6));
        btnToggle.setLayoutParams(btnLp);
        root.addView(btnToggle);

        root.addView(bodyText(0xFF757575, 11), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((TextView) root.getChildAt(root.getChildCount() - 1))
                .setText("用法：电脑与手表连同一个 WiFi → 用浏览器打开上面的地址 → "
                        + "选音乐文件上传。用完请回来点「停止」。");
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

    private void refresh() {
        LanTransferServer server = LanTransferService.getServer();
        boolean on = server != null && server.isRunning();
        if (on) {
            String ip = LanTransferServer.getLanAddress(this);
            tvStatus.setText("服务运行中");
            tvUrl.setText(ip != null
                    ? "http://" + ip + ":" + server.getPort()
                    : "未获取到局域网 IP（请确认已连 WiFi）");
            tvDir.setText("保存到：" + server.getSaveDir().getAbsolutePath());
            int count = server.getUploadedCount();
            tvCount.setText(count > 0
                    ? "已接收 " + count + " 个文件（最近：" + server.getLastFileName() + "）"
                    : "尚未接收到文件");
            btnToggle.setText("停止");
        } else {
            tvStatus.setText("服务已停止");
            tvUrl.setText("—");
            tvDir.setText("保存目录：" + LanTransferServer.defaultSaveDir().getAbsolutePath());
            tvCount.setText("点击下面的按钮开始");
            btnToggle.setText("开始");
        }
    }
}
