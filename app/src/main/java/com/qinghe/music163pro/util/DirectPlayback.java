package com.qinghe.music163pro.util;

import android.content.Context;

import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;

import java.util.Collections;

/**
 * 「网络直链」播放（2026-11-28）。
 *
 * <p>把「打开链接」「搜索框粘贴直链」两条链路收在一处：建一条 source=url 的临时歌曲，
 * 交给播放器直接出流，不查询也不入库。</p>
 */
public final class DirectPlayback {

    private DirectPlayback() {
    }

    /** @return true 表示已交给播放器 */
    public static boolean play(Context context, String url) {
        if (context == null || url == null || url.trim().isEmpty()) {
            return false;
        }
        try {
            MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
            playerManager.setContext(context);
            Song song = new Song(0L, "网络直链", "直链播放", "");
            song.setUrl(url.trim());
            song.setSource("url");
            playerManager.setPlaylist(Collections.singletonList(song), 0);
            playerManager.playCurrent();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
