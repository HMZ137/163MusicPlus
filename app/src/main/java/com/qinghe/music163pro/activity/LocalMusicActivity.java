package com.qinghe.music163pro.activity;

import android.app.AlertDialog;
import android.text.TextUtils;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.manager.DownloadManager;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.BatchFavoriteHelper;
import com.qinghe.music163pro.util.LocalLibraryCache;
import com.qinghe.music163pro.util.LocalMetadata;
import com.qinghe.music163pro.util.LocalMusicHides;
import com.qinghe.music163pro.util.LocalTracks;
import com.qinghe.music163pro.util.LocalPlaylistManager;
import com.qinghe.music163pro.util.MultiSelectController;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.ScriptSort;
import com.qinghe.music163pro.util.ScriptTranslit;
import com.qinghe.music163pro.util.SongQuery;
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
    /** 状态 / 筛选行（扫描进度 / 搜索命中 / 已移出若干首）；没有内容时整行不显示。 */
    private TextView tvStatusRow;
    /** 扫描出来的**全量**列表：搜索 / 清除搜索都以它为基准。localSongs 是它过滤后的当前视图。 */
    private final List<Song> allSongs = new ArrayList<>();
    /** 当前搜索关键词；空串表示未搜索。 */
    private String searchQuery = "";
    /** 扫描 / 读标签阶段的状态文案（放状态行，不挤标题）。扫描结束置回 null。 */
    private String scanStatus = null;
    private MultiSelectController multiSelect;
    /** 「从列表移除」的隐藏路径（只过滤列表展示，不删卡里的文件）。扫描开始时重载。 */
    private final java.util.Set<String> hiddenPaths = new java.util.LinkedHashSet<>();

    /** 排序方式：曲名（默认）/ 曲师 / 专辑。选择会记住。 */
    private static final int SORT_NAME = 0;
    private static final int SORT_ARTIST = 1;
    private static final int SORT_ALBUM = 2;
    private static final String PREF_SORT_MODE = "local_sort_mode";
    private int sortMode = SORT_NAME;
    private boolean scanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 本地音乐库缓存：扫描结果落盘，供下次启动解析本地歌路径
        LocalLibraryCache.init(this);
        setContentView(R.layout.activity_local_music);

        tvTitle = findViewById(R.id.tv_local_music_title);
        lvSongs = findViewById(R.id.lv_local_music);
        tvEmpty = findViewById(R.id.tv_local_music_empty);
        tvStatusRow = findViewById(R.id.tv_status_row);
        if (tvStatusRow != null) {
            tvStatusRow.setOnClickListener(v -> onStatusRowClick());
        }
        View btnSearch = findViewById(R.id.btn_local_search);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> showSearchDialog());
        }
        updateStatusRow();
        // 标题栏撑到返回按钮的占位高度：否则按钮会压住第一行的封面（详见该方法注释）
        // 返回按钮的占位加在**整个标题栏**上（而不是只加给「动作按钮那一行」）：
        //   · 加给动作行 → 那一行被强行撑到返回按钮的高度（约 101px），下面再叠一行标题，
        //     整个头部吃掉近 150px，列表只剩两行半；
        //   · 加给外层 → 外层本来就是返回按钮的占地高度，两行内容正好填进去，
        //     头部回到「一个返回按钮」的量级，列表多出约一行空间。
        WatchBackButton.applyTitleBarInset(this, findViewById(R.id.layout_local_music_title_bar));

        // 动作行只留最常用的两个 + 「更多」；五个操作（搜索/排序/A-Z/随机/局域网传歌）全在「更多」里
        View btnSort = findViewById(R.id.btn_local_sort);
        if (btnSort != null) {
            btnSort.setOnClickListener(v -> showSortDialog());
        }
        View btnMore = findViewById(R.id.btn_local_more);
        if (btnMore != null) {
            btnMore.setOnClickListener(v -> showMoreDialog());
        }
        // 记住上次选的排序方式（曲名 / 曲师 / 专辑）
        sortMode = getSharedPreferences("music163_settings", MODE_PRIVATE)
                .getInt(PREF_SORT_MODE, SORT_NAME);

        adapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, localSongs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText(song.getDisplayName());
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
                        // 「加入歌单」：把选中的本地歌曲归类进某个本地歌单（本地收藏模式才有意义）
                        actions.add(new MultiSelectController.Action(
                                "add_to_local_playlist",
                                getString(R.string.add_to_local_playlist),
                                getResources().getColor(R.color.colorPrimary)));
                        // 「移出列表」：只从这个列表里去掉，**不删**卡里的文件（见 LocalMusicHides）
                        actions.add(new MultiSelectController.Action(
                                "hide_from_list",
                                "移出列表",
                                getResources().getColor(R.color.colorError)));
                        return actions;
                    }

                    @Override
                    public void onAction(MultiSelectController.Action action, List<Integer> positions) {
                        if (MultiSelectController.Action.ID_FAVORITE.equals(action.id)) {
                            favoriteSelected(positions);
                        } else if ("add_to_local_playlist".equals(action.id)) {
                            addSelectedToPlaylist(positions);
                        } else if ("hide_from_list".equals(action.id)) {
                            hideSelectedFromList(positions);
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
        scanStatus = "扫描中…";
        updateStatusRow();
        updateEmptyState();
        new Thread(() -> {
            final List<Song> found = scanLocalAudio();
            final int total = found.size();
            mainHandler.post(() -> {
                if (generation != scanGeneration) {
                    return;
                }
                // 全量快照：搜索 / 清除搜索都以它为基准。
                // 这里存的是同一批 Song 对象引用，所以后台补齐元信息（曲名/曲师/专辑）后两边都能看到。
                allSongs.clear();
                allSongs.addAll(found);
                // 扫描结果写进缓存：下次启动不必等扫描完就能解析本地歌路径
                LocalLibraryCache.putAll(found);
                // 统一走 applySearch：无搜索词时它等价于「显示全量」，有搜索词时自动重新过滤
                applySearch(searchQuery);
                // 文件扫完、列表出来了，但曲名/封面还要逐首读标签（后台跑，可能十几秒）。
                // 先把标题保持在「加载中」状态，否则用户看到「本地音乐 N首」、却半天歌名
                // 还是文件名、列表也不再动，会以为卡死了。
                if (total > 0) {
                    scanStatus = "读取歌曲信息中… 0/" + total;
                        updateStatusRow();
                }
                updateEmptyState();
            });
            // 列表已经显示出来之后，再慢慢补元信息（不阻塞「先看到歌单」）
            readMetadata(found, generation, total);
        }).start();
    }

    private List<Song> scanLocalAudio() {
        List<Song> result = new ArrayList<>();
        // 「从列表移除」的隐藏名单：每次扫描重新读，保证外部改动即时生效（只过滤展示，不删文件）
        hiddenPaths.clear();
        hiddenPaths.addAll(LocalMusicHides.getHidden(this));
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
                // 「从列表移除」过的曲目直接跳过：只不进这个列表，卡里的文件原样保留
                if (!hiddenPaths.contains(child.getAbsolutePath())) {
                    out.add(fileToSong(child));
                }
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
    private void readMetadata(List<Song> songs, int generation, int total) {
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
            // 边补边刷新：让用户看到名字一个个变正确，而不是一次性跳变；
            // 同时把进度写进标题，明确表示「还在加载」，避免被误判卡死。
            if (processed % METADATA_NOTIFY_EVERY == 0 || processed == total) {
                final int p = processed;
                mainHandler.post(() -> {
                    if (generation == scanGeneration && adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    if (generation == scanGeneration && total > 0) {
                        scanStatus = "读取歌曲信息中… " + p + "/" + total;
                        updateStatusRow();
                    }
                });
            }
        }
        // 不论有没有字段变化都走收尾：否则 changed=false 时会永远停在「读取歌曲信息中…」。
        // changed 在循环里被赋值，不是 effectively final，lambda 捕获前先落到 final 变量上。
        final boolean changedFinal = changed;
        mainHandler.post(() -> finalizeScan(generation, changedFinal));
    }

    /**
     * 扫描 / 读标签收尾：曲名换成元信息里的之后字母归属可能变了，重排一次；
     * 然后关掉加载态，标题切回正式的「本地音乐 (N首)」。
     */
    private void finalizeScan(int generation, boolean changed) {
        if (generation != scanGeneration || isFinishing() || isDestroyed()) {
            return;
        }
        if (changed) {
            sortAndIndex();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
        scanning = false;
        updateEmptyState();
        // 扫描会为每个文件落一份封面缓存，扫完顺手把总量压回上限（磁盘 IO，放后台）
        new Thread(() -> com.qinghe.music163pro.util.LocalMetadata
                .trimCoverCache(getApplicationContext())).start();
    }

    // ==================== 排序与首字母索引 ====================

    /** 当前排序方式下，用哪个字段做首字母与字典序比较。 */
    private String sortKey(Song song) {
        if (song == null) {
            return "";
        }
        switch (sortMode) {
            case SORT_ARTIST:
                return song.getArtist() == null ? "" : song.getArtist();
            case SORT_ALBUM:
                return song.getAlbum() == null ? "" : song.getAlbum();
            case SORT_NAME:
            default:
                return song.getName() == null ? "" : song.getName();
        }
    }

    /** 当前排序方式的中文名（对话框标题 / 提示用）。 */
    private String sortModeLabel() {
        switch (sortMode) {
            case SORT_ARTIST:
                return "曲师";
            case SORT_ALBUM:
                return "专辑";
            default:
                return "曲名";
        }
    }

    /**
     * 「更多」二级菜单：本页共五个操作，标题栏放不下，统一收在这里。
     *
     * <p>为什么不全挤在标题栏：动作行扣掉返回按钮占位后只有约 220px，五个文字按钮要约 280px，
     * 硬塞必然被压扁或折行。所以最常用的「搜索 / 排序」留在外面，其余进菜单 ——
     * 但菜单里**五条都在**，功能一个不少。</p>
     */
    private void showMoreDialog() {
        final java.util.List<String> labels = new java.util.ArrayList<>();
        labels.add(getString(R.string.search));
        labels.add(getString(R.string.local_music_sort) + "（当前：" + sortModeLabel() + "）");
        labels.add(getString(R.string.local_music_letters) + " 字母索引");
        labels.add(getString(R.string.local_music_shuffle) + "播放");
        // 纯在线模式下不提供「局域网传歌」（把歌传进卡里属于本地场景）
        final boolean withLanTransfer =
                !com.qinghe.music163pro.util.PlaybackMode.isLocalEntryHidden(this);
        if (withLanTransfer) {
            labels.add("局域网传歌");
        }
        final String[] items = labels.toArray(new String[0]);
        com.qinghe.music163pro.util.WatchMenuDialog.show(this, getString(R.string.local_music), items, which -> {
                    switch (which) {
                        case 0:
                            showSearchDialog();
                            break;
                        case 1:
                            showSortDialog();
                            break;
                        case 2:
                            openLetterPicker();
                            break;
                        case 3:
                            playShuffled();
                            break;
                        case 4:
                            if (withLanTransfer) {
                                startActivity(new Intent(this, LanTransferActivity.class));
                            }
                            break;
                        default:
                            break;
                    }
                });
    }

    /** 选择排序方式：曲名 / 曲师 / 专辑（会记住）。 */
    private void showSortDialog() {
        final String[] options = {"按曲名", "按曲师", "按专辑"};
        com.qinghe.music163pro.util.WatchMenuDialog.show(this, "排序方式（当前：" + sortModeLabel() + "）", options, which -> {
                    if (which == sortMode) {
                        return;
                    }
                    sortMode = which;
                    getSharedPreferences("music163_settings", MODE_PRIVATE)
                            .edit().putInt(PREF_SORT_MODE, sortMode).apply();
                    sortAndIndex();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    Toast.makeText(this, "已按「" + sortModeLabel() + "」排序",
                            Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * 按当前排序方式（曲名 / 曲师 / 专辑）排序，并建立「首字母 → 第一首下标」的分组。
     *
     * <p>先比所选字段的首字母（{@link PinyinUtils}，中/西文都能取到），同字母内再用中文排序规则
     * 比整个字段；仍相同就按曲名排 —— 这样顺序稳定，字母索引也与排序口径一致。按曲师 / 专辑排序时，
     * 同一曲师 / 专辑下的曲目会自然聚在一起，正是「按曲师看」「按专辑看」想要的读法。</p>
     */
    private void sortAndIndex() {
        // 排序口径见 ScriptSort：**先按文字分段（拉丁→汉字→假名→西里尔→#）**，
        // 段内各用自己的序（汉字拼音+声调 / 假名五十音 / 西里尔字母表 / 拉丁字母表）。
        Collections.sort(localSongs, (left, right) -> {
            int byScript = ScriptSort.TEXT_ORDER.compare(sortKey(left), sortKey(right));
            if (byScript != 0) {
                return byScript;
            }
            // 同段同字面：再按曲名兜底，保证顺序稳定
            String leftName = left.getName() == null ? "" : left.getName();
            String rightName = right.getName() == null ? "" : right.getName();
            return ScriptSort.TEXT_ORDER.compare(leftName, rightName);
        });
        buildLetterGroups();
    }

    /** 按排好序的列表生成分组信息，供字母选择页使用。 */
    private void buildLetterGroups() {
        // 分组键带「文字」前缀：拉丁的 A 与汉字的 A 是两组（排序也是先分文字段，否则索引会错位）
        Map<String, Integer> firstIndex = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Character> labels = new LinkedHashMap<>();
        for (int i = 0; i < localSongs.size(); i++) {
            String sk = sortKey(localSongs.get(i));
            // 以「显示标签」为分组单位：拉丁 A 与汉字 A 合并成一个 A，
            // 索引页不再出现重复字母（跳转定位到当前顺序里该字母的第一首）
            String key = String.valueOf(ScriptSort.groupLabel(sk).charAt(0));
            if (!firstIndex.containsKey(key)) {
                firstIndex.put(key, i);
                labels.put(key, ScriptSort.groupLabel(sk).charAt(0));
            }
            Integer count = counts.get(key);
            counts.put(key, count == null ? 1 : count + 1);
        }
        groupLetters.clear();
        groupStarts = new int[firstIndex.size()];
        groupCounts = new int[firstIndex.size()];
        // 索引页按 **字母表顺序** 展示（不再跟随列表首现位置）；
        // 跳转仍指向该字母在当前列表里的第一首，所以位置数组照旧有效。
        java.util.List<String> keys = new ArrayList<>(firstIndex.keySet());
        java.util.Collections.sort(keys);
        int index = 0;
        for (String key : keys) {
            Character label = labels.get(key);
            groupLetters.add(label == null ? PinyinUtils.OTHER : label);
            groupStarts[index] = firstIndex.get(key);
            Integer count = counts.get(key);
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

            @Override
            public void onNeedLogin(int addedLocal) {
                if (addedLocal > 0) {
                    // 本地曲目已经按本地收藏处理好了，只是「云端那部分」没登录
                    if (multiSelect != null) {
                        multiSelect.exitSelectionMode();
                    }
                    Toast.makeText(LocalMusicActivity.this,
                            "已加入本地收藏 " + addedLocal + " 首；云端收藏需先登录",
                            Toast.LENGTH_LONG).show();
                } else {
                    onNeedLogin();
                }
            }
        });
    }

    /**
     * 把选中的曲目从本地音乐列表里移除。
     *
     * <p><b>只写隐藏名单，绝不删卡里的文件</b> —— 本地音乐列表扫的是用户自己的音频，
     * 误删是不可挽回的。想恢复时走 {@link #showHiddenDialog()}。</p>
     */
    private void hideSelectedFromList(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < localSongs.size()) {
                Song song = localSongs.get(position);
                if (song != null && !TextUtils.isEmpty(song.getUrl())) {
                    targets.add(song);
                    paths.add(song.getUrl());
                }
            }
        }
        if (paths.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("移出列表")
                .setMessage("把选中的 " + paths.size() + " 首从本地音乐列表移出？\n不会删除卡里的文件。")
                .setPositiveButton("移出", (d, w) -> {
                    int added = LocalMusicHides.hide(this, paths);
                    if (multiSelect != null) {
                        multiSelect.exitSelectionMode();
                    }
                    hiddenPaths.addAll(paths);
                    localSongs.removeAll(targets);
                    sortAndIndex();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    updateEmptyState();
                    updateStatusRow();
                    Toast.makeText(LocalMusicActivity.this,
                            added > 0 ? "已移出 " + added + " 首（文件未删除）"
                                    : "这些曲目已在隐藏名单里",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 状态 / 筛选行文案：扫描进度、搜索命中、已移出首数，按需拼成一行。
     *
     * <p>标题那行只有约 220px 可用（扣掉返回按钮占位与右侧按钮），放不下「读取歌曲信息中… (12/455)」
     * 这种长文案，会被截断成看不到进度 —— 所以进度统一放到这一行来显示。</p>
     */
    private void updateStatusRow() {
        if (tvStatusRow == null) {
            return;
        }
        java.util.List<String> parts = new ArrayList<>();
        if (scanStatus != null && !scanStatus.isEmpty()) {
            parts.add(scanStatus);
        }
        if (!searchQuery.isEmpty()) {
            parts.add("搜索「" + searchQuery + "」命中 " + localSongs.size() + " 首（点按清除）");
        }
        int hidden = LocalMusicHides.getHidden(this).size();
        if (hidden > 0) {
            parts.add("已移出 " + hidden + " 首（点按恢复）");
        }
        if (parts.isEmpty()) {
            tvStatusRow.setVisibility(View.GONE);
            return;
        }
        tvStatusRow.setVisibility(View.VISIBLE);
        tvStatusRow.setText(TextUtils.join(" · ", parts));
    }

    /** 状态行点按：搜索中先清搜索，否则打开「已移出」恢复界面。 */
    private void onStatusRowClick() {
        if (!searchQuery.isEmpty()) {
            applySearch("");
            return;
        }
        showHiddenDialog();
    }

    /** 搜索本地音乐：按曲名 / 曲师 / 专辑 / 文件名过滤当前列表。 */
    private void showSearchDialog() {
        if (scanning) {
            Toast.makeText(this, "正在扫描，稍候再搜", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("曲名 / 曲师 / 专辑");
        input.setText(searchQuery);
        input.setSingleLine(true);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);
        // 输入框吃掉剩余宽度，右侧留给「×」
        input.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 输入框右侧的小「×」：只清空输入框内容。
        // （要清掉已经生效的搜索，把输入框清空后点「確定」即可 —— 空关键词等价于显示全部）
        TextView btnClearInput = new TextView(this);
        btnClearInput.setText("✕");
        btnClearInput.setTextColor(0xFFAAAAAA);
        btnClearInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        btnClearInput.setPadding(px(10), px(4), px(2), px(4));
        btnClearInput.setClickable(true);
        btnClearInput.setFocusable(true);
        btnClearInput.setOnClickListener(v -> input.setText(""));

        android.widget.LinearLayout fieldRow = new android.widget.LinearLayout(this);
        fieldRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        fieldRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        fieldRow.addView(input);
        fieldRow.addView(btnClearInput);

        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(fieldRow, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("搜索本地音乐")
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        applySearch(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 应用搜索关键词：从全量列表 {@link #allSongs} 过滤出当前列表 {@link #localSongs} 并重排。
     * 传空串等于清除搜索、恢复全量。
     */
    private void applySearch(String query) {
        searchQuery = query == null ? "" : query;
        localSongs.clear();
        if (searchQuery.isEmpty()) {
            localSongs.addAll(allSongs);
        } else {
            final String needle = SongQuery.normalize(searchQuery);
            for (Song song : allSongs) {
                if (SongQuery.matches(song, needle)) {
                    localSongs.add(song);
                }
            }
        }
        sortAndIndex();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmptyState();
        updateStatusRow();
    }


    /**
     * 恢复界面：列出被移出的曲目逐条恢复，另给一个「全部恢复」。
     *
     * <p>列表项**必须自己建**（不能用 {@code setItems}）：默认的对话框列表项允许多行，
     * 本地音乐的文件名又长（含「曲名 - 曲师」），一条会被折成两三行、把对话框顶得很高，
     * 一屏放不下两条。这里统一单行 + 中间省略 —— 中间省略能同时保住文件名开头与扩展名。</p>
     */
    private void showHiddenDialog() {
        final java.util.Set<String> hidden = LocalMusicHides.getHidden(this);
        if (hidden.isEmpty()) {
            return;
        }
        final List<String> paths = new ArrayList<>(hidden);
        final List<String> names = LocalMusicHides.displayNames(paths);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return names.size();
            }

            @Override
            public Object getItem(int position) {
                return names.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = convertView instanceof TextView ? (TextView) convertView : null;
                if (tv == null) {
                    tv = new TextView(LocalMusicActivity.this);
                    tv.setTextColor(getResources().getColor(R.color.text_primary));
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
                    tv.setSingleLine(true);
                    tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    tv.setPadding(px(18), px(12), px(18), px(12));
                    tv.setBackgroundResource(android.R.drawable.list_selector_background);
                }
                tv.setText(names.get(position));
                return tv;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            LocalMusicHides.unhide(this, paths.get(position));
            updateStatusRow();
            rescanAfterHideChange();
        });

        new AlertDialog.Builder(this)
                .setTitle("已移出的曲目")
                .setView(list)
                .setPositiveButton("全部恢复", (d, w) -> {
                    LocalMusicHides.unhideAll(this);
                    updateStatusRow();
                    rescanAfterHideChange();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 隐藏名单变了以后重新扫一遍，让列表立刻反映出来。 */
    private void rescanAfterHideChange() {
        scanGeneration++;
        scanning = false;
        localSongs.clear();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        startScan();
    }

    /** 把多选的本地歌曲加入某个本地歌单（或新建歌单）。 */
    private void addSelectedToPlaylist(List<Integer> positions) {
        final List<Song> targets = new ArrayList<>();
        for (int position : positions) {
            if (position >= 0 && position < localSongs.size()) {
                targets.add(localSongs.get(position));
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        List<String> names = LocalPlaylistManager.listPlaylistNames();
        final List<String> choices = new ArrayList<>(names);
        choices.add("＋ " + getString(R.string.new_playlist));
        final String[] items = choices.toArray(new String[0]);
        com.qinghe.music163pro.util.WatchMenuDialog.show(this, getString(R.string.add_to_local_playlist), items, which -> {
                    if (which == choices.size() - 1) {
                        showNewPlaylistDialog(targets);
                        return;
                    }
                    String name = names.get(which);
                    int added = LocalPlaylistManager.addSongs(name, targets);
                    Toast.makeText(LocalMusicActivity.this,
                            added > 0 ? getString(R.string.added_to_playlist, added)
                                    : getString(R.string.already_in_playlist),
                            Toast.LENGTH_SHORT).show();
                    if (multiSelect != null) multiSelect.exitSelectionMode();
                });
    }

    /** 多选「加入歌单」时选择「新建歌单」的弹窗。 */
    private void showNewPlaylistDialog(final List<Song> targets) {
        EditText input = new EditText(this);
        input.setHint(R.string.playlist_name_hint);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);

        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(input, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.create_local_playlist)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, R.string.playlist_name_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int added;
                    if (LocalPlaylistManager.playlistExists(name)) {
                        added = LocalPlaylistManager.addSongs(name, targets);
                    } else if (LocalPlaylistManager.createPlaylist(name)) {
                        added = LocalPlaylistManager.addSongs(name, targets);
                    } else {
                        Toast.makeText(this, R.string.create_failed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this,
                            getString(R.string.created_and_added, added), Toast.LENGTH_SHORT).show();
                    if (multiSelect != null) multiSelect.exitSelectionMode();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateEmptyState() {
        boolean empty = localSongs.isEmpty();
        if (scanning) {
            // 加载中：保留当前阶段标题（扫描中… / 读取歌曲信息中…），不要覆盖；
            // 只管列表 / 空态的可见性，避免「扫描中」把正在显示的进度标题冲掉。
            tvEmpty.setVisibility(View.GONE);
            lvSongs.setVisibility(empty ? View.GONE : View.VISIBLE);
            return;
        }
        // 扫描结束：收起进度状态；标题恒为「本地音乐」（XML 里的默认文案）
        scanStatus = null;
        updateStatusRow();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        lvSongs.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            tvEmpty.setText(searchQuery.isEmpty() ? "未找到本地音乐" : "没有匹配的曲目");
        }
    }
}
