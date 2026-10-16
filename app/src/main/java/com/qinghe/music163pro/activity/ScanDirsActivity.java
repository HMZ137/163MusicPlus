package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.LocalScanScope;
import com.qinghe.music163pro.util.MusicLog;
import com.qinghe.music163pro.util.WatchBackButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * L6：自定义扫描目录管理（文档 L6 改动要点 3）。
 *
 * <p>文档对这块的要求（逐条落实）：</p>
 * <ul>
 *   <li>入口位于「本地歌曲设置」页之下（本页从那里进）；</li>
 *   <li>支持**添加与移除**：添加走完整路径输入；移除用**左划**（与黑名单页同一手势语义）；</li>
 *   <li>**必须处理目录可访问性**：不存在 / 不可读的目录在列表里**标出警告**，
 *       扫描时会跳过并提示，但**不会让整个扫描失败**（逻辑在 {@link LocalScanScope} 里）。</li>
 * </ul>
 *
 * <p>存储完全复用 {@link LocalScanScope}（`/sdcard/163Music/scan-dirs.json`），不新建机制。</p>
 */
public class ScanDirsActivity extends BaseWatchActivity {

    /** 判定左划的最小横向位移（dp）。 */
    private static final int SWIPE_MIN_DP = 32;

    private static final String TAG = "ScanDirs";

    private ListView listView;
    private TextView emptyView;
    private final List<String> dirs = new ArrayList<>();
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_dirs);

        View titleBar = findViewById(R.id.layout_scan_dirs_title_bar);
        WatchBackButton.applyTitleBarInset(this, titleBar);

        listView = findViewById(R.id.lv_scan_dirs);
        emptyView = findViewById(R.id.tv_scan_dirs_empty);

        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return dirs.size();
            }

            @Override
            public Object getItem(int position) {
                return dirs.get(position);
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
                String path = dirs.get(position);
                boolean usable = LocalScanScope.isUsableDir(new File(path));
                TextView name = row.findViewById(R.id.tv_item_name);
                TextView sub = row.findViewById(R.id.tv_item_artist);
                if (name != null) {
                    // 不可用的目录直接标出警告，别让用户以为"加了却没生效"
                    name.setText((usable ? "" : "⚠ ") + lastSegment(path));
                }
                if (sub != null) {
                    sub.setText(usable ? path : path + "（不可用，扫描时会跳过）");
                }
                row.setOnTouchListener(new SwipeAwayListener(path));
                return row;
            }
        };
        listView.setAdapter(adapter);

        View btnAdd = findViewById(R.id.btn_scan_add);
        if (btnAdd != null) {
            btnAdd.setOnClickListener(v -> promptAdd());
        }
        reload();
    }

    private void reload() {
        dirs.clear();
        dirs.addAll(LocalScanScope.getRawCustomDirs(this));
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        boolean empty = dirs.isEmpty();
        if (emptyView != null) {
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (listView != null) {
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private void promptAdd() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("/sdcard/我的音乐");
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.scan_dirs_title)
                .setMessage(R.string.scan_dirs_add_prompt)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    String path = input.getText() == null ? "" : input.getText().toString().trim();
                    if (path.isEmpty()) {
                        Toast.makeText(this, "路径不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean added = LocalScanScope.addCustomDir(this, path);
                    MusicLog.i("ScanDirs", "添加自定义扫描目录: " + path + " 结果=" + added
                            + " 可用=" + LocalScanScope.isUsableDir(new File(path)));
                    Toast.makeText(this, added ? "已添加（扫描时生效）"
                                    : (LocalScanScope.isUsableDir(new File(path)) ? "该目录已在列表中" : "已添加，但该目录当前不可用"),
                            Toast.LENGTH_SHORT).show();
                    reload();
                })
                .show();
    }

    private void remove(String path) {
        if (LocalScanScope.removeCustomDir(this, path)) {
            MusicLog.i("ScanDirs", "移除自定义扫描目录: " + path);
            Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show();
            reload();
        }
    }

    private static String lastSegment(String path) {
        if (path == null || path.isEmpty()) {
            return "(空)";
        }
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('/');
        return idx >= 0 && idx < p.length() - 1 ? p.substring(idx + 1) : p;
    }

    /**
     * 左划移除。注意：{@code ACTION_DOWN} **必须返回 true**，否则本行拿不到后续 MOVE
     * （手势被父 ListView 接管），左划永远检测不到 —— 这个坑在黑名单页已经踩过一次。
     */
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
                    MusicLog.i(TAG, "行触摸 DOWN x=" + downX + " y=" + downY + " path=" + path);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getX() - downX;
                    float dy = Math.abs(event.getY() - downY);
                    int min = (int) (SWIPE_MIN_DP * getResources().getDisplayMetrics().density + 0.5f);
                    // 低噪声诊断：只在横向位移明显时打（定位"左划到底有没有送到这一行"）
                    if (Math.abs(dx) > 20f) {
                        MusicLog.i(TAG, "行触摸 MOVE dx=" + dx + " dy=" + dy + " 阈值=" + min
                                + " 判定=" + (dx < -min && dy < min));
                    }
                    if (dx < -min && dy < min) {
                        remove(path);
                    }
                    return true;
                }
                default:
                    return true;
            }
        }
    }
}
