package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.LanDiscovery;
import com.qinghe.music163pro.util.LanTransferServer;
import com.qinghe.music163pro.util.UiKit;
import com.qinghe.music163pro.util.WatchBackButton;
import com.qinghe.music163pro.util.WatchMenuDialog;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 手表端「发送文件」（2026-11-28 组网互传 / 二次改版）。
 *
 * <p>文件选择改用<b>系统文件选择器</b>（SAF，任意目录都能选），不再只扫 Music / TransFiles；
 * 设备列表沿用统一的 {@link WatchMenuDialog}，页面样式与「局域网传歌」一致
 * （卡片行 + 强调色大圆角按钮）。</p>
 */
public class LanSendActivity extends BaseWatchActivity {

    private static final int REQ_PICK = 0x2A;

    /** 选中的文件（content:// 或 file:// 都能读）。 */
    private static final class Picked {
        final Uri uri;
        final String name;
        final long size;

        Picked(Uri uri, String name, long size) {
            this.uri = uri;
            this.name = name;
            this.size = size;
        }
    }

    private TextView tvDevice;
    private TextView tvFiles;
    private TextView tvProgress;
    private TextView btnSend;

    private LanDiscovery.Peer target;
    /** 目标是「网页端」：文件复制到 TransFiles 并登记进收件箱，网页可下载。 */
    private boolean webTarget;
    private final List<Picked> picked = new ArrayList<>();
    private boolean sending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.bg_dark));
        root.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(10));

        TextView title = new TextView(this);
        title.setText("发送文件");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.watch_title_text_size));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dpToPx(4), 0, dpToPx(10));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        WatchBackButton.applyTitleBarInset(this, title);

        LinearLayout rowDevice = addRow(root, "目标设备", "点这里扫描同一 Wi-Fi 的设备");
        tvDevice = (TextView) rowDevice.getChildAt(1);
        rowDevice.setOnClickListener(v -> scanDevices());

        LinearLayout rowFiles = addRow(root, "待发文件", "点这里打开系统文件选择器");
        tvFiles = (TextView) rowFiles.getChildAt(1);
        rowFiles.setOnClickListener(v -> pickFiles());

        tvProgress = new TextView(this);
        tvProgress.setTextColor(getResources().getColor(R.color.text_secondary));
        tvProgress.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        tvProgress.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(4));
        tvProgress.setText("先选目标设备，再选文件");
        root.addView(tvProgress);

        // 与「局域网传歌」主按钮同款：强调色 + 大圆角
        btnSend = new TextView(this);
        btnSend.setText("发送");
        btnSend.setTextColor(0xFF1D1D1D);
        btnSend.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        btnSend.setGravity(Gravity.CENTER);
        btnSend.setPadding(px(10), px(12), px(10), px(12));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(UiKit.COLOR_ACCENT);
        bg.setCornerRadius(UiKit.dp(this, UiKit.DP_BUTTON_RADIUS));
        btnSend.setBackground(bg);
        btnSend.setClickable(true);
        btnSend.setFocusable(true);
        btnSend.setOnClickListener(v -> startSend());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(0, px(8), 0, px(4));
        btnSend.setLayoutParams(btnLp);
        root.addView(btnSend);

        setContentView(root);
        refreshSummary();
    }

    /** 一行「标题 + 说明」卡片；说明是第 1 个子 View。 */
    private LinearLayout addRow(LinearLayout root, String title, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dpToPx(14), dpToPx(9), dpToPx(14), dpToPx(9));
        row.setBackgroundResource(R.drawable.bg_card_tile);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dpToPx(6);
        row.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(t);

        TextView subView = new TextView(this);
        subView.setText(sub);
        subView.setTextColor(getResources().getColor(R.color.text_secondary));
        subView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        subView.setPadding(0, dpToPx(2), 0, 0);
        row.addView(subView);

        root.addView(row);
        return row;
    }

    // ==================== 选设备 ====================

    private void scanDevices() {
        tvDevice.setText("正在扫描…");
        new Thread(() -> {
            String selfIp = LanTransferServer.getLanAddress(LanSendActivity.this);
            int selfPort = 0;
            try {
                LanTransferServer running =
                        com.qinghe.music163pro.service.LanTransferService.getServer();
                if (running != null && running.isRunning()) {
                    selfPort = running.getPort();
                }
            } catch (Throwable ignored) {
            }
            final List<LanDiscovery.Peer> peers = LanDiscovery.scan(selfPort, selfIp);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (peers.isEmpty()) {
                    tvDevice.setText("没发现其它手表；点这里仍可发给「网页端」");
                }
                // 第 0 项固定是「网页端」：手表不能主动推给浏览器，
                // 选它就把文件登记进收件箱，网页「已接收」里能直接下载
                String[] labels = new String[peers.size() + 1];
                labels[0] = "网页端（浏览器下载）";
                for (int i = 0; i < peers.size(); i++) {
                    LanDiscovery.Peer p = peers.get(i);
                    labels[i + 1] = p.name + " · " + p.ip + ":" + p.port;
                }
                tvDevice.setText((peers.isEmpty() ? "没发现其它手表" : "发现 " + peers.size() + " 台")
                        + "，点这里重选");
                WatchMenuDialog.show(this, "选择设备", labels, which -> {
                    if (which == 0) {
                        target = null;
                        webTarget = true;
                        refreshSummary();
                        return;
                    }
                    int idx = which - 1;
                    if (idx < 0 || idx >= peers.size()) {
                        return;
                    }
                    target = peers.get(idx);
                    webTarget = false;
                    refreshSummary();
                });
            });
        }, "lan-scan").start();
    }

    // ==================== 选文件（系统选择器） ====================

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_PICK);
        } catch (Exception e) {
            UiKit.toastLong(this, "系统没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        picked.clear();
        try {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    addPicked(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                addPicked(data.getData());
            }
        } catch (Exception e) {
            UiKit.toastLong(this, "读取所选文件失败");
        }
        refreshSummary();
    }

    private void addPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        String name = null;
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = c.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0) {
                    name = c.getString(nameIdx);
                }
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                    size = c.getLong(sizeIdx);
                }
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment() == null ? "文件" : uri.getLastPathSegment();
        }
        picked.add(new Picked(uri, name, size));
        try {
            // 长期授权：发送时（甚至重进页面后）还能读这个 Uri
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    // ==================== 发送 ====================

    private void refreshSummary() {
        if (webTarget) {
            tvDevice.setText("网页端（浏览器下载）· 点这里重选");
        } else if (target != null) {
            tvDevice.setText(target.name + " · " + target.ip + ":" + target.port + "（点击重选）");
        }
        tvFiles.setText(picked.isEmpty() ? "点这里打开系统文件选择器"
                : picked.size() + " 个文件：" + picked.get(0).name
                + (picked.size() > 1 ? " 等" : ""));
        boolean ready = (webTarget || target != null) && !picked.isEmpty() && !sending;
        btnSend.setAlpha(ready ? 1f : 0.45f);
    }

    private void startSend() {
        if (sending) {
            return;
        }
        if (target == null && !webTarget) {
            UiKit.toast(this, "请先选择目标设备");
            return;
        }
        if (picked.isEmpty()) {
            UiKit.toast(this, "请先选择要发送的文件");
            return;
        }
        sending = true;
        btnSend.setAlpha(0.45f);
        final List<Picked> files = new ArrayList<>(picked);
        final LanDiscovery.Peer peer = target;
        new Thread(() -> {
            if (webTarget) {
                sendToWeb(files);
                return;
            }
            int ok = 0;
            for (int i = 0; i < files.size(); i++) {
                final int index = i;
                final Picked f = files.get(i);
                runOnUiThread(() -> tvProgress.setText("发送中 " + (index + 1) + "/" + files.size()
                        + "：" + f.name));
                if (postFile(f, peer)) {
                    ok++;
                }
            }
            final int done = ok;
            runOnUiThread(() -> {
                sending = false;
                tvProgress.setText("发送完成：" + done + "/" + files.size() + " 个成功");
                refreshSummary();
                UiKit.toast(LanSendActivity.this, "已发送 " + done + " 个文件到 " + peer.name);
            });
        }, "lan-send").start();
    }

    /**
     * 「发给网页端」：把文件复制到 TransFiles 并登记进收件箱，
     * 网页的「已接收」里立刻能下载（不需要浏览器一直开着）。
     */
    private void sendToWeb(List<Picked> files) {
        LanTransferServer server = com.qinghe.music163pro.service.LanTransferService.getServer();
        java.io.File dir = LanTransferServer.defaultTransFilesDir();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        int ok = 0;
        for (int i = 0; i < files.size(); i++) {
            final int index = i;
            final Picked f = files.get(i);
            runOnUiThread(() -> tvProgress.setText("发送到网页端 " + (index + 1) + "/" + files.size()
                    + "：" + f.name));
            java.io.File out = new java.io.File(dir, f.name);
            try (InputStream in = getContentResolver().openInputStream(f.uri);
                 java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                if (in == null) {
                    continue;
                }
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
                if (server != null && server.publishToWeb(out, "本机")) {
                    ok++;
                }
            } catch (Exception e) {
                com.qinghe.music163pro.util.MusicLog.w("LanSend", "投递网页端失败 " + f.name, e);
            }
        }
        final int done = ok;
        runOnUiThread(() -> {
            sending = false;
            tvProgress.setText("已投递到网页端：" + done + "/" + files.size()
                    + " 个（在网页「已接收」里下载）");
            refreshSummary();
            UiKit.toast(LanSendActivity.this, "已投递 " + done + " 个文件，去网页下载");
        });
    }

    /** 直接 POST 到对方的 /upload（复用现有接收服务，不另开端口）。 */
    private boolean postFile(Picked file, LanDiscovery.Peer peer) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            String name = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20");
            StringBuilder url = new StringBuilder(peer.url())
                    .append("/upload?name=").append(name);
            if (file.name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                url.append("&kind=apk");
            }
            in = getContentResolver().openInputStream(file.uri);
            if (in == null) {
                return false;
            }
            long length = file.size;
            conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            if (length > 0) {
                conn.setFixedLengthStreamingMode(length);
            } else {
                // 部分 provider 不给 SIZE，退化成 chunked（服务端按 Content-Length 读，
                // 这种情况下可能记成 0 字节，属已知限制）
                conn.setChunkedStreamingMode(32 * 1024);
            }
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            OutputStream out = conn.getOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            com.qinghe.music163pro.util.MusicLog.w("LanSend", "发送失败 " + file.name, e);
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
