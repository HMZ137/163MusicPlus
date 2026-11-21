package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.ThemeManager;
import com.qinghe.music163pro.util.UiKit;

/**
 * 设置首页（需求 2026-11-21 重构）：把所有设置项按「外观 / 播放 / 交互 / 数据 / 关于」分成五类，
 * 不再把所有开关都塞进一个「开关选项」页。
 */
public class SettingsHomeActivity extends BaseWatchActivity {

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
        title.setText("设置");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, dpToPx(4), 0, dpToPx(10));
        root.addView(title);

        root.addView(category("外观与显示", "主题、字号、缩放、防烧屏、歌名显示、背景",
                v -> open("appearance")));
        root.addView(category("播放与音质", "播放模式、随机切序、均衡器、表冠、变速、收藏",
                v -> open("playback")));
        root.addView(category("交互与浮层", "返回手势、定时关闭、歌词、识曲、解析接口",
                v -> open("interaction")));
        root.addView(category("数据与工具", "缓存管理、导出音乐、编辑更多页、小天才模块",
                v -> open("data")));
        root.addView(category("关于", "检测更新、帮助、关于、开源信息",
                v -> open("about")));

        setContentView(scroll);
    }

    private void open(String page) {
        android.content.Intent i = new android.content.Intent(this, SettingsPageActivity.class);
        i.putExtra(SettingsPageActivity.EXTRA_PAGE, page);
        startActivity(i);
    }

    private LinearLayout category(String name, String sub, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        row.setMinimumHeight(dpToPx(54));
        row.setBackground(rippleBackground());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dpToPx(5);
        row.setLayoutParams(rp);
        row.setClickable(true);
        row.setFocusable(true);

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        t.setSingleLine(true);
        row.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(getResources().getColor(R.color.text_secondary));
        s.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        s.setMaxLines(2);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        s.setPadding(0, dpToPx(1), 0, 0);
        row.addView(s);

        row.setOnClickListener(l);
        return row;
    }

    /** 深色圆角 + 运行时主题色涟漪（跟随自定义 hex）。 */
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
