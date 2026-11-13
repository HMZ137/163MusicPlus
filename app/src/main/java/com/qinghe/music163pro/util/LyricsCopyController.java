package com.qinghe.music163pro.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 歌词「长按 1 秒 → 进入复制状态 → 多选复制」的公共控制器。
 *
 * <p>主界面歌词页（MainActivity 的 ViewPager 第 1 页）与全屏歌词页（LyricsActivity）
 * 的行结构一样：一个竖直 {@link LinearLayout}，每行一个子 View（原文 + 音译 + 译文）。
 * 把手势计时、选中态、操作条、剪贴板收在这一处，两个页面只负责挂载，避免两套实现走偏。</p>
 *
 * <p><b>为什么要自己计时而不用 GestureDetector.onLongPress</b>：系统长按阈值是 500ms，
 * 需求明确要求 1 秒；而两个歌词页都已经有自己的 GestureDetector（双击跳进度），
 * 再叠一个 detector 会互相抢事件。这里只在 dispatchTouchEvent 里做「观察」，
 * 不消费任何事件，双击 / 滚动 / 翻页全部照旧。</p>
 *
 * <p><b>不中断滚动同步计时器</b>：进入复制态时，页面各自的 scroll runnable 只需问一次
 * {@link #isActive()} 就跳过本轮高亮与自动滚动（仍要重新 post，否则循环会断）。
 * 这样退出复制态时无需重建 Runnable，也不会出现两个同步循环叠加。</p>
 */
public final class LyricsCopyController {

    /** 长按进入复制状态的时长（需求方：1 秒）。 */
    public static final long LONG_PRESS_MS = 1000L;

    private static final int SELECTED_FILL = 0x55BB86FC;
    private static final int SELECTED_STROKE = 0xFFBB86FC;
    private static final int BAR_BG = 0xF01A1A1A;
    private static final int BTN_BG = 0xFF303030;

    /** 操作条挂到页面顶部的方式由各页面自己实现（FrameLayout / RelativeLayout 参数不同）。 */
    public interface BarHost {
        void attach(View bar);

        void detach(View bar);
    }

    private final Context context;
    private final ScrollView scrollView;
    private final LinearLayout rowContainer;
    private final BarHost barHost;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> lines = new ArrayList<>();
    /** 升序集合，复制时天然按歌词顺序输出；不用 List#sort（minSdk 23 上会炸）。 */
    private final TreeSet<Integer> selected = new TreeSet<>();
    private final List<Drawable> originalBackgrounds = new ArrayList<>();
    private final int touchSlop;

    private boolean active;
    private boolean pendingLongPress;
    private float downRawX;
    private float downRawY;
    /**
     * 长按触发的那一次手势，抬手时会走到行 View 的 click —— 必须吞掉，否则刚进复制态
     * 就把长按行反选了。用布尔而不是时间窗：用户可能长按后继续按住很久才抬手，
     * 时间窗会失效；下一次 ACTION_DOWN 才清除。
     */
    private boolean suppressNextClick;
    private LinearLayout actionBar;
    private TextView countLabel;
    private TextView selectAllButton;
    private TextView copyButton;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            pendingLongPress = false;
            if (!active) {
                enterCopyMode();
            }
        }
    };

    public LyricsCopyController(Context context, ScrollView scrollView,
                                LinearLayout rowContainer, BarHost barHost) {
        this.context = context;
        this.scrollView = scrollView;
        this.rowContainer = rowContainer;
        this.barHost = barHost;
        this.touchSlop = Math.max(
                ViewConfiguration.get(context).getScaledTouchSlop(), dp(8));
    }

    /**
     * 更新「每行要复制的文本」。歌词行重建（切歌 / 开关音译翻译 / AI 着色）时必须重新调用；
     * 若当前正在复制态，先退出，避免选中下标打到新行上。
     */
    public void setLines(List<String> newLines) {
        if (active) {
            exit();
        }
        lines.clear();
        if (newLines != null) {
            lines.addAll(newLines);
        }
    }

    public boolean isActive() {
        return active;
    }

    /** 返回键 / 右滑返回：复制态优先消费，只退出复制态而不关页面。 */
    public boolean onBack() {
        if (!active) {
            return false;
        }
        exit();
        return true;
    }

    /** 页面销毁或切走时清理：撤掉计时器、操作条与所有行监听。 */
    public void release() {
        cancelPending();
        exit();
        lines.clear();
    }

    /**
     * 在 Activity 的 dispatchTouchEvent 里调用；<b>只观察，不消费</b>。
     * 未进入复制态时：在歌词滚动区内按下就启动 1 秒计时，移动超过 slop 或抬手即取消。
     * 复制态下不做手势处理，行点击由行自身的 OnClickListener 负责，ScrollView 仍可滚动。
     */
    public void onTouch(MotionEvent event) {
        if (rowContainer == null || lines.isEmpty()) {
            return;
        }
        if (active) {
            // 下一次按下才解除「吞掉抬手点击」；本次长按的 UP 之后不做任何处理
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                suppressNextClick = false;
            }
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!isInsideScrollView(event.getRawX(), event.getRawY())) {
                    break;
                }
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                cancelPending();
                pendingLongPress = true;
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                if (pendingLongPress) {
                    float dx = Math.abs(event.getRawX() - downRawX);
                    float dy = Math.abs(event.getRawY() - downRawY);
                    // 横向也要取消：歌词页是 ViewPager2 的一页，用户可能是在翻页
                    if (Math.max(dx, dy) > touchSlop) {
                        cancelPending();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelPending();
                break;
            default:
                break;
        }
    }

    private void cancelPending() {
        if (pendingLongPress) {
            handler.removeCallbacks(longPressRunnable);
            pendingLongPress = false;
        }
    }

    private boolean isInsideScrollView(float rawX, float rawY) {
        if (scrollView == null || scrollView.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        scrollView.getLocationOnScreen(location);
        return rawX >= location[0] && rawX <= location[0] + scrollView.getWidth()
                && rawY >= location[1] && rawY <= location[1] + scrollView.getHeight();
    }

    private void enterCopyMode() {
        if (rowContainer == null || rowContainer.getChildCount() == 0 || lines.isEmpty()) {
            return;
        }
        active = true;
        selected.clear();
        // 长按的那一行默认选中，进入即可直接「复制」单句
        int anchor = findRowIndexAtRawY(downRawY);
        if (anchor >= 0) {
            selected.add(anchor);
        }
        bindRows();
        buildActionBar();
        if (barHost != null && actionBar != null) {
            barHost.attach(actionBar);
        }
        suppressNextClick = true;
        Toast.makeText(context, "已进入歌词复制：点选多行，或全选后复制",
                Toast.LENGTH_SHORT).show();
    }

    private void bindRows() {
        originalBackgrounds.clear();
        int count = Math.min(rowContainer.getChildCount(), lines.size());
        for (int i = 0; i < count; i++) {
            View row = rowContainer.getChildAt(i);
            originalBackgrounds.add(row.getBackground());
            final int index = i;
            row.setOnClickListener(v -> {
                if (suppressNextClick) {
                    return;
                }
                toggle(index);
            });
            row.setClickable(true);
        }
        refreshVisuals();
    }

    private void toggle(int index) {
        if (selected.contains(index)) {
            selected.remove(index);
        } else {
            selected.add(index);
        }
        refreshVisuals();
    }

    private void toggleSelectAll() {
        if (selected.size() >= Math.min(rowContainer.getChildCount(), lines.size())) {
            selected.clear();
        } else {
            selected.clear();
            int count = Math.min(rowContainer.getChildCount(), lines.size());
            for (int i = 0; i < count; i++) {
                selected.add(i);
            }
        }
        refreshVisuals();
    }

    private void refreshVisuals() {
        int count = Math.min(rowContainer.getChildCount(), lines.size());
        for (int i = 0; i < count; i++) {
            View row = rowContainer.getChildAt(i);
            if (selected.contains(i)) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(SELECTED_FILL);
                bg.setStroke(dp(1), SELECTED_STROKE);
                bg.setCornerRadius(dp(8));
                row.setBackground(bg);
            } else {
                row.setBackground(i < originalBackgrounds.size()
                        ? originalBackgrounds.get(i) : null);
            }
        }
        updateActionBar();
    }

    private void buildActionBar() {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(BAR_BG);
        bar.setPadding(dp(8), dp(4), dp(8), dp(6));

        countLabel = new TextView(context);
        countLabel.setTextColor(0xCCFFFFFF);
        countLabel.setTextSize(10);
        countLabel.setGravity(Gravity.CENTER);
        countLabel.setPadding(0, 0, 0, dp(3));
        bar.addView(countLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        selectAllButton = makeButton("全选", v -> toggleSelectAll());
        copyButton = makeButton("复制", v -> copySelected());
        TextView exitButton = makeButton("退出", v -> exit());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(selectAllButton, buttonParams);
        buttons.addView(copyButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(exitButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        actionBar = bar;
        updateActionBar();
    }

    private TextView makeButton(String label, View.OnClickListener listener) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(11);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(3), dp(6), dp(3), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BTN_BG);
        bg.setCornerRadius(dp(8));
        button.setBackground(bg);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(listener);
        return button;
    }

    private void updateActionBar() {
        if (actionBar == null) {
            return;
        }
        int total = Math.min(rowContainer.getChildCount(), lines.size());
        countLabel.setText("已选 " + selected.size() + " / " + total + " 行 · 点选歌词");
        selectAllButton.setText(selected.size() >= total ? "清空" : "全选");
        copyButton.setText(selected.isEmpty() ? "复制" : "复制(" + selected.size() + ")");
    }

    private void copySelected() {
        if (selected.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(context, "请先选择歌词行");
            return;
        }
        StringBuilder text = new StringBuilder();
        int copied = 0;
        for (Integer index : selected) {
            if (index == null || index < 0 || index >= lines.size()) {
                continue;
            }
            String line = lines.get(index);
            if (line == null || line.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(line);
            copied++;
        }
        if (copied == 0) {
            com.qinghe.music163pro.util.UiKit.toast(context, "所选歌词为空");
            return;
        }
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            com.qinghe.music163pro.util.UiKit.toast(context, "复制失败");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("歌词", text.toString()));
        com.qinghe.music163pro.util.UiKit.toast(context, "已复制 " + copied + " 行歌词");
        exit();
    }

    /** 退出复制态：恢复每行原本的背景与点击状态，移除操作条。 */
    public void exit() {
        if (!active) {
            return;
        }
        active = false;
        cancelPending();
        int count = Math.min(rowContainer.getChildCount(), originalBackgrounds.size());
        for (int i = 0; i < count; i++) {
            View row = rowContainer.getChildAt(i);
            row.setBackground(originalBackgrounds.get(i));
            row.setOnClickListener(null);
            row.setClickable(false);
        }
        originalBackgrounds.clear();
        if (barHost != null && actionBar != null) {
            barHost.detach(actionBar);
        }
        actionBar = null;
        countLabel = null;
        selectAllButton = null;
        copyButton = null;
        selected.clear();
        suppressNextClick = false;
    }

    /** 找出屏幕 rawY 落在哪一行；没落在任何行里时取最近的一行。 */
    private int findRowIndexAtRawY(float rawY) {
        if (rowContainer == null) {
            return -1;
        }
        int nearest = -1;
        float nearestDistance = Float.MAX_VALUE;
        int[] location = new int[2];
        for (int i = 0; i < rowContainer.getChildCount(); i++) {
            View row = rowContainer.getChildAt(i);
            if (row == null || row.getVisibility() != View.VISIBLE) {
                continue;
            }
            row.getLocationOnScreen(location);
            float top = location[1];
            float bottom = top + row.getHeight();
            if (rawY >= top && rawY <= bottom) {
                return i;
            }
            float distance = Math.abs(rawY - (top + row.getHeight() / 2f));
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = i;
            }
        }
        return nearest;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
