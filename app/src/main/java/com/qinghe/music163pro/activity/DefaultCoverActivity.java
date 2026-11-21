package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.DefaultCover;

/**
 * 默认封面设置（需求 2026-11-21 任务 4）：选一张图作为无封面歌曲的兜底缩略图。
 */
public class DefaultCoverActivity extends BaseWatchActivity {

    private static final int REQ_PICK = 4102;
    private ImageView preview;

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
        title.setText("默认封面");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, dpToPx(4), 0, dpToPx(10));
        root.addView(title);

        // 预览
        preview = new ImageView(this);
        Bitmap cur = DefaultCover.load(this);
        if (cur != null) {
            preview.setImageBitmap(cur);
        } else {
            preview.setBackgroundColor(0xFF2A2A2A);
        }
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setMinimumHeight(dpToPx(120));
        LinearLayout.LayoutParams pv = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(120));
        pv.bottomMargin = dpToPx(10);
        preview.setLayoutParams(pv);
        root.addView(preview);

        root.addView(button("选择图片", v ->
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
                                .setType("image/*"), REQ_PICK)));
        root.addView(button("恢复默认（清除）", v -> {
            DefaultCover.clear(this);
            refreshPreview();
            Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show();
        }));

        TextView hint = new TextView(this);
        hint.setText("歌曲没有封面信息时，列表左侧缩略图用这张图兜底，不再显示黑色。");
        hint.setTextColor(getResources().getColor(R.color.text_secondary));
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        hint.setPadding(dpToPx(8), dpToPx(10), dpToPx(8), 0);
        root.addView(hint);

        setContentView(scroll);
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(com.qinghe.music163pro.util.ThemeManager.accentColor(this));
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        // 代码 ripple：涟漪色用运行时主题色（含自定义 hex）
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

    private void refreshPreview() {
        Bitmap cur = DefaultCover.load(this);
        if (cur != null) {
            preview.setImageBitmap(cur);
        } else {
            preview.setImageBitmap(null);
            preview.setBackgroundColor(0xFF2A2A2A);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            DefaultCover.save(this, data.getData());
            refreshPreview();
            Toast.makeText(this, "已设置默认封面", Toast.LENGTH_SHORT).show();
        }
    }
}
