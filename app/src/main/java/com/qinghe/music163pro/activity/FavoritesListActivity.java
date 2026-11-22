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
        // 2026-11-22：首次进入收藏页提示「哪些页面支持长按」（5 秒内不可关闭）
        showLongPressHintOnce();

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

    /** 首次进入收藏页弹一次「长按可多选」提示；5 秒倒计时内不可关闭。 */
    private void showLongPressHintOnce() {
        try {
            android.content.SharedPreferences p = getSharedPreferences(
                    "favorites_ui_prefs", MODE_PRIVATE);
            if (p.getBoolean("long_press_hint_shown", false)) {
                return;
            }
            p.edit().putBoolean("long_press_hint_shown", true).apply();
            getWindow().getDecorView().postDelayed(this::showLongPressHint, 600L);
        } catch (Throwable ignored) {
        }
    }

    private void showLongPressHint() {
        if (isFinishing()) {
            return;
        }
        final android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_Translucent_NoTitleBar);
        final android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setBackground(com.qinghe.music163pro.util.UiKit.dialogCard(this));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("这些页面可以长按");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(title);

        TextView body = new TextView(this);
        body.setText("· 收藏 · 单曲：长按任意歌曲进入多选\n"
                + "　 （删除 / 下一首播放 / 加入本地歌单 / 收藏到歌单）\n"
                + "· 收藏 · 歌单：长按歌单可删除或取消收藏\n"
                + "· 播放列表 / 歌单详情：长按歌曲可批量操作");
        body.setTextColor(0xFFAAAAAA);
        body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        android.widget.LinearLayout.LayoutParams bodyLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = pad / 2;
        card.addView(body, bodyLp);

        final TextView countdown = new TextView(this);
        countdown.setText("5 秒后可关闭");
        countdown.setTextColor(0xFF888888);
        countdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        countdown.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams cLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = pad / 2;
        card.addView(countdown, cLp);

        final TextView ok = new TextView(this);
        ok.setText("知道了");
        ok.setTextColor(com.qinghe.music163pro.util.ThemeManager.accentColor(this));
        ok.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        ok.setGravity(android.view.Gravity.CENTER);
        ok.setPadding(0, pad / 2, 0, pad / 2);
        ok.setVisibility(android.view.View.GONE);
        android.widget.LinearLayout.LayoutParams okLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        okLp.topMargin = pad / 2;
        card.addView(ok, okLp);
        ok.setOnClickListener(v -> dialog.dismiss());

        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(this);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.min((int) (screenW * 0.92f), screenW - pad);
        android.widget.FrameLayout.LayoutParams cardLp =
                new android.widget.FrameLayout.LayoutParams(
                        cardW, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = android.view.Gravity.CENTER;
        wrapper.addView(card, cardLp);
        dialog.setContentView(wrapper);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();

        final android.os.Handler handler = new android.os.Handler(
                android.os.Looper.getMainLooper());
        final int[] remain = {5};
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                remain[0]--;
                if (remain[0] > 0) {
                    countdown.setText(remain[0] + " 秒后可关闭");
                    handler.postDelayed(this, 1000L);
                } else {
                    countdown.setVisibility(android.view.View.GONE);
                    ok.setVisibility(android.view.View.VISIBLE);
                    dialog.setCancelable(true);
                    dialog.setCanceledOnTouchOutside(true);
                }
            }
        };
        handler.postDelayed(tick, 1000L);
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
