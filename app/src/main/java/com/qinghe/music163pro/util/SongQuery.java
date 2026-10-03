package com.qinghe.music163pro.util;

import android.text.TextUtils;

import com.qinghe.music163pro.model.Song;

import java.util.Locale;

/**
 * 歌曲关键词匹配 —— 本地音乐页、本地歌单详情、在线歌单详情共用一份口径。
 *
 * <p>匹配字段：曲名 / 曲师 / 专辑 / 文件路径（本地曲目）。忽略大小写。</p>
 *
 * <p>还会再做一次**假名与西里尔的拉丁转写**比对（{@link ScriptTranslit}）：
 * 输入 {@code k} 能命中「かがやき」，{@code miku} 能命中「初音ミク」，
 * 输入西里尔字母也能命中拉丁曲名 —— 因为两边都先转成拉丁再比。</p>
 *
 * <p>抽成公共类是因为这段逻辑先前在本地音乐页里，歌单详情页再各抄一份必然走样
 * （例如忘了转写层，就会出现「本地音乐搜得到、歌单里搜不到」这种不一致）。</p>
 */
public final class SongQuery {

    private SongQuery() {
    }

    /** 把用户输入规整成内部使用的关键词（去空白 + 小写）；空串表示不过滤。 */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 关键词是否命中。
     *
     * @param needle 已经过 {@link #normalize(String)} 的关键词；空串视为命中（不过滤）
     */
    public static boolean matches(Song song, String needle) {
        if (song == null) {
            return false;
        }
        if (TextUtils.isEmpty(needle)) {
            return true;
        }
        if (contains(song.getName(), needle)
                || contains(song.getArtist(), needle)
                || contains(song.getAlbum(), needle)
                || contains(song.getUrl(), needle)) {
            return true;
        }
        final String needleLatin = ScriptTranslit.toLatin(needle);
        if (needleLatin.isEmpty()) {
            return false;
        }
        // 逐个字段比对「全部拉丁候选写法」（含促音符的两种写法）
        return matchAny(ScriptTranslit.toLatinVariants(song.getName()), needleLatin)
                || matchAny(ScriptTranslit.toLatinVariants(song.getArtist()), needleLatin)
                || matchAny(ScriptTranslit.toLatinVariants(song.getAlbum()), needleLatin);
    }

    /** 候选写法里任意一个包含关键词即算命中。 */
    private static boolean matchAny(java.util.List<String> candidates, String needle) {
        if (candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (contains(candidate, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
