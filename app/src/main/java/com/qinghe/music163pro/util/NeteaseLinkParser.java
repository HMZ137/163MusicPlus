package com.qinghe.music163pro.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云链接 / 直链的纯文本解析（2026-11-28 新增）。
 *
 * <p>设计约束：<b>这里只做字符串解析，不发网络请求</b>，方便单测、也避免入口页被 IO 卡住。
 * 短链（{@code 163cn.tv/xxx}）需要跟随跳转才能拿到 ID，交给调用方处理。</p>
 */
public final class NeteaseLinkParser {

    private NeteaseLinkParser() {
    }

    /** 解析结果。 */
    public static final class Result {
        public enum Type {
            /** 直接可播放的音频直链（.mp3/.flac/.m4a…）。 */
            DIRECT_AUDIO,
            /** 网易云歌曲 ID。 */
            SONG_ID,
            /** 163cn.tv 这类短链，需联网跟随跳转。 */
            SHORT_LINK,
            /** 认不出来。 */
            UNKNOWN
        }

        public final Type type;
        public final long songId;
        public final String url;

        private Result(Type type, long songId, String url) {
            this.type = type;
            this.songId = songId;
            this.url = url;
        }

        public boolean isPlayable() {
            return type == Type.SONG_ID || type == Type.DIRECT_AUDIO;
        }
    }

    /** 任意 163 域名链接上的 id 参数：{@code ?id=1} / {@code &id=1} / {@code #/song?id=1} 都吃得下 */
    private static final Pattern ID_PARAM = Pattern.compile(
            "(?:music\\.163\\.com|y\\.music\\.163\\.com)[^\\s]*?[?&#]id=(\\d{1,15})",
            Pattern.CASE_INSENSITIVE);
    /** 路径形式：{@code /song/123}、{@code /m/song/123}、{@code /#/song/123} */
    private static final Pattern SONG_PATH = Pattern.compile(
            "(?:music\\.163\\.com|y\\.music\\.163\\.com)/(?:#/|m/)?(?:song|program)/(\\d{1,15})",
            Pattern.CASE_INSENSITIVE);
    /** 纯数字（可能是 ID） */
    private static final Pattern BARE_ID = Pattern.compile("^\\d{1,12}$");
    /** 非单曲的 163 链接（歌单 / 专辑 / 歌手 / 榜单）：id 不是歌曲 ID，别硬当歌播 */
    private static final Pattern NON_SONG_URL = Pattern.compile(
            "music\\.163\\.com/[^\\s]*(?:playlist|album|artist|toplist|djradio|event)",
            Pattern.CASE_INSENSITIVE);

    /** 163cn.tv/xxxx 短链 */
    private static final Pattern SHORT = Pattern.compile(
            "https?://[a-z0-9.]*163cn\\.tv/[A-Za-z0-9]+", Pattern.CASE_INSENSITIVE);
    /** 音频直链 */
    private static final Pattern AUDIO_URL = Pattern.compile(
            "^https?://\\S+\\.(mp3|flac|m4a|aac|wav|ogg|opus)(\\?\\S*)?$",
            Pattern.CASE_INSENSITIVE);
    /** 从任意文本里抠出第一个 http(s) 链接 */
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    /**
     * 解析一段用户输入（搜索框 / 分享文本 / 剪贴板）。
     */
    public static Result parse(String raw) {
        if (raw == null) {
            return new Result(Result.Type.UNKNOWN, 0, null);
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return new Result(Result.Type.UNKNOWN, 0, null);
        }

        // 1) 纯数字：当作歌曲 ID（4 位以上，避免把「1」「12」这种当 ID）
        if (BARE_ID.matcher(text).matches() && text.length() >= 4) {
            return new Result(Result.Type.SONG_ID, Long.parseLong(text), null);
        }

        // 2) 分享文本里可能夹着链接，先抠链接
        String url = text;
        Matcher u = URL_IN_TEXT.matcher(text);
        if (u.find()) {
            url = u.group();
        }

        // 3) 短链优先（短链本身不含 ID）
        if (SHORT.matcher(url).find()) {
            return new Result(Result.Type.SHORT_LINK, 0, url);
        }

        // 4) 音频直链
        if (AUDIO_URL.matcher(url).matches()) {
            return new Result(Result.Type.DIRECT_AUDIO, 0, url);
        }

        // 4.5) 歌单 / 专辑 / 歌手 / 榜单：id 不是歌曲 ID，直接判为不可播，避免播错歌
        if (NON_SONG_URL.matcher(url).find()) {
            return new Result(Result.Type.UNKNOWN, 0, url);
        }

        // 5) 网易云链接里的 id 参数
        //
        // 2026-11-28 修：以前写法是
        //     Matcher m = PATH_ID.matcher(url);
        //     if (!m.find()) m = ANY_ID.matcher(url);
        //     if (m.find()) ...
        // 同一个 Matcher 连调两次 find()，第二次会从上一个匹配之后继续找 ——
        // 只有一处 id 时第二次必然返回 false，于是「带 ?id= 的链接」一律解析失败，
        // 用户必须手动把「网址?id=」删掉、只留数字才能用。这里改成一次性取匹配结果。
        Matcher m = ID_PARAM.matcher(url);
        boolean hit = m.find();
        if (!hit) {
            m = SONG_PATH.matcher(url);
            hit = m.find();
        }
        if (hit) {
            try {
                return new Result(Result.Type.SONG_ID, Long.parseLong(m.group(1)), null);
            } catch (NumberFormatException ignored) {
                // 落到 UNKNOWN
            }
        }

        return new Result(Result.Type.UNKNOWN, 0, url.startsWith("http") ? url : null);
    }

    /** 是否是一个音频直链。 */
    public static boolean isDirectAudioUrl(String url) {
        return parse(url).type == Result.Type.DIRECT_AUDIO;
    }
}
