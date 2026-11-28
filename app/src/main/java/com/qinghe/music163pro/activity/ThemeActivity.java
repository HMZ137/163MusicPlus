package com.qinghe.music163pro.activity;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.ThemeManager;

/**
 * 主题选择（P1-10）。
 *
 * <p>按需求方口径：<b>只做内置主题与主色，不做预览卡片、不做深浅色切换</b>。
 * 点一套主题立即生效并重建本页；其它页面在下次进入时应用（{@code BaseWatchActivity} 统一 setTheme）。</p>
 */
public class ThemeActivity extends BaseWatchActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getResources().getColor(R.color.bg_dark));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(16));
        scroll.addView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("主题");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dpToPx(4), 0, dpToPx(8));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, title);

        int current = ThemeManager.getIndex(this);
        for (int i = 0; i < ThemeManager.NAMES.length; i++) {
            root.addView(buildThemeRow(i, i == current));
        }
        // 自定义色号入口（需求方 2026-11-21）
        root.addView(buildCustomRow());

        TextView hint = new TextView(this);
        hint.setText("主题只改变强调色（按钮、高亮、选中态），不改变背景与文字颜色。\n"
                + "当前：" + ThemeManager.name(this));
        hint.setTextColor(getResources().getColor(R.color.text_secondary));
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        hint.setLineSpacing(0f, 1.2f);
        hint.setPadding(dpToPx(10), dpToPx(12), dpToPx(10), 0);
        root.addView(hint);

        setContentView(scroll);
    }

    /** 列表底部的「自定义色号」入口：显示当前自定义色，点击进输入页。 */
    private LinearLayout buildCustomRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        row.setMinimumHeight(dpToPx(48));
        row.setBackground(getResources().getDrawable(R.drawable.bg_card_tile_ripple));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dpToPx(6);
        row.setLayoutParams(rp);
        row.setClickable(true);
        row.setFocusable(true);

        View swatch = new View(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        int custom = ThemeManager.getCustomColor(this);
        circle.setColor(custom != 0 ? custom : 0xFF3A3A3A);
        swatch.setBackground(circle);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dpToPx(22), dpToPx(22));
        sp.rightMargin = dpToPx(12);
        row.addView(swatch, sp);

        TextView name = new TextView(this);
        name.setText(custom != 0
                ? String.format(java.util.Locale.ROOT, "自定义 #%06X", custom & 0xFFFFFF)
                : "自定义色号…");
        name.setTextColor(getResources().getColor(R.color.text_primary));
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        row.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, CustomThemeActivity.class)));
        return row;
    }

    private LinearLayout buildThemeRow(int index, boolean selected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        row.setMinimumHeight(dpToPx(48));
        row.setBackground(getResources().getDrawable(R.drawable.bg_card_tile));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dpToPx(2);
        row.setLayoutParams(rowParams);
        row.setClickable(true);
        row.setFocusable(true);

        // 色块
        View swatch = new View(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ThemeManager.COLORS[index]);
        swatch.setBackground(circle);
        LinearLayout.LayoutParams swatchParams =
                new LinearLayout.LayoutParams(dpToPx(22), dpToPx(22));
        swatchParams.rightMargin = dpToPx(12);
        row.addView(swatch, swatchParams);

        TextView name = new TextView(this);
        name.setText(ThemeManager.NAMES[index]);
        name.setTextColor(getResources().getColor(R.color.text_primary));
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        TextView mark = new TextView(this);
        mark.setText(selected ? "✓" : "");
        mark.setTextColor(ThemeManager.COLORS[index]);
        mark.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(mark);

        row.setOnClickListener(v -> {
            if (ThemeManager.getIndex(this) == index) return;
            ThemeManager.setIndex(this, index);
            // 重建本页，让新主题立刻可见（其它页面下次进入时生效）
            recreate();
        });
        return row;
    }
}
