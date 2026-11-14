package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.qinghe.music163pro.model.Song;

/**
 * 主界面播放页的歌名显示（需求方 2026-11-15）。
 *
 * <p>本地歌曲文件名统一是「曲名 - 曲师.mp3」，两个开关控制显示：</p>
 * <ul>
 *   <li>{@link #PREF_HIDE_ARTIST}：去掉歌名里的「 - 曲师」后缀（**只对本地歌曲**，
 *       在线歌曲的歌名/别名可能自带 " - "，绝不误切）；</li>
 *   <li>{@link #PREF_STRIP_BRACKETS}：去掉括号内容，如（remix）/(feat. xxx)/【Live】。</li>
 * </ul>
 *
 * <p><b>不误伤在线播放的别名</b>：先对「原始歌名」做裁剪，最后才把
 * {@code transName}（网易云 tns 译名/别名）以「（别名）」形式单独追加 ——
 * 括号规则永远不会吃到别名，在线歌名也不会被去曲师规则切掉。</p>
 */
public final class SongDisplay {

    public static final String PREF_HIDE_ARTIST = "main_hide_artist_suffix";
    public static final String PREF_STRIP_BRACKETS = "main_strip_brackets";

    private static final String PREFS = "music163_settings";

    private SongDisplay() {
    }

    public static boolean hideArtist(Context context) {
        return pref(context, PREF_HIDE_ARTIST);
    }

    public static boolean stripBrackets(Context context) {
        return pref(context, PREF_STRIP_BRACKETS);
    }

    private static boolean pref(Context context, String key) {
        if (context == null) {
            return false;
        }
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getBoolean(key, false);
    }

    /** 主界面播放页专用：按开关裁剪后显示，别名单独保留。 */
    public static String mainTitle(Context context, Song song) {
        if (song == null) {
            return "";
        }
        String base = song.getName() == null ? "" : song.getName();

        // 去曲师：只对本地歌曲（在线歌名可能本身就带 " - "，且要保护在线别名）
        if (hideArtist(context) && isLocal(song)) {
            int dash = base.lastIndexOf(" - ");
            if (dash > 0) {
                base = base.substring(0, dash);
            }
        }
        // 去括号内容：先作用于基础歌名
        if (stripBrackets(context)) {
            base = base.replaceAll("[（(【\\[][^)）】\\]]*[)）】\\]]", "").trim();
        }

        // 别名最后追加：括号规则碰不到它，在线别名完整保留
        String trans = song.getTransName();
        if (trans != null && !trans.isEmpty() && !trans.equals(song.getName())) {
            base = base + "（" + trans + "）";
        }
        return base;
    }

    private static boolean isLocal(Song song) {
        if (song.isForceLocalPlayback()) {
            return true;
        }
        String url = song.getUrl();
        return url != null && !url.isEmpty()
                && !url.startsWith("http://") && !url.startsWith("https://");
    }
}
