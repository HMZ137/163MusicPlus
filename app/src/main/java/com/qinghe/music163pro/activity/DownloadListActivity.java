package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.qinghe.music163pro.util.BatchFavoriteHelper;
import com.qinghe.music163pro.util.PlaybackMode;
import com.qinghe.music163pro.util.UiKit;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.util.NetworkImageLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Download list activity - shows all downloaded songs from /sdcard/163Music/Download/
 * 长按进入多选，可批量收藏 / 批量删除。
 */
public class DownloadListActivity extends BaseWatchActivity {

    private final List<Song> downloadedSongs = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private ListView lvDownloads;
    private TextView tvEmpty;
    private TextView tvTitle;
    private MultiSelectController multiSelect;

    /** 有下载/整理在进行中时，用来定时刷新列表（进度、新完成的条目）。 */
    private static final long REFRESH_INTERVAL_MS = 1000L;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            updateTitleText();
            // 队列空了就自然停下来，不需要外部再取消
            if (DownloadManager.getActiveDownloadCount() > 0) {
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }
    };
    private boolean tidyInProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_list);

        // 标题栏要撑到浮动返回按钮的占位高度，否则按钮会压住列表第一行的封面
        // 与本地音乐页同理：占位加在**整个标题栏**上。若只加给上面的动作行，那一行会被撑到
        // 返回按钮的高度，再叠一行标题，头部会白吃掉几十像素的列表空间。
        View titleBar = findViewById(R.id.layout_downloads_title_bar);
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, titleBar);
        // 标题栏里还叠了「下载 / 本地」标签行，占位高度要把这一行也算上 ——
        // 否则标题会被按剩余高度压缩，字的下半截被切掉（实机确认）。
        com.qinghe.music163pro.util.WatchBackButton.addTitleBarExtraHeight(
                this, titleBar, UiKit.dp(this, UiKit.DP_TAB_ROW_HEIGHT + 4));

        // Apply keep screen on setting

        lvDownloads = findViewById(R.id.lv_downloads);
        tvEmpty = findViewById(R.id.tv_empty);
        tvTitle = findViewById(R.id.tv_downloads_title);

        // 「整理」：把早期下载固定的 song.mp3 / song.flac 改成「曲名 - 曲师」
        View btnTidy = findViewById(R.id.btn_tidy_downloads);
        if (btnTidy != null) {
            btnTidy.setOnClickListener(v -> confirmTidyFileNames());
        }

        // 页内标签：下载（当前页）/ 本地。
        // 「本地」原先是动作行里的一个按钮，跳到本机音乐页；现在提成标签，样式与「收藏」页一致。
        TextView tabDownloads = findViewById(R.id.tab_downloads);
        TextView tabLocal = findViewById(R.id.tab_local_music);
        View tabRow = findViewById(R.id.ll_download_tabs);
        UiKit.setTabSelected(tabDownloads, true);
        UiKit.setTabSelected(tabLocal, false);
        if (tabDownloads != null) {
            tabDownloads.setOnClickListener(v -> { /* 已经在下载标签上，无需动作 */ });
        }
        if (tabLocal != null) {
            tabLocal.setOnClickListener(v -> openLocalMusicTab());
        }
        // 纯在线模式：「本地」入口收起（下载管理本身保留 —— 用户总得有地方管已下到卡里的歌）。
        // 只剩一个「下载」标签没有意义，整行一起收掉。
        if (PlaybackMode.isLocalEntryHidden(this) && tabRow != null) {
            tabRow.setVisibility(View.GONE);
        }

        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, downloadedSongs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText(song.getDisplayName());
                    tvArtist.setText(song.getArtist());
                    if (ivCover != null) {
                        // coverUrl may be local file:// path or remote URL
                        NetworkImageLoader.load(ivCover, song.getCoverUrl());
                    }
                    applyDownloadState(view, song);
                }
                return view;
            }
        };
        lvDownloads.setAdapter(adapter);
        NetworkImageLoader.attachListViewOptimization(lvDownloads, adapter, position ->
                position >= 0 && position < downloadedSongs.size()
                        ? downloadedSongs.get(position).getCoverUrl() : null);

        lvDownloads.setOnItemClickListener((parent, view, position, id) -> {
            playDownloadedSong(position);
        });

        // 长按进入多选模式，多选后可通过动作栏批量收藏或批量删除
        multiSelect = MultiSelectController.attach(this, lvDownloads,
                new MultiSelectController.Callback() {
                    @Override
                    public List<MultiSelectController.Action> getActions() {
                        List<MultiSelectController.Action> actions = new ArrayList<>();
                        actions.add(MultiSelectController.Action.favorite(DownloadListActivity.this));
                        actions.add(MultiSelectController.Action.delete(DownloadListActivity.this));
                        return actions;
                    }

                    @Override
                    public void onAction(MultiSelectController.Action action, List<Integer> positions) {
                        if (MultiSelectController.Action.ID_FAVORITE.equals(action.id)) {
                            favoriteSelected(positions);
                        } else if (MultiSelectController.Action.ID_DELETE.equals(action.id)) {
                            confirmDeleteSelected(positions);
                        }
                    }
                });
        if (multiSelect != null) {
            addBackInterceptor(multiSelect::handleBackPressed);
        }

        loadDownloads();
        updateEmptyState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 列表数据在 onResume 会重新加载，选中下标可能失效，先退出多选。
        if (multiSelect != null) {
            multiSelect.exitSelectionMode();
        }
        loadDownloads();
        updateEmptyState();
        // 进页面时若还有下载在跑，就跟一下进度
        if (DownloadManager.getActiveDownloadCount() > 0) {
            refreshHandler.removeCallbacks(refreshTick);
            refreshHandler.post(refreshTick);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 页面不可见时停止刷新，别白耗电
        refreshHandler.removeCallbacks(refreshTick);
    }

    /**
     * 行内显示这首歌的下载状态：「排队中…」/「下载中 45%」。
     * 不在队列里就隐藏，对已完成或已删除的条目没有影响。
     */
    private void applyDownloadState(View row, Song song) {
        TextView tvProgress = row.findViewById(R.id.tv_item_progress);
        if (tvProgress == null) {
            return;
        }
        DownloadManager.DownloadTask task = DownloadManager.getActiveDownload(song.getId());
        if (task == null) {
            tvProgress.setVisibility(View.GONE);
            return;
        }
        tvProgress.setVisibility(View.VISIBLE);
        if (task.state == DownloadManager.DownloadState.QUEUED) {
            tvProgress.setText("排队中…");
        } else if (task.percent >= 0) {
            tvProgress.setText("下载中 " + task.percent + "%");
        } else {
            tvProgress.setText("下载中…");
        }
    }

    /** 标题按优先级显示：整理进度 > 下载队列 > 常态标题。 */
    private void updateTitleText() {
        if (tvTitle == null || tidyInProgress) {
            return;
        }
        int pending = DownloadManager.getActiveDownloadCount();
        if (pending > 0) {
            tvTitle.setText("下载中 " + pending + " 项");
        } else {
            tvTitle.setText(R.string.downloads);
        }
    }

    /**
     * 删除选中的下载项。先确认，再逐首删除本地文件。
     */
    private void confirmDeleteSelected(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < downloadedSongs.size()) {
                targets.add(downloadedSongs.get(position));
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        showConfirmDialog("确认删除",
                "确定删除选中的 " + targets.size() + " 首歌曲？\n文件将被永久删除。", () -> {
                    int deleted = 0;
                    for (Song song : targets) {
                        if (DownloadManager.deleteDownload(song)) {
                            deleted++;
                        }
                    }
                    if (multiSelect != null) {
                        multiSelect.exitSelectionMode();
                    }
                    loadDownloads();
                    updateEmptyState();
                    Toast.makeText(DownloadListActivity.this,
                            "已删除 " + deleted + " 首", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * 批量收藏选中的下载歌曲。
     *
     * <p>收藏口径与单曲收藏保持一致：设置里切到「云端收藏」时走网易云接口（需要登录），
     * 否则写入本地收藏。详见 {@link BatchFavoriteHelper}。</p>
     */
    private void favoriteSelected(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < downloadedSongs.size()) {
                targets.add(downloadedSongs.get(position));
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        Toast.makeText(this, getString(R.string.multi_select_favoriting, targets.size()),
                Toast.LENGTH_SHORT).show();
        BatchFavoriteHelper.favoriteAll(this, targets, new BatchFavoriteHelper.Callback() {
            @Override
            public void onFinished(int added, int skipped, int failed, int total) {
                if (multiSelect != null) {
                    multiSelect.exitSelectionMode();
                }
                Toast.makeText(DownloadListActivity.this,
                        BatchFavoriteHelper.buildResultMessage(DownloadListActivity.this, added, failed),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNeedLogin() {
                Toast.makeText(DownloadListActivity.this,
                        R.string.multi_select_favorite_need_login, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 「整理」入口：把早期下载固定的 {@code song.mp3} / {@code song.flac}
     * 改成「曲名 - 曲师」。会改动磁盘上的文件名，所以先二次确认。
     */
    private void confirmTidyFileNames() {
        showConfirmDialog("整理文件名",
                "把早期下载的 song.mp3 改名为「曲名 - 曲师」？\n"
                        + "只影响早期下载的固定文件名，不会动你自己放进目录里的音乐。",
                this::tidyFileNames);
    }

    private void tidyFileNames() {
        tidyInProgress = true;
        Toast.makeText(this, "正在整理…", Toast.LENGTH_SHORT).show();
        DownloadManager.migrateLegacyFileNames(new DownloadManager.MigrateCallback() {
            @Override
            public void onProgress(int done, int total, String currentName) {
                // 进度直接写在标题上：表盘小，弹进度条反而挡列表
                if (tvTitle != null) {
                    tvTitle.setText("整理中 " + done + "/" + total);
                }
            }

            @Override
            public void onFinished(int renamed, int skipped, int failed) {
                tidyInProgress = false;
                String message;
                if (failed < 0) {
                    message = "整理失败，请重试";
                } else if (renamed == 0 && skipped == 0) {
                    message = "没有需要整理的文件";
                } else {
                    message = "已改名 " + renamed + " 首";
                    if (skipped > 0) {
                        message += "，跳过 " + skipped + " 首";
                    }
                    if (failed > 0) {
                        message += "，失败 " + failed + " 首";
                    }
                }
                Toast.makeText(DownloadListActivity.this, message, Toast.LENGTH_LONG).show();
                updateTitleText();
                loadDownloads();
                updateEmptyState();
            }
        });
    }

    private void loadDownloads() {
        downloadedSongs.clear();

        // Load from new subfolder format (with info.json)
        List<File> songDirs = DownloadManager.getDownloadedSongDirs();
        for (File dir : songDirs) {
            Song song = DownloadManager.loadSongInfo(dir);
            if (song != null) {
                song.setForceLocalPlayback(true);
                downloadedSongs.add(song);
            }
        }

        // Load legacy flat .mp3 files (backward compatibility)
        List<File> legacyFiles = DownloadManager.getDownloadedFiles();
        for (File file : legacyFiles) {
            Song song = fileToSong(file);
            downloadedSongs.add(song);
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateTitleText();

        // Fetch covers from network for songs without covers (legacy downloads)
        fetchMissingCovers();
    }

    /**
     * For songs in the download list that don't have a cover URL,
     * fetch their details from the network to get cover URLs.
     */
    private void fetchMissingCovers() {
        List<Long> missingIds = new ArrayList<>();
        for (Song song : downloadedSongs) {
            if (song.getId() > 0 && (song.getCoverUrl() == null || song.getCoverUrl().isEmpty())) {
                missingIds.add(song.getId());
            }
        }
        if (missingIds.isEmpty()) return;

        MusicPlayerManager pm = MusicPlayerManager.getInstance();
        String cookie = pm.getCookie();
        MusicApiHelper.fetchSongsDetails(missingIds, cookie,
                new MusicApiHelper.BatchSongDetailsCallback() {
                    @Override
                    public void onResult(Map<Long, Song> songMap) {
                        boolean changed = false;
                        for (Song song : downloadedSongs) {
                            if (song.getId() > 0 && (song.getCoverUrl() == null || song.getCoverUrl().isEmpty())) {
                                Song detail = songMap.get(song.getId());
                                if (detail != null && detail.getCoverUrl() != null && !detail.getCoverUrl().isEmpty()) {
                                    song.setCoverUrl(detail.getCoverUrl());
                                    changed = true;
                                }
                            }
                        }
                        if (changed && adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        // Network not available or error - just show without covers
                    }
                });
    }

    private void updateEmptyState() {
        if (downloadedSongs.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            lvDownloads.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            lvDownloads.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Build a full playlist from all downloaded songs and play the selected one.
     */
    private void playDownloadedSong(int position) {
        try {
            List<Song> playlist = new ArrayList<>(downloadedSongs);

            MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
            playerManager.setPlaylist(playlist, position);
            playerManager.playCurrent();

            // Navigate back to MainActivity
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Convert a loose audio file in the download root to a Song
     * (id=0, name/artist parsed from the 曲名 - 曲师 file name).
     */
    private Song fileToSong(File file) {
        // 先去掉扩展名（.mp3 / .flac / …），再按「曲名 - 曲师」解析
        String name = DownloadManager.stripAudioExtension(file.getName());
        String songName = name;
        String artist = "";
        int dashIdx = name.indexOf(" - ");
        if (dashIdx > 0) {
            songName = name.substring(0, dashIdx);
            artist = name.substring(dashIdx + 3);
        }
        Song song = new Song(0, songName, artist, "");
        song.setUrl(file.getAbsolutePath());
        song.setForceLocalPlayback(true);
        return song;
    }
    /**
     * 切到「本地」标签（本机音乐页）。
     *
     * <p>「本地音乐管理」现在是下载 / 本地两个标签，但两边的内容各自都是一个成熟页面
     * （下载有进度刷新、整理、批量收藏；本地有扫描、排序、搜索、局域网传歌），
     * 合成一个 Activity 要动两套列表与动作行，风险大于收益。所以标签仍然跨 Activity：
     * 先开目标页再 finish 自己，**不往返回栈里叠页面**（按返回键应当回到进入本页之前的
     * 位置，而不是在两个标签之间来回跳），并去掉切换动画，让它看着像页内切换。</p>
     */
    private void openLocalMusicTab() {
        startActivity(new Intent(this, LocalMusicActivity.class));
        overridePendingTransition(0, 0);
        finish();
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
        refreshHandler.removeCallbacks(refreshTick);
        NetworkImageLoader.cancelAll();
    }

}
