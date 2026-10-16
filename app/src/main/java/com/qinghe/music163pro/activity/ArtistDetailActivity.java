package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
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
    private TextView btnSimilar;

    /** 头部里「原位」的切换行（随列表滚动）。同时用于计算吸顶时机。 */
    private LinearLayout switchRow;
    /** 叠在列表上方的吸顶副本；头部里那份滚出顶部时显示。 */
    private LinearLayout stickyTabsRow;
    private TextView btnHotSticky;
    private TextView btnAllSticky;

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

        // ── 可滚动的头部：头像 / 名字 / 统计 / 简介 / 切换按钮 / 相似歌手 ──
        // 这些内容以前直接挂在 root 上，是「固定」的：在 378×496 的表盘上，
        // 它们连同顶栏一起把歌曲列表挤到只剩两三行（几乎看不见作品）。
        // 现在统一装进 ListView 的 header view，随列表一起滚 —— 往下滑一次
        // 头部让开，整屏都留给作品列表。
        //
        // 两个必须遵守的约束：
        //   1) addHeaderView 必须在 setAdapter 之前调用；
        //   2) header 自身的 LayoutParams 必须是 AbsListView.LayoutParams
        //      （ListView 内部按该类型取尺寸/强转，用 LinearLayout.LayoutParams 会 class cast）。
        LinearLayout listHeader = new LinearLayout(this);
        listHeader.setOrientation(LinearLayout.VERTICAL);
        listHeader.setLayoutParams(new AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT,
                AbsListView.LayoutParams.WRAP_CONTENT));

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
titleCenterLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        titleCenterLp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        titleCenterLp.setMargins(0, 0, px(56), 0);
        tvTitle.setLayoutParams(titleCenterLp);

        titleBar.addView(tvTitle);
        // G5：标题居左；避让由统一入口按返回按钮占位动态补齐（勿手写像素）
        WatchBackButton.alignTitleStart(this, tvTitle);
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
        listHeader.addView(header);

        // ── 简介（最多 4 行）──
        tvDesc = new TextView(this);
        tvDesc.setTextColor(0x99FFFFFF);
        tvDesc.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
        tvDesc.setMaxLines(4);
        tvDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvDesc.setPadding(px(6), 0, px(6), px(4));
        tvDesc.setVisibility(View.GONE);
        listHeader.addView(tvDesc);

        // ── 切换：热门 50 首 / 全部歌曲（吸顶）──
        // 需求：滑到列表深处时也要能切模式，不必一路滑回顶部。
        // 做法是经典的 sticky header：头部里放一份「原位」切换行（随列表自然滚动、位置对），
        // 另外在列表上方叠一份完全同构的「吸顶」副本；当原位那份滚过列表顶部就显示副本。
        // 两份共用 applySwitchStyle / switchMode，所以高亮与行为永远一致，不存在状态不同步。
        switchRow = new LinearLayout(this);
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
        listHeader.addView(switchRow);

        btnHot.setOnClickListener(v -> switchMode(0));
        btnAll.setOnClickListener(v -> switchMode(1));

        // 吸顶副本：与原位那份尺寸完全一致（原位是 margin 2/3，这里用等价 padding 2/3，
        // 这样交接时按钮位置不跳），背景取页面底色以盖住下面滚过的列表内容。
        stickyTabsRow = new LinearLayout(this);
        stickyTabsRow.setOrientation(LinearLayout.HORIZONTAL);
        stickyTabsRow.setGravity(Gravity.CENTER);
        stickyTabsRow.setBackgroundColor(0xFF121212);
        stickyTabsRow.setPadding(0, px(2), 0, px(3));
        stickyTabsRow.setVisibility(View.GONE);

        btnHotSticky = buildSwitchButton("热门 50 首");
        btnAllSticky = buildSwitchButton("全部歌曲");
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp2.setMarginEnd(px(6));
        stickyTabsRow.addView(btnHotSticky, btnLp2);
        stickyTabsRow.addView(btnAllSticky);

        btnHotSticky.setOnClickListener(v -> switchMode(0));
        btnAllSticky.setOnClickListener(v -> switchMode(1));
        updateSwitchStyle();

        // ── 「相似歌手」单独占一行、通栏 ──
        // 它是导航不是模式切换；早先挤在上一行里，三个按钮在 270dpi 表盘上放不下，
        // 「相似歌手」被折成竖排四个字（实机截图确认）。通栏还顺带把它和上面两个
        // 模式按钮在视觉上区分开。
        LinearLayout similarRow = new LinearLayout(this);
        similarRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams similarRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        similarRowLp.setMargins(0, 0, 0, px(3));
        similarRow.setLayoutParams(similarRowLp);

        btnSimilar = buildSwitchButton("相似歌手");
        applySwitchStyle(btnSimilar, false);   // 永远描边样式，不参与高亮
        btnSimilar.setOnClickListener(v -> openSimilarArtists());
        similarRow.addView(btnSimilar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        listHeader.addView(similarRow);

        // ── 歌曲列表 ──
        // 外面套一层 FrameLayout：吸顶切换行要「叠」在列表之上，而不是参与 LinearLayout 排版。
        // 如果让它留在排版流里，GONE->VISIBLE 会改变高度、把列表整体往下顶 —— 会出现跳变。
        FrameLayout listHolder = new FrameLayout(this);
        listHolder.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        lvSongs = new ListView(this);
        lvSongs.setDivider(null);
        lvSongs.setDividerHeight(0);
        // header 里的「热门 50 首 / 全部歌曲 / 相似歌手」是可点按钮。
        // ListView 默认 itemsCanFocus=false，会屏蔽子视图的点击 —— 必须打开，
        // 否则这些按钮全部失效。列表行（item_song 根布局）本身不可聚焦，
        // 所以打开后歌曲行的点击回调照常触发，两者互不干扰。
        lvSongs.setItemsCanFocus(true);
        lvSongs.addHeaderView(listHeader, null, false);   // 必须在 setAdapter 之前
        adapter = new SongAdapter();
        lvSongs.setAdapter(adapter);
        lvSongs.setOnItemClickListener((parent, view, position, id) -> playFrom(position));
        lvSongs.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem,
                                 int visibleItemCount, int totalItemCount) {
                updateStickyTabs();
            }
        });

        FrameLayout.LayoutParams stickyLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        stickyLp.gravity = Gravity.TOP;

        listHolder.addView(lvSongs, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        listHolder.addView(stickyTabsRow, stickyLp);
        root.addView(listHolder);

        loadArtist();
    }

    /**
     * 决定「热门/全部」切换行是否该吸顶。
     *
     * <p>判据：原位切换行是否已被滚到列表顶部之上。header 是 ListView 的位置 0，
     * 只要它还在可见区，{@code getChildAt(0)} 就是它，{@code -getTop()} 即已滚过的距离；
     * 一旦 header 整体滚出，firstVisibleItem 会变成 1，此时直接认定已吸顶。</p>
     *
     * <p>阈值减掉 paddingTop：原位那份用 margin、吸顶副本用等价 padding，
     * 减掉这一段才能让交接瞬间按钮落在同一像素上，不出现跳动。</p>
     */
    private void updateStickyTabs() {
        if (lvSongs == null || switchRow == null || stickyTabsRow == null) {
            return;
        }
        boolean sticky;
        if (lvSongs.getFirstVisiblePosition() == 0) {
            View headerView = lvSongs.getChildAt(0);
            int scrolled = headerView == null ? 0 : -headerView.getTop();
            sticky = scrolled >= switchRow.getTop() - px(2);
        } else {
            sticky = true;
        }
        int want = sticky ? View.VISIBLE : View.GONE;
        if (stickyTabsRow.getVisibility() != want) {
            stickyTabsRow.setVisibility(want);
        }
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

    /** 打开「相似歌手」页（/simi/artist），顺着听下去时用来换口味。 */
    private void openSimilarArtists() {
        Intent intent = new Intent(this, SimilarActivity.class);
        intent.putExtra("mode", SimilarActivity.MODE_ARTIST);
        intent.putExtra("id", artistId);
        intent.putExtra("title", artistName == null ? "" : artistName);
        startActivity(intent);
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
        // 头部已作为 ListView 的 header view，占掉位置 0..n-1：
        // onItemClick 回调里的 position 是「含 header 的绝对位置」，必须先扣除，
        // 否则点第 1 首会播成第 2 首（经典的 header 偏移一位 bug）。
        int dataPosition = position - lvSongs.getHeaderViewsCount();
        if (songs.isEmpty() || dataPosition < 0 || dataPosition >= songs.size()) {
            return;
        }
        List<Song> playlist = new ArrayList<>(songs);
        playerManager.setPlaylistFromSource(playlist, dataPosition,
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
        // 吸顶副本必须一起更新，否则两份会在视觉上不同步（一份高亮一份描边）
        if (btnHotSticky != null) {
            applySwitchStyle(btnHotSticky, mode == 0);
        }
        if (btnAllSticky != null) {
            applySwitchStyle(btnAllSticky, mode == 1);
        }
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
