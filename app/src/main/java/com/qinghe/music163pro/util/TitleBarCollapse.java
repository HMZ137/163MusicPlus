package com.qinghe.music163pro.util;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AbsListView;
import android.widget.ScrollView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * G3：标题栏「<b>上滑收起 / 下滑展开</b>」，并要求标题栏本身**固定、不随列表滚动**。
 *
 * <p><b>方向以需求方 2026-10-17 晚的第二次勘误为准</b>：<u>上滑 = 收起，下滑 = 展开</u>；
 * 并且收起后 <b>2.5 秒无任何触摸操作自动展开</b>（需求方口径「超过 2.5s 无操作时展出」）。
 * 此前记录过的「下滑 = 收起，上滑 = 展开」已被需求方推翻，技术文档 G3 节同步改回并补上自动展开，
 * 改代码时以本文件注释为准。</p>
 *
 * <p><b>为什么放在这里统一做</b>：与 G5 同一思路 —— 全 App 的标题栏有一个统一时机
 * （{@link WatchBackButton#applyTitleBarInset} / {@link WatchBackButton#applyTitleBarTopInset}），
 * 把「登记标题栏」挂进那个时机，所有走统一链路的页面一次性接入，不必逐页改。
 * 手势只由基类 {@code BaseWatchActivity.dispatchTouchEvent} 观察（<b>不消费事件</b>），
 * 因此不会抢掉列表的滚动/点击。</p>
 *
 * <p><b>固定标题栏</b>：若登记时发现标题栏位于滚动容器内部（ScrollView / ListView /
 * RecyclerView），说明该页标题仍是滚动内容的第一个子项（会随列表滚走），此时**不登记**并留一条
 * 日志 —— 这类页面必须先做「把标题移出滚动容器」的结构改造（见技术文档 G3 阻塞项，共 9 个页面）。</p>
 *
 * <p><b>状态不持久化</b>：收起态不写盘，页面重建 / 重新进入由 {@link #reset(Activity)} 复位为展开
 * （G3 验收要求「返回再进入时状态复位」）。</p>
 */
public final class TitleBarCollapse {

    private static final String TAG = "TitleBarCollapse";

    /** 设置项：标题栏上滑收起（设置 → 交互与浮层）。 */
    public static final String PREF_ENABLED = "title_bar_collapse_enabled";

    /** 触发一次收起/展开所需的最小竖直位移（设计基准 320 宽下的 px，经 px() 换算）。 */
    private static final int SWIPE_THRESHOLD_BASE = 26;
    /** 收起/展开动画时长。 */
    private static final long ANIM_MS = 180L;
    /**
     * 收起后**无操作自动展开**的等待时长（需求方 2026-10-17 晚口径：2.5 秒）。
     *
     * <p>「无操作」按<b>没有新的触摸按下</b>判定：一旦用户再次按下屏幕（滚动列表、点击等），
     * 计时从头开始 —— 手还在操作时标题栏不该自己弹回来；手停下来 2.5 秒才自动展开。</p>
     */
    private static final long AUTO_EXPAND_MS = 2500L;

    /** 已登记的标题栏：Activity → 标题栏 View。 */
    private static final Map<Activity, View> TITLE_BARS =
            Collections.synchronizedMap(new WeakHashMap<Activity, View>());
    /** 展开态的高度（px），收起后用它还原。 */
    private static final Map<View, Integer> EXPANDED_HEIGHT =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());
    /** 本次手势的按下点。 */
    private static final Map<Activity, Float> DOWN_Y =
            Collections.synchronizedMap(new WeakHashMap<Activity, Float>());
    /** 收起态的自动展开任务：Activity → 待执行的那一次展开。 */
    private static final Map<Activity, Runnable> AUTO_EXPAND_TASKS =
            Collections.synchronizedMap(new WeakHashMap<Activity, Runnable>());
    /** 自动展开用的主线程定时器（只在收起后跑一次，2.5s）。 */
    private static final Handler AUTO_EXPAND_HANDLER = new Handler(Looper.getMainLooper());

    private TitleBarCollapse() {
    }

    /**
     * 登记一个标题栏（由 {@link WatchBackButton} 的统一时机调用，页面无需自己接）。
     *
     * <p>标题栏在滚动容器内部时**不登记**：那种页面需要先做「固定标题栏」的结构改造。</p>
     */
    public static void register(Activity activity, View titleBar) {
        if (activity == null || titleBar == null) {
            return;
        }
        if (isInsideScrollable(titleBar)) {
            MusicLog.w(TAG, activity.getClass().getSimpleName()
                    + " 的标题栏在滚动容器内部（会随列表滚走），已跳过收起登记 —— 需先固定标题栏");
            return;
        }
        TITLE_BARS.put(activity, titleBar);
        captureExpandedHeight(titleBar);
    }

    /** 标题栏是否位于滚动容器内部。 */
    private static boolean isInsideScrollable(View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent instanceof ScrollView || parent instanceof AbsListView
                    || parent instanceof androidx.recyclerview.widget.RecyclerView) {
                return true;
            }
            parent = ((View) parent).getParent();
        }
        return false;
    }

    /** 记下展开态高度（布局可能还没完成，post 一次再量）。 */
    private static void captureExpandedHeight(final View titleBar) {
        titleBar.post(() -> {
            int h = titleBar.getHeight();
            if (h > 0 && !isCollapsed(titleBar)) {
                EXPANDED_HEIGHT.put(titleBar, h);
            }
        });
    }

    /**
     * 由 {@code BaseWatchActivity.dispatchTouchEvent} 调用：<b>只观察、不消费</b>。
     *
     * <p>下滑超过阈值 → 收起；上滑超过阈值 → 展开。</p>
     */
    public static void observe(Activity activity, MotionEvent ev) {
        if (activity == null || ev == null) {
            return;
        }
        View titleBar = TITLE_BARS.get(activity);
        if (titleBar == null) {
            return;
        }
        // 2026-11-28：设置里可以关掉「标题栏上滑收起」。
        // 判断放在手势入口而不是登记时，改完设置不用重进页面就生效。
        if (!isEnabled(activity)) {
            return;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                DOWN_Y.put(activity, ev.getY());
                // 「无操作」以有没有新的按下为准：手还在操作（滚动/点击）时不断顺延自动展开。
                if (isCollapsed(titleBar)) {
                    scheduleAutoExpand(activity, titleBar);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                Float down = DOWN_Y.remove(activity);
                if (down == null) {
                    break;
                }
                float dy = ev.getY() - down;
                int threshold = WatchUiUtils.px(activity, SWIPE_THRESHOLD_BASE);
                // 需求方 2026-10-17 晚第二次勘误：**上滑 = 收起**（dy 为负）、**下滑 = 展开**（dy 为正）。
                if (dy <= -threshold) {
                    collapse(activity, titleBar);
                } else if (dy >= threshold) {
                    expand(activity, titleBar);
                }
                break;
            }
            default:
                break;
        }
    }

    /** 是否启用「上滑收起」（默认开）。 */
    public static boolean isEnabled(Activity activity) {
        if (activity == null) {
            return true;
        }
        return activity.getSharedPreferences("music163_settings", Activity.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, true);
    }

    /** 页面重新进入时复位为展开（G3 验收：返回再进入时状态复位）。 */    public static void reset(Activity activity) {
        View titleBar = activity == null ? null : TITLE_BARS.get(activity);
        cancelAutoExpand(activity);
        if (titleBar != null) {
            expand(activity, titleBar);
        }
    }

    /** 是否处于收起态（高度为 0）。 */
    public static boolean isCollapsed(View titleBar) {
        ViewGroup.LayoutParams lp = titleBar == null ? null : titleBar.getLayoutParams();
        return lp != null && lp.height == 0;
    }

    // ==================== 收起 / 展开 ====================

    private static void collapse(Activity activity, View titleBar) {
        if (isCollapsed(titleBar)) {
            return;
        }
        int current = titleBar.getHeight();
        if (current > 0) {
            EXPANDED_HEIGHT.put(titleBar, current);
        }
        animateHeight(titleBar, current, 0);
        // 收起后起一个「2.5 秒无操作自动展开」的计时；用户再有按下会顺延（见 observe）。
        scheduleAutoExpand(activity, titleBar);
    }

    private static void expand(Activity activity, View titleBar) {
        cancelAutoExpand(activity);
        Integer target = EXPANDED_HEIGHT.get(titleBar);
        if (target == null || target <= 0) {
            // 还没量到过展开高度：退回 wrap_content，让系统自己量
            setHeight(titleBar, ViewGroup.LayoutParams.WRAP_CONTENT);
            return;
        }
        if (titleBar.getHeight() == target) {
            return;
        }
        animateHeight(titleBar, titleBar.getHeight(), target);
    }

    /**
     * 收起后安排一次「2.5 秒无操作自动展开」。
     *
     * <p>重复调用先取消旧任务再重排：用户每按一下屏幕，倒计时就从头开始 ——
     * 手还在操作时标题栏不该自己弹回来。</p>
     */
    private static void scheduleAutoExpand(final Activity activity, final View titleBar) {
        cancelAutoExpand(activity);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                AUTO_EXPAND_TASKS.remove(activity);
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                // 收起态才需要展开；期间用户已经手动展开的话这次是空操作
                if (isCollapsed(titleBar)) {
                    expand(activity, titleBar);
                }
            }
        };
        AUTO_EXPAND_TASKS.put(activity, task);
        AUTO_EXPAND_HANDLER.postDelayed(task, AUTO_EXPAND_MS);
    }

    /** 取消「自动展开」计时（展开、复位、页面销毁路径都走这里，幂等）。 */
    private static void cancelAutoExpand(Activity activity) {
        if (activity == null) {
            return;
        }
        Runnable task = AUTO_EXPAND_TASKS.remove(activity);
        if (task != null) {
            AUTO_EXPAND_HANDLER.removeCallbacks(task);
        }
    }

    private static void animateHeight(final View titleBar, int from, int to) {
        if (from == to) {
            setHeight(titleBar, to);
            return;
        }
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofInt(from, to);
        animator.setDuration(ANIM_MS);
        animator.addUpdateListener(animation ->
                setHeight(titleBar, (Integer) animation.getAnimatedValue()));
        animator.start();
    }

    private static void setHeight(View view, int height) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null) {
            return;
        }
        if (lp.height != height) {
            lp.height = height;
            view.setLayoutParams(lp);
        }
        // 收起到 0 时顺手隐藏，避免边框/阴影残留一像素
        view.setVisibility(height == 0 ? View.GONE : View.VISIBLE);
    }
}
