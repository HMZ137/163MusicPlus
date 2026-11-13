package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.WatchBackButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Album detail activity - shows album name, cover, and track list.
 * Designed for watch screen (320x360 dpi).
 */
public class AlbumDetailActivity extends BaseWatchActivity {

    private ListView lvSongs;
    private final List<Song> displayList = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private MusicPlayerManager playerManager;
    private TextView tvStatus;
    private ImageView ivAlbumCover;
    private TextView tvAlbumName;
    private TextView btnAlbumSub;

    private long albumId;
    private boolean albumSubscribed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        albumId = getIntent().getLongExtra("album_id", 0);
        String albumName = getIntent().getStringExtra("album_name");
        String albumCoverUrl = getIntent().getStringExtra("album_cover_url");

        playerManager = MusicPlayerManager.getInstance();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(px(6), px(6), px(6), px(6));

        // Title bar
        android.widget.RelativeLayout titleBar = new android.widget.RelativeLayout(this);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("专辑");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        android.widget.RelativeLayout.LayoutParams titleParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        // 右侧要放收藏按钮，标题往左留出占位，避免长专辑名压住按钮
        titleParams.setMargins(0, 0, px(56), 0);
        tvTitle.setLayoutParams(titleParams);
        titleBar.addView(tvTitle);

        // 收藏 / 已收藏（weapi /api/album/sub 与 /api/album/unsub）
        btnAlbumSub = new TextView(this);
        btnAlbumSub.setTextSize(12);
        btnAlbumSub.setGravity(Gravity.CENTER);
        btnAlbumSub.setPadding(px(7), px(2), px(7), px(2));
        btnAlbumSub.setClickable(true);
        btnAlbumSub.setFocusable(true);
        applyAlbumSubStyle(false);
        android.widget.RelativeLayout.LayoutParams subParams =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        subParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        subParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        subParams.setMarginEnd(px(6));
        btnAlbumSub.setLayoutParams(subParams);
        btnAlbumSub.setOnClickListener(v -> toggleAlbumSub());
        titleBar.addView(btnAlbumSub);
        root.addView(titleBar);

        // Album info row: cover + name
        LinearLayout albumInfoRow = new LinearLayout(this);
        albumInfoRow.setOrientation(LinearLayout.HORIZONTAL);
        albumInfoRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        albumInfoRow.setPadding(0, px(8), 0, px(8));
        albumInfoRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(albumInfoRow);

        ivAlbumCover = new ImageView(this);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(px(52), px(52));
        coverParams.setMarginEnd(px(8));
        ivAlbumCover.setLayoutParams(coverParams);
        ivAlbumCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivAlbumCover.setBackgroundColor(0xFF252525);
        albumInfoRow.addView(ivAlbumCover);

        tvAlbumName = new TextView(this);
        tvAlbumName.setText(albumName != null ? albumName : "");
        tvAlbumName.setTextColor(0xFFFFFFFF);
        tvAlbumName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        tvAlbumName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAlbumName.setMaxLines(2);
        tvAlbumName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tvAlbumName.setLayoutParams(nameParams);
        albumInfoRow.addView(tvAlbumName);

        // Load initial cover if provided via intent
        if (albumCoverUrl != null && !albumCoverUrl.isEmpty()) {
            NetworkImageLoader.load(ivAlbumCover, albumCoverUrl);
        }

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
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, titleBar);
        // G5：标题居左。**必须在 applyTitleBarInset 之后**调用 —— 该入口会把标题栏的 start
        // 内边距撑到「返回按钮占位」，此时累计内缩已经够了，这里不会再追加 margin。
        // 反序（先调用本方法）会把避让计两次：标题被推到 2×占位（实测 x=188），看着偏右。
        WatchBackButton.alignTitleStart(this, tvTitle);

        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, displayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText((position + 1) + ". " + song.getDisplayName());
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
            Song song = displayList.get(position);
            List<Song> playlist = new ArrayList<>(displayList);
            playerManager.setPlaylist(playlist, position);
            playerManager.playCurrent();
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        if (albumId > 0) {
            loadAlbumDetail(albumId);
            loadAlbumSubState(albumId);
        } else {
            tvStatus.setText("专辑信息不可用");
            btnAlbumSub.setVisibility(View.GONE);
        }
    }

    /**
     * 回显收藏按钮的初始状态。
     *
     * <p>走「收藏专辑列表」逐页找，不用 {@code /album/detail/dynamic} ——
     * 后者在本机网络下会一直不返回（连续 15s 读超时，实测）。</p>
     */
    private void loadAlbumSubState(long albumId) {
        String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            // 未登录谈不上"已收藏/未收藏"，隐藏按钮比显示一个必然失败的按钮好
            btnAlbumSub.setVisibility(View.GONE);
            return;
        }
        MusicApiHelper.isAlbumSubscribed(albumId, cookie, new MusicApiHelper.AlbumSubStateCallback() {
            @Override
            public void onResult(boolean subscribed) {
                albumSubscribed = subscribed;
                applyAlbumSubStyle(subscribed);
            }

            @Override
            public void onError(String message) {
                // 查不到状态就保持"未收藏"，按钮仍可点（点了会真的去收藏）
                btnAlbumSub.setVisibility(View.VISIBLE);
            }
        });
    }

    private void toggleAlbumSub() {
        if (albumId <= 0) {
            return;
        }
        String cookie = playerManager.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "请先登录");
            return;
        }
        final boolean target = !albumSubscribed;
        btnAlbumSub.setEnabled(false);
        MusicApiHelper.subAlbum(albumId, target, cookie, new MusicApiHelper.AlbumSubCallback() {
            @Override
            public void onResult(boolean subscribed) {
                btnAlbumSub.setEnabled(true);
                albumSubscribed = subscribed;
                applyAlbumSubStyle(subscribed);
                com.qinghe.music163pro.util.UiKit.toast(AlbumDetailActivity.this, subscribed ? "已收藏专辑" : "已取消收藏");
            }

            @Override
            public void onError(String message) {
                btnAlbumSub.setEnabled(true);
                Toast.makeText(AlbumDetailActivity.this, "操作失败：" + message,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void applyAlbumSubStyle(boolean on) {
        if (on) {
            btnAlbumSub.setBackgroundResource(R.drawable.bg_btn_accent);
            btnAlbumSub.setTextColor(com.qinghe.music163pro.util.UiKit.COLOR_ON_ACCENT);
            btnAlbumSub.setText("已收藏");
        } else {
            btnAlbumSub.setBackgroundResource(R.drawable.bg_btn_outline);
            btnAlbumSub.setTextColor(com.qinghe.music163pro.util.UiKit.COLOR_ACCENT);
            btnAlbumSub.setText("收藏");
        }
    }

    private void loadAlbumDetail(long albumId) {
        String cookie = playerManager.getCookie();
        MusicApiHelper.getAlbumDetail(albumId, cookie, new MusicApiHelper.AlbumDetailCallback() {
            @Override
            public void onResult(String albumName, String coverUrl, List<Song> songs) {
                if (!albumName.isEmpty()) {
                    tvAlbumName.setText(albumName);
                }
                if (!coverUrl.isEmpty()) {
                    NetworkImageLoader.load(ivAlbumCover, coverUrl);
                }
                displayList.clear();
                displayList.addAll(songs);
                adapter.notifyDataSetChanged();
                if (songs.isEmpty()) {
                    tvStatus.setText("暂无歌曲");
                    lvSongs.setVisibility(View.GONE);
                } else {
                    tvStatus.setText(songs.size() + " 首歌曲");
                    lvSongs.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onError(String message) {
                tvStatus.setText("加载失败: " + message);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }
}
