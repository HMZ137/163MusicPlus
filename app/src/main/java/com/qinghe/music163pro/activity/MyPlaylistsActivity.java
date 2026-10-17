package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.fragment.LocalPlaylistsFragment;
import com.qinghe.music163pro.fragment.MyPlaylistsPageFragment;
import com.qinghe.music163pro.fragment.OnlinePlaylistsFragment;
import com.qinghe.music163pro.util.MusicLog;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.PlaybackMode;
import com.qinghe.music163pro.util.WatchBackButton;
import com.qinghe.music163pro.util.WatchMenuDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的歌单（4-16：ViewPager2 + Fragment 迁移后的薄宿主）。
 *
 * <p><b>改造前</b>：1173 行自绘 Activity，在线 / 本地两套列表逻辑共用一个 ListView，
 * 靠页内「在线歌单 / 本地歌单」两个文字 Tab 切换；标题栏里还有「A-Z」「+」两个文字按钮。</p>
 *
 * <p><b>现在</b>：两页各一个 Fragment（{@link OnlinePlaylistsFragment} /
 * {@link LocalPlaylistsFragment}，公共部分在 {@link MyPlaylistsPageFragment}），
 * 宿主只负责：单行标题栏（标题 + ☰）、独立信息行、ViewPager2 容器、模式裁剪与菜单转发。</p>
 *
 * <p><b>模式裁剪（与改造前一致）</b>：纯本地播放模式只挂「本地歌单」一页；纯在线模式只挂
 * 「在线歌单」一页；两种模式都关时两页都在、左右滑切换。默认落在哪一页沿用改造前的判断
 * （不走云端收藏时默认本地页，否则在线页）。</p>
 */
public class MyPlaylistsActivity extends BaseWatchActivity implements MyPlaylistsPageFragment.Host {

    /** 信息行里的页名，下标是 {@link MyPlaylistsPageFragment} 的页序号。 */
    private static final String[] PAGE_NAMES = {"在线歌单", "本地歌单"};

    private ViewPager2 vpMyPlaylistsTabs;
    private TextView tvMyPlaylistsInfo;
    /** 当前实际挂载的页（顺序即 ViewPager2 的顺序）；纯本地 / 纯在线模式下只有一个元素。 */
    private int[] pages = new int[0];
    /** 每页最近一次回写的摘要（切页时直接取用）。 */
    private final String[] pageSummaries = new String[PAGE_NAMES.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.content.SharedPreferences prefs =
                getSharedPreferences("music163_settings", MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        setContentView(R.layout.activity_my_playlists);
        MusicLog.i("Vp2", "MyPlaylistsActivity.onCreate（在线/本地两页容器）");

        // 标题栏 = 单行「标题 + ☰ 菜单」（新口径：标题与按钮同一行、更多用矢量图标）
        View titleBar = findViewById(R.id.layout_my_playlists_title_bar);
        WatchBackButton.applySingleRowTitleBarInset(this, titleBar);

        tvMyPlaylistsInfo = findViewById(R.id.tv_my_playlists_info);
        View btnMenu = findViewById(R.id.btn_my_playlists_menu);
        if (btnMenu != null) {
            btnMenu.setOnClickListener(v -> showMyPlaylistsMenu());
        }

        // 模式裁剪：纯本地只留本地页；纯在线只留在线页；都关则两页都在
        boolean localOnlyMode = PlaybackMode.isLocalOnly(this);
        boolean onlineOnlyMode = PlaybackMode.isOnlineOnly(this);
        boolean showOnline = !localOnlyMode;
        boolean showLocal = !onlineOnlyMode;
        if (!showOnline && !showLocal) {
            // 两个开关互斥，正常不会同时为真；兜底两页都挂，避免空白页
            showOnline = true;
            showLocal = true;
        }
        List<Integer> pageList = new ArrayList<>();
        if (showOnline) {
            pageList.add(MyPlaylistsPageFragment.PAGE_ONLINE);
        }
        if (showLocal) {
            pageList.add(MyPlaylistsPageFragment.PAGE_LOCAL);
        }
        pages = new int[pageList.size()];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = pageList.get(i);
        }

        // 默认页沿用改造前：纯在线 → 在线；否则不走云端收藏 → 本地，走云端收藏 → 在线
        int initialPage = onlineOnlyMode ? MyPlaylistsPageFragment.PAGE_ONLINE
                : ((!localOnlyMode && PlaybackMode.isCloudFavoriteEnabled(this))
                        ? MyPlaylistsPageFragment.PAGE_ONLINE : MyPlaylistsPageFragment.PAGE_LOCAL);
        int initialPosition = 0;
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] == initialPage) {
                initialPosition = i;
                break;
            }
        }

        vpMyPlaylistsTabs = findViewById(R.id.vp_my_playlists_tabs);
        vpMyPlaylistsTabs.setAdapter(new TabsAdapter(this, pages));
        // 只挂一页时关掉左右滑；两页时也只预建相邻一页（在线页要发网络请求）
        vpMyPlaylistsTabs.setUserInputEnabled(pages.length > 1);
        vpMyPlaylistsTabs.setOffscreenPageLimit(1);
        vpMyPlaylistsTabs.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (position >= 0 && position < pages.length) {
                    MusicLog.i("Vp2", "我的歌单滑到 page=" + pages[position]
                            + " (" + PAGE_NAMES[pages[position]] + ")");
                    updateInfoRow(pages[position]);
                }
            }
        });
        vpMyPlaylistsTabs.setCurrentItem(initialPosition, false);
        updateInfoRow(pages[initialPosition]);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }

    // ==================== 标题栏菜单 / 信息行 ====================

    /**
     * 单行标题栏右侧 ☰：新建歌单 / A-Z 字母索引，本地歌单页多一项「导入歌单」。
     *
     * <p>需求方 2026-10-17：「导入歌单的按钮也放到右上角的菜单按钮中」——原先它占着状态行右侧一块，
     * 现在收进 ☰，状态行只留「N 个歌单」。</p>
     */
    private void showMyPlaylistsMenu() {
        final MyPlaylistsPageFragment page = currentPage();
        if (page == null) {
            return;
        }
        final boolean localPage = page.pageIndex() == MyPlaylistsPageFragment.PAGE_LOCAL;
        final String[] items = localPage
                ? new String[]{"新建歌单", "导入歌单", "A-Z 字母索引"}
                : new String[]{"新建歌单", "A-Z 字母索引"};
        WatchMenuDialog.show(this, "我的歌单", items, which -> {
            if (which == 0) {
                page.showCreatePlaylistDialog();
            } else if (localPage && which == 1) {
                page.importPlaylists();
            } else {
                page.openLetterPicker();
            }
        });
    }

    private void updateInfoRow(int page) {
        if (tvMyPlaylistsInfo == null || page < 0 || page >= PAGE_NAMES.length) {
            return;
        }
        String text = pageSummaries[page];
        if (text == null || text.isEmpty()) {
            text = PAGE_NAMES[page];
        }
        tvMyPlaylistsInfo.setText(text);
    }

    @Override
    public void onMyPlaylistsPageSummary(int page, CharSequence summary) {
        if (page < 0 || page >= pageSummaries.length) {
            return;
        }
        pageSummaries[page] = summary == null ? "" : summary.toString();
        int position = vpMyPlaylistsTabs == null ? -1 : vpMyPlaylistsTabs.getCurrentItem();
        if (position >= 0 && position < pages.length && pages[position] == page) {
            updateInfoRow(page);
        }
    }

    /** 当前可见页的 Fragment（按页序号匹配，避免依赖 ViewPager2 的 tag 约定）。 */
    private MyPlaylistsPageFragment currentPage() {
        if (vpMyPlaylistsTabs == null) {
            return null;
        }
        int position = vpMyPlaylistsTabs.getCurrentItem();
        if (position < 0 || position >= pages.length) {
            return null;
        }
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof MyPlaylistsPageFragment
                    && f.isAdded()
                    && ((MyPlaylistsPageFragment) f).pageIndex() == pages[position]) {
                return (MyPlaylistsPageFragment) f;
            }
        }
        return null;
    }

    /** ViewPager2 页面适配器（每一页对应「在线歌单」或「本地歌单」）。 */
    private static final class TabsAdapter extends FragmentStateAdapter {
        private final int[] pages;

        TabsAdapter(FragmentActivity activity, int[] pages) {
            super(activity);
            this.pages = pages;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            if (pages[position] == MyPlaylistsPageFragment.PAGE_ONLINE) {
                return new OnlinePlaylistsFragment();
            }
            return new LocalPlaylistsFragment();
        }

        @Override
        public int getItemCount() {
            return pages.length;
        }
    }
}
