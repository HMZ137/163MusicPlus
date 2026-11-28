package com.qinghe.music163pro.util;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.qinghe.music163pro.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 歌曲列表的多选控制器。
 *
 * <p>长按列表项进入多选模式，进入后点击列表项切换选中状态，底部弹出动作栏显示
 * 「已选 N 项」以及若干动作按钮（删除、收藏，后续可以通过 {@link Callback#getActions()}
 * 继续追加）。返回键或「取消」退出多选模式。</p>
 *
 * <p>实现上不要求页面改造自己的 Adapter：控制器会用一层包装 Adapter 包住原有的
 * {@link ListAdapter}，在复用出来的行视图上补充勾选状态与高亮，因此对现有列表渲染
 * 逻辑零侵入（行布局需要包含 {@code R.id.iv_item_check}，见 {@code item_song.xml}）。</p>
 *
 * <p>典型用法：</p>
 * <pre>
 * multiSelect = MultiSelectController.attach(this, lvSongs, new MultiSelectController.Callback() {
 *     public List&lt;Action&gt; getActions() { return Collections.singletonList(Action.delete()); }
 *     public void onAction(Action action, List&lt;Integer&gt; positions) { confirmDelete(positions); }
 * });
 * addBackInterceptor(multiSelect::handleBackPressed);
 * </pre>
 */
public final class MultiSelectController {

    /** 动作栏里的一个动作按钮。 */
    public static final class Action {
        public static final String ID_DELETE = "delete";
        public static final String ID_FAVORITE = "favorite";
        /** 需求 2026-11-21 任务 6：下一首播放（可叠加）。 */
        public static final String ID_PLAY_NEXT = "play_next";

        public final String id;
        public final String label;
        public final int textColor;
        /** 图标资源；0 表示按 id 兜底（见 {@link #defaultIcon(String)}）。 */
        public final int iconRes;

        public Action(String id, String label, int textColor) {
            this(id, label, textColor, 0);
        }

        public Action(String id, String label, int textColor, int iconRes) {
            this.id = id;
            this.label = label;
            this.textColor = textColor;
            this.iconRes = iconRes;
        }

        /** 没有显式给图标时，按动作 id 兜一个（2026-11-28：动作栏改为「图标 + 小字」）。 */
        public int icon() {
            if (iconRes != 0) {
                return iconRes;
            }
            if (ID_DELETE.equals(id)) {
                return R.drawable.ic_delete;
            }
            if (ID_FAVORITE.equals(id)) {
                return R.drawable.ic_favorite_border;
            }
            if (ID_PLAY_NEXT.equals(id)) {
                return R.drawable.ic_skip_next;
            }
            return R.drawable.ic_queue_music;
        }

        /** 默认的「删除」动作。 */
        public static Action delete(Activity activity) {
            int color = activity.getResources().getColor(R.color.colorError);
            return new Action(ID_DELETE, activity.getString(R.string.multi_select_delete), color);
        }

        /** 默认的「收藏」动作。语义与单曲收藏一致，具体走本地还是云端由页面决定。 */
        public static Action favorite(Activity activity) {
            int color = ThemeManager.accentColor(activity);
            return new Action(ID_FAVORITE, activity.getString(R.string.multi_select_favorite), color);
        }

        /** 需求 2026-11-21 任务 6：「下一首播放」，可叠加、同一首不重复。 */
        public static Action playNext(Activity activity) {
            int color = ThemeManager.accentColor(activity);
            return new Action(ID_PLAY_NEXT, "下一首播放", color);
        }
    }

    /** 页面回调。 */
    public interface Callback {
        /** 需要展示的动作按钮，按顺序横向排列。 */
        List<Action> getActions();

        /** 某个动作被点击。positions 为已排序的选中下标。 */
        void onAction(Action action, List<Integer> positions);
    }

    /** 主题强调色（20% 透明）：非 final，由 ThemeManager 刷新（P1-10）。 */
    static int SELECTED_TINT = 0x33BB86FC;
    /** 与 {@code bg_card_row} 的 inset 对齐：左右 6dp / 上下 3dp。 */
    private static final int SELECT_INSET_H_DP = 6;
    private static final int SELECT_INSET_V_DP = 3;
    /** 主题强调色：非 final，由 ThemeManager 刷新（P1-10）。 */
    static int CHECK_ACTIVE_COLOR = 0xFFBB86FC;
    private static final int CHECK_INACTIVE_COLOR = 0x80FFFFFF;
    private static final int ACTION_BUTTON_BG = 0xFF2D2D2D;
    /** 多选动作栏收起后，无操作 2s 自动弹出（与标题栏收缩一致，标题栏是 2.5s）。 */
    private static final long AUTO_EXPAND_MS = 2000L;
    private boolean actionsCollapsed;
    private View collapsedHandle;
    private final android.os.Handler collapseHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable autoExpandTask;

    private final Activity activity;
    private final ListView listView;
    private final Callback callback;
    private final List<Action> actions = new ArrayList<>();
    private final List<Integer> selectedPositions = new ArrayList<>();
    private final Set<Integer> selectedLookup = new LinkedHashSet<>();
    private final Drawable selectedForeground;
    private final SelectionAdapter adapter;

    private final AdapterView.OnItemClickListener originalClickListener;
    private final AdapterView.OnItemLongClickListener originalLongClickListener;

    private LinearLayout actionBar;
    /** 动作按钮区（不含标题行）。滚动时只收这部分，标题「已选 N 首 / 取消」保留。 */
    private LinearLayout actionsContainer;
    /** 设置项：多选动作栏滚动时收起（设置 → 交互与浮层）。 */
    public static final String PREF_SCROLL_COLLAPSE = "multiselect_bar_scroll_collapse";
    private TextView headerView;
    private boolean selectionMode;
    private boolean enabled = true;

    private MultiSelectController(Activity activity, ListView listView, Callback callback) {
        this.activity = activity;
        this.listView = listView;
        this.callback = callback;
        // 选中高亮必须跟随行卡片的圆角：行背景是 bg_card_row —— 由 drawable 自身做
        // 「左右 6dp / 上下 3dp」内缩 + 10dp 圆角（ListView 会忽略 layout_margin，所以
        // 圆角与缩进都只能由 drawable 承担）。早先这里用直角 ColorDrawable 直接铺满整行，
        // 选中时就会出现一块方角、盖到卡片之外的紫色矩形（需求方附图 复选着色_1.png）。
        GradientDrawable selectedShape = new GradientDrawable();
        selectedShape.setColor(SELECTED_TINT);
        selectedShape.setCornerRadius(UiKit.dp(activity, UiKit.DP_ROW_RADIUS));
        this.selectedForeground = new android.graphics.drawable.InsetDrawable(selectedShape,
                UiKit.dp(activity, SELECT_INSET_H_DP), UiKit.dp(activity, SELECT_INSET_V_DP),
                UiKit.dp(activity, SELECT_INSET_H_DP), UiKit.dp(activity, SELECT_INSET_V_DP));
        List<Action> declaredActions = callback.getActions();
        if (declaredActions != null) {
            actions.addAll(declaredActions);
        }

        // 先记录页面原有的点击逻辑，非多选状态下原样转发，保证原有交互不变。
        this.originalClickListener = listView.getOnItemClickListener();
        this.originalLongClickListener = listView.getOnItemLongClickListener();

        this.adapter = new SelectionAdapter(listView.getAdapter());
        listView.setAdapter(adapter);
        listView.setItemsCanFocus(false);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (enabled && selectionMode) {
                    toggle(position);
                } else if (originalClickListener != null) {
                    originalClickListener.onItemClick(parent, view, position, id);
                }
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (enabled) {
                    if (!selectionMode) {
                        enterSelectionMode(position);
                    } else {
                        toggle(position);
                    }
                    return true;
                }
                return originalLongClickListener != null
                        && originalLongClickListener.onItemLongClick(parent, view, position, id);
            }
        });

        // 2026-11-28：滚动列表时把动作按钮区收起来，只看得到「已选 N 首 / 取消」；
        // 停下来再展开。走分发器而不是 setOnScrollListener，避免顶掉封面按需加载。
        ListScrollDispatcher.add(listView, new ListScrollDispatcher.Listener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (!selectionMode) {
                    return;
                }
                setActionsCollapsed(scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE);
            }
        });
    }

    /** 收起 / 展开动作按钮区（受设置开关控制）。 */
    private void setActionsCollapsed(boolean collapsed) {
        if (actionsContainer == null || actionBar == null
                || actionBar.getVisibility() != View.VISIBLE) {
            return;
        }
        boolean allowed = activity.getSharedPreferences("music163_settings", Activity.MODE_PRIVATE)
                .getBoolean(PREF_SCROLL_COLLAPSE, true);
        actionsContainer.setVisibility(collapsed && allowed ? View.GONE : View.VISIBLE);
    }

    /**
     * 为列表挂载多选能力。请在页面设置好自己的点击/长按监听之后再调用，
     * 这样非多选状态下的原有交互才能被正确转发。
     */
    public static MultiSelectController attach(Activity activity, ListView listView, Callback callback) {
        if (activity == null || listView == null || callback == null) {
            return null;
        }
        return new MultiSelectController(activity, listView, callback);
    }

    // ── 对外状态 ────────────────────────────────────────────────────────────

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getSelectedCount() {
        return selectedPositions.size();
    }

    public List<Integer> getSelectedPositions() {
        return new ArrayList<>(selectedPositions);
    }

    /**
     * 开关多选能力。关闭时会自动退出多选模式（例如收藏页切到云端模式时，
     * 云端歌曲不提供批量本地删除）。
     */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (!enabled) {
            exitSelectionMode();
        }
    }

    /** 供页面注册到返回键拦截：多选模式下消费返回事件并退出多选。 */
    public boolean handleBackPressed() {
        if (selectionMode) {
            exitSelectionMode();
            return true;
        }
        return false;
    }

    public void exitSelectionMode() {
        if (!selectionMode) {
            return;
        }
        selectionMode = false;
        selectedPositions.clear();
        selectedLookup.clear();
        if (actionBar != null) {
            expandActions();
            if (collapsedHandle != null) {
                collapsedHandle.setVisibility(View.GONE);
            }
            actionBar.setVisibility(View.GONE);
        }
        setActionsCollapsed(false);
        notifyRowsChanged();
    }

    // ── 内部逻辑 ────────────────────────────────────────────────────────────

    private void enterSelectionMode(int position) {
        selectionMode = true;
        select(position, true);
        ensureActionBar();
        setActionsCollapsed(false);
        notifyRowsChanged();
    }

    private void toggle(int position) {
        boolean nowSelected = selectedLookup.contains(position);
        select(position, !nowSelected);
        if (selectedPositions.isEmpty()) {
            // 一项都不剩时直接退出多选，避免出现「已选 0 项」的空动作栏。
            exitSelectionMode();
            return;
        }
        notifyRowsChanged();
    }

    private void select(int position, boolean selected) {
        if (selected) {
            if (selectedLookup.add(position)) {
                selectedPositions.add(position);
            }
        } else if (selectedLookup.remove(position)) {
            selectedPositions.remove(Integer.valueOf(position));
        }
        updateHeaderText();
    }

    private void notifyRowsChanged() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void updateHeaderText() {
        if (headerView != null) {
            headerView.setText(activity.getString(R.string.multi_select_header, selectedPositions.size()));
        }
    }

    private void ensureActionBar() {
        if (actionBar != null) {
            updateHeaderText();
            actionBar.setVisibility(View.VISIBLE);
            return;
        }
        FrameLayout content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackground(com.qinghe.music163pro.util.UiKit.roundedFill(bar.getContext(), 0xF21E1E1E));
        bar.setPadding(px(6), px(4), px(6), px(5));
        bar.setClickable(true);

        bar.addView(buildHeaderRow());

        // 2026-11-28：动作按钮**只占底下一排**（需求方：原来两列三行占了半个屏幕）。
        // 横向空间不够时靠 shortLabel() 把长标签压到 2~3 字，而不是折行堆高。
        actionsContainer = new LinearLayout(activity);
        actionsContainer.setOrientation(LinearLayout.HORIZONTAL);
        final int perRow = Integer.MAX_VALUE;
        // 2026-11-28：按钮**直接**挂在横向容器下。
        // 之前外面还套了一层「行」，行在横向容器里是 WRAP_CONTENT，按钮的 weight=1 失效，
        // 每个按钮只剩图标那点宽度（实测 19px），文字全被截断 —— 这一层必须去掉。
        actionsContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < actions.size(); i++) {
            actionsContainer.addView(buildActionButton(actions.get(i)));
        }
        // 最后一个动作按钮不需要右边距，避免动作栏右侧留白。
        View last = actionsContainer.getChildAt(actionsContainer.getChildCount() - 1);
        if (last != null) {
            ViewGroup.LayoutParams raw = last.getLayoutParams();
            if (raw instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) raw).rightMargin = 0;
                last.setLayoutParams(raw);
            }
        }
        bar.addView(actionsContainer);

        // 参数必须在 addView 时一起传：先 setLayoutParams 再 addView 在部分容器上会被重新生成，
        // 结果就是「gravity=BOTTOM」丢失、动作条跑到屏幕顶部（两行版本实机复现）。
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        // 左右留白：动作栏本身是圆角 drawable，贴满屏宽时左右两个圆角被切掉，
        // 看起来仍是一条直角横条（实机截图确认）。
        params.leftMargin = px(8);
        params.rightMargin = px(8);
        params.bottomMargin = px(4);

        content.addView(bar, params);
        actionBar = bar;

        // 2026-11-28：收起态只在底部留一条小手柄；上滑手柄 / 2s 无操作都会弹回完整动作栏
        TextView handle = new TextView(activity);
        handle.setText("操作 ▴");
        handle.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11));
        handle.setTextColor(activity.getResources().getColor(R.color.text_primary));
        handle.setGravity(Gravity.CENTER);
        handle.setPadding(px(10), px(4), px(10), px(4));
        handle.setClickable(true);
        handle.setVisibility(View.GONE);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(ACTION_BUTTON_BG);
        handleBg.setCornerRadius(UiKit.dp(activity, UiKit.DP_BUTTON_RADIUS));
        handle.setBackground(handleBg);
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        handleParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = px(4);
        handle.setOnClickListener(v -> expandActions());
        content.addView(handle, handleParams);
        collapsedHandle = handle;

        // 在完整动作栏上下滑 → 收起；上滑 → 展开（与标题栏手势方向一致）
        final android.view.GestureDetector detector = new android.view.GestureDetector(activity,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(android.view.MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                           float vx, float vy) {
                        float dy = e2.getY() - e1.getY();
                        int threshold = px(24);
                        if (dy <= -threshold) {
                            expandActions();
                        } else if (dy >= threshold) {
                            collapseActions();
                        }
                        return true;
                    }
                });
        bar.setOnTouchListener((v, ev) -> detector.onTouchEvent(ev));
        // 按钮格子是 clickable，会吃掉 MOVE/UP，父容器的 OnTouchListener 收不到 fling；
        // 「已选 N 项」这行文字本身不可点，在它上面下滑同样能收起。
        if (headerView != null) {
            headerView.setOnTouchListener((v, ev) -> detector.onTouchEvent(ev));
        }

        updateHeaderText();
    }

    /** 收起：完整动作栏隐藏，底部只留一条手柄。 */
    private void collapseActions() {
        if (actionsCollapsed || actionBar == null) {
            return;
        }
        actionsCollapsed = true;
        actionBar.setVisibility(View.GONE);
        if (collapsedHandle != null) {
            collapsedHandle.setVisibility(View.VISIBLE);
        }
        if (autoExpandTask != null) {
            collapseHandler.removeCallbacks(autoExpandTask);
        }
        autoExpandTask = () -> expandActions();
        collapseHandler.postDelayed(autoExpandTask, AUTO_EXPAND_MS);
    }

    /** 展开：收起手柄隐藏，恢复完整动作栏。 */
    private void expandActions() {
        if (autoExpandTask != null) {
            collapseHandler.removeCallbacks(autoExpandTask);
            autoExpandTask = null;
        }
        if (!actionsCollapsed) {
            return;
        }
        actionsCollapsed = false;
        if (collapsedHandle != null) {
            collapsedHandle.setVisibility(View.GONE);
        }
        if (actionBar != null) {
            actionBar.setVisibility(View.VISIBLE);
        }
    }

    private View buildHeaderRow() {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        headerView = new TextView(activity);
        headerView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        headerView.setTextColor(activity.getResources().getColor(R.color.text_primary));
        header.addView(headerView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cancel = new TextView(activity);
        cancel.setText(R.string.multi_select_cancel);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
        cancel.setTextColor(ThemeManager.accentColor(activity));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(px(12), px(4), px(8), px(4));
        cancel.setClickable(true);
        cancel.setFocusable(true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitSelectionMode();
            }
        });
        header.addView(cancel);
        return header;
    }

    /**
     * 动作按钮（2026-11-28 改版）：**上图标、下小字**，仿 Win11 右键菜单底部的图标条。
     *
     * <p>需求方明确要求「图标下方有小字说明提高辨识度」——图标单看太容易混淆，
     * 所以文字必须留着且要够清楚（11sp，比原来 14sp 单行标签略小但仍有辨识度）。</p>
     */
    /** 底下一排放得下 5 个格子，标签必须压短（图标+小字仍保留辨识度）。 */
    private static String shortLabel(Action action) {
        if (action == null) return "";
        if (Action.ID_PLAY_NEXT.equals(action.id)) return "下一首";
        if (Action.ID_FAVORITE.equals(action.id)) return "收藏";
        if (Action.ID_DELETE.equals(action.id)) return "删除";
        String label = action.label;
        if (label == null) return "";
        if (label.contains("本地歌单")) return "本地";
        if (label.contains("歌单")) return "收藏";
        if (label.contains("下载")) return "下载";
        if (label.contains("移出")) return "移出";
        return label.length() > 2 ? label.substring(0, 2) : label;
    }

    private View buildActionButton(final Action action) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(px(1), px(8), px(1), px(8));
        cell.setClickable(true);
        cell.setFocusable(true);

        GradientDrawable background = new GradientDrawable();
        background.setColor(ACTION_BUTTON_BG);
        background.setCornerRadius(UiKit.dp(activity, UiKit.DP_BUTTON_RADIUS));
        cell.setBackground(background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = px(6);
        cell.setLayoutParams(params);

        ImageView icon = new ImageView(activity);
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(px(20), px(20));
        icon.setLayoutParams(iconParams);
        icon.setImageResource(action.icon());
        icon.setColorFilter(action.textColor);
        cell.addView(icon);

        TextView label = new TextView(activity);
        label.setText(shortLabel(action));
        label.setTextColor(0xFFE6E1F0);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, px(3), 0, 0);
        // 单行 + 截断：保证所有格子等高（标签字数不同也不会把某个格子撑成两行）
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        label.setLayoutParams(labelParams);
        cell.addView(label);

        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performAction(action);
            }
        });
        return cell;
    }

    private void performAction(Action action) {
        if (selectedPositions.isEmpty()) {
            return;
        }
        List<Integer> positions = new ArrayList<>(selectedPositions);
        Collections.sort(positions);
        if (callback != null) {
            callback.onAction(action, positions);
        }
    }

    private void applyRowState(View row, int position) {
        if (row == null) {
            return;
        }
        boolean selected = selectedLookup.contains(position);

        ImageView check = row.findViewById(R.id.iv_item_check);
        if (check != null) {
            if (selectionMode) {
                check.setVisibility(View.VISIBLE);
                check.setImageResource(selected
                        ? R.drawable.ic_check_circle
                        : R.drawable.ic_check_circle_outline);
                check.setColorFilter(selected ? CHECK_ACTIVE_COLOR : CHECK_INACTIVE_COLOR);
                check.setContentDescription(activity.getString(selected
                        ? R.string.multi_select_selected
                        : R.string.multi_select_unselected));
            } else {
                check.setVisibility(View.GONE);
            }
        }

        if (selectionMode) {
            row.setForeground(selected ? selectedForeground : null);
            row.setAlpha(selected ? 1f : 0.55f);
        } else {
            row.setForeground(null);
            row.setAlpha(1f);
        }
    }

    private int px(int baseValue) {
        return WatchUiUtils.px(activity, baseValue);
    }

    /**
     * 包装原有 Adapter：数据来源仍由页面维护，只在 getView 出来的行视图上补勾选状态。
     * 同时把原 Adapter 的数据变化通知转发给 ListView，页面继续调用原有的
     * {@code notifyDataSetChanged()} 即可正常刷新。
     */
    private final class SelectionAdapter extends BaseAdapter {

        private final ListAdapter delegate;
        private final android.database.DataSetObserver observer = new android.database.DataSetObserver() {
            @Override
            public void onChanged() {
                notifyDataSetChanged();
            }

            @Override
            public void onInvalidated() {
                notifyDataSetInvalidated();
            }
        };

        SelectionAdapter(ListAdapter delegate) {
            this.delegate = delegate;
            if (delegate != null) {
                delegate.registerDataSetObserver(observer);
            }
        }

        @Override
        public int getCount() {
            return delegate == null ? 0 : delegate.getCount();
        }

        @Override
        public Object getItem(int position) {
            return delegate == null ? null : delegate.getItem(position);
        }

        @Override
        public long getItemId(int position) {
            return delegate == null ? 0L : delegate.getItemId(position);
        }

        @Override
        public int getItemViewType(int position) {
            return delegate == null ? 0 : delegate.getItemViewType(position);
        }

        @Override
        public int getViewTypeCount() {
            return delegate == null ? 1 : Math.max(1, delegate.getViewTypeCount());
        }

        @Override
        public boolean hasStableIds() {
            return delegate != null && delegate.hasStableIds();
        }

        @Override
        public boolean isEmpty() {
            return delegate == null || delegate.isEmpty();
        }

        @Override
        public boolean areAllItemsEnabled() {
            return delegate == null || delegate.areAllItemsEnabled();
        }

        @Override
        public boolean isEnabled(int position) {
            return delegate == null || delegate.isEnabled(position);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (delegate == null) {
                return convertView;
            }
            View row = delegate.getView(position, convertView, parent);
            applyRowState(row, position);
            return row;
        }
    }
}
