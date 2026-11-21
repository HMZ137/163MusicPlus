package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.fragment.FavoritesAlbumsFragment;
import com.qinghe.music163pro.fragment.FavoritesPageFragment;
import com.qinghe.music163pro.fragment.FavoritesPlaylistsFragment;
import com.qinghe.music163pro.fragment.FavoritesSongsFragment;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.MusicLog;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.WatchBackButton;
import com.qinghe.music163pro.util.WatchMenuDialog;

/**
 * 收藏列表（4-12~4-15：ViewPager2 + Fragment 迁移后的薄宿主）。
 *
 * <p><b>迁移口径</b>（需求方 2026-10-17）：多 Tab 页先迁到 ViewPager2 + Fragment，
 * 迁移后<b>不保留多 Tab 按钮</b>（参考 `去除指示_1.png` 圈出的「单曲 / 歌单 / 专辑」标签行）；
 * 标题栏只留「返回 / 标题 / 菜单」，页面之间靠左右滑切换；
 * 「其他需要展示的内容」放到标题栏外的独立行（参考 `多Tab实例_1.bmp`）——
 * 本页这一行显示「当前页 · 数量」，滑动切页时跟随。</p>
 *
 * <p>三个页面的数据、适配器、点击/长按、多选、空态都在各自的 Fragment 里
 * （{@link FavoritesSongsFragment} / {@link FavoritesPlaylistsFragment} / {@link FavoritesAlbumsFragment}），
 * 宿主只负责：标题栏 + 信息行 + ViewPager2 容器 + 登录态/收藏模式 + 返回键转发。</p>
 *
 * <p><b>返回键</b>：只注册一个拦截器，转发给当前页 Fragment 的
 * {@link FavoritesPageFragment#handleBackPressed()}（单曲页的多选模式要消费返回）。</p>
 */
public class FavoritesListActivity extends BaseWatchActivity implements FavoritesPageFragment.Host {

    /** 「其他需要展示的内容」行里的页名，下标与 ViewPager2 的页一一对应。 */
    // P0-8（2026-11-20）：专辑页签已砍掉，收藏专辑改为与歌单混排（见 FavoritesPlaylistsFragment）
    private static final String[] PAGE_NAMES = {"单曲", "歌单"};

    private ViewPager2 vpFavoritesTabs;
    private TextView tvFavInfo;
    /** 每页最近一次回写的摘要（切页时直接取用，避免显示上一页的数字）。 */
    private final String[] pageSummaries = new String[PAGE_NAMES.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favorites_list);
        MusicLog.i("Vp2", "FavoritesListActivity.onCreate（VP2 三页容器）");

        // 标题栏 = 单行「标题 + ☰ 菜单」（需求方 2026-10-17 口径：不要两行错开、更多用矢量图标）。
        // 竖直占位 / 标题避开浮动返回按钮由统一机制处理。
        View titleBar = findViewById(R.id.layout_fav_title_bar);
        WatchBackButton.applySingleRowTitleBarInset(this, titleBar);

        tvFavInfo = findViewById(R.id.tv_fav_info);
        View btnMenu = findViewById(R.id.btn_fav_menu);
        if (btnMenu != null) {
            btnMenu.setOnClickListener(v -> showFavoritesMenu());
        }

        vpFavoritesTabs = findViewById(R.id.vp_favorites_tabs);
        vpFavoritesTabs.setAdapter(new TabsAdapter(this));
        // 三页都轻量，但只预建相邻一页：避免一进来就把云端「收藏的专辑」请求打出去。
        vpFavoritesTabs.setOffscreenPageLimit(1);
        vpFavoritesTabs.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                MusicLog.i("Vp2", "收藏页滑到 page=" + position + " (" + PAGE_NAMES[position] + ")");
                updateInfoRow(position);
            }
        });
        updateInfoRow(0);

        // 单曲页多选时由 Fragment 消费返回键；其余情况走默认返回。
        addBackInterceptor(() -> {
            FavoritesPageFragment<?> page = currentPage();
            return page != null && page.handleBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }

    // ==================== 宿主契约（FavoritesPageFragment.Host） ====================

    @Override
    public String getCookie() {
        return MusicPlayerManager.getInstance().getCookie();
    }

    @Override
    public boolean isCloudMode() {
        return getSharedPreferences("music163_settings", MODE_PRIVATE)
                .getBoolean("fav_mode_cloud", false);
    }

    @Override
    public void onFavoritesPageSummary(int page, CharSequence summary) {
        if (page < 0 || page >= pageSummaries.length) {
            return;
        }
        pageSummaries[page] = summary == null ? "" : summary.toString();
        // 只回写当前可见页；其余页的摘要先存着，滑过去时再显示。
        if (vpFavoritesTabs != null && vpFavoritesTabs.getCurrentItem() == page) {
            updateInfoRow(page);
        }
    }

    // ==================== 标题栏菜单 / 信息行 ====================

    /** 标题栏右上角「更多」：按「只留 返回/标题/菜单」的口径收一个入口，当前只有「刷新收藏」。 */
    private void showFavoritesMenu() {
        WatchMenuDialog.show(this, getString(R.string.favorites),
                new String[]{"刷新收藏"}, which -> {
                    FavoritesPageFragment<?> page = currentPage();
                    if (page != null) {
                        page.refresh();
                    }
                });
    }

    /** 独立信息行：显示「当前页 · 数量」；数据还没回来时先显示页名，不留空白行。 */
    private void updateInfoRow(int page) {
        if (tvFavInfo == null || page < 0 || page >= PAGE_NAMES.length) {
            return;
        }
        String text = pageSummaries[page];
        if (text == null || text.isEmpty()) {
            text = PAGE_NAMES[page];
        }
        tvFavInfo.setText(text);
    }

    /** 当前可见页的 Fragment（ViewPager2 的页在 FragmentStateAdapter 里带 tag，但直接按页序号找更稳）。 */
    private FavoritesPageFragment<?> currentPage() {
        if (vpFavoritesTabs == null) {
            return null;
        }
        int page = vpFavoritesTabs.getCurrentItem();
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof FavoritesPageFragment
                    && f.isAdded()
                    && ((FavoritesPageFragment<?>) f).pageIndex() == page) {
                return (FavoritesPageFragment<?>) f;
            }
        }
        return null;
    }

    /** ViewPager2 的页面适配器（第 0 页单曲 / 第 1 页歌单；专辑已并入歌单页混排）。 */
    private static final class TabsAdapter extends FragmentStateAdapter {
        TabsAdapter(FragmentActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            if (position == FavoritesPageFragment.PAGE_PLAYLISTS) {
                return new FavoritesPlaylistsFragment();
            }
            return new FavoritesSongsFragment();
        }

        @Override
        public int getItemCount() {
            return PAGE_NAMES.length;
        }
    }
}
