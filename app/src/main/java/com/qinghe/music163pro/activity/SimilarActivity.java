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

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.WatchBackButton;

import java.util.ArrayList;
import java.util.List;

/**
 * 相似内容页：一个页面同时承载「相似歌手」和「相似歌曲」两种模式，
 * 因为两者的骨架完全一样（顶部标题 + 一个列表），只有数据源和点击行为不同。
 *
 * <p>入口：歌手页的「相似歌手」按钮（{@code mode=artist}）、
 * 歌曲信息页的「相似歌曲」入口（{@code mode=song}）。</p>
 *
 * <p>用到的接口（weapi）：{@code /api/discovery/simiArtist?artistid=}、
 * {@code /api/v1/discovery/simiSong?songid=}。</p>
 */
public class SimilarActivity extends BaseWatchActivity {

    public static final String MODE_ARTIST = "artist";
    public static final String MODE_SONG = "song";

    private MusicPlayerManager playerManager;

    private String mode = MODE_ARTIST;
    private long targetId;
    private String targetTitle = "";

    private TextView tvEmpty;
    private ListView lvItems;

    private final List<MusicApiHelper.ArtistBrief> artists = new ArrayList<>();
    private final List<Song> songs = new ArrayList<>();
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mode = getIntent().getStringExtra("mode");
        if (mode == null) {
            mode = MODE_ARTIST;
        }
        targetId = getIntent().getLongExtra("id", 0);
        targetTitle = getIntent().getStringExtra("title");
        if (targetTitle == null) {
            targetTitle = "";
        }
        playerManager = MusicPlayerManager.getInstance();

        if (targetId <= 0) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(px(8), px(6), px(8), px(6));
        setContentView(root);

        // ── 顶栏：返回占位 + 标题 ──
        android.widget.RelativeLayout titleBar = new android.widget.RelativeLayout(this);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        WatchBackButton.applyTitleBarInset(this, titleBar);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(MODE_ARTIST.equals(mode) ? "相似歌手" : "相似歌曲");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.RelativeLayout.LayoutParams titleLp =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
titleLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        titleLp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        tvTitle.setLayoutParams(titleLp);
        titleBar.addView(tvTitle);
        // G5：标题居左；避让由统一入口按返回按钮占位动态补齐（勿手写像素）
        WatchBackButton.alignTitleStart(this, tvTitle);
        root.addView(titleBar);

        // ── 副标题：来自哪首歌 / 哪位歌手 ──
        TextView tvSub = new TextView(this);
        tvSub.setText(targetTitle);
        tvSub.setTextColor(0x99FFFFFF);
        tvSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tvSub.setGravity(Gravity.CENTER);
        tvSub.setSingleLine(true);
        tvSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvSub.setPadding(0, 0, 0, px(4));
        root.addView(tvSub);

        tvEmpty = new TextView(this);
        tvEmpty.setText("加载中…");
        tvEmpty.setTextColor(0x99FFFFFF);
        tvEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(0, px(24), 0, 0);
        root.addView(tvEmpty);

        lvItems = new ListView(this);
        lvItems.setDivider(null);
        lvItems.setDividerHeight(0);
        lvItems.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        lvItems.setVisibility(View.GONE);
        if (MODE_ARTIST.equals(mode)) {
            adapter = new ArtistAdapter();
            lvItems.setOnItemClickListener((parent, view, position, id) -> openArtist(position));
        } else {
            adapter = new SongAdapter();
            lvItems.setOnItemClickListener((parent, view, position, id) -> playFrom(position));
        }
        lvItems.setAdapter(adapter);
        root.addView(lvItems);

        load();
    }

    private void load() {
        String cookie = playerManager.getCookie();
        if (MODE_ARTIST.equals(mode)) {
            MusicApiHelper.getSimiArtistList(targetId, cookie, new MusicApiHelper.SimiArtistCallback() {
                @Override
                public void onResult(List<MusicApiHelper.ArtistBrief> result) {
                    artists.clear();
                    if (result != null) {
                        artists.addAll(result);
                    }
                    adapter.notifyDataSetChanged();
                    showEmpty(artists.isEmpty() ? "没有找到相似歌手" : null);
                }

                @Override
                public void onError(String message) {
                    showEmpty("加载失败：" + message);
                }
            });
        } else {
            MusicApiHelper.getSimiSongList(targetId, cookie, new MusicApiHelper.SongListCallback() {
                @Override
                public void onResult(List<Song> result) {
                    songs.clear();
                    if (result != null) {
                        songs.addAll(result);
                    }
                    adapter.notifyDataSetChanged();
                    showEmpty(songs.isEmpty() ? "没有找到相似歌曲" : null);
                }

                @Override
                public void onError(String message) {
                    showEmpty("加载失败：" + message);
                }
            });
        }
    }

    private void showEmpty(String text) {
        if (text == null) {
            tvEmpty.setVisibility(View.GONE);
            lvItems.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setText(text);
            tvEmpty.setVisibility(View.VISIBLE);
            lvItems.setVisibility(View.GONE);
        }
    }

    private void openArtist(int position) {
        if (position < 0 || position >= artists.size()) {
            return;
        }
        MusicApiHelper.ArtistBrief brief = artists.get(position);
        Intent intent = new Intent(this, ArtistDetailActivity.class);
        intent.putExtra("artist_id", brief.id);
        intent.putExtra("artist_name", brief.name);
        startActivity(intent);
    }

    private void playFrom(int position) {
        if (songs.isEmpty() || position < 0 || position >= songs.size()) {
            return;
        }
        List<Song> playlist = new ArrayList<>(songs);
        String sourceName = targetTitle.isEmpty()
                ? (MODE_ARTIST.equals(mode) ? "相似歌手" : "相似歌曲") : targetTitle;
        playerManager.setPlaylistFromSource(playlist, position, 0L, sourceName,
                playlist.size(), "", 0, false);
        playerManager.playCurrent();
        startActivity(new Intent(this, MainActivity.class));
    }

    // ==================== 适配器 ====================

    /** 相似歌手行：复用歌单行布局（头像 + 名字 + 统计）。 */
    private class ArtistAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return artists.size();
        }

        @Override
        public Object getItem(int position) {
            return artists.get(position);
        }

        @Override
        public long getItemId(int position) {
            return artists.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_playlist, parent, false);
            }
            MusicApiHelper.ArtistBrief brief = artists.get(position);
            TextView tvName = view.findViewById(R.id.tv_playlist_name);
            TextView tvInfo = view.findViewById(R.id.tv_playlist_info);
            ImageView ivCover = view.findViewById(R.id.iv_cover);

            tvName.setText(brief.name);
            StringBuilder info = new StringBuilder();
            if (brief.alias != null && !brief.alias.isEmpty()) {
                info.append(brief.alias);
            }
            if (brief.musicSize > 0) {
                if (info.length() > 0) {
                    info.append(" · ");
                }
                info.append("歌曲 ").append(brief.musicSize);
            }
            if (brief.albumSize > 0) {
                if (info.length() > 0) {
                    info.append(" · ");
                }
                info.append("专辑 ").append(brief.albumSize);
            }
            tvInfo.setText(info.toString());
            if (ivCover != null && brief.picUrl != null && !brief.picUrl.isEmpty()) {
                NetworkImageLoader.load(ivCover, brief.picUrl);
            }
            return view;
        }
    }

    /** 相似歌曲行：复用歌曲行布局。 */
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }
}
