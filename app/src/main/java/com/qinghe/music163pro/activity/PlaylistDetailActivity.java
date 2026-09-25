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

import androidx.appcompat.app.AppCompatActivity;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.manager.PlaylistManager;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.WatchConfirmDialog;
import com.qinghe.music163pro.util.WatchUiUtils;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Playlist detail activity - shows songs in a playlist and allows playing them.
 * Supports cloud mode (subscribe/unsubscribe via API) and local mode (local save).
 * Rules:
 * - Cloud mode: "我喜欢的音乐" → no unsub/delete; my created → no unsub, can delete;
 *   others' → can unsub, no delete.
 * - Local mode: all can unsub (local remove), no delete.
 * Designed for watch screen (320x360 dpi).
 */
public class PlaylistDetailActivity extends BaseWatchActivity {

    private ListView lvSongs;
    private final List<Song> displayList = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private MusicPlayerManager playerManager;
    private PlaylistManager playlistManager;
    private TextView tvStatus;
    private TextView tvTitle;
    private TextView tvCreatorLabel;
    private ImageView btnFav;
    private ImageView btnDelete;
    private long playlistId;
    private String playlistName;
    private int trackCount;
    private String creator;
    private long creatorUserId;
    private boolean isLikedPlaylist;
    private long currentUserId = -1;
    private boolean isCloudMode;
    private boolean isSubscribed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        isCloudMode = prefs.getBoolean("fav_mode_cloud", false);

        playlistId = getIntent().getLongExtra("playlist_id", 0);
        playlistName = getIntent().getStringExtra("playlist_name");
        trackCount = getIntent().getIntExtra("track_count", 0);
        creator = getIntent().getStringExtra("creator");
        creatorUserId = getIntent().getLongExtra("creator_user_id", 0);
        isLikedPlaylist = getIntent().getBooleanExtra("is_liked_playlist", false);

        playerManager = MusicPlayerManager.getInstance();
        playlistManager = new PlaylistManager();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(px(6), px(6), px(6), px(6));

        // Title row with heart + delete buttons on the right
        android.widget.RelativeLayout titleRow = new android.widget.RelativeLayout(this);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Title
        tvTitle = new TextView(this);
        updateTitleText();
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        tvTitle.setGravity(Gravity.CENTER);
        // 左右各留出可用空间：左边给浮动返回按钮，右边给「删除/收藏」那 44px 按钮
        // （按钮距右边缘还有一圈圆角安全内缩，所以右侧要比左侧多留一点）
        tvTitle.setPadding(px(56), 0, px(80), px(4));
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.RelativeLayout.LayoutParams titleParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        tvTitle.setLayoutParams(titleParams);
        titleRow.addView(tvTitle);

        // 删除按钮 —— 贴右上，但要留出「圆角安全内缩」，否则会被物理圆角切掉
        int corner = WatchUiUtils.cornerInset(this);
        btnDelete = new ImageView(this);
        btnDelete.setImageResource(R.drawable.ic_delete);
        btnDelete.setClickable(true);
        btnDelete.setFocusable(true);
        btnDelete.setId(View.generateViewId());
        // 触控区取 44px（原来 26x28 太小）；图标靠 padding 收到 ~20px 居中
        android.widget.RelativeLayout.LayoutParams delParams = new android.widget.RelativeLayout.LayoutParams(
                px(44), px(44));
        delParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        delParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        delParams.setMarginEnd(corner);
        btnDelete.setLayoutParams(delParams);
        btnDelete.setPadding(px(12), px(12), px(12), px(12));
        btnDelete.setVisibility(View.GONE);
        btnDelete.setOnClickListener(v -> onDeletePlaylist());
        titleRow.addView(btnDelete);

        // 收藏按钮 —— **直接贴父容器右端，不再用 LEFT_OF 删除按钮定位**。
        //
        // 原来写的是 LEFT_OF btnDelete，而 updateActionButtons() 里「别人的歌单」那一支
        // （雷达歌单就属于这种）会把 btnDelete 设为 GONE —— RelativeLayout 中相对一个 GONE
        // 的视图定位，依赖方会被甩到父容器最左侧。结果收藏按钮跑到左上角，正好压在浮动
        // 返回按钮底下：位置错、而且点不到。
        // 两个按钮在任何分支里都不会同时可见，所以各自贴右端即可，不会重叠。
        btnFav = new ImageView(this);
        btnFav.setClickable(true);
        btnFav.setFocusable(true);
        android.widget.RelativeLayout.LayoutParams favParams = new android.widget.RelativeLayout.LayoutParams(
                px(44), px(44));
        favParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        favParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        favParams.setMarginEnd(corner);
        btnFav.setLayoutParams(favParams);
        btnFav.setPadding(px(12), px(12), px(12), px(12));
        btnFav.setOnClickListener(v -> togglePlaylistFav());
        titleRow.addView(btnFav);

        root.addView(titleRow);

        // Creator label (always created, visibility depends on data)
        tvCreatorLabel = new TextView(this);
        tvCreatorLabel.setTextColor(0xB3FFFFFF);
        tvCreatorLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tvCreatorLabel.setGravity(Gravity.CENTER);
        tvCreatorLabel.setPadding(0, 0, 0, px(2));
        updateCreatorLabel();
        root.addView(tvCreatorLabel);

        // Status text
        tvStatus = new TextView(this);
        tvStatus.setText("正在加载...");
        tvStatus.setTextColor(0x80FFFFFF);
        tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, px(4), 0, px(4));
        root.addView(tvStatus);

        lvSongs = new ListView(this);
        lvSongs.setDividerHeight(1);
        lvSongs.setDivider(getResources().getDrawable(android.R.color.transparent));
        LinearLayout.LayoutParams lvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        lvSongs.setLayoutParams(lvParams);
        lvSongs.setVisibility(View.GONE);
        root.addView(lvSongs);

        setContentView(root);

        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, displayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText((position + 1) + ". " + song.getName());
                    tvArtist.setText(song.getArtist());
                    if (ivCover != null) {
                        NetworkImageLoader.load(ivCover, song.getCoverUrl());
                    }
                }
                return view;
            }
        };
        lvSongs.setAdapter(adapter);
        NetworkImageLoader.attachListViewOptimization(lvSongs, adapter, position ->
                position >= 0 && position < displayList.size()
                        ? displayList.get(position).getCoverUrl() : null);

        lvSongs.setOnItemClickListener((parent, view, position, id) -> {
            List<Song> playlist = new ArrayList<>(displayList);
            playerManager.setPlaylistFromSource(playlist, position,
                    playlistId, playlistName, trackCount, creator,
                    creatorUserId, isLikedPlaylist);
            playerManager.playCurrent();
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        // Long-press to delete song from user's created playlist (not liked playlist)
        // Works in both cloud and local mode (playlist lives on server regardless)
        lvSongs.setOnItemLongClickListener((parent, view, position, id) -> {
            if (isLikedPlaylist) return false;
            if (creatorUserId <= 0 || creatorUserId != currentUserId) return false;

            Song song = displayList.get(position);
            showConfirmDialog("确认删除", "确定从歌单中删除「" + song.getName() + "」？", () -> {
                String cookie = playerManager.getCookie();
                if (cookie == null || cookie.isEmpty()) {
                    Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(this, "正在删除...", Toast.LENGTH_SHORT).show();
                MusicApiHelper.playlistTracks("del", playlistId,
                        new long[]{song.getId()}, cookie,
                        new MusicApiHelper.PlaylistActionCallback() {
                            @Override
                            public void onResult(boolean success) {
                                if (success) {
                                    displayList.remove(position);
                                    adapter.notifyDataSetChanged();
                                    trackCount = displayList.size();
                                    updateTitleText();
                                    tvStatus.setText(trackCount + " 首歌曲");
                                    Toast.makeText(PlaylistDetailActivity.this,
                                            "已删除", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(PlaylistDetailActivity.this,
                                            "删除失败", Toast.LENGTH_SHORT).show();
                                }
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(PlaylistDetailActivity.this,
                                        "删除失败: " + message, Toast.LENGTH_SHORT).show();
                            }
                        });
            });
            return true;
        });

        // Always get current user ID for playlist type identification (liked/created/others')
        String cookie = playerManager.getCookie();
        if (cookie != null && !cookie.isEmpty()) {
            MusicApiHelper.getUid(cookie, new MusicApiHelper.AccountCallback() {
                @Override
                public void onResult(JSONObject json) {
                    currentUserId = json.optLong("uid", -1);
                    updateActionButtons();
                }
                @Override
                public void onError(String message) {
                    updateActionButtons();
                }
            });
        }

        updateFavButton();
        updateActionButtons();

        if (playlistId > 0) {
            loadPlaylistDetail(playlistId);
        }
    }

    private void updateTitleText() {
        String titleText = playlistName != null ? playlistName : "歌单";
        if (trackCount > 0) {
            titleText += " (" + trackCount + "首)";
        }
        tvTitle.setText(titleText);
    }

    private void updateCreatorLabel() {
        if (creator != null && !creator.isEmpty()) {
            tvCreatorLabel.setText("创建者: " + creator);
            tvCreatorLabel.setVisibility(View.VISIBLE);
        } else {
            tvCreatorLabel.setVisibility(View.GONE);
        }
    }

    private void loadPlaylistDetail(long playlistId) {
        String cookie = playerManager.getCookie();
        MusicApiHelper.getPlaylistDetailWithMeta(playlistId, cookie, new MusicApiHelper.PlaylistDetailWithMetaCallback() {
            @Override
            public void onResult(List<Song> songs, int apiTrackCount, String apiCreator,
                                 long apiCreatorUserId, int specialType, boolean subscribed) {
                displayList.clear();
                displayList.addAll(songs);
                adapter.notifyDataSetChanged();

                // Update metadata from API response (self-correct for all entry points)
                trackCount = songs.size();
                if (apiCreatorUserId > 0) {
                    creatorUserId = apiCreatorUserId;
                }
                isLikedPlaylist = (specialType == 5);
                isSubscribed = subscribed;
                if (apiCreator != null && !apiCreator.isEmpty()) {
                    creator = apiCreator;
                }

                updateTitleText();
                updateCreatorLabel();
                if (songs.isEmpty()) {
                    tvStatus.setText("暂无歌曲");
                } else {
                    tvStatus.setText(songs.size() + " 首歌曲");
                    lvSongs.setVisibility(View.VISIBLE);
                }

                // Refresh buttons with corrected metadata
                updateActionButtons();
            }

            @Override
            public void onError(String message) {
                tvStatus.setText("加载失败: " + message);
            }
        });
    }

    /**
     * Update button visibility based on playlist type (always identified regardless of mode):
     *   - "我喜欢的音乐" (specialType=5) → no unsub, no delete
     *   - My created playlist → no unsub, can delete
     *   - Others' playlist → can sub/unsub (cloud) or local save/remove, no delete
     */
    private void updateActionButtons() {
        if (isLikedPlaylist) {
            // "我喜欢的音乐" - cannot unsub or delete
            btnFav.setVisibility(View.GONE);
            btnDelete.setVisibility(View.GONE);
        } else if (creatorUserId > 0 && creatorUserId == currentUserId) {
            // My created playlist - cannot unsub but can delete
            btnFav.setVisibility(View.GONE);
            btnDelete.setVisibility(View.VISIBLE);
        } else {
            // Others' playlist - can sub/unsub or local save/remove, cannot delete
            btnFav.setVisibility(View.VISIBLE);
            btnDelete.setVisibility(View.GONE);
            updateFavButton();
        }
    }

    private void updateFavButton() {
        if (playlistId <= 0) {
            btnFav.setVisibility(View.GONE);
            return;
        }
        if (isCloudMode) {
            btnFav.setImageResource(isSubscribed ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            btnFav.setColorFilter(isSubscribed ? 0xFFFF4081 : 0x80FFFFFF);
        } else {
            boolean saved = playlistManager.isPlaylistSaved(playlistId);
            btnFav.setImageResource(saved ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            btnFav.setColorFilter(saved ? 0xFFFF4081 : 0x80FFFFFF);
        }
    }

    private void togglePlaylistFav() {
        if (playlistId <= 0) return;

        if (isCloudMode) {
            // Cloud mode: toggle between subscribe/unsubscribe based on current state
            String cookie = playerManager.getCookie();
            if (cookie == null || cookie.isEmpty()) {
                Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isSubscribed) {
                Toast.makeText(this, "正在取消收藏...", Toast.LENGTH_SHORT).show();
                MusicApiHelper.subscribePlaylist(playlistId, false, cookie,
                        new MusicApiHelper.PlaylistActionCallback() {
                    @Override
                    public void onResult(boolean success) {
                        if (success) {
                            isSubscribed = false;
                            playlistManager.removePlaylist(playlistId);
                            updateFavButton();
                            Toast.makeText(PlaylistDetailActivity.this,
                                    "已取消云端收藏", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(PlaylistDetailActivity.this,
                                    "取消收藏失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                    @Override
                    public void onError(String message) {
                        Toast.makeText(PlaylistDetailActivity.this,
                                "取消收藏失败: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                Toast.makeText(this, "正在收藏...", Toast.LENGTH_SHORT).show();
                MusicApiHelper.subscribePlaylist(playlistId, true, cookie,
                        new MusicApiHelper.PlaylistActionCallback() {
                    @Override
                    public void onResult(boolean success) {
                        if (success) {
                            isSubscribed = true;
                            updateFavButton();
                            Toast.makeText(PlaylistDetailActivity.this,
                                    "已收藏到云端", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(PlaylistDetailActivity.this,
                                    "收藏失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                    @Override
                    public void onError(String message) {
                        Toast.makeText(PlaylistDetailActivity.this,
                                "收藏失败: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } else {
            // Local mode: toggle local save
            boolean saved = playlistManager.isPlaylistSaved(playlistId);
            if (saved) {
                playlistManager.removePlaylist(playlistId);
                Toast.makeText(this, "已取消收藏歌单", Toast.LENGTH_SHORT).show();
            } else {
                PlaylistInfo info = new PlaylistInfo(playlistId,
                        playlistName != null ? playlistName : "",
                        trackCount, creator != null ? creator : "",
                        creatorUserId, true, isLikedPlaylist ? "5" : "0");
                playlistManager.addPlaylist(info);
                Toast.makeText(this, "已收藏歌单", Toast.LENGTH_SHORT).show();
            }
            updateFavButton();
        }
    }

    private void onDeletePlaylist() {
        if (playlistId <= 0) return;
        showConfirmDialog("确认删除", "确定从云端删除歌单「" + (playlistName != null ? playlistName : "") + "」？\n此操作不可恢复。", () -> {
            Toast.makeText(this, "正在删除...", Toast.LENGTH_SHORT).show();
            String cookie = playerManager.getCookie();
            MusicApiHelper.deletePlaylist(playlistId, cookie,
                    new MusicApiHelper.PlaylistActionCallback() {
                @Override
                public void onResult(boolean success) {
                    if (success) {
                        playlistManager.removePlaylist(playlistId);
                        Toast.makeText(PlaylistDetailActivity.this,
                                "已删除歌单", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(PlaylistDetailActivity.this,
                                "删除失败", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override
                public void onError(String message) {
                    Toast.makeText(PlaylistDetailActivity.this,
                            "删除失败: " + message, Toast.LENGTH_SHORT).show();
                }
            });
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
