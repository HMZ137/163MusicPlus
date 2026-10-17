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
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;

import java.util.ArrayList;
import java.util.List;

/** S1：搜索结果的「单曲」标签页。 */
public class SearchSongsFragment extends SearchListFragment<Song> {

    @Override
    protected int itemLayoutRes() {
        return R.layout.item_song;
    }

    @Override
    protected int itemTextRes() {
        return R.id.tv_item_name;
    }

    @Override
    protected void bindRow(View row, Song song) {
        TextView tvName = row.findViewById(R.id.tv_item_name);
        TextView tvArtist = row.findViewById(R.id.tv_item_artist);
        ImageView ivCover = row.findViewById(R.id.iv_cover);
        tvName.setText(song.getDisplayName());
        tvArtist.setText(song.getArtist());
        if (ivCover != null) {
            NetworkImageLoader.load(ivCover, song.getCoverUrl());
        }
    }

    @Override
    protected String coverUrl(Song song) {
        return song.getCoverUrl();
    }

    @Override
    protected void fetchPage(int offset, PageCallback<Song> callback) {
        MusicApiHelper.searchSongs(keyword(), offset, cookie(), new MusicApiHelper.SearchCallback() {
            @Override
            public void onResult(List<Song> songs) {
                callback.onResult(songs);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    protected void onItemClicked(int position, Song song) {
        // 播放队列 = 当前搜索结果（与原实现一致），再跳回主界面播放页
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        playerManager.setPlaylist(new ArrayList<>(items), position);
        playerManager.playCurrent();
        Intent intent = new Intent(requireContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        requireActivity().finish();
    }

    @Override
    protected void onEmptyFirstPage() {
        Toast.makeText(requireContext(), R.string.no_song, Toast.LENGTH_SHORT).show();
    }
}
