package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.PlaybackStats;
import com.qinghe.music163pro.util.WatchConfirmDialog;

import java.util.List;

/**
 * 播放统计：累计播放时长、播放次数、最常听的歌曲。
 *
 * <p>页面很轻，所以直接在代码里拼控件（这个项目的多个页面都是这个风格），
 * 只用一个空的容器 + 清空按钮作为布局。</p>
 */
public class PlaybackStatsActivity extends BaseWatchActivity {

    /** 只展示前几名，表盘没那么大。 */
    private static final int TOP_TRACK_LIMIT = 10;

    private LinearLayout contentLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playback_stats);
        // G5：标题居左 + 返回按钮占位。
        // 本页标题在布局里原本没有 id，WatchBackButton 的遍历式识别（id 名含 "title"）认不出来，
        // 所以 G5 的统一切点覆盖不到它 —— 标题一直是居中的。这里显式把标题 TextView 当「标题栏」
        // 传入（与 MoreActivity / EditMoreActivity 同款做法）：既把标题行撑到返回按钮的占位高度，
        // 又让它对齐到左侧。改布局时请勿删掉 tv_stats_title 这个 id。
        TextView tvStatsTitle = findViewById(R.id.tv_stats_title);
        if (tvStatsTitle != null) {
            com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, tvStatsTitle);
        }

        contentLayout = findViewById(R.id.layout_stats_content);
        View btnClear = findViewById(R.id.btn_clear_stats);
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> confirmClear());
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到页面时重新读一次，反正在后台页面播放也不会变
        render();
    }

    private void render() {
        if (contentLayout == null) {
            return;
        }
        contentLayout.removeAllViews();

        PlaybackStats.Summary summary = PlaybackStats.getSummary(this);
        addStatRow("累计播放时长", PlaybackStats.formatDuration(summary.totalPlayMs));
        addStatRow("播放次数", summary.totalPlayCount + " 次");
        addStatRow("统计到的歌曲", summary.trackedSongCount + " 首");

        List<PlaybackStats.TrackStat> top = PlaybackStats.getTopTracks(this, TOP_TRACK_LIMIT);
        addSectionTitle("最常听");
        if (top.isEmpty()) {
            addHint("还没有播放记录");
            return;
        }
        for (int i = 0; i < top.size(); i++) {
            PlaybackStats.TrackStat track = top.get(i);
            String title = (i + 1) + ". " + safe(track.name);
            if (track.transName != null && !track.transName.isEmpty()
                    && !track.transName.equals(track.name)) {
                title = (i + 1) + ". " + safe(track.name) + "（" + track.transName + "）";
            }
            String subtitle = safe(track.artist) + " · " + track.playCount + " 次";
            addTrackRow(title, subtitle);
        }
    }

    private void addStatRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(10), dpToPx(7), dpToPx(10), dpToPx(7));

        TextView tvLabel = makeText(label, 0xB3FFFFFF, 13);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLabel);

        TextView tvValue = makeText(value, com.qinghe.music163pro.util.UiKit.COLOR_ACCENT, 13);
        row.addView(tvValue);

        contentLayout.addView(row);
        contentLayout.addView(makeDivider());
    }

    private void addSectionTitle(String text) {
        TextView tv = makeText(text, 0x80FFFFFF, 12);
        tv.setPadding(dpToPx(10), dpToPx(12), dpToPx(10), dpToPx(4));
        contentLayout.addView(tv);
    }

    private void addTrackRow(String title, String subtitle) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6));

        TextView tvTitle = makeText(title, 0xFFFFFFFF, 13);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        column.addView(tvTitle);

        TextView tvSubtitle = makeText(subtitle, 0x80FFFFFF, 11);
        tvSubtitle.setSingleLine(true);
        tvSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        column.addView(tvSubtitle);

        contentLayout.addView(column);
        contentLayout.addView(makeDivider());
    }

    private void addHint(String text) {
        TextView tv = makeText(text, 0x80FFFFFF, 12);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dpToPx(16), 0, dpToPx(16));
        contentLayout.addView(tv);
    }

    private void confirmClear() {
        WatchConfirmDialog.show(this, "清空统计",
                "清空累计播放时长和播放次数？此操作不可撤销。",
                () -> {
                    PlaybackStats.clear(getApplicationContext());
                    com.qinghe.music163pro.util.UiKit.toast(this, "已清空");
                    render();
                },
                // 不可撤销，用警示色
                WatchConfirmDialog.Options.destructiveOptions());
    }

    private TextView makeText(String text, int color, int sizePx) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sizePx));
        return tv;
    }

    private View makeDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(0x1FFFFFFF);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dpToPx(1) / 2)));
        return divider;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
