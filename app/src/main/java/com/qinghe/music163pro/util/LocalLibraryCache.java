package com.qinghe.music163pro.util;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.qinghe.music163pro.model.Song;

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
 * 本地音乐库缓存：把「本地歌曲 → 文件路径」这条映射持久化下来。
 *
 * <h3>为什么需要它</h3>
 * <p>本地歌的身份是**文件路径本身**（{@link LocalTracks#idForPath} 是路径的哈希，不可逆）。
 * 一旦路径丢了，就只剩一个负数 id，播放器拿着它既播不了本地文件、也不能去问网易云 ——
 * 表现为「本地文件未找到」。</p>
 *
 * <p>光靠「播放状态里存 url」不够：老版本已经写进磁盘的那份状态里没有 url，
 * 升上来第一次启动照样解析不出路径。缓存的作用是**补救**——只要这首本地歌曾经被扫到过
 * 或被播过，下次启动就能按 id 把它找回来。</p>
 *
 * <h3>写在哪</h3>
 * <p>{@code getFilesDir()}（应用私有目录）。它是**缓存**，不需要抗卸载，也就不必去申请
 * 外部存储权限 —— 权限被拒时其它功能照常，缓存自身不会成为新的失败点。</p>
 *
 * <h3>读取时的校验</h3>
 * <p>返回路径前一律 {@link File#exists()} 校验：文件被删/被移走/SD 卡未挂载时，
 * 宁可返回 null 让上层走原本的兜底与报错，也不要给出一条指向不存在的路径。
 * 同时把这条失效记录清掉，避免缓存越攒越脏。</p>
 */
public final class LocalLibraryCache {

    private static final String TAG = "LocalLibraryCache";
    private static final String FILE_NAME = "local_library.json";
    /** 缓存上限：手表存储有限，超了就丢最旧的。 */
    private static final int MAX_ENTRIES = 2000;

    private static final String KEY_ID = "id";
    private static final String KEY_PATH = "path";
    private static final String KEY_NAME = "name";
    private static final String KEY_ARTIST = "artist";
    private static final String KEY_ALBUM = "album";

    private static final Object LOCK = new Object();
    private static File cacheFile;
    private static boolean loaded;
    /** id → 条目。用 LinkedHashMap 保持写入顺序，便于裁剪最旧的。 */
    private static final Map<Long, Entry> entries = new LinkedHashMap<>();

    private LocalLibraryCache() {
    }

    private static final class Entry {
        final long id;
        final String path;
        final String name;
        final String artist;
        final String album;

        Entry(long id, String path, String name, String artist, String album) {
            this.id = id;
            this.path = path;
            this.name = name;
            this.artist = artist;
            this.album = album;
        }
    }

    /**
     * 初始化。幂等；内部只保留 {@code Application Context}。
     *
     * <p>任何会用到缓存的地方（扫描、播放、状态恢复）调用一次即可。</p>
     */
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
     * 记录一首本地歌。
     *
     * <p>只对**本地**歌生效：{@code url} 是本地路径、或 id 为负（{@link LocalTracks#isLocalId}）。
     * 云歌没有可缓存的路径，存进去只是污染。</p>
     */
    public static void put(Song song) {
        if (song == null) {
            return;
        }
        String path = song.getUrl();
        if (TextUtils.isEmpty(path) || !LocalTracks.isLocalPath(path)) {
            return;
        }
        long id = song.getId();
        if (id == 0 && !TextUtils.isEmpty(path)) {
            // 有的本地歌是从收藏里读出来的，id 可能没算过 —— 按路径补一个稳定的负数 id
            id = LocalTracks.idForPath(path);
        }
        if (!LocalTracks.isLocalId(id)) {
            return;
        }
        synchronized (LOCK) {
            entries.remove(id);
            entries.put(id, new Entry(id, path,
                    nullToEmpty(song.getName()),
                    nullToEmpty(song.getArtist()),
                    nullToEmpty(song.getAlbum())));
            trimLocked();
            scheduleWriteLocked();
        }
    }

    /** 批量记录（扫描结束后整体写入）。 */
    public static void putAll(List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        List<Song> snapshot = new ArrayList<>(songs);
        synchronized (LOCK) {
            for (Song s : snapshot) {
                if (s == null) {
                    continue;
                }
                String path = s.getUrl();
                if (TextUtils.isEmpty(path) || !LocalTracks.isLocalPath(path)) {
                    continue;
                }
                long id = s.getId();
                if (id == 0) {
                    id = LocalTracks.idForPath(path);
                }
                if (!LocalTracks.isLocalId(id)) {
                    continue;
                }
                entries.remove(id);
                entries.put(id, new Entry(id, path,
                        nullToEmpty(s.getName()),
                        nullToEmpty(s.getArtist()),
                        nullToEmpty(s.getAlbum())));
            }
            trimLocked();
            scheduleWriteLocked();
        }
    }

    /**
     * 按 id 查出本地文件路径。
     *
     * @return 存在则返回绝对路径；未缓存、或缓存的那条已被删除/移走，返回 null
     *         （顺便清掉失效记录）
     */
    public static String pathFor(long id) {
        if (!LocalTracks.isLocalId(id)) {
            return null;
        }
        synchronized (LOCK) {
            Entry e = entries.get(id);
            if (e == null || TextUtils.isEmpty(e.path)) {
                return null;
            }
            File f = new File(e.path);
            if (!f.exists()) {
                // 文件没了：这条记录已经没用，清掉免得越攒越脏
                entries.remove(id);
                scheduleWriteLocked();
                return null;
            }
            return e.path;
        }
    }

    /**
     * 按 id 取路径，**不校验文件是否存在**。
     *
     * <p>只在最后兜底时用：SD 卡还没挂载完成时 {@link File#exists()} 会一律返回 false，
     * 但缓存里那条路径其实是对的 —— 这种情况应该交给播放器去真正打开，而不是直接报
     * 「找不到文件」。</p>
     */
    public static String pathForUnchecked(long id) {
        if (!LocalTracks.isLocalId(id)) {
            return null;
        }
        synchronized (LOCK) {
            Entry e = entries.get(id);
            return (e == null || TextUtils.isEmpty(e.path)) ? null : e.path;
        }
    }

    /** 已缓存的条数（用于界面显示 / 自检）。 */
    public static int size() {
        synchronized (LOCK) {
            return entries.size();
        }
    }

    /** 清掉全部缓存。 */
    public static void clear() {
        synchronized (LOCK) {
            entries.clear();
            scheduleWriteLocked();
        }
    }

    // ==================== 内部实现 ====================

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 超出上限时丢最旧的（LinkedHashMap 的迭代顺序 = 写入顺序）。 */
    private static void trimLocked() {
        if (entries.size() <= MAX_ENTRIES) {
            return;
        }
        Iterator<Map.Entry<Long, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext() && entries.size() > MAX_ENTRIES) {
            it.next();
            it.remove();
        }
    }

    /**
     * 落盘。缓存是可重建的，写失败不影响主流程 —— 只记日志。
     *
     * <p>放在后台线程：扫描一次可能写几百条，不该卡住 UI。</p>
     */
    private static void scheduleWriteLocked() {
        final Map<Long, Entry> snapshot = new LinkedHashMap<>(entries);
        final File target = cacheFile;
        if (target == null) {
            return;
        }
        new Thread(() -> writeFile(target, snapshot), "LocalLibraryCache-write").start();
    }

    private static void writeFile(File target, Map<Long, Entry> snapshot) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : snapshot.values()) {
                JSONObject o = new JSONObject();
                o.put(KEY_ID, e.id);
                o.put(KEY_PATH, e.path);
                o.put(KEY_NAME, e.name);
                o.put(KEY_ARTIST, e.artist);
                o.put(KEY_ALBUM, e.album);
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
            Log.w(TAG, "写入本地音乐库缓存失败（可重建，忽略）", e);
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
                long id = o.optLong(KEY_ID, 0);
                String path = o.optString(KEY_PATH, "");
                if (!LocalTracks.isLocalId(id) || TextUtils.isEmpty(path)) {
                    continue;
                }
                // 读进来时先不校验 exists() —— 启动时 SD 卡可能还没挂载好，
                // 校验推迟到真正取路径时（见 pathFor）。
                entries.put(id, new Entry(id, path,
                        o.optString(KEY_NAME, ""),
                        o.optString(KEY_ARTIST, ""),
                        o.optString(KEY_ALBUM, "")));
            }
        } catch (Exception e) {
            // 缓存坏了就当没有，下次扫描会重建
            Log.w(TAG, "读取本地音乐库缓存失败，已忽略", e);
        }
    }
}
