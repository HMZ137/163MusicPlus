package com.qinghe.music163pro.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.PinyinUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Shows the user's playlists (created + subscribed) from the cloud.
 * Top-right "+" button to create a new playlist.
 * Clicking a playlist opens PlaylistDetailActivity with full ownership info.
 * Designed for watch screen (320x360 dpi).
 */
public class MyPlaylistsActivity extends BaseWatchActivity {

    private ListView lvPlaylists;
    private final List<PlaylistInfo> displayList = new ArrayList<>();
    private ArrayAdapter<PlaylistInfo> adapter;
    private TextView tvStatus;
    private long currentUserId = -1;

    /** 打开字母选择页的请求码。 */
    private static final int REQUEST_LETTER_PICKER = 0x52;
    /** 分组首字母（与 starts/counts 等长），传给字母选择页。 */
    private final List<Character> groupLetters = new ArrayList<>();
    private int[] groupStarts = new int[0];
    private int[] groupCounts = new int[0];
    /** 字母跳转后，onResume 会重新拉列表，这里先记下目标位，等拉完再跳过去。 */
    private int pendingScrollTo = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(px(6), px(6), px(6), px(6));

        // Title row with "+" button
        android.widget.RelativeLayout titleRow = new android.widget.RelativeLayout(this);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("我的歌单");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(px(28), 0, px(28), px(4));
        android.widget.RelativeLayout.LayoutParams titleParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        tvTitle.setLayoutParams(titleParams);
        titleRow.addView(tvTitle);

        // "+" button (top-right)
        ImageView btnCreate = new ImageView(this);
        btnCreate.setImageResource(R.drawable.ic_add_box);
        btnCreate.setColorFilter(0xFFBB86FC);
        btnCreate.setClickable(true);
        btnCreate.setFocusable(true);
        android.widget.RelativeLayout.LayoutParams btnParams = new android.widget.RelativeLayout.LayoutParams(
                px(24), px(24));
        btnParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        btnParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        int idCreate = View.generateViewId();
        btnCreate.setId(idCreate);
        btnCreate.setLayoutParams(btnParams);
        btnCreate.setOnClickListener(v -> showCreatePlaylistDialog());
        titleRow.addView(btnCreate);

        // 「A-Z」首字母跳转：复用 LetterPickerActivity + PinyinUtils（与本地音乐页同一套）。
        // 放在「+」左侧，标题居中、本就够短，不会和左右按钮打架。
        TextView btnAZ = new TextView(this);
        btnAZ.setText("A-Z");
        btnAZ.setTextColor(0xFFBB86FC);
        btnAZ.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        btnAZ.setPadding(px(8), px(4), px(8), px(4));
        btnAZ.setClickable(true);
        btnAZ.setFocusable(true);
        android.widget.RelativeLayout.LayoutParams azParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        azParams.addRule(android.widget.RelativeLayout.LEFT_OF, idCreate);
        azParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        btnAZ.setLayoutParams(azParams);
        btnAZ.setOnClickListener(v -> openLetterPicker());
        titleRow.addView(btnAZ);

        root.addView(titleRow);

        // Status text
        tvStatus = new TextView(this);
        tvStatus.setText("正在加载...");
        tvStatus.setTextColor(0x80FFFFFF);
        tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, px(4), 0, px(4));
        root.addView(tvStatus);

        lvPlaylists = new ListView(this);
        lvPlaylists.setDividerHeight(1);
        lvPlaylists.setDivider(getResources().getDrawable(android.R.color.transparent));
        LinearLayout.LayoutParams lvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        lvPlaylists.setLayoutParams(lvParams);
        lvPlaylists.setVisibility(View.GONE);
        root.addView(lvPlaylists);

        setContentView(root);

        adapter = new ArrayAdapter<PlaylistInfo>(this,
                R.layout.item_my_playlist, R.id.tv_name, displayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    view = getLayoutInflater().inflate(R.layout.item_my_playlist, parent, false);
                }
                PlaylistInfo pl = getItem(position);
                if (pl != null) {
                    ImageView icon = view.findViewById(R.id.iv_playlist_icon);
                    TextView tvName = view.findViewById(R.id.tv_name);
                    TextView tvDetail = view.findViewById(R.id.tv_detail);

                    // Load playlist cover image; fall back to static drawable if no cover URL
                    if (pl.getCoverUrl() != null && !pl.getCoverUrl().isEmpty()) {
                        icon.clearColorFilter();
                        NetworkImageLoader.load(icon, pl.getCoverUrl());
                    } else if (pl.isLikedPlaylist()) {
                        icon.setImageResource(R.drawable.ic_favorite);
                        icon.setColorFilter(0xFFFF4081);
                    } else if (pl.getUserId() == currentUserId) {
                        icon.setImageResource(R.drawable.ic_music_note);
                        icon.setColorFilter(0xFFBB86FC);
                    } else {
                        icon.setImageResource(R.drawable.ic_queue_music);
                        icon.setColorFilter(0x80FFFFFF);
                    }

                    tvName.setText(pl.getName());
                    tvName.setTextColor(0xFFFFFFFF);
                    tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));

                    String detail = pl.getTrackCount() + "首";
                    if (pl.getCreator() != null && !pl.getCreator().isEmpty()) {
                        detail += " · " + pl.getCreator();
                    }
                    tvDetail.setText(detail);
                    tvDetail.setTextColor(0xB3FFFFFF);
                    tvDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
                }
                return view;
            }
        };
        lvPlaylists.setAdapter(adapter);
        NetworkImageLoader.attachListViewOptimization(lvPlaylists, adapter, position ->
                position >= 0 && position < displayList.size()
                        ? displayList.get(position).getCoverUrl() : null);

        lvPlaylists.setOnItemClickListener((parent, view, position, id) -> {
            PlaylistInfo pl = displayList.get(position);
            Intent intent = new Intent(this, PlaylistDetailActivity.class);
            intent.putExtra("playlist_id", pl.getId());
            intent.putExtra("playlist_name", pl.getName());
            intent.putExtra("track_count", pl.getTrackCount());
            intent.putExtra("creator", pl.getCreator());
            intent.putExtra("creator_user_id", pl.getUserId());
            intent.putExtra("is_liked_playlist", pl.isLikedPlaylist());
            startActivity(intent);
        });

        lvPlaylists.setOnItemLongClickListener((parent, view, position, id) -> {
            PlaylistInfo pl = displayList.get(position);
            if (pl.isLikedPlaylist()) {
                // "我喜欢的音乐" – no actions
                return true;
            }
            String cookie = MusicPlayerManager.getInstance().getCookie();
            if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
                android.widget.Toast.makeText(this, "请先登录", android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
            if (pl.getUserId() == currentUserId) {
                // Own playlist: rename or delete
                showOwnPlaylistOptions(pl, cookie);
            } else {
                // Subscribed playlist: unsubscribe
                showUnsubscribeDialog(pl, cookie);
            }
            return true;
        });

        loadPlaylists();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh list when returning from detail (e.g. after delete/unsub)
        loadPlaylists();
    }

    private void loadPlaylists() {
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            tvStatus.setText("请先登录");
            return;
        }

        MusicApiHelper.getUserPlaylists(cookie, new MusicApiHelper.UserPlaylistsCallback() {
            @Override
            public void onResult(List<PlaylistInfo> playlists) {
                displayList.clear();
                displayList.addAll(playlists);
                // 按拼音首字母排序并建立首字母分组，供「A-Z」跳转使用
                sortAndIndex();
                // Detect current user ID from the first playlist's creator (if it's their "liked" playlist)
                if (!playlists.isEmpty() && playlists.get(0).isLikedPlaylist()) {
                    currentUserId = playlists.get(0).getUserId();
                }
                adapter.notifyDataSetChanged();
                // 字母跳转后回来：等列表重建完成再滚到目标位置
                if (pendingScrollTo >= 0) {
                    final int pos = pendingScrollTo;
                    pendingScrollTo = -1;
                    lvPlaylists.setSelection(pos);
                }
                if (playlists.isEmpty()) {
                    tvStatus.setText("暂无歌单");
                } else {
                    tvStatus.setText(playlists.size() + " 个歌单");
                    lvPlaylists.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onError(String message) {
                // 拉取失败，清掉待跳转，避免下次成功加载时误跳
                pendingScrollTo = -1;
                tvStatus.setText("加载失败: " + message);
            }
        });
    }

    // ==================== 首字母索引（复用 PinyinUtils + LetterPickerActivity） ====================

    /**
     * 按歌单名的拼音首字母排序，并建立「首字母 → 第一首下标」的分组。
     * 同字母内再按中文排序规则比名称，保证稳定字典序。
     */
    private void sortAndIndex() {
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(displayList, (left, right) -> {
            char leftInitial = PinyinUtils.initialOfWord(left.getName());
            char rightInitial = PinyinUtils.initialOfWord(right.getName());
            if (leftInitial != rightInitial) {
                // '#'（数字 / 符号 / 非汉字）永远排在最后
                if (leftInitial == PinyinUtils.OTHER) return 1;
                if (rightInitial == PinyinUtils.OTHER) return -1;
                return Character.compare(leftInitial, rightInitial);
            }
            int byName = collator.compare(left.getName(), right.getName());
            return byName;
        });
        buildLetterGroups();
    }

    /** 按排好序的列表生成分组信息，供字母选择页使用。 */
    private void buildLetterGroups() {
        java.util.LinkedHashMap<Character, Integer> firstIndex = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<Character, Integer> counts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < displayList.size(); i++) {
            char initial = PinyinUtils.initialOfWord(displayList.get(i).getName());
            if (!firstIndex.containsKey(initial)) {
                firstIndex.put(initial, i);
            }
            Integer count = counts.get(initial);
            counts.put(initial, count == null ? 1 : count + 1);
        }
        groupLetters.clear();
        groupStarts = new int[firstIndex.size()];
        groupCounts = new int[firstIndex.size()];
        int index = 0;
        for (java.util.Map.Entry<Character, Integer> entry : firstIndex.entrySet()) {
            groupLetters.add(entry.getKey());
            groupStarts[index] = entry.getValue();
            Integer count = counts.get(entry.getKey());
            groupCounts[index] = count == null ? 0 : count;
            index++;
        }
    }

    /** 打开独立的字母选择页（与本地音乐页同一套交互）。 */
    private void openLetterPicker() {
        if (groupLetters.isEmpty()) {
            Toast.makeText(this, "还没有可跳转的字母", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder letters = new StringBuilder(groupLetters.size());
        for (Character letter : groupLetters) {
            letters.append(letter.charValue());
        }
        Intent intent = new Intent(this, LetterPickerActivity.class);
        intent.putExtra(LetterPickerActivity.EXTRA_LETTERS, letters.toString());
        intent.putExtra(LetterPickerActivity.EXTRA_STARTS, groupStarts);
        intent.putExtra(LetterPickerActivity.EXTRA_COUNTS, groupCounts);
        intent.putExtra(LetterPickerActivity.EXTRA_CURRENT, lvPlaylists.getFirstVisiblePosition());
        startActivityForResult(intent, REQUEST_LETTER_PICKER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LETTER_PICKER || resultCode != RESULT_OK || data == null) {
            return;
        }
        int target = data.getIntExtra(LetterPickerActivity.EXTRA_RESULT_INDEX, -1);
        if (target < 0) {
            return;
        }
        // 记下来：onResume 会重新拉取列表，等拉取完成（列表重建）后再跳过去，
        // 否则刚 setSelection 就被 reload 冲掉位置。
        pendingScrollTo = Math.max(0, Math.min(target, displayList.size() - 1));
        lvPlaylists.setSelection(pendingScrollTo);
    }

    private void showOwnPlaylistOptions(PlaylistInfo pl, String cookie) {
        new AlertDialog.Builder(this)
                .setTitle(pl.getName())
                .setItems(new String[]{"改名", "删除"}, (dialog, which) -> {
                    if (which == 0) {
                        showRenamePlaylistDialog(pl, cookie);
                    } else {
                        showDeletePlaylistDialog(pl, cookie);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenamePlaylistDialog(PlaylistInfo pl, String cookie) {
        EditText input = new EditText(this);
        input.setText(pl.getName());
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);
        input.setSelectAllOnFocus(true);

        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(input, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("改名")
                .setView(container)
                .setPositiveButton("确认", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) {
                        android.widget.Toast.makeText(this, "名称不能为空", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    MusicApiHelper.updatePlaylistName(pl.getId(), newName, cookie,
                            new MusicApiHelper.PlaylistActionCallback() {
                                @Override
                                public void onResult(boolean success) {
                                    if (success) {
                                        android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                                "已重命名", android.widget.Toast.LENGTH_SHORT).show();
                                        loadPlaylists();
                                    } else {
                                        android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                                "改名失败", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                }
                                @Override
                                public void onError(String message) {
                                    android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                            "改名失败: " + message, android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeletePlaylistDialog(PlaylistInfo pl, String cookie) {
        new AlertDialog.Builder(this)
                .setTitle("删除歌单")
                .setMessage("确定删除「" + pl.getName() + "」？此操作不可撤销。")
                .setPositiveButton("删除", (d, w) -> {
                    MusicApiHelper.deletePlaylist(pl.getId(), cookie,
                            new MusicApiHelper.PlaylistActionCallback() {
                                @Override
                                public void onResult(boolean success) {
                                    if (success) {
                                        android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                                "已删除", android.widget.Toast.LENGTH_SHORT).show();
                                        loadPlaylists();
                                    } else {
                                        android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                                "删除失败", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                }
                                @Override
                                public void onError(String message) {
                                    android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                            "删除失败: " + message, android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showUnsubscribeDialog(PlaylistInfo pl, String cookie) {
        new AlertDialog.Builder(this)
                .setTitle("取消收藏")
                .setMessage("确定取消收藏歌单「" + pl.getName() + "」？")
                .setPositiveButton("确认", (d, w) -> {
                    MusicApiHelper.subscribePlaylist(pl.getId(), false, cookie,
                            new MusicApiHelper.PlaylistActionCallback() {
                                @Override
                                public void onResult(boolean success) {
                                    if (success) {
                                        android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                                "已取消收藏", android.widget.Toast.LENGTH_SHORT).show();
                                        loadPlaylists();
                                    } else {
                                        android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                                "操作失败", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                }
                                @Override
                                public void onError(String message) {
                                    android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                            "操作失败: " + message, android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCreatePlaylistDialog() {
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }

        EditText input = new EditText(this);
        input.setHint("输入歌单名称");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);

        LinearLayout container = new LinearLayout(this);
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("创建歌单")
                .setView(container)
                .setPositiveButton("创建", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "请输入歌单名称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createPlaylist(name, cookie);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createPlaylist(String name, String cookie) {
        Toast.makeText(this, "正在创建...", Toast.LENGTH_SHORT).show();
        MusicApiHelper.createPlaylist(name, 0, cookie, new MusicApiHelper.PlaylistCreateCallback() {
            @Override
            public void onResult(long playlistId, String resultName) {
                Toast.makeText(MyPlaylistsActivity.this,
                        "已创建「" + resultName + "」", Toast.LENGTH_SHORT).show();
                loadPlaylists();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MyPlaylistsActivity.this,
                        "创建失败: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }
}
