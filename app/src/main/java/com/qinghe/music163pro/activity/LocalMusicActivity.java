package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.BatchFavoriteHelper;
import com.qinghe.music163pro.util.LocalTracks;
import com.qinghe.music163pro.util.MultiSelectController;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.PinyinUtils;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本地音乐：把卡里已有的音频（用户自己拷进来的，或别的来源的）扫描出来单独成列表。
 *
 * <p>为什么不并进「下载」列表：下载列表的删除会真的删掉歌曲目录，而这里扫到的都是
 * 用户自己的文件，混在一起太容易误删。所以单独一个列表，并且**只提供批量收藏，
 * 不提供删除**。</p>
 *
 * <p>扫描范围刻意限定，不做全盘遍历（手表上又慢又费电）：
 * {@code 163Music/}（跳过 Download 子树，那是下载列表的地盘）、{@code Music/}、
 * {@code Download/}，且递归深度限制在 {@link #MAX_DEPTH} 层。</p>
 */
public class LocalMusicActivity extends BaseWatchActivity {

    /** 递归深度上限，避免在大目录上扫太久。 */
    private static final int MAX_DEPTH = 4;
    /** 单次扫描最多收集多少首，防止内存被撑爆。 */
    private static final int MAX_TRACKS = 500;

    private final List<Song> localSongs = new ArrayList<>();
    /** 首字母 -> 该字母第一首歌在 localSongs 里的下标（用于索引跳转）。 */
    private final Map<Character, Integer> letterIndex = new LinkedHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ArrayAdapter<Song> adapter;
    private ListView lvSongs;
    private TextView tvEmpty;
    private TextView tvTitle;
    private LinearLayout letterIndexLayout;
    private MultiSelectController multiSelect;
    private boolean scanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local_music);

        tvTitle = findViewById(R.id.tv_local_music_title);
        lvSongs = findViewById(R.id.lv_local_music);
        tvEmpty = findViewById(R.id.tv_local_music_empty);
        letterIndexLayout = findViewById(R.id.layout_letter_index);

        View btnShuffle = findViewById(R.id.btn_local_shuffle);
        if (btnShuffle != null) {
            btnShuffle.setOnClickListener(v -> playShuffled());
        }

        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, localSongs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText(song.getName());
                    tvArtist.setText(song.getArtist());
                    if (ivCover != null) {
                        NetworkImageLoader.load(ivCover, song.getCoverUrl());
                    }
                }
                return view;
            }
        };
        lvSongs.setAdapter(adapter);

        lvSongs.setOnItemClickListener((parent, view, position, id) -> playFrom(position));

        // 只挂「收藏」——这里没有删除，避免误删用户自己的文件
        multiSelect = MultiSelectController.attach(this, lvSongs,
                new MultiSelectController.Callback() {
                    @Override
                    public List<MultiSelectController.Action> getActions() {
                        List<MultiSelectController.Action> actions = new ArrayList<>();
                        actions.add(MultiSelectController.Action.favorite(LocalMusicActivity.this));
                        return actions;
                    }

                    @Override
                    public void onAction(MultiSelectController.Action action, List<Integer> positions) {
                        if (MultiSelectController.Action.ID_FAVORITE.equals(action.id)) {
                            favoriteSelected(positions);
                        }
                    }
                });
        if (multiSelect != null) {
            addBackInterceptor(multiSelect::handleBackPressed);
        }

        updateEmptyState();
        startScan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (multiSelect != null) {
            multiSelect.exitSelectionMode();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }

    // ==================== 扫描 ====================

    private void startScan() {
        if (scanning) {
            return;
        }
        scanning = true;
        setTitleText("扫描中…");
        new Thread(() -> {
            final List<Song> found = scanLocalAudio();
            mainHandler.post(() -> {
                scanning = false;
                localSongs.clear();
                localSongs.addAll(found);
                sortAndIndex();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                updateEmptyState();
            });
        }).start();
    }

    private List<Song> scanLocalAudio() {
        List<Song> result = new ArrayList<>();
        File external = Environment.getExternalStorageDirectory();
        for (File root : new File[]{
                new File(external, "163Music"),
                new File(external, "Music"),
                new File(external, "Download"),
        }) {
            collectAudio(root, 0, result);
            if (result.size() >= MAX_TRACKS) {
                break;
            }
        }
        return result;
    }

    /**
     * 递归收集音频。跳过 {@code 163Music/Download} 子树 —— 那部分由「下载」列表负责展示，
     * 重复出现会让用户以为文件有两份。
     */
    private void collectAudio(File dir, int depth, List<Song> out) {
        if (dir == null || !dir.isDirectory() || depth > MAX_DEPTH || out.size() >= MAX_TRACKS) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child == null || out.size() >= MAX_TRACKS) {
                return;
            }
            if (child.isDirectory()) {
                if (isDownloadSubtree(child)) {
                    continue;
                }
                collectAudio(child, depth + 1, out);
            } else if (DownloadManager.isAudioFile(child)) {
                out.add(fileToSong(child));
            }
        }
    }

    /** 是否是 163Music/Download 下的目录（含其自身）。 */
    private boolean isDownloadSubtree(File dir) {
        try {
            File downloadDir = new File(
                    new File(Environment.getExternalStorageDirectory(), "163Music"), "Download");
            String downloadPath = downloadDir.getCanonicalPath();
            String dirPath = dir.getCanonicalPath();
            return dirPath.equals(downloadPath) || dirPath.startsWith(downloadPath + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 按「曲名 - 曲师」把文件名还原成歌曲；解析不出曲师时用所在目录名兜底。
     */
    private Song fileToSong(File file) {
        String base = DownloadManager.stripAudioExtension(file.getName());
        String name = base;
        String artist = "";
        int separator = base.indexOf(" - ");
        if (separator > 0) {
            name = base.substring(0, separator);
            artist = base.substring(separator + 3);
        } else {
            File parent = file.getParentFile();
            if (parent != null && !parent.getName().isEmpty()) {
                artist = parent.getName();
            }
        }
        String path = file.getAbsolutePath();
        // 本地文件没有网易云 id，用路径哈希造一个稳定的负数 id
        // （负数本身就是「本地」标签，见 LocalTracks）
        Song song = new Song(LocalTracks.idForPath(path),
                LocalTracks.safeName(name), artist, "");
        song.setUrl(path);
        song.setForceLocalPlayback(true);
        return song;
    }

    // ==================== 排序与首字母索引 ====================

    /**
     * 按拼音首字母排序，并建立「首字母 → 第一首下标」的索引。
     *
     * <p>先比首字母（{@link PinyinUtils}），同字母内再按中文排序规则比曲名，
     * 这样「张/周/赵」之间也是稳定的字典序，而不是随扫描顺序乱跳。</p>
     */
    private void sortAndIndex() {
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(localSongs, (left, right) -> {
            char leftInitial = PinyinUtils.initialOfWord(left.getName());
            char rightInitial = PinyinUtils.initialOfWord(right.getName());
            if (leftInitial != rightInitial) {
                // '#' 永远排在最后
                if (leftInitial == PinyinUtils.OTHER) return 1;
                if (rightInitial == PinyinUtils.OTHER) return -1;
                return Character.compare(leftInitial, rightInitial);
            }
            int byName = collator.compare(String.valueOf(left.getName()),
                    String.valueOf(right.getName()));
            if (byName != 0) {
                return byName;
            }
            return 0;
        });

        letterIndex.clear();
        for (int i = 0; i < localSongs.size(); i++) {
            char initial = PinyinUtils.initialOfWord(localSongs.get(i).getName());
            if (!letterIndex.containsKey(initial)) {
                letterIndex.put(initial, i);
            }
        }
        buildLetterIndexBar();
    }

    /** 右侧的字母索引条：只列出实际存在的字母，点一下跳到该字母的第一首。 */
    private void buildLetterIndexBar() {
        if (letterIndexLayout == null) {
            return;
        }
        letterIndexLayout.removeAllViews();
        if (letterIndex.isEmpty()) {
            return;
        }
        boolean compact = letterIndex.size() > 16;
        for (Map.Entry<Character, Integer> entry : letterIndex.entrySet()) {
            final int targetIndex = entry.getValue();
            TextView letterView = new TextView(this);
            letterView.setText(PinyinUtils.labelOf(entry.getKey()));
            letterView.setTextColor(getResources().getColor(R.color.colorPrimary));
            letterView.setTextSize(compact ? 9 : 11);
            letterView.setGravity(Gravity.CENTER);
            letterView.setPadding(dpToPx(3), dpToPx(1), dpToPx(3), dpToPx(1));
            letterView.setClickable(true);
            letterView.setFocusable(true);
            letterView.setOnClickListener(v -> {
                // setSelection 会把该行滚到顶部，正好是「跳到这个字母的位置」
                lvSongs.setSelection(Math.max(0, targetIndex));
            });
            letterIndexLayout.addView(letterView);
        }
    }

    /** 随机播放全部本地音乐。 */
    private void playShuffled() {
        if (localSongs.isEmpty()) {
            Toast.makeText(this, "没有可播放的本地音乐", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Song> playlist = new ArrayList<>(localSongs);
        Collections.shuffle(playlist);
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        playerManager.setPlaylist(playlist, 0);
        // 顺手切成随机模式，这样后续切歌也保持随机
        playerManager.setPlayMode(MusicPlayerManager.PlayMode.RANDOM);
        playerManager.playCurrent();
        finish();
    }

    // ==================== 交互 ====================

    private void playFrom(int position) {
        if (position < 0 || position >= localSongs.size()) {
            return;
        }
        List<Song> playlist = new ArrayList<>(localSongs);
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        playerManager.setPlaylist(playlist, position);
        playerManager.playCurrent();
        finish();
    }

    private void favoriteSelected(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < localSongs.size()) {
                targets.add(localSongs.get(position));
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
                Toast.makeText(LocalMusicActivity.this,
                        BatchFavoriteHelper.buildResultMessage(LocalMusicActivity.this, added, failed),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNeedLogin() {
                Toast.makeText(LocalMusicActivity.this,
                        R.string.multi_select_favorite_need_login, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setTitleText(String text) {
        if (tvTitle != null) {
            tvTitle.setText(text);
        }
    }

    private void updateEmptyState() {
        if (scanning) {
            setTitleText("扫描中…");
        } else {
            setTitleText("本地音乐 (" + localSongs.size() + "首)");
        }
        boolean empty = localSongs.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        lvSongs.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty && !scanning) {
            tvEmpty.setText("未找到本地音乐");
        }
    }
}
