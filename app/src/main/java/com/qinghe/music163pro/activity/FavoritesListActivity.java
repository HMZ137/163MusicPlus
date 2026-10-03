package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.WatchConfirmDialog;
import com.qinghe.music163pro.util.MultiSelectController;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.manager.FavoritesManager;
import com.qinghe.music163pro.manager.PlaylistManager;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.LocalTracks;

import java.util.ArrayList;
import java.util.List;

/**
 * Favorites list activity - shows favorited songs and playlists.
 * Supports both local and cloud modes, with tabs for songs and playlists.
 * 本地收藏的歌曲列表支持长按进入多选批量取消收藏。
 */
public class FavoritesListActivity extends BaseWatchActivity {

    private static final int TAB_SONGS = 0;
    private static final int TAB_PLAYLISTS = 1;
    private static final int TAB_ALBUMS = 2;

    private final List<Song> favoritesList = new ArrayList<>();
    private final List<PlaylistInfo> playlistsList = new ArrayList<>();
    private final List<MusicApiHelper.AlbumBrief> albumsList = new ArrayList<>();
    private ArrayAdapter<Song> songAdapter;
    private ArrayAdapter<PlaylistInfo> playlistAdapter;
    private ArrayAdapter<MusicApiHelper.AlbumBrief> albumAdapter;
    private FavoritesManager favoritesManager;
    private PlaylistManager playlistManager;
    private MusicPlayerManager playerManager;
    private TextView tvEmpty;
    private ListView lvFavorites;
    private ListView lvFavPlaylists;
    private ListView lvFavAlbums;
    private TextView tabFavSongs;
    private TextView tabFavPlaylists;
    private TextView tabFavAlbums;
    private MultiSelectController multiSelect;

    /** 当前 Tab：0 单曲 / 1 歌单 / 2 专辑。 */
    private int tab = TAB_SONGS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favorites_list);
        // 标题栏要撑到浮动返回按钮的占位高度，否则按钮会压住列表第一行的封面
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(
                this, findViewById(R.id.tv_favorites_title));

        lvFavorites = findViewById(R.id.lv_favorites);
        lvFavPlaylists = findViewById(R.id.lv_fav_playlists);
        lvFavAlbums = findViewById(R.id.lv_fav_albums);
        tvEmpty = findViewById(R.id.tv_empty);
        tabFavSongs = findViewById(R.id.tab_fav_songs);
        tabFavPlaylists = findViewById(R.id.tab_fav_playlists);
        tabFavAlbums = findViewById(R.id.tab_fav_albums);

        favoritesManager = new FavoritesManager(this);
        playlistManager = new PlaylistManager();
        playerManager = MusicPlayerManager.getInstance();

        // Song adapter
        songAdapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, favoritesList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText(song.getDisplayName());
                    // 本地收藏带「本地」标签：它的 id 是路径派生的负数，
                    // 不属于网易云曲目体系（没有云端音质/评论/歌单可用）。
                    if (LocalTracks.isLocalId(song.getId())) {
                        tvName.setText(song.getDisplayName() + " [本地]");
                        tvArtist.setText(LocalTracks.safeName(song.getArtist()));
                    } else {
                        tvArtist.setText(song.getArtist());
                    }
                    if (ivCover != null) {
                        NetworkImageLoader.load(ivCover, song.getCoverUrl());
                    }
                }
                return view;
            }
        };
        lvFavorites.setAdapter(songAdapter);
        NetworkImageLoader.attachListViewOptimization(lvFavorites, songAdapter, position ->
                position >= 0 && position < favoritesList.size()
                        ? favoritesList.get(position).getCoverUrl() : null);

        // Playlist adapter
        playlistAdapter = new ArrayAdapter<PlaylistInfo>(this, R.layout.item_playlist, R.id.tv_playlist_name, playlistsList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                PlaylistInfo pl = getItem(position);
                if (pl != null) {
                    TextView tvName = view.findViewById(R.id.tv_playlist_name);
                    TextView tvInfo = view.findViewById(R.id.tv_playlist_info);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText(pl.getName());
                    String info = pl.getTrackCount() + "\u9996";
                    if (pl.getCreator() != null && !pl.getCreator().isEmpty()) {
                        info += " \u00b7 " + pl.getCreator();
                    }
                    tvInfo.setText(info);
                    if (ivCover != null) {
                        NetworkImageLoader.load(ivCover, pl.getCoverUrl());
                    }
                }
                return view;
            }
        };
        lvFavPlaylists.setAdapter(playlistAdapter);
        NetworkImageLoader.attachListViewOptimization(lvFavPlaylists, playlistAdapter, position ->
                position >= 0 && position < playlistsList.size()
                        ? playlistsList.get(position).getCoverUrl() : null);

        // Album adapter（收藏的专辑，复用歌单行布局：封面 + 专辑名 + "N首 · 歌手"）
        albumAdapter = new ArrayAdapter<MusicApiHelper.AlbumBrief>(this, R.layout.item_playlist,
                R.id.tv_playlist_name, albumsList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                MusicApiHelper.AlbumBrief album = getItem(position);
                if (album != null) {
                    TextView tvName = view.findViewById(R.id.tv_playlist_name);
                    TextView tvInfo = view.findViewById(R.id.tv_playlist_info);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText(album.name);
                    StringBuilder info = new StringBuilder();
                    if (album.size > 0) {
                        info.append(album.size).append("\u9996");
                    }
                    if (album.artist != null && !album.artist.isEmpty()) {
                        if (info.length() > 0) {
                            info.append(" \u00b7 ");
                        }
                        info.append(album.artist);
                    }
                    tvInfo.setText(info.toString());
                    if (ivCover != null) {
                        NetworkImageLoader.load(ivCover, album.picUrl);
                    }
                }
                return view;
            }
        };
        lvFavAlbums.setAdapter(albumAdapter);
        NetworkImageLoader.attachListViewOptimization(lvFavAlbums, albumAdapter, position ->
                position >= 0 && position < albumsList.size()
                        ? albumsList.get(position).picUrl : null);

        // Tab click handlers
        tabFavSongs.setOnClickListener(v -> switchToSongTab());
        tabFavPlaylists.setOnClickListener(v -> switchToPlaylistTab());
        tabFavAlbums.setOnClickListener(v -> switchToAlbumTab());

        lvFavorites.setOnItemClickListener((parent, view, position, id) -> {
            Song song = favoritesList.get(position);
            List<Song> playlist = new ArrayList<>(favoritesList);
            playerManager.setPlaylist(playlist, position);
            playerManager.playCurrent();
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        lvFavorites.setOnItemLongClickListener((parent, view, position, id) -> {
            Song song = favoritesList.get(position);
            showUnfavoriteDialog(song, position);
            return true;
        });

        lvFavPlaylists.setOnItemClickListener((parent, view, position, id) -> {
            PlaylistInfo pl = playlistsList.get(position);
            Intent intent = new Intent(this, PlaylistDetailActivity.class);
            intent.putExtra("playlist_id", pl.getId());
            intent.putExtra("playlist_name", pl.getName());
            intent.putExtra("track_count", pl.getTrackCount());
            intent.putExtra("creator", pl.getCreator());
            intent.putExtra("creator_user_id", pl.getUserId());
            intent.putExtra("is_liked_playlist", pl.isLikedPlaylist());
            startActivity(intent);
        });

        // 收藏的专辑：点进专辑详情（可在那里取消收藏）
        lvFavAlbums.setOnItemClickListener((parent, view, position, id) -> {
            MusicApiHelper.AlbumBrief album = albumsList.get(position);
            Intent intent = new Intent(this, AlbumDetailActivity.class);
            intent.putExtra("album_id", album.id);
            intent.putExtra("album_name", album.name);
            intent.putExtra("album_cover_url", album.picUrl);
            startActivity(intent);
        });

        // Long press to delete playlist from local (with confirmation)
        lvFavPlaylists.setOnItemLongClickListener((parent, view, position, id) -> {
            SharedPreferences settingsPrefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
            boolean isCloud = settingsPrefs.getBoolean("fav_mode_cloud", false);
            if (!isCloud) {
                PlaylistInfo pl = playlistsList.get(position);
                showConfirmDialog("确认删除", "确定删除歌单「" + pl.getName() + "」？", () -> {
                    playlistManager.removePlaylist(pl.getId());
                    playlistsList.remove(position);
                    playlistAdapter.notifyDataSetChanged();
                    updateEmptyState();
                    Toast.makeText(this, "\u5df2\u5220\u9664\u6b4c\u5355", Toast.LENGTH_SHORT).show();
                });
            }
            return true;
        });

        // 长按进入多选模式。云端收藏不支持批量本地删除，此时控制器会被禁用，
        // 长按仍然回落到上面的单条「取消收藏」逻辑。
        multiSelect = MultiSelectController.attach(this, lvFavorites,
                new MultiSelectController.Callback() {
                    @Override
                    public List<MultiSelectController.Action> getActions() {
                        return java.util.Collections.singletonList(
                                MultiSelectController.Action.delete(FavoritesListActivity.this));
                    }

                    @Override
                    public void onAction(MultiSelectController.Action action, List<Integer> positions) {
                        if (MultiSelectController.Action.ID_DELETE.equals(action.id)) {
                            confirmDeleteSelected(positions);
                        }
                    }
                });
        if (multiSelect != null) {
            addBackInterceptor(multiSelect::handleBackPressed);
        }

        loadData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 列表数据在 onResume 会重新加载，选中下标可能失效，先退出多选。
        if (multiSelect != null) {
            multiSelect.exitSelectionMode();
        }
        loadData();
    }

    /**
     * 删除选中的本地收藏歌曲。
     */
    private void confirmDeleteSelected(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < favoritesList.size()) {
                targets.add(favoritesList.get(position));
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        showConfirmDialog("取消收藏",
                "确定取消收藏选中的 " + targets.size() + " 首歌曲？", () -> {
                    for (Song song : targets) {
                        favoritesManager.removeFavorite(song);
                    }
                    if (multiSelect != null) {
                        multiSelect.exitSelectionMode();
                    }
                    loadData();
                    Toast.makeText(FavoritesListActivity.this, "已取消收藏", Toast.LENGTH_SHORT).show();
                });
    }

    private void switchToSongTab() {
        tab = TAB_SONGS;
        applyTabStyles();
        lvFavPlaylists.setVisibility(View.GONE);
        lvFavAlbums.setVisibility(View.GONE);
        loadData();
    }

    private void switchToPlaylistTab() {
        tab = TAB_PLAYLISTS;
        applyTabStyles();
        lvFavorites.setVisibility(View.GONE);
        lvFavAlbums.setVisibility(View.GONE);
        // 歌曲列表已隐藏，退出多选避免动作栏残留在歌单页上
        if (multiSelect != null) {
            multiSelect.setEnabled(false);
        }
        loadPlaylists();
    }

    private void switchToAlbumTab() {
        tab = TAB_ALBUMS;
        applyTabStyles();
        lvFavorites.setVisibility(View.GONE);
        lvFavPlaylists.setVisibility(View.GONE);
        if (multiSelect != null) {
            multiSelect.setEnabled(false);
        }
        loadAlbums();
    }

    /** 只改文字色：选中项主色、其余次要色（沿用原两个 Tab 的视觉）。 */
    private void applyTabStyles() {
        tabFavSongs.setTextColor(tab == TAB_SONGS ? 0xFFBB86FC : 0xB3FFFFFF);
        tabFavPlaylists.setTextColor(tab == TAB_PLAYLISTS ? 0xFFBB86FC : 0xB3FFFFFF);
        tabFavAlbums.setTextColor(tab == TAB_ALBUMS ? 0xFFBB86FC : 0xB3FFFFFF);
    }

    private void loadData() {
        if (tab == TAB_SONGS) {
            loadFavorites();
        } else if (tab == TAB_PLAYLISTS) {
            loadPlaylists();
        } else {
            loadAlbums();
        }
    }

    /** 收藏的专辑（weapi /api/album/sublist），仅云端——专辑收藏本身就是账号数据。 */
    private void loadAlbums() {
        albumsList.clear();
        albumAdapter.notifyDataSetChanged();
        tvEmpty.setText("正在加载收藏的专辑...");
        tvEmpty.setVisibility(View.VISIBLE);
        lvFavAlbums.setVisibility(View.GONE);

        String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            tvEmpty.setText("请先登录以查看收藏的专辑");
            return;
        }

        MusicApiHelper.getSubscribedAlbums(100, 0, cookie,
                new MusicApiHelper.SubscribedAlbumsCallback() {
                    @Override
                    public void onResult(int total, boolean hasMore, List<MusicApiHelper.AlbumBrief> albums) {
                        albumsList.clear();
                        if (albums != null) {
                            albumsList.addAll(albums);
                        }
                        albumAdapter.notifyDataSetChanged();
                        tvEmpty.setText("暂无收藏的专辑");
                        updateEmptyState();
                    }

                    @Override
                    public void onError(String message) {
                        tvEmpty.setText("加载失败: " + message);
                        tvEmpty.setVisibility(View.VISIBLE);
                        lvFavAlbums.setVisibility(View.GONE);
                    }
                });
    }

    private void loadFavorites() {
        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        boolean isCloud = prefs.getBoolean("fav_mode_cloud", false);

        // 多选批量删除只针对本地收藏；云端收藏由 API 管理，保持原有的单条「取消收藏」交互。
        if (multiSelect != null) {
            multiSelect.setEnabled(!isCloud);
        }

        if (isCloud) {
            loadCloudFavorites();
        } else {
            loadLocalFavorites();
        }
    }

    private void loadLocalFavorites() {
        // First, load from local storage immediately for fast display
        favoritesList.clear();
        favoritesList.addAll(favoritesManager.getFavorites());
        songAdapter.notifyDataSetChanged();
        updateEmptyState();

        // Then, fetch fresh details (including coverUrls) from API in background
        List<Long> ids = favoritesManager.getFavoriteIds();
        if (ids.isEmpty()) return;
        String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) return;

        MusicApiHelper.fetchSongsDetails(ids, cookie, new MusicApiHelper.BatchSongDetailsCallback() {
            @Override
            public void onResult(java.util.Map<Long, Song> songMap) {
                if (songMap.isEmpty()) return;
                // Build local lookup map for O(1) fallback access
                java.util.Map<Long, Song> localMap = new java.util.HashMap<>();
                for (Song ls : favoritesList) localMap.put(ls.getId(), ls);
                // Rebuild the list preserving original order
                List<Song> updated = new ArrayList<>();
                for (Long id : ids) {
                    // 本地收藏优先：先用本地那条，只有本地没有时才用 API 返回的曲目。
                    // 反过来（先放 API 结果）会把本地收藏顶成"在线版本"，
                    // 表现就是本地歌一闪而过、最后只剩在线收藏。
                    Song local = localMap.get(id);
                    if (local != null) {
                        updated.add(local);
                        continue;
                    }
                    Song s = songMap.get(id);
                    if (s != null) {
                        updated.add(s);
                    }
                }
                favoritesList.clear();
                favoritesList.addAll(updated);
                songAdapter.notifyDataSetChanged();
                updateEmptyState();
            }

            @Override
            public void onError(String message) {
                // Keep local data displayed, silently ignore error
            }
        });
    }

    private void loadCloudFavorites() {
        favoritesList.clear();
        songAdapter.notifyDataSetChanged();
        tvEmpty.setText("\u6b63\u5728\u52a0\u8f7d\u4e91\u7aef\u6536\u85cf...");
        tvEmpty.setVisibility(View.VISIBLE);
        lvFavorites.setVisibility(View.GONE);

        String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            tvEmpty.setText("\u8bf7\u5148\u767b\u5f55\u4ee5\u4f7f\u7528\u4e91\u7aef\u6536\u85cf");
            return;
        }

        MusicApiHelper.getCloudFavorites(cookie, new MusicApiHelper.CloudFavoritesCallback() {
            @Override
            public void onResult(List<Song> songs) {
                favoritesList.clear();
                // 云端收藏 + 本地收藏 合并显示：本地歌曲不该受"收藏模式"影响，联网也要能看到
                java.util.Set<Long> cloudIds = new java.util.HashSet<>();
                for (Song cs : songs) {
                    cloudIds.add(cs.getId());
                }
                List<Song> merged = new ArrayList<>(songs);
                for (Song local : favoritesManager.getFavorites()) {
                    if (local != null && !cloudIds.contains(local.getId())) {
                        merged.add(local);
                    }
                }
                favoritesList.addAll(merged);
                songAdapter.notifyDataSetChanged();
                if (merged.isEmpty()) {
                    tvEmpty.setText("暂无收藏");
                }
                updateEmptyState();
            }

            @Override
            public void onError(String message) {
                tvEmpty.setText("\u52a0\u8f7d\u5931\u8d25: " + message);
                tvEmpty.setVisibility(View.VISIBLE);
                lvFavorites.setVisibility(View.GONE);
            }
        });
    }

    private void loadPlaylists() {
        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        boolean isCloud = prefs.getBoolean("fav_mode_cloud", false);

        if (isCloud) {
            loadCloudPlaylists();
        } else {
            loadLocalPlaylists();
        }
    }

    private void loadLocalPlaylists() {
        playlistsList.clear();
        playlistsList.addAll(playlistManager.getPlaylists());
        playlistAdapter.notifyDataSetChanged();
        updateEmptyState();

        // Refresh track counts, creator info, and coverUrl from API for each locally saved playlist
        String cookie = playerManager.getCookie();
        if (cookie != null && !cookie.isEmpty()) {
            for (int i = 0; i < playlistsList.size(); i++) {
                final long plId = playlistsList.get(i).getId();
                MusicApiHelper.getPlaylistMetaWithCover(plId, cookie, new MusicApiHelper.PlaylistMetaWithCoverCallback() {
                    @Override
                    public void onResult(int trackCount, String creator, long creatorUserId,
                                         int specialType, boolean subscribed, String coverUrl) {
                        for (int j = 0; j < playlistsList.size(); j++) {
                            if (playlistsList.get(j).getId() == plId) {
                                PlaylistInfo updated = playlistsList.get(j);
                                updated.setTrackCount(trackCount);
                                if (creator != null && !creator.isEmpty()) {
                                    updated.setCreator(creator);
                                }
                                if (coverUrl != null && !coverUrl.isEmpty()) {
                                    updated.setCoverUrl(coverUrl);
                                }
                                playlistAdapter.notifyDataSetChanged();
                                playlistManager.updatePlaylistMeta(plId, trackCount, creator);
                                break;
                            }
                        }
                    }

                    @Override
                    public void onError(String message) {
                        // Silently ignore - display local data as fallback
                    }
                });
            }
        }
    }

    private void loadCloudPlaylists() {
        playlistsList.clear();
        playlistAdapter.notifyDataSetChanged();
        tvEmpty.setText("\u6b63\u5728\u52a0\u8f7d\u4e91\u7aef\u6b4c\u5355...");
        tvEmpty.setVisibility(View.VISIBLE);
        lvFavPlaylists.setVisibility(View.GONE);

        String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            tvEmpty.setText("\u8bf7\u5148\u767b\u5f55\u4ee5\u4f7f\u7528\u4e91\u7aef\u6b4c\u5355");
            return;
        }

        MusicApiHelper.getUserPlaylists(cookie, new MusicApiHelper.UserPlaylistsCallback() {
            @Override
            public void onResult(List<PlaylistInfo> playlists) {
                playlistsList.clear();
                playlistsList.addAll(playlists);
                playlistAdapter.notifyDataSetChanged();
                tvEmpty.setText("\u6682\u65e0\u4e91\u7aef\u6b4c\u5355");
                updateEmptyState();
            }

            @Override
            public void onError(String message) {
                tvEmpty.setText("\u52a0\u8f7d\u5931\u8d25: " + message);
                tvEmpty.setVisibility(View.VISIBLE);
                lvFavPlaylists.setVisibility(View.GONE);
            }
        });
    }

    private void updateEmptyState() {
        if (tab == TAB_SONGS) {
            lvFavPlaylists.setVisibility(View.GONE);
            lvFavAlbums.setVisibility(View.GONE);
            if (favoritesList.isEmpty()) {
                tvEmpty.setVisibility(View.VISIBLE);
                lvFavorites.setVisibility(View.GONE);
            } else {
                tvEmpty.setVisibility(View.GONE);
                lvFavorites.setVisibility(View.VISIBLE);
            }
        } else if (tab == TAB_PLAYLISTS) {
            lvFavorites.setVisibility(View.GONE);
            lvFavAlbums.setVisibility(View.GONE);
            if (playlistsList.isEmpty()) {
                tvEmpty.setVisibility(View.VISIBLE);
                lvFavPlaylists.setVisibility(View.GONE);
            } else {
                tvEmpty.setVisibility(View.GONE);
                lvFavPlaylists.setVisibility(View.VISIBLE);
            }
        } else {
            lvFavorites.setVisibility(View.GONE);
            lvFavPlaylists.setVisibility(View.GONE);
            if (albumsList.isEmpty()) {
                tvEmpty.setVisibility(View.VISIBLE);
                lvFavAlbums.setVisibility(View.GONE);
            } else {
                tvEmpty.setVisibility(View.GONE);
                lvFavAlbums.setVisibility(View.VISIBLE);
            }
        }
    }
    private void showUnfavoriteDialog(Song song, int position) {
        showConfirmDialog("取消收藏", "确定取消收藏「" + song.getName() + "」？", () -> {
            SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
            boolean isCloud = prefs.getBoolean("fav_mode_cloud", false);
            if (isCloud) {
                String cookie = playerManager.getCookie();
                if (cookie == null || cookie.isEmpty()) {
                    Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
                    return;
                }
                MusicApiHelper.likeTrack(song.getId(), false, cookie,
                        new MusicApiHelper.LikeCallback() {
                    @Override
                    public void onResult(boolean success) {
                        if (success) {
                            favoritesList.remove(position);
                            songAdapter.notifyDataSetChanged();
                            updateEmptyState();
                            Toast.makeText(FavoritesListActivity.this, "已取消云端收藏", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(FavoritesListActivity.this, "操作失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                    @Override
                    public void onError(String message) {
                        Toast.makeText(FavoritesListActivity.this, "操作失败: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                favoritesManager.removeFavorite(song);
                favoritesList.remove(position);
                songAdapter.notifyDataSetChanged();
                updateEmptyState();
                Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Show a confirmation dialog adapted for watch (360x320 px screen).
     */
    private void showConfirmDialog(String title, String message, Runnable onConfirm) {
        WatchConfirmDialog.show(this, title, message, onConfirm,
                WatchConfirmDialog.Options.defaultOptions());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }
}
