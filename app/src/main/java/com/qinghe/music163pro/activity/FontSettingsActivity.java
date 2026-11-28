package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.CustomFont;

/**
 * 自定义字体设置（需求 2026-11-21 任务 8）：导入字体文件、恢复默认。不做预览。
 */
public class FontSettingsActivity extends BaseWatchActivity {

    private static final int REQ_PICK_FONT = 4201;
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
        title.setText("自定义字体");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(15);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dpToPx(10));
        root.addView(title);        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, title);


        tvState = buildButton("当前：系统默认", null);
        root.addView(tvState);

        root.addView(buildButton("导入字体文件（ttf / otf）", v -> pickFont()));
        root.addView(buildButton("恢复默认字体", v -> {
            CustomFont.clear(this);
            Toast.makeText(this, "已恢复默认字体", Toast.LENGTH_SHORT).show();
            recreate();
        }));

        TextView hint = new TextView(this);
        hint.setText("字体文件保存在应用私有目录，全局生效；导入后重新进入页面即可看到效果。");
        hint.setTextColor(getResources().getColor(R.color.text_secondary));
        hint.setTextSize(10);
        hint.setPadding(dpToPx(8), dpToPx(12), dpToPx(8), 0);
        root.addView(hint);

        setContentView(scroll);
        refreshState();
    }

    private TextView buildButton(String label, View.OnClickListener l) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(getResources().getColor(R.color.colorPrimary));
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1E1F24);
        bg.setCornerRadius(dpToPx(14));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dpToPx(6);
        btn.setLayoutParams(lp);
        if (l != null) {
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setOnClickListener(l);
        }
        return btn;
    }

    private void pickFont() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQ_PICK_FONT);
    }

    private void refreshState() {
        if (tvState == null) return;
        if (CustomFont.has(this)) {
            Typeface tf = CustomFont.get(this);
            tvState.setText(tf != null ? "当前：自定义字体（已生效）"
                    : "当前：自定义字体（加载失败，已回退默认）");
        } else {
            tvState.setText("当前：系统默认");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FONT && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            boolean ok = CustomFont.save(this, data.getData())
                    && CustomFont.get(this) != null;
            Toast.makeText(this, ok ? "字体已导入" : "导入失败：不是有效的字体文件",
                    Toast.LENGTH_SHORT).show();
            recreate();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }
}
