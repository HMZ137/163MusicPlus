package com.qinghe.music163pro.util;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地歌曲**元信息索引**：把「文件 → 标签（曲名 / 曲师 / 专辑 / 有无内嵌封面）」持久化下来，
 * 并记住读取时文件的 {@code size + lastModified}。
 *
 * <h3>为什么需要它</h3>
 * <p>{@link LocalMetadata#read} 每首歌都要开一次 {@code MediaMetadataRetriever} 去解析文件头，
 * 几百首就是十几秒 —— 这就是「本地音乐」页每次进来都要等一会的来源。而绝大多数文件在两次进入
 * 之间根本没有变过，那些解析全是白做的。</p>
 *
 * <p>有了索引之后：扫描只对「新增 / 改动过（size 或 mtime 变了）」的文件真正读标签；
 * 其余直接取缓存。而且索引本身就是上次扫描的完整快照，所以进页面时可以先**不碰文件内容**
 * 就把列表铺出来（秒开），再在后台走目录做增删校验。</p>
 *
 * <h3>失效判定</h3>
 * <p>只认 {@code size + lastModified}：这两个字段在「文件被替换 / 重新下载 / 改过标签」时都会变，
 * 比每次重读标签便宜得多。判定失效时顺手<b>把该文件的封面缓存也删掉</b> ——
 * 否则改了标签的文件会继续显示旧封面。</p>
 *
 * <h3>写在哪</h3>
 * <p>{@code getFilesDir()}（应用私有目录）。它是**缓存**，不需要抗卸载，也不需要存储权限，
 * 权限被拒时其它功能照常，缓存不会成为新的失败点。「更多 → 刷新缓存」会把它整份清掉。</p>
 */
public final class LocalMetadataIndex {

    private static final String TAG = "LocalMetadataIndex";
    private static final String FILE_NAME = "local_metadata.json";
    /** 缓存上限：与扫描上限（500 首）留足余量，超了丢最旧的。 */
    private static final int MAX_ENTRIES = 2000;

    private static final String KEY_PATH = "path";
    private static final String KEY_SIZE = "size";
    private static final String KEY_MTIME = "mtime";
    private static final String KEY_TITLE = "title";
    private static final String KEY_ARTIST = "artist";
    private static final String KEY_ALBUM = "album";
    private static final String KEY_COVER = "cover";

    private static final Object LOCK = new Object();
    private static File cacheFile;
    private static boolean loaded;
    /** 写盘合并：有改动待写 / 当前是否正在写（见 {@link #scheduleWriteLocked}）。 */
    private static boolean writePending = false;
    private static boolean writeScheduled = false;
    /** 路径 → 条目。LinkedHashMap 保序，便于超限时丢最旧的。 */
    private static final Map<String, Entry> entries = new LinkedHashMap<>();

    private LocalMetadataIndex() {
    }

    /** 一条索引记录。标签字段可能为 {@code null}（该文件确实没有这些标签）。 */
    public static final class Entry {
        public final String path;
        public final long size;
        public final long modified;
        public final String title;
        public final String artist;
        public final String album;
        /** 该文件有内嵌封面，且封面缓存文件仍然存在。 */
        public final boolean hasCover;

        Entry(String path, long size, long modified,
              String title, String artist, String album, boolean hasCover) {
            this.path = path;
            this.size = size;
            this.modified = modified;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.hasCover = hasCover;
        }
    }

    /** 初始化。幂等；任何会用到索引的地方（扫描页 / 刷新）调用一次即可。 */
    public static void init(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (cacheFile == null) {
                cacheFile = new File(app.getFilesDir(), FILE_NAME);
            }
            if (!loaded) {
                loaded = true;
                readLocked();
            }
        }
    }

    /**
     * 取一条仍然有效的缓存。
     *
     * @return 命中返回条目；未缓存、或文件已被替换（size / mtime 变了）、
     *         或「有封面」但封面缓存已被回收，都返回 {@code null}（调用方应当重新读标签）
     */
    public static Entry get(Context context, File audio) {
        if (context == null || audio == null) {
            return null;
        }
        final String path = audio.getAbsolutePath();
        synchronized (LOCK) {
            Entry e = entries.get(path);
            if (e == null) {
                return null;
            }
            if (e.size != audio.length() || e.modified != audio.lastModified()) {
                // 文件被换过：标签和封面都可能已经不同了。
                // 这里只从内存里丢掉，不写盘 —— 一次扫描里失效的可能是几百首，
                // 每次都触发全量重写就是灾难（见 scheduleWriteLocked 的说明）。
                // 本轮扫描结束时 retain()/put() 会把变更一次性合并落盘。
                entries.remove(path);
                LocalMetadata.invalidateCoverCache(context.getApplicationContext(), path);
                return null;
            }
            if (e.hasCover) {
                File cover = LocalMetadata.cachedCoverFile(context, path);
                if (cover == null || !cover.isFile()) {
                    // 封面缓存被 trim 掉了：当作未命中，重读一次会重新抽出来
                    return null;
                }
            }
            return e;
        }
    }

    /**
     * 与 {@link #get} 相同，但把结果还原成 {@link LocalMetadata.Info}
     * （「有封面」还原成封面缓存文件），方便调用方按原来那套逻辑使用。
     *
     * @return 命中返回 Info；未命中返回 {@code null}，调用方应去 {@link LocalMetadata#read}
     */
    public static LocalMetadata.Info infoFor(Context context, File audio) {
        Entry e = get(context, audio);
        if (e == null) {
            return null;
        }
        File cover = e.hasCover ? LocalMetadata.cachedCoverFile(context, e.path) : null;
        return new LocalMetadata.Info(e.title, e.artist, e.album, cover);
    }

    /** 记录一次标签读取结果（读不到标签也要记 —— 它表示「这个文件已经读过了」）。 */
    public static void put(Context context, File audio, LocalMetadata.Info info) {
        if (context == null || audio == null || !audio.isFile()) {
            return;
        }
        final String path = audio.getAbsolutePath();
        String title = info == null ? null : info.title;
        String artist = info == null ? null : info.artist;
        String album = info == null ? null : info.album;
        File cover = info == null ? null : info.cover;
        synchronized (LOCK) {
            entries.remove(path);
            entries.put(path, new Entry(path, audio.length(), audio.lastModified(),
                    title, artist, album, cover != null && cover.isFile()));
            trimLocked();
            scheduleWriteLocked();
        }
    }

    /**
     * 上次扫描的完整快照，用来在**不碰文件内容**的前提下先把列表铺出来。
     * 顺序即上次写入顺序；是否仍然存在由调用方用 {@link File#isFile()} 过滤。
     */
    public static List<Entry> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(entries.values());
        }
    }

    /**
     * 剪枝：只保留 {@code keepPaths} 里的条目。
     *
     * <p>索引是按路径覆盖写的，从不主动删 —— 删掉的歌会一直留在里面，于是每次进页面都要
     * 逐条 {@code isFile()} 去筛（457 首实测约 1.8s）。走完目录后调一次本方法，
     * 索引就和卡里的实际文件对齐了，首屏也就不必再逐条校验。</p>
     *
     * @return 被移除的条数
     */
    public static int retain(java.util.Set<String> keepPaths) {
        if (keepPaths == null) {
            return 0;
        }
        int removed = 0;
        synchronized (LOCK) {
            Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Entry> e = it.next();
                if (!keepPaths.contains(e.getKey())) {
                    it.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                scheduleWriteLocked();
            }
        }
        return removed;
    }

    /** 清空索引（「刷新缓存」用）。封面缓存不在这里清 —— 由 {@link LocalMetadata#clearCache} 负责。 */
    public static void clear(Context context) {
        synchronized (LOCK) {
            entries.clear();
            scheduleWriteLocked();
        }
    }

    /** 已缓存条数（用于界面提示 / 自检）。 */
    public static int size() {
        synchronized (LOCK) {
            return entries.size();
        }
    }

    // ==================== 内部实现 ====================

    private static void trimLocked() {
        if (entries.size() <= MAX_ENTRIES) {
            return;
        }
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext() && entries.size() > MAX_ENTRIES) {
            it.next();
            it.remove();
        }
    }

    /**
     * 落盘：**合并去抖**，而不是「每次改动都起一个线程重写全量」。
     *
     * <p>为什么必须合并：一次全量重扫（换了 400 多首歌）会连续调用几百次 put，
     * 旧写法等于开几百个线程、每个都把整份索引（100KB 级）重新序列化写盘 ——
     * 在手表上是纯粹的 CPU/IO 风暴，还会把并发的小快照写回去，导致索引被截断成
     * 「只有前几首」，下一轮又变成全量 miss，于是每切一次页面就重来一遍。</p>
     *
     * <p>现在：至多一个写线程在跑，期间的新改动只置 dirty；跑完若 dirty 就再写一轮
     * （写入的是当时最新的快照，所以最后落盘的一定是完整的）。</p>
     */
    private static void scheduleWriteLocked() {
        writePending = true;
        if (writeScheduled) {
            return;     // 已经排了一轮，等它跑完自然会带上这次改动
        }
        writeScheduled = true;
        WRITE_EXECUTOR.execute(() -> {
            while (true) {
                List<Entry> snapshot;
                File target;
                synchronized (LOCK) {
                    if (!writePending) {
                        writeScheduled = false;
                        return;
                    }
                    writePending = false;
                    snapshot = new ArrayList<>(entries.values());
                    target = cacheFile;
                }
                if (target != null) {
                    writeFile(target, snapshot);
                }
            }
        });
    }

    /**
     * 单线程写盘执行器：常驻一个 MIN_PRIORITY 线程，空闲时几乎不耗电。
     * 用它而不是「每次改动 new Thread」：后者会在一次全量重扫里开出几百个线程。
     */
    private static final java.util.concurrent.ExecutorService WRITE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "LocalMetadataIndex-write");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    private static void writeFile(File target, List<Entry> snapshot) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : snapshot) {
                JSONObject o = new JSONObject();
                o.put(KEY_PATH, e.path);
                o.put(KEY_SIZE, e.size);
                o.put(KEY_MTIME, e.modified);
                o.put(KEY_TITLE, e.title == null ? JSONObject.NULL : e.title);
                o.put(KEY_ARTIST, e.artist == null ? JSONObject.NULL : e.artist);
                o.put(KEY_ALBUM, e.album == null ? JSONObject.NULL : e.album);
                o.put(KEY_COVER, e.hasCover);
                arr.put(o);
            }
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(arr.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            // 先写临时文件再改名，避免写一半被杀留下半个文件
            if (!tmp.renameTo(target)) {
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "写入本地歌曲元信息索引失败（可重建，忽略）", e);
        }
    }

    private static void readLocked() {
        File f = cacheFile;
        if (f == null || !f.exists()) {
            return;
        }
        try {
            int len = (int) f.length();
            byte[] buf = new byte[len];
            FileInputStream in = new FileInputStream(f);
            try {
                int read = 0;
                while (read < len) {
                    int n = in.read(buf, read, len - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            } finally {
                in.close();
            }
            JSONArray arr = new JSONArray(new String(buf, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String path = o.optString(KEY_PATH, "");
                if (TextUtils.isEmpty(path)) {
                    continue;
                }
                entries.put(path, new Entry(path,
                        o.optLong(KEY_SIZE, -1L),
                        o.optLong(KEY_MTIME, -1L),
                        optStringOrNull(o, KEY_TITLE),
                        optStringOrNull(o, KEY_ARTIST),
                        optStringOrNull(o, KEY_ALBUM),
                        o.optBoolean(KEY_COVER, false)));
            }
        } catch (Exception e) {
            // 缓存坏了就当没有，下次扫描会重建
            Log.w(TAG, "读取本地歌曲元信息索引失败，已忽略", e);
        }
    }

    /** JSON 里的 null 要还原成「没有这个标签」，不能变成字符串 "null"（否则会显示成歌名）。 */
    private static String optStringOrNull(JSONObject o, String key) {
        if (o.isNull(key)) {
            return null;
        }
        String v = o.optString(key, "");
        return TextUtils.isEmpty(v) ? null : v;
    }
}
