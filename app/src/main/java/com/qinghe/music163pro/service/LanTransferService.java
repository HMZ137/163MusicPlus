package com.qinghe.music163pro.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.qinghe.music163pro.util.LanTransferServer;
import com.qinghe.music163pro.util.LocalLibraryCache;

/**
 * 局域网传歌的宿主服务。
 *
 * <p>用服务而不是直接挂在 Activity 上：手表息屏很快，Activity 一进后台线程就会被系统
 * 优先回收，传到一半就断了。前台服务 + 常驻通知既能保活，也把「该关掉它」这件事
 * 明确地摆在用户眼前（一直开着监听端口不好）。</p>
 */
public class LanTransferService extends Service {

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
        if (server == null) {
            server = new LanTransferServer();
        }
        if (!server.isRunning()) {
            server.start();
        }
        startForeground(NOTIFY_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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
