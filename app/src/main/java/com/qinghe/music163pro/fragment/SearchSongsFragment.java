package com.qinghe.music163pro.fragment;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.MainActivity;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.model.SoundInfo;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * S2：搜索结果的「单曲」标签页。
 *
 * <p>按需求把**声音（type=2000，电台节目）结果并入单曲标签**：声音条目与单曲混排在同一个列表，
 * 点击声音时用它的 {@code mainSong} 构造 {@link Song}（播放内核会按 id 去 {@code getSongUrl} 取地址），
 * 与单曲一样进入播放队列（任务 5-4 / 5-6）。</p>
 */
public class SearchSongsFragment extends SearchListFragment<SearchSongsFragment.Entry> {

    /** 混排条目：同一时刻只会有一个字段非空。 */
    public static final class Entry {
        final Song song;
        final SoundInfo sound;

        private Entry(Song song, SoundInfo sound) {
            this.song = song;
            this.sound = sound;
        }

        static Entry of(Song song) {
            return new Entry(song, null);
        }

        static Entry of(SoundInfo sound) {
            return new Entry(null, sound);
        }

        String displayName() {
            if (song != null) {
                return song.getDisplayName();
            }
            return sound.getMainSongName().isEmpty() ? sound.getName() : sound.getMainSongName();
        }

        String displayArtist() {
            if (song != null) {
                return song.getArtist() == null ? "" : song.getArtist();
            }
            if (!sound.getDjName().isEmpty()) {
                return "声音 · " + sound.getDjName();
            }
            return sound.getMainSongArtist();
        }

        String cover() {
            return song != null ? song.getCoverUrl() : sound.getCoverUrl();
        }

        /** 转成可播放的歌曲；声音没有主歌 id 时返回 null。 */
        Song playable() {
            if (song != null) {
                return song;
            }
            if (sound.getMainSongId() <= 0) {
                return null;
            }
            String name = sound.getMainSongName().isEmpty() ? sound.getName() : sound.getMainSongName();
            String artist = sound.getMainSongArtist().isEmpty()
                    ? sound.getDjName() : sound.getMainSongArtist();
            Song playable = new Song(sound.getMainSongId(), name, artist, "");
            if (!sound.getCoverUrl().isEmpty()) {
                playable.setCoverUrl(sound.getCoverUrl());
            }
            return playable;
        }
    }

    @Override
    protected int pageIndex() {
        return 0;
    }

    @Override
    protected CharSequence pageName() {
        return "单曲";
    }

    @Override
    protected int itemLayoutRes() {
        return R.layout.item_song;
    }

    @Override
    protected int itemTextRes() {
        return R.id.tv_item_name;
    }

    @Override
    protected void bindRow(View row, Entry item) {
        TextView tvName = row.findViewById(R.id.tv_item_name);
        TextView tvArtist = row.findViewById(R.id.tv_item_artist);
        ImageView ivCover = row.findViewById(R.id.iv_cover);
        tvName.setText(item.displayName());
        tvArtist.setText(item.displayArtist());
        if (ivCover != null) {
            NetworkImageLoader.load(ivCover, item.cover());
        }
    }

    @Override
    protected String coverUrl(Entry item) {
        return item.cover();
    }

    @Override
    protected void fetchPage(int offset, PageCallback<Entry> callback) {
        // 单曲与声音并行请求，都回来后交替拼成一页（单曲在前，声音穿插在后）
        final List<Song> songs = new ArrayList<>();
        final List<SoundInfo> sounds = new ArrayList<>();
        final int[] pending = {2};
        final boolean[] error = {false};
        final String[] lastError = {null};

        final Runnable finish = () -> {
            if (--pending[0] > 0) {
                return;
            }
            if (error[0] && songs.isEmpty() && sounds.isEmpty()) {
                callback.onError(lastError[0] != null ? lastError[0] : "未知错误");
                return;
            }
            List<Entry> merged = new ArrayList<>();
            int max = Math.max(songs.size(), sounds.size());
            for (int i = 0; i < max; i++) {
                if (i < songs.size()) {
                    merged.add(Entry.of(songs.get(i)));
                }
                if (i < sounds.size()) {
                    merged.add(Entry.of(sounds.get(i)));
                }
            }
            callback.onResult(merged);
        };

        MusicApiHelper.searchSongs(keyword(), offset, cookie(),
                new MusicApiHelper.SearchCallback() {
                    @Override
                    public void onResult(List<Song> result) {
                        songs.addAll(result);
                        finish.run();
                    }

                    @Override
                    public void onError(String message) {
                        error[0] = true;
                        lastError[0] = message;
                        finish.run();
                    }
                });
        MusicApiHelper.searchSounds(keyword(), offset, cookie(),
                new MusicApiHelper.SearchSoundCallback() {
                    @Override
                    public void onResult(List<SoundInfo> result) {
                        sounds.addAll(result);
                        finish.run();
                    }

                    @Override
                    public void onError(String message) {
                        error[0] = true;
                        lastError[0] = message;
                        finish.run();
                    }
                });
    }

    @Override
    protected void onItemClicked(int position, Entry item) {
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        // 用当前列表里所有「可播放」条目组成队列；声音若不携带主歌 id 则单独回退
        List<Song> playlist = new ArrayList<>();
        int target = -1;
        for (int i = 0; i < items.size(); i++) {
            Song playable = items.get(i).playable();
            if (playable == null) {
                continue;
            }
            if (i == position) {
                target = playlist.size();
            }
            playlist.add(playable);
        }
        if (target < 0) {
            Song single = item.playable();
            if (single == null) {
                com.qinghe.music163pro.util.UiKit.toast(requireContext(), "该声音暂无可播放的主歌");
                return;
            }
            playlist.clear();
            playlist.add(single);
            target = 0;
        }
        playerManager.setPlaylist(playlist, target);
        playerManager.playCurrent();
        Intent intent = new Intent(requireContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        requireActivity().finish();
    }

    @Override
    protected void onEmptyFirstPage() {
        com.qinghe.music163pro.util.UiKit.toast(requireContext(), getString(R.string.no_song));
    }
}
