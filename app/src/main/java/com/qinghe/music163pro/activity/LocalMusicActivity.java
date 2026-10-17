package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.fragment.LocalMusicFragment;
import com.qinghe.music163pro.util.LocalLibraryCache;
import com.qinghe.music163pro.util.LocalMetadataIndex;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.UiKit;
import com.qinghe.music163pro.util.WatchBackButton;

/**
 * 本地音乐（完整页）—— 4-3 步骤③-2 起只是**薄宿主**。
 *
 * <p><b>改造前</b>：这个 Activity 有 1000 多行，把「扫描 / 两阶段加载 / 排序 / 搜索 / 多选 /
 * 隐藏名单 / A-Z 选择器 / 局域网传歌」全塞在一起；下载页的「本地」标签只能另做一个桥接占位页。</p>
 *
 * <p><b>现在</b>：数据侧在 {@code util/LocalMusicLoader}（步骤①），视图与交互在
 * {@code fragment/LocalMusicFragment}（步骤② 建、步骤③-2 补全）。本类只做四件事：</p>
 * <ol>
 *   <li>提供**单行**标题栏（左标题「本地音乐」+ 右 ☰ 菜单 + 下载/本地标签行）—— 返回按钮与
 *       标题栏避让的统一机制都挂在 Activity 上，留在宿主里最稳；</li>
 *   <li>把内容区交给 {@code LocalMusicFragment(advanced=true)}；</li>
 *   <li>把标题栏的 ☰ 转调给 Fragment（搜索 / 排序 / A-Z / 随机 / 局域网传歌 / 刷新缓存
 *       全部在 {@code LocalMusicFragment.showMoreDialog()} 里）；</li>
 *   <li>多选模式的返回键拦截转调给 Fragment。</li>
 * </ol>
 *
 * <p>两条入口（More→本机音乐、下载页→本地标签）至此共用同一个 Fragment，差异只有
 * {@link LocalMusicFragment#setAdvanced(boolean)}：本页 true，标签页 false。</p>
 */
public class LocalMusicActivity extends BaseWatchActivity implements LocalMusicFragment.Host {

    /** Fragment 在 FragmentManager 里的 tag（重建时按它找回，避免重复 add）。 */
    private static final String TAG_LOCAL_MUSIC = "local_music";

    /** 内容区里的 Fragment 引用；视图重建期间可能为 null，按需从 FragmentManager 找回。 */
    private LocalMusicFragment fragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 本地音乐库缓存：扫描结果落盘，供下次启动解析本地歌路径
        LocalLibraryCache.init(this);
        // 元信息索引：曲名/曲师/封面读过一次就记下来，下次只给「变过」的文件重读标签。
        // 这是「本地音乐」页秒开的关键 —— 详见 LocalMetadataIndex 的类注释。
        LocalMetadataIndex.init(this);
        setContentView(R.layout.activity_local_music);

        // 标题栏 = 单行「标题 + ☰ 菜单」（需求方 2026-10-17 口径：不要两行错开、更多用矢量图标）。
        // 单行版只撑到返回按钮底边；标题栏里还叠着「下载 / 本地」标签行，
        // 占位高度要把这一行再补上，否则标题会被按剩余高度压缩、字的下半截被切掉（实机确认）。
        View titleBar = findViewById(R.id.layout_local_music_title_bar);
        WatchBackButton.applySingleRowTitleBarInset(this, titleBar);
        WatchBackButton.addTitleBarExtraHeight(this, titleBar,
                UiKit.dp(this, UiKit.DP_TAB_ROW_HEIGHT + 4));

        // ☰ 菜单：搜索 / 排序 / A-Z 字母索引 / 随机播放 / 局域网传歌 / 刷新本地缓存
        // 全部在 LocalMusicFragment.showMoreDialog() 里，这里只转调（标题栏不再放三个文字按钮）。
        View btnMore = findViewById(R.id.btn_local_more);
        if (btnMore != null) {
            btnMore.setOnClickListener(v -> {
                LocalMusicFragment f = fragment();
                if (f != null) {
                    f.showMoreDialog();
                }
            });
        }

        // 页内标签：下载 / 本地（当前页）。样式与「收藏」页一致；「下载」切到下载管理页。
        TextView tabDownloads = findViewById(R.id.tab_downloads);
        TextView tabLocal = findViewById(R.id.tab_local_music);
        UiKit.setTabSelected(tabDownloads, false);
        UiKit.setTabSelected(tabLocal, true);
        if (tabDownloads != null) {
            tabDownloads.setOnClickListener(v -> openDownloadsTab());
        }
        if (tabLocal != null) {
            tabLocal.setOnClickListener(v -> { /* 已经在本标签上，无需动作 */ });
        }

        // 内容区：advanced=true 的 LocalMusicFragment（列表 / 状态行 / 空态 + 全部高级能力）
        if (savedInstanceState == null) {
            LocalMusicFragment f = new LocalMusicFragment();
            f.setAdvanced(true);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.local_music_fragment_container, f, TAG_LOCAL_MUSIC)
                    .commitNow();
            fragment = f;
        } else {
            Fragment restored = getSupportFragmentManager().findFragmentByTag(TAG_LOCAL_MUSIC);
            if (restored instanceof LocalMusicFragment) {
                fragment = (LocalMusicFragment) restored;
            }
        }

        // 多选模式消费返回键（与改造前一致：先退出多选，再走默认返回）
        addBackInterceptor(() -> {
            LocalMusicFragment f = fragment();
            return f != null && f.handleBackPressed();
        });
    }

    /** 取内容区 Fragment：优先用字段，页面重建后按 tag 找回。 */
    private LocalMusicFragment fragment() {
        if (fragment == null || !fragment.isAdded()) {
            Fragment found = getSupportFragmentManager().findFragmentByTag(TAG_LOCAL_MUSIC);
            if (found instanceof LocalMusicFragment) {
                fragment = (LocalMusicFragment) found;
            }
        }
        return fragment;
    }

    @Override
    public void onLocalSongPlayed() {
        // 完整页是「二级页面」：播完收起自己，返回进入本页之前的位置
        finish();
    }

    /**
     * 切到「下载」标签（下载管理页）。
     *
     * <p>与 {@code DownloadListActivity.openLocalMusicTab()} 对称：先开目标页再 finish 自己，
     * 不往返回栈里叠页面、去掉切换动画，让它看着像页内切换而不是页面跳转。</p>
     */
    private void openDownloadsTab() {
        startActivity(new Intent(this, DownloadListActivity.class));
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }
}
