package com.qinghe.music163pro.activity;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.ThemeManager;

/**
 * 自定义主题（P1-10 扩展）：输入 hex 色号，实时预览，应用后立即生效。
 *
 * <p>说明：Android 主题是编译期资源，运行时造不出新 theme，所以这里做的是
 * 「代码侧精确覆盖 + XML 侧取最接近的预设」——见 {@link ThemeManager#styleRes} /
 * {@link ThemeManager#accentColor}。</p>
 */
public class CustomThemeActivity extends BaseWatchActivity {

    private EditText input;
    private View swatch;
    private TextView previewText;
    private TextView hintView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getResources().getColor(R.color.bg_dark));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(16));
        scroll.addView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("自定义主题");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dpToPx(4), 0, dpToPx(10));
        root.addView(title);

        // 输入框
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("#RRGGBB");
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        input.setTextColor(getResources().getColor(R.color.text_primary));
        input.setHintTextColor(getResources().getColor(R.color.text_secondary));
        int cur = ThemeManager.accentColor(this);
        input.setText(String.format(java.util.Locale.ROOT, "#%06X", cur & 0xFFFFFF));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0xFF2D2D2D);
        inputBg.setCornerRadius(dpToPx(10));
        input.setBackground(inputBg);
        input.setPadding(dpToPx(12), dpToPx(9), dpToPx(12), dpToPx(9));
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 预览卡片：色块 + 示例文字
        LinearLayout previewCard = new LinearLayout(this);
        previewCard.setOrientation(LinearLayout.HORIZONTAL);
        previewCard.setGravity(Gravity.CENTER_VERTICAL);
        previewCard.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1E1F24);
        cardBg.setCornerRadius(dpToPx(14));
        previewCard.setBackground(cardBg);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dpToPx(10);
        previewCard.setLayoutParams(cardParams);

        swatch = new View(this);
        LinearLayout.LayoutParams swatchParams =
                new LinearLayout.LayoutParams(dpToPx(36), dpToPx(36));
        swatchParams.rightMargin = dpToPx(12);
        previewCard.addView(swatch, swatchParams);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        previewText = new TextView(this);
        previewText.setText("主题色预览");
        previewText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        textCol.addView(previewText);

        hintView = new TextView(this);
        hintView.setText("按钮、高亮、进度条会变成这个颜色");
        hintView.setTextColor(getResources().getColor(R.color.text_secondary));
        hintView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        hintView.setPadding(0, dpToPx(2), 0, 0);
        textCol.addView(hintView);

        previewCard.addView(textCol);
        root.addView(previewCard);

        // 应用按钮
        TextView apply = new TextView(this);
        apply.setText("应用");
        apply.setTextColor(0xFF1D1D1D);
        apply.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        apply.setGravity(Gravity.CENTER);
        apply.setPadding(0, dpToPx(11), 0, dpToPx(11));
        GradientDrawable applyBg = new GradientDrawable();
        applyBg.setColor(ThemeManager.accentColor(this));
        applyBg.setCornerRadius(dpToPx(12));
        apply.setBackground(applyBg);
        apply.setClickable(true);
        apply.setFocusable(true);
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        applyParams.topMargin = dpToPx(12);
        apply.setLayoutParams(applyParams);
        apply.setOnClickListener(v -> applyCustom());
        root.addView(apply);

        setContentView(scroll);

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                updatePreview(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        updatePreview(input.getText().toString());
    }

    /** 实时预览：解析成功就刷色块与文字色，失败标红提示。 */
    private void updatePreview(String raw) {
        Integer color = parseHex(raw);
        if (color == null) {
            hintView.setText("色号格式不对，示例：#39C5BB / #FC0");
            hintView.setTextColor(0xFFFF7B72);
            if (swatch != null) {
                GradientDrawable bad = new GradientDrawable();
                bad.setShape(GradientDrawable.OVAL);
                bad.setColor(0xFF3A3A3A);
                swatch.setBackground(bad);
            }
            if (previewText != null) {
                previewText.setTextColor(getResources().getColor(R.color.text_secondary));
            }
            return;
        }
        if (swatch != null) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(color);
            swatch.setBackground(dot);
        }
        if (previewText != null) {
            previewText.setTextColor(color);
        }
        if (hintView != null) {
            hintView.setText(String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF));
            hintView.setTextColor(getResources().getColor(R.color.text_secondary));
        }
    }

    private void applyCustom() {
        Integer color = parseHex(input.getText().toString());
        if (color == null) {
            Toast.makeText(this, "色号格式不对，示例：#39C5BB", Toast.LENGTH_SHORT).show();
            return;
        }
        ThemeManager.setCustomColor(this, color);
        Toast.makeText(this, "已应用自定义主题", Toast.LENGTH_SHORT).show();
        // 回主题页并重建，让新颜色立刻可见
        recreate();
    }

    /** 支持 #RGB / #RRGGBB / 不带 #，统一返回不透明 ARGB；非法返回 null。 */
    private Integer parseHex(String raw) {
        if (raw == null) return null;
        String t = raw.trim().replace("#", "").replace(" ", "");
        if (t.length() == 3) {
            t = "" + t.charAt(0) + t.charAt(0)
                    + t.charAt(1) + t.charAt(1)
                    + t.charAt(2) + t.charAt(2);
        }
        if (t.length() != 6) return null;
        try {
            return (int) (Long.parseLong(t, 16) | 0xFF000000L);
        } catch (Exception e) {
            return null;
        }
    }
}
