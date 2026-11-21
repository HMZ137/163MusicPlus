package com.qinghe.music163pro.fragment;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.AlbumDetailActivity;
import com.qinghe.music163pro.activity.PlaylistDetailActivity;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.manager.PlaylistManager;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.util.NetworkImageLoader;

import java.util.List;

/**
 * 4-14：收藏的「歌单」页（原 {@code FavoritesListActivity} 的第二个标签）。
 *
 * <p>与改造前一致：本地模式显示本机保存的歌单并逐个异步补「曲目数 / 创建者 / 封面」；
 * 云端模式显示账号收藏的歌单；点击进 {@code PlaylistDetailActivity}；
 * 本地模式下长按可确认删除歌单（云端模式长按不提供删除）。</p>
 */
public class FavoritesPlaylistsFragment extends FavoritesPageFragment<PlaylistInfo> {

    private PlaylistManager playlistManager;

    @Override
    public int pageIndex() {
        return PAGE_PLAYLISTS;
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
    protected String coverUrl(PlaylistInfo item) {
        return item.getCoverUrl();
    }

    @Override
    protected CharSequence emptyText() {
        return isCloudMode() ? "暂无云端歌单" : "暂无收藏的歌单";
    }

    @Override
    protected CharSequence pageName() {
        return "歌单";
    }

    @Override
    protected CharSequence countUnit() {
        return "个";
    }

    @Override
    protected void onListReady() {
        playlistManager = new PlaylistManager();
    }

    @Override
    protected void bindRow(View row, PlaylistInfo pl) {
        TextView tvName = row.findViewById(R.id.tv_playlist_name);
        TextView tvInfo = row.findViewById(R.id.tv_playlist_info);
        ImageView ivCover = row.findViewById(R.id.iv_cover);
        tvName.setText(pl.getName());
        // P0-8：专辑与歌单混排，靠「专辑」前缀区分
        boolean isAlbum = com.qinghe.music163pro.util.LocalAlbumFavorites.isAlbumEntry(pl);
        StringBuilder info = new StringBuilder(isAlbum ? "专辑" : "歌单");
        if (pl.getTrackCount() > 0) {
            info.append(" \u00b7 ").append(pl.getTrackCount()).append("\u9996");
        }
        if (pl.getCreator() != null && !pl.getCreator().isEmpty()) {
            info.append(" \u00b7 ").append(pl.getCreator());
        }
        tvInfo.setText(info.toString());
        if (ivCover != null) {
            NetworkImageLoader.load(ivCover, pl.getCoverUrl());
        }
    }

    @Override
    protected void onItemClicked(int position, PlaylistInfo pl) {
        // P0-8：专辑条目 → 专辑详情；歌单条目 → 歌单详情
        if (com.qinghe.music163pro.util.LocalAlbumFavorites.isAlbumEntry(pl)) {
            Intent intent = new Intent(requireContext(), AlbumDetailActivity.class);
            intent.putExtra("album_id", com.qinghe.music163pro.util.LocalAlbumFavorites.albumIdOf(pl));
            intent.putExtra("album_name", pl.getName());
            intent.putExtra("album_cover_url", pl.getCoverUrl());
            startActivity(intent);
            return;
        }
        Intent intent = new Intent(requireContext(), PlaylistDetailActivity.class);
        intent.putExtra("playlist_id", pl.getId());
        intent.putExtra("playlist_name", pl.getName());
        intent.putExtra("track_count", pl.getTrackCount());
        intent.putExtra("creator", pl.getCreator());
        intent.putExtra("creator_user_id", pl.getUserId());
        intent.putExtra("is_liked_playlist", pl.isLikedPlaylist());
        startActivity(intent);
    }

    @Override
    protected boolean onItemLongClicked(int position, PlaylistInfo pl) {
        // P0-8：专辑条目长按 = 取消收藏（只动本地记录）
        if (com.qinghe.music163pro.util.LocalAlbumFavorites.isAlbumEntry(pl)) {
            showConfirmDialog("取消收藏", "把「" + pl.getName() + "」从收藏专辑移除？", () -> {
                com.qinghe.music163pro.util.LocalAlbumFavorites.remove(requireContext(),
                        com.qinghe.music163pro.util.LocalAlbumFavorites.albumIdOf(pl));
                removeItemAt(position);
                toast("已取消收藏");
            });
            return true;
        }
        // 只有本地模式（自己保存的歌单）才能删除；云端收藏的歌单由 API 管理，长按不提供删除。
        if (!isCloudMode()) {
            showConfirmDialog("确认删除", "确定删除歌单「" + pl.getName() + "」？", () -> {
                playlistManager.removePlaylist(pl.getId());
                removeItemAt(position);
                toast("已删除歌单");
            });
        }
        return true;
    }

    @Override
    protected void loadData() {
        if (playlistManager == null) {
            playlistManager = new PlaylistManager();
        }
        if (isCloudMode()) {
            loadCloudPlaylists();
        } else {
            loadLocalPlaylists();
        }
    }

    private void loadLocalPlaylists() {
        final List<PlaylistInfo> local = new java.util.ArrayList<>(playlistManager.getPlaylists());
        // P0-8：本地收藏专辑与歌单混排（专辑用负数 id 标记）
        local.addAll(com.qinghe.music163pro.util.LocalAlbumFavorites.list(requireContext()));
        applyItems(local);

        // 逐个补「曲目数 / 创建者 / 封面」（与原实现一致：拿到就刷新该行并回写本地元信息）。
        final String cookie = cookie();
        if (cookie == null || cookie.isEmpty()) {
            return;
        }
        for (int i = 0; i < local.size(); i++) {
            final long plId = local.get(i).getId();
            MusicApiHelper.getPlaylistMetaWithCover(plId, cookie,
                    new MusicApiHelper.PlaylistMetaWithCoverCallback() {
                        @Override
                        public void onResult(int trackCount, String creator, long creatorUserId,
                                             int specialType, boolean subscribed, String coverUrl) {
                            if (!isViewReady()) {
                                return;
                            }
                            for (int j = 0; j < items.size(); j++) {
                                PlaylistInfo updated = items.get(j);
                                if (updated.getId() == plId) {
                                    updated.setTrackCount(trackCount);
                                    if (creator != null && !creator.isEmpty()) {
                                        updated.setCreator(creator);
                                    }
                                    if (coverUrl != null && !coverUrl.isEmpty()) {
                                        updated.setCoverUrl(coverUrl);
                                    }
                                    notifyItemsChanged();
                                    playlistManager.updatePlaylistMeta(plId, trackCount, creator);
                                    break;
                                }
                            }
                        }

                        @Override
                        public void onError(String message) {
                            // 静默忽略：本地数据继续显示（与改造前一致）。
                        }
                    });
        }
    }

    private void loadCloudPlaylists() {
        showStatus("正在加载云端歌单...");
        String cookie = cookie();
        if (cookie == null || cookie.isEmpty()) {
            showStatus("请先登录以使用云端歌单");
            return;
        }
        MusicApiHelper.getUserPlaylists(cookie, new MusicApiHelper.UserPlaylistsCallback() {
            @Override
            public void onResult(List<PlaylistInfo> playlists) {
                if (isViewReady()) {
                    // P0-8：云端歌单 + 本地收藏专辑先混排，再异步补「云端收藏专辑」
                    List<PlaylistInfo> merged = new java.util.ArrayList<>(playlists);
                    merged.addAll(com.qinghe.music163pro.util.LocalAlbumFavorites.list(requireContext()));
                    applyItems(merged);
                    loadCloudAlbums();
                }
            }

            @Override
            public void onError(String message) {
                if (isViewReady()) {
                    // 歌单拉失败也要把本地收藏专辑显示出来
                    applyItems(com.qinghe.music163pro.util.LocalAlbumFavorites.list(requireContext()));
                }
            }
        });
    }

    /** P0-8：云端收藏的专辑，追加到列表尾部，与本地收藏专辑、歌单混排。 */
    private void loadCloudAlbums() {
        final String cookie = cookie();
        if (cookie == null || cookie.isEmpty()) return;
        MusicApiHelper.getSubscribedAlbums(30, 0, cookie,
                new MusicApiHelper.SubscribedAlbumsCallback() {
                    @Override
                    public void onResult(int total, boolean hasMore,
                                         List<MusicApiHelper.AlbumBrief> albums) {
                        if (!isViewReady() || albums == null || albums.isEmpty()) return;
                        for (MusicApiHelper.AlbumBrief a : albums) {
                            if (a == null || a.id <= 0) continue;
                            PlaylistInfo info = new PlaylistInfo(-a.id, a.name, a.size, a.artist);
                            info.setCoverUrl(a.picUrl);
                            items.add(info);
                        }
                        notifyItemsChanged();
                    }

                    @Override
                    public void onError(String message) {
                        // 静默：本地与歌单部分已经显示
                    }
                });
    }
}
