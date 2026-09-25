package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.BatchFavoriteHelper;
import com.qinghe.music163pro.util.LocalMetadata;
import com.qinghe.music163pro.util.LocalTracks;
import com.qinghe.music163pro.util.MultiSelectController;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.PinyinUtils;
import com.qinghe.music163pro.util.WatchBackButton;

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
 *
 * <p><b>曲名 / 歌手 / 封面来自哪儿：</b>优先读音频文件自己的元信息标签
 * （见 {@link LocalMetadata}），标签缺失才回落到「文件名里的『曲名 - 曲师』」，
 * 再回落不到就用所在目录名当歌手。此前只做了后两级，于是自己拷进来的歌在列表里
 * 一律显示文件名、封面全空 —— 即使文件里本来就带着标签和封面。</p>
 *
 * <p><b>两个阶段显示：</b>扫描（列文件，快）先出列表；读元信息（每首要解一次音频，慢）
 * 在后台补上并逐批刷新。这样进页面立刻能看到歌单，而不是黑屏等十几秒。</p>
 */
public class LocalMusicActivity extends BaseWatchActivity {

    /** 递归深度上限，避免在大目录上扫太久。 */
    private static final int MAX_DEPTH = 4;
    /** 单次扫描最多收集多少首，防止内存被撑爆。 */
    private static final int MAX_TRACKS = 500;
    /** 打开字母选择页的请求码。 */
    private static final int REQUEST_LETTER_PICKER = 0x51;
    /** 元信息补齐过程中，每处理这么多首就刷新一次列表。 */
    private static final int METADATA_NOTIFY_EVERY = 8;

    private final List<Song> localSongs = new ArrayList<>();
    /**
     * 分组标题（一个字符一组）与每组的首曲下标 / 曲目数，三份等长。
     * 直接以「并行数组」形式持有，是因为要原样塞进 Intent 传给字母选择页；
     * 换成 List&lt;对象&gt; 还得再序列化一次。
     */
    private final List<Character> groupLetters = new ArrayList<>();
    private int[] groupStarts = new int[0];
    private int[] groupCounts = new int[0];
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /**
     * 扫描批次号。重新扫描时自增，用来丢弃上一批还没跑完的元信息补齐 ——
     * 否则旧批次的回调会往新列表里写数据，出现「歌名对不上歌」。
     */
    private int scanGeneration;

    private ArrayAdapter<Song> adapter;
    private ListView lvSongs;
    private TextView tvEmpty;
    private TextView tvTitle;
    private MultiSelectController multiSelect;
    private boolean scanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local_music);

        tvTitle = findViewById(R.id.tv_local_music_title);
        lvSongs = findViewById(R.id.lv_local_music);
        tvEmpty = findViewById(R.id.tv_local_music_empty);
        // 标题栏撑到返回按钮的占位高度：否则按钮会压住第一行的封面（详见该方法注释）
        WatchBackButton.applyTitleBarInset(this, findViewById(R.id.layout_local_music_title_bar));

        View btnLetters = findViewById(R.id.btn_local_letters);
        if (btnLetters != null) {
            btnLetters.setOnClickListener(v -> openLetterPicker());
        }

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
                    // 元信息里可能根本没有歌手 —— 兜底会给出空串，这里再防一次 null
                    tvArtist.setText(song.getArtist() == null ? "" : song.getArtist());
                    if (ivCover != null) {
                        // 本地封面走 file:// ，NetworkImageLoader 已经支持这条分支
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
        final int generation = ++scanGeneration;
        setTitleText("扫描中…");
        new Thread(() -> {
            final List<Song> found = scanLocalAudio();
            mainHandler.post(() -> {
                if (generation != scanGeneration) {
                    return;
                }
                scanning = false;
                localSongs.clear();
                localSongs.addAll(found);
                sortAndIndex();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                updateEmptyState();
            });
            // 列表已经显示出来之后，再慢慢补元信息（不阻塞「先看到歌单」）
            readMetadata(found, generation);
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

    /** 从文件名猜出来的「曲名 / 曲师」。元信息读不到时用它兜底。 */
    private static final class NameGuess {
        final String name;
        final String artist;

        NameGuess(String name, String artist) {
            this.name = name;
            this.artist = artist;
        }
    }

    /**
     * 按「曲名 - 曲师」把文件名拆开；拆不出曲师时用所在目录名兜底。
     *
     * <p>这个猜测在两处用到：扫描时先给出一个能看的标题，以及元信息补齐时作为
     * 缺失字段的回落值。所以抽成一个函数，保证两处口径完全一致。</p>
     */
    private static NameGuess guessFromFileName(File file) {
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
        return new NameGuess(name, artist);
    }

    /**
     * 先用文件名猜出来的信息建一个 Song，保证扫描一结束列表就能显示。
     * 元信息标签稍后由 {@link #readMetadata} 补上。
     */
    private Song fileToSong(File file) {
        NameGuess guess = guessFromFileName(file);
        String path = file.getAbsolutePath();
        // 本地文件没有网易云 id，用路径哈希造一个稳定的负数 id
        // （负数本身就是「本地」标签，见 LocalTracks）
        Song song = new Song(LocalTracks.idForPath(path),
                LocalTracks.safeName(guess.name), guess.artist, "");
        song.setUrl(path);
        song.setForceLocalPlayback(true);
        return song;
    }

    /**
     * 逐首读元信息，把标题 / 歌手 / 内嵌封面补进 Song。
     *
     * <p><b>不能放主线程</b>：每首要开一次 {@link android.media.MediaMetadataRetriever}，
     * 几百首就是十几秒。这里在扫描线程上跑，边跑边分批发通知刷新界面。</p>
     *
     * <p>排序只在全部补完之后重做一次 —— 中途重排会让用户正在看的列表不断跳动。</p>
     *
     * <p>两个提前退出的条件：换了一轮扫描（{@code generation} 变了），或者页面已经关了。
     * 后者是必要的 —— 这几百次音频解析很贵，用户点一下就退出的话不该继续烧电。</p>
     */
    private void readMetadata(List<Song> songs, int generation) {
        boolean changed = false;
        int processed = 0;
        for (Song song : songs) {
            if (generation != scanGeneration || isFinishing() || isDestroyed()) {
                // 已经重新扫描过一轮，或者页面已经关掉，这批结果作废，别再往下白跑
                return;
            }
            processed++;
            File audio = new File(song.getUrl());
            LocalMetadata.Info info = LocalMetadata.read(this, audio);
            NameGuess guess = guessFromFileName(audio);

            String name = info.hasTitle() ? info.title : guess.name;
            String artist = info.hasArtist() ? info.artist : guess.artist;
            String newName = LocalTracks.safeName(name);
            String newArtist = artist == null ? "" : artist;
            if (!newName.equals(song.getName())) {
                song.setName(newName);
                changed = true;
            }
            if (!newArtist.equals(song.getArtist())) {
                song.setArtist(newArtist);
                changed = true;
            }
            if (info.cover != null && !info.cover.getAbsolutePath().isEmpty()) {
                String coverUrl = "file://" + info.cover.getAbsolutePath();
                if (!coverUrl.equals(song.getCoverUrl())) {
                    song.setCoverUrl(coverUrl);
                    changed = true;
                }
            }
            // 边补边刷新：让用户看到名字一个个变正确，而不是一次性跳变
            if (processed % METADATA_NOTIFY_EVERY == 0) {
                mainHandler.post(() -> {
                    if (generation == scanGeneration && adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }
        if (!changed) {
            return;
        }
        mainHandler.post(() -> {
            if (generation != scanGeneration) {
                return;
            }
            // 曲名换成元信息里的之后，字母归属可能变了，重排一次
            sortAndIndex();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
    }

    // ==================== 排序与首字母索引 ====================

    /**
     * 按拼音首字母排序，并建立「首字母 → 第一首下标」的分组。
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
        buildLetterGroups();
    }

    /** 按排好序的列表生成分组信息，供字母选择页使用。 */
    private void buildLetterGroups() {
        Map<Character, Integer> firstIndex = new LinkedHashMap<>();
        Map<Character, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < localSongs.size(); i++) {
            char initial = PinyinUtils.initialOfWord(localSongs.get(i).getName());
            if (!firstIndex.containsKey(initial)) {
                firstIndex.put(initial, i);
            }
            Integer count = counts.get(initial);
            counts.put(initial, count == null ? 1 : count + 1);
        }
        groupLetters.clear();
        groupStarts = new int[firstIndex.size()];
        groupCounts = new int[firstIndex.size()];
        int index = 0;
        for (Map.Entry<Character, Integer> entry : firstIndex.entrySet()) {
            groupLetters.add(entry.getKey());
            groupStarts[index] = entry.getValue();
            Integer count = counts.get(entry.getKey());
            groupCounts[index] = count == null ? 0 : count;
            index++;
        }
    }

    /**
     * 打开独立的字母选择页。
     *
     * <p>原来这里维护的是列表右侧那条竖排窄索引条。它在 320px 的表盘上最窄只能塞
     * 9sp 的字，点不准 —— 换成独立一屏的大格子后触控面积翻了 5 倍以上。</p>
     */
    private void openLetterPicker() {
        if (groupLetters.isEmpty()) {
            Toast.makeText(this, "还没有可跳转的字母", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder letters = new StringBuilder(groupLetters.size());
        for (Character letter : groupLetters) {
            letters.append(letter.charValue());
        }
        Intent intent = new Intent(this, LetterPickerActivity.class);
        intent.putExtra(LetterPickerActivity.EXTRA_LETTERS, letters.toString());
        intent.putExtra(LetterPickerActivity.EXTRA_STARTS, groupStarts);
        intent.putExtra(LetterPickerActivity.EXTRA_COUNTS, groupCounts);
        // 把当前停留位置传过去，让选择页把所在字母高亮出来
        intent.putExtra(LetterPickerActivity.EXTRA_CURRENT, lvSongs.getFirstVisiblePosition());
        startActivityForResult(intent, REQUEST_LETTER_PICKER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LETTER_PICKER || resultCode != RESULT_OK || data == null) {
            return;
        }
        int target = data.getIntExtra(LetterPickerActivity.EXTRA_RESULT_INDEX, -1);
        if (target < 0) {
            return;
        }
        // setSelection 会把该行滚到顶部，正好是「跳到这个字母的位置」
        lvSongs.setSelection(Math.max(0, Math.min(target, localSongs.size() - 1)));
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
