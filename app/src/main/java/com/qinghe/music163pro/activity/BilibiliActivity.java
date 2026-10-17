package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qinghe.music163pro.R;

/**
 * 「听bilibili」功能菜单。
 *
 * <p>这里只保留两条**不依赖 B 站账号**的链路：</p>
 * <ul>
 *   <li><b>从BV号打开</b> —— 解析视频信息、取音频流播放（下游还会用到分 P 与字幕）；</li>
 *   <li><b>收藏</b> —— 本机收藏的 B 站视频，与账号无关。</li>
 * </ul>
 *
 * <p>已移除的入口：搜索视频、登录B站、云端收藏，以及底部的登录状态提示行。</p>
 *
 * <p>⚠️ 被移除的入口对应的页面（{@code BilibiliSearchActivity} / {@code BilibiliLoginActivity} /
 * {@code BilibiliCloudFavoritesActivity} / {@code BilibiliCloudFavoriteVideosActivity}）
 * 以及 {@code BilibiliApiHelper} 里的相关接口**代码都保留着**，只是不再有入口。
 * 这是有意的取舍：改动最小、可逆，将来想恢复只要把磁贴加回来即可。</p>
 */
public class BilibiliActivity extends BaseWatchActivity {

    /**
     * 页面标题栏里已经有返回控件，不再叠加左上角浮动返回按钮，避免出现两个返回入口。
     */
    @Override
    protected boolean shouldShowBackButton() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(getResources().getColor(R.color.bg_dark));
        scrollView.setFillViewport(true);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(px(10), px(8), px(10), px(10));

        container.addView(createTitleBar());

        // 核心入口：解析 BV 号并打开视频
        container.addView(createMenuItem(R.drawable.ic_video_library, "从BV号打开",
                () -> startActivity(new Intent(this, BilibiliBvidActivity.class))));

        // 本机收藏的 B 站视频（不需要登录）
        container.addView(createMenuItem(R.drawable.ic_favorite_border, "收藏",
                () -> startActivity(new Intent(this, BilibiliFavoritesActivity.class))));

        // 打开过的 BV 号（BV 号记不住，靠它找回「刚才那个视频」）
        container.addView(createMenuItem(R.drawable.ic_history, "历史记录",
                () -> startActivity(new Intent(this, BilibiliHistoryActivity.class))));

        scrollView.addView(container);
        setContentView(scrollView);
    }

    private LinearLayout createTitleBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(38)));
        insetTitleBar(bar);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView back = new ImageView(this);
        back.setLayoutParams(new LinearLayout.LayoutParams(px(30), px(30)));
        back.setPadding(px(5), px(5), px(5), px(5));
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setColorFilter(getResources().getColor(R.color.text_primary));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        title.setGravity(Gravity.START);
        title.setText("听bilibili");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(title);

        // 右侧占位，让标题保持在视觉中间
        ImageView placeholder = new ImageView(this);
        placeholder.setLayoutParams(new LinearLayout.LayoutParams(px(30), px(30)));
        bar.addView(placeholder);
        return bar;
    }

    private LinearLayout createMenuItem(int iconRes, String label, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(52));
        params.topMargin = px(6);
        row.setLayoutParams(params);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(14), 0, px(14), 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundColor(getResources().getColor(R.color.surface_elevated));
        row.setOnClickListener(v -> onClick.run());

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                px(22), px(22));
        icon.setLayoutParams(iconParams);
        icon.setImageResource(iconRes);
        icon.setAlpha(0.7f);
        row.addView(icon);

        TextView tv = new TextView(this);
        LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvParams.setMarginStart(px(10));
        tv.setLayoutParams(tvParams);
        tv.setText(label);
        tv.setTextColor(getResources().getColor(R.color.text_primary));
        tv.setTextSize(14);
        row.addView(tv);

        return row;
    }
}
