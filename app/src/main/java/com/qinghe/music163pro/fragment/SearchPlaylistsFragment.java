package com.qinghe.music163pro.fragment;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.AlbumDetailActivity;
import com.qinghe.music163pro.activity.PlaylistDetailActivity;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.AlbumInfo;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.util.NetworkImageLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * S2：搜索结果的「歌单」标签页。
 *
 * <p>按需求把**专辑（type=10）结果并入歌单标签**：两类条目混排在同一列表里，
 * 各自用同一行布局（封面 + 名称 + 一行说明），点击行为按类型分流
 * （歌单 → {@link PlaylistDetailActivity}，专辑 → {@link AlbumDetailActivity}）。</p>
 *
 * <p><b>分页口径</b>：两类结果各自用同一个 offset 并行请求，拼成一页后再回给基类，
 * 避免混排导致 offset 错位（任务 5-7）。</p>
 */
public class SearchPlaylistsFragment extends SearchListFragment<SearchPlaylistsFragment.Entry> {

    /** 混排条目：同一时刻只会有一个字段非空。 */
    public static final class Entry {
        final PlaylistInfo playlist;
        final AlbumInfo album;

        private Entry(PlaylistInfo playlist, AlbumInfo album) {
            this.playlist = playlist;
            this.album = album;
        }

        static Entry of(PlaylistInfo playlist) {
            return new Entry(playlist, null);
        }

        static Entry of(AlbumInfo album) {
            return new Entry(null, album);
        }

        String name() {
            return playlist != null ? playlist.getName() : album.getName();
        }

        String coverUrl() {
            return playlist != null ? playlist.getCoverUrl() : album.getCoverUrl();
        }
    }

    @Override
    protected int itemLayoutRes() {
        return R.layout.item_playlist;
    }

    @Override
    protected int itemTextRes() {
        return R.id.tv_playlist_name;
    }

    @Override
    protected void bindRow(View row, Entry item) {
        TextView tvName = row.findViewById(R.id.tv_playlist_name);
        TextView tvInfo = row.findViewById(R.id.tv_playlist_info);
        ImageView ivCover = row.findViewById(R.id.iv_cover);
        tvName.setText(item.name());
        String info;
        if (item.playlist != null) {
            PlaylistInfo playlistInfo = item.playlist;
            info = playlistInfo.getTrackCount() + "首";
            if (playlistInfo.getCreator() != null && !playlistInfo.getCreator().isEmpty()) {
                info += " · " + playlistInfo.getCreator();
            }
        } else {
            AlbumInfo album = item.album;
            info = album.getTrackCount() > 0 ? "专辑 · " + album.getTrackCount() + "首" : "专辑";
            if (!album.getArtist().isEmpty()) {
                info += " · " + album.getArtist();
            }
        }
        tvInfo.setText(info);
        if (ivCover != null) {
            NetworkImageLoader.load(ivCover, item.coverUrl());
        }
    }

    @Override
    protected String coverUrl(Entry item) {
        return item.coverUrl();
    }

    @Override
    protected void fetchPage(int offset, PageCallback<Entry> callback) {
        // 两类结果并行请求，都回来后交替拼成一页（歌单在前，专辑穿插在后）
        final List<PlaylistInfo> playlists = new ArrayList<>();
        final List<AlbumInfo> albums = new ArrayList<>();
        final int[] pending = {2};
        final boolean[] error = {false};
        final String[] lastError = {null};

        final Runnable finish = () -> {
            if (--pending[0] > 0) {
                return;
            }
            if (error[0] && playlists.isEmpty() && albums.isEmpty()) {
                callback.onError(lastError[0] != null ? lastError[0] : "未知错误");
                return;
            }
            List<Entry> merged = new ArrayList<>();
            int max = Math.max(playlists.size(), albums.size());
            for (int i = 0; i < max; i++) {
                if (i < playlists.size()) {
                    merged.add(Entry.of(playlists.get(i)));
                }
                if (i < albums.size()) {
                    merged.add(Entry.of(albums.get(i)));
                }
            }
            callback.onResult(merged);
        };

        MusicApiHelper.searchPlaylists(keyword(), offset, cookie(),
                new MusicApiHelper.SearchPlaylistCallback() {
                    @Override
                    public void onResult(List<PlaylistInfo> result) {
                        playlists.addAll(result);
                        finish.run();
                    }

                    @Override
                    public void onError(String message) {
                        error[0] = true;
                        lastError[0] = message;
                        finish.run();
                    }
                });
        MusicApiHelper.searchAlbums(keyword(), offset, cookie(),
                new MusicApiHelper.SearchAlbumCallback() {
                    @Override
                    public void onResult(List<AlbumInfo> result) {
                        albums.addAll(result);
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
        if (item.playlist != null) {
            PlaylistInfo playlistInfo = item.playlist;
            Intent intent = new Intent(requireContext(), PlaylistDetailActivity.class);
            intent.putExtra("playlist_id", playlistInfo.getId());
            intent.putExtra("playlist_name", playlistInfo.getName());
            intent.putExtra("track_count", playlistInfo.getTrackCount());
            intent.putExtra("creator", playlistInfo.getCreator());
            intent.putExtra("creator_user_id", playlistInfo.getUserId());
            intent.putExtra("is_liked_playlist", playlistInfo.isLikedPlaylist());
            startActivity(intent);
            return;
        }
        AlbumInfo album = item.album;
        Intent intent = new Intent(requireContext(), AlbumDetailActivity.class);
        intent.putExtra("album_id", album.getId());
        intent.putExtra("album_name", album.getName());
        intent.putExtra("album_cover_url", album.getCoverUrl());
        startActivity(intent);
    }

    @Override
    protected void onEmptyFirstPage() {
        Toast.makeText(requireContext(), "暂无歌单或专辑", Toast.LENGTH_SHORT).show();
    }
}
