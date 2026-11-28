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

import androidx.appcompat.app.AppCompatActivity;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.manager.PlaylistManager;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.BatchFavoriteHelper;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.util.MultiSelectController;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.PinyinUtils;
import com.qinghe.music163pro.util.SongQuery;
import com.qinghe.music163pro.util.ScriptSort;
import com.qinghe.music163pro.util.WatchConfirmDialog;
import com.qinghe.music163pro.util.WatchUiUtils;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Playlist detail activity - shows songs in a playlist and allows playing them.
 * Supports cloud mode (subscribe/unsubscribe via API) and local mode (local save).
 * Rules:
 * - Cloud mode: "我喜欢的音乐" → no unsub/delete; my created → no unsub, can delete;
 *   others' → can unsub, no delete.
 * - Local mode: all can unsub (local remove), no delete.
 * Designed for watch screen (320x360 dpi).
 */
public class PlaylistDetailActivity extends BaseWatchActivity {

    private ListView lvSongs;
    private final List<Song> displayList = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private MusicPlayerManager playerManager;
    private PlaylistManager playlistManager;
    private TextView tvStatus;
    private TextView tvTitle;
    private TextView tvCreatorLabel;
    private long playlistId;
    private String playlistName;
    private int trackCount;
    private String creator;

    private long creatorUserId;
    private boolean isLikedPlaylist;
    /** 多选控制器（删除 / 收藏 / 下载）。 */
    private MultiSelectController multiSelect;
    /** 全量曲目快照：搜索与排序的基准（displayList 是过滤后的当前视图）。 */
    private final List<Song> allTracks = new ArrayList<>();
    private String searchQuery = "";
    /** 0 原顺序 / 1 曲名 / 2 曲师 / 3 专辑。 */
    private int sortMode = 0;

    // ── A-Z 首字母跳转（与「我的歌单」页同一套 LetterPicker 交互） ──────────
    private static final int REQUEST_LETTER_PICKER = 0x52;
    /** 分组首字母（与 starts/counts 等长），传给字母选择页。 */
    private final List<Character> groupLetters = new ArrayList<>();
    private int[] groupStarts = new int[0];
    private int[] groupCounts = new int[0];
    /** 字母跳转的目标位：列表重建（applyFilter）后应用，避免被刷新冲掉。 */
    private int pendingScrollTo = -1;

    private long currentUserId = -1;
    private boolean isCloudMode;
    private boolean isSubscribed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        isCloudMode = prefs.getBoolean("fav_mode_cloud", false);

        playlistId = getIntent().getLongExtra("playlist_id", 0);
        playlistName = getIntent().getStringExtra("playlist_name");
        trackCount = getIntent().getIntExtra("track_count", 0);
        creator = getIntent().getStringExtra("creator");
        creatorUserId = getIntent().getLongExtra("creator_user_id", 0);
        isLikedPlaylist = getIntent().getBooleanExtra("is_liked_playlist", false);

        playerManager = MusicPlayerManager.getInstance();
        playlistManager = new PlaylistManager();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(com.qinghe.music163pro.util.UiKit.COLOR_BG_SURFACE);
        root.setPadding(px(6), px(6), px(6), px(6));

        // Title row with heart + delete buttons on the right
        android.widget.RelativeLayout titleRow = new android.widget.RelativeLayout(this);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Title
        tvTitle = new TextView(this);
        updateTitleText();
        // P3：单行标题栏口径 —— 标题居左、右侧只留 ☰ 菜单（收藏/删除收进菜单）
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.watch_title_text_size));
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.START);
        tvTitle.setPadding(0, 0, px(44), px(4));
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.RelativeLayout.LayoutParams titleParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        tvTitle.setLayoutParams(titleParams);
        titleRow.addView(tvTitle);

        // P3：右上角只保留 ☰ 矢量菜单（搜索 / 排序 / A-Z / 收藏 / 删除 全收进菜单）
        ImageView btnMore = new ImageView(this);
        btnMore.setImageResource(R.drawable.ic_menu);
        btnMore.setColorFilter(com.qinghe.music163pro.util.ThemeManager.accentColor(this));
        btnMore.setClickable(true);
        btnMore.setFocusable(true);
        btnMore.setContentDescription("更多");
        android.widget.RelativeLayout.LayoutParams moreParams =
                new android.widget.RelativeLayout.LayoutParams(px(40), px(40));
        moreParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        moreParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        btnMore.setLayoutParams(moreParams);
        btnMore.setPadding(px(8), px(8), px(8), px(8));
        btnMore.setOnClickListener(v -> showMoreDialog());
        titleRow.addView(btnMore);

        root.addView(titleRow);

        // P3：独立信息行（口径）——「创建者 · N 首」，搜索时显示命中数；
        // 原先标题里的「(N首)」与这一行重复，已从标题移除。
        tvCreatorLabel = new TextView(this);
        tvCreatorLabel.setTextColor(0xB3FFFFFF);
        tvCreatorLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tvCreatorLabel.setGravity(Gravity.START);
        tvCreatorLabel.setSingleLine(true);
        tvCreatorLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvCreatorLabel.setPadding(px(2), px(2), px(2), px(2));
        root.addView(tvCreatorLabel);
        updateInfoRow();

        // Status text
        tvStatus = new TextView(this);
        tvStatus.setText("正在加载...");
        tvStatus.setTextColor(0x80FFFFFF);
        tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tvStatus.setGravity(Gravity.START);
        tvStatus.setPadding(px(2), px(2), px(2), px(2));
        root.addView(tvStatus);

        lvSongs = new ListView(this);
        lvSongs.setDividerHeight(1);
        lvSongs.setDivider(getResources().getDrawable(android.R.color.transparent));
        LinearLayout.LayoutParams lvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        lvSongs.setLayoutParams(lvParams);
        lvSongs.setVisibility(View.GONE);
        root.addView(lvSongs);

        setContentView(root);
        // P3：单行标题栏占位与 G5 标题居左统一机制（浮动返回按钮 / 物理圆角避让）
        com.qinghe.music163pro.util.WatchBackButton.applySingleRowTitleBarInset(this, titleRow);

        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, displayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText((position + 1) + ". " + song.getDisplayName());
                    tvArtist.setText(song.getArtist());
                    if (ivCover != null) {
                        NetworkImageLoader.load(ivCover, song.getCoverUrl());
                    }
                }
                return view;
            }
        };
        lvSongs.setAdapter(adapter);
        NetworkImageLoader.attachListViewOptimization(lvSongs, adapter, position ->
                position >= 0 && position < displayList.size()
                        ? displayList.get(position).getCoverUrl() : null);

        lvSongs.setOnItemClickListener((parent, view, position, id) -> {
            List<Song> playlist = new ArrayList<>(displayList);
            playerManager.setPlaylistFromSource(playlist, position,
                    playlistId, playlistName, trackCount, creator,
                    creatorUserId, isLikedPlaylist);
            playerManager.playCurrent();
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        // Long-press to delete song from user's created playlist (not liked playlist)
        // Works in both cloud and local mode (playlist lives on server regardless)
        lvSongs.setOnItemLongClickListener((parent, view, position, id) -> {
            if (isLikedPlaylist) return false;
            if (creatorUserId <= 0 || creatorUserId != currentUserId) return false;

            Song song = displayList.get(position);
            showConfirmDialog("确认删除", "确定从歌单中删除「" + song.getName() + "」？", () -> {
                String cookie = playerManager.getCookie();
                if (cookie == null || cookie.isEmpty()) {
                    com.qinghe.music163pro.util.UiKit.toast(this, "请先登录");
                    return;
                }
                com.qinghe.music163pro.util.UiKit.toast(this, "正在删除...");
                MusicApiHelper.playlistTracks("del", playlistId,
                        new long[]{song.getId()}, cookie,
                        new MusicApiHelper.PlaylistActionCallback() {
                            @Override
                            public void onResult(boolean success) {
                                if (success) {
                                    displayList.remove(position);
                                    adapter.notifyDataSetChanged();
                                    trackCount = displayList.size();
                                    updateTitleText();
                                    updateInfoRow();
                                    updateStatusRow();
                                    com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "已删除");
                                } else {
                                    com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "删除失败");
                                }
                            }

                            @Override
                            public void onError(String message) {
                                com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "删除失败: " + message);
                            }
                        });
            });
            return true;
        });

        // 长按进入多选：删除的**前置条件与原来完全一致**（非「我喜欢的」+ 自己是创建者 + 已登录），
        // 只是从「一次删一首」变成可批量，并顺带提供收藏与批量下载。
        multiSelect = MultiSelectController.attach(this, lvSongs,
                new MultiSelectController.Callback() {
                    @Override
                    public List<MultiSelectController.Action> getActions() {
                        List<MultiSelectController.Action> actions = new ArrayList<>();
                        // 任务 6（2026-11-21）：歌单内的歌曲也要支持「下一首播放」
                        actions.add(MultiSelectController.Action.playNext(PlaylistDetailActivity.this));
                        actions.add(MultiSelectController.Action.delete(PlaylistDetailActivity.this));
                        actions.add(MultiSelectController.Action.favorite(PlaylistDetailActivity.this));
                        actions.add(new MultiSelectController.Action("download", "下载",
                                com.qinghe.music163pro.util.ThemeManager.accentColor(PlaylistDetailActivity.this)));
                        // 任务 3（2026-11-21）：在线歌曲也能加入本地歌单
                        actions.add(new MultiSelectController.Action("add_to_local_playlist", "加入本地歌单",
                                com.qinghe.music163pro.util.ThemeManager.accentColor(PlaylistDetailActivity.this)));
                        return actions;
                    }

                    @Override
                    public void onAction(MultiSelectController.Action action, List<Integer> positions) {
                        if (MultiSelectController.Action.ID_DELETE.equals(action.id)) {
                            confirmDeleteSelected(positions);
                        } else if (MultiSelectController.Action.ID_FAVORITE.equals(action.id)) {
                            favoriteSelected(positions);
                        } else if ("download".equals(action.id)) {
                            downloadSelected(positions);
                        } else if (MultiSelectController.Action.ID_PLAY_NEXT.equals(action.id)) {
                            playNextSelected(positions);
                        } else if ("add_to_local_playlist".equals(action.id)) {
                            com.qinghe.music163pro.util.LocalPlaylistPicker.show(
                                    PlaylistDetailActivity.this, pick(positions), null);
                        }
                    }
                });
        if (multiSelect != null) {
            addBackInterceptor(multiSelect::handleBackPressed);
        }

        // Always get current user ID for playlist type identification (liked/created/others')
        String cookie = playerManager.getCookie();
        if (cookie != null && !cookie.isEmpty()) {
            MusicApiHelper.getUid(cookie, new MusicApiHelper.AccountCallback() {
                @Override
                public void onResult(JSONObject json) {
                    currentUserId = json.optLong("uid", -1);
                    updateActionButtons();
                }
                @Override
                public void onError(String message) {
                    updateActionButtons();
                }
            });
        }

        updateFavButton();
        updateActionButtons();

        if (playlistId > 0) {
            loadPlaylistDetail(playlistId);
        }
    }

    // ==================== 多选：删除 / 收藏 / 批量下载 ====================

    /** 取出选中的曲目（按当前显示位置）。 */
    private List<Song> pick(List<Integer> positions) {
        List<Song> targets = new ArrayList<>();
        if (positions == null) {
            return targets;
        }
        for (int position : positions) {
            if (position >= 0 && position < displayList.size()) {
                targets.add(displayList.get(position));
            }
        }
        return targets;
    }

    /**
     * 批量删除歌单内歌曲。
     *
     * <p>前置条件与改动前的「长按删除」**完全一致**，一条都没放松：</p>
     * <ul>
     *   <li>「我喜欢的音乐」不给删（云端本身也不允许）；</li>
     *   <li>不是自己创建的歌单不给删；</li>
     *   <li>没有登录态不给删。</li>
     * </ul>
     * <p>不同之处只是接口本身支持一次传多个 id，所以批量删除仍然**只发一次请求**。</p>
     */
    private void confirmDeleteSelected(List<Integer> positions) {
        final List<Song> targets = pick(positions);
        if (targets.isEmpty()) {
            return;
        }
        if (isLikedPlaylist) {
            com.qinghe.music163pro.util.UiKit.toast(this, "「我喜欢的音乐」不能删除歌曲");
            return;
        }
        if (creatorUserId <= 0 || creatorUserId != currentUserId) {
            com.qinghe.music163pro.util.UiKit.toast(this, "只有自己创建的歌单才能删除歌曲");
            return;
        }
        final String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "请先登录");
            return;
        }
        String message = targets.size() == 1
                ? "确定从歌单中删除「" + targets.get(0).getName() + "」？"
                : "确定从歌单中删除选中的 " + targets.size() + " 首？";
        showConfirmDialog("确认删除", message, () -> {
            final long[] ids = new long[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                ids[i] = targets.get(i).getId();
            }
            com.qinghe.music163pro.util.UiKit.toast(this, "正在删除...");
            MusicApiHelper.playlistTracks("del", playlistId, ids, cookie,
                    new MusicApiHelper.PlaylistActionCallback() {
                        @Override
                        public void onResult(boolean success) {
                            if (success) {
                                allTracks.removeAll(targets);
                                if (multiSelect != null) {
                                    multiSelect.exitSelectionMode();
                                }
                                trackCount = allTracks.size();
                                applyFilter();
                                updateTitleText();
                                com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "已删除");
                            } else {
                                com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "删除失败");
                            }
                        }

                        @Override
                        public void onError(String message) {
                            com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "删除失败: " + message);
                        }
                    });
        });
    }

    /** 批量收藏（云端模式走接口、本地模式进本地库，与单曲收藏同口径）。 */
    private void favoriteSelected(List<Integer> positions) {
        final List<Song> targets = pick(positions);
        if (targets.isEmpty()) {
            return;
        }
        BatchFavoriteHelper.favoriteAll(this, targets, new BatchFavoriteHelper.Callback() {
            @Override
            public void onFinished(int added, int skipped, int failed, int total) {
                if (multiSelect != null) {
                    multiSelect.exitSelectionMode();
                }
                Toast.makeText(PlaylistDetailActivity.this,
                        BatchFavoriteHelper.buildResultMessage(PlaylistDetailActivity.this, added, failed),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNeedLogin() {
                com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, R.string.multi_select_favorite_need_login);
            }
        });
    }

    /** 批量下载：串行提交，避免一次起一堆请求（与批量收藏同一思路）。 */
    /**
     * 任务 6（2026-11-21）：把选中的歌曲排到「下一首播放」，可叠加、同一首不重复。
     */
    private void playNextSelected(List<Integer> positions) {
        List<Song> targets = pick(positions);
        if (targets.isEmpty()) {
            return;
        }
        int added = 0;
        for (Song song : targets) {
            if (playerManager.queueNextSong(song)) {
                added++;
            }
        }
        com.qinghe.music163pro.util.UiKit.toast(this,
                added > 0 ? "已加入下一首播放：" + added + " 首"
                        : "所选歌曲已在下一首播放队列中");
    }

    private void downloadSelected(List<Integer> positions) {
        final List<Song> targets = pick(positions);
        if (targets.isEmpty()) {
            return;
        }
        final String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "请先登录");
            return;
        }
        if (multiSelect != null) {
            multiSelect.exitSelectionMode();
        }
        com.qinghe.music163pro.util.UiKit.toast(this, "开始下载 " + targets.size() + " 首");
        downloadNext(targets, 0, cookie);
    }

    private void downloadNext(final List<Song> targets, final int index, final String cookie) {
        if (index >= targets.size()) {
            Toast.makeText(this, "已提交 " + targets.size() + " 首到下载队列（去「下载管理」看进度）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        DownloadManager.downloadSong(targets.get(index), cookie, new DownloadManager.DownloadCallback() {
            @Override
            public void onSuccess(String filePath) {
                downloadNext(targets, index + 1, cookie);
            }

            @Override
            public void onError(String message) {
                // 单首失败不打断整批，继续下一首
                downloadNext(targets, index + 1, cookie);
            }
        });
    }

    // ==================== 更多：搜索 / 排序 ====================

    private void showMoreDialog() {
        final String[] items = {
                searchQuery.isEmpty() ? "搜索歌曲" : "搜索歌曲（当前：「" + searchQuery + "」）",
                searchQuery.isEmpty() ? "" : "清除搜索",
                "排序：原顺序",
                "排序：按曲名",
                "排序：按曲师",
                "排序：按专辑",
        };
        java.util.List<String> labels = new ArrayList<>();
        java.util.List<Runnable> actions = new ArrayList<>();
        labels.add("搜索歌曲");
        actions.add(this::showSearchDialog);
        if (!searchQuery.isEmpty()) {
            labels.add("清除搜索");
            actions.add(() -> {
                searchQuery = "";
                applyFilter();
            });
        }
        labels.add("排序：原顺序");
        actions.add(() -> setSortMode(0));
        labels.add("排序：按曲名");
        actions.add(() -> setSortMode(1));
        labels.add("排序：按曲师");
        actions.add(() -> setSortMode(2));
        labels.add("排序：按专辑");
        actions.add(() -> setSortMode(3));
        labels.add("A-Z 跳转");
        actions.add(this::openLetterPicker);
        // 任务 6（2026-11-21）：队列里有「下一首播放」时提供清空入口
        com.qinghe.music163pro.player.MusicPlayerManager pm =
                com.qinghe.music163pro.player.MusicPlayerManager.getInstance();
        if (!pm.getPlayNextQueue().isEmpty()) {
            labels.add("清空下一首播放（" + pm.getPlayNextQueue().size() + " 首）");
            actions.add(() -> {
                pm.clearPlayNextQueue();
                android.widget.Toast.makeText(this, "已清空下一首播放",
                        android.widget.Toast.LENGTH_SHORT).show();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        }
        // P3：收藏 / 删除也收进同一个 ☰ 菜单（原先是标题栏内联图标）
        if (canFavoritePlaylist()) {
            boolean saved = isCloudMode ? isSubscribed
                    : playlistManager.isPlaylistSaved(playlistId);
            labels.add(saved ? "取消收藏歌单" : "收藏歌单");
            actions.add(this::togglePlaylistFav);
        }
        int dangerIndex = com.qinghe.music163pro.util.WatchMenuDialog.NO_DANGER;
        if (canDeletePlaylist()) {
            labels.add("删除歌单");
            dangerIndex = labels.size() - 1;
            actions.add(this::onDeletePlaylist);
        }

        // 统一菜单弹层（标题单行固定 + 列表独立滚动）：修掉长标题与列表重叠的问题；
        // 当前排序项主色高亮（下标 = 前面「搜索歌曲/清除搜索」的条数 + 排序序号）
        int sortBase = searchQuery.isEmpty() ? 1 : 2;
        int selected = sortBase + Math.max(0, Math.min(3, sortMode));
        com.qinghe.music163pro.util.WatchMenuDialog.show(this,
                "歌单操作（当前排序：" + sortModeLabel() + "）",
                labels.toArray(new String[0]), selected, dangerIndex, which -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                });
    }

    private void setSortMode(int mode) {
        sortMode = mode;
        applyFilter();
        com.qinghe.music163pro.util.UiKit.toast(this, "已按「" + sortModeLabel() + "」排列");
    }

    private String sortModeLabel() {
        switch (sortMode) {
            case 1:
                return "曲名";
            case 2:
                return "曲师";
            case 3:
                return "专辑";
            default:
                return "原顺序";
        }
    }

    // ==================== A-Z 首字母跳转（复用 LetterPickerActivity） ====================

    /**
     * 打开独立的字母选择页。分组按当前显示顺序构建（A-Z 跳转语义 = 按「曲名」分组），
     * 首字母用 {@link ScriptSort#groupLabel}：拉丁/汉字/假名/西里尔统一折算成拉丁字母，
     * 与本地音乐页、我的歌单页的合并字母口径一致。
     */
    private void openLetterPicker() {
        if (sortMode != 1) {
            // 按曲名分组跳转只有在曲名排序下才有意义，自动切过去
            sortMode = 1;
            applyFilter();
            com.qinghe.music163pro.util.UiKit.toast(this, "已切到「曲名」排序");
        }
        buildLetterGroups();
        if (groupLetters.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "还没有可跳转的字母");
            return;
        }
        StringBuilder letters = new StringBuilder(groupLetters.size());
        for (Character letter : groupLetters) {
            letters.append(letter.charValue());
        }
        Intent intent = new Intent(this, LetterPickerActivity.class);
        intent.putExtra(LetterPickerActivity.EXTRA_LETTERS, letters.toString());
        intent.putExtra(LetterPickerActivity.EXTRA_STARTS, groupStarts);
        intent.putExtra(LetterPickerActivity.EXTRA_COUNTS, groupCounts);
        intent.putExtra(LetterPickerActivity.EXTRA_CURRENT, lvSongs.getFirstVisiblePosition());
        startActivityForResult(intent, REQUEST_LETTER_PICKER);
    }

    /** 按当前显示顺序建立「首字母 → 第一首下标」的分组（LinkedHashMap 保持字母出现顺序）。 */
    private void buildLetterGroups() {
        groupLetters.clear();
        java.util.LinkedHashMap<Character, int[]> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < displayList.size(); i++) {
            Song song = displayList.get(i);
            String label = ScriptSort.groupLabel(
                    song == null || song.getName() == null ? "" : song.getName());
            char letter = label == null || label.isEmpty() ? '#' : label.charAt(0);
            int[] slot = groups.get(letter);
            if (slot == null) {
                groups.put(letter, new int[]{i, 1});
            } else {
                slot[1]++;
            }
        }
        groupStarts = new int[groups.size()];
        groupCounts = new int[groups.size()];
        // 字母按字母表顺序展示（与本地音乐页一致），# 段固定最后
        java.util.List<Character> keys = new ArrayList<>(groups.keySet());
        java.util.Collections.sort(keys, (a, b) -> {
            if (a == '#') return b == '#' ? 0 : 1;
            if (b == '#') return -1;
            return Character.compare(a, b);
        });
        int index = 0;
        for (Character key : keys) {
            groupLetters.add(key);
            groupStarts[index] = groups.get(key)[0];
            groupCounts[index] = groups.get(key)[1];
            index++;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LETTER_PICKER || resultCode != RESULT_OK || data == null) {
            return;
        }
        int target = data.getIntExtra(LetterPickerActivity.EXTRA_RESULT_INDEX, -1);
        if (target < 0) {
            return;
        }
        // 先记下来再立即跳：万一后续 applyFilter 重建列表，位置也不会丢
        pendingScrollTo = Math.max(0, Math.min(target, displayList.size() - 1));
        lvSongs.setSelection(pendingScrollTo);
    }

    private String sortKey(Song song) {
        if (song == null) {
            return "";
        }
        switch (sortMode) {
            case 2:
                return song.getArtist() == null ? "" : song.getArtist();
            case 3:
                return song.getAlbum() == null ? "" : song.getAlbum();
            default:
                return song.getName() == null ? "" : song.getName();
        }
    }

    /** 按当前搜索词 + 排序方式重建列表（allTracks 是底稿，displayList 是视图）。 */
    private void applyFilter() {
        displayList.clear();
        if (searchQuery.isEmpty()) {
            displayList.addAll(allTracks);
        } else {
            final String needle = SongQuery.normalize(searchQuery);
            for (Song song : allTracks) {
                if (SongQuery.matches(song, needle)) {
                    displayList.add(song);
                }
            }
        }
        if (sortMode != 0) {
            final java.text.Collator collator =
                    java.text.Collator.getInstance(java.util.Locale.CHINA);
            java.util.Collections.sort(displayList, (l, r) -> {
                String lk = sortKey(l);
                String rk = sortKey(r);
                char li = PinyinUtils.initialOfWord(lk);
                char ri = PinyinUtils.initialOfWord(rk);
                if (li != ri) {
                    if (li == PinyinUtils.OTHER) return 1;
                    if (ri == PinyinUtils.OTHER) return -1;
                    return Character.compare(li, ri);
                }
                int byKey = collator.compare(lk, rk);
                return byKey != 0 ? byKey
                        : collator.compare(String.valueOf(l.getName()), String.valueOf(r.getName()));
            });
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        // A-Z 跳转留下的目标位：列表重建完成后落到目标分组
        if (pendingScrollTo >= 0 && !displayList.isEmpty()) {
            lvSongs.setSelection(Math.min(pendingScrollTo, displayList.size() - 1));
            pendingScrollTo = -1;
        }
        updateInfoRow();
        updateStatusRow();
    }

    private void showSearchDialog() {
        // P5：统一浮窗（输入栏带 × 清空）
        com.qinghe.music163pro.util.WatchPopup.input(this, "搜索歌单内歌曲", null,
                "曲名 / 曲师 / 专辑", searchQuery, "确定", text -> {
                    searchQuery = text;
                    applyFilter();
                });
    }

    private void updateTitleText() {
        // P3：数量移到独立信息行，标题只留歌单名（避免「歌单名 (N首)」+「N 首」重复）
        tvTitle.setText(playlistName != null ? playlistName : "歌单");
    }

    /** P3：独立信息行 ——「创建者 · N 首」；搜索时显示命中数。 */
    private void updateInfoRow() {
        if (tvCreatorLabel == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (creator != null && !creator.isEmpty()) {
            sb.append("创建者: ").append(creator);
        }
        if (!searchQuery.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("搜索命中 ").append(displayList.size()).append(" 首");
        } else {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            int count = displayList.isEmpty() ? trackCount : displayList.size();
            sb.append(Math.max(0, count)).append(" 首");
        }
        tvCreatorLabel.setText(sb.toString());
        tvCreatorLabel.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** P3：状态行只负责「加载中 / 暂无歌曲 / 没有匹配 / 加载失败」，数量交给信息行。 */
    private void updateStatusRow() {
        if (tvStatus == null) {
            return;
        }
        if (displayList.isEmpty()) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(searchQuery.isEmpty() ? "暂无歌曲" : "没有匹配的曲目");
        } else {
            tvStatus.setVisibility(View.GONE);
        }
    }

    /** 歌单动态：在创建者一行后面补上播放量/收藏/评论。 */
    private long dynamicLoadedFor = -1L;

    private void loadPlaylistDynamic(final long playlistId) {
        if (dynamicLoadedFor == playlistId) {
            return;
        }
        dynamicLoadedFor = playlistId;
        MusicApiHelper.getPlaylistDynamic(playlistId,
                com.qinghe.music163pro.player.MusicPlayerManager.getInstance().getCookie(),
                new MusicApiHelper.PlaylistDynamicCallback() {
                    @Override
                    public void onResult(long playCount, long subscribedCount,
                                         long commentCount, long shareCount) {
                        if (creator == null || creator.isEmpty()) {
                            return;
                        }
                        StringBuilder sb = new StringBuilder("创建者: ").append(creator);
                        sb.append(" · 播放 ").append(formatCount(playCount));
                        sb.append(" · 收藏 ").append(formatCount(subscribedCount));
                        if (commentCount > 0) {
                            sb.append(" · 评论 ").append(formatCount(commentCount));
                        }
                        sb.append(" · ").append(trackCount).append(" 首");
                        tvCreatorLabel.setText(sb.toString());
                    }

                    @Override
                    public void onError(String message) {
                        // 拿不到动态信息就保持原来的"创建者: xxx"
                    }
                });
    }

    private String formatCount(long n) {
        if (n >= 100000000L) {
            return String.format(java.util.Locale.CHINA, "%.1f亿", n / 100000000.0);
        }
        if (n >= 10000L) {
            return String.format(java.util.Locale.CHINA, "%.1f万", n / 10000.0);
        }
        return String.valueOf(n);
    }

    private void updateCreatorLabel() {
        if (creator != null && !creator.isEmpty()) {
            tvCreatorLabel.setText("创建者: " + creator);
            tvCreatorLabel.setVisibility(View.VISIBLE);
        } else {
            tvCreatorLabel.setVisibility(View.GONE);
        }
    }

    private void loadPlaylistDetail(long playlistId) {
        String cookie = playerManager.getCookie();
        MusicApiHelper.getPlaylistDetailWithMeta(playlistId, cookie, new MusicApiHelper.PlaylistDetailWithMetaCallback() {
            @Override
            public void onResult(List<Song> songs, int apiTrackCount, String apiCreator,
                                 long apiCreatorUserId, int specialType, boolean subscribed) {
                allTracks.clear();
                allTracks.addAll(songs);
                displayList.clear();
                displayList.addAll(songs);
                adapter.notifyDataSetChanged();

                // Update metadata from API response (self-correct for all entry points)
                trackCount = songs.size();
                if (apiCreatorUserId > 0) {
                    creatorUserId = apiCreatorUserId;
                }
                isLikedPlaylist = (specialType == 5);
                isSubscribed = subscribed;
                if (apiCreator != null && !apiCreator.isEmpty()) {
                    creator = apiCreator;
                }

                updateTitleText();
                updateCreatorLabel();
                loadPlaylistDynamic(playlistId);
                updateInfoRow();
                if (songs.isEmpty()) {
                    tvStatus.setVisibility(View.VISIBLE);
                    tvStatus.setText("暂无歌曲");
                } else {
                    tvStatus.setVisibility(View.GONE);
                    lvSongs.setVisibility(View.VISIBLE);
                }

                // Refresh buttons with corrected metadata
                updateActionButtons();
            }

            @Override
            public void onError(String message) {
                tvStatus.setText("加载失败: " + message);
            }
        });
    }

    /**
     * Update button visibility based on playlist type (always identified regardless of mode):
     *   - "我喜欢的音乐" (specialType=5) → no unsub, no delete
     *   - My created playlist → no unsub, can delete
     *   - Others' playlist → can sub/unsub (cloud) or local save/remove, no delete
     */
    /**
     * P3：收藏 / 删除已收进 ☰ 菜单（不再占用标题栏内联图标），
     * 这里只刷新信息行；菜单项按 {@link #canFavoritePlaylist()} / {@link #canDeletePlaylist()} 现算。
     */
    private void updateActionButtons() {
        updateInfoRow();
    }

    private void updateFavButton() {
        // 收藏态由 ☰ 菜单按需渲染（「收藏歌单 / 取消收藏歌单」），不再有独立图标按钮
    }

    /** 别人的歌单才可收藏 / 取消收藏（「我喜欢的音乐」与自建歌单不可）。 */
    private boolean canFavoritePlaylist() {
        return playlistId > 0 && !isLikedPlaylist
                && !(creatorUserId > 0 && creatorUserId == currentUserId);
    }

    /** 只有自建歌单可删除。 */
    private boolean canDeletePlaylist() {
        return playlistId > 0 && !isLikedPlaylist
                && creatorUserId > 0 && creatorUserId == currentUserId;
    }

    private void togglePlaylistFav() {
        if (playlistId <= 0) return;

        if (isCloudMode) {
            // Cloud mode: toggle between subscribe/unsubscribe based on current state
            String cookie = playerManager.getCookie();
            if (cookie == null || cookie.isEmpty()) {
                com.qinghe.music163pro.util.UiKit.toast(this, "请先登录");
                return;
            }
            if (isSubscribed) {
                com.qinghe.music163pro.util.UiKit.toast(this, "正在取消收藏...");
                MusicApiHelper.subscribePlaylist(playlistId, false, cookie,
                        new MusicApiHelper.PlaylistActionCallback() {
                    @Override
                    public void onResult(boolean success) {
                        if (success) {
                            isSubscribed = false;
                            playlistManager.removePlaylist(playlistId);
                            updateFavButton();
                            com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "已取消云端收藏");
                        } else {
                            com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "取消收藏失败");
                        }
                    }
                    @Override
                    public void onError(String message) {
                        com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "取消收藏失败: " + message);
                    }
                });
            } else {
                com.qinghe.music163pro.util.UiKit.toast(this, "正在收藏...");
                MusicApiHelper.subscribePlaylist(playlistId, true, cookie,
                        new MusicApiHelper.PlaylistActionCallback() {
                    @Override
                    public void onResult(boolean success) {
                        if (success) {
                            isSubscribed = true;
                            updateFavButton();
                            com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "已收藏到云端");
                        } else {
                            com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "收藏失败");
                        }
                    }
                    @Override
                    public void onError(String message) {
                        com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "收藏失败: " + message);
                    }
                });
            }
        } else {
            // Local mode: toggle local save
            boolean saved = playlistManager.isPlaylistSaved(playlistId);
            if (saved) {
                playlistManager.removePlaylist(playlistId);
                com.qinghe.music163pro.util.UiKit.toast(this, "已取消收藏歌单");
            } else {
                PlaylistInfo info = new PlaylistInfo(playlistId,
                        playlistName != null ? playlistName : "",
                        trackCount, creator != null ? creator : "",
                        creatorUserId, true, isLikedPlaylist ? "5" : "0");
                playlistManager.addPlaylist(info);
                com.qinghe.music163pro.util.UiKit.toast(this, "已收藏歌单");
            }
            updateFavButton();
        }
    }

    private void onDeletePlaylist() {
        if (playlistId <= 0) return;
        showConfirmDialog("确认删除", "确定从云端删除歌单「" + (playlistName != null ? playlistName : "") + "」？\n此操作不可恢复。", () -> {
            com.qinghe.music163pro.util.UiKit.toast(this, "正在删除...");
            String cookie = playerManager.getCookie();
            MusicApiHelper.deletePlaylist(playlistId, cookie,
                    new MusicApiHelper.PlaylistActionCallback() {
                @Override
                public void onResult(boolean success) {
                    if (success) {
                        playlistManager.removePlaylist(playlistId);
                        com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "已删除歌单");
                        finish();
                    } else {
                        com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "删除失败");
                    }
                }
                @Override
                public void onError(String message) {
                    com.qinghe.music163pro.util.UiKit.toast(PlaylistDetailActivity.this, "删除失败: " + message);
                }
            });
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
