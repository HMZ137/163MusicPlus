package com.qinghe.music163pro.util;

import android.content.Context;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.model.Song;

import java.util.ArrayList;
import java.util.List;

/**
 * 「加入本地歌单」选择器（需求 2026-11-21 任务 3）。
 *
 * <p>在线歌曲与本地歌曲同列表处理：选歌单 → 落盘（在线歌曲带正 id + 封面，路径为空，
 * 播放时走网络）。失效/无版权由播放侧按原有方式提示。</p>
 */
public final class LocalPlaylistPicker {

    private LocalPlaylistPicker() {
    }

    /**
     * 弹出歌单选择，把 songs 加入所选（或新建的）本地歌单。
     *
     * @param onAdded 添加成功后的回调（可为 null），一般用于刷新列表
     */
    public static void show(android.app.Activity context, List<Song> songs, Runnable onAdded) {
        if (context == null || songs == null || songs.isEmpty()) {
            return;
        }
        final List<String> names = LocalPlaylistManager.listPlaylistNames();
        final List<String> choices = new ArrayList<>(names);
        choices.add("＋ " + context.getString(R.string.new_playlist));
        WatchMenuDialog.show(context, context.getString(R.string.add_to_local_playlist),
                choices.toArray(new String[0]), which -> {
                    if (which < 0 || which >= choices.size()) {
                        return;
                    }
                    if (which == choices.size() - 1) {
                        WatchPopup.input(context, "新建歌单", "输入歌单名称", "名称", "",
                                "创建", name -> {
                                    if (name == null || name.trim().isEmpty()) {
                                        return;
                                    }
                                    String trimmed = name.trim();
                                    LocalPlaylistManager.createPlaylist(trimmed);
                                    int added = LocalPlaylistManager.addSongs(trimmed, songs);
                                    UiKit.toast(context, context.getString(
                                            R.string.added_to_playlist, added));
                                    if (onAdded != null) {
                                        onAdded.run();
                                    }
                                });
                        return;
                    }
                    String name = names.get(which);
                    int added = LocalPlaylistManager.addSongs(name, songs);
                    UiKit.toast(context, added > 0
                            ? context.getString(R.string.added_to_playlist, added)
                            : "所选歌曲都已在该歌单中");
                    if (onAdded != null) {
                        onAdded.run();
                    }
                });
    }
}
