package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.manager.BilibiliHistoryManager;

import java.util.List;

/**
 * 「听bilibili」打开过的 BV 号历史。
 *
 * <p>BV 号本身记不住，看过的视频过了就找不回来 —— 这个页面按「最近打开在前」列出来，
 * 点一条直接回到那个视频的分 P 页。数据由 {@link BilibiliHistoryManager} 记录
 * （BilibiliPlaylistActivity 打开时 + 播放 B 站曲目时各记一次）。</p>
 */
public class BilibiliHistoryActivity extends BaseWatchActivity {

    private LinearLayout listContainer;
    private TextView emptyView;

    /** 标题栏自带返回控件，不再叠加浮动返回按钮。 */
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(10), px(8), px(10), px(10));

        root.addView(createTitleBar());

        emptyView = new TextView(this);
        emptyView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(getResources().getColor(R.color.text_secondary));
        emptyView.setTextSize(12);
        emptyView.setPadding(0, px(32), 0, 0);
        emptyView.setText("还没有打开过 B 站视频");
        root.addView(emptyView);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        scrollView.addView(root);
        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderHistory();
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

        ImageView back = new ImageView(this);
        back.setLayoutParams(new LinearLayout.LayoutParams(px(30), px(30)));
        back.setPadding(px(5), px(5), px(5), px(5));
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setColorFilter(getResources().getColor(R.color.text_primary));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView tvTitle = new TextView(this);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvTitle.setGravity(Gravity.START);
        tvTitle.setText("B站历史");
        tvTitle.setTextColor(getResources().getColor(R.color.text_primary));
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(tvTitle);

        // 右侧「清空」：与左侧返回等宽占位，保证标题居中
        TextView btnClear = new TextView(this);
        btnClear.setLayoutParams(new LinearLayout.LayoutParams(px(48),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        btnClear.setGravity(Gravity.CENTER);
        btnClear.setText("清空");
        btnClear.setTextColor(com.qinghe.music163pro.util.ThemeManager.accentColor(this));
        btnClear.setTextSize(12);
        btnClear.setPadding(px(4), px(6), px(4), px(6));
        btnClear.setClickable(true);
        btnClear.setFocusable(true);
        btnClear.setOnClickListener(v -> confirmClear());
        bar.addView(btnClear);

        return bar;
    }

    private void renderHistory() {
        listContainer.removeAllViews();
        List<BilibiliHistoryManager.Entry> entries = BilibiliHistoryManager.getAll(this);
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        for (BilibiliHistoryManager.Entry entry : entries) {
            listContainer.addView(createHistoryItem(entry));
        }
    }

    private LinearLayout createHistoryItem(final BilibiliHistoryManager.Entry entry) {
        LinearLayout item = new LinearLayout(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = px(6);
        item.setLayoutParams(params);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(px(12), px(10), px(12), px(10));
        item.setBackground(com.qinghe.music163pro.util.UiKit.roundedFill(item.getContext(), getResources().getColor(R.color.surface_elevated)));
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(v -> openEntry(entry));
        item.setOnLongClickListener(v -> {
            confirmRemove(entry);
            return true;
        });

        TextView title = new TextView(this);
        title.setText(entry.displayName());
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(16);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        item.addView(title);

        // 第二行：BV号 · UP主 · 多久之前 —— 尽量在一行里把「这是哪个视频」补全
        StringBuilder sb = new StringBuilder(entry.bvid);
        if (!TextUtils.isEmpty(entry.owner)) {
            sb.append(" · ").append(entry.owner);
        }
        String when = BilibiliHistoryManager.formatTime(entry.timestamp);
        if (!TextUtils.isEmpty(when)) {
            sb.append(" · ").append(when);
        }
        TextView sub = new TextView(this);
        sub.setText(sb.toString());
        sub.setTextColor(getResources().getColor(R.color.text_secondary));
        sub.setTextSize(11);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, px(2), 0, 0);
        item.addView(sub);
        return item;
    }

    /** 点一条：回到那个视频的分 P 页（与收藏页的行为一致）。 */
    private void openEntry(BilibiliHistoryManager.Entry entry) {
        Intent intent = new Intent(this, BilibiliPlaylistActivity.class);
        intent.putExtra("bvid", entry.bvid);
        intent.putExtra("video_title", entry.title);
        intent.putExtra("owner_name", entry.owner);
        startActivity(intent);
    }

    private void confirmRemove(final BilibiliHistoryManager.Entry entry) {
        // P5：统一浮窗（危险动作红色描边按钮）
        com.qinghe.music163pro.util.WatchPopup.confirm(this, "删除这条记录",
                entry.displayName(), "删除", true, () -> {
                    BilibiliHistoryManager.remove(this, entry.bvid);
                    renderHistory();
                });
    }

    private void confirmClear() {
        if (BilibiliHistoryManager.getAll(this).isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "历史已经是空的");
            return;
        }
        com.qinghe.music163pro.util.WatchPopup.confirm(this, "清空 B 站历史",
                "只清记录，不影响收藏与下载。", "清空", true, () -> {
                    BilibiliHistoryManager.clear(this);
                    renderHistory();
                    com.qinghe.music163pro.util.UiKit.toast(this, "已清空");
                });
    }
}
