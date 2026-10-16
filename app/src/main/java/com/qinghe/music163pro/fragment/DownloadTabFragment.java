package com.qinghe.music163pro.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.qinghe.music163pro.R;

/**
 * 「下载」标签页（任务 <b>4-2</b>）：把下载列表的**列表表面**搬进 ViewPager2 的页面容器。
 *
 * <p><b>职责边界（刻意如此）</b>：本 Fragment 只持有这一页的**视图**（`ListView` + 空态
 * `TextView`），而数据、适配器、读盘、落地判据、多选、标题栏、整理等仍由
 * {@link com.qinghe.music163pro.activity.DownloadListActivity} 统一掌管。
 * 也就是说这次迁移搬的是「列表表面」，不是把 600 行逻辑重写一遍 ——
 * 迁移风险与回归面都压到最小，符合文档 4-2「尽量整体搬迁而非重写」的要求。</p>
 *
 * <p><b>UiScale 兼容（G4 改动要点 3）</b>：视图由 {@code onCreateView} 的 inflater 创建，
 * 其 Context 来自宿主 Activity（已由 {@code UiScale.wrap} 处理过）。
 * <b>不可</b>改用 {@code requireActivity().getApplicationContext()}，否则字号缩放会丢。</p>
 */
public class DownloadTabFragment extends Fragment {

    /** 宿主需要实现的回调。 */
    public interface Host {
        /** 本页视图就绪：把列表与空态交给宿主绑定数据与交互。 */
        void onDownloadListViewsReady(ListView listView, TextView emptyView);

        /** 本页成为当前页（首次进入 / 从「本地」滑回来）：宿主据此刷新数据。 */
        void onDownloadTabResumed();

        /** 本页离开前台：宿主据此停掉进度刷新，别白耗电。 */
        void onDownloadTabPaused();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_download_tab, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ListView listView = view.findViewById(R.id.lv_downloads);
        TextView emptyView = view.findViewById(R.id.tv_empty);
        host().onDownloadListViewsReady(listView, emptyView);
    }

    @Override
    public void onResume() {
        super.onResume();
        // ViewPager2 只把**当前页**置为 RESUMED，其余页停在 STARTED，
        // 所以这里的 onResume / onPause 天然就是「本页是否在前台」的判据。
        host().onDownloadTabResumed();
    }

    @Override
    public void onPause() {
        host().onDownloadTabPaused();
        super.onPause();
    }

    private Host host() {
        return (Host) requireActivity();
    }
}
