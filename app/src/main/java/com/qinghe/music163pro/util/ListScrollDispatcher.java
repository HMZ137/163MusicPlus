package com.qinghe.music163pro.util;

import android.widget.AbsListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 列表滚动事件的「一装多订阅」分发器（2026-11-28）。
 *
 * <p>{@link AbsListView#setOnScrollListener} 只有一个槽位：封面按需加载
 * （{@link NetworkImageLoader#attachListViewOptimization}）已经占了它，
 * 多选动作栏的「滚动时收起」再直接 set 一次就会把封面加载顶掉。
 * 这里改成先登记、再由一个统一的监听转发给全部订阅者。</p>
 *
 * <p>只对 ListView 这类 AbsListView 生效（本工程列表都是 ListView）。</p>
 */
public final class ListScrollDispatcher {

    /** 订阅回调，语义与 {@link AbsListView.OnScrollListener} 一致。 */
    public interface Listener {
        void onScrollStateChanged(AbsListView view, int scrollState);

        default void onScroll(AbsListView view, int firstVisibleItem,
                              int visibleItemCount, int totalItemCount) {
        }
    }

    private static final Map<AbsListView, List<Listener>> REGISTRY = new WeakHashMap<>();

    private ListScrollDispatcher() {
    }

    /** 订阅滚动事件；同一个列表可以挂多个订阅者（重复订阅同一个对象会被忽略）。 */
    public static void add(final AbsListView view, final Listener listener) {
        if (view == null || listener == null) {
            return;
        }
        synchronized (REGISTRY) {
            List<Listener> listeners = REGISTRY.get(view);
            if (listeners == null) {
                listeners = new ArrayList<>();
                REGISTRY.put(view, listeners);
                install(view, listeners);
            }
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    private static void install(final AbsListView view, final List<Listener> listeners) {
        view.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int scrollState) {
                // 复制一份再遍历：回调里可能会 add 新订阅者，直接遍历原表会 ConcurrentModificationException
                List<Listener> snapshot;
                synchronized (REGISTRY) {
                    snapshot = new ArrayList<>(listeners);
                }
                for (Listener l : snapshot) {
                    l.onScrollStateChanged(v, scrollState);
                }
            }

            @Override
            public void onScroll(AbsListView v, int firstVisibleItem,
                                 int visibleItemCount, int totalItemCount) {
                List<Listener> snapshot;
                synchronized (REGISTRY) {
                    snapshot = new ArrayList<>(listeners);
                }
                for (Listener l : snapshot) {
                    l.onScroll(v, firstVisibleItem, visibleItemCount, totalItemCount);
                }
            }
        });
    }
}
