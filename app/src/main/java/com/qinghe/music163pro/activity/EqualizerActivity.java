package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.EqualizerController;

/**
 * 均衡器设置页：总开关 + 预设 + 低/中/高 三段增益。
 *
 * <p>只影响播放输出，不改歌曲文件 / 缓存 / 下载文件（需求 2026-11-20）。</p>
 */
public class EqualizerActivity extends BaseWatchActivity {

    private TextView tvSwitchValue;
    private TextView tvPresetValue;
    private final TextView[] bandValues = new TextView[3];
    private LinearLayout bandSection;

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
        title.setText("均衡器");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dpToPx(4), 0, dpToPx(8));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 总开关
        LinearLayout switchRow = buildRow("均衡器", "");
        tvSwitchValue = (TextView) switchRow.getChildAt(1);
        refreshSwitch();
        switchRow.setOnClickListener(v -> {
            boolean on = !EqualizerController.isEnabled(this);
            EqualizerController.setEnabled(this, on);
            refreshSwitch();
            refreshBands();
            Toast.makeText(this, on ? "均衡器已开启" : "均衡器已关闭", Toast.LENGTH_SHORT).show();
        });
        root.addView(switchRow);

        // 预设
        LinearLayout presetRow = buildRow("预设", presetLabel());
        tvPresetValue = (TextView) presetRow.getChildAt(1);
        // 需求（2026-11-20）：点开浮窗选择，不再点按循环切下一个
        presetRow.setOnClickListener(v -> showPresetDialog());
        root.addView(presetRow);

        // 三段
        TextView sectionTitle = new TextView(this);
        sectionTitle.setText("频段增益");
        sectionTitle.setTextColor(getResources().getColor(R.color.text_secondary));
        sectionTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        sectionTitle.setPadding(dpToPx(8), dpToPx(10), 0, dpToPx(4));
        root.addView(sectionTitle);

        bandSection = new LinearLayout(this);
        bandSection.setOrientation(LinearLayout.VERTICAL);
        root.addView(bandSection);

        String[] names = {"低音", "中音", "高音"};
        String[] subs = {"60Hz", "1kHz", "12kHz"};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            LinearLayout row = buildStepperRow(names[i], subs[i],
                    () -> stepBand(index, -1), () -> stepBand(index, 1));
            bandValues[i] = (TextView) row.getChildAt(1);
            bandSection.addView(row);
        }

        TextView hint = new TextView(this);
        hint.setText("只影响播放输出，不改歌曲文件；设备段数 " + EqualizerController.getDeviceBandCount()
                + " 段，已映射为低/中/高");
        hint.setTextColor(getResources().getColor(R.color.text_secondary));
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        hint.setPadding(dpToPx(8), dpToPx(10), dpToPx(8), 0);
        hint.setLineSpacing(0f, 1.15f);
        root.addView(hint);

        setContentView(scroll);
        refreshBands();
    }

    // ────────────────────── 行构建 ──────────────────────

    /** 普通行：左标题 + 右值（值 TextView 是 child[1]）。 */
    private LinearLayout buildRow(String title, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        row.setMinimumHeight(dpToPx(48));
        row.setBackground(getResources().getDrawable(R.drawable.bg_card_tile));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setClickable(true);
        row.setFocusable(true);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(getResources().getColor(R.color.text_primary));
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvTitle);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextColor(com.qinghe.music163pro.util.ThemeManager.accentColor(this));
        tvValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(tvValue);
        return row;
    }

    /** 步进行：左侧「标题 + 小字说明」两行，右侧值 + 「− / +」按钮（参考设置页行式）。 */
    private LinearLayout buildStepperRow(String title, String subtitle,
                                         Runnable minus, Runnable plus) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(6), dpToPx(8), dpToPx(6));
        row.setMinimumHeight(dpToPx(56));
        row.setBackground(getResources().getDrawable(R.drawable.bg_card_tile));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题列：标题 + 小字说明（纵向），占满剩余宽度，避免标题被挤成竖排
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleCol.setPadding(0, 0, dpToPx(6), 0);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(getResources().getColor(R.color.text_primary));
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tvTitle.setSingleLine(true);
        titleCol.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText(subtitle);
        tvSub.setTextColor(getResources().getColor(R.color.text_secondary));
        tvSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        tvSub.setPadding(0, dpToPx(1), 0, 0);
        titleCol.addView(tvSub);

        row.addView(titleCol);

        TextView tvValue = new TextView(this);
        tvValue.setText("0.0 dB");
        tvValue.setTextColor(com.qinghe.music163pro.util.ThemeManager.accentColor(this));
        tvValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        tvValue.setGravity(Gravity.CENTER);
        tvValue.setPadding(dpToPx(2), 0, dpToPx(2), 0);
        row.addView(tvValue);

        row.addView(buildStepButton("−", minus));
        row.addView(buildStepButton("+", plus));
        return row;
    }

    private TextView buildStepButton(String label, Runnable action) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(getResources().getColor(R.color.text_primary));
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        btn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(dpToPx(30), dpToPx(30));
        lp.setMarginStart(dpToPx(4));
        btn.setLayoutParams(lp);
        btn.setBackground(getResources().getDrawable(R.drawable.bg_card_tile));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    // ────────────────────── 交互 ──────────────────────

    /** 点开浮窗选预设（当前项高亮），选中后立即应用。 */
    private void showPresetDialog() {
        com.qinghe.music163pro.util.WatchMenuDialog.show(this, "预设",
                EqualizerController.PRESET_NAMES,
                EqualizerController.getPreset(this),
                index -> {
                    EqualizerController.setPreset(this, index);
                    if (tvPresetValue != null) tvPresetValue.setText(presetLabel());
                    refreshBands();
                });
    }

    private void stepBand(int index, int delta) {
        short current = EqualizerController.getBand(this, index);
        int next = current + delta * 10;   // 每步 1.0dB（单位 0.1dB）
        short[] range = EqualizerController.levelRange();
        // 统一限制在 ±12dB（120 个 0.1dB 单位），避免出现 +60dB 这种越界值
        int lo = Math.max(range[0], -120);
        int hi = Math.min(range[1], 120);
        if (next < lo) next = lo;
        if (next > hi) next = hi;
        EqualizerController.setBand(this, index, (short) next);
        refreshBands();
    }

    // ────────────────────── 刷新 ──────────────────────

    private void refreshSwitch() {
        boolean on = EqualizerController.isEnabled(this);
        tvSwitchValue.setText(on ? "开" : "关");
        if (bandSection != null) {
            bandSection.setAlpha(on ? 1f : 0.45f);
        }
    }

    private String presetLabel() {
        int p = EqualizerController.getPreset(this);
        return EqualizerController.PRESET_NAMES[Math.max(0,
                Math.min(p, EqualizerController.PRESET_NAMES.length - 1))];
    }

    private void refreshBands() {
        for (int i = 0; i < 3 && i < bandValues.length; i++) {
            if (bandValues[i] == null) continue;
            short v = EqualizerController.getBand(this, i);
            bandValues[i].setText(String.format(java.util.Locale.US, "%+.1f dB", v / 10f));
        }
        if (tvPresetValue != null) tvPresetValue.setText(presetLabel());
        refreshSwitch();
    }
}
