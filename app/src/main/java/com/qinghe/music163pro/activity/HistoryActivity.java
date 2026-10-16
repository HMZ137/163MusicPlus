package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.WatchConfirmDialog;
import com.qinghe.music163pro.util.MultiSelectController;
import com.qinghe.music163pro.util.BatchFavoriteHelper;
import com.qinghe.music163pro.manager.HistoryManager;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.WatchBackButton;

import java.util.ArrayList;
import java.util.List;

/**
 * History activity - shows play history sorted by time (newest first).
 * 长按进入多选，可批量收藏 / 批量删除播放记录。
 * Designed for watch screen (320x360 dpi).
 */
public class HistoryActivity extends BaseWatchActivity {

    private ListView lvHistory;
    private final List<Song> displayList = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private MusicPlayerManager playerManager;
    private TextView tvEmpty;
    private MultiSelectController multiSelect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        playerManager = MusicPlayerManager.getInstance();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF212121);
        root.setPadding(px(6), px(6), px(6), px(6));

        // Title bar with clear button - use RelativeLayout for true centering
        android.widget.RelativeLayout titleBar = new android.widget.RelativeLayout(this);
        titleBar.setPadding(0, 0, 0, px(6));
        root.addView(titleBar);

        TextView btnClear = new TextView(this);
        btnClear.setText("清空");
        btnClear.setTextColor(0xFFBB86FC);
        btnClear.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        btnClear.setPadding(px(8), px(4), px(8), px(4));
        btnClear.setClickable(true);
        btnClear.setFocusable(true);
        btnClear.setId(View.generateViewId());
        btnClear.setOnClickListener(v -> showClearConfirmDialog());
        android.widget.RelativeLayout.LayoutParams clearParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        clearParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        clearParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        btnClear.setLayoutParams(clearParams);
        titleBar.addView(btnClear);

        TextView title = new TextView(this);
        title.setText("历史记录");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        title.setGravity(Gravity.CENTER);
        android.widget.RelativeLayout.LayoutParams titleParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        title.setLayoutParams(titleParams);
        titleBar.addView(title);

        tvEmpty = new TextView(this);
        tvEmpty.setText("暂无播放记录");
        tvEmpty.setTextColor(0x80FFFFFF);
        tvEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(0, px(40), 0, 0);
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty);

        lvHistory = new ListView(this);
        lvHistory.setDividerHeight(1);
        lvHistory.setDivider(getResources().getDrawable(android.R.color.transparent));
        LinearLayout.LayoutParams lvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        lvHistory.setLayoutParams(lvParams);
        root.addView(lvHistory);

        setContentView(root);
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, titleBar);
        // G5：标题居左。**必须在 applyTitleBarInset 之后**调用 —— 该入口会把标题栏的 start
        // 内边距撑到「返回按钮占位」，此时累计内缩已经够了，这里不会再追加 margin。
        // 反序（先调用本方法）会把避让计两次：标题被推到 2×占位（实测 x=188），看着偏右。
        WatchBackButton.alignTitleStart(this, title);

        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, displayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText(song.getDisplayName());
                    tvArtist.setText(song.getArtist());
                    if (ivCover != null) {
                        NetworkImageLoader.load(ivCover, song.getCoverUrl());
                    }
                }
                return view;
            }
        };
        lvHistory.setAdapter(adapter);
        NetworkImageLoader.attachListViewOptimization(lvHistory, adapter, position ->
                position >= 0 && position < displayList.size()
                        ? displayList.get(position).getCoverUrl() : null);

        lvHistory.setOnItemClickListener((parent, view, position, id) -> {
            Song song = displayList.get(position);
            List<Song> playlist = new ArrayList<>(displayList);
            playerManager.setPlaylist(playlist, position);
            playerManager.playCurrent();
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        // 长按进入多选模式，多选后可通过动作栏批量收藏或删除播放记录
        multiSelect = MultiSelectController.attach(this, lvHistory,
                new MultiSelectController.Callback() {
                    @Override
                    public List<MultiSelectController.Action> getActions() {
                        List<MultiSelectController.Action> actions = new ArrayList<>();
                        actions.add(MultiSelectController.Action.favorite(HistoryActivity.this));
                        actions.add(MultiSelectController.Action.delete(HistoryActivity.this));
                        return actions;
                    }

                    @Override
                    public void onAction(MultiSelectController.Action action, List<Integer> positions) {
                        if (MultiSelectController.Action.ID_FAVORITE.equals(action.id)) {
                            favoriteSelected(positions);
                        } else if (MultiSelectController.Action.ID_DELETE.equals(action.id)) {
                            confirmDeleteSelected(positions);
                        }
                    }
                });
        if (multiSelect != null) {
            addBackInterceptor(multiSelect::handleBackPressed);
        }

        loadHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 列表数据在 onResume 会重新加载，选中下标可能失效，先退出多选。
        if (multiSelect != null) {
            multiSelect.exitSelectionMode();
        }
        loadHistory();
    }

    /**
     * 删除选中的播放记录。
     */
    private void confirmDeleteSelected(List<Integer> positions) {
        final List<Long> songIds = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < displayList.size()) {
                songIds.add(displayList.get(position).getId());
            }
        }
        if (songIds.isEmpty()) {
            return;
        }
        showConfirmDialog("确认删除",
                "确定删除选中的 " + songIds.size() + " 条播放记录？", () -> {
                    for (Long songId : songIds) {
                        HistoryManager.getInstance().removeFromHistory(songId);
                    }
                    if (multiSelect != null) {
                        multiSelect.exitSelectionMode();
                    }
                    loadHistory();
                    Toast.makeText(HistoryActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * 批量收藏选中的播放记录里的歌曲。
     *
     * <p>收藏口径与单曲收藏保持一致：设置里切到「云端收藏」时走网易云接口（需要登录），
     * 否则写入本地收藏。详见 {@link BatchFavoriteHelper}。</p>
     */
    private void favoriteSelected(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < displayList.size()) {
                targets.add(displayList.get(position));
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        Toast.makeText(this, getString(R.string.multi_select_favoriting, targets.size()),
                Toast.LENGTH_SHORT).show();
        BatchFavoriteHelper.favoriteAll(this, targets, new BatchFavoriteHelper.Callback() {
            @Override
            public void onFinished(int added, int skipped, int failed, int total) {
                if (multiSelect != null) {
                    multiSelect.exitSelectionMode();
                }
                Toast.makeText(HistoryActivity.this,
                        BatchFavoriteHelper.buildResultMessage(HistoryActivity.this, added, failed),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNeedLogin() {
                Toast.makeText(HistoryActivity.this,
                        R.string.multi_select_favorite_need_login, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadHistory() {
        displayList.clear();
        displayList.addAll(HistoryManager.getInstance().getHistory());
        adapter.notifyDataSetChanged();

        if (displayList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            lvHistory.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            lvHistory.setVisibility(View.VISIBLE);
        }

        // Fetch coverUrls from API in background
        if (!displayList.isEmpty()) {
            String cookie = playerManager.getCookie();
            if (cookie != null && !cookie.isEmpty()) {
                List<Long> ids = new ArrayList<>();
                for (Song s : displayList) {
                    ids.add(s.getId());
                }
                com.qinghe.music163pro.api.MusicApiHelper.fetchSongsDetails(ids, cookie,
                        new com.qinghe.music163pro.api.MusicApiHelper.BatchSongDetailsCallback() {
                    @Override
                    public void onResult(java.util.Map<Long, Song> songMap) {
                        if (songMap.isEmpty()) return;
                        for (int i = 0; i < displayList.size(); i++) {
                            Song local = displayList.get(i);
                            Song fresh = songMap.get(local.getId());
                            if (fresh != null && fresh.getCoverUrl() != null && !fresh.getCoverUrl().isEmpty()) {
                                local.setCoverUrl(fresh.getCoverUrl());
                            }
                        }
                        adapter.notifyDataSetChanged();
                    }

                    @Override
                    public void onError(String message) {
                        // Keep local data, silently ignore
                    }
                });
            }
        }
    }

    private void showClearConfirmDialog() {
        showConfirmDialog("确认清空", "确定清空所有播放记录？", () -> {
            HistoryManager.getInstance().clearHistory();
            loadHistory();
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
        });
    }
    /**
     * Show a confirmation dialog adapted for watch (360x320 px screen).
     */
    private void showConfirmDialog(String title, String message, Runnable onConfirm) {
        WatchConfirmDialog.show(this, title, message, onConfirm,
                WatchConfirmDialog.Options.defaultOptions());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }
}
