package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.BilibiliApiHelper;

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
     * 2026-11-21：改为显示全局浮动返回按钮。
     *
     * <p>原来返回 false（靠标题栏的箭头），但页面内容比一屏长、滚动后标题栏看不见，
     * 用户会觉得「没有返回入口」。现在统一用浮动按钮，标题栏的箭头同步移除。</p>
     */
    @Override
    protected boolean shouldShowBackButton() {
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 2026-11-21：从登录页返回后刷新「已登录：昵称」
        if (loginRow != null) {
            refreshLoginState(loginRow);
        }
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

        // 2026-11-21：搜索入口之前被移除了，搜索功能重做后加回来
        container.addView(createMenuItem(R.drawable.ic_search, "搜索视频",
                () -> startActivity(new Intent(this, BilibiliSearchActivity.class))));

        // 本机收藏的 B 站视频（不需要登录）
        container.addView(createMenuItem(R.drawable.ic_favorite_border, "收藏",
                () -> startActivity(new Intent(this, BilibiliFavoritesActivity.class))));

        // 2026-11-21：B 站账号登录（扫码 / Cookie）与云端收藏
        LinearLayout loginRow = createMenuItem(R.drawable.ic_person, loginLabel(),
                () -> startActivity(new Intent(this, BilibiliLoginActivity.class)));
        container.addView(loginRow);
        this.loginRow = loginRow;
        // 登录后这里长期显示「已登录：昵称」，不必再点进去确认
        refreshLoginState(loginRow);

        container.addView(createMenuItem(R.drawable.ic_cloud, "在线收藏",
                () -> startActivity(new Intent(this, BilibiliCloudFavoritesActivity.class))));

        // 打开过的 BV 号（BV 号记不住，靠它找回「刚才那个视频」）
        container.addView(createMenuItem(R.drawable.ic_history, "历史记录",
                () -> startActivity(new Intent(this, BilibiliHistoryActivity.class))));

        scrollView.addView(container);
        setContentView(scrollView);
    }

    private LinearLayout createTitleBar() {
        LinearLayout bar = new LinearLayout(this);
        // 2026-11-28：标题栏高度用统一入口撑到浮动返回按钮的占位，
        // 否则列表第一行会被按钮压住（手算 max() 会算矮）
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        insetTitleBar(bar);
        // 2026-11-28：标题栏直接给固定高度 —— 浮动返回按钮实测占 y=8~88，
        // 用 applyTitleBarTopInset 会被「可收起标题栏」逻辑再压回一行高。
        ViewGroup.LayoutParams barLp = bar.getLayoutParams();
        barLp.height = px(80);
        bar.setLayoutParams(barLp);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        // 2026-11-21：返回入口改由全局浮动按钮承担，这里不再画标题栏箭头（避免两个返回入口）
        TextView title = new TextView(this);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        title.setGravity(Gravity.CENTER);
        title.setText("听bilibili");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(title);

        // 2026-11-28：去掉右侧占位 —— 标题已按整屏居中，占位反而把标题推左
        return bar;
    }

    /** 本地判断的登录文案（有 Cookie 就先显示「已登录」，昵称随后异步补上）。 */
    private String loginLabel() {
        String cookie = getSharedPreferences("music163_settings", MODE_PRIVATE)
                .getString("bilibili_cookie", "");
        return (cookie == null || cookie.trim().isEmpty()) ? "登录" : "已登录";
    }

    /** 异步拉一次账号信息，把「已登录」补成「已登录：昵称」。 */
    private void refreshLoginState(LinearLayout row) {
        final TextView title = findTitleText(row);
        if (title == null) {
            return;
        }
        String cookie = getSharedPreferences("music163_settings", MODE_PRIVATE)
                .getString("bilibili_cookie", "");
        if (cookie == null || cookie.trim().isEmpty()) {
            title.setText("登录");
            return;
        }
        title.setText("已登录");
        BilibiliApiHelper.getNavInfo(cookie, new BilibiliApiHelper.NavCallback() {
            @Override
            public void onResult(boolean loggedIn, String uname) {
                if (!loggedIn) {
                    title.setText("已登录");
                    return;
                }
                title.setText(uname == null || uname.trim().isEmpty()
                        ? "已登录" : "已登录：" + uname.trim());
            }

            @Override
            public void onError(String message) {
                // 网络失败时保留本地判断（有 Cookie 就显示已登录）
            }
        });
    }

    /** 在菜单行里找标题 TextView（跳过图标）。 */
    private TextView findTitleText(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                return (TextView) child;
            }
            if (child instanceof ViewGroup) {
                TextView nested = findTitleText((ViewGroup) child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private LinearLayout loginRow;

    private LinearLayout createMenuItem(int iconRes, String label, Runnable onClick) {
        // 2026-11-28：尺寸与「更多」页的行对齐（54dp 高 / 16dp 内边距 / 17sp 文字 / 涟漪底）
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(54));
        params.topMargin = px(6);
        row.setLayoutParams(params);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(16), 0, px(16), 0);
        row.setClickable(true);
        row.setFocusable(true);
        // 与「更多」同一个底：常态深色 + 按下淡紫，都带 18dp 圆角
        row.setBackgroundResource(R.drawable.bg_card_tile_ripple);
        row.setOnClickListener(v -> onClick.run());

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(px(22), px(22));
        icon.setLayoutParams(iconParams);
        icon.setImageResource(iconRes);
        icon.setAlpha(0.7f);
        row.addView(icon);

        TextView tv = new TextView(this);
        // 单行（与「更多」一致），过长结尾省略；昵称长也不会把行撑高
        LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvParams.setMarginStart(px(12));
        tv.setLayoutParams(tvParams);
        tv.setText(label);
        tv.setTextColor(getResources().getColor(R.color.text_primary));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.watch_text_item));
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(tv);

        return row;
    }
}
