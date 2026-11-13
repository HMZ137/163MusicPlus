package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.LocalMusicHides;
import com.qinghe.music163pro.util.WatchBackButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * F3：本地黑名单管理页（「本地设置 - 黑名单」）。
 *
 * <p><b>语义</b>：本地音乐页的「移出列表」（{@code MultiSelectController.Action.delete()}）
 * 只是把路径记进 {@link LocalMusicHides}，**从不删文件**（误删用户自己拷进来的音乐不可挽回）。
 * 本页就是那份名单的管理界面：列出被移除的曲目，**左划**即移出名单 ——
 * 移出后下一次扫描该曲目会重新出现在本地列表里。</p>
 *
 * <p><b>复用</b>：完全复用既有的 {@link LocalMusicHides#getHidden}/{@link LocalMusicHides#unhide}，
 * 不新建任何存储机制（文档 F3 改动要点 1）。</p>
 *
 * <p><b>左划实现</b>：本项目没有现成的 swipe-to-delete，这里给每一行挂一个最小实现：
 * 只有「横向位移超过阈值、且竖直位移小于阈值」才算左划，**避免误伤列表的竖直滚动与点击**。</p>
 */
public class HiddenTracksActivity extends BaseWatchActivity {

    /** 判定左划的最小横向位移（dp，换算成像素后用，不写死 px）。 */
    private static final int SWIPE_MIN_DP = 32;

    private ListView listView;
    private TextView emptyView;
    private final List<String> paths = new ArrayList<>();
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hidden_tracks);

        // 与其它页同一套：标题栏占位（返回按钮不会压住标题）+ G5 的标题居左由统一机制处理
        View titleBar = findViewById(R.id.layout_hidden_title_bar);
        WatchBackButton.applyTitleBarInset(this, titleBar);

        listView = findViewById(R.id.lv_hidden_tracks);
        emptyView = findViewById(R.id.tv_hidden_empty);

        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return paths.size();
            }

            @Override
            public Object getItem(int position) {
                return paths.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = convertView != null
                        ? convertView
                        : getLayoutInflater().inflate(R.layout.item_song, parent, false);
                String path = paths.get(position);
                TextView name = row.findViewById(R.id.tv_item_name);
                TextView artist = row.findViewById(R.id.tv_item_artist);
                if (name != null) {
                    name.setText(new File(path).getName());
                }
                if (artist != null) {
                    artist.setText(folderOf(path));
                }
                row.setOnTouchListener(new SwipeAwayListener(path));
                return row;
            }
        };
        listView.setAdapter(adapter);
        reload();
    }

    /** 从磁盘重读名单并刷新（每次移除后都重读，保证与文件一致）。 */
    private void reload() {
        paths.clear();
        paths.addAll(LocalMusicHides.getHidden(this));
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        boolean empty = paths.isEmpty();
        if (emptyView != null) {
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (listView != null) {
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private void removeFromBlacklist(String path) {
        if (LocalMusicHides.unhide(this, path)) {
            com.qinghe.music163pro.util.UiKit.toast(this, "已移出黑名单，下次扫描会重新出现");
            reload();
        }
    }

    private static String folderOf(String path) {
        File parent = new File(path).getParentFile();
        return parent == null ? "" : parent.getName();
    }

    /** 左划即移除的最小触摸实现（横向超阈值、竖直未超阈值才判定为左划）。 */
    private final class SwipeAwayListener implements View.OnTouchListener {
        private final String path;
        private float downX;
        private float downY;

        SwipeAwayListener(String path) {
            this.path = path;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    // ⚠️ 必须返回 true：否则本行**拿不到后续 MOVE 事件**（手势被父 ListView 接管），
                    // 左划就永远检测不到 —— 这是上一版实测"划了没反应"的原因。
                    // 不会因此挡住列表滚动：ListView 的 onInterceptTouchEvent 仍会在竖直位移
                    // 超过 touch slop 时把手势抢回去，正常上下滚动照旧。
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getX() - downX;
                    float dy = Math.abs(event.getY() - downY);
                    int min = (int) (SWIPE_MIN_DP * getResources().getDisplayMetrics().density + 0.5f);
                    if (dx < -min && dy < min) {
                        removeFromBlacklist(path);
                        return true;   // 左划已生效
                    }
                    return true;       // 继续接收 MOVE，等判定
                }
                default:
                    return true;
            }
        }
    }
}
