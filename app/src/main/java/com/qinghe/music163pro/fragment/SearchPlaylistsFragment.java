package com.qinghe.music163pro.fragment;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.PlaylistDetailActivity;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.util.NetworkImageLoader;

import java.util.List;

/** S1：搜索结果的「歌单」标签页。 */
public class SearchPlaylistsFragment extends SearchListFragment<PlaylistInfo> {

    @Override
    protected int itemLayoutRes() {
        return R.layout.item_playlist;
    }

    @Override
    protected int itemTextRes() {
        return R.id.tv_playlist_name;
    }

    @Override
    protected void bindRow(View row, PlaylistInfo playlistInfo) {
        TextView tvName = row.findViewById(R.id.tv_playlist_name);
        TextView tvInfo = row.findViewById(R.id.tv_playlist_info);
        ImageView ivCover = row.findViewById(R.id.iv_cover);
        tvName.setText(playlistInfo.getName());
        String info = playlistInfo.getTrackCount() + "首";
        if (playlistInfo.getCreator() != null && !playlistInfo.getCreator().isEmpty()) {
            info += " · " + playlistInfo.getCreator();
        }
        tvInfo.setText(info);
        if (ivCover != null) {
            NetworkImageLoader.load(ivCover, playlistInfo.getCoverUrl());
        }
    }

    @Override
    protected String coverUrl(PlaylistInfo playlistInfo) {
        return playlistInfo.getCoverUrl();
    }

    @Override
    protected void fetchPage(int offset, PageCallback<PlaylistInfo> callback) {
        MusicApiHelper.searchPlaylists(keyword(), offset, cookie(),
                new MusicApiHelper.SearchPlaylistCallback() {
                    @Override
                    public void onResult(List<PlaylistInfo> playlists) {
                        callback.onResult(playlists);
                    }

                    @Override
                    public void onError(String message) {
                        callback.onError(message);
                    }
                });
    }

    @Override
    protected void onItemClicked(int position, PlaylistInfo playlistInfo) {
        Intent intent = new Intent(requireContext(), PlaylistDetailActivity.class);
        intent.putExtra("playlist_id", playlistInfo.getId());
        intent.putExtra("playlist_name", playlistInfo.getName());
        intent.putExtra("track_count", playlistInfo.getTrackCount());
        intent.putExtra("creator", playlistInfo.getCreator());
        intent.putExtra("creator_user_id", playlistInfo.getUserId());
        intent.putExtra("is_liked_playlist", playlistInfo.isLikedPlaylist());
        startActivity(intent);
    }

    @Override
    protected void onEmptyFirstPage() {
        Toast.makeText(requireContext(), "暂无歌单", Toast.LENGTH_SHORT).show();
    }
}
