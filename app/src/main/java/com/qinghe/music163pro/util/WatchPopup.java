package com.qinghe.music163pro.util;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.qinghe.music163pro.R;

import java.util.ArrayList;
import java.util.List;

/**
 * P5：全 App 统一浮窗（按需求方参考图 `浮窗改进_1.bmp`）。
 *
 * <p>参考图逐条落地：</p>
 * <ol>
 *   <li><b>大圆角卡片</b>：底色 {@link UiKit#dialogCard}（14dp 圆角），左右各留 16dp 外边距；</li>
 *   <li><b>标题居中</b>；</li>
 *   <li>标题下可选一行<b>简要说明</b>——<b>没有就不加</b>；</li>
 *   <li>标题/说明与内容之间一条<b>分隔线</b>；</li>
 *   <li><b>输入栏</b>：圆角输入框，右侧带 <b>×</b>（清空）；</li>
 *   <li>可选<b>复选框</b>行——<b>没有就不加</b>；</li>
 *   <li>底部<b>圆角按钮</b>，数量<b>可以多于两个</b>（2 个横排，3 个及以上竖排）。</li>
 * </ol>
 *
 * <p>用法（链式）：</p>
 * <pre>
 * WatchPopup.create(this)
 *         .title("新建本地歌单")
 *         .input("输入歌单名称", "")
 *         .primary("创建", text -&gt; { ... })
 *         .cancelButton()
 *         .show();
 * </pre>
 */
public final class WatchPopup {

    /** 按钮样式。 */
    public static final int STYLE_PRIMARY = 0;
    public static final int STYLE_SECONDARY = 1;
    /** 危险动作（删除 / 移出）：红字 + 红描边。 */
    public static final int STYLE_DANGER = 2;

    /** 输入框回调。 */
    public interface OnTextResult {
        void onResult(String text);
    }

    private static final int OVERLAY_COLOR = 0xCC000000;
    private static final String OVERLAY_TAG = "watch_popup_overlay";

    private static final class Btn {
        final String label;
        final int style;
        final Runnable action;
        final boolean closeOnClick;
        final OnTextResult textAction;

        Btn(String label, int style, Runnable action, boolean closeOnClick, OnTextResult textAction) {
            this.label = label;
            this.style = style;
            this.action = action;
            this.closeOnClick = closeOnClick;
            this.textAction = textAction;
        }
    }

    private final Activity activity;
    private String title;
    private String description;
    private String content;
    private boolean withInput;
    private String inputHint = "";
    private String inputInitial = "";
    private boolean withCheckbox;
    private String checkboxText = "";
    private boolean checkboxChecked;
    private final List<Btn> buttons = new ArrayList<>();
    private boolean dismissOnOutsideTap = true;

    private EditText input;
    private CheckBox checkbox;

    private WatchPopup(Activity activity) {
        this.activity = activity;
    }

    public static WatchPopup create(Activity activity) {
        return new WatchPopup(activity);
    }

    // ── 链式配置 ────────────────────────────────────────────────────────────

    public WatchPopup title(String title) {
        this.title = title;
        return this;
    }

    /** 标题下的「简要说明」；传 null / 空串表示不需要（按参考图「没有就不加」）。 */
    public WatchPopup description(String description) {
        this.description = description;
        return this;
    }

    /** 正文（确认框的提示文案）；与 input 可同时使用（说明在上、输入栏在下）。 */
    public WatchPopup content(String content) {
        this.content = content;
        return this;
    }

    /** 输入栏（右侧带 × 清空）。 */
    public WatchPopup input(String hint, String initial) {
        this.withInput = true;
        this.inputHint = hint == null ? "" : hint;
        this.inputInitial = initial == null ? "" : initial;
        return this;
    }

    /** 复选框行（参考图：「没有就不加」）。 */
    public WatchPopup checkbox(String text, boolean checked) {
        this.withCheckbox = true;
        this.checkboxText = text == null ? "" : text;
        this.checkboxChecked = checked;
        return this;
    }

    /** 追加一个按钮；<b>可以多于两个</b>。 */
    public WatchPopup button(String label, int style, Runnable action) {
        buttons.add(new Btn(label, style, action, true, null));
        return this;
    }

    /** 确认按钮：点击时把输入框文本带回（无输入栏时 text 为空串）。 */
    public WatchPopup primaryButton(String label, OnTextResult action) {
        buttons.add(new Btn(label, STYLE_PRIMARY, null, true, action));
        return this;
    }

    public WatchPopup dangerButton(String label, Runnable action) {
        buttons.add(new Btn(label, STYLE_DANGER, action, true, null));
        return this;
    }

    public WatchPopup cancelButton() {
        buttons.add(new Btn("取消", STYLE_SECONDARY, null, true, null));
        return this;
    }

    public WatchPopup dismissOnOutsideTap(boolean dismiss) {
        this.dismissOnOutsideTap = dismiss;
        return this;
    }

    /**
     * 复选框是否被勾选（没有配置复选框时恒为 false）。
     *
     * <p>按钮回调只带输入框文本，需要复选状态时在回调里取本方法即可
     * （弹层点击按钮后先 dismiss 再执行回调，此时复选框实例与状态仍可读）。</p>
     */
    public boolean isCheckboxChecked() {
        return checkbox != null && checkbox.isChecked();
    }

    // ── 渲染 ────────────────────────────────────────────────────────────────

    public void show() {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        FrameLayout rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) {
            return;
        }
        dismissExisting(rootView);

        final FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag(OVERLAY_TAG);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(OVERLAY_COLOR);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        final LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiKit.dialogCard(activity));   // 14dp 大圆角
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        cardParams.leftMargin = dp(16);
        cardParams.rightMargin = dp(16);
        card.setLayoutParams(cardParams);
        card.setClickable(true);

        boolean hasHeader = !TextUtils.isEmpty(title) || !TextUtils.isEmpty(description);
        if (!TextUtils.isEmpty(title)) {
            card.addView(createTitle());
        }
        if (!TextUtils.isEmpty(description)) {
            card.addView(createDescription());
        }
        boolean hasBody = !TextUtils.isEmpty(content) || withInput || withCheckbox;
        if (hasHeader && hasBody) {
            card.addView(createDivider());
        }
        if (!TextUtils.isEmpty(content)) {
            card.addView(createContent());
        }
        if (withInput) {
            card.addView(createInputRow());
        }
        if (withCheckbox) {
            card.addView(createCheckboxRow());
        }

        // 底部按钮：2 个横排（参考图形态），>=3 个竖排（手表窄屏）
        if (!buttons.isEmpty()) {
            LinearLayout buttonArea = new LinearLayout(activity);
            boolean horizontal = buttons.size() == 2;
            buttonArea.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            // 参考图（浮窗1/浮窗2）：按钮靠右（由下面的 weight 占位实现），垂直居中
            buttonArea.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams areaParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            areaParams.topMargin = dp(10);
            buttonArea.setLayoutParams(areaParams);
            if (horizontal) {
                // 水平 LinearLayout 里 Gravity.END 遇到 WRAP_CONTENT 子项时不可靠
                // （实测「确定」会被右边界裁掉），用一个 weight=1 的空 View 把按钮推到右侧。
                View spacer = new View(activity);
                buttonArea.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            }
            for (int i = 0; i < buttons.size(); i++) {
                buttonArea.addView(createButton(overlay, buttons.get(i), horizontal, i));
            }
            card.addView(buttonArea);
        }

        overlay.addView(card);
        overlay.setOnClickListener(v -> {
            if (dismissOnOutsideTap) {
                dismiss(overlay);
            }
        });
        card.setOnClickListener(v -> {
            // 消费卡片内点击，避免冒泡到遮罩导致关闭
        });
        rootView.addView(overlay);

        if (input != null) {
            input.requestFocus();
        }
    }

    private TextView createTitle() {
        TextView tv = new TextView(activity);
        tv.setText(title);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiKit.sp(activity, 12f));
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        // 参考图（浮窗1/浮窗2）：标题左对齐，而不是居中
        tv.setGravity(Gravity.START);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    private TextView createDescription() {
        TextView tv = new TextView(activity);
        tv.setText(description);
        tv.setTextColor(0xB3FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiKit.textCaptionPx(activity));
        tv.setGravity(Gravity.START);   // 与标题一致左对齐
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    private View createDivider() {
        View divider = new View(activity);
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(8);
        divider.setLayoutParams(lp);
        return divider;
    }

    private TextView createContent() {
        TextView tv = new TextView(activity);
        tv.setText(content);
        tv.setTextColor(0xB3FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiKit.textBodyPx(activity));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    /** 输入栏：圆角输入框 + 右侧 ×（清空）。 */
    private View createInputRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(6);
        row.setLayoutParams(rowParams);

        input = new EditText(activity);
        input.setHint(inputHint);
        input.setText(inputInitial);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0x80FFFFFF);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiKit.textBodyPx(activity));
        // 参考图（浮窗2）：输入区只有一条下划线，没有圆角色块
        input.setPadding(dp(2), dp(8), dp(2), dp(8));
        input.setBackgroundResource(R.drawable.bg_input_underline);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(input, inputParams);

        ImageView clear = new ImageView(activity);
        clear.setImageResource(R.drawable.ic_close);
        clear.setColorFilter(0x80FFFFFF);
        clear.setPadding(dp(8), dp(8), dp(8), dp(8));
        clear.setClickable(true);
        clear.setFocusable(true);
        clear.setContentDescription("清空");
        clear.setOnClickListener(v -> input.setText(""));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        clearParams.leftMargin = dp(6);
        row.addView(clear, clearParams);
        return row;
    }

    /** 复选框行（参考图「没有就不加」）。 */
    private View createCheckboxRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(4));

        checkbox = new CheckBox(activity);
        checkbox.setText(checkboxText);
        checkbox.setChecked(checkboxChecked);
        checkbox.setTextColor(0xB3FFFFFF);
        checkbox.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiKit.textSubPx(activity));
        checkbox.setButtonTintList(android.content.res.ColorStateList.valueOf(UiKit.COLOR_ACCENT));
        checkbox.setPadding(0, 0, 0, 0);
        row.addView(checkbox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> checkbox.toggle());
        return row;
    }

    private TextView createButton(final FrameLayout overlay, final Btn btn,
                                  boolean horizontal, int index) {
        // 参考图：底部是「文字按钮」（透明底、主色/危险色文字、右对齐），不是等宽实心块
        TextView button = new TextView(activity);
        button.setText(btn.label);
        button.setTextColor(btn.style == STYLE_DANGER ? 0xFFCF6679 : UiKit.COLOR_ACCENT);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiKit.sp(activity, 12f));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        button.setBackgroundResource(R.drawable.bg_selectable_rounded);
        button.setClickable(true);
        button.setFocusable(true);
        LinearLayout.LayoutParams lp;
        if (horizontal) {
            lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(UiKit.DP_BUTTON_HEIGHT));
            lp.leftMargin = dp(8);
        } else {
            lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(UiKit.DP_BUTTON_HEIGHT));
            if (index > 0) {
                lp.topMargin = dp(6);
            }
        }
        button.setLayoutParams(lp);
        button.setOnClickListener(v -> {
            String text = input == null ? "" : input.getText().toString().trim();
            if (btn.closeOnClick) {
                dismiss(overlay);
            }
            if (btn.textAction != null) {
                btn.textAction.onResult(text);
            } else if (btn.action != null) {
                btn.action.run();
            }
        });
        return button;
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private int dp(int value) {
        return UiKit.dp(activity, value);
    }

    private static void dismiss(FrameLayout overlay) {
        if (overlay == null || overlay.getParent() == null) {
            return;
        }
        ((ViewGroup) overlay.getParent()).removeView(overlay);
    }

    private static void dismissExisting(FrameLayout rootView) {
        View existing = rootView.findViewWithTag(OVERLAY_TAG);
        if (existing != null) {
            rootView.removeView(existing);
        }
    }

    // ── 快捷入口（覆盖大多数场景） ──────────────────────────────────────────

    /** 确认框：标题 + 正文 + 取消/确认（圆角按钮，取消在左、确认在右）。 */
    public static void confirm(Activity activity, String title, String message,
                               String confirmLabel, boolean danger, Runnable onConfirm) {
        WatchPopup popup = create(activity)
                .title(title)
                .content(message)
                .cancelButton();
        if (danger) {
            popup.dangerButton(confirmLabel, onConfirm);
        } else {
            popup.button(confirmLabel, STYLE_PRIMARY, onConfirm);
        }
        popup.show();
    }

    /** 输入框（如改名 / 新建 / 搜索）：标题 + 可选说明 + 输入栏 + 取消/确认。 */
    public static void input(Activity activity, String title, String description,
                             String hint, String initial, String confirmLabel,
                             OnTextResult onResult) {
        create(activity)
                .title(title)
                .description(description)
                .input(hint, initial)
                .cancelButton()
                .primaryButton(confirmLabel, onResult)
                .show();
    }

    /** 动作列表（多于两个按钮时自动竖排，用于「移除 / 下一首播放」这类长按菜单）。 */
    public static void actions(Activity activity, String title, String description,
                               String[] labels, Runnable[] actions) {
        WatchPopup popup = create(activity).title(title).description(description);
        for (int i = 0; i < labels.length; i++) {
            Runnable action = actions != null && i < actions.length ? actions[i] : null;
            popup.button(labels[i], STYLE_SECONDARY, action);
        }
        popup.cancelButton();
        popup.show();
    }

    /**
     * 兼容层：旧 {@code WatchConfirmDialog} 的「取消 / 确定」实现（P5 起两者共用同一套渲染）。
     */
    public static void popup(Activity activity, String title, String description, String content,
                             boolean dismissOnOutsideTap, boolean primaryStyle,
                             boolean danger, Runnable onConfirm) {
        WatchPopup popup = create(activity)
                .title(title)
                .description(description)
                .content(content)
                .dismissOnOutsideTap(dismissOnOutsideTap)
                .cancelButton();
        if (danger) {
            popup.dangerButton("确定", onConfirm);
        } else {
            popup.button("确定", primaryStyle ? STYLE_PRIMARY : STYLE_SECONDARY, onConfirm);
        }
        popup.show();
    }
}
