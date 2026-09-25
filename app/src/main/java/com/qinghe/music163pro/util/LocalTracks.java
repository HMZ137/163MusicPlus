package com.qinghe.music163pro.util;

import android.text.TextUtils;

import com.qinghe.music163pro.model.Song;

/**
 * 本地（下载来的 / 用户自己拷进来的）音乐的公共判断与标识。
 *
 * <p>集中在一处，是因为「这首歌算不算本地」这个判断被播放页二级菜单、收藏、
 * 列表展示等多处用到，各写一套很容易出现「菜单隐藏了但收藏还走云端」这种不一致。</p>
 */
public final class LocalTracks {

    private LocalTracks() {
    }

    /**
     * 这首歌是否**只能**本地播放。
     *
     * <p>三个判据任一成立即可：</p>
     * <ul>
     *   <li>{@code forceLocalPlayback}：下载列表 / 本地音乐页显式要求的本地播放；</li>
     *   <li>没有网易云 id（{@code id <= 0}）：拿不到歌词、评论、音质、歌单等一切云端能力；</li>
     *   <li>url 是本地文件路径而不是 http(s) 链接。</li>
     * </ul>
     */
    public static boolean isLocalOnly(Song song) {
        if (song == null) {
            return false;
        }
        if (song.isForceLocalPlayback()) {
            return true;
        }
        if (song.getId() <= 0) {
            return true;
        }
        return isLocalPath(song.getUrl());
    }

    /** url 是否是一个本地文件路径。 */
    public static boolean isLocalPath(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase();
        return !lower.startsWith("http://") && !lower.startsWith("https://");
    }

    /**
     * 给本地文件算一个**稳定的负数 id**。
     *
     * <p>本地文件没有网易云 id，而收藏是按 id 去重存取的 —— 直接都用 0 会让所有本地歌
     * 互相覆盖。所以用文件路径的哈希造一个负数：既稳定（同一文件每次一样），
     * 又天然把「本地」这个标签编码进了 id（见 {@link #isLocalId}）。</p>
     *
     * @return 负数 id；路径为空时返回 0
     */
    public static long idForPath(String path) {
        if (TextUtils.isEmpty(path)) {
            return 0;
        }
        long hash = path.hashCode() & 0x7FFFFFFFL;
        // -1 起，避开 0（0 在本项目里表示「没有 id」）
        return -1L - hash;
    }

    /** id 是否代表一条本地收藏。 */
    public static boolean isLocalId(long id) {
        return id < 0;
    }

    /** 本地文件缺曲名时的兜底显示。 */
    public static String safeName(String name) {
        return TextUtils.isEmpty(name) ? "未知曲目" : name;
    }
}
