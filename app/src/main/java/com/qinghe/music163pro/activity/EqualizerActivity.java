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

import java.util.ArrayList;
import java.util.List;

/**
 * 音效页（2026-11-28 增强，方案 B）：均衡器 + 低音增强 + 响度增强。
 *
 * <p>均衡器的段数<b>跟随设备</b>（通常 5 段），每段一行步进调节；预设的 3 个锚点会插值展开。</p>
 * <p>只影响播放输出，不改歌曲文件 / 缓存 / 下载文件（需求 2026-11-20）。</p>
 */
public class EqualizerActivity extends BaseWatchActivity {

    private TextView tvSwitchValue;
    private TextView tvPresetValue;
    private LinearLayout bandSection;
    private LinearLayout effectSection;
    /** 每个频段右侧的 dB 文本，下标与段号一致。 */
    private final List<TextView> bandValues = new ArrayList<>();
    private TextView tvBassValue;
    private TextView tvBassStrengthValue;
    private TextView tvLoudValue;
    private TextView tvLoudGainValue;

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
        title.setText("音效");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dpToPx(4), 0, dpToPx(8));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, title);

        // ── 均衡器总开关 ──
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

        // ── 预设 ──
        LinearLayout presetRow = buildRow("预设", presetLabel());
        tvPresetValue = (TextView) presetRow.getChildAt(1);
        presetRow.setOnClickListener(v -> showPresetDialog());
        root.addView(presetRow);

        // ── 频段（段数跟随设备） ──
        root.addView(buildSectionTitle("频段增益"));

        bandSection = new LinearLayout(this);
        bandSection.setOrientation(LinearLayout.VERTICAL);
        root.addView(bandSection);
        buildBandRows();

        // ── 音效（低音 / 响度） ──
        root.addView(buildSectionTitle("音效"));

        effectSection = new LinearLayout(this);
        effectSection.setOrientation(LinearLayout.VERTICAL);
        root.addView(effectSection);
        buildEffectRows();

        TextView hint = new TextView(this);
        hint.setText("只影响播放输出，不改歌曲文件；均衡器设备段数 "
                + EqualizerController.getDeviceBandCount()
                + "，低音增强与响度增强走系统音效，可单独开关。");
        hint.setTextColor(getResources().getColor(R.color.text_secondary));
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        hint.setPadding(dpToPx(8), dpToPx(10), dpToPx(8), 0);
        hint.setLineSpacing(0f, 1.15f);
        root.addView(hint);

        setContentView(scroll);
        refreshBands();
    }

    private TextView buildSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getResources().getColor(R.color.text_secondary));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setPadding(dpToPx(8), dpToPx(10), 0, dpToPx(4));
        return tv;
    }

    /** 按当前段数（设备报的段数）生成每段一行。 */
    private void buildBandRows() {
        bandSection.removeAllViews();
        bandValues.clear();
        int count = EqualizerController.getBandCount(this);
        for (int i = 0; i < count; i++) {
            final int index = i;
            String hz = formatHz(EqualizerController.bandCenterHz(this, i));
            LinearLayout row = buildStepperRow("频段 " + (i + 1), hz,
                    () -> stepBand(index, -1), () -> stepBand(index, 1));
            bandValues.add((TextView) row.getChildAt(1));
            bandSection.addView(row);
        }
    }

    /** 低音增强 / 响度增强两组「开关 + 强度」。 */
    private void buildEffectRows() {
        effectSection.removeAllViews();

        LinearLayout bassRow = buildSwitchRow("低音增强", "补 60Hz 以下，EQ 抬不动的那段");
        tvBassValue = (TextView) bassRow.getChildAt(1);
        bassRow.setOnClickListener(v -> {
            if (!EqualizerController.isBassBoostAvailable()) {
                Toast.makeText(this, "本机不支持低音增强", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean on = !EqualizerController.isBassBoostEnabled(this);
            EqualizerController.setBassBoostEnabled(this, on);
            refreshBands();
        });
        effectSection.addView(bassRow);

        LinearLayout bassStrengthRow = buildStepperRow("低音强度", "0~100%",
                () -> stepBassStrength(-10), () -> stepBassStrength(10));
        tvBassStrengthValue = (TextView) bassStrengthRow.getChildAt(1);
        effectSection.addView(bassStrengthRow);

        LinearLayout loudRow = buildSwitchRow("响度增强", "小音量下更响、更有动态");
        tvLoudValue = (TextView) loudRow.getChildAt(1);
        loudRow.setOnClickListener(v -> {
            if (!EqualizerController.isLoudnessAvailable()) {
                Toast.makeText(this, "本机不支持响度增强", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean on = !EqualizerController.isLoudnessEnabled(this);
            EqualizerController.setLoudnessEnabled(this, on);
            refreshBands();
        });
        effectSection.addView(loudRow);

        LinearLayout loudGainRow = buildStepperRow("响度增益", "0~10 dB",
                () -> stepLoudGain(-1), () -> stepLoudGain(1));
        tvLoudGainValue = (TextView) loudGainRow.getChildAt(1);
        effectSection.addView(loudGainRow);
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

    /** 开关行：左「标题 + 小字说明」，右「开 / 关」（值 TextView 是 child[1]）。 */
    private LinearLayout buildSwitchRow(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        row.setMinimumHeight(dpToPx(52));
        row.setBackground(getResources().getDrawable(R.drawable.bg_card_tile));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setClickable(true);
        row.setFocusable(true);

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
        tvSub.setSingleLine(true);
        tvSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleCol.addView(tvSub);
        row.addView(titleCol);

        TextView tvValue = new TextView(this);
        tvValue.setText("关");
        tvValue.setTextColor(com.qinghe.music163pro.util.ThemeManager.accentColor(this));
        tvValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(tvValue);
        return row;
    }

    /** 步进行：左侧「标题 + 小字说明」两行，右侧值 + 「− / +」按钮。 */
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
        short current = EqualizerController.getBandLevel(this, index);
        int next = current + delta * 10;   // 每步 1.0dB（单位 0.1dB）
        short[] range = EqualizerController.levelRange();
        int lo = range[0];
        int hi = range[1];
        if (next < lo) next = lo;
        if (next > hi) next = hi;
        EqualizerController.setBandLevel(this, index, (short) next);
        refreshBands();
    }

    private void stepBassStrength(int delta) {
        int next = EqualizerController.getBassBoostStrength(this) + delta;
        EqualizerController.setBassBoostStrength(this, next);
        refreshBands();
    }

    private void stepLoudGain(int delta) {
        int next = EqualizerController.getLoudnessGainDb(this) + delta;
        EqualizerController.setLoudnessGainDb(this, next);
        refreshBands();
    }

    // ────────────────────── 刷新 ──────────────────────

    private void refreshSwitch() {
        boolean on = EqualizerController.isEnabled(this);
        if (tvSwitchValue != null) {
            tvSwitchValue.setText(on ? "开" : "关");
        }
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
        for (int i = 0; i < bandValues.size(); i++) {
            TextView tv = bandValues.get(i);
            if (tv == null) continue;
            short v = EqualizerController.getBandLevel(this, i);
            tv.setText(String.format(java.util.Locale.US, "%+.1f dB", v / 10f));
        }
        if (tvPresetValue != null) tvPresetValue.setText(presetLabel());

        boolean bassOn = EqualizerController.isBassBoostEnabled(this);
        if (tvBassValue != null) {
            tvBassValue.setText(EqualizerController.isBassBoostAvailable()
                    ? (bassOn ? "开" : "关") : "不支持");
        }
        if (tvBassStrengthValue != null) {
            tvBassStrengthValue.setText(EqualizerController.getBassBoostStrength(this) + "%");
        }
        boolean loudOn = EqualizerController.isLoudnessEnabled(this);
        if (tvLoudValue != null) {
            tvLoudValue.setText(EqualizerController.isLoudnessAvailable()
                    ? (loudOn ? "开" : "关") : "不支持");
        }
        if (tvLoudGainValue != null) {
            tvLoudGainValue.setText(EqualizerController.getLoudnessGainDb(this) + " dB");
        }
        refreshSwitch();
    }

    /** 60 → 60Hz，910 → 910Hz，3600 → 3.6kHz，14000 → 14kHz。 */
    private String formatHz(int hz) {
        if (hz <= 0) {
            return "";
        }
        if (hz < 1000) {
            return hz + "Hz";
        }
        return String.format(java.util.Locale.US, "%.1fkHz", hz / 1000f);
    }
}
