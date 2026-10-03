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
    private ImageView btnFav;
    private ImageView btnDelete;
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
        root.setBackgroundColor(0xFF121212);
        root.setPadding(px(6), px(6), px(6), px(6));

        // Title row with heart + delete buttons on the right
        android.widget.RelativeLayout titleRow = new android.widget.RelativeLayout(this);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Title
        tvTitle = new TextView(this);
        updateTitleText();
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        tvTitle.setGravity(Gravity.CENTER);
        // 左右各留出可用空间：左边给浮动返回按钮，右边给「删除/收藏」那 44px 按钮
        // （按钮距右边缘还有一圈圆角安全内缩，所以右侧要比左侧多留一点）
        tvTitle.setPadding(px(56), 0, px(96), px(4));
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.RelativeLayout.LayoutParams titleParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        tvTitle.setLayoutParams(titleParams);
        titleRow.addView(tvTitle);

        // 删除按钮 —— 贴右上，但要留出「圆角安全内缩」，否则会被物理圆角切掉
        int corner = WatchUiUtils.cornerInset(this);
        btnDelete = new ImageView(this);
        btnDelete.setImageResource(R.drawable.ic_delete);
        btnDelete.setClickable(true);
        btnDelete.setFocusable(true);
        btnDelete.setId(View.generateViewId());
        // 触控区取 44px（原来 26x28 太小）；图标靠 padding 收到 ~20px 居中
        android.widget.RelativeLayout.LayoutParams delParams = new android.widget.RelativeLayout.LayoutParams(
                px(44), px(44));
        delParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        delParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        delParams.setMarginEnd(corner);
        btnDelete.setLayoutParams(delParams);
        btnDelete.setPadding(px(12), px(12), px(12), px(12));
        btnDelete.setVisibility(View.GONE);
        btnDelete.setOnClickListener(v -> onDeletePlaylist());
        titleRow.addView(btnDelete);

        // 收藏按钮 —— **直接贴父容器右端，不再用 LEFT_OF 删除按钮定位**。
        //
        // 原来写的是 LEFT_OF btnDelete，而 updateActionButtons() 里「别人的歌单」那一支
        // （雷达歌单就属于这种）会把 btnDelete 设为 GONE —— RelativeLayout 中相对一个 GONE
        // 的视图定位，依赖方会被甩到父容器最左侧。结果收藏按钮跑到左上角，正好压在浮动
        // 返回按钮底下：位置错、而且点不到。
        // 两个按钮在任何分支里都不会同时可见，所以各自贴右端即可，不会重叠。
        btnFav = new ImageView(this);
        btnFav.setClickable(true);
        btnFav.setFocusable(true);
        android.widget.RelativeLayout.LayoutParams favParams = new android.widget.RelativeLayout.LayoutParams(
                px(44), px(44));
        favParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        favParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        favParams.setMarginEnd(corner);
        btnFav.setLayoutParams(favParams);
        btnFav.setPadding(px(12), px(12), px(12), px(12));
        btnFav.setOnClickListener(v -> togglePlaylistFav());
        // 给收藏按钮一个 id，供「更多」按钮 LEFT_OF 链式避让
        btnFav.setId(View.generateViewId());
        titleRow.addView(btnFav);

        // 「更多」：搜索 / 排序。放收藏按钮左侧，链式避让，不会互相顶掉。
        TextView btnMore = new TextView(this);
        btnMore.setText(R.string.local_music_more);
        btnMore.setTextColor(getResources().getColor(R.color.colorPrimary));
        btnMore.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        btnMore.setPadding(px(6), px(6), px(6), px(6));
        btnMore.setClickable(true);
        btnMore.setFocusable(true);
        android.widget.RelativeLayout.LayoutParams moreParams =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        // 锚点：始终 INVISIBLE（INVISIBLE 仍参与布局，GONE 不参与）——
        // 原来 LEFT_OF btnFav，而自己创建的歌单会把 btnFav 设成 GONE，
        // RelativeLayout 甩依赖方到最左侧，「更多」就被返回按钮完全遮挡了。
        View endAnchor = new View(this);
        endAnchor.setId(View.generateViewId());
        android.widget.RelativeLayout.LayoutParams anchorParams =
                new android.widget.RelativeLayout.LayoutParams(px(44) + corner, px(1));
        anchorParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        anchorParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        endAnchor.setLayoutParams(anchorParams);
        endAnchor.setVisibility(View.INVISIBLE);
        titleRow.addView(endAnchor);

        moreParams.addRule(android.widget.RelativeLayout.LEFT_OF, endAnchor.getId());
        moreParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        btnMore.setLayoutParams(moreParams);
        btnMore.setOnClickListener(v -> showMoreDialog());
        titleRow.addView(btnMore);

        root.addView(titleRow);

        // Creator label (always created, visibility depends on data)
        tvCreatorLabel = new TextView(this);
        tvCreatorLabel.setTextColor(0xB3FFFFFF);
        tvCreatorLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tvCreatorLabel.setGravity(Gravity.CENTER);
        tvCreatorLabel.setPadding(0, 0, 0, px(2));
        updateCreatorLabel();
        root.addView(tvCreatorLabel);

        // Status text
        tvStatus = new TextView(this);
        tvStatus.setText("正在加载...");
        tvStatus.setTextColor(0x80FFFFFF);
        tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, px(4), 0, px(4));
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
                    Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(this, "正在删除...", Toast.LENGTH_SHORT).show();
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
                                    tvStatus.setText(trackCount + " 首歌曲");
                                    Toast.makeText(PlaylistDetailActivity.this,
                                            "已删除", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(PlaylistDetailActivity.this,
                                            "删除失败", Toast.LENGTH_SHORT).show();
                                }
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(PlaylistDetailActivity.this,
                                        "删除失败: " + message, Toast.LENGTH_SHORT).show();
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
                        actions.add(MultiSelectController.Action.delete(PlaylistDetailActivity.this));
                        actions.add(MultiSelectController.Action.favorite(PlaylistDetailActivity.this));
                        actions.add(new MultiSelectController.Action("download", "下载",
                                getResources().getColor(R.color.colorPrimary)));
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
            Toast.makeText(this, "「我喜欢的音乐」不能删除歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        if (creatorUserId <= 0 || creatorUserId != currentUserId) {
            Toast.makeText(this, "只有自己创建的歌单才能删除歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        final String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "正在删除...", Toast.LENGTH_SHORT).show();
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
                                Toast.makeText(PlaylistDetailActivity.this,
                                        "已删除", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(PlaylistDetailActivity.this,
                                        "删除失败", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(PlaylistDetailActivity.this,
                                    "删除失败: " + message, Toast.LENGTH_SHORT).show();
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
                Toast.makeText(PlaylistDetailActivity.this,
                        R.string.multi_select_favorite_need_login, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 批量下载：串行提交，避免一次起一堆请求（与批量收藏同一思路）。 */
    private void downloadSelected(List<Integer> positions) {
        final List<Song> targets = pick(positions);
        if (targets.isEmpty()) {
            return;
        }
        final String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        if (multiSelect != null) {
            multiSelect.exitSelectionMode();
        }
        Toast.makeText(this, "开始下载 " + targets.size() + " 首", Toast.LENGTH_SHORT).show();
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

        // 统一菜单弹层（标题单行固定 + 列表独立滚动）：修掉长标题与列表重叠的问题；
        // 当前排序项主色高亮（下标 = 前面「搜索歌曲/清除搜索」的条数 + 排序序号）
        int sortBase = searchQuery.isEmpty() ? 1 : 2;
        int selected = sortBase + Math.max(0, Math.min(3, sortMode));
        com.qinghe.music163pro.util.WatchMenuDialog.show(this,
                "歌单操作（当前排序：" + sortModeLabel() + "）",
                labels.toArray(new String[0]), selected, which -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                });
    }

    private void setSortMode(int mode) {
        sortMode = mode;
        applyFilter();
        Toast.makeText(this, "已按「" + sortModeLabel() + "」排列", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "已切到「曲名」排序", Toast.LENGTH_SHORT).show();
        }
        buildLetterGroups();
        if (groupLetters.isEmpty()) {
            Toast.makeText(this, "还没有可跳转的字母", Toast.LENGTH_SHORT).show();
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
        if (displayList.isEmpty() && searchQuery.isEmpty()) {
            tvStatus.setText("暂无歌曲");
        } else if (!searchQuery.isEmpty()) {
            tvStatus.setText("搜索「" + searchQuery + "」命中 " + displayList.size() + " 首");
        } else {
            tvStatus.setText(displayList.size() + " 首歌曲");
        }
    }

    private void showSearchDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("曲名 / 曲师 / 专辑");
        input.setText(searchQuery);
        input.setSingleLine(true);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);

        LinearLayout container = new LinearLayout(this);
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new android.app.AlertDialog.Builder(this)
                .setTitle("搜索歌单内歌曲")
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    searchQuery = input.getText().toString().trim();
                    applyFilter();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateTitleText() {
        String titleText = playlistName != null ? playlistName : "歌单";
        if (trackCount > 0) {
            titleText += " (" + trackCount + "首)";
        }
        tvTitle.setText(titleText);
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
                if (songs.isEmpty()) {
                    tvStatus.setText("暂无歌曲");
                } else {
                    tvStatus.setText(songs.size() + " 首歌曲");
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
    private void updateActionButtons() {
        if (isLikedPlaylist) {
            // "我喜欢的音乐" - cannot unsub or delete
            btnFav.setVisibility(View.GONE);
            btnDelete.setVisibility(View.GONE);
        } else if (creatorUserId > 0 && creatorUserId == currentUserId) {
            // My created playlist - cannot unsub but can delete
            btnFav.setVisibility(View.GONE);
            btnDelete.setVisibility(View.VISIBLE);
        } else {
            // Others' playlist - can sub/unsub or local save/remove, cannot delete
            btnFav.setVisibility(View.VISIBLE);
            btnDelete.setVisibility(View.GONE);
            updateFavButton();
        }
    }

    private void updateFavButton() {
        if (playlistId <= 0) {
            btnFav.setVisibility(View.GONE);
            return;
        }
        if (isCloudMode) {
            btnFav.setImageResource(isSubscribed ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            btnFav.setColorFilter(isSubscribed ? 0xFFFF4081 : 0x80FFFFFF);
        } else {
            boolean saved = playlistManager.isPlaylistSaved(playlistId);
            btnFav.setImageResource(saved ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            btnFav.setColorFilter(saved ? 0xFFFF4081 : 0x80FFFFFF);
        }
    }

    private void togglePlaylistFav() {
        if (playlistId <= 0) return;

        if (isCloudMode) {
            // Cloud mode: toggle between subscribe/unsubscribe based on current state
            String cookie = playerManager.getCookie();
            if (cookie == null || cookie.isEmpty()) {
                Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isSubscribed) {
                Toast.makeText(this, "正在取消收藏...", Toast.LENGTH_SHORT).show();
                MusicApiHelper.subscribePlaylist(playlistId, false, cookie,
                        new MusicApiHelper.PlaylistActionCallback() {
                    @Override
                    public void onResult(boolean success) {
                        if (success) {
                            isSubscribed = false;
                            playlistManager.removePlaylist(playlistId);
                            updateFavButton();
                            Toast.makeText(PlaylistDetailActivity.this,
                                    "已取消云端收藏", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(PlaylistDetailActivity.this,
                                    "取消收藏失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                    @Override
                    public void onError(String message) {
                        Toast.makeText(PlaylistDetailActivity.this,
                                "取消收藏失败: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                Toast.makeText(this, "正在收藏...", Toast.LENGTH_SHORT).show();
                MusicApiHelper.subscribePlaylist(playlistId, true, cookie,
                        new MusicApiHelper.PlaylistActionCallback() {
                    @Override
                    public void onResult(boolean success) {
                        if (success) {
                            isSubscribed = true;
                            updateFavButton();
                            Toast.makeText(PlaylistDetailActivity.this,
                                    "已收藏到云端", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(PlaylistDetailActivity.this,
                                    "收藏失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                    @Override
                    public void onError(String message) {
                        Toast.makeText(PlaylistDetailActivity.this,
                                "收藏失败: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } else {
            // Local mode: toggle local save
            boolean saved = playlistManager.isPlaylistSaved(playlistId);
            if (saved) {
                playlistManager.removePlaylist(playlistId);
                Toast.makeText(this, "已取消收藏歌单", Toast.LENGTH_SHORT).show();
            } else {
                PlaylistInfo info = new PlaylistInfo(playlistId,
                        playlistName != null ? playlistName : "",
                        trackCount, creator != null ? creator : "",
                        creatorUserId, true, isLikedPlaylist ? "5" : "0");
                playlistManager.addPlaylist(info);
                Toast.makeText(this, "已收藏歌单", Toast.LENGTH_SHORT).show();
            }
            updateFavButton();
        }
    }

    private void onDeletePlaylist() {
        if (playlistId <= 0) return;
        showConfirmDialog("确认删除", "确定从云端删除歌单「" + (playlistName != null ? playlistName : "") + "」？\n此操作不可恢复。", () -> {
            Toast.makeText(this, "正在删除...", Toast.LENGTH_SHORT).show();
            String cookie = playerManager.getCookie();
            MusicApiHelper.deletePlaylist(playlistId, cookie,
                    new MusicApiHelper.PlaylistActionCallback() {
                @Override
                public void onResult(boolean success) {
                    if (success) {
                        playlistManager.removePlaylist(playlistId);
                        Toast.makeText(PlaylistDetailActivity.this,
                                "已删除歌单", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(PlaylistDetailActivity.this,
                                "删除失败", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override
                public void onError(String message) {
                    Toast.makeText(PlaylistDetailActivity.this,
                            "删除失败: " + message, Toast.LENGTH_SHORT).show();
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
