package com.qinghe.music163pro.fragment;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.MainActivity;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.manager.FavoritesManager;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.LocalTracks;
import com.qinghe.music163pro.util.MultiSelectController;
import com.qinghe.music163pro.util.NetworkImageLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * 4-13：收藏的「单曲」页（原 {@code FavoritesListActivity} 的第一个标签）。
 *
 * <p>行为与改造前逐条对齐：本地收藏优先显示（立即可见），再异步补云端封面/音质等详情；
 * 收藏模式为云端时显示云端收藏并合并本地收藏；点歌把当前列表设为播放队列并回主界面；
 * 长按单条取消收藏（本地直接删、云端走 {@code likeTrack} 接口）；
 * <b>本地收藏</b>额外支持长按进入多选、批量取消收藏（云端收藏不支持批量，控制器会被禁用，
 * 长按回落到单条取消收藏）。</p>
 */
public class FavoritesSongsFragment extends FavoritesPageFragment<Song> {

    private FavoritesManager favoritesManager;
    private final MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
    private MultiSelectController multiSelect;

    @Override
    public int pageIndex() {
        return PAGE_SONGS;
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
    protected String coverUrl(Song item) {
        return item.getCoverUrl();
    }

    @Override
    protected CharSequence emptyText() {
        return "暂无收藏";
    }

    @Override
    protected CharSequence pageName() {
        return "单曲";
    }

    @Override
    protected CharSequence countUnit() {
        return "首";
    }

    @Override
    protected void onListReady() {
        favoritesManager = new FavoritesManager(requireContext());
        // 长按进入多选模式。云端收藏不支持批量本地删除，此时控制器会被禁用，
        // 长按仍然回落到单条「取消收藏」逻辑（见 onItemLongClicked）。
        multiSelect = MultiSelectController.attach(requireActivity(), listView(),
                new MultiSelectController.Callback() {
                    @Override
                    public List<MultiSelectController.Action> getActions() {
                        // 需求 2026-11-21 任务 6：加入「下一首播放」（可叠加）
                        List<MultiSelectController.Action> acts = new java.util.ArrayList<>();
                        acts.add(MultiSelectController.Action.playNext(requireActivity()));
                        // 需求 2026-11-27：多选加「下载」（在线歌曲批量入库到下载管理）
                        acts.add(new MultiSelectController.Action("download", "下载",
                                com.qinghe.music163pro.util.ThemeManager.accentColor(requireContext())));
                        // 任务 3（2026-11-21）：收藏的在线歌曲也能加入本地歌单
                        acts.add(new MultiSelectController.Action("add_to_local_playlist", "加入本地歌单",
                                com.qinghe.music163pro.util.ThemeManager.accentColor(requireContext())));
                        // 需求 2026-11-22：与「加入本地歌单」并列的云端版 —— 收藏到网易云歌单
                        acts.add(new MultiSelectController.Action("add_to_cloud_playlist", "收藏到歌单",
                                com.qinghe.music163pro.util.ThemeManager.accentColor(requireContext())));
                        acts.add(MultiSelectController.Action.delete(requireActivity()));
                        return acts;
                    }

                    @Override
                    public void onAction(MultiSelectController.Action action, List<Integer> positions) {
                        if (MultiSelectController.Action.ID_DELETE.equals(action.id)) {
                            confirmDeleteSelected(positions);
                        } else if (MultiSelectController.Action.ID_PLAY_NEXT.equals(action.id)) {
                            playNextSelected(positions);
                        } else if ("add_to_cloud_playlist".equals(action.id)) {
                            // 需求 2026-11-22：加入网易云歌单（选在线曲目，本地文件跳过）
                            java.util.List<Long> ids = new java.util.ArrayList<>();
                            for (int p : positions) {
                                if (p >= 0 && p < items.size() && items.get(p).getId() > 0) {
                                    ids.add(items.get(p).getId());
                                }
                            }
                            addToCloudPlaylist(ids);
                        } else if ("add_to_local_playlist".equals(action.id)) {
                            java.util.List<Song> targets = new java.util.ArrayList<>();
                            for (int p : positions) {
                                if (p >= 0 && p < items.size()) {
                                    targets.add(items.get(p));
                                }
                            }
                            com.qinghe.music163pro.util.LocalPlaylistPicker.show(
                                    requireActivity(), targets, null);
                        } else if ("download".equals(action.id)) {
                            downloadSelected(positions);
                        }
                    }
                });
    }

    @Override
    protected void onBeforeReload() {
        // 列表数据会重载，选中下标可能失效，先退出多选。
        if (multiSelect != null) {
            multiSelect.exitSelectionMode();
        }
    }

    @Override
    public boolean handleBackPressed() {
        return multiSelect != null && multiSelect.handleBackPressed();
    }

    // ── 行渲染 ──────────────────────────────────────────────────────────────

    @Override
    protected void bindRow(View row, Song song) {
        // 任务 5（2026-11-21）：ViewHolder 化，避免每次绑定都 findViewById
        Object tag = row.getTag();
        TextView tvName;
        TextView tvArtist;
        ImageView ivCover;
        if (tag instanceof View[]) {
            View[] h = (View[]) tag;
            tvName = (TextView) h[0];
            tvArtist = (TextView) h[1];
            ivCover = (ImageView) h[2];
        } else {
            tvName = row.findViewById(R.id.tv_item_name);
            tvArtist = row.findViewById(R.id.tv_item_artist);
            ivCover = row.findViewById(R.id.iv_cover);
            row.setTag(new View[]{tvName, tvArtist, ivCover});
        }
        if (tvName != null) {
            String name = song.getDisplayName();
            if (!name.contentEquals(tvName.getText())) {
                tvName.setText(name);
            }
        }
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

    @Override
    protected void onItemClicked(int position, Song song) {
        // 播放队列 = 当前收藏列表（与改造前一致），然后回主界面（播放器页）。
        List<Song> playlist = new ArrayList<>(items);
        playerManager.setPlaylist(playlist, position);
        playerManager.playCurrent();
        Intent intent = new Intent(requireContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        requireActivity().finish();
    }

    @Override
    protected boolean onItemLongClicked(int position, Song song) {
        // 多选可用时控制器会先于这里吃掉长按（进入多选）；能走到这里说明是云端收藏模式。
        showUnfavoriteDialog(song, position);
        return true;
    }

    // ── 加载 ────────────────────────────────────────────────────────────────

    @Override
    protected void loadData() {
        if (favoritesManager == null) {
            favoritesManager = new FavoritesManager(requireContext());
        }
        boolean isCloud = isCloudMode();
        // 多选批量删除只针对本地收藏；云端收藏由 API 管理，保持原有的单条「取消收藏」交互。
        if (multiSelect != null) {
            multiSelect.setEnabled(!isCloud);
        }
        if (isCloud) {
            loadCloudFavorites();
        } else {
            // 2026-11-21 任务 2：恢复本地收藏列表（内部已过滤掉本地文件歌曲，保留在线歌曲的收藏）
            loadLocalFavorites();
        }
    }

    private void loadLocalFavorites() {
        // 先本地立即可见（离线/未登录也能看），再异步补详情。
        // 2026-11-21 任务 2：本地歌曲收藏不再展示。
        // 注意「本地收藏」里同时存着**在线歌曲**的收藏记录，这些必须保留 ——
        // 只过滤掉本地文件歌曲（id 由路径派生，LocalTracks.isLocalId 可判定）。
        List<Song> all = favoritesManager.getFavorites();
        List<Song> visible = new ArrayList<>();
        for (Song s : all) {
            if (s == null) {
                continue;
            }
            if (com.qinghe.music163pro.util.LocalTracks.isLocalId(s.getId())) {
                continue;
            }
            visible.add(s);
        }
        applyItems(visible);
        // 快照一份 favorites.json 的内容当兜底：详情回来时用它补「不在 id 索引里」的条目（见下）。
        final List<Song> localSnapshot = new ArrayList<>(items);

        final List<Long> ids = favoritesManager.getFavoriteIds();
        if (ids.isEmpty()) {
            return;
        }
        final String cookie = cookie();
        if (cookie == null || cookie.isEmpty()) {
            return;
        }
        // 任务 5（2026-11-21）：5 分钟内不重复补详情，避免每次进页都打网络。
        // 列表本身已经用本地快照立即渲染，这里只影响"补音质/评论数"这类详情。
        final android.content.SharedPreferences uiPrefs = requireContext()
                .getSharedPreferences("favorites_ui_prefs", android.content.Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - uiPrefs.getLong("detail_fetched_at", 0)
                < 5 * 60 * 1000L) {
            return;
        }
        MusicApiHelper.fetchSongsDetails(ids, cookie, new MusicApiHelper.BatchSongDetailsCallback() {
            @Override
            public void onResult(java.util.Map<Long, Song> songMap) {
                if (songMap.isEmpty() || !isViewReady()) {
                    return;
                }
                uiPrefs.edit().putLong("detail_fetched_at", System.currentTimeMillis()).apply();
                // 本地收藏优先：先用本地那条，只有本地没有时才用 API 返回的曲目。
                // 反过来（先放 API 结果）会把本地收藏顶成"在线版本"，
                // 表现就是本地歌一闪而过、最后只剩在线收藏。
                java.util.Map<Long, Song> localMap = new java.util.HashMap<>();
                java.util.Set<Long> idSet = new java.util.HashSet<>(ids);
                for (Song ls : localSnapshot) {
                    localMap.put(ls.getId(), ls);
                }
                List<Song> updated = new ArrayList<>();
                for (Long id : ids) {
                    Song local = localMap.get(id);
                    Song remote = songMap.get(id);
                    if (local != null) {
                        // 2026-11-22：用远端详情补本地记录的缺失字段（尤其是封面）。
                        // 以前这里直接用本地那条，远端带回 cover 的数据被整条丢弃，
                        // 旧收藏记录（持久化里根本没有 cover 字段）就永远显示默认占位图。
                        if (remote != null) {
                            if (local.getCoverUrl() == null || local.getCoverUrl().isEmpty()) {
                                local.setCoverUrl(remote.getCoverUrl());
                            }
                            if (local.getUrl() == null || local.getUrl().isEmpty()) {
                                local.setUrl(remote.getUrl());
                            }
                            if (local.getAlbum() == null || local.getAlbum().isEmpty()) {
                                local.setAlbum(remote.getAlbum());
                            }
                        }
                        updated.add(local);
                        continue;
                    }
                    Song s = songMap.get(id);
                    if (s != null) {
                        updated.add(s);
                    }
                }
                // ❗顺带修掉的旧缺陷：本地收藏里的**本地曲目**用的是路径派生的负数 id，
                // FavoritesManager 不会把它们写进 favorite-ids.json（那是给云端同步/查重用的），
                // 而上面这段「按 ids 重建」只遍历 id 索引 ⇒ 这些条目会被整批丢掉。
                // 改造前的表现就是「本地收藏的本地歌只在首屏闪一下，随后消失」。
                // 这里按 favorites.json 的原顺序把它们补到列表末尾，顺序与改造前首屏一致。
                for (Song local : localSnapshot) {
                    if (!idSet.contains(local.getId())) {
                        updated.add(local);
                    }
                }
                applyItems(updated);
            }

            @Override
            public void onError(String message) {
                // 保留本地数据展示，静默忽略（与改造前一致）。
            }
        });
    }

    private void loadCloudFavorites() {
        showStatus("正在加载云端收藏...");
        String cookie = cookie();
        if (cookie == null || cookie.isEmpty()) {
            showStatus("请先登录以使用云端收藏");
            return;
        }
        MusicApiHelper.getCloudFavorites(cookie, new MusicApiHelper.CloudFavoritesCallback() {
            @Override
            public void onResult(List<Song> songs) {
                if (!isViewReady()) {
                    return;
                }
                // 2026-11-21 任务 2：不再合并本地收藏（本地歌曲收藏不展示），只显示云端收藏。
                applyItems(new ArrayList<>(songs));
            }

            @Override
            public void onError(String message) {
                if (isViewReady()) {
                    showStatus("加载失败: " + message);
                }
            }
        });
    }

    // ── 取消收藏 ────────────────────────────────────────────────────────────

    /** 删除选中的本地收藏歌曲（长按多选 → 底部动作栏「删除」）。 */
    /**
     * 任务 6（2026-11-21）：把选中的收藏歌曲排到「下一首播放」，可叠加、同一首不重复。
     */
    /** 需求 2026-11-22：把选中的在线歌曲加入网易云歌单（弹歌单选择菜单）。 */
    private void addToCloudPlaylist(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(requireContext(),
                    "选中的歌曲没有可收藏的在线曲目");
            return;
        }
        String cookie = cookie();
        if (cookie == null || cookie.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(requireContext(), "请先登录后再收藏到歌单");
            return;
        }
        com.qinghe.music163pro.util.UiKit.toast(requireContext(), "正在获取歌单列表...");
        MusicApiHelper.getUserPlaylists(cookie, new MusicApiHelper.UserPlaylistsCallback() {
            @Override
            public void onResult(java.util.List<PlaylistInfo> playlists) {
                if (!isViewReady() || playlists == null || playlists.isEmpty()) {
                    com.qinghe.music163pro.util.UiKit.toast(requireContext(), "没有可用歌单");
                    return;
                }
                java.util.List<String> names = new java.util.ArrayList<>();
                for (PlaylistInfo pl : playlists) {
                    names.add(pl.getName());
                }
                com.qinghe.music163pro.util.WatchMenuDialog.show(requireActivity(), "收藏到歌单",
                        names.toArray(new String[0]), which -> {
                            if (which < 0 || which >= playlists.size()) {
                                return;
                            }
                            long pid = playlists.get(which).getId();
                            long[] trackIds = new long[ids.size()];
                            for (int i = 0; i < ids.size(); i++) {
                                trackIds[i] = ids.get(i);
                            }
                            MusicApiHelper.playlistTracks("add", pid, trackIds, cookie,
                                    new MusicApiHelper.PlaylistActionCallback() {
                                        @Override
                                        public void onResult(boolean success) {
                                            com.qinghe.music163pro.util.UiKit.toast(requireContext(),
                                                    success ? "已收藏到歌单" : "收藏失败");
                                        }

                                        @Override
                                        public void onError(String message) {
                                            com.qinghe.music163pro.util.UiKit.toast(requireContext(),
                                                    "收藏失败: " + message);
                                        }
                                    });
                        });
            }

            @Override
            public void onError(String message) {
                com.qinghe.music163pro.util.UiKit.toast(requireContext(), "获取歌单失败: " + message);
            }
        });
    }

    private void playNextSelected(List<Integer> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        com.qinghe.music163pro.player.MusicPlayerManager pm =
                com.qinghe.music163pro.player.MusicPlayerManager.getInstance();
        int added = 0;
        for (int position : positions) {
            if (position < 0 || position >= items.size()) {
                continue;
            }
            if (pm.queueNextSong(items.get(position))) {
                added++;
            }
        }
        android.widget.Toast.makeText(requireContext(),
                added > 0 ? "已加入下一首播放：" + added + " 首" : "所选歌曲已在下一首播放队列中",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 需求 2026-11-27：多选「下载」—— 把选中的在线歌曲批量加入下载队列。
     *
     * <p>跳过两类：本地收藏（负数 id / forceLocalPlayback，本来就是本机文件）、
     * 已经下载过的（避免重复占空间）。音质走 DownloadManager 的默认档（exhigh），
     * 与单曲「下载」里默认选项一致；真正的进度在下载管理页看。</p>
     */
    private void downloadSelected(List<Integer> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        final List<Song> targets = new ArrayList<>();
        int skipped = 0;
        for (int position : positions) {
            if (position < 0 || position >= items.size()) {
                continue;
            }
            Song song = items.get(position);
            if (song == null) {
                continue;
            }
            if (LocalTracks.isLocalId(song.getId()) || song.isForceLocalPlayback()) {
                skipped++;
                continue;
            }
            if (com.qinghe.music163pro.manager.DownloadManager.isDownloaded(song)) {
                skipped++;
                continue;
            }
            targets.add(song);
        }
        if (targets.isEmpty()) {
            toast("没有可下载的歌曲（本地或已下载 " + skipped + " 首）");
            return;
        }
        final String cookie = playerManager == null ? null : playerManager.getCookie();
        for (Song song : targets) {
            com.qinghe.music163pro.manager.DownloadManager.downloadSong(song, cookie,
                    new com.qinghe.music163pro.manager.DownloadManager.DownloadCallback() {
                        @Override
                        public void onSuccess(String filePath) {
                        }

                        @Override
                        public void onError(String message) {
                            // 单曲失败只记日志：批量场景下逐条弹 toast 会把屏幕刷满
                            com.qinghe.music163pro.util.MusicLog.i("FavDownload",
                                    "下载失败 " + song.getName() + ": " + message);
                        }
                    });
        }
        if (multiSelect != null) {
            multiSelect.exitSelectionMode();
        }
        toast("已加入下载队列 " + targets.size() + " 首"
                + (skipped > 0 ? "，跳过 " + skipped + " 首" : ""));
    }

    private void confirmDeleteSelected(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < items.size()) {
                targets.add(items.get(position));
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
                    toast("已取消收藏");
                });
    }

    private void showUnfavoriteDialog(Song song, int position) {
        showConfirmDialog("取消收藏", "确定取消收藏「" + song.getName() + "」？", () -> {
            if (isCloudMode()) {
                String cookie = cookie();
                if (cookie == null || cookie.isEmpty()) {
                    toast("请先登录");
                    return;
                }
                MusicApiHelper.likeTrack(song.getId(), false, cookie,
                        new MusicApiHelper.LikeCallback() {
                            @Override
                            public void onResult(boolean success) {
                                if (success) {
                                    removeItemAt(position);
                                    toast("已取消云端收藏");
                                } else {
                                    toast("操作失败");
                                }
                            }

                            @Override
                            public void onError(String message) {
                                toast("操作失败: " + message);
                            }
                        });
            } else {
                favoritesManager.removeFavorite(song);
                removeItemAt(position);
                toast("已取消收藏");
            }
        });
    }
}
