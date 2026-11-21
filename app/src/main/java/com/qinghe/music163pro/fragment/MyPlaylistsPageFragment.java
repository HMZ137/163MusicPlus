package com.qinghe.music163pro.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.LetterPickerActivity;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.PinyinUtils;
import com.qinghe.music163pro.util.WatchUiUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 4-16：我的歌单两个页面（在线歌单 / 本地歌单）的公共基类。
 *
 * <p>改造前这一页是 1173 行的自绘 Activity，两套列表逻辑（在线/本地）共用一个 ListView 和
 * 一个 displayList，靠 {@code activeTab} 切换，标题栏里还塞了「A-Z」和「+」两个文字按钮。
 * 迁到 ViewPager2 后：两页各一份 Fragment（各自持有自己的列表与加载逻辑），
 * 公共部分（列表、状态行、空态、A-Z 字母索引、排行入口行、信息行回写）收到本基类。</p>
 *
 * <p><b>加载时机</b>：ViewPager2 只把当前页置为 RESUMED，所以 {@link #onResume()} 里加载天然
 * 覆盖「首次进入 / 滑到本页 / 从详情页返回」；纯本地 / 纯在线模式下容器只挂一页，行为与改造前一致。</p>
 *
 * <p><b>「听歌排行」入口行</b>：两页首个条目都是伪歌单（固定 id {@code -1001}），不参与排序与
 * A-Z 分组，点击分别进服务端排行 / 本机播放统计。</p>
 */
public abstract class MyPlaylistsPageFragment extends Fragment {

    /** 宿主（MyPlaylistsActivity）回写「当前页 · 歌单数」信息行。 */
    public interface Host {
        void onMyPlaylistsPageSummary(int page, CharSequence summary);
    }

    public static final int PAGE_ONLINE = 0;
    public static final int PAGE_LOCAL = 1;
    /** 「听歌排行」伪歌单的固定 id（两页各自的首个条目）。 */
    protected static final long RANK_ENTRY_ID = -1001L;
    private static final int REQUEST_LETTER_PICKER = 0x52;

    protected final List<PlaylistInfo> items = new ArrayList<>();
    protected ListView listView;
    protected TextView tvStatus;
    private ArrayAdapter<PlaylistInfo> adapter;

    private final List<Character> groupLetters = new ArrayList<>();
    private int[] groupStarts = new int[0];
    private int[] groupCounts = new int[0];
    private int pendingScrollTo = -1;

    // ── 子类契约 ────────────────────────────────────────────────────────────

    /** 页序号（{@link #PAGE_ONLINE} / {@link #PAGE_LOCAL}）。 */
    public abstract int pageIndex();

    /** 信息行里的页名（在线歌单 / 本地歌单）。 */
    protected abstract CharSequence pageName();

    /** 加载本页数据；拿到后调用 {@link #applyItems(List)} 并 {@link #setStatus}。 */
    protected abstract void loadData();

    /** 非排行行的行内绑定。 */
    protected abstract void bindRow(View row, PlaylistInfo pl);

    /** 排行入口行的数据（两页的曲目数与标题不同）。 */
    protected abstract PlaylistInfo createRankEntry();

    /** 排行入口行点击：在线 → 服务端排行；本地 → 本机播放统计。 */
    protected abstract void openRankPage();

    /** 行点击（非排行行）。 */
    protected abstract void onRowClicked(PlaylistInfo pl);

    /** 行长按（非排行行）；返回 true 表示已处理。 */
    protected boolean onRowLongClicked(PlaylistInfo pl) {
        return false;
    }

    /** 顶栏 ☰ 的「新建歌单」：按本页语义创建（在线建云歌单 / 本地建本地歌单）。 */
    public abstract void showCreatePlaylistDialog();

    /** 「导入歌单」：已按需求方口径并入顶栏 ☰ 菜单，由宿主按当前页语义转发（只有本地页实现）。 */
    public void importPlaylists() {
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_my_playlists, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listView = view.findViewById(R.id.lv_my_playlists);
        tvStatus = view.findViewById(R.id.tv_my_playlists_status);

        adapter = new ArrayAdapter<PlaylistInfo>(requireContext(),
                R.layout.item_my_playlist, R.id.tv_name, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = convertView;
                if (row == null) {
                    row = getLayoutInflater().inflate(R.layout.item_my_playlist, parent, false);
                }
                PlaylistInfo pl = getItem(position);
                if (pl == null) {
                    return row;
                }
                if (isRankEntry(pl)) {
                    bindRankRow(row, pl);
                } else {
                    bindRow(row, pl);
                }
                return row;
            }
        };
        listView.setAdapter(adapter);
        NetworkImageLoader.attachListViewOptimization(listView, adapter, position ->
                position >= 0 && position < items.size() ? items.get(position).getCoverUrl() : null);
        listView.setOnItemClickListener((parent, v, position, id) -> {
            if (position < 0 || position >= items.size()) {
                return;
            }
            PlaylistInfo pl = items.get(position);
            if (isRankEntry(pl)) {
                openRankPage();
            } else {
                onRowClicked(pl);
            }
        });
        listView.setOnItemLongClickListener((parent, v, position, id) -> {
            if (position < 0 || position >= items.size()) {
                return false;
            }
            PlaylistInfo pl = items.get(position);
            if (isRankEntry(pl)) {
                return true;   // 入口行无长按菜单
            }
            return onRowLongClicked(pl);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // ViewPager2 只把当前页置为 RESUMED ⇒ 首次进入 / 滑到本页 / 从详情页返回都走这里。
        loadData();
        refreshRankEntry();
    }

    // ── 数据落地 ────────────────────────────────────────────────────────────

    /** 应用一页数据：清空 → 加排行入口 → 按拼音排序 + 建字母分组 → 刷新 → 回写信息行。 */
    protected final void applyItems(List<PlaylistInfo> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        insertRankEntry();
        sortAndIndex();
        notifyItemsChanged();
        scrollPending();
        reportSummary();
    }

    /** 状态行文案 + 列表显隐（空列表时只显示状态行，与改造前一致）。 */
    protected final void setStatus(CharSequence text, boolean showList) {
        if (tvStatus != null) {
            tvStatus.setText(text);
        }
        if (listView != null) {
            listView.setVisibility(showList ? View.VISIBLE : View.GONE);
        }
    }

    protected final void notifyItemsChanged() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    protected final boolean isViewReady() {
        return isAdded() && listView != null;
    }

    /** 真实歌单数（排除排行入口行）。 */
    protected final int realItemCount() {
        if (items.isEmpty()) {
            return 0;
        }
        return items.size() - (isRankEntry(items.get(0)) ? 1 : 0);
    }

    /** 从详情页 / 排行页返回后刷新入口行的副标题。 */
    protected final void refreshRankEntry() {
        if (!items.isEmpty() && isRankEntry(items.get(0))) {
            items.set(0, createRankEntry());
            notifyItemsChanged();
        }
    }

    /**
     * 把当前列表的加载结果同步到状态行。
     *
     * <p><b>数量不写在这里</b>：标题下的独立信息行已经显示「页名 · N 个」
     * （{@link Host#onMyPlaylistsPageSummary} 回写），状态行再写一遍「N 个歌单」就是重复
     * —— 需求方 2026-10-17 指出本地歌单页有两个数量指示。现在状态行只负责「空列表提示 /
     * 未登录 / 加载失败」这类信息，列表非空时整行隐藏。</p>
     */
    protected final void updateCountStatus(CharSequence emptyText) {
        if (realItemCount() == 0) {
            if (tvStatus != null) {
                tvStatus.setVisibility(View.VISIBLE);
            }
            setStatus(emptyText, false);
        } else {
            if (tvStatus != null) {
                tvStatus.setVisibility(View.GONE);
            }
            setStatus("", true);
        }
    }

    // ── 排行入口 ────────────────────────────────────────────────────────────

    protected final boolean isRankEntry(PlaylistInfo pl) {
        return pl != null && pl.getId() == RANK_ENTRY_ID;
    }

    private void insertRankEntry() {
        PlaylistInfo entry = createRankEntry();
        if (!items.isEmpty() && isRankEntry(items.get(0))) {
            items.set(0, entry);
        } else {
            items.add(0, entry);
        }
    }

    /** 排行入口行：与普通歌单同样式（音符图标 + 主色），文案为「N 首 · 昵称」。 */
    private void bindRankRow(View row, PlaylistInfo pl) {
        ImageView icon = row.findViewById(R.id.iv_playlist_icon);
        TextView tvName = row.findViewById(R.id.tv_name);
        TextView tvDetail = row.findViewById(R.id.tv_detail);
        icon.clearColorFilter();
        icon.setImageResource(R.drawable.ic_music_note);
        icon.setColorFilter(com.qinghe.music163pro.util.UiKit.COLOR_ACCENT);
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

    // ── 排序与 A-Z 字母索引（与改造前同一套 PinyinUtils + LetterPickerActivity） ──

    /**
     * 按歌单名的拼音首字母排序，并建立「首字母 → 第一项下标」分组。
     * 排行入口固定在首位、不参与排序；同字母内按中文拼音规则比名称。
     */
    private void sortAndIndex() {
        final Collator collator = Collator.getInstance(Locale.CHINA);
        final int from = (!items.isEmpty() && isRankEntry(items.get(0))) ? 1 : 0;
        if (items.size() - from > 1) {
            Collections.sort(items.subList(from, items.size()), (left, right) -> {
                char leftInitial = PinyinUtils.initialOfWord(left.getName());
                char rightInitial = PinyinUtils.initialOfWord(right.getName());
                if (leftInitial != rightInitial) {
                    // '#'（数字 / 符号 / 非汉字）永远排在最后
                    if (leftInitial == PinyinUtils.OTHER) {
                        return 1;
                    }
                    if (rightInitial == PinyinUtils.OTHER) {
                        return -1;
                    }
                    return Character.compare(leftInitial, rightInitial);
                }
                return collator.compare(left.getName(), right.getName());
            });
        }
        buildLetterGroups();
    }

    private void buildLetterGroups() {
        Map<Character, Integer> firstIndex = new LinkedHashMap<>();
        Map<Character, Integer> counts = new LinkedHashMap<>();
        int from = (!items.isEmpty() && isRankEntry(items.get(0))) ? 1 : 0;
        for (int i = from; i < items.size(); i++) {
            char initial = PinyinUtils.initialOfWord(items.get(i).getName());
            if (!firstIndex.containsKey(initial)) {
                firstIndex.put(initial, i);
            }
            Integer count = counts.get(initial);
            counts.put(initial, count == null ? 1 : count + 1);
        }
        groupLetters.clear();
        groupStarts = new int[firstIndex.size()];
        groupCounts = new int[firstIndex.size()];
        int index = 0;
        for (Map.Entry<Character, Integer> entry : firstIndex.entrySet()) {
            groupLetters.add(entry.getKey());
            groupStarts[index] = entry.getValue();
            Integer count = counts.get(entry.getKey());
            groupCounts[index] = count == null ? 0 : count;
            index++;
        }
    }

    /** 顶栏 ☰ 菜单里的「A-Z 字母索引」：打开独立字母选择页。 */
    public final void openLetterPicker() {
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
        // 记下来：onResume 会重新拉取列表，等列表重建完成后再跳，否则刚 setSelection 就被 reload 冲掉。
        pendingScrollTo = Math.max(0, Math.min(target, items.size() - 1));
        listView.setSelection(pendingScrollTo);
    }

    private void scrollPending() {
        if (pendingScrollTo >= 0 && listView != null) {
            final int pos = pendingScrollTo;
            pendingScrollTo = -1;
            listView.setSelection(pos);
        }
    }

    /** 清掉待跳转（加载失败时避免下次成功加载误跳）。 */
    protected final void clearPendingScroll() {
        pendingScrollTo = -1;
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private void reportSummary() {
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).onMyPlaylistsPageSummary(pageIndex(),
                    pageName() + " · " + realItemCount() + " 个");
        }
    }

    protected final int px(int base) {
        return WatchUiUtils.px(requireContext(), base);
    }

    protected final void toast(CharSequence text) {
        if (isAdded()) {
            com.qinghe.music163pro.util.UiKit.toast(requireContext(), text);
        }
    }

    protected final void toastLong(CharSequence text) {
        if (isAdded()) {
            com.qinghe.music163pro.util.UiKit.toastLong(requireContext(), text);
        }
    }
}
