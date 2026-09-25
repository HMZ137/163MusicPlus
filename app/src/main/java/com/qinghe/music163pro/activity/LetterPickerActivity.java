package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.WatchBackButton;

import java.util.ArrayList;
import java.util.List;

/**
 * 字母选择页 —— 本地音乐的 A–Z 跳转入口，独立一屏。
 *
 * <p><b>为什么从「列表右侧的窄索引条」改成独立页面：</b>320px 宽的表盘上，竖排 20 多个
 * 字母只能压到 9sp，一列贴着屏幕最右边；手指点上去偏一点就滚到别处，实机反馈是
 * 「字母太小不好点」。拆成一屏大格子之后每格约 58×48px，触控面积是原来的 5 倍以上。</p>
 *
 * <p><b>只列出「真的有歌」的那些字母</b>，不像系统通讯录那样把 26 个字母全摆出来：
 * 手表屏幕小，密集页面上再塞一堆点不动的灰格子只会更难找。每个格子右下角标了该字母
 * 的曲目数，等于顺带给了一份「我的音乐按字母分布」的概览。</p>
 *
 * <p>本页不回传「选了哪个字母」，而是直接回传该字母第一首曲目在列表里的下标 ——
 * 调用方拿到就能 {@code setSelection()}，不需要知道字母是怎么分组的。</p>
 */
public class LetterPickerActivity extends BaseWatchActivity {

    /** 分组标题，一个字符一组，例如 {@code "ABZ#"}。 */
    public static final String EXTRA_LETTERS = "letters";
    /** 每个分组的首曲下标，与 {@link #EXTRA_LETTERS} 等长。 */
    public static final String EXTRA_STARTS = "starts";
    /** 每个分组的曲目数，与 {@link #EXTRA_LETTERS} 等长。 */
    public static final String EXTRA_COUNTS = "counts";
    /** 当前列表顶端曲目的下标，用来把所在分组高亮出来。传负数表示不高亮。 */
    public static final String EXTRA_CURRENT = "current";
    /** 回传：选中分组的第一首曲目下标。 */
    public static final String EXTRA_RESULT_INDEX = "result_index";

    /** 网格列数。5 列 × 320px 屏 = 每格约 58px，是能稳定点中的宽度。 */
    private static final int COLUMNS = 5;

    /** 一个分组。 */
    private static final class Group {
        final String label;
        final int start;
        final int count;

        Group(String label, int start, int count) {
            this.label = label;
            this.start = start;
            this.count = count;
        }
    }

    private final List<Group> groups = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_letter_picker);

        readGroups();

        TextView tvTitle = findViewById(R.id.tv_letter_picker_title);
        TextView tvEmpty = findViewById(R.id.tv_letter_picker_empty);
        GridView grid = findViewById(R.id.gv_letter_grid);

        if (groups.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            grid.setVisibility(View.GONE);
            if (tvTitle != null) {
                tvTitle.setText(R.string.letter_picker_title);
            }
            return;
        }

        if (tvTitle != null) {
            tvTitle.setText(getString(R.string.letter_picker_title)
                    + " · " + groups.size() + "组");
        }

        // GridView 是整宽铺开的，左上角会被浮动返回按钮压住第一格，所以整块下移。
        // 返回按钮的尺寸随屏宽缩放，这里必须问它要实际占位，不能写死。
        grid.setPadding(grid.getPaddingStart(),
                WatchBackButton.getReservedStartPadding(this),
                grid.getPaddingEnd(),
                grid.getPaddingBottom());
        grid.setNumColumns(COLUMNS);
        grid.setAdapter(new LetterAdapter());
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= groups.size()) {
                return;
            }
            // 回传的是「该字母第一首的下标」，调用方直接拿去 setSelection
            setResult(RESULT_OK, new Intent()
                    .putExtra(EXTRA_RESULT_INDEX, groups.get(position).start));
            finish();
        });
    }

    private void readGroups() {
        groups.clear();
        // 优先用调用方给的数组；长度对不上就当成「没有可跳转的字母」处理，
        // 免得按下标取值时越界。
        String letters = getIntent().getStringExtra(EXTRA_LETTERS);
        int[] starts = getIntent().getIntArrayExtra(EXTRA_STARTS);
        int[] counts = getIntent().getIntArrayExtra(EXTRA_COUNTS);
        if (letters == null || starts == null || counts == null) {
            return;
        }
        int size = Math.min(letters.length(), Math.min(starts.length, counts.length));
        for (int i = 0; i < size; i++) {
            groups.add(new Group(String.valueOf(letters.charAt(i)), starts[i], counts[i]));
        }
    }

    /**
     * 网格适配器。
     *
     * <p>格子用代码拼而不是写 item 布局：这里只有「大字母 + 小计数」两个控件，
     * 而且字号需要跟着屏宽缩放（{@link #px(int)}），写进 XML 反而要额外为每档
     * 分辨率维护一份 dimens。</p>
     */
    private final class LetterAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return groups.size();
        }

        @Override
        public Object getItem(int position) {
            return groups.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Group group = groups.get(position);

            // 复用条件：是这一套格子（一个 LinearLayout 恰好两个孩子）。
            // 不用 findViewByid 是为了不引入新的 id 资源 —— 这个格子只在代码里用，
            // 没有 XML 引用它，为它单开一个 ids.xml 不值得。
            LinearLayout cell = null;
            if (convertView instanceof LinearLayout
                    && ((LinearLayout) convertView).getChildCount() == 2) {
                cell = (LinearLayout) convertView;
            }

            TextView tvLetter;
            TextView tvCount;
            if (cell != null) {
                tvLetter = (TextView) cell.getChildAt(0);
                tvCount = (TextView) cell.getChildAt(1);
            } else {
                cell = new LinearLayout(LetterPickerActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setBackgroundResource(R.drawable.bg_letter_cell);
                cell.setMinimumHeight(px(46));

                tvLetter = new TextView(LetterPickerActivity.this);
                tvLetter.setGravity(Gravity.CENTER);
                tvLetter.setTextSize(18);
                tvLetter.setIncludeFontPadding(false);
                cell.addView(tvLetter, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                tvCount = new TextView(LetterPickerActivity.this);
                tvCount.setGravity(Gravity.CENTER);
                tvCount.setTextSize(9);
                tvCount.setTextColor(getResources().getColor(R.color.text_secondary));
                tvCount.setIncludeFontPadding(false);
                LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                countParams.topMargin = px(1);
                cell.addView(tvCount, countParams);
            }

            tvLetter.setText(group.label);
            // 列表当前停留在这个分组里的话，用主色点出来 —— 相当于把「我现在在哪儿」
            // 从原来那条索引条手里接过来
            boolean current = position == indexOfCurrentGroup();
            tvLetter.setTextColor(getResources().getColor(
                    current ? R.color.colorPrimary : R.color.text_primary));
            tvCount.setText(getString(R.string.letter_picker_count, group.count));
            return cell;
        }
    }

    /** 当前列表顶端那首歌属于哪一组。拿不到（没传 / 传负数）时返回 -1。 */
    private int indexOfCurrentGroup() {
        int current = getIntent().getIntExtra(EXTRA_CURRENT, -1);
        if (current < 0) {
            return -1;
        }
        for (int i = groups.size() - 1; i >= 0; i--) {
            if (groups.get(i).start <= current) {
                return i;
            }
        }
        return -1;
    }
}
