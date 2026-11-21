package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.ThemeManager;
import com.qinghe.music163pro.util.UiKit;

/**
 * 设置分类页（需求 2026-11-21 重构）：一个页面按 {@link #EXTRA_PAGE} 渲染五类之一，
 * 避免为每类复制一整页代码。
 *
 * <p>page 取值：appearance / playback / interaction / data / about。</p>
 */
public class SettingsPageActivity extends BaseWatchActivity {

    public static final String EXTRA_PAGE = "settings_page";

    private LinearLayout container;
    private String page;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        page = getIntent().getStringExtra(EXTRA_PAGE);
        if (page == null) page = "appearance";

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getResources().getColor(R.color.bg_dark));
        scroll.setFillViewport(true);
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(16));
        scroll.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        addTitle(titleOf(page));
        if ("appearance".equals(page)) {
            buildAppearance();
        } else if ("playback".equals(page)) {
            buildPlayback();
        } else if ("interaction".equals(page)) {
            buildInteraction();
        } else if ("data".equals(page)) {
            buildData();
        } else {
            buildAbout();
        }
    }

    private String titleOf(String p) {
        switch (p) {
            case "playback": return "播放与音质";
            case "interaction": return "交互与浮层";
            case "data": return "数据与工具";
            case "about": return "关于";
            default: return "外观与显示";
        }
    }

    // ==================== 外观与显示 ====================

    private void buildAppearance() {
        addSection("主题");
        addRow("主题", ThemeManager.name(this), () ->
                startActivity(new Intent(this, ThemeActivity.class)));
        addRow("背景", "封面 / 自定义图 / 网页 / 默认封面", () ->
                startActivity(new Intent(this, BackgroundHubActivity.class)));

        addSection("界面");
        addRow("界面缩放", com.qinghe.music163pro.util.UiScale.getDpi(this) + " dpi", () -> {
            com.qinghe.music163pro.util.WatchPopup.input(this, "界面缩放（dpi）",
                    "320 为原始大小；确认后自动保存并生效", "dpi",
                    String.valueOf(com.qinghe.music163pro.util.UiScale.getDpi(this)), "确定", text -> {
                        try {
                            int v = Math.max(160, Math.min(640, Integer.parseInt(text.trim())));
                            com.qinghe.music163pro.util.UiScale.setDpi(this, v);
                            recreate();
                        } catch (NumberFormatException ignored) {
                        }
                    });
        });
        addRow("页面字号", com.qinghe.music163pro.util.UiScale.getFontPercent(this) + "%", () -> {
            com.qinghe.music163pro.util.WatchPopup.input(this, "页面字号（%）",
                    "100 为原始大小；确认后自动保存并生效", "%",
                    String.valueOf(com.qinghe.music163pro.util.UiScale.getFontPercent(this)), "确定", text -> {
                        try {
                            int v = Math.max(80, Math.min(200, Integer.parseInt(text.trim())));
                            com.qinghe.music163pro.util.UiScale.setFontPercent(this, v);
                            recreate();
                        } catch (NumberFormatException ignored) {
                        }
                    });
        });

        // 需求 2026-11-21：自定义字体（导入 ttf 后全局生效）
        addRow("自定义字体",
                com.qinghe.music163pro.util.CustomFont.has(this) ? "已启用" : "系统默认",
                () -> startActivity(new Intent(this, FontSettingsActivity.class)));

        addSection("屏幕");
        addRow("屏幕常亮", "保持屏幕不熄灭", () -> openToggle("appearance"));
        addRow("OLED 防烧屏", "主页面内容周期移位", () -> openToggle("appearance"));

        addSection("播放页歌名");
        addRow("歌名显示选项", "去曲师 / 去括号内容", () -> openToggle("appearance"));
    }

    // ==================== 播放与音质 ====================

    private void buildPlayback() {
        addSection("音效");
        addRow("均衡器",
                com.qinghe.music163pro.util.EqualizerController.isEnabled(this) ? "已开启" : "已关闭",
                () -> startActivity(new Intent(this, EqualizerActivity.class)));

        addSection("表冠");
        addSwitchRow("表冠调音量", "关闭后表冠不再调整音量",
                com.qinghe.music163pro.util.CrownVolumePrefs.isEnabled(this),
                (btn, checked) ->
                        com.qinghe.music163pro.util.CrownVolumePrefs.setEnabled(this, checked));
        final TextView sens = addValueRow("表冠灵敏度",
                "档位 " + com.qinghe.music163pro.util.CrownVolumePrefs.getSensitivity(this));
        ((LinearLayout) sens.getParent()).setOnClickListener(v -> {
            int next = com.qinghe.music163pro.util.CrownVolumePrefs.getSensitivity(this) % 3 + 1;
            com.qinghe.music163pro.util.CrownVolumePrefs.setSensitivity(this, next);
            sens.setText("档位 " + next);
        });
        final TextView cap = addValueRow("表冠音量上限",
                com.qinghe.music163pro.util.CrownVolumePrefs.getMaxPercent(this) + "%");
        ((LinearLayout) cap.getParent()).setOnClickListener(v ->
                com.qinghe.music163pro.util.WatchPopup.input(this, "表冠音量上限（%）",
                        "10~100；到达后表冠不再加大音量（减小不受限）", "%",
                        String.valueOf(com.qinghe.music163pro.util.CrownVolumePrefs.getMaxPercent(this)),
                        "确定", text -> {
                            try {
                                int percent = Math.max(10, Math.min(100, Integer.parseInt(text.trim())));
                                com.qinghe.music163pro.util.CrownVolumePrefs.setMaxPercent(this, percent);
                                cap.setText(percent + "%");
                            } catch (NumberFormatException ignored) {
                            }
                        }));

        addSection("播放行为");
        addRow("播放模式与切歌", "本地/纯在线、随机切序、变速、跳过试听、收藏模式",
                () -> openToggle("playback"));
    }

    // ==================== 交互与浮层 ====================

    private void buildInteraction() {
        addSection("手势与定时");
        addRow("返回手势 / 定时关闭", "返回按钮、定时结束退出", () -> openToggle("interaction"));

        addSection("歌词");
        addRow("歌词显示", "滚动模式、偏移、恢复间隔", () -> openToggle("interaction"));

        addSection("其它");
        addRow("听歌识曲 / 手表解析接口", "识曲模式、解析接口开关", () -> openToggle("interaction"));
    }

    // ==================== 数据与工具 ====================

    private void buildData() {
        addSection("存储");
        addRow("清理缓存", "元信息索引 / 本地音乐列表缓存", () ->
                com.qinghe.music163pro.util.WatchMenuDialog.show(this, "清理缓存",
                        new String[]{"清理元信息索引", "清理本地音乐列表缓存", "全部清理"},
                        index -> {
                            if (index == 0 || index == 2) {
                                com.qinghe.music163pro.util.LocalMetadataIndex.clear(this);
                            }
                            if (index == 1 || index == 2) {
                                com.qinghe.music163pro.util.LocalMusicCache.invalidate(this);
                                com.qinghe.music163pro.util.LocalLibraryCache.clear();
                            }
                            Toast.makeText(this, "已清理，下次进入本地音乐会重新扫描",
                                    Toast.LENGTH_SHORT).show();
                        }));
        addRow("编辑更多页面", "自定义「更多」页显示项", () ->
                startActivity(new Intent(this, EditMoreActivity.class)));
        addRow("其它工具", "导出音乐 / 小天才模块", () ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    // ==================== 关于 ====================

    private void buildAbout() {
        addSection("应用");
        addRow("检测更新", "检查是否有新版本", () ->
                startActivity(new Intent(this, UpdateActivity.class)));
        addRow("帮助", "使用说明", () ->
                startActivity(new Intent(this, HelpActivity.class)));
        addRow("关于", "版本信息与作者", () ->
                startActivity(new Intent(this, AboutActivity.class)));
        addRow("开源信息", "第三方库许可", () ->
                startActivity(new Intent(this, OpenSourceActivity.class)));
    }

    // ==================== 行构建 ====================

    /** 跳到原开关页的指定分类（复用同一套逻辑，只显示本类行）。 */
    private void openToggle(String section) {
        Intent i = new Intent(this, ToggleSettingsActivity.class);
        i.putExtra(ToggleSettingsActivity.EXTRA_SECTION, section);
        startActivity(i);
    }

    private void addTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, dpToPx(4), 0, dpToPx(10));
        container.addView(t);
    }

    /** 小标题（需求 2026-11-21：设置页冗余标题已清理，不再渲染；方法保留避免改调用点）。 */
    private void addSection(String name) {
        // no-op
    }

    private void addRow(String title, String sub, Runnable action) {
        LinearLayout row = buildRow(title, sub);
        row.setOnClickListener(v -> action.run());
        container.addView(row);
    }

    /** 「标题 + 副标题 + MD3 开关」行（需求 2026-11-21：纯开关不再用「开/关」文字指示）。 */
    private void addSwitchRow(String title, String sub, boolean initial,
                              android.widget.CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(7), dpToPx(16), dpToPx(7));
        row.setMinimumHeight(dpToPx(48));
        row.setBackground(rippleBackground());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dpToPx(4);
        row.setLayoutParams(rp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        t.setSingleLine(true);
        textCol.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(getResources().getColor(R.color.text_secondary));
        s.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        s.setMaxLines(1);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(s);
        row.addView(textCol);

        com.google.android.material.materialswitch.MaterialSwitch sw =
                new com.google.android.material.materialswitch.MaterialSwitch(this);
        sw.setTextOn("");
        sw.setTextOff("");
        sw.setScaleX(0.8f);
        sw.setScaleY(0.8f);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);

        row.setOnClickListener(v -> sw.toggle());
        container.addView(row);
    }

    private TextView addValueRow(String title, String initialValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(7), dpToPx(16), dpToPx(7));
        row.setMinimumHeight(dpToPx(46));
        row.setBackground(rippleBackground());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dpToPx(4);
        row.setLayoutParams(rp);
        row.setClickable(true);
        row.setFocusable(true);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        t.setSingleLine(true);
        row.addView(t);

        TextView value = new TextView(this);
        value.setText(initialValue);
        value.setTextColor(ThemeManager.accentColor(this));
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        value.setSingleLine(true);
        row.addView(value);

        container.addView(row);
        return value;
    }

    private LinearLayout buildRow(String title, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(7), dpToPx(16), dpToPx(7));
        row.setMinimumHeight(dpToPx(48));
        row.setBackground(rippleBackground());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dpToPx(4);
        row.setLayoutParams(rp);
        row.setClickable(true);
        row.setFocusable(true);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        t.setSingleLine(true);
        row.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(getResources().getColor(R.color.text_secondary));
        s.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        s.setMaxLines(2);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(s);
        return row;
    }

    private android.graphics.drawable.Drawable rippleBackground() {
        android.graphics.drawable.GradientDrawable body = new android.graphics.drawable.GradientDrawable();
        body.setColor(0xFF1E1F24);
        body.setCornerRadius(dpToPx(14));
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dpToPx(14));
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        UiKit.withAlpha(ThemeManager.accentColor(this), 0x33)),
                body, mask);
    }
}
