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
import com.qinghe.music163pro.util.LanPageBackground;

/**
 * 局域网传歌网页背景（需求 2026-11-21 任务 1）：选图 + 模糊档位。
 */
public class LanPageBackgroundActivity extends BaseWatchActivity {

    private static final int REQ_PICK = 4101;
    private TextView tvBlur;
    private TextView tvState;

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
        title.setText("局域网网页背景");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, dpToPx(4), 0, dpToPx(10));
        root.addView(title);        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, title);


        root.addView(button("选择背景图片", v ->
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
                                .setType("image/*"), REQ_PICK)));

        tvBlur = button("", v -> {
            int next = (LanPageBackground.getBlurLevel(this) + 1) % 4;
            LanPageBackground.setBlurLevel(this, next);
            refresh();
            Toast.makeText(this, "模糊档位已切换", Toast.LENGTH_SHORT).show();
        });
        root.addView(tvBlur);

        tvState = button("清除背景图片", v -> {
            LanPageBackground.clear(this);
            refresh();
            Toast.makeText(this, "已清除，网页恢复默认深色", Toast.LENGTH_SHORT).show();
        });
        root.addView(tvState);

        TextView hint = new TextView(this);
        hint.setText("背景图通过 /api/lan-bg 下发给上传页；未设置时网页用默认深色。");
        hint.setTextColor(getResources().getColor(R.color.text_secondary));
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        hint.setPadding(dpToPx(8), dpToPx(10), dpToPx(8), 0);
        root.addView(hint);

        setContentView(scroll);
        refresh();
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(com.qinghe.music163pro.util.ThemeManager.accentColor(this));
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        // 代码 ripple：涟漪色用运行时主题色（含自定义 hex），不用 ?attr 避免"不跟随自定义主题"
        android.graphics.drawable.GradientDrawable bd = new android.graphics.drawable.GradientDrawable();
        bd.setColor(0xFF1E1F24);
        bd.setCornerRadius(dpToPx(14));
        android.graphics.drawable.GradientDrawable mk = new android.graphics.drawable.GradientDrawable();
        mk.setColor(0xFFFFFFFF);
        mk.setCornerRadius(dpToPx(14));
        btn.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        com.qinghe.music163pro.util.UiKit.withAlpha(
                                com.qinghe.music163pro.util.UiKit.COLOR_ACCENT, 0x33)),
                bd, mk));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dpToPx(6);
        btn.setLayoutParams(lp);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(l);
        return btn;
    }

    private void refresh() {
        tvBlur.setText("模糊强度：" + new String[]{"关闭", "弱", "中", "强"}
                [LanPageBackground.getBlurLevel(this)] + "（点按切换）");
        tvState.setText(LanPageBackground.has(this) ? "已设置背景图（点清除）" : "未设置背景图");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            LanPageBackground.save(this, data.getData());
            refresh();
            Toast.makeText(this, "已保存网页背景", Toast.LENGTH_SHORT).show();
        }
    }
}
