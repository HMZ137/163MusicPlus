package com.qinghe.music163pro.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Locale;

/**
 * 「网络直链」播放（2026-11-28）。
 *
 * <p><b>为什么不是直接流播</b>：手表这代 ROM 的 Stagefright 对 HTTP 流很挑，
 * 实测会出现「请求 → 断开 → 重连」死循环（server 侧能看到连接被反复 reset），
 * 声音一直出不来。所以改成<b>先把文件下到缓存目录，再当本地文件播</b> ——
 * 只多等几秒，但稳定，而且本地播放本来就走得通。</p>
 */
public final class DirectPlayback {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DirectPlayback() {
    }

    /**
     * 播放一个网络直链：异步下载 → 落盘 → 本地播放。
     *
     * @return 立即返回 true 表示已受理（真正播放要等下载完成）
     */
    public static boolean play(final Context context, final String url) {
        if (context == null || url == null || url.trim().isEmpty()) {
            return false;
        }
        final String target = url.trim();
        if (!target.toLowerCase(Locale.ROOT).startsWith("http")) {
            return playLocalPath(context, target);
        }
        UiKit.toast(context, "正在下载直链…");
        new Thread(() -> {
            File cached = download(context, target);
            MAIN.post(() -> {
                if (cached == null) {
                    UiKit.toastLong(context, "直链下载失败，请检查地址或网络");
                    return;
                }
                playLocalPath(context, cached.getAbsolutePath());
            });
        }, "direct-download").start();
        return true;
    }

    /** 直接把一个本地路径当歌播（文件必须已存在）。 */
    private static boolean playLocalPath(Context context, String path) {
        try {
            MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
            playerManager.setContext(context);
            Song song = new Song(0L, "网络直链", "直链播放", "");
            song.setUrl(path);
            song.setSource("url");
            song.setForceLocalPlayback(true);
            playerManager.setPlaylist(Collections.singletonList(song), 0);
            playerManager.playCurrent();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static File download(Context context, String url) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            File dir = new File(context.getCacheDir(), "direct");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            String ext = "mp3";
            int dot = url.lastIndexOf('.');
            if (dot > 0) {
                String guess = url.substring(dot + 1);
                int q = guess.indexOf('?');
                if (q >= 0) {
                    guess = guess.substring(0, q);
                }
                if (guess.length() > 0 && guess.length() <= 4) {
                    ext = guess.toLowerCase(Locale.ROOT);
                }
            }
            File out = new File(dir, "direct_" + Math.abs(url.hashCode()) + "." + ext);
            if (out.exists() && out.length() > 0) {
                return out;   // 同一个直链不重复下载
            }
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);
            conn.connect();
            if (conn.getResponseCode() != 200) {
                return null;
            }
            in = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            fos.close();
            return out.length() > 0 ? out : null;
        } catch (Throwable t) {
            return null;
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
