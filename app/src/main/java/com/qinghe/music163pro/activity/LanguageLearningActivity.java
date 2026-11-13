package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.AiLyricApi;
import com.qinghe.music163pro.util.AiLyricCache;
import com.qinghe.music163pro.util.AiLyricSettings;
import com.qinghe.music163pro.util.UiKit;
import com.qinghe.music163pro.util.WatchBackButton;

import java.util.Locale;

/**
 * 语言学习模式设置页。
 *
 * <p>内容：总开关、DeepSeek API Key（混淆存储）、模型名（默认 deepseek-flash，可自行填写）、
 * 测试连接、缓存与用量统计（含清空）、词类配色图例、隐私说明。</p>
 */
public class LanguageLearningActivity extends BaseWatchActivity {

    private Switch switchEnabled;
    private TextView tvKeyValue;
    private TextView tvModelValue;
    private TextView tvStatsValue;
    private TextView tvTestState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF121212);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(8), px(6), px(8), px(14));
        scroll.addView(root);
        setContentView(scroll);

        // 标题栏（给浮动返回按钮留占位）
        android.widget.RelativeLayout titleBar = new android.widget.RelativeLayout(this);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, titleBar);
        TextView title = new TextView(this);
        title.setText("高级歌词翻译");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        android.widget.RelativeLayout.LayoutParams titleParams =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
titleParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        title.setLayoutParams(titleParams);
        titleBar.addView(title);
        // G5：标题居左；避让由统一入口按返回按钮占位动态补齐（勿手写像素）
        WatchBackButton.alignTitleStart(this, title);
        root.addView(titleBar);

        // ── 总开关 ──
        LinearLayout switchRow = cardRow();
        LinearLayout switchText = textColumn();
        switchText.addView(label("高级歌词翻译"));
        switchText.addView(hint("歌词按词性着色；没有翻译的歌交给 AI 翻译"));
        switchRow.addView(switchText);
        switchEnabled = new Switch(this);
        switchEnabled.setChecked(AiLyricSettings.isEnabled(this));
        switchEnabled.setOnCheckedChangeListener((btn, checked) -> {
            if (!checked) {
                AiLyricSettings.setEnabled(this, false);
                toast("已关闭高级歌词翻译（歌词页仍可用「AI」按钮单次激活）");
                return;
            }
            if (!AiLyricSettings.hasApiKey(this)) {
                btn.setChecked(false);
                toast("请先填写 DeepSeek API Key");
                showKeyDialog();
                return;
            }
            AiLyricSettings.setEnabled(this, true);
            toast("已开启高级歌词翻译");
        });
        switchRow.addView(switchEnabled);
        root.addView(switchRow);

        // ── API Key ──
        LinearLayout keyRow = cardRow();
        LinearLayout keyText = textColumn();
        keyText.addView(label("DeepSeek API Key"));
        keyText.addView(hint("混淆保存在本机（非明文）"));
        keyRow.addView(keyText);
        tvKeyValue = valueText(AiLyricSettings.maskedApiKey(this));
        keyRow.addView(tvKeyValue);
        keyRow.setOnClickListener(v -> showKeyDialog());
        root.addView(keyRow);

        // ── 模型 ──
        LinearLayout modelRow = cardRow();
        LinearLayout modelText = textColumn();
        modelText.addView(label("模型"));
        modelText.addView(hint("默认 deepseek-flash，可自行填写其它模型名"));
        modelRow.addView(modelText);
        tvModelValue = valueText(AiLyricSettings.getModel(this));
        modelRow.addView(tvModelValue);
        modelRow.setOnClickListener(v -> showModelDialog());
        root.addView(modelRow);

        // ── 测试连接 ──
        LinearLayout testRow = cardRow();
        LinearLayout testText = textColumn();
        testText.addView(label("测试连接"));
        testText.addView(hint("发一条最小请求，验证 Key / 模型 / 网络"));
        testRow.addView(testText);
        tvTestState = valueText("未测试");
        testRow.addView(tvTestState);
        testRow.setOnClickListener(v -> runTest());
        root.addView(testRow);

        // ── 缓存与用量 ──
        LinearLayout statsRow = cardRow();
        LinearLayout statsText = textColumn();
        statsText.addView(label("缓存与用量"));
        statsText.addView(hint("同一首歌只分析一次；缓存放在 /sdcard/163MusicPro/ai_lyrics"));
        statsRow.addView(statsText);
        tvStatsValue = valueText("");
        statsRow.addView(tvStatsValue);
        statsRow.setOnClickListener(v -> showCacheMenu());
        root.addView(statsRow);

        // ── 词类配色图例 ──
        root.addView(sectionTitle("词类配色"));
        addLegend(root, "代词", 0xFF4C9AFF);
        addLegend(root, "动词", 0xFFFF6B6B);
        addLegend(root, "名词", 0xFF37C978);
        addLegend(root, "形容词", 0xFFB57BFF);
        addLegend(root, "副词", 0xFF2FD3C9);
        addLegend(root, "介词 / 连词", 0xFFFFC145);
        addLegend(root, "短语 / 习语", 0xFFE0A33E);
        addLegend(root, "其它（助词 / 语气词等）", 0xFF8C9BB5);

        root.addView(sectionTitle("说明"));
        TextView note = hint("开启后，分析某首歌时会把它的歌词、歌名、歌手、专辑发送到 DeepSeek 服务器；"
                + "结果缓存在手表上，同一首歌不会重复消耗 token。关闭本开关后，歌词页右上角的「AI」按钮"
                + "仍可对当前这首歌单次激活。");
        note.setPadding(px(10), 0, px(10), px(6));
        root.addView(note);
    }

    // ==================== 行 / 文本构件 ====================

    private LinearLayout cardRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card_tile);
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(px(48));
        row.setPadding(px(12), px(8), px(12), px(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, px(2), 0, px(2));
        row.setLayoutParams(lp);
        return row;
    }

    private LinearLayout textColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return col;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        return tv;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0x8AFFFFFF);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        return tv;
    }

    private TextView valueText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getResources().getColor(R.color.colorPrimary));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tv.setGravity(Gravity.END);
        tv.setMaxLines(2);
        return tv;
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0x80FFFFFF);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        tv.setPadding(px(12), px(12), px(12), px(4));
        return tv;
    }

    private void addLegend(LinearLayout root, String name, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(16), px(3), px(16), px(3));

        View swatch = new View(this);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(UiKit.dp(this, 3));
        swatch.setBackground(bg);
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(px(18), px(12));
        swatchLp.setMarginEnd(px(10));
        row.addView(swatch, swatchLp);

        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextColor(0xCCFFFFFF);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        row.addView(tv);
        root.addView(row);
    }

    // ==================== 交互 ====================

    @Override
    protected void onResume() {
        super.onResume();
        refreshValues();
    }

    private void refreshValues() {
        if (tvKeyValue != null) {
            tvKeyValue.setText(AiLyricSettings.maskedApiKey(this));
        }
        if (tvModelValue != null) {
            tvModelValue.setText(AiLyricSettings.getModel(this));
        }
        if (tvStatsValue != null) {
            tvStatsValue.setText(String.format(Locale.CHINA, "%d 首 · %s\n累计 %d 次 / %d tokens",
                    AiLyricCache.countSongs(), AiLyricCache.sizeText(),
                    AiLyricSettings.getTotalRequests(this), AiLyricSettings.getTotalTokens(this)));
        }
        if (switchEnabled != null) {
            switchEnabled.setChecked(AiLyricSettings.isEnabled(this));
        }
    }

    private void showKeyDialog() {
        // P5：统一浮窗；3 个按钮（保存 / 清空 / 取消）竖排，输入栏带 ×
        com.qinghe.music163pro.util.WatchPopup popup =
                com.qinghe.music163pro.util.WatchPopup.create(this)
                        .title("DeepSeek API Key")
                        .description("仅保存在本机（混淆存储）")
                        .input("sk-…", AiLyricSettings.getApiKey(this));
        popup.cancelButton();
        popup.button("清空", com.qinghe.music163pro.util.WatchPopup.STYLE_SECONDARY, () -> {
            AiLyricSettings.setApiKey(this, "");
            refreshValues();
        });
        popup.primaryButton("保存", text -> {
            AiLyricSettings.setApiKey(this, text == null ? "" : text);
            refreshValues();
            toast("已保存（混淆存储）");
        });
        popup.show();
    }

    private void showModelDialog() {
        // P5：统一浮窗；3 个按钮（保存 / 恢复默认 / 取消）竖排，输入栏带 ×
        com.qinghe.music163pro.util.WatchPopup popup =
                com.qinghe.music163pro.util.WatchPopup.create(this)
                        .title("模型名")
                        .description("默认：" + AiLyricSettings.DEFAULT_MODEL)
                        .input(AiLyricSettings.DEFAULT_MODEL, AiLyricSettings.getModel(this));
        popup.cancelButton();
        popup.button("恢复默认", com.qinghe.music163pro.util.WatchPopup.STYLE_SECONDARY, () -> {
            AiLyricSettings.setModel(this, AiLyricSettings.DEFAULT_MODEL);
            refreshValues();
        });
        popup.primaryButton("保存", text -> {
            AiLyricSettings.setModel(this, text == null ? "" : text);
            refreshValues();
        });
        popup.show();
    }

    private void runTest() {
        if (!AiLyricSettings.hasApiKey(this)) {
            toast("请先填写 API Key");
            return;
        }
        tvTestState.setText("测试中…");
        AiLyricApi.testConnection(this, new AiLyricApi.Callback() {
            @Override
            public void onResult(AiLyricApi.Reply reply) {
                tvTestState.setText("连接正常");
                toast("连接正常（模型：" + AiLyricSettings.getModel(LanguageLearningActivity.this) + "）");
                refreshValues();
            }

            @Override
            public void onError(String message) {
                tvTestState.setText("失败");
                toast("测试失败：" + message);
                refreshValues();
            }
        });
    }

    private void showCacheMenu() {
        com.qinghe.music163pro.util.WatchMenuDialog.show(this,
                "缓存与用量（" + AiLyricCache.countSongs() + " 首 · " + AiLyricCache.sizeText() + "）",
                new String[]{"清空 AI 歌词缓存", "重置用量统计"}, which -> {
                    if (which == 0) {
                        com.qinghe.music163pro.util.WatchConfirmDialog.show(this, "清空缓存",
                                "将删除 /sdcard/163MusicPro/ai_lyrics 下的全部分析结果，下次打开需要重新消耗 token。",
                                () -> {
                                    AiLyricCache.clearAll();
                                    refreshValues();
                                    toast("缓存已清空");
                                }, null);
                    } else {
                        AiLyricSettings.resetUsage(this);
                        refreshValues();
                        toast("用量统计已重置");
                    }
                });
    }

    private void toast(String msg) {
        com.qinghe.music163pro.util.UiKit.toast(this, msg);
    }
}
