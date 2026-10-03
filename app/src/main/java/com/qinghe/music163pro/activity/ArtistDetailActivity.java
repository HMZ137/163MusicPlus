package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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
import com.qinghe.music163pro.util.UiKit;
import com.qinghe.music163pro.util.WatchBackButton;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌手页：头像 / 名字 / 简介 + 热门 50 首 / 全部歌曲 + 收藏歌手。
 *
 * <p>入口：歌曲信息页的「查看歌手主页」。之前从歌曲点歌手名是死路，这里把"顺藤摸瓜听下去"补上。</p>
 *
 * <p>用到的接口（weapi）：{@code /api/v1/artist/{id}}（详情 + 热门 50）、
 * {@code /api/v1/artist/songs}（全部歌曲，order=hot|time）、{@code /api/artist/sub}（收藏歌手）。</p>
 */
public class ArtistDetailActivity extends BaseWatchActivity {

    private static final int ALL_SONGS_LIMIT = 100;

    private MusicPlayerManager playerManager;

    private long artistId;
    private String artistName;

    private TextView tvTitle;
    private TextView btnSubscribe;
    private ImageView ivAvatar;
    private TextView tvName;
    private TextView tvStat;
    private TextView tvDesc;
    private TextView btnHot;
    private TextView btnAll;

    private ListView lvSongs;
    private SongAdapter adapter;
    private final List<Song> songs = new ArrayList<>();

    /** 0 = 热门 50 首，1 = 全部歌曲。 */
    private int mode = 0;
    private boolean subscribed = false;
    private boolean loadedAll = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        artistId = getIntent().getLongExtra("artist_id", 0);
        artistName = getIntent().getStringExtra("artist_name");
        if (artistId <= 0) {
            Toast.makeText(this, "缺少歌手信息", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        playerManager = MusicPlayerManager.getInstance();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(px(8), px(6), px(8), px(6));
        setContentView(root);

        // ── 顶栏：返回占位 + 标题 + 收藏 ──
        android.widget.RelativeLayout titleBar = new android.widget.RelativeLayout(this);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        WatchBackButton.applyTitleBarInset(this, titleBar);

        tvTitle = new TextView(this);
        tvTitle.setText(artistName == null ? "歌手" : artistName);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.RelativeLayout.LayoutParams titleLp =
                new android.widget.RelativeLayout.LayoutParams(
                        0, android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleLp.addRule(android.widget.RelativeLayout.START_OF, 1);   // 占位，稍后由下方按钮覆盖
        tvTitle.setLayoutParams(titleLp);

        btnSubscribe = new TextView(this);
        btnSubscribe.setText("收藏");
        btnSubscribe.setTextSize(12);
        btnSubscribe.setGravity(Gravity.CENTER);
        btnSubscribe.setPadding(px(7), px(2), px(7), px(2));
        btnSubscribe.setClickable(true);
        btnSubscribe.setFocusable(true);
        applySubStyle(false);
        android.widget.RelativeLayout.LayoutParams subLp =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        subLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        subLp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        subLp.setMarginEnd(px(6));
        btnSubscribe.setLayoutParams(subLp);
        btnSubscribe.setOnClickListener(v -> toggleSubscribe());

        android.widget.RelativeLayout.LayoutParams titleCenterLp =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleCenterLp.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT);
        titleCenterLp.setMargins(0, 0, px(56), 0);
        tvTitle.setLayoutParams(titleCenterLp);

        titleBar.addView(tvTitle);
        titleBar.addView(btnSubscribe);
        root.addView(titleBar);

        // ── 头部：头像 + 名字 + 统计 ──
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerLp.setMargins(px(6), px(2), px(6), px(2));
        header.setLayoutParams(headerLp);

        ivAvatar = new ImageView(this);
        ivAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivAvatar.setBackgroundColor(0xFF252525);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(px(56), px(56));
        avatarLp.setMarginEnd(px(10));
        header.addView(ivAvatar, avatarLp);

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        tvName = new TextView(this);
        tvName.setText(artistName == null ? "" : artistName);
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvName.setSingleLine(true);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        infoCol.addView(tvName);

        tvStat = new TextView(this);
        tvStat.setTextColor(0x99FFFFFF);
        tvStat.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tvStat.setSingleLine(true);
        infoCol.addView(tvStat);

        header.addView(infoCol);
        root.addView(header);

        // ── 简介（最多 4 行）──
        tvDesc = new TextView(this);
        tvDesc.setTextColor(0x99FFFFFF);
        tvDesc.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tvDesc.setMaxLines(4);
        tvDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvDesc.setPadding(px(6), 0, px(6), px(4));
        tvDesc.setVisibility(View.GONE);
        root.addView(tvDesc);

        // ── 切换：热门 50 首 / 全部歌曲 ──
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        switchLp.setMargins(0, px(2), 0, px(3));
        switchRow.setLayoutParams(switchLp);

        btnHot = buildSwitchButton("热门 50 首");
        btnAll = buildSwitchButton("全部歌曲");
        LinearLayout.LayoutParams btnLp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp1.setMarginEnd(px(6));
        switchRow.addView(btnHot, btnLp1);
        switchRow.addView(btnAll);
        root.addView(switchRow);

        btnHot.setOnClickListener(v -> switchMode(0));
        btnAll.setOnClickListener(v -> switchMode(1));
        updateSwitchStyle();

        // ── 歌曲列表 ──
        lvSongs = new ListView(this);
        lvSongs.setDivider(null);
        lvSongs.setDividerHeight(0);
        lvSongs.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new SongAdapter();
        lvSongs.setAdapter(adapter);
        lvSongs.setOnItemClickListener((parent, view, position, id) -> playFrom(position));
        root.addView(lvSongs);

        loadArtist();
    }

    private void loadArtist() {
        MusicApiHelper.getArtistDetail(artistId, playerManager.getCookie(),
                new MusicApiHelper.ArtistDetailCallback() {
                    @Override
                    public void onResult(MusicApiHelper.ArtistDetail detail) {
                        if (detail.name != null && !detail.name.isEmpty()) {
                            artistName = detail.name;
                            tvTitle.setText(detail.name);
                            tvName.setText(detail.name);
                        }
                        if (detail.picUrl != null && !detail.picUrl.isEmpty()) {
                            NetworkImageLoader.load(ivAvatar, detail.picUrl);
                        }
                        StringBuilder stat = new StringBuilder();
                        if (detail.musicSize > 0) {
                            stat.append("歌曲 ").append(detail.musicSize);
                        }
                        if (detail.albumSize > 0) {
                            if (stat.length() > 0) {
                                stat.append(" · ");
                            }
                            stat.append("专辑 ").append(detail.albumSize);
                        }
                        tvStat.setText(stat.toString());
                        if (detail.briefDesc != null && !detail.briefDesc.isEmpty()) {
                            tvDesc.setText(detail.briefDesc.trim());
                            tvDesc.setVisibility(View.VISIBLE);
                        }
                        songs.clear();
                        songs.addAll(detail.hotSongs);
                        adapter.notifyDataSetChanged();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(ArtistDetailActivity.this, "歌手信息加载失败：" + message,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** 切换到「热门 50 首 / 全部歌曲」。 */
    private void switchMode(int newMode) {
        if (mode == newMode) {
            return;
        }
        mode = newMode;
        updateSwitchStyle();
        if (mode == 0) {
            if (!songs.isEmpty()) {
                adapter.notifyDataSetChanged();
            }
            return;
        }
        if (loadedAll) {
            return;
        }
        MusicApiHelper.getArtistSongs(artistId, 0, ALL_SONGS_LIMIT, "hot",
                playerManager.getCookie(), new MusicApiHelper.ArtistSongsCallback() {
                    @Override
                    public void onResult(List<Song> all) {
                        loadedAll = true;
                        songs.clear();
                        songs.addAll(all);
                        adapter.notifyDataSetChanged();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(ArtistDetailActivity.this, "歌曲加载失败：" + message,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void toggleSubscribe() {
        final boolean target = !subscribed;
        MusicApiHelper.subArtist(artistId, target, playerManager.getCookie(),
                new MusicApiHelper.ArtistSubCallback() {
                    @Override
                    public void onResult(boolean result) {
                        subscribed = result;
                        applySubStyle(subscribed);
                        Toast.makeText(ArtistDetailActivity.this,
                                subscribed ? "已收藏歌手" : "已取消收藏",
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(ArtistDetailActivity.this, "操作失败：" + message,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** 以歌手页列表为播放队列开始播放。 */
    private void playFrom(int position) {
        if (songs.isEmpty() || position < 0 || position >= songs.size()) {
            return;
        }
        List<Song> playlist = new ArrayList<>(songs);
        playerManager.setPlaylistFromSource(playlist, position,
                0L, artistName == null ? "歌手" : artistName, playlist.size(),
                artistName == null ? "" : artistName, 0, false);
        playerManager.playCurrent();
        startActivity(new Intent(this, MainActivity.class));
    }

    // ==================== 构件 ====================

    private TextView buildSwitchButton(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(px(10), px(3), px(10), px(3));
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }

    private void updateSwitchStyle() {
        applySwitchStyle(btnHot, mode == 0);
        applySwitchStyle(btnAll, mode == 1);
    }

    private void applySwitchStyle(TextView btn, boolean on) {
        if (on) {
            btn.setBackgroundResource(R.drawable.bg_btn_accent);
            btn.setTextColor(UiKit.COLOR_ON_ACCENT);
        } else {
            btn.setBackgroundResource(R.drawable.bg_btn_outline);
            btn.setTextColor(UiKit.COLOR_ACCENT);
        }
    }

    private void applySubStyle(boolean on) {
        if (on) {
            btnSubscribe.setBackgroundResource(R.drawable.bg_btn_accent);
            btnSubscribe.setTextColor(UiKit.COLOR_ON_ACCENT);
            btnSubscribe.setText("已收藏");
        } else {
            btnSubscribe.setBackgroundResource(R.drawable.bg_btn_outline);
            btnSubscribe.setTextColor(UiKit.COLOR_ACCENT);
            btnSubscribe.setText("收藏");
        }
    }

    /** 歌曲列表适配（复用 item_song 布局）。 */
    private class SongAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return songs.size();
        }

        @Override
        public Object getItem(int position) {
            return songs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return songs.get(position).getId();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_song, parent, false);
            }
            Song song = songs.get(position);
            TextView tvName = view.findViewById(R.id.tv_item_name);
            TextView tvArtist = view.findViewById(R.id.tv_item_artist);
            ImageView ivCover = view.findViewById(R.id.iv_cover);
            View tvProgress = view.findViewById(R.id.tv_item_progress);
            View ivCheck = view.findViewById(R.id.iv_item_check);

            tvName.setText(song.getDisplayName());
            tvArtist.setText(song.getArtist());
            if (tvProgress != null) {
                tvProgress.setVisibility(View.GONE);
            }
            if (ivCheck != null) {
                ivCheck.setVisibility(View.GONE);
            }
            if (ivCover != null && song.getCoverUrl() != null && !song.getCoverUrl().isEmpty()) {
                NetworkImageLoader.load(ivCover, song.getCoverUrl());
            }
            return view;
        }
    }
}
