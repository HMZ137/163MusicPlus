package com.qinghe.music163pro.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.BatchFavoriteHelper;
import com.qinghe.music163pro.util.LocalPlaylistManager;
import com.qinghe.music163pro.util.MultiSelectController;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.PinyinUtils;
import com.qinghe.music163pro.util.SongQuery;
import com.qinghe.music163pro.util.ScriptSort;
import com.qinghe.music163pro.util.WatchBackButton;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 本地歌单详情：列出歌单内的本地歌曲，点击播放；**长按进入多选**，可批量
 * 移出歌单 / 收藏 / 上移 / 下移；标题栏「更多」里可搜索歌单内歌曲、切换排序方式。
 *
 * <p>歌单本身是一份 {@code /sdcard/163Music/Playlists/&lt;名&gt;.json}（与导入导出同格式），
 * 这里的歌曲只按 {@code path} 引用本地音频文件，不内嵌音频数据；**移出歌单不会删除文件**。</p>
 *
 * <p><b>排序分两种：</b>「自定义」直接改歌单文件里的顺序（上移 / 下移，持久化）；
 * 「按曲名 / 曲师 / 专辑」只是当前视图的排序（不改文件）。</p>
 */
public class LocalPlaylistDetailActivity extends BaseWatchActivity {

    private static final int SORT_CUSTOM = 0;
    private static final int SORT_NAME = 1;
    private static final int SORT_ARTIST = 2;
    private static final int SORT_ALBUM = 3;

    private String playlistName;
    /** 当前显示的列表（搜索过滤后的结果）。 */
    private final List<Song> songs = new ArrayList<>();
    /** 歌单全量（搜索基准）。 */
    private final List<Song> allSongs = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private ListView lvSongs;
    private TextView tvEmpty;
    private TextView tvTitle;
    private TextView tvStatusRow;
    private MultiSelectController multiSelect;

    private String searchQuery = "";
    private int sortMode = SORT_CUSTOM;

    // ── A-Z 首字母跳转（与「我的歌单」页同一套 LetterPicker 交互） ──────────
    private static final int REQUEST_LETTER_PICKER = 0x52;
    /** 分组首字母（与 starts/counts 等长），传给字母选择页。 */
    private final List<Character> groupLetters = new ArrayList<>();
    private int[] groupStarts = new int[0];
    private int[] groupCounts = new int[0];
    /** 字母跳转的目标位：列表重建（applySearch）后应用，避免被刷新冲掉。 */
    private int pendingScrollTo = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playlistName = getIntent().getStringExtra("playlist_name");
        if (playlistName == null) playlistName = "";

        setContentView(R.layout.activity_local_playlist_detail);

        tvTitle = findViewById(R.id.tv_local_playlist_title);
        lvSongs = findViewById(R.id.lv_local_playlist);
        tvEmpty = findViewById(R.id.tv_local_playlist_empty);
        tvTitle.setText(playlistName);
        // 标题栏撑到左上角浮动返回按钮（X）的占位高度，避免压住第一行
        WatchBackButton.applyTitleBarInset(this, findViewById(R.id.layout_local_playlist_title_bar));

        tvStatusRow = findViewById(R.id.tv_status_row);
        if (tvStatusRow != null) {
            tvStatusRow.setOnClickListener(v -> {
                if (!searchQuery.isEmpty()) {
                    applySearch("");
                }
            });
        }
        View btnMore = findViewById(R.id.btn_local_playlist_more);
        if (btnMore != null) {
            btnMore.setOnClickListener(v -> showMoreDialog());
        }

        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, songs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText(song.getDisplayName());
                    tvArtist.setText(song.getArtist() == null ? "" : song.getArtist());
                    if (ivCover != null) {
                        NetworkImageLoader.load(ivCover, song.getCoverUrl());
                    }
                }
                return view;
            }
        };
        lvSongs.setAdapter(adapter);

        // 长按进入多选（原「长按直接移出」的语义改由多选里的「移出歌单」承担，
        // 这样单选与批量走同一套确认/文案）。
        multiSelect = MultiSelectController.attach(this, lvSongs,
                new MultiSelectController.Callback() {
                    @Override
                    public List<MultiSelectController.Action> getActions() {
                        List<MultiSelectController.Action> actions = new ArrayList<>();
                        actions.add(MultiSelectController.Action.delete(LocalPlaylistDetailActivity.this));
                        actions.add(MultiSelectController.Action.favorite(LocalPlaylistDetailActivity.this));
                        actions.add(new MultiSelectController.Action("move_up", "上移",
                                getResources().getColor(R.color.colorPrimary)));
                        actions.add(new MultiSelectController.Action("move_down", "下移",
                                getResources().getColor(R.color.colorPrimary)));
                        return actions;
                    }

                    @Override
                    public void onAction(MultiSelectController.Action action, List<Integer> positions) {
                        if (MultiSelectController.Action.ID_DELETE.equals(action.id)) {
                            confirmRemoveSelected(positions);
                        } else if (MultiSelectController.Action.ID_FAVORITE.equals(action.id)) {
                            favoriteSelected(positions);
                        } else if ("move_up".equals(action.id)) {
                            moveSelected(positions, -1);
                        } else if ("move_down".equals(action.id)) {
                            moveSelected(positions, 1);
                        }
                    }
                });
        if (multiSelect != null) {
            addBackInterceptor(multiSelect::handleBackPressed);
        }
        lvSongs.setOnItemClickListener((parent, view, position, id) -> playFrom(position));

        loadSongs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (multiSelect != null) {
            multiSelect.exitSelectionMode();
        }
        loadSongs();
    }

    // ==================== 数据 ====================

    private void loadSongs() {
        allSongs.clear();
        allSongs.addAll(LocalPlaylistManager.getPlaylistSongs(playlistName));
        applySearch(searchQuery);
    }

    /** 应用搜索词并把结果写进当前列表（空串＝显示全量）。 */
    private void applySearch(String query) {
        searchQuery = query == null ? "" : query;
        songs.clear();
        if (searchQuery.isEmpty()) {
            songs.addAll(allSongs);
        } else {
            final String needle = SongQuery.normalize(searchQuery);
            for (Song song : allSongs) {
                if (SongQuery.matches(song, needle)) {
                    songs.add(song);
                }
            }
        }
        applySort();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        // A-Z 跳转留下的目标位：列表重建完成后落到目标分组
        if (pendingScrollTo >= 0 && !songs.isEmpty()) {
            lvSongs.setSelection(Math.min(pendingScrollTo, songs.size() - 1));
            pendingScrollTo = -1;
        }
        updateEmptyAndStatus();
    }

    /** 按当前排序方式排列当前视图（自定义＝保持文件顺序）。 */
    private void applySort() {
        if (sortMode == SORT_CUSTOM) {
            return;
        }
        final Collator collator = Collator.getInstance(Locale.CHINA);
        java.util.Collections.sort(songs, (l, r) -> {
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
            if (byKey != 0) {
                return byKey;
            }
            return collator.compare(String.valueOf(l.getName()), String.valueOf(r.getName()));
        });
    }

    private String sortKey(Song song) {
        if (song == null) {
            return "";
        }
        switch (sortMode) {
            case SORT_ARTIST:
                return song.getArtist() == null ? "" : song.getArtist();
            case SORT_ALBUM:
                return song.getAlbum() == null ? "" : song.getAlbum();
            default:
                return song.getName() == null ? "" : song.getName();
        }
    }

    private String sortModeLabel() {
        switch (sortMode) {
            case SORT_NAME:
                return "曲名";
            case SORT_ARTIST:
                return "曲师";
            case SORT_ALBUM:
                return "专辑";
            default:
                return "自定义";
        }
    }

    private void updateEmptyAndStatus() {
        boolean empty = songs.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        lvSongs.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            tvEmpty.setText(searchQuery.isEmpty() ? "歌单为空" : "没有匹配的曲目");
        }
        if (tvStatusRow != null) {
            if (searchQuery.isEmpty()) {
                tvStatusRow.setVisibility(View.GONE);
            } else {
                tvStatusRow.setVisibility(View.VISIBLE);
                tvStatusRow.setText("搜索「" + searchQuery + "」命中 " + songs.size() + " 首（点按清除）");
            }
        }
    }

    private void playFrom(int position) {
        if (position < 0 || position >= songs.size()) {
            return;
        }
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        playerManager.setPlaylist(new ArrayList<>(songs), position);
        playerManager.playCurrent();
        finish();
    }

    // ==================== 多选动作 ====================

    /**
     * 批量移出歌单。
     *
     * <p>与改动前「长按单首移出」的语义完全一致：**只从歌单里去引用，不删卡上的文件**，
     * 且同样二次确认。区别只是能一次处理多首。</p>
     */
    private void confirmRemoveSelected(List<Integer> positions) {
        final List<Song> targets = pick(positions);
        if (targets.isEmpty()) {
            return;
        }
        String message = targets.size() == 1
                ? "确定将「" + targets.get(0).getName() + "」移出歌单？"
                : "确定将选中的 " + targets.size() + " 首移出歌单？";
        com.qinghe.music163pro.util.WatchPopup.confirm(this, "从歌单移除",
                message + "\n不会删除卡里的文件。", "移除", true, () -> {
                    // 用「按路径过滤后整体重写」的方式一次落盘，避免逐首重写 json
                    List<Song> remain = new ArrayList<>();
                    for (Song s : allSongs) {
                        if (!containsPath(targets, s.getUrl())) {
                            remain.add(s);
                        }
                    }
                    if (LocalPlaylistManager.saveOrder(playlistName, remain)) {
                        if (multiSelect != null) {
                            multiSelect.exitSelectionMode();
                        }
                        Toast.makeText(this, "已移出 " + targets.size() + " 首",
                                Toast.LENGTH_SHORT).show();
                        loadSongs();
                    } else {
                        Toast.makeText(this, "移除失败", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** 批量加入本地收藏（本地曲目一律进本地收藏库，与单曲收藏口径一致）。 */
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
                Toast.makeText(LocalPlaylistDetailActivity.this,
                        BatchFavoriteHelper.buildResultMessage(LocalPlaylistDetailActivity.this, added, failed),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNeedLogin() {
                Toast.makeText(LocalPlaylistDetailActivity.this,
                        R.string.multi_select_favorite_need_login, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 自定义排序：把选中的曲目整体上移 / 下移一位，并**写回歌单文件**。
     *
     * <p>按字段排序（曲名/曲师/专辑）只是视图排序，和自定义顺序冲突，所以一旦手动移动，
     * 就自动切回「自定义」并提示一声，避免"移了却没生效"的错觉。</p>
     */
    private void moveSelected(List<Integer> positions, int delta) {
        final List<Song> targets = pick(positions);
        if (targets.isEmpty()) {
            return;
        }
        if (sortMode != SORT_CUSTOM) {
            sortMode = SORT_CUSTOM;
            Toast.makeText(this, "已切回「自定义」顺序（上移/下移只对自定义顺序生效）",
                    Toast.LENGTH_LONG).show();
        }
        List<Song> order = new ArrayList<>(allSongs);
        if (delta < 0) {
            // 上移：从小到大依次与前一位置换，避免多选时自己人挤在一起
            Integer[] idx = selectedIndices(positions);
            Arrays.sort(idx);
            for (int i : idx) {
                if (i > 0) {
                    java.util.Collections.swap(order, i, i - 1);
                }
            }
        } else {
            Integer[] idx = selectedIndices(positions);
            Arrays.sort(idx, java.util.Collections.reverseOrder());
            for (int i : idx) {
                if (i < order.size() - 1) {
                    java.util.Collections.swap(order, i, i + 1);
                }
            }
        }
        if (LocalPlaylistManager.saveOrder(playlistName, order)) {
            if (multiSelect != null) {
                multiSelect.exitSelectionMode();
            }
            loadSongs();
        } else {
            Toast.makeText(this, "保存顺序失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 取出选中的歌曲（按当前列表位置）。 */
    private List<Song> pick(List<Integer> positions) {
        List<Song> targets = new ArrayList<>();
        if (positions == null) {
            return targets;
        }
        for (int position : positions) {
            if (position >= 0 && position < songs.size()) {
                targets.add(songs.get(position));
            }
        }
        return targets;
    }

    /** 选中的位置（映射到 allSongs 的下标：自定义排序时两者一致）。 */
    private Integer[] selectedIndices(List<Integer> positions) {
        List<Integer> idx = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < songs.size()) {
                idx.add(allSongs.indexOf(songs.get(position)));
            }
        }
        return idx.toArray(new Integer[0]);
    }

    private boolean containsPath(List<Song> list, String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        for (Song s : list) {
            if (path.equals(s.getUrl())) {
                return true;
            }
        }
        return false;
    }

    // ==================== 更多（搜索 / 排序） ====================

    private void showMoreDialog() {
        final String[] items = {
                "搜索歌曲",
                "排序：自定义",
                "排序：按曲名",
                "排序：按曲师",
                "排序：按专辑",
                "A-Z 跳转",
        };
        // 统一菜单弹层：标题单行固定 + 列表独立滚动，长标题不再与列表重叠；当前排序项高亮
        com.qinghe.music163pro.util.WatchMenuDialog.show(this,
                "歌单操作（当前排序：" + sortModeLabel() + "）", items,
                1 + Math.max(0, Math.min(3, sortMode)), which -> {
                    switch (which) {
                        case 0:
                            showSearchDialog();
                            break;
                        case 1:
                            setSortMode(SORT_CUSTOM);
                            break;
                        case 2:
                            setSortMode(SORT_NAME);
                            break;
                        case 3:
                            setSortMode(SORT_ARTIST);
                            break;
                        case 4:
                            setSortMode(SORT_ALBUM);
                            break;
                        case 5:
                            openLetterPicker();
                            break;
                        default:
                            break;
                    }
                });
    }

    private void setSortMode(int mode) {
        sortMode = mode;
        applySearch(searchQuery);
        Toast.makeText(this, "已按「" + sortModeLabel() + "」排列", Toast.LENGTH_SHORT).show();
    }

    // ==================== A-Z 首字母跳转（复用 LetterPickerActivity） ====================

    /**
     * 打开独立的字母选择页。分组按当前显示顺序构建（A-Z 跳转语义 = 按「曲名」分组），
     * 首字母用 {@link ScriptSort#groupLabel}：拉丁/汉字/假名/西里尔统一折算成拉丁字母，
     * 与本地音乐页、我的歌单页的合并字母口径一致。
     */
    private void openLetterPicker() {
        if (sortMode != SORT_NAME) {
            // 按曲名分组跳转只有在曲名排序下才有意义，自动切过去
            setSortMode(SORT_NAME);
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
        for (int i = 0; i < songs.size(); i++) {
            Song song = songs.get(i);
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
        // 先记下来再立即跳：万一后续 applySearch 重建列表，位置也不会丢
        pendingScrollTo = Math.max(0, Math.min(target, songs.size() - 1));
        lvSongs.setSelection(pendingScrollTo);
    }

    private void showSearchDialog() {
        // P5：统一浮窗（输入栏带 × 清空）
        com.qinghe.music163pro.util.WatchPopup.input(this, "搜索歌单内歌曲", null,
                "曲名 / 曲师 / 专辑", searchQuery, "确定", text -> applySearch(text));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }
}
