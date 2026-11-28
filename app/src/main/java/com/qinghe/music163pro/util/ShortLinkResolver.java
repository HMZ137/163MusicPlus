package com.qinghe.music163pro.util;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 短链解析（2026-11-28 新增）：{@code 163cn.tv/xxxx} 这类链接要先跟随 302
 * 才能知道它指向歌曲、歌单、专辑还是 MV。
 *
 * <p><b>会阻塞</b>：调用方必须在后台线程执行。超时 8s，只读响应头不读 body。</p>
 */
public final class ShortLinkResolver {

    private ShortLinkResolver() {
    }

    /** 短链指向的资源类型。 */
    public enum Type {
        SONG, PLAYLIST, ALBUM, MV, UNKNOWN
    }

    /** 解析结果。 */
    public static final class Target {
        public final Type type;
        public final long id;
        public final String finalUrl;

        Target(Type type, long id, String finalUrl) {
            this.type = type;
            this.id = id;
            this.finalUrl = finalUrl;
        }
    }

    private static final Pattern SONG = Pattern.compile("/song[/?].*?id=(\\d{1,15})");
    private static final Pattern PLAYLIST = Pattern.compile("/(?:playlist|my/m/music/playlist)[/?].*?id=(\\d{1,15})");
    private static final Pattern ALBUM = Pattern.compile("/album[/?].*?id=(\\d{1,15})");
    private static final Pattern MV = Pattern.compile("/mv[/?].*?id=(\\d{1,15})");
    private static final Pattern ANY_ID = Pattern.compile("[?&]id=(\\d{1,15})");

    /**
     * 跟随跳转并识别目标类型。失败抛 IOException，由调用方提示。
     */
    public static Target resolve(String shortUrl) throws IOException {
        String finalUrl = followRedirects(shortUrl);
        if (finalUrl == null) {
            return new Target(Type.UNKNOWN, 0, null);
        }
        Target t = match(finalUrl, SONG, Type.SONG);
        if (t != null) {
            return t;
        }
        t = match(finalUrl, PLAYLIST, Type.PLAYLIST);
        if (t != null) {
            return t;
        }
        t = match(finalUrl, ALBUM, Type.ALBUM);
        if (t != null) {
            return t;
        }
        t = match(finalUrl, MV, Type.MV);
        if (t != null) {
            return t;
        }
        // 兜底：认不出路径但带着 id，按歌曲处理（网易云分享页最常见的落地形态）
        Matcher m = ANY_ID.matcher(finalUrl);
        if (m.find()) {
            return new Target(Type.SONG, Long.parseLong(m.group(1)), finalUrl);
        }
        return new Target(Type.UNKNOWN, 0, finalUrl);
    }

    private static Target match(String url, Pattern p, Type type) {
        Matcher m = p.matcher(url);
        if (!m.find()) {
            return null;
        }
        try {
            return new Target(type, Long.parseLong(m.group(1)), url);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 手动跟随 302（不依赖 HttpURLConnection 的自动跳转，便于看清每一跳）。 */
    private static String followRedirects(String url) throws IOException {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 11; Watch) AppleWebKit/537.36 Chrome/110 Mobile");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        return current;
                    }
                    current = loc.startsWith("http") ? loc
                            : new URL(new URL(current), loc).toString();
                    continue;
                }
                return current;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return current;
    }
}
