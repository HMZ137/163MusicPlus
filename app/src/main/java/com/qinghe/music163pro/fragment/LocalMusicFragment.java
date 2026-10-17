package com.qinghe.music163pro.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.MainActivity;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.LocalMusicLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * 4-3 步骤②：本地音乐 Fragment（列表 + 扫描接线 + 点击播放）。
 *
 * <p><b>定位</b>：给 G4 的 ViewPager2 容器用 —— 下载页的「本地」标签直接挂这个 Fragment，
 * 替代原来的桥接占位页（那个页只有一个按钮跳去 Activity）。</p>
 *
 * <p><b>取舍</b>：标签页只承载**轻量浏览**（列表 / 点击播放 / 空态 / 状态行）；
 * 搜索、A-Z 索引条、多选、局域网传歌仍留在完整的 {@code LocalMusicActivity}
 * —— 那些功能都吃 position 索引，硬塞进标签页收益小、回归面大。完整页仍是「本机音乐」入口。</p>
 *
 * <p><b>数据侧</b>完全复用步骤①抽出的 {@link LocalMusicLoader}（两阶段加载、索引回写、
 * 隐藏名单跳过、下载子树排除都在里面），本类只做视图与回调。</p>
 *
 * <p><b>4-3 步骤③-1：高级能力开关。</b>两条入口共用这一份实现后，能力差异只由
 * {@link #setAdvanced(boolean)} 决定（默认 {@code false}）：</p>
 * <ul>
 *   <li>{@code false}（下载页「本地」标签）：只启用列表 / 点击播放 / 状态行 / 空态；</li>
 *   <li>{@code true}（More→本机音乐完整页）：在上面基础上启用搜索、排序、A-Z 字母索引、
 *       长按多选（收藏 / 加入歌单 / 移出列表）、局域网传歌入口；状态行也变成可点按的
 *       筛选行（清除搜索 / 恢复已移出的曲目）。</li>
 * </ul>
 * <p>本开关在步骤③-1 先落地（此时完整页仍是旧 {@code LocalMusicActivity}，行为零变化）；
 * 步骤③-2 再把完整页改成「薄宿主 + {@code advanced=true} 的本 Fragment」。</p>
 */
public class LocalMusicFragment extends Fragment {

    private final List<Song> songs = new ArrayList<>();
    private ListView listView;
    private TextView statusView;
    private TextView emptyView;
    private ArrayAdapter<Song> adapter;
    private int generation = 0;
    private boolean viewDestroyed = false;
    /**
     * 高级能力开关（4-3 步骤③-1，默认 {@code false}）。
     * 见类注释：{@code true} 才启用 搜索 / 排序 / A-Z / 长按多选 / 局域网传歌。
     */
    private boolean advanced = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_local_music_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listView = view.findViewById(R.id.lv_local_music_tab);
        statusView = view.findViewById(R.id.tv_local_music_tab_status);
        emptyView = view.findViewById(R.id.tv_local_music_tab_empty);

        adapter = new ArrayAdapter<>(requireContext(), R.layout.item_song, R.id.tv_item_name, songs);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, v, position, id) -> playFrom(position));

        applyMode();
        startScan();
    }

    @Override
    public void onDestroyView() {
        viewDestroyed = true;
        generation++;   // 让仍在跑的加载器立刻作废（避免它往已销毁的视图上回调）
        super.onDestroyView();
    }

    // ==================== 4-3 步骤③-1：高级能力开关 ====================

    /**
     * 设置高级能力开关（默认 {@code false}，含义见类注释）。
     *
     * <p>可以在 {@code attach} 之前调用（宿主在事务里 add 之前设置），也可以在视图就绪后调用
     * （会立刻重新应用一遍）。步骤③-1 先只落地开关与模式应用骨架：完整页仍是旧 Activity，
     * 因此本方法目前对现有体验零影响。</p>
     */
    public void setAdvanced(boolean advanced) {
        this.advanced = advanced;
        applyMode();
    }

    /** 当前是否为高级模式（完整页为 {@code true}，下载页「本地」标签为 {@code false}）。 */
    public boolean isAdvanced() {
        return advanced;
    }

    /**
     * 把当前模式应用到视图上。视图尚未创建时直接返回 —— {@link #onViewCreated} 会再调一次。
     *
     * <p>步骤③-1 只有一处可见差异：高级模式下状态行是**可点按**的筛选行（清除搜索 / 恢复已移出），
     * 轻量模式下它保持纯文本。搜索 / 排序 / A-Z / 多选 / 局域网传歌的接线在步骤③-2 搬入。</p>
     */
    private void applyMode() {
        if (statusView == null) {
            return;
        }
        statusView.setClickable(advanced);
        statusView.setFocusable(advanced);
    }

    private void startScan() {
        final int gen = ++generation;
        if (statusView != null) {
            statusView.setText("扫描中…");
            statusView.setVisibility(View.VISIBLE);
        }
        if (emptyView != null) {
            emptyView.setVisibility(View.GONE);
        }
        LocalMusicLoader loader = new LocalMusicLoader(requireContext(), gen,
                new LocalMusicLoader.Callback() {
                    @Override
                    public void onFirstPaint(List<Song> cachedSongs) {
                        if (isStale(gen)) {
                            return;
                        }
                        songs.clear();
                        songs.addAll(cachedSongs);
                        refreshViews();
                    }

                    @Override
                    public void onWalkDone(List<Song> found, int misses) {
                        if (isStale(gen)) {
                            return;
                        }
                        songs.clear();
                        songs.addAll(found);
                        refreshViews();
                    }

                    @Override
                    public void onMetadataProgress(int processed, int total, boolean readingFromDisk) {
                        if (isStale(gen)) {
                            return;
                        }
                        adapter.notifyDataSetChanged();
                        if (statusView != null && total > 0 && readingFromDisk) {
                            statusView.setText("读取歌曲信息中… " + processed + "/" + total);
                        }
                    }

                    @Override
                    public void onFinished(boolean changed) {
                        if (isStale(gen)) {
                            return;
                        }
                        adapter.notifyDataSetChanged();
                        if (statusView != null) {
                            statusView.setText("本地音乐 " + songs.size() + " 首");
                        }
                    }

                    @Override
                    public boolean isCancelled() {
                        return isStale(gen);
                    }

                    @Override
                    public void onSkippedDirs(final List<String> skipped) {
                        if (isStale(gen)) {
                            return;
                        }
                        final android.app.Activity activity = getActivity();
                        if (activity == null) {
                            return;
                        }
                        activity.runOnUiThread(() -> Toast.makeText(activity,
                                "已跳过 " + skipped.size() + " 个不可用的自定义扫描目录",
                                Toast.LENGTH_LONG).show());
                    }
                });
        new Thread(loader::runBlocking, "local-tab-scan").start();
    }

    private boolean isStale(int gen) {
        return viewDestroyed || !isAdded() || gen != generation;
    }

    private void refreshViews() {
        if (adapter == null) {
            return;
        }
        adapter.notifyDataSetChanged();
        boolean empty = songs.isEmpty();
        if (emptyView != null) {
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (listView != null) {
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (statusView != null && !empty) {
            statusView.setText("本地音乐 " + songs.size() + " 首");
        }
    }

    /** 点击播放：与完整页同一套（整张本地列表作为播放队列，再从该位置开始）。 */
    private void playFrom(int position) {
        if (position < 0 || position >= songs.size()) {
            return;
        }
        List<Song> playlist = new ArrayList<>(songs);
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        playerManager.setPlaylist(playlist, position);
        playerManager.playCurrent();
        startActivity(new Intent(requireContext(), MainActivity.class));
    }
}
