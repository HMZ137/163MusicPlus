package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qinghe.music163pro.R;

/**
 * 背景设置统一入口（需求 2026-11-21 任务 5）：把三个背景相关子项收进来。
 * 子页功能与跳转不变，设置页只保留这一个入口。
 */
public class BackgroundHubActivity extends BaseWatchActivity {

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
        title.setText("背景设置");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, dpToPx(4), 0, dpToPx(10));
        root.addView(title);        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, title);


        root.addView(entry("播放页 / 歌词页背景", "封面 / 自定义图 / 流体 / 模糊",
                v -> startActivity(new android.content.Intent(this, BackgroundSettingsActivity.class))));
        root.addView(entry("局域网传歌网页背景", "浏览器上传页的背景图与模糊",
                v -> startActivity(new android.content.Intent(this, LanPageBackgroundActivity.class))));
        root.addView(entry("默认封面", "歌曲无封面信息时的兜底图",
                v -> startActivity(new android.content.Intent(this, DefaultCoverActivity.class))));

        setContentView(scroll);
    }

    private LinearLayout entry(String title, String sub, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        row.setMinimumHeight(dpToPx(52));
        // 用代码构建 ripple：涟漪色取「运行时」主题色（含自定义 hex），
        // 不用 drawable 里的 ?attr/colorAccentSoft —— attr 是编译期预设，自定义主题下不跟随。
        android.graphics.drawable.GradientDrawable body = new android.graphics.drawable.GradientDrawable();
        body.setColor(0xFF1E1F24);
        body.setCornerRadius(dpToPx(14));
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dpToPx(14));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        com.qinghe.music163pro.util.UiKit.withAlpha(
                                com.qinghe.music163pro.util.UiKit.COLOR_ACCENT, 0x33)),
                body, mask));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dpToPx(4);
        row.setLayoutParams(rp);
        row.setClickable(true);
        row.setFocusable(true);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(getResources().getColor(R.color.text_secondary));
        s.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        s.setPadding(0, dpToPx(1), 0, 0);
        s.setMaxLines(2);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(s);

        row.setOnClickListener(l);
        return row;
    }
}
