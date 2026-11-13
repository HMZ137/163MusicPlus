package com.qinghe.music163pro.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.MainActivity;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.MusicLog;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.WatchUiUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * P8：歌手页两页（热门 50 首 / 全部歌曲）的公共基类。
 *
 * <p>迁移前这两份内容合在 {@code ArtistDetailActivity} 的一个 ListView 里：一个 header
 * （头像 / 名字 / 统计 / 简介）+ 「热门 50 首 / 全部歌曲」两个切换按钮 + 一份 {@code songs} 列表，
 * 切模式就是换这份列表的数据。按需求方 2026-10-17 口径（同 4-12 / 4-16）迁到
 * <b>ViewPager2 + Fragment</b> 后：两个模式各是一页，靠左右滑切换、<b>不再保留标签按钮</b>；
 * 「当前页 · 数量」放到宿主标题栏外的独立信息行。</p>
 *
 * <p><b>歌手资料</b>（头像 / 名字 / 歌曲·专辑数 / 简介）仍做成每页 ListView 的 header：
 * 固定在标题下方会把作品列表挤到只剩两三行（378×496 表盘上实测过），随列表滚动才让得开。</p>
 *
 * <p><b>数据加载</b>：歌手详情（含热门 50 首）只由宿主 Activity 请求一次，
 * 本基类通过 {@link Host#getArtistDetail()} 取用；「全部歌曲」由
 * {@link ArtistAllSongsFragment} 自己按需请求（只在真正滑到该页 / 刷新时才打网络）。</p>
 */
public abstract class ArtistPageFragment extends Fragment {

    /** 宿主（{@code ArtistDetailActivity}）提供歌手资料、登录态与信息行回写。 */
    public interface Host {
        /** 当前登录 cookie（未登录为 null / 空）。 */
        String getCookie();

        /** 歌手 id。 */
        long getArtistId();

        /** 歌手名（详情回来前先用 intent 里带的名字）。 */
        String getArtistName();

        /** 歌手详情（宿主首次加载成功后缓存；加载中 / 失败为 null）。 */
        MusicApiHelper.ArtistDetail getArtistDetail();

        /** 歌手详情加载失败原因（成功或加载中为 null）。 */
        String getArtistDetailError();

        /** 回写「当前页 · 数量」信息行；page 是本页序号，与 ViewPager2 的页一致。 */
        void onArtistPageSummary(int page, CharSequence summary);
    }

    public static final int PAGE_HOT = 0;
    public static final int PAGE_ALL = 1;

    /** 本页数据。子类加载完通过 {@link #applyItems(List)} 落进来。 */
    protected final List<Song> items = new ArrayList<>();

    private ListView listView;
    private TextView emptyView;
    private SongAdapter adapter;

    private View profileHeader;
    private ImageView ivAvatar;
    private TextView tvName;
    private TextView tvStat;
    private TextView tvDesc;

    /** 页序号（{@link #PAGE_HOT} / {@link #PAGE_ALL}）。 */
    public abstract int pageIndex();

    /** 信息行里的页名：热门 / 全部。 */
    protected abstract CharSequence pageName();

    /** 加载本页数据；拿到数据后调用 {@link #applyItems(List)} 或 {@link #showStatus(CharSequence)}。 */
    protected abstract void loadData();

    /** 列表为空时的文案。 */
    protected abstract CharSequence emptyText();

    /** 刷新（标题栏 ☰ → 刷新）。默认重跑 {@link #loadData()}，需要强制重拉的页可重写。 */
    protected void onRefresh() {
        loadData();
    }

    // ==================== 生命周期 ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_artist_songs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listView = view.findViewById(R.id.lv_artist_songs);
        emptyView = view.findViewById(R.id.tv_artist_empty);
        // 歌手资料作 header：必须 addHeaderView 在 setAdapter 之前，且自己用
        // AbsListView.LayoutParams（列表内部按该类型取尺寸）。
        profileHeader = buildProfileHeader();
        listView.addHeaderView(profileHeader, null, false);
        adapter = new SongAdapter();
        listView.setAdapter(adapter);
        // 封面按需加载（快速滑动时暂停、停稳后补加载可见行），与收藏页 / 搜索页同一套工具。
        NetworkImageLoader.attachListViewOptimization(listView, adapter, position ->
                position >= 0 && position < items.size() ? items.get(position).getCoverUrl() : null);
        listView.setOnItemClickListener((parent, v, position, id) -> {
            // position 是「含 header 的绝对位置」，先扣掉 header 数（与改造前的 header 偏移处理一致）。
            int dataPosition = position - listView.getHeaderViewsCount();
            if (dataPosition >= 0 && dataPosition < items.size()) {
                playFrom(dataPosition);
            }
        });
        updateEmptyState();
    }

    @Override
    public void onResume() {
        super.onResume();
        // ViewPager2 只把当前页置为 RESUMED ⇒ 这里就是「本页在前台」的时机（首次进入 / 滑到本页 / 从主界面返回）。
        MusicLog.i("Vp2", "歌手页 onResume page=" + pageIndex() + " (" + pageName() + ")");
        renderProfile();
        reportLoading();
        loadData();
    }

    @Override
    public void onDestroyView() {
        // 视图销毁后异步回调不能再碰列表；items 保留（与改造前 Activity 字段语义一致）。
        listView = null;
        emptyView = null;
        adapter = null;
        profileHeader = null;
        ivAvatar = null;
        tvName = null;
        tvStat = null;
        tvDesc = null;
        super.onDestroyView();
    }

    // ==================== 歌手资料 header ====================

    /** 代码构建歌手资料 header（头像 / 名字 / 歌曲·专辑数 / 简介），与改造前同一套尺寸与配色。 */
    private View buildProfileHeader() {
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setLayoutParams(new AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(px(6), px(2), px(6), px(2));
        row.setLayoutParams(rowLp);

        ivAvatar = new ImageView(requireContext());
        ivAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivAvatar.setBackgroundColor(0xFF252525);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(px(56), px(56));
        avatarLp.setMarginEnd(px(10));
        row.addView(ivAvatar, avatarLp);

        LinearLayout infoCol = new LinearLayout(requireContext());
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        tvName = new TextView(requireContext());
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvName.setSingleLine(true);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        infoCol.addView(tvName);

        tvStat = new TextView(requireContext());
        tvStat.setTextColor(0x99FFFFFF);
        tvStat.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tvStat.setSingleLine(true);
        infoCol.addView(tvStat);

        row.addView(infoCol);
        header.addView(row);

        tvDesc = new TextView(requireContext());
        tvDesc.setTextColor(0x99FFFFFF);
        tvDesc.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tvDesc.setMaxLines(4);
        tvDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvDesc.setPadding(px(6), 0, px(6), px(4));
        tvDesc.setVisibility(View.GONE);
        header.addView(tvDesc);
        return header;
    }

    /** 用宿主缓存的歌手详情刷新 header（详情未回来时保留占位，不留空白行）。 */
    protected final void renderProfile() {
        if (profileHeader == null) {
            return;
        }
        MusicApiHelper.ArtistDetail detail = detail();
        if (detail == null) {
            // 详情还没回来：名字先用 intent 里带的，避免 header 空白。
            Host h = host();
            if (tvName != null && tvName.getText().length() == 0 && h != null) {
                tvName.setText(h.getArtistName() == null ? "" : h.getArtistName());
            }
            return;
        }
        if (detail.name != null && !detail.name.isEmpty() && tvName != null) {
            tvName.setText(detail.name);
        }
        if (detail.picUrl != null && !detail.picUrl.isEmpty() && ivAvatar != null) {
            NetworkImageLoader.load(ivAvatar, detail.picUrl);
        }
        StringBuilder stat = new StringBuilder();
        if (detail.musicSize > 0) {
            stat.append("歌曲 ").append(detail.musicSize);
        }
        if (detail.albumSize > 0) {
            if (stat.length() > 0) {
                stat.append(" · ");
            }
            stat.append("专辑 ").append(detail.albumSize);
        }
        if (tvStat != null) {
            tvStat.setText(stat.toString());
        }
        if (detail.briefDesc != null && !detail.briefDesc.isEmpty() && tvDesc != null) {
            tvDesc.setText(detail.briefDesc.trim());
            tvDesc.setVisibility(View.VISIBLE);
        }
    }

    // ==================== 给子类的落数据 / 状态工具 ====================

    /** 首次加载或重载完成：刷新列表与空态，并回写信息行。 */
    protected final void applyItems(List<Song> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyItemsChanged();
        updateEmptyState();
        reportSummary();
    }

    /** 只刷新行。 */
    protected final void notifyItemsChanged() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** 加载中 / 失败等状态：空态区显示说明，并回写信息行。 */
    protected final void showStatus(CharSequence text) {
        if (emptyView != null) {
            emptyView.setText(text);
            emptyView.setVisibility(View.VISIBLE);
        }
        if (listView != null) {
            listView.setVisibility(View.GONE);
        }
        Host h = host();
        if (h != null) {
            h.onArtistPageSummary(pageIndex(), pageName() + " · " + text);
        }
    }

    private void updateEmptyState() {
        if (listView == null || emptyView == null) {
            return;
        }
        boolean empty = items.isEmpty();
        emptyView.setText(emptyText());
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** 宿主「☰ 菜单 → 刷新」：重新加载当前页。 */
    public final void refresh() {
        if (isAdded()) {
            reportLoading();
            onRefresh();
        }
    }

    /** 宿主加载到歌手详情后回调：刷新 header，并让本页有机会用详情里的数据（热门 50 首）。 */
    public final void onArtistDetailReady() {
        if (!isAdded()) {
            return;
        }
        renderProfile();
        onDetailReady();
    }

    /**
     * 详情到达后本页要做什么：默认重跑 {@link #loadData()}（热门页直接用详情里的 hotSongs）。
     *
     * <p>「全部歌曲」页重写为空实现：它不依赖歌手详情，而且按需求方「滑到才加载」的口径，
     * 未滑到的页不该因为详情回来就白打一次网络请求。</p>
     */
    protected void onDetailReady() {
        loadData();
    }

    // ==================== 播放 ====================

    /** 以本页歌曲为播放队列开始播放，然后回主界面（与改造前一致）。 */
    private void playFrom(int position) {
        if (items.isEmpty() || position < 0 || position >= items.size()) {
            return;
        }
        Host h = host();
        String name = h == null || h.getArtistName() == null || h.getArtistName().isEmpty()
                ? "歌手" : h.getArtistName();
        List<Song> playlist = new ArrayList<>(items);
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        playerManager.setPlaylistFromSource(playlist, position,
                0L, name, playlist.size(), name, 0, false);
        playerManager.playCurrent();
        Intent intent = new Intent(requireContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    // ==================== 小工具 ====================

    protected final ListView listView() {
        return listView;
    }

    protected final boolean isViewReady() {
        return isAdded() && listView != null;
    }

    protected final MusicApiHelper.ArtistDetail detail() {
        Host h = host();
        return h == null ? null : h.getArtistDetail();
    }

    protected final boolean hasDetail() {
        return detail() != null;
    }

    protected final String detailError() {
        Host h = host();
        return h == null ? null : h.getArtistDetailError();
    }

    protected final long artistId() {
        Host h = host();
        return h == null ? 0L : h.getArtistId();
    }

    protected final String cookie() {
        Host h = host();
        return h == null ? null : h.getCookie();
    }

    protected final void reportSummary() {
        Host h = host();
        if (h != null) {
            h.onArtistPageSummary(pageIndex(), pageName() + " · " + items.size() + " 首");
        }
    }

    protected final void reportLoading() {
        Host h = host();
        if (h != null) {
            h.onArtistPageSummary(pageIndex(), pageName() + " · 加载中…");
        }
    }

    protected final Host host() {
        return getActivity() instanceof Host ? (Host) getActivity() : null;
    }

    protected final int px(int baseValue) {
        return WatchUiUtils.px(requireContext(), baseValue);
    }

    protected final void toast(CharSequence text) {
        if (isAdded()) {
            com.qinghe.music163pro.util.UiKit.toast(requireContext(), text);
        }
    }

    /** 歌曲列表适配（复用 item_song 布局，与改造前的行渲染一致）。 */
    private final class SongAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).getId();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_song, parent, false);
            }
            Song song = items.get(position);
            TextView tvItemName = view.findViewById(R.id.tv_item_name);
            TextView tvItemArtist = view.findViewById(R.id.tv_item_artist);
            ImageView ivCover = view.findViewById(R.id.iv_cover);
            View tvProgress = view.findViewById(R.id.tv_item_progress);
            View ivCheck = view.findViewById(R.id.iv_item_check);

            tvItemName.setText(song.getDisplayName());
            tvItemArtist.setText(song.getArtist());
            if (tvProgress != null) {
                tvProgress.setVisibility(View.GONE);
            }
            if (ivCheck != null) {
                ivCheck.setVisibility(View.GONE);
            }
            if (ivCover != null && song.getCoverUrl() != null && !song.getCoverUrl().isEmpty()) {
                NetworkImageLoader.load(ivCover, song.getCoverUrl());
            }
            return view;
        }
    }
}
