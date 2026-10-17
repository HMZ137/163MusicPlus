package com.qinghe.music163pro.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.LocalPlaylistDetailActivity;
import com.qinghe.music163pro.activity.PlaybackStatsActivity;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.util.LocalMetadataIndex;
import com.qinghe.music163pro.util.LocalMusicLoader;
import com.qinghe.music163pro.util.LocalPlaylistManager;
import com.qinghe.music163pro.util.WatchConfirmDialog;
import com.qinghe.music163pro.util.WatchMenuDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 4-16：我的歌单 —— 「本地歌单」页。
 *
 * <p>改造前是 MyPlaylistsActivity 的 TAB_LOCAL 分支：列出 {@code /sdcard/163Music/Playlists}
 * 下的本地歌单（首个条目为「本地听歌排行」入口）、长按「改名/导出/批量添加本地歌曲/删除」、
 * 顶栏 + 新建本地歌单、状态行右侧「导入歌单」。</p>
 *
 * <p>「批量添加本地歌曲」复用 {@link LocalMusicLoader} 的扫描链路（与「本机音乐」页同一套），
 * 扫描完成后一次性写进歌单；用 {@link #localScanGeneration} 作废已离开页面后的回调。</p>
 */
public class LocalPlaylistsFragment extends MyPlaylistsPageFragment {

    private static final String PREFS = "music163_settings";
    private static final String KEY_RANK_NICK = "rank_entry_nickname";

    /** 批量添加本地歌曲的扫描代数：离开页面 / 再次触发时递增，旧扫描回调据此作废。 */
    private int localScanGeneration = 0;

    @Override
    public int pageIndex() {
        return PAGE_LOCAL;
    }

    @Override
    protected CharSequence pageName() {
        return "本地歌单";
    }

    @Override
    protected boolean hasImportButton() {
        return true;
    }

    // ── 加载 ────────────────────────────────────────────────────────────────

    @Override
    protected void loadData() {
        List<String> names = LocalPlaylistManager.listPlaylistNames();
        List<PlaylistInfo> playlists = new ArrayList<>();
        for (String name : names) {
            // 用 PlaylistInfo 承载，userId 设为 -1 使其在本页被当成「自己的歌单」
            PlaylistInfo pl = new PlaylistInfo(-1, name,
                    LocalPlaylistManager.getPlaylistSongCount(name), "本地");
            pl.setUserId(-1);
            pl.setCoverUrl(null);
            playlists.add(pl);
        }
        applyItems(playlists);
        updateCountStatus("暂无本地歌单，点「导入歌单」或 ☰ 新建");
    }

    @Override
    protected PlaylistInfo createRankEntry() {
        int count = com.qinghe.music163pro.util.PlaybackStats.getSummary(requireContext())
                .trackedSongCount;
        String nick = rankPrefs().getString(KEY_RANK_NICK, null);
        PlaylistInfo entry = new PlaylistInfo(RANK_ENTRY_ID, "本地听歌排行", count,
                nick != null && !nick.isEmpty() ? nick : "");
        entry.setUserId(-1);
        return entry;
    }

    @Override
    protected void openRankPage() {
        startActivity(new Intent(requireContext(), PlaybackStatsActivity.class));
    }

    // ── 行渲染 / 交互 ───────────────────────────────────────────────────────

    @Override
    protected void bindRow(View row, PlaylistInfo pl) {
        ImageView icon = row.findViewById(R.id.iv_playlist_icon);
        TextView tvName = row.findViewById(R.id.tv_name);
        TextView tvDetail = row.findViewById(R.id.tv_detail);
        // 本地歌单统一用音符图标，不区分归属
        icon.clearColorFilter();
        icon.setImageResource(R.drawable.ic_music_note);
        icon.setColorFilter(0xFFBB86FC);
        tvName.setText(pl.getName());
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        String detail = pl.getTrackCount() + "首";
        if (pl.getCreator() != null && !pl.getCreator().isEmpty()) {
            detail += " · " + pl.getCreator();
        }
        tvDetail.setText(detail);
        tvDetail.setTextColor(0xB3FFFFFF);
        tvDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
    }

    @Override
    protected void onRowClicked(PlaylistInfo pl) {
        Intent intent = new Intent(requireContext(), LocalPlaylistDetailActivity.class);
        intent.putExtra("playlist_name", pl.getName());
        startActivity(intent);
    }

    @Override
    protected boolean onRowLongClicked(PlaylistInfo pl) {
        showLocalPlaylistOptions(pl);
        return true;
    }

    // ── 新建 / 改名 / 删除 / 导出 ────────────────────────────────────────────

    @Override
    public void showCreatePlaylistDialog() {
        com.qinghe.music163pro.util.WatchPopup.input(requireActivity(),
                "新建本地歌单", null, "输入歌单名称", "", "创建", name -> {
                    if (name.isEmpty()) {
                        toast("请输入歌单名称");
                        return;
                    }
                    if (LocalPlaylistManager.playlistExists(name)) {
                        toast("歌单已存在");
                        return;
                    }
                    if (LocalPlaylistManager.createPlaylist(name)) {
                        toast("已创建「" + name + "」");
                        loadData();
                    } else {
                        toast("创建失败");
                    }
                });
    }

    private void showLocalPlaylistOptions(PlaylistInfo pl) {
        WatchMenuDialog.show(requireContext(), pl.getName(),
                new String[]{"改名", "导出", "批量添加本地歌曲", "删除"},
                WatchMenuDialog.NO_SELECTION, 3,
                which -> {
                    if (which == 0) {
                        showRenameLocalDialog(pl);
                    } else if (which == 1) {
                        exportLocalPlaylist(pl);
                    } else if (which == 2) {
                        addAllLocalSongsToPlaylist(pl);
                    } else {
                        showDeleteLocalDialog(pl);
                    }
                });
    }

    private void showRenameLocalDialog(PlaylistInfo pl) {
        com.qinghe.music163pro.util.WatchPopup.input(requireActivity(),
                "改名", null, "歌单名称", pl.getName(), "确认", newName -> {
                    if (newName.isEmpty()) {
                        toast("名称不能为空");
                        return;
                    }
                    if (newName.equals(pl.getName())) {
                        return;
                    }
                    if (LocalPlaylistManager.playlistExists(newName)) {
                        toast("歌单名已存在");
                        return;
                    }
                    if (LocalPlaylistManager.renamePlaylist(pl.getName(), newName)) {
                        toast("已重命名");
                        loadData();
                    } else {
                        toast("改名失败");
                    }
                });
    }

    private void showDeleteLocalDialog(PlaylistInfo pl) {
        com.qinghe.music163pro.util.WatchPopup.confirm(requireActivity(), "删除歌单",
                "确定删除「" + pl.getName() + "」？此操作不可撤销。", "删除", true, () -> {
                    if (LocalPlaylistManager.deletePlaylist(pl.getName())) {
                        toast("已删除");
                        loadData();
                    } else {
                        toast("删除失败");
                    }
                });
    }

    /** 导出：把本地歌单复制一份到 /sdcard/Download/<歌单名>.json，方便拷到电脑。 */
    private void exportLocalPlaylist(PlaylistInfo pl) {
        String safe = pl.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        File dest = new File(Environment.getExternalStorageDirectory(), "Download/" + safe + ".json");
        if (LocalPlaylistManager.exportToFile(pl.getName(), dest)) {
            toastLong("已导出到 " + dest.getAbsolutePath());
        } else {
            toast("导出失败");
        }
    }

    // ── 批量添加本地歌曲（复用 LocalMusicLoader 扫描链路） ────────────────────

    private void addAllLocalSongsToPlaylist(final PlaylistInfo pl) {
        final String name = pl.getName();
        WatchConfirmDialog.show(requireActivity(), "批量添加本地歌曲",
                "扫描本地音乐并全部加入「" + name + "」？\n已在歌单里的会自动跳过。",
                () -> startLocalScanForPlaylist(name),
                WatchConfirmDialog.Options.defaultOptions());
    }

    /** 后台跑一轮本地扫描，收尾时把扫到的歌一次性加进歌单。 */
    private void startLocalScanForPlaylist(final String playlistName) {
        final int generation = ++localScanGeneration;
        LocalMetadataIndex.init(requireContext());
        toast("正在扫描本地音乐…");
        final AtomicReference<List<Song>> found = new AtomicReference<>();
        LocalMusicLoader loader = new LocalMusicLoader(requireContext(), generation,
                new LocalMusicLoader.Callback() {
                    @Override
                    public void onFirstPaint(List<Song> cachedSongs) {
                        // 批量添加不需要首屏：等走完目录、元信息补齐后再统一写歌单
                    }

                    @Override
                    public void onWalkDone(List<Song> songs, int misses) {
                        found.set(songs);
                    }

                    @Override
                    public void onMetadataProgress(int processed, int total, boolean readingFromDisk) {
                        // 进度不展示：只收尾时 toast 一次结果
                    }

                    @Override
                    public void onFinished(boolean changed) {
                        if (generation != localScanGeneration || !isAdded()) {
                            return;
                        }
                        List<Song> songs = found.get();
                        if (songs == null || songs.isEmpty()) {
                            toast("没有扫描到本地歌曲");
                            return;
                        }
                        final List<Song> snapshot = new ArrayList<>(songs);
                        final int gen = generation;
                        new Thread(() -> {
                            final int added = LocalPlaylistManager.addSongs(playlistName, snapshot);
                            if (!isAdded()) {
                                return;
                            }
                            requireActivity().runOnUiThread(() -> {
                                if (gen != localScanGeneration || !isAdded()) {
                                    return;
                                }
                                toastLong(added > 0
                                        ? "已添加 " + added + " 首到「" + playlistName + "」"
                                        : "这些曲目已在歌单里");
                                loadData();
                            });
                        }, "local-playlist-add").start();
                    }

                    @Override
                    public boolean isCancelled() {
                        return generation != localScanGeneration || !isAdded();
                    }

                    @Override
                    public void onSkippedDirs(List<String> skipped) {
                        if (isAdded()) {
                            toastLong("已跳过 " + skipped.size() + " 个不可用的自定义扫描目录");
                        }
                    }
                });
        new Thread(loader::runBlocking, "local-scan-playlist").start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 作废仍在跑的「批量添加本地歌曲」扫描（避免回调已销毁的页面）
        localScanGeneration++;
    }

    // ── 导入歌单（约定目录扫描） ─────────────────────────────────────────────

    @Override
    protected void onImportClicked() {
        List<File> candidates = LocalPlaylistManager.findImportCandidates();
        if (candidates.isEmpty()) {
            toastLong("未找到可导入的歌单\n请把 json 放到："
                    + LocalPlaylistManager.getImportDir().getAbsolutePath());
            return;
        }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            names[i] = candidates.get(i).getName();
        }
        WatchMenuDialog.show(requireContext(), "导入歌单", names, which -> {
            File src = candidates.get(which);
            if (LocalPlaylistManager.importFromFile(src, null)) {
                toast("已导入「" + names[which] + "」");
                loadData();
            } else {
                toast("导入失败");
            }
        });
    }

    private SharedPreferences rankPrefs() {
        return requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
