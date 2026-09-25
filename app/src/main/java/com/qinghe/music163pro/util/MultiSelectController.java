package com.qinghe.music163pro.util;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
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

        public final String id;
        public final String label;
        public final int textColor;

        public Action(String id, String label, int textColor) {
            this.id = id;
            this.label = label;
            this.textColor = textColor;
        }

        /** 默认的「删除」动作。 */
        public static Action delete(Activity activity) {
            int color = activity.getResources().getColor(R.color.colorError);
            return new Action(ID_DELETE, activity.getString(R.string.multi_select_delete), color);
        }

        /** 默认的「收藏」动作。语义与单曲收藏一致，具体走本地还是云端由页面决定。 */
        public static Action favorite(Activity activity) {
            int color = activity.getResources().getColor(R.color.colorPrimary);
            return new Action(ID_FAVORITE, activity.getString(R.string.multi_select_favorite), color);
        }
    }

    /** 页面回调。 */
    public interface Callback {
        /** 需要展示的动作按钮，按顺序横向排列。 */
        List<Action> getActions();

        /** 某个动作被点击。positions 为已排序的选中下标。 */
        void onAction(Action action, List<Integer> positions);
    }

    private static final int SELECTED_TINT = 0x33BB86FC;
    private static final int CHECK_ACTIVE_COLOR = 0xFFBB86FC;
    private static final int CHECK_INACTIVE_COLOR = 0x80FFFFFF;
    private static final int ACTION_BUTTON_BG = 0xFF2D2D2D;

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
    private TextView headerView;
    private boolean selectionMode;
    private boolean enabled = true;

    private MultiSelectController(Activity activity, ListView listView, Callback callback) {
        this.activity = activity;
        this.listView = listView;
        this.callback = callback;
        this.selectedForeground = new ColorDrawable(SELECTED_TINT);
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
            actionBar.setVisibility(View.GONE);
        }
        notifyRowsChanged();
    }

    // ── 内部逻辑 ────────────────────────────────────────────────────────────

    private void enterSelectionMode(int position) {
        selectionMode = true;
        select(position, true);
        ensureActionBar();
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
        bar.setBackgroundColor(0xF21E1E1E);
        bar.setPadding(px(6), px(4), px(6), px(5));
        bar.setClickable(true);

        bar.addView(buildHeaderRow());

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, px(2), 0, 0);
        for (Action action : actions) {
            actionRow.addView(buildActionButton(action));
        }
        // 最后一个动作按钮不需要右边距，避免动作栏右侧留白。
        if (actionRow.getChildCount() > 0) {
            View last = actionRow.getChildAt(actionRow.getChildCount() - 1);
            ViewGroup.LayoutParams raw = last.getLayoutParams();
            if (raw instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) raw).rightMargin = 0;
                last.setLayoutParams(raw);
            }
        }
        bar.addView(actionRow);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(params);

        content.addView(bar);
        actionBar = bar;
        updateHeaderText();
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
        cancel.setTextColor(activity.getResources().getColor(R.color.colorPrimary));
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

    private View buildActionButton(final Action action) {
        TextView button = new TextView(activity);
        button.setText(action.label);
        button.setTextColor(action.textColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
        button.setGravity(Gravity.CENTER);
        button.setPadding(px(16), px(9), px(16), px(9));
        button.setClickable(true);
        button.setFocusable(true);

        GradientDrawable background = new GradientDrawable();
        background.setColor(ACTION_BUTTON_BG);
        background.setCornerRadius(px(6));
        button.setBackground(background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = px(6);
        button.setLayoutParams(params);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performAction(action);
            }
        });
        return button;
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
