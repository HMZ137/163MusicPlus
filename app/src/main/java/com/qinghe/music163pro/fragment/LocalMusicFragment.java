package com.qinghe.music163pro.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.LanTransferActivity;
import com.qinghe.music163pro.activity.LetterPickerActivity;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.BatchFavoriteHelper;
import com.qinghe.music163pro.util.LocalLibraryCache;
import com.qinghe.music163pro.util.LocalMetadataIndex;
import com.qinghe.music163pro.util.LocalMusicHides;
import com.qinghe.music163pro.util.LocalMusicLoader;
import com.qinghe.music163pro.util.LocalPlaylistManager;
import com.qinghe.music163pro.util.MultiSelectController;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.PinyinUtils;
import com.qinghe.music163pro.util.PlaybackMode;
import com.qinghe.music163pro.util.ScriptSort;
import com.qinghe.music163pro.util.SongQuery;
import com.qinghe.music163pro.util.WatchConfirmDialog;
import com.qinghe.music163pro.util.WatchMenuDialog;
import com.qinghe.music163pro.util.WatchUiUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地音乐：把卡里已有的音频（用户自己拷进来的，或别的来源的）扫描出来单独成列表。
 *
 * <p><b>4-3 步骤③-2：两条入口共用这一份实现。</b>能力差异只由 {@link #setAdvanced(boolean)} 决定：</p>
 * <ul>
 *   <li>{@code false}（下载页「本地」标签，默认）：轻量浏览 —— 列表 / 点击播放 / 状态行 / 空态；</li>
 *   <li>{@code true}（More→本机音乐完整页）：在上面基础上启用搜索、排序、A-Z 字母索引、
 *       长按多选（收藏 / 加入歌单 / 移出列表）、局域网传歌入口；状态行变成可点按的筛选行。</li>
 * </ul>
 *
 * <p><b>数据侧</b>完全复用 4-3 步骤① 抽出的 {@link LocalMusicLoader}（两阶段加载、索引回写、
 * 隐藏名单跳过、下载子树排除都在里面），本类只做视图与交互；扫描日志格式与改造前逐字一致：
 * {@code 本地扫描耗时: 缓存首屏=Xms 走目录=Yms 共N首 需读标签=M首 收尾=Zms}。</p>
 *
 * <p><b>为什么要这个页面：</b>下载列表的删除会真的删掉歌曲目录，而这里扫到的都是用户自己的文件，
 * 混在一起太容易误删。所以单独一个列表，并且只提供批量收藏 / 移出列表（只写隐藏名单，不删文件）。
 * 扫描范围刻意限定（见 {@code LocalScanScope}），不做全盘遍历（手表上又慢又费电）。</p>
 */
public class LocalMusicFragment extends Fragment {

    /** 宿主回调：本类不直接 finish 宿主，交给宿主决定收起自己还是跳回主界面。 */
    public interface Host {
        /** 用户点了一首本地曲目（播放队列已设好并开始播放）。 */
        void onLocalSongPlayed();
    }

    /** 打开字母选择页的请求码。 */
    private static final int REQUEST_LETTER_PICKER = 0x51;

    /** 排序方式：曲名（默认）/ 曲师 / 专辑。选择会记住。 */
    private static final int SORT_NAME = 0;
    private static final int SORT_ARTIST = 1;
    private static final int SORT_ALBUM = 2;
    private static final String PREFS = "music163_settings";
    private static final String PREF_SORT_MODE = "local_sort_mode";

    /** 当前视图列表（搜索过滤后的结果；无搜索时等于 allSongs）。 */
    private final List<Song> localSongs = new ArrayList<>();
    /** 扫描出来的**全量**列表：搜索 / 清除搜索都以它为基准。 */
    private final List<Song> allSongs = new ArrayList<>();
    /**
     * 分组标题（一个字符一组）与每组的首曲下标 / 曲目数，三份等长。
     * 直接以并行数组形式持有，因为要原样塞进 Intent 传给字母选择页。
     */
    private final List<Character> groupLetters = new ArrayList<>();
    private int[] groupStarts = new int[0];
    private int[] groupCounts = new int[0];
    /** 「从列表移除」的隐藏路径（只过滤列表展示，不删卡里的文件）。 */
    private final Set<String> hiddenPaths = new LinkedHashSet<>();

    private ListView listView;
    private TextView statusView;
    private TextView emptyView;
    private ArrayAdapter<Song> adapter;
    private MultiSelectController multiSelect;

    /** 当前搜索关键词；空串表示未搜索。 */
    private String searchQuery = "";
    /** 扫描 / 读标签阶段的状态文案（放状态行，不挤标题）。扫描结束置回 null。 */
    private String scanStatus = null;
    private boolean scanning;
    /**
     * 扫描批次号。重新扫描时自增，用来丢弃上一批还没跑完的元信息补齐 ——
     * 否则旧批次的回调会往新列表里写数据，出现「歌名对不上歌」。
     */
    private int scanGeneration;

    /** 高级能力开关（4-3 步骤③-1 落地，默认 false，含义见类注释）。 */
    private boolean advanced = false;
    private boolean viewDestroyed = false;

    private int sortMode = SORT_NAME;

    // ==================== 生命周期 ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_local_music_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewDestroyed = false;
        listView = view.findViewById(R.id.lv_local_music);
        statusView = view.findViewById(R.id.tv_status_row);
        emptyView = view.findViewById(R.id.tv_local_music_empty);
        if (statusView != null) {
            statusView.setOnClickListener(v -> onStatusRowClick());
        }
        // 记住上次选的排序方式（曲名 / 曲师 / 专辑）
        sortMode = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(PREF_SORT_MODE, SORT_NAME);

        adapter = new ArrayAdapter<Song>(requireContext(), R.layout.item_song,
                R.id.tv_item_name, localSongs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = row.findViewById(R.id.tv_item_name);
                    TextView tvArtist = row.findViewById(R.id.tv_item_artist);
                    ImageView ivCover = row.findViewById(R.id.iv_cover);
                    tvName.setText(song.getDisplayName());
                    // 元信息里可能根本没有歌手 —— 兜底会给出空串，这里再防一次 null
                    tvArtist.setText(song.getArtist() == null ? "" : song.getArtist());
                    if (ivCover != null) {
                        // 本地封面走 file:// ，NetworkImageLoader 已经支持这条分支
                        NetworkImageLoader.load(ivCover, song.getCoverUrl());
                    }
                }
                return row;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, v, position, id) -> playFrom(position));

        applyMode();
        updateEmptyState();
        startScan();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (multiSelect != null) {
            multiSelect.exitSelectionMode();
        }
    }

    @Override
    public void onDestroyView() {
        viewDestroyed = true;
        scanGeneration++;   // 让仍在跑的加载器立刻作废（避免它往已销毁的视图上回调）
        multiSelect = null;
        listView = null;
        statusView = null;
        emptyView = null;
        adapter = null;
        super.onDestroyView();
    }

    // ==================== 4-3 步骤③：高级能力开关 ====================

    /**
     * 设置高级能力开关（默认 {@code false}，含义见类注释）。
     *
     * <p>可以在 {@code attach} 之前调用（宿主在事务里 add 之前设置），也可以在视图就绪后调用
     * （会立刻重新应用一遍）。下载页「本地」标签不设置 —— 保持轻量。</p>
     */
    public void setAdvanced(boolean advanced) {
        this.advanced = advanced;
        applyMode();
    }

    /** 当前是否为高级模式（完整页为 {@code true}，下载页「本地」标签为 {@code false}）。 */
    public boolean isAdvanced() {
        return advanced;
    }

    /** 多选模式的返回键拦截；宿主应把它注册进 {@code BaseWatchActivity.addBackInterceptor}。 */
    public boolean handleBackPressed() {
        return multiSelect != null && multiSelect.handleBackPressed();
    }

    /**
     * 把当前模式应用到视图上。视图尚未创建时直接返回 —— {@link #onViewCreated} 会再调一次。
     *
     * <p>高级模式启用：长按多选（收藏 / 加入歌单 / 移出列表）与可点按的状态行；
     * 轻量模式两者都不启用（状态行只是纯文本，长按无动作）。</p>
     */
    private void applyMode() {
        if (listView == null) {
            return;
        }
        if (statusView != null) {
            statusView.setClickable(advanced);
            statusView.setFocusable(advanced);
        }
        if (advanced) {
            if (multiSelect == null) {
                multiSelect = MultiSelectController.attach(requireActivity(), listView,
                        new MultiSelectController.Callback() {
                            @Override
                            public List<MultiSelectController.Action> getActions() {
                                List<MultiSelectController.Action> actions = new ArrayList<>();
                                actions.add(MultiSelectController.Action.favorite(requireActivity()));
                                // 「加入歌单」：把选中的本地歌曲归类进某个本地歌单（本地收藏模式才有意义）
                                actions.add(new MultiSelectController.Action(
                                        "add_to_local_playlist",
                                        getString(R.string.add_to_local_playlist),
                                        getResources().getColor(R.color.colorPrimary)));
                                // 「移出列表」：只从这个列表里去掉，**不删**卡里的文件（见 LocalMusicHides）
                                actions.add(new MultiSelectController.Action(
                                        "hide_from_list",
                                        "移出列表",
                                        getResources().getColor(R.color.colorError)));
                                return actions;
                            }

                            @Override
                            public void onAction(MultiSelectController.Action action,
                                                 List<Integer> positions) {
                                if (MultiSelectController.Action.ID_FAVORITE.equals(action.id)) {
                                    favoriteSelected(positions);
                                } else if ("add_to_local_playlist".equals(action.id)) {
                                    addSelectedToPlaylist(positions);
                                } else if ("hide_from_list".equals(action.id)) {
                                    hideSelectedFromList(positions);
                                }
                            }
                        });
            }
        } else if (multiSelect != null) {
            multiSelect.exitSelectionMode();
            multiSelect.setEnabled(false);
        }
    }

    // ==================== 扫描（数据侧在 LocalMusicLoader） ====================

    private boolean isStale(int generation) {
        return viewDestroyed || !isAdded() || generation != scanGeneration || listView == null;
    }

    /** 发起一轮扫描；真正的两阶段加载在 {@link LocalMusicLoader} 里。 */
    private void startScan() {
        if (scanning) {
            return;
        }
        scanning = true;
        final int generation = ++scanGeneration;
        scanStatus = "扫描中…";
        refreshStatus();
        updateEmptyState();
        final LocalMusicLoader loader = new LocalMusicLoader(requireContext(), generation,
                new LocalMusicLoader.Callback() {
                    @Override
                    public void onFirstPaint(List<Song> cachedSongs) {
                        if (isStale(generation)) {
                            return;
                        }
                        allSongs.clear();
                        allSongs.addAll(cachedSongs);
                        // 完整页与改造前一致地显示「已显示缓存 · 校验中…」；
                        // 标签页只显示首数（它没有搜索/多选，状态行保持一行纯文本）。
                        scanStatus = advanced ? "已显示缓存 · 校验中…" : null;
                        refreshStatus();
                        // 不排序：缓存顺序即上次排好的，等走完目录自然会排一次
                        applySearch(searchQuery, false);
                    }

                    @Override
                    public void onWalkDone(List<Song> songs, int misses) {
                        if (isStale(generation)) {
                            return;
                        }
                        allSongs.clear();
                        allSongs.addAll(songs);
                        // 扫描结果写进缓存：下次启动不必等扫描完就能解析本地歌路径
                        LocalLibraryCache.putAll(songs);
                        if (advanced && !songs.isEmpty() && misses > 0) {
                            scanStatus = "读取歌曲信息中… 0/" + songs.size();
                        } else if (!advanced) {
                            scanStatus = null;
                        }
                        applySearch(searchQuery);
                    }

                    @Override
                    public void onMetadataProgress(int processed, int total, boolean readingFromDisk) {
                        if (isStale(generation)) {
                            return;
                        }
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                        if (total > 0 && readingFromDisk) {
                            scanStatus = "读取歌曲信息中… " + processed + "/" + total;
                            refreshStatus();
                        }
                    }

                    @Override
                    public void onFinished(boolean changed) {
                        if (isStale(generation)) {
                            return;
                        }
                        finalizeScan(generation, changed);
                    }

                    @Override
                    public boolean isCancelled() {
                        return isStale(generation);
                    }

                    @Override
                    public void onSkippedDirs(final List<String> skipped) {
                        if (isStale(generation)) {
                            return;
                        }
                        final Context context = getContext();
                        if (context == null) {
                            return;
                        }
                        final String message = "已跳过 " + skipped.size() + " 个不可用的自定义扫描目录";
                        requireActivity().runOnUiThread(() -> Toast.makeText(context, message,
                                Toast.LENGTH_LONG).show());
                    }
                });
        new Thread(loader::runBlocking, "local-scan").start();
    }

    /**
     * 扫描/读标签收尾：曲名换成元信息里的之后字母归属可能变了，重排一次；
     * 然后关掉加载态。封面缓存压缩已在 loader 收尾时后台执行。
     */
    private void finalizeScan(int generation, boolean changed) {
        if (isStale(generation)) {
            return;
        }
        if (changed) {
            sortAndIndex();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
        scanning = false;
        updateEmptyState();
    }

    // ==================== 排序与首字母索引 ====================

    /** 当前排序方式下，用哪个字段做首字母与字典序比较。 */
    private String sortKey(Song song) {
        if (song == null) {
            return "";
        }
        switch (sortMode) {
            case SORT_ARTIST:
                return song.getArtist() == null ? "" : song.getArtist();
            case SORT_ALBUM:
                return song.getAlbum() == null ? "" : song.getAlbum();
            case SORT_NAME:
            default:
                return song.getName() == null ? "" : song.getName();
        }
    }

    /** 当前排序方式的中文名（对话框标题 / 提示用）。 */
    private String sortModeLabel() {
        switch (sortMode) {
            case SORT_ARTIST:
                return "曲师";
            case SORT_ALBUM:
                return "专辑";
            default:
                return "曲名";
        }
    }

    /**
     * 按当前排序方式（曲名 / 曲师 / 专辑）排序，并建立「首字母 → 第一首下标」的分组。
     *
     * <p>先比所选字段的首字母（{@link PinyinUtils}，中/西文都能取到），同字母内再用中文排序规则
     * 比整个字段；仍相同就按曲名排 —— 这样顺序稳定，字母索引也与排序口径一致。</p>
     */
    private void sortAndIndex() {
        // 排序口径见 ScriptSort：先按文字分段（拉丁→汉字→假名→西里尔→#），段内各用自己的序。
        Collections.sort(localSongs, (left, right) -> {
            int byScript = ScriptSort.TEXT_ORDER.compare(sortKey(left), sortKey(right));
            if (byScript != 0) {
                return byScript;
            }
            // 同段同字面：再按曲名兜底，保证顺序稳定
            String leftName = left.getName() == null ? "" : left.getName();
            String rightName = right.getName() == null ? "" : right.getName();
            return ScriptSort.TEXT_ORDER.compare(leftName, rightName);
        });
        buildLetterGroups();
    }

    /** 按排好序的列表生成分组信息，供字母选择页使用。 */
    private void buildLetterGroups() {
        // 分组键带「文字」前缀：拉丁的 A 与汉字的 A 是两组（排序也是先分文字段，否则索引会错位）
        Map<String, Integer> firstIndex = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Character> labels = new LinkedHashMap<>();
        for (int i = 0; i < localSongs.size(); i++) {
            String sk = sortKey(localSongs.get(i));
            // 以「显示标签」为分组单位：拉丁 A 与汉字 A 合并成一个 A
            String key = String.valueOf(ScriptSort.groupLabel(sk).charAt(0));
            if (!firstIndex.containsKey(key)) {
                firstIndex.put(key, i);
                labels.put(key, ScriptSort.groupLabel(sk).charAt(0));
            }
            Integer count = counts.get(key);
            counts.put(key, count == null ? 1 : count + 1);
        }
        groupLetters.clear();
        groupStarts = new int[firstIndex.size()];
        groupCounts = new int[firstIndex.size()];
        // 索引页按字母表顺序展示；跳转仍指向该字母在当前列表里的第一首
        List<String> keys = new ArrayList<>(firstIndex.keySet());
        Collections.sort(keys);
        int index = 0;
        for (String key : keys) {
            Character label = labels.get(key);
            groupLetters.add(label == null ? PinyinUtils.OTHER : label);
            groupStarts[index] = firstIndex.get(key);
            Integer count = counts.get(key);
            groupCounts[index] = count == null ? 0 : count;
            index++;
        }
    }

    /**
     * 打开独立的字母选择页（原来那条竖排窄索引条在 320px 表盘上点不准，
     * 换成独立一屏的大格子后触控面积翻了 5 倍以上）。
     */
    private void openLetterPicker() {
        if (groupLetters.isEmpty()) {
            toast("还没有可跳转的字母");
            return;
        }
        StringBuilder letters = new StringBuilder(groupLetters.size());
        for (Character letter : groupLetters) {
            letters.append(letter.charValue());
        }
        Intent intent = new Intent(requireContext(), LetterPickerActivity.class);
        intent.putExtra(LetterPickerActivity.EXTRA_LETTERS, letters.toString());
        intent.putExtra(LetterPickerActivity.EXTRA_STARTS, groupStarts);
        intent.putExtra(LetterPickerActivity.EXTRA_COUNTS, groupCounts);
        // 把当前停留位置传过去，让选择页把所在字母高亮出来
        intent.putExtra(LetterPickerActivity.EXTRA_CURRENT,
                listView == null ? 0 : listView.getFirstVisiblePosition());
        startActivityForResult(intent, REQUEST_LETTER_PICKER);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LETTER_PICKER || resultCode != android.app.Activity.RESULT_OK
                || data == null || listView == null) {
            return;
        }
        int target = data.getIntExtra(LetterPickerActivity.EXTRA_RESULT_INDEX, -1);
        if (target < 0) {
            return;
        }
        // setSelection 会把该行滚到顶部，正好是「跳到这个字母的位置」
        listView.setSelection(Math.max(0, Math.min(target, localSongs.size() - 1)));
    }

    // ==================== 标题栏动作（由宿主 Activity 的按钮转调） ====================

    /** 搜索本地音乐：按曲名 / 曲师 / 专辑 / 文件名过滤当前列表。 */
    public void showSearchDialog() {
        if (scanning) {
            toast("正在扫描，稍候再搜");
            return;
        }
        final Context context = requireContext();
        final EditText input = new EditText(context);
        input.setHint("曲名 / 曲师 / 专辑");
        input.setText(searchQuery);
        input.setSingleLine(true);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);
        // 输入框吃掉剩余宽度，右侧留给「×」
        input.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 输入框右侧的小「×」：只清空输入框内容。
        // （要清掉已经生效的搜索，把输入框清空后点「確定」即可 —— 空关键词等价于显示全部）
        TextView btnClearInput = new TextView(context);
        btnClearInput.setText("✕");
        btnClearInput.setTextColor(0xFFAAAAAA);
        btnClearInput.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
        btnClearInput.setPadding(px(10), px(4), px(2), px(4));
        btnClearInput.setClickable(true);
        btnClearInput.setFocusable(true);
        btnClearInput.setOnClickListener(v -> input.setText(""));

        LinearLayout fieldRow = new LinearLayout(context);
        fieldRow.setOrientation(LinearLayout.HORIZONTAL);
        fieldRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        fieldRow.addView(input);
        fieldRow.addView(btnClearInput);

        LinearLayout container = new LinearLayout(context);
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(fieldRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(context)
                .setTitle("搜索本地音乐")
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        applySearch(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 选择排序方式：曲名 / 曲师 / 专辑（会记住）。 */
    public void showSortDialog() {
        final String[] options = {"按曲名", "按曲师", "按专辑"};
        WatchMenuDialog.show(requireContext(), "排序方式（当前：" + sortModeLabel() + "）", options,
                which -> {
                    if (which == sortMode) {
                        return;
                    }
                    sortMode = which;
                    requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putInt(PREF_SORT_MODE, sortMode).apply();
                    sortAndIndex();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    toast("已按「" + sortModeLabel() + "」排序");
                });
    }

    /**
     * 「更多」二级菜单：本页共五个操作，标题栏放不下，统一收在这里。
     *
     * <p>另有第六条「刷新本地缓存」：缓存按文件的 size/mtime 自动失效，正常用不着手动刷；
     * 这条是给「改了标签但文件没变」这类自动判定覆盖不到的情况留的后路。</p>
     */
    public void showMoreDialog() {
        final List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.search));
        labels.add(getString(R.string.local_music_sort) + "（当前：" + sortModeLabel() + "）");
        labels.add(getString(R.string.local_music_letters) + " 字母索引");
        labels.add(getString(R.string.local_music_shuffle) + "播放");
        // 纯在线模式下不提供「局域网传歌」（把歌传进卡里属于本地场景）
        final boolean withLanTransfer = !PlaybackMode.isLocalEntryHidden(requireContext());
        if (withLanTransfer) {
            labels.add("局域网传歌");
        }
        labels.add("刷新本地缓存（" + LocalMetadataIndex.size() + " 首已缓存）");
        final int idxRefresh = labels.size() - 1;
        final String[] items = labels.toArray(new String[0]);
        WatchMenuDialog.show(requireContext(), getString(R.string.local_music), items, which -> {
            switch (which) {
                case 0:
                    showSearchDialog();
                    break;
                case 1:
                    showSortDialog();
                    break;
                case 2:
                    openLetterPicker();
                    break;
                case 3:
                    playShuffled();
                    break;
                case 4:
                    if (withLanTransfer) {
                        startActivity(new Intent(requireContext(), LanTransferActivity.class));
                    }
                    break;
                default:
                    if (which == idxRefresh) {
                        confirmRefreshCache();
                    }
                    break;
            }
        });
    }

    /** 「刷新本地缓存」的二次确认：全量重读标签是重活（本机 457 首实测约 21s），先说清代价。 */
    private void confirmRefreshCache() {
        final int cached = LocalMetadataIndex.size();
        WatchConfirmDialog.show(requireActivity(), "刷新本地缓存",
                cached > 0
                        ? "清掉已缓存的 " + cached + " 首，重新读取全部标签。文件多时约需 20 秒。"
                        : "重新读取全部本地文件的标签，约需 20 秒。",
                this::refreshLocalCache,
                WatchConfirmDialog.Options.defaultOptions());
    }

    /**
     * 清掉元信息索引并重新扫描。
     *
     * <p>不清封面缓存：封面文件是按路径存且在文件被改动时自动失效的，清掉只会让几百张封面
     * 被重新抽取一遍，白白再慢一轮。真要连封面一起重建，走「设置 → 清理缓存」。</p>
     *
     * <p>{@code scanGeneration++} 是为了让**正在跑**的那一轮扫描作废 —— 否则它会在读完
     * 旧数据后覆盖掉刚刷出来的结果。</p>
     */
    private void refreshLocalCache() {
        LocalMetadataIndex.clear(requireContext());
        scanGeneration++;
        scanning = false;
        allSongs.clear();
        localSongs.clear();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        scanStatus = null;
        refreshStatus();
        updateEmptyState();
        startScan();
        toast("正在重新读取本地歌曲信息…");
    }

    /** 随机播放全部本地音乐。 */
    private void playShuffled() {
        if (localSongs.isEmpty()) {
            toast("没有可播放的本地音乐");
            return;
        }
        List<Song> playlist = new ArrayList<>(localSongs);
        Collections.shuffle(playlist);
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        playerManager.setPlaylist(playlist, 0);
        // 顺手切成随机模式，这样后续切歌也保持随机
        playerManager.setPlayMode(MusicPlayerManager.PlayMode.RANDOM);
        playerManager.playCurrent();
        notifyHostSongPlayed();
    }

    // ==================== 交互 ====================

    /** 点击播放：整张当前本地列表作为播放队列，再从该位置开始。 */
    private void playFrom(int position) {
        if (position < 0 || position >= localSongs.size()) {
            return;
        }
        List<Song> playlist = new ArrayList<>(localSongs);
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        playerManager.setPlaylist(playlist, position);
        playerManager.playCurrent();
        notifyHostSongPlayed();
    }

    private void notifyHostSongPlayed() {
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).onLocalSongPlayed();
        } else if (getActivity() != null) {
            getActivity().finish();
        }
    }

    private void favoriteSelected(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < localSongs.size()) {
                targets.add(localSongs.get(position));
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        final android.app.Activity activity = requireActivity();
        toast(getString(R.string.multi_select_favoriting, targets.size()));
        BatchFavoriteHelper.favoriteAll(activity, targets, new BatchFavoriteHelper.Callback() {
            @Override
            public void onFinished(int added, int skipped, int failed, int total) {
                if (multiSelect != null) {
                    multiSelect.exitSelectionMode();
                }
                Toast.makeText(activity,
                        BatchFavoriteHelper.buildResultMessage(activity, added, failed),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNeedLogin() {
                Toast.makeText(activity, R.string.multi_select_favorite_need_login,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNeedLogin(int addedLocal) {
                if (addedLocal > 0) {
                    // 本地曲目已经按本地收藏处理好了，只是「云端那部分」没登录
                    if (multiSelect != null) {
                        multiSelect.exitSelectionMode();
                    }
                    Toast.makeText(activity,
                            "已加入本地收藏 " + addedLocal + " 首；云端收藏需先登录",
                            Toast.LENGTH_LONG).show();
                } else {
                    onNeedLogin();
                }
            }
        });
    }

    /**
     * 把选中的曲目从本地音乐列表里移除。
     *
     * <p><b>只写隐藏名单，绝不删卡里的文件</b> —— 本地音乐列表扫的是用户自己的音频，
     * 误删是不可挽回的。想恢复时走 {@link #showHiddenDialog()}。</p>
     */
    private void hideSelectedFromList(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < localSongs.size()) {
                Song song = localSongs.get(position);
                if (song != null && !TextUtils.isEmpty(song.getUrl())) {
                    targets.add(song);
                    paths.add(song.getUrl());
                }
            }
        }
        if (paths.isEmpty()) {
            return;
        }
        final Context context = requireContext();
        new AlertDialog.Builder(context)
                .setTitle("移出列表")
                .setMessage("把选中的 " + paths.size() + " 首从本地音乐列表移出？\n不会删除卡里的文件。")
                .setPositiveButton("移出", (d, w) -> {
                    int added = LocalMusicHides.hide(context, paths);
                    if (multiSelect != null) {
                        multiSelect.exitSelectionMode();
                    }
                    hiddenPaths.addAll(paths);
                    localSongs.removeAll(targets);
                    sortAndIndex();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    updateEmptyState();
                    refreshStatus();
                    toast(added > 0 ? "已移出 " + added + " 首（文件未删除）"
                            : "这些曲目已在隐藏名单里");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 状态 / 筛选行更新。
     *
     * <p>完整页（advanced）：扫描进度 / 搜索命中 / 已移出首数，按需拼成一行，无内容时整行隐藏；
     * 标签页：只显示扫描进度或「本地音乐 N 首」（与 4-3 步骤② 的表现一致）。</p>
     */
    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        if (!advanced) {
            statusView.setVisibility(View.VISIBLE);
            if (scanStatus != null && !scanStatus.isEmpty()) {
                statusView.setText(scanStatus);
            } else {
                statusView.setText("本地音乐 " + localSongs.size() + " 首");
            }
            return;
        }
        List<String> parts = new ArrayList<>();
        if (scanStatus != null && !scanStatus.isEmpty()) {
            parts.add(scanStatus);
        }
        if (!searchQuery.isEmpty()) {
            parts.add("搜索「" + searchQuery + "」命中 " + localSongs.size() + " 首（点按清除）");
        }
        int hidden = LocalMusicHides.getHidden(requireContext()).size();
        if (hidden > 0) {
            parts.add("已移出 " + hidden + " 首（点按恢复）");
        }
        if (parts.isEmpty()) {
            statusView.setVisibility(View.GONE);
            return;
        }
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(TextUtils.join(" · ", parts));
    }

    /** 状态行点按：搜索中先清搜索，否则打开「已移出」恢复界面。 */
    private void onStatusRowClick() {
        if (!advanced) {
            return;
        }
        if (!searchQuery.isEmpty()) {
            applySearch("");
            return;
        }
        showHiddenDialog();
    }

    /**
     * 应用搜索关键词：从全量列表 {@link #allSongs} 过滤出当前列表 {@link #localSongs} 并重排。
     * 传空串等于清除搜索、恢复全量。
     */
    private void applySearch(String query) {
        applySearch(query, true);
    }

    /**
     * @param sort 是否重排。排序在主线程，而它比的是逐字 GBK 拼音（ScriptSort），
     *             几百首就是几千次字符级转写 —— 本机实测一趟要数秒。缓存首屏那一趟刻意传 false。
     */
    private void applySearch(String query, boolean sort) {
        searchQuery = query == null ? "" : query;
        localSongs.clear();
        if (searchQuery.isEmpty()) {
            localSongs.addAll(allSongs);
        } else {
            final String needle = SongQuery.normalize(searchQuery);
            for (Song song : allSongs) {
                if (SongQuery.matches(song, needle)) {
                    localSongs.add(song);
                }
            }
        }
        if (sort) {
            sortAndIndex();
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmptyState();
        refreshStatus();
    }

    /**
     * 恢复界面：列出被移出的曲目逐条恢复，另给一个「全部恢复」。
     *
     * <p>列表项必须自己建（不能用 {@code setItems}）：默认的对话框列表项允许多行，
     * 本地音乐的文件名又长，一条会被折成两三行、把对话框顶得很高。这里统一单行 + 中间省略。</p>
     */
    private void showHiddenDialog() {
        final Context context = requireContext();
        final Set<String> hidden = LocalMusicHides.getHidden(context);
        if (hidden.isEmpty()) {
            return;
        }
        final List<String> paths = new ArrayList<>(hidden);
        final List<String> names = LocalMusicHides.displayNames(paths);

        ListView list = new ListView(context);
        list.setDivider(null);
        list.setAdapter(new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return names.size();
            }

            @Override
            public Object getItem(int position) {
                return names.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = convertView instanceof TextView ? (TextView) convertView : null;
                if (tv == null) {
                    tv = new TextView(context);
                    tv.setTextColor(getResources().getColor(R.color.text_primary));
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
                    tv.setSingleLine(true);
                    tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    tv.setPadding(px(18), px(12), px(18), px(12));
                    tv.setBackgroundResource(android.R.drawable.list_selector_background);
                }
                tv.setText(names.get(position));
                return tv;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            LocalMusicHides.unhide(context, paths.get(position));
            refreshStatus();
            rescanAfterHideChange();
        });

        new AlertDialog.Builder(context)
                .setTitle("已移出的曲目")
                .setView(list)
                .setPositiveButton("全部恢复", (d, w) -> {
                    LocalMusicHides.unhideAll(context);
                    refreshStatus();
                    rescanAfterHideChange();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 隐藏名单变了以后重新扫一遍，让列表立刻反映出来。 */
    private void rescanAfterHideChange() {
        scanGeneration++;
        scanning = false;
        localSongs.clear();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        startScan();
    }

    /** 把多选的本地歌曲加入某个本地歌单（或新建歌单）。 */
    private void addSelectedToPlaylist(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < localSongs.size()) {
                targets.add(localSongs.get(position));
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        List<String> names = LocalPlaylistManager.listPlaylistNames();
        final List<String> choices = new ArrayList<>(names);
        choices.add("＋ " + getString(R.string.new_playlist));
        final String[] items = choices.toArray(new String[0]);
        WatchMenuDialog.show(requireContext(), getString(R.string.add_to_local_playlist), items,
                which -> {
                    if (which == choices.size() - 1) {
                        showNewPlaylistDialog(targets);
                        return;
                    }
                    String name = names.get(which);
                    int added = LocalPlaylistManager.addSongs(name, targets);
                    toast(added > 0 ? getString(R.string.added_to_playlist, added)
                            : getString(R.string.already_in_playlist));
                    if (multiSelect != null) {
                        multiSelect.exitSelectionMode();
                    }
                });
    }

    /** 多选「加入歌单」时选择「新建歌单」的弹窗。 */
    private void showNewPlaylistDialog(final List<Song> targets) {
        final Context context = requireContext();
        EditText input = new EditText(context);
        input.setHint(R.string.playlist_name_hint);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);

        LinearLayout container = new LinearLayout(context);
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(context)
                .setTitle(R.string.create_local_playlist)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast(getString(R.string.playlist_name_empty));
                        return;
                    }
                    int added;
                    if (LocalPlaylistManager.playlistExists(name)) {
                        added = LocalPlaylistManager.addSongs(name, targets);
                    } else if (LocalPlaylistManager.createPlaylist(name)) {
                        added = LocalPlaylistManager.addSongs(name, targets);
                    } else {
                        toast(getString(R.string.create_failed));
                        return;
                    }
                    toast(getString(R.string.created_and_added, added));
                    if (multiSelect != null) {
                        multiSelect.exitSelectionMode();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 列表 / 空态可见性与空态文案。 */
    private void updateEmptyState() {
        if (listView == null || emptyView == null) {
            return;
        }
        boolean empty = localSongs.isEmpty();
        if (scanning) {
            // 加载中：保留当前阶段状态文案（扫描中… / 读取歌曲信息中…），不要覆盖；
            // 只管列表 / 空态的可见性。
            emptyView.setVisibility(View.GONE);
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
            refreshStatus();
            return;
        }
        // 扫描结束：收起进度状态
        scanStatus = null;
        refreshStatus();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            if (advanced) {
                emptyView.setText(searchQuery.isEmpty() ? "未找到本地音乐" : "没有匹配的曲目");
            } else {
                emptyView.setText(R.string.local_music_empty);
            }
        }
    }

    // ==================== 小工具 ====================

    private int px(int baseValue) {
        return WatchUiUtils.px(requireContext(), baseValue);
    }

    private void toast(CharSequence text) {
        Context context = getContext();
        if (context != null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        }
    }
}
