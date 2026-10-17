package com.qinghe.music163pro.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.MusicLog;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.WatchConfirmDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * 4-12：收藏列表三页（单曲 / 歌单 / 专辑）的公共基类。
 *
 * <p>三个标签原本是 {@link com.qinghe.music163pro.activity.FavoritesListActivity} 里的
 * 三个 ListView + 三份几乎相同的「加载 → 适配器 → 空态」逻辑。迁到 ViewPager2 后把公共部分收到这里，
 * 子类只负责：条目布局 / 行内绑定 / 一页数据的加载 / 点击跳转 / 空态文案。</p>
 *
 * <p><b>加载时机</b>：ViewPager2 只把<b>当前页</b>置为 RESUMED，其余页停在 STARTED，所以基类在
 * {@link #onResume()} 里调 {@link #loadData()} 天然就是「本页在前台」的时机 —— 首次进入、左右滑到本页、
 * 从详情页返回都覆盖得到，与原 Activity 在 {@code onResume} 重新加载数据的口径一致；
 * 而没被滑到的页不会白发网络请求（专辑页尤其需要）。</p>
 *
 * <p><b>空态</b>：每页有自己的空态文案，不再共用宿主那一个 {@code tv_empty}（原实现里空态文字
 * 会被上一个标签的加载逻辑覆盖成别的页的文案）。</p>
 *
 * <p><b>「其他需要展示的内容」行</b>：按需求方口径（`多Tab实例_1.bmp`）去 Tab 后，
 * 用标题栏外的一行独立信息显示「当前页 · 数量」；本基类在数据落地 / 加载中 / 失败时通过
 * {@link Host#onFavoritesPageSummary(int, CharSequence)} 回写。</p>
 */
public abstract class FavoritesPageFragment<T> extends Fragment {

    /** 宿主（{@code FavoritesListActivity}）提供登录态、收藏模式与信息行回写。 */
    public interface Host {
        /** 当前登录 cookie（未登录为 null / 空）。 */
        String getCookie();

        /** 收藏模式：true = 云端（{@code fav_mode_cloud} 开关打开），false = 本地。 */
        boolean isCloudMode();

        /** 回写「当前页 · 数量」信息行；page 是本页序号，与 ViewPager2 的页一致。 */
        void onFavoritesPageSummary(int page, CharSequence summary);
    }

    public static final int PAGE_SONGS = 0;
    public static final int PAGE_PLAYLISTS = 1;
    public static final int PAGE_ALBUMS = 2;

    /** 本页数据。子类加载完通过 {@link #applyItems(List)} 落进来。 */
    protected final List<T> items = new ArrayList<>();
    private ListView listView;
    private TextView emptyView;
    private ArrayAdapter<T> adapter;

    /** 页序号（{@link #PAGE_SONGS} / {@link #PAGE_PLAYLISTS} / {@link #PAGE_ALBUMS}）。 */
    public abstract int pageIndex();

    /** 条目布局（item_song / item_playlist）。 */
    protected abstract int itemLayoutRes();

    /** 条目主文本控件 id（ArrayAdapter 需要）。 */
    protected abstract int itemTextRes();

    /** 行内绑定（曲名 / 曲师、封面等）。 */
    protected abstract void bindRow(View row, T item);

    /** 该条目的封面地址（滚动时按需加载用，可为 null）。 */
    protected abstract String coverUrl(T item);

    /** 加载本页数据；拿到数据后调用 {@link #applyItems(List)} 或 {@link #showStatus(CharSequence)}。 */
    protected abstract void loadData();

    /** 列表为空时的文案（可随本地/云端模式不同）。 */
    protected abstract CharSequence emptyText();

    /** 信息行里的页名：单曲 / 歌单 / 专辑。 */
    protected abstract CharSequence pageName();

    /** 信息行里的数量后缀：首 / 个 / 张。 */
    protected abstract CharSequence countUnit();

    /** 行点击（子类按需重写）。 */
    protected void onItemClicked(int position, T item) {
    }

    /** 行长按（子类按需重写；返回 true 表示已处理）。 */
    protected boolean onItemLongClicked(int position, T item) {
        return false;
    }

    /**
     * 视图就绪（子类在这里挂多选等需要 ListView 的附加能力）。
     *
     * <p>此时适配器与点击/长按监听都已设好 —— {@code MultiSelectController.attach} 必须在设置监听之后调用，
     * 否则非多选状态下的原有点击逻辑不会被转发。</p>
     */
    protected void onListReady() {
    }

    /** 每次 {@link #onResume()} 里面向 {@link #loadData()} 之前的一次复位钩子。 */
    protected void onBeforeReload() {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_favorites_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listView = view.findViewById(R.id.lv_fav_list);
        emptyView = view.findViewById(R.id.tv_fav_empty);
        adapter = new ArrayAdapter<T>(requireContext(), itemLayoutRes(), itemTextRes(), items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                T item = getItem(position);
                if (item != null) {
                    bindRow(row, item);
                }
                return row;
            }
        };
        listView.setAdapter(adapter);
        // 封面按需加载（快速滑动时暂停、停稳后补加载可见行），与搜索页共用同一套工具。
        NetworkImageLoader.attachListViewOptimization(listView, adapter, position ->
                position >= 0 && position < items.size() ? coverUrl(items.get(position)) : null);
        listView.setOnItemClickListener((parent, v, position, id) -> {
            if (position >= 0 && position < items.size()) {
                onItemClicked(position, items.get(position));
            }
        });
        listView.setOnItemLongClickListener((parent, v, position, id) ->
                position >= 0 && position < items.size() && onItemLongClicked(position, items.get(position)));
        onListReady();
        updateEmptyState();
    }

    @Override
    public void onResume() {
        super.onResume();
        // ViewPager2 只把当前页置为 RESUMED ⇒ 这里就是「本页在前台」的时机。
        MusicLog.i("Vp2", "收藏页 onResume page=" + pageIndex() + " (" + pageName() + ")");
        onBeforeReload();
        reportLoading();
        loadData();
    }

    @Override
    public void onDestroyView() {
        // 视图销毁后异步回调不能再碰列表；items 保留，视图重建时直接复用（与改造前的字段语义一致）。
        listView = null;
        emptyView = null;
        adapter = null;
        super.onDestroyView();
    }

    // ── 给子类的落数据 / 状态工具 ────────────────────────────────────────────

    /** 首次加载或重载完成：刷新列表与空态，并回写信息行。 */
    protected final void applyItems(List<T> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyItemsChanged();
        updateEmptyState();
        reportSummary();
    }

    /** 只刷新行（原地修改了 items 里的对象 / 删了某一项之后用）。 */
    protected final void notifyItemsChanged() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** 删掉一项并刷新（云端取消收藏 / 本地删除歌单等）。 */
    protected final void removeItemAt(int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        items.remove(position);
        notifyItemsChanged();
        updateEmptyState();
        reportSummary();
    }

    /** 加载中 / 失败 / 未登录等状态：空态区显示说明，并回写信息行。 */
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
            h.onFavoritesPageSummary(pageIndex(), pageName() + " · " + text);
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

    /** 宿主「菜单 → 刷新」：重新加载当前页。 */
    public final void refresh() {
        if (isAdded()) {
            reportLoading();
            loadData();
        }
    }

    /**
     * 返回键：子类（单曲页的多选）可以消费。默认不消费。
     *
     * <p>宿主只注册了<b>一个</b>返回键拦截器，转发给当前页 Fragment 的这个方法 ——
     * 多选控制器仍由页面（Fragment）持有，不必在宿主里再存一份。</p>
     */
    public boolean handleBackPressed() {
        return false;
    }

    // ── 给子类的小工具 ──────────────────────────────────────────────────────

    protected final ListView listView() {
        return listView;
    }

    protected final boolean isViewReady() {
        return isAdded() && listView != null;
    }

    protected final void reportSummary() {
        Host h = host();
        if (h != null) {
            // 数字与量词之间留一个空格，与下载页的「71 首」信息行保持同一排版。
            h.onFavoritesPageSummary(pageIndex(), pageName() + " · " + items.size() + " " + countUnit());
        }
    }

    protected final void reportLoading() {
        Host h = host();
        if (h != null) {
            h.onFavoritesPageSummary(pageIndex(), pageName() + " · 加载中…");
        }
    }

    protected final Host host() {
        return getActivity() instanceof Host ? (Host) getActivity() : null;
    }

    protected final String cookie() {
        Host h = host();
        return h == null ? null : h.getCookie();
    }

    protected final boolean isCloudMode() {
        Host h = host();
        return h != null && h.isCloudMode();
    }

    protected final void showConfirmDialog(String title, String message, Runnable onConfirm) {
        if (!isAdded()) {
            return;
        }
        WatchConfirmDialog.show(requireActivity(), title, message, onConfirm,
                WatchConfirmDialog.Options.defaultOptions());
    }

    protected final void toast(CharSequence text) {
        if (isAdded()) {
            Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
        }
    }
}
