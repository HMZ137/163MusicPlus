package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.fragment.ArtistAllSongsFragment;
import com.qinghe.music163pro.fragment.ArtistHotSongsFragment;
import com.qinghe.music163pro.fragment.ArtistPageFragment;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.MusicLog;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.WatchBackButton;
import com.qinghe.music163pro.util.WatchMenuDialog;

/**
 * 歌手页（P8：ViewPager2 + Fragment 迁移后的薄宿主）。
 *
 * <p><b>迁移口径</b>（需求方 2026-10-17，同 4-12 / 4-16）：多 Tab 页先迁到 ViewPager2 + Fragment，
 * 迁移后<b>不保留多 Tab 按钮</b>；标题栏只留「返回 / 标题 / 菜单」（☰ 矢量图标、与标题同一行）；
 * 「其他需要展示的内容」放到标题栏外的独立行 —— 本页这一行显示「当前页 · 数量」，
 * 左右滑切页时跟随。</p>
 *
 * <p>迁移前「热门 50 首 / 全部歌曲」是一个 ListView 里的两个切换按钮（外加一份吸顶副本）＋
 * 一份共享的歌曲列表；现在两个模式各是一页：
 * {@link ArtistHotSongsFragment}（直接用歌手详情里的 hotSongs）与
 * {@link ArtistAllSongsFragment}（{@code /api/v1/artist/songs}，上限 100 首）。
 * 歌手资料（头像 / 名字 / 歌曲·专辑数 / 简介）放在两页列表的 header 里随列表滚动 ——
 * 固定在标题栏下方会把作品列表挤到只剩两三行（378×496 表盘实测）。</p>
 *
 * <p>宿主只负责：标题栏 + ☰ 菜单（收藏歌手 / 相似歌手 / 刷新）+ 信息行 + ViewPager2 容器 +
 * 歌手详情请求（只请求一次，两页共用）。</p>
 */
public class ArtistDetailActivity extends BaseWatchActivity implements ArtistPageFragment.Host {

    /** 「其他需要展示的内容」行里的页名，下标与 ViewPager2 的页一一对应。 */
    private static final String[] PAGE_NAMES = {"热门", "全部"};

    private MusicPlayerManager playerManager;

    private long artistId;
    private String artistName;

    private TextView tvTitle;
    private TextView tvInfo;
    private ViewPager2 vpArtistTabs;

    /** 歌手详情（含热门 50 首）；失败时为 null，失败原因见 {@link #artistDetailError}。 */
    private MusicApiHelper.ArtistDetail artistDetail;
    private String artistDetailError;

    /** 是否已收藏该歌手（与原实现一致：本地状态，切换后即时生效）。 */
    private boolean subscribed = false;

    /** 每页最近一次回写的摘要（切页时直接取用，避免显示上一页的数字）。 */
    private final String[] pageSummaries = new String[PAGE_NAMES.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        artistId = getIntent().getLongExtra("artist_id", 0);
        artistName = getIntent().getStringExtra("artist_name");
        if (artistId <= 0) {
            com.qinghe.music163pro.util.UiKit.toast(this, "缺少歌手信息");
            finish();
            return;
        }
        playerManager = MusicPlayerManager.getInstance();

        setContentView(R.layout.activity_artist_detail);
        MusicLog.i("Vp2", "ArtistDetailActivity.onCreate（VP2 两页容器） id=" + artistId);

        // 标题栏 = 单行「标题 + ☰ 菜单」（需求方 2026-10-17 口径：不要两行错开、更多用矢量图标）。
        // 竖直占位 / 标题避开浮动返回按钮由统一机制处理（含 G5 标题居左）。
        View titleBar = findViewById(R.id.layout_artist_title_bar);
        WatchBackButton.applySingleRowTitleBarInset(this, titleBar);

        tvTitle = findViewById(R.id.tv_artist_title);
        tvInfo = findViewById(R.id.tv_artist_info);
        tvTitle.setText(artistName == null || artistName.isEmpty() ? "歌手" : artistName);

        View btnMenu = findViewById(R.id.btn_artist_menu);
        if (btnMenu != null) {
            btnMenu.setOnClickListener(v -> showArtistMenu());
        }

        vpArtistTabs = findViewById(R.id.vp_artist_tabs);
        vpArtistTabs.setAdapter(new TabsAdapter(this));
        // 两页都轻量且都用到同一份歌手详情：两页一起建好，避免滑过去才白屏。
        vpArtistTabs.setOffscreenPageLimit(1);
        vpArtistTabs.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                MusicLog.i("Vp2", "歌手页滑到 page=" + position + " (" + PAGE_NAMES[position] + ")");
                updateInfoRow(position);
            }
        });
        updateInfoRow(0);

        loadArtistDetail();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }

    // ==================== 宿主契约（ArtistPageFragment.Host） ====================

    @Override
    public String getCookie() {
        return playerManager.getCookie();
    }

    @Override
    public long getArtistId() {
        return artistId;
    }

    @Override
    public String getArtistName() {
        return artistName;
    }

    @Override
    public MusicApiHelper.ArtistDetail getArtistDetail() {
        return artistDetail;
    }

    @Override
    public String getArtistDetailError() {
        return artistDetailError;
    }

    @Override
    public void onArtistPageSummary(int page, CharSequence summary) {
        if (page < 0 || page >= pageSummaries.length) {
            return;
        }
        pageSummaries[page] = summary == null ? "" : summary.toString();
        // 只回写当前可见页；其余页的摘要先存着，滑过去时再显示。
        if (vpArtistTabs != null && vpArtistTabs.getCurrentItem() == page) {
            updateInfoRow(page);
        }
    }

    // ==================== 标题栏菜单 / 信息行 ====================

    /** 标题栏右上角 ☰：收藏歌手 / 相似歌手 / 刷新（「只留 返回/标题/菜单」口径下把页面动作收进菜单）。 */
    private void showArtistMenu() {
        final boolean toUnsubscribe = subscribed;
        String[] labels = {
                toUnsubscribe ? "取消收藏歌手" : "收藏歌手",
                "相似歌手",
                "刷新",
        };
        Runnable[] actions = {
                this::toggleSubscribe,
                this::openSimilarArtists,
                this::refreshCurrentPage,
        };
        String title = artistName == null || artistName.isEmpty() ? "歌手" : artistName;
        WatchMenuDialog.show(this, title, labels, which -> {
            if (which >= 0 && which < actions.length && actions[which] != null) {
                actions[which].run();
            }
        });
    }

    /** 独立信息行：显示「当前页 · 数量」；数据还没回来时先显示页名，不留空白行。 */
    private void updateInfoRow(int page) {
        if (tvInfo == null || page < 0 || page >= PAGE_NAMES.length) {
            return;
        }
        String text = pageSummaries[page];
        if (text == null || text.isEmpty()) {
            text = PAGE_NAMES[page];
        }
        tvInfo.setText(text);
    }

    /** 当前可见页的 Fragment（按页序号找，比解析 FragmentStateAdapter 的 tag 稳）。 */
    private ArtistPageFragment currentPage() {
        if (vpArtistTabs == null) {
            return null;
        }
        int page = vpArtistTabs.getCurrentItem();
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof ArtistPageFragment
                    && f.isAdded()
                    && ((ArtistPageFragment) f).pageIndex() == page) {
                return (ArtistPageFragment) f;
            }
        }
        return null;
    }

    private void refreshCurrentPage() {
        ArtistPageFragment page = currentPage();
        if (page != null) {
            page.refresh();
        }
    }

    // ==================== 歌手详情 / 收藏 / 相似歌手 ====================

    /** 歌手详情（含热门 50 首）只请求一次，两页共用；回来后再通知已建好的 Fragment。 */
    private void loadArtistDetail() {
        MusicApiHelper.getArtistDetail(artistId, playerManager.getCookie(),
                new MusicApiHelper.ArtistDetailCallback() {
                    @Override
                    public void onResult(MusicApiHelper.ArtistDetail detail) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        artistDetail = detail;
                        artistDetailError = null;
                        if (detail.name != null && !detail.name.isEmpty()) {
                            artistName = detail.name;
                            if (tvTitle != null) {
                                tvTitle.setText(detail.name);
                            }
                        }
                        MusicLog.i("Vp2", "歌手详情 OK id=" + artistId + " name=" + artistName
                                + " 热门=" + detail.hotSongs.size()
                                + " 歌曲数=" + detail.musicSize + " 专辑数=" + detail.albumSize);
                        notifyDetailReady();
                    }

                    @Override
                    public void onError(String message) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        artistDetailError = message;
                        MusicLog.w("Vp2", "歌手详情失败 id=" + artistId + " -> " + message);
                        notifyDetailReady();
                    }
                });
    }

    /** 详情成功或失败后，通知两页刷新 header / 用详情里的热门 50 首。 */
    private void notifyDetailReady() {
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof ArtistPageFragment) {
                ((ArtistPageFragment) f).onArtistDetailReady();
            }
        }
        if (vpArtistTabs != null) {
            updateInfoRow(vpArtistTabs.getCurrentItem());
        }
    }

    /** 打开「相似歌手」页（/simi/artist），顺着听下去时用来换口味。 */
    private void openSimilarArtists() {
        Intent intent = new Intent(this, SimilarActivity.class);
        intent.putExtra("mode", SimilarActivity.MODE_ARTIST);
        intent.putExtra("id", artistId);
        intent.putExtra("title", artistName == null ? "" : artistName);
        startActivity(intent);
    }

    /** 收藏 / 取消收藏歌手（与原实现同一接口与提示文案）。 */
    private void toggleSubscribe() {
        final boolean target = !subscribed;
        MusicApiHelper.subArtist(artistId, target, playerManager.getCookie(),
                new MusicApiHelper.ArtistSubCallback() {
                    @Override
                    public void onResult(boolean result) {
                        subscribed = result;
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

    /** ViewPager2 的页面适配器（第 0 页热门 50 首 / 第 1 页全部歌曲）。 */
    private static final class TabsAdapter extends FragmentStateAdapter {
        TabsAdapter(FragmentActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            if (position == ArtistPageFragment.PAGE_ALL) {
                return new ArtistAllSongsFragment();
            }
            return new ArtistHotSongsFragment();
        }

        @Override
        public int getItemCount() {
            return PAGE_NAMES.length;
        }
    }
}
