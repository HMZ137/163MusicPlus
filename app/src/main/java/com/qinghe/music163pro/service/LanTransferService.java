package com.qinghe.music163pro.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.qinghe.music163pro.util.LanTransferServer;
import com.qinghe.music163pro.util.LocalLibraryCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 局域网传歌的宿主服务。
 *
 * <p>用服务而不是直接挂在 Activity 上：手表息屏很快，Activity 一进后台线程就会被系统
 * 优先回收，传到一半就断了。前台服务 + 常驻通知既能保活，也把「该关掉它」这件事
 * 明确地摆在用户眼前（一直开着监听端口不好）。</p>
 */
public class LanTransferService extends Service {

    private android.content.BroadcastReceiver wifiWatcher;
    private boolean wifiWatcherRegistered;

    private static final String CHANNEL_ID = "lan_transfer";
    private static final int NOTIFY_ID = 2051;

    /** 进程内的单例，供界面读取状态。 */
    private static volatile LanTransferServer server;

    @Nullable
    public static LanTransferServer getServer() {
        return server;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannelIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LocalLibraryCache.init(this);
        // 需求方 2026-11-15：服务也可能被外部 intent 直接拉起，这里再拦一道
        if (!com.qinghe.music163pro.util.LanNetwork.isWifiConnected(this)) {
            com.qinghe.music163pro.util.UiKit.toast(this,
                    "未连接 WiFi，局域网传歌已停止（不使用移动数据）");
            stopSelf();
            return START_NOT_STICKY;
        }
        registerWifiWatcher();
        if (server == null) {
            server = new LanTransferServer();
            // 注入美化后的上传页（参考 UI.html 的 LAN Drop 设计）；读不到就用内置回退页
            injectLanUploadPage(server);
        }
        if (!server.isRunning()) {
            server.start();
        }
        startForeground(NOTIFY_ID, buildNotification());
        return START_STICKY;
    }

    /** WiFi 断开时立刻关端口并停服，避免回落到移动数据继续监听。 */
    private void registerWifiWatcher() {
        if (wifiWatcherRegistered) {
            return;
        }
        wifiWatcher = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                if (!android.net.ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                    return;
                }
                if (!com.qinghe.music163pro.util.LanNetwork.isWifiConnected(ctx)) {
                    if (server != null) {
                        server.stop();
                    }
                    stopSelf();
                }
            }
        };
        registerReceiver(wifiWatcher,
                new android.content.IntentFilter(
                        android.net.ConnectivityManager.CONNECTIVITY_ACTION));
        wifiWatcherRegistered = true;
    }

    @Override
    public void onDestroy() {
        if (wifiWatcherRegistered) {
            unregisterReceiver(wifiWatcher);
            wifiWatcherRegistered = false;
        }
        if (server != null) {
            server.stop();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 从 assets 读取美化后的上传页并注入到服务。读不到（极端情况）就保持内置回退页。 */
    private void injectLanUploadPage(LanTransferServer server) {
        InputStream ais = null;
        try {
            ais = getAssets().open("lan_upload.html");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = ais.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            server.setIndexHtml(new String(bos.toByteArray(), "UTF-8"));
            Log.i("LanTransferService", "已注入美化上传页 lan_upload.html");
        } catch (Exception e) {
            Log.w("LanTransferService", "读取 lan_upload.html 失败，使用内置回退页", e);
        } finally {
            if (ais != null) {
                try { ais.close(); } catch (Exception ignored) { }
            }
        }
    }

    private Notification buildNotification() {
        String title = "局域网传歌已开启";
        String text;
        if (server != null && server.isRunning()) {
            String ip = LanTransferServer.getLanAddress(this);
            text = ip != null
                    ? "在电脑浏览器打开 http://" + ip + ":" + server.getPort()
                    : "未连接到局域网（端口 " + server.getPort() + "）";
        } else {
            text = "端口启动失败";
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOngoing(true)
                    .build();
        }
        //noinspection deprecation
        return new Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "局域网传歌", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("传歌服务运行时的常驻提示");
        nm.createNotificationChannel(channel);
    }
}
