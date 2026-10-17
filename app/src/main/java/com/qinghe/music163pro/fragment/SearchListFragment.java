package com.qinghe.music163pro.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.NetworkImageLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * S1：搜索页「单曲 / 歌单 / MV」三个结果页的公共基类。
 *
 * <p>三个标签原本是 {@code SearchActivity} 里的三个 ListView + 三份几乎相同的分页逻辑
 * （每页 20 条、滑到底自动加载更多、封面按需加载）。迁到 ViewPager2 后把这份公共逻辑收到这里，
 * 子类只负责：条目布局 / 行内绑定 / 一次网络请求 / 点击跳转 / 空结果提示。</p>
 *
 * <p><b>关键词来源</b>：搜索框仍留在 Activity，Fragment 通过 {@link Host#getSearchKeyword()} 取；
 * 新搜索时 Activity 会遍历已创建的 Fragment 调 {@link #search(String)}；后来才创建（ViewPager2 预加载/滑到）
 * 的 Fragment 会在 {@code onViewCreated} 里用当前关键词补一次搜索 —— 两种时机都覆盖到。</p>
 */
public abstract class SearchListFragment<T> extends Fragment {

    /** 宿主（SearchActivity）提供当前关键词、登录态与结果页信息行回写。 */
    public interface Host {
        String getSearchKeyword();

        String getCookie();

        /**
         * 回写「当前结果页 · 已加载条数」信息行（去 Tab 后由标题栏外的独立行显示）。
         *
         * @param page    结果页序号（0 单曲 / 1 歌单 / 2 MV），与 ViewPager2 的页一致
         * @param summary 形如「单曲 · 40 条」
         */
        void onSearchPageSummary(int page, CharSequence summary);
    }

    /** 单页请求回调。 */
    public interface PageCallback<T> {
        void onResult(List<T> page);

        void onError(String message);
    }

    /** 每页条数（与原实现一致）。 */
    protected static final int PAGE_SIZE = 20;

    protected final List<T> items = new ArrayList<>();
    private ListView listView;
    private ArrayAdapter<T> adapter;
    private int offset = 0;
    private boolean hasMore = true;
    private boolean loadingMore = false;
    private boolean searched = false;

    /** 结果页序号（0 单曲 / 1 歌单 / 2 MV），用于信息行回写与日志。 */
    protected abstract int pageIndex();

    /** 信息行里的结果页名字（单曲 / 歌单 / MV）。 */
    protected abstract CharSequence pageName();

    /** 条目布局（item_song / item_playlist / item_mv）。 */
    protected abstract int itemLayoutRes();

    /** 条目主文本控件 id（ArrayAdapter 需要）。 */
    protected abstract int itemTextRes();

    /** 行内绑定（曲名/曲师、封面等）。 */
    protected abstract void bindRow(View row, T item);

    /** 发起一页请求；子类直接转调 MusicApiHelper。 */
    protected abstract void fetchPage(int offset, PageCallback<T> callback);

    /** 该条目的封面地址（滚动时按需加载用，可为 null）。 */
    protected abstract String coverUrl(T item);

    protected abstract void onItemClicked(int position, T item);

    /** 第一页就是空结果时的提示。 */
    protected abstract void onEmptyFirstPage();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listView = view.findViewById(R.id.lv_search_list);
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
        listView.setOnItemClickListener((parent, v, position, id) -> {
            if (position >= 0 && position < items.size()) {
                onItemClicked(position, items.get(position));
            }
        });
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int scrollState) {
                NetworkImageLoader.onListScrollStateChanged(v, adapter, scrollState, provider());
            }

            @Override
            public void onScroll(AbsListView v, int firstVisibleItem, int visibleItemCount,
                                 int totalItemCount) {
                if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount
                        && searched && hasMore && !loadingMore) {
                    loadMore();
                }
                NetworkImageLoader.onListScrolled(firstVisibleItem, visibleItemCount,
                        totalItemCount, provider());
            }
        });
        Host host = host();
        if (host != null && host.getSearchKeyword() != null
                && !host.getSearchKeyword().isEmpty()) {
            search(host.getSearchKeyword());
        }
    }

    /** 用新关键词重搜（清空旧结果，从第 0 条开始）。 */
    public void search(String keyword) {
        searched = true;
        offset = 0;
        hasMore = true;
        loadingMore = false;
        reportLoading();
        fetchPage(0, new PageCallback<T>() {
            @Override
            public void onResult(List<T> page) {
                if (!isAdded() || listView == null) {
                    return;
                }
                items.clear();
                items.addAll(page);
                adapter.notifyDataSetChanged();
                offset = page.size();
                hasMore = page.size() >= PAGE_SIZE;
                reportSummary();
                if (page.isEmpty()) {
                    onEmptyFirstPage();
                }
            }

            @Override
            public void onError(String message) {
                if (isAdded()) {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void loadMore() {
        loadingMore = true;
        fetchPage(offset, new PageCallback<T>() {
            @Override
            public void onResult(List<T> page) {
                loadingMore = false;
                if (!isAdded() || listView == null) {
                    return;
                }
                if (page.isEmpty()) {
                    hasMore = false;
                    return;
                }
                items.addAll(page);
                adapter.notifyDataSetChanged();
                offset += page.size();
                hasMore = page.size() >= PAGE_SIZE;
                reportSummary();
            }

            @Override
            public void onError(String message) {
                loadingMore = false;
                if (isAdded()) {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private NetworkImageLoader.CoverUrlProvider provider() {
        return position -> position >= 0 && position < items.size()
                ? coverUrl(items.get(position)) : null;
    }

    protected Host host() {
        return getActivity() instanceof Host ? (Host) getActivity() : null;
    }

    /** 回写「结果页 · 已加载 N 条」信息行（首屏、加载更多之后都会刷新）。 */
    protected final void reportSummary() {
        Host h = host();
        if (h != null) {
            h.onSearchPageSummary(pageIndex(), pageName() + " · " + items.size() + " 条");
        }
    }

    /** 搜索结果请求在途时的信息行文案。 */
    protected final void reportLoading() {
        Host h = host();
        if (h != null) {
            h.onSearchPageSummary(pageIndex(), pageName() + " · 搜索中…");
        }
    }

    protected String keyword() {
        Host h = host();
        return h == null || h.getSearchKeyword() == null ? "" : h.getSearchKeyword();
    }

    protected String cookie() {
        Host h = host();
        return h == null ? null : h.getCookie();
    }
}
