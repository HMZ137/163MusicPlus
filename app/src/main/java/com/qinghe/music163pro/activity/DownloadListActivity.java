package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.WatchConfirmDialog;
import com.qinghe.music163pro.util.MultiSelectController;
import com.qinghe.music163pro.util.BatchFavoriteHelper;
import com.qinghe.music163pro.util.PlaybackMode;
import com.qinghe.music163pro.util.UiKit;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.fragment.DownloadTabFragment;
import com.qinghe.music163pro.fragment.LocalMusicFragment;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Download list activity - shows all downloaded songs from /sdcard/163Music/Download/
 * 长按进入多选，可批量收藏 / 批量删除。
 */
public class DownloadListActivity extends BaseWatchActivity
        implements DownloadTabFragment.Host, LocalMusicFragment.Host {

    /** 4-1：内容区容器 —— 承载「下载 / 本地」两页。 */
    private ViewPager2 vpDownloadTabs;
    /**
     * 「本地」入口是否可见。纯在线模式会把它收起；为 {@code false} 时容器只保留「下载」一页，
     * 并关掉左右滑动（滑到一页空白没有意义）。
     */
    private boolean tabsVisible = true;

    private final List<Song> downloadedSongs = new ArrayList<>();
    private ArrayAdapter<Song> adapter;
    private ListView lvDownloads;
    private TextView tvEmpty;
    private TextView tvTitle;
    private MultiSelectController multiSelect;

    /** 有下载/整理在进行中时，用来定时刷新列表（进度、新完成的条目）。 */
    private static final long REFRESH_INTERVAL_MS = 1000L;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    /**
     * 加载代数：读盘在后台跑，连续触发时只认最后一次的结果。
     *
     * <p>注意这里必须配 {@link #pendingLoads} 一起用。只看代数会出问题：onCreate 与
     * onResume 会各发一次请求（gen=1、gen=2），单线程队列里 gen=1 先跑完被丢弃，
     * 还得再排一个 gen=2 才轮到最后一次落地 —— 用户要多等一整轮读盘。</p>
     */
    private int loadGeneration = 0;
    /** 尚未落地的加载请求数；归零说明当前代数就是最新一次，结果必须应用。 */
    private int pendingLoads = 0;

    /**
     * 进程级下载列表缓存。
     *
     * <p>读盘一轮要几秒（每个目录都要 listFiles + 读 info.json），进页面时先用上次的
     * 结果渲染首屏，后台刷新完再静默替换 —— 否则用户会盯着「暂无下载」等十几秒，
     * 看起来就像功能坏了。</p>
     */
    private static final List<Song> CACHED_DOWNLOADS = new ArrayList<>();

    private static final java.util.concurrent.ExecutorService LOAD_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "downloads-load");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            updateTitleText();
            // 队列空了就自然停下来，不需要外部再取消
            if (DownloadManager.getActiveDownloadCount() > 0) {
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }
    };
    private boolean tidyInProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_list);
        // 4-5 埋点：本页起点。与下面「下载页视图就绪」以及 page1 预加载的时间戳一起，
        // 用来判断 ViewPager2 的预加载是否落在首屏关键路径上（低噪声，每次开页一行）。
        com.qinghe.music163pro.util.MusicLog.i("Vp2", "DownloadListActivity.onCreate 开始");

        // 标题栏要撑到浮动返回按钮的占位高度，否则按钮会压住列表第一行的封面
        // 与本地音乐页同理：占位加在**整个标题栏**上。若只加给上面的动作行，那一行会被撑到
        // 返回按钮的高度，再叠一行标题，头部会白吃掉几十像素的列表空间。
        View titleBar = findViewById(R.id.layout_downloads_title_bar);
        // 只有「动作行」避让左上角的返回按钮；标题栏里的「下载 / 本地」标签行
        // 要占满整行宽度（用户明确要求），所以走 applyTitleBarInset 的变体。
        //
        // ⚠️ D8 修复：**标题不再交给这个方法**。原先把标题也列为 insetChild，
        // 该方法会给它同时留左、右两侧占位（右侧是当年为了让**居中**标题保持居中的）；
        // 而 G5 之后标题一律**左对齐**，右侧占位纯属浪费 —— 实测把「本地音乐管理」6 个字
        // 的可用宽压到 222px（需要约 241px），于是被省略成「本地音乐…」。
        // 这里改成：标题只留**左侧**（返回按钮）占位，右侧归零，可用宽回到约 272px。
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, titleBar,
                findViewById(R.id.layout_downloads_action_row));
        // 标题栏里还叠了「下载 / 本地」标签行，占位高度要把这一行也算上 ——
        // 否则标题会被按剩余高度压缩，字的下半截被切掉（实机确认）。
        com.qinghe.music163pro.util.WatchBackButton.addTitleBarExtraHeight(
                this, titleBar, UiKit.dp(this, UiKit.DP_TAB_ROW_HEIGHT + 4));

        // Apply keep screen on setting

        // 4-1/4-2：下载列表与空态视图已迁到 ViewPager2 的「下载」页（DownloadTabFragment），
        // 这里不再直接 findViewById —— 该页视图就绪后由 onDownloadListViewsReady() 回调绑定。
        tvTitle = findViewById(R.id.tv_downloads_title);
        // D8：把标题从上面那个方法的 insetChildren 里摘出来了（不再要**右侧**占位）。
        // 左侧（返回按钮）避让由 G5 的统一机制自己在标题上处理，这里**不要再加 padding** ——
        // 实测叠加会变成双重缩进：盒子 260px、文字区只剩 166px，标题被截得更短（「本地音…」）。

        // 「整理」：把早期下载固定的 song.mp3 / song.flac 改成「曲名 - 曲师」
        View btnTidy = findViewById(R.id.btn_tidy_downloads);
        if (btnTidy != null) {
            btnTidy.setOnClickListener(v -> confirmTidyFileNames());
        }

        // 页内标签：下载 / 本地。4-1 起标签**不再跨 Activity**，而是切 ViewPager2 的页；
        // 「本地」页在 4-3 之前是桥接占位（内部按钮仍走原来的 openLocalMusicTab 路径）。
        vpDownloadTabs = findViewById(R.id.vp_download_tabs);
        TextView tabDownloads = findViewById(R.id.tab_downloads);
        TextView tabLocal = findViewById(R.id.tab_local_music);
        View tabRow = findViewById(R.id.ll_download_tabs);

        // 纯在线模式：「本地」入口收起（下载管理本身保留 —— 用户总得有地方管已下到卡里的歌）。
        // 只剩一个「下载」标签没有意义，整行一起收掉，容器也只留一页。
        tabsVisible = !PlaybackMode.isLocalEntryHidden(this);
        if (!tabsVisible && tabRow != null) {
            tabRow.setVisibility(View.GONE);
        }
        vpDownloadTabs.setAdapter(new TabsAdapter(this));
        // 预加载限 1：下一页提前建好够用；多建只会白读盘（4-5 会实测首屏耗时再定值）。
        vpDownloadTabs.setOffscreenPageLimit(1);
        vpDownloadTabs.setUserInputEnabled(tabsVisible);

        UiKit.setTabSelected(tabDownloads, true);
        UiKit.setTabSelected(tabLocal, false);
        // 双向同步（G4 改动要点 5）：点标签 → 切页；滑动换页 → 回写标签选中态。
        if (tabDownloads != null) {
            tabDownloads.setOnClickListener(v -> vpDownloadTabs.setCurrentItem(0, true));
        }
        if (tabLocal != null) {
            tabLocal.setOnClickListener(v -> vpDownloadTabs.setCurrentItem(1, true));
        }
        vpDownloadTabs.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                UiKit.setTabSelected(tabDownloads, position == 0);
                UiKit.setTabSelected(tabLocal, position == 1);
            }
        });

        // 适配器在这里就建好（ArrayAdapter 不需要 ListView 即可构造）；
        // 真正 setAdapter/监听/多选都等「下载」页视图就绪后由 onDownloadListViewsReady() 绑定。
        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, downloadedSongs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText(song.getDisplayName());
                    tvArtist.setText(song.getArtist());
                    if (ivCover != null) {
                        // coverUrl may be local file:// path or remote URL
                        NetworkImageLoader.load(ivCover, song.getCoverUrl());
                    }
                    applyDownloadState(view, song);
                }
                return view;
            }
        };

        // 这里**不**发请求：列表页的 onResume 会触发一次（见 onDownloadTabResumed），
        // 首次进入、切回本标签、从别处返回都覆盖得到。
    }

    /** 4-1：ViewPager2 的页面适配器（第 0 页 = 下载，第 1 页 = 本地）。 */
    private final class TabsAdapter extends FragmentStateAdapter {
        TabsAdapter(FragmentActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return position == 0 ? new DownloadTabFragment() : new LocalMusicFragment();
        }

        @Override
        public int getItemCount() {
            return tabsVisible ? 2 : 1;
        }
    }

    // ==================== 4-1 / 4-2：ViewPager2 页面的回调 ====================

    /**
     * 「下载」页视图就绪：把 ListView / 空态接过来，并完成适配器、点击、多选的绑定。
     *
     * <p>页面被重建（ViewPager2 回收再建）时本方法会**再次**触发，所以这里每次都重新绑定 ——
     * 用新视图覆盖旧引用，避免抱着已销毁的 ListView。</p>
     */
    @Override
    public void onDownloadListViewsReady(ListView listView, TextView emptyView) {
        com.qinghe.music163pro.util.MusicLog.i("Vp2", "下载页视图就绪（首屏可用）");
        lvDownloads = listView;
        tvEmpty = emptyView;
        if (lvDownloads == null) {
            return;
        }
        lvDownloads.setAdapter(adapter);
        NetworkImageLoader.attachListViewOptimization(lvDownloads, adapter, position ->
                position >= 0 && position < downloadedSongs.size()
                        ? downloadedSongs.get(position).getCoverUrl() : null);
        lvDownloads.setOnItemClickListener((parent, view, position, id) ->
                playDownloadedSong(position));

        // 长按进入多选模式，多选后可通过动作栏批量收藏或批量删除
        multiSelect = MultiSelectController.attach(this, lvDownloads,
                new MultiSelectController.Callback() {
                    @Override
                    public List<MultiSelectController.Action> getActions() {
                        List<MultiSelectController.Action> actions = new ArrayList<>();
                        actions.add(MultiSelectController.Action.favorite(DownloadListActivity.this));
                        actions.add(MultiSelectController.Action.delete(DownloadListActivity.this));
                        return actions;
                    }

                    @Override
                    public void onAction(MultiSelectController.Action action, List<Integer> positions) {
                        if (MultiSelectController.Action.ID_FAVORITE.equals(action.id)) {
                            favoriteSelected(positions);
                        } else if (MultiSelectController.Action.ID_DELETE.equals(action.id)) {
                            confirmDeleteSelected(positions);
                        }
                    }
                });
        if (multiSelect != null) {
            addBackInterceptor(multiSelect::handleBackPressed);
        }
        updateEmptyState();
    }

    /** 「下载」页成为当前页：这里才是原来的 onResume 干的事。 */
    @Override
    public void onDownloadTabResumed() {
        if (multiSelect != null) {
            multiSelect.exitSelectionMode();
        }
        applyCachedFirstPaint();
        loadDownloads();
        // 进页面时若还有下载在跑，就跟一下进度
        if (DownloadManager.getActiveDownloadCount() > 0) {
            refreshHandler.removeCallbacks(refreshTick);
            refreshHandler.post(refreshTick);
        }
    }

    /** 「下载」页离开前台：停掉进度刷新，别白耗电（原 onPause 的职责）。 */
    @Override
    public void onDownloadTabPaused() {
        refreshHandler.removeCallbacks(refreshTick);
    }

    /**
     * 「本地」页里点了一首歌（4-3 步骤③-2 起由 {@link LocalMusicFragment} 回调）。
     *
     * <p>行为与步骤② 的标签页一致：播放已由 Fragment 设好队列并开始，这里跳回主界面
     * （主界面是播放器页，用户点歌后自然要看到它）。完整页 LocalMusicActivity 的宿主
     * 实现相反 —— 那里是二级页面，直接 finish 自己。</p>
     */
    @Override
    public void onLocalSongPlayed() {
        startActivity(new Intent(this, MainActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 4-1 起：列表的加载与刷新由「下载」页（DownloadTabFragment）的 onResume 驱动
        // —— 见 onDownloadTabResumed()。这样「本页是否在前台」的判据由 ViewPager2 天然给出，
        // 不会再出现「已经切到本地页、后台还在读盘刷新」。
        updateTitleText();
    }

    /** 用上一次的结果先把列表画出来，避免用户盯着「暂无下载」等读盘。 */
    private void applyCachedFirstPaint() {
        if (CACHED_DOWNLOADS.isEmpty() || !downloadedSongs.isEmpty()) {
            return;
        }
        downloadedSongs.addAll(CACHED_DOWNLOADS);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmptyState();
        com.qinghe.music163pro.util.MusicLog.i("Downloads",
                "首屏用缓存: " + downloadedSongs.size() + " 首");
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 页面不可见时停止刷新，别白耗电
        refreshHandler.removeCallbacks(refreshTick);
    }

    /**
     * 行内显示这首歌的下载状态：「排队中…」/「下载中 45%」。
     * 不在队列里就隐藏，对已完成或已删除的条目没有影响。
     */
    private void applyDownloadState(View row, Song song) {
        TextView tvProgress = row.findViewById(R.id.tv_item_progress);
        if (tvProgress == null) {
            return;
        }
        DownloadManager.DownloadTask task = DownloadManager.getActiveDownload(song.getId());
        if (task == null) {
            tvProgress.setVisibility(View.GONE);
            return;
        }
        tvProgress.setVisibility(View.VISIBLE);
        if (task.state == DownloadManager.DownloadState.QUEUED) {
            tvProgress.setText("排队中…");
        } else if (task.percent >= 0) {
            tvProgress.setText("下载中 " + task.percent + "%");
        } else {
            tvProgress.setText("下载中…");
        }
    }

    /** 标题按优先级显示：整理进度 > 下载队列 > 常态标题。 */
    private void updateTitleText() {
        if (tvTitle == null || tidyInProgress) {
            return;
        }
        int pending = DownloadManager.getActiveDownloadCount();
        if (pending > 0) {
            tvTitle.setText("下载中 " + pending + " 项");
        } else {
            tvTitle.setText(R.string.downloads);
        }
    }

    /**
     * 删除选中的下载项。先确认，再逐首删除本地文件。
     */
    private void confirmDeleteSelected(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < downloadedSongs.size()) {
                targets.add(downloadedSongs.get(position));
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        showConfirmDialog("确认删除",
                "确定删除选中的 " + targets.size() + " 首歌曲？\n文件将被永久删除。", () -> {
                    int deleted = 0;
                    for (Song song : targets) {
                        if (DownloadManager.deleteDownload(song)) {
                            deleted++;
                        }
                    }
                    if (multiSelect != null) {
                        multiSelect.exitSelectionMode();
                    }
                    loadDownloads();
                    Toast.makeText(DownloadListActivity.this,
                            "已删除 " + deleted + " 首", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * 批量收藏选中的下载歌曲。
     *
     * <p>收藏口径与单曲收藏保持一致：设置里切到「云端收藏」时走网易云接口（需要登录），
     * 否则写入本地收藏。详见 {@link BatchFavoriteHelper}。</p>
     */
    private void favoriteSelected(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < downloadedSongs.size()) {
                targets.add(downloadedSongs.get(position));
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        Toast.makeText(this, getString(R.string.multi_select_favoriting, targets.size()),
                Toast.LENGTH_SHORT).show();
        BatchFavoriteHelper.favoriteAll(this, targets, new BatchFavoriteHelper.Callback() {
            @Override
            public void onFinished(int added, int skipped, int failed, int total) {
                if (multiSelect != null) {
                    multiSelect.exitSelectionMode();
                }
                Toast.makeText(DownloadListActivity.this,
                        BatchFavoriteHelper.buildResultMessage(DownloadListActivity.this, added, failed),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNeedLogin() {
                Toast.makeText(DownloadListActivity.this,
                        R.string.multi_select_favorite_need_login, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 「整理」入口：把早期下载固定的 {@code song.mp3} / {@code song.flac}
     * 改成「曲名 - 曲师」。会改动磁盘上的文件名，所以先二次确认。
     */
    private void confirmTidyFileNames() {
        showConfirmDialog("整理文件名",
                "把早期下载的 song.mp3 改名为「曲名 - 曲师」？\n"
                        + "只影响早期下载的固定文件名，不会动你自己放进目录里的音乐。",
                this::tidyFileNames);
    }

    private void tidyFileNames() {
        tidyInProgress = true;
        Toast.makeText(this, "正在整理…", Toast.LENGTH_SHORT).show();
        DownloadManager.migrateLegacyFileNames(new DownloadManager.MigrateCallback() {
            @Override
            public void onProgress(int done, int total, String currentName) {
                // 进度直接写在标题上：表盘小，弹进度条反而挡列表
                if (tvTitle != null) {
                    tvTitle.setText("整理中 " + done + "/" + total);
                }
            }

            @Override
            public void onFinished(int renamed, int skipped, int failed) {
                tidyInProgress = false;
                String message;
                if (failed < 0) {
                    message = "整理失败，请重试";
                } else if (renamed == 0 && skipped == 0) {
                    message = "没有需要整理的文件";
                } else {
                    message = "已改名 " + renamed + " 首";
                    if (skipped > 0) {
                        message += "，跳过 " + skipped + " 首";
                    }
                    if (failed > 0) {
                        message += "，失败 " + failed + " 首";
                    }
                }
                Toast.makeText(DownloadListActivity.this, message, Toast.LENGTH_LONG).show();
                updateTitleText();
                loadDownloads();
            }
        });
    }

    /**
     * 重新加载下载列表。
     *
     * <p><b>必须放后台线程</b>：每个下载目录都要打开读一遍 info.json（没有 info.json 的
     * 还要 listDirectory + 按名字解析）。全放主线程时实测卡死 11 秒触发 ANR
     * （logcat: Completed ANR in 11154ms），系统随后 Force finishing 本页面 ——
     * 用户看到的就是「进下载列表卡很久、然后闪退回上一页」。</p>
     *
     * <p><b>落地规则：</b>用 {@link #pendingLoads} 计数，**归零的那一轮才应用**。
     * 不能直接比 {@link #loadGeneration}：onCreate + onResume 会各发一次请求，
     * 单线程队列里先跑完的那一轮明明读到了数据也会被判为过期丢掉，
     * 用户要多等一整轮读盘（实测多等约 4 秒）。</p>
     *
     * <p>调用点只有 {@code onResume} 一处 —— 首次进页面、从别处返回、切标签回来都覆盖得到。</p>
     */
    private void loadDownloads() {
        final int generation = loadGeneration + 1;
        loadGeneration = generation;
        pendingLoads++;
        com.qinghe.music163pro.util.MusicLog.i("Downloads",
                "loadDownloads start gen=" + generation + " pending=" + pendingLoads);
        updateEmptyState();
        LOAD_EXECUTOR.execute(() -> {
            List<Song> loaded = new ArrayList<>();
            // Load from new subfolder format (with info.json)
            try {
                List<File> songDirs = DownloadManager.getDownloadedSongDirs();
                com.qinghe.music163pro.util.MusicLog.i("Downloads",
                        "gen=" + generation + " songDirs=" + songDirs.size());
                for (File dir : songDirs) {
                    Song song = DownloadManager.loadSongInfo(dir);
                    if (song != null) {
                        song.setForceLocalPlayback(true);
                        loaded.add(song);
                    }
                }

                // Load legacy flat .mp3 files (backward compatibility)
                List<File> legacyFiles = DownloadManager.getDownloadedFiles();
                com.qinghe.music163pro.util.MusicLog.i("Downloads",
                        "gen=" + generation + " legacyFiles=" + legacyFiles.size());
                for (File file : legacyFiles) {
                    loaded.add(fileToSong(file));
                }
            } catch (Throwable t) {
                // 单个下载目录读失败不该让整页空掉：已经读到的部分照常显示
                com.qinghe.music163pro.util.MusicLog.i("Downloads",
                        "gen=" + generation + " 读盘异常: " + t);
            }
            final List<Song> result = loaded;
            com.qinghe.music163pro.util.MusicLog.i("Downloads",
                    "gen=" + generation + " loaded=" + result.size() + " posting");
            refreshHandler.post(() -> {
                // 落地条件只看「还有没有更新的请求在排队」：不再拿 generation 直接比，
                // 否则最先跑完的那一轮明明读到了数据也白读，用户要多等一整轮。
                pendingLoads = Math.max(0, pendingLoads - 1);
                boolean isLatest = pendingLoads == 0;
                com.qinghe.music163pro.util.MusicLog.i("Downloads",
                        "gen=" + generation + " apply pending=" + pendingLoads
                                + " latest=" + isLatest
                                + " finishing=" + isFinishing() + " destroyed=" + isDestroyed()
                                + " result=" + result.size());
                if (!isLatest || isFinishing() || isDestroyed()) {
                    return;   // 还有更新的一轮在排队，或页面已关
                }
                CACHED_DOWNLOADS.clear();
                CACHED_DOWNLOADS.addAll(result);
                downloadedSongs.clear();
                downloadedSongs.addAll(result);
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                updateTitleText();
                updateEmptyState();
                com.qinghe.music163pro.util.MusicLog.i("Downloads",
                        "gen=" + generation + " applied size=" + downloadedSongs.size()
                                + " empty=" + (tvEmpty != null ? tvEmpty.getVisibility() : -1));
                // Fetch covers from network for songs without covers (legacy downloads)
                fetchMissingCovers();
            });
        });
    }

    /**
     * For songs in the download list that don't have a cover URL,
     * fetch their details from the network to get cover URLs.
     */
    private void fetchMissingCovers() {
        List<Long> missingIds = new ArrayList<>();
        for (Song song : downloadedSongs) {
            if (song.getId() > 0 && (song.getCoverUrl() == null || song.getCoverUrl().isEmpty())) {
                missingIds.add(song.getId());
            }
        }
        if (missingIds.isEmpty()) return;

        MusicPlayerManager pm = MusicPlayerManager.getInstance();
        String cookie = pm.getCookie();
        MusicApiHelper.fetchSongsDetails(missingIds, cookie,
                new MusicApiHelper.BatchSongDetailsCallback() {
                    @Override
                    public void onResult(Map<Long, Song> songMap) {
                        boolean changed = false;
                        for (Song song : downloadedSongs) {
                            if (song.getId() > 0 && (song.getCoverUrl() == null || song.getCoverUrl().isEmpty())) {
                                Song detail = songMap.get(song.getId());
                                if (detail != null && detail.getCoverUrl() != null && !detail.getCoverUrl().isEmpty()) {
                                    song.setCoverUrl(detail.getCoverUrl());
                                    changed = true;
                                }
                            }
                        }
                        if (changed && adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        // Network not available or error - just show without covers
                    }
                });
    }

    private void updateEmptyState() {
        if (tvEmpty == null || lvDownloads == null) {
            // 4-1 起列表住在 ViewPager2 的页面里：视图可能尚未建好，或该页已被回收。
            // 此时没有可更新的空态，直接返回（否则 NPE）。
            return;
        }
        if (!downloadedSongs.isEmpty()) {
            tvEmpty.setVisibility(View.GONE);
            lvDownloads.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.VISIBLE);
        lvDownloads.setVisibility(View.GONE);
        // 还在读盘时不要说「暂无下载」——那是在断言一个还没得出的结论，
        // 用户会以为功能坏了（读盘要 1~2 秒）。读盘结束仍为空才显示真正的空态。
        if (pendingLoads > 0) {
            tvEmpty.setText(R.string.downloads_loading);
        } else {
            tvEmpty.setText(R.string.downloads_empty);
        }
    }

    /**
     * Build a full playlist from all downloaded songs and play the selected one.
     */
    private void playDownloadedSong(int position) {
        try {
            List<Song> playlist = new ArrayList<>(downloadedSongs);

            MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
            playerManager.setPlaylist(playlist, position);
            playerManager.playCurrent();

            // Navigate back to MainActivity
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Convert a loose audio file in the download root to a Song
     * (id=0, name/artist parsed from the 曲名 - 曲师 file name).
     */
    private Song fileToSong(File file) {
        // 先去掉扩展名（.mp3 / .flac / …），再按「曲名 - 曲师」解析
        String name = DownloadManager.stripAudioExtension(file.getName());
        String songName = name;
        String artist = "";
        int dashIdx = name.indexOf(" - ");
        if (dashIdx > 0) {
            songName = name.substring(0, dashIdx);
            artist = name.substring(dashIdx + 3);
        }
        Song song = new Song(0, songName, artist, "");
        song.setUrl(file.getAbsolutePath());
        song.setForceLocalPlayback(true);
        return song;
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
        refreshHandler.removeCallbacks(refreshTick);
        NetworkImageLoader.cancelAll();
    }

}
