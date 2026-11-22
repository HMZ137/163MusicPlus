package com.qinghe.music163pro.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import com.qinghe.music163pro.model.Song;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FavoritesManager {

    private static final String TAG = "FavoritesManager";
    private static final String PREFS_NAME = "music163_favorites";
    private static final String KEY_FAVORITES = "favorites_json";
    private static final String KEY_CLOUD_SYNC = "cloud_sync";

    private static final String EXT_DIR_NAME = "163Music";
    private static final String EXT_FILE_NAME = "favorites.json";
    private static final String FAVORITE_IDS_FILE = "favorite-ids.json";

    private final SharedPreferences prefs;
    private final Context context;

    public FavoritesManager(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initIdFile();
    }

    private File getIdsFile() {
        File dir = new File(Environment.getExternalStorageDirectory(), EXT_DIR_NAME);
        return new File(dir, FAVORITE_IDS_FILE);
    }

    private void initIdFile() {
        try {
            File idsFile = getIdsFile();
            if (!idsFile.exists()) {
                // Migrate IDs from favorites.json if it exists
                JSONArray idsArr = new JSONArray();
                File oldFile = new File(new File(Environment.getExternalStorageDirectory(), EXT_DIR_NAME), EXT_FILE_NAME);
                if (oldFile.exists()) {
                    try {
                        FileInputStream fis = new FileInputStream(oldFile);
                        InputStreamReader reader = new InputStreamReader(fis, "UTF-8");
                        StringBuilder sb = new StringBuilder();
                        char[] buf = new char[1024];
                        int len;
                        while ((len = reader.read(buf)) != -1) sb.append(buf, 0, len);
                        reader.close();
                        fis.close();
                        JSONArray oldArr = new JSONArray(sb.toString());
                        for (int i = 0; i < oldArr.length(); i++) {
                            JSONObject obj = oldArr.getJSONObject(i);
                            if (obj.has("id")) idsArr.put(obj.getLong("id"));
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error migrating favorite IDs from old file", e);
                    }
                } else {
                    // Also try SharedPreferences as source
                    String json = prefs.getString(KEY_FAVORITES, "[]");
                    try {
                        JSONArray arr = new JSONArray(json);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject obj = arr.getJSONObject(i);
                            if (obj.has("id")) idsArr.put(obj.getLong("id"));
                        }
                    } catch (Exception ignored) {}
                }
                saveIdFile(idsArr);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error initializing favorite-ids.json", e);
        }
    }

    /** 2026-11-22：收藏 ID 数组内存缓存 —— 每次读盘+解析 JSONArray 太贵，
     *  主界面浮层、歌词页、列表页都在频繁调 isFavorite。写路径会同步更新缓存。 */
    private JSONArray cachedIds;

    private JSONArray loadIdArray() {
        try {
            if (cachedIds != null) {
                return cachedIds;
            }
            File idsFile = getIdsFile();
            if (!idsFile.exists()) {
                cachedIds = new JSONArray();
                return cachedIds;
            }
            FileInputStream fis = new FileInputStream(idsFile);
            InputStreamReader reader = new InputStreamReader(fis, "UTF-8");
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int len;
            while ((len = reader.read(buf)) != -1) sb.append(buf, 0, len);
            reader.close();
            fis.close();
            cachedIds = new JSONArray(sb.toString());
            return cachedIds;
        } catch (Exception e) {
            Log.w(TAG, "Error loading favorite-ids.json", e);
            cachedIds = new JSONArray();
            return cachedIds;
        }
    }

    private void saveIdFile(JSONArray arr) {
        // 2026-11-22：写路径同步刷新内存缓存，保证下次 loadIdArray 拿到最新值
        cachedIds = arr;
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), EXT_DIR_NAME);
            if (!dir.exists()) dir.mkdirs();
            File idsFile = getIdsFile();
            FileOutputStream fos = new FileOutputStream(idsFile);
            OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(arr.toString());
            writer.flush();
            writer.close();
            fos.close();
        } catch (Exception e) {
            Log.w(TAG, "Error saving favorite-ids.json", e);
        }
    }

    public List<Long> getFavoriteIds() {
        List<Long> ids = new ArrayList<>();
        try {
            JSONArray arr = loadIdArray();
            for (int i = 0; i < arr.length(); i++) {
                ids.add(arr.getLong(i));
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading favorite IDs", e);
        }
        return ids;
    }

    public void addFavorite(Song song) {
        List<Song> list = getFavorites();
        for (Song s : list) {
            if (s.getId() == song.getId()) return;
        }
        list.add(0, song);
        saveFavorites(list);
        // 本地收藏用的是「路径派生的负数 id」，不属于网易云的曲目体系，
        // 写进 ids 文件会污染云同步与快速查重，所以跳过。
        if (song.getId() <= 0) {
            return;
        }
        // Also add ID to ids file
        try {
            JSONArray arr = loadIdArray();
            for (int i = 0; i < arr.length(); i++) {
                if (arr.getLong(i) == song.getId()) return;
            }
            JSONArray newArr = new JSONArray();
            newArr.put(song.getId());
            for (int i = 0; i < arr.length(); i++) newArr.put(arr.getLong(i));
            saveIdFile(newArr);
        } catch (Exception e) {
            Log.w(TAG, "Error adding favorite ID", e);
        }
    }

    public void removeFavorite(Song song) {
        List<Song> list = getFavorites();
        List<Song> updated = new ArrayList<>();
        for (Song s : list) {
            if (s.getId() != song.getId()) {
                updated.add(s);
            }
        }
        saveFavorites(updated);
        // Also remove from ids file
        try {
            JSONArray arr = loadIdArray();
            JSONArray newArr = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (arr.getLong(i) != song.getId()) newArr.put(arr.getLong(i));
            }
            saveIdFile(newArr);
        } catch (Exception e) {
            Log.w(TAG, "Error removing favorite ID", e);
        }
    }

    /**
     * 批量加入本地收藏。
     *
     * <p>与逐首调用 {@link #addFavorite(Song)} 的区别是只读写一次 prefs / favorites.json /
     * favorite-ids.json，避免在手表上对一个几十首的列表反复做整表序列化。</p>
     *
     * <p>已收藏（id 相同）的歌曲会被跳过。**本地曲目的负数 id 会被正常收下**，只是不写进
     * favorite-ids.json（那个文件是给云端同步/查重用的）。</p>
     *
     * @param songs 待收藏歌曲，允许为 null / 空。本地曲目请先经
     *              {@link com.qinghe.music163pro.util.LocalTracks#asLocalFavoriteEntry}
     *              规范化（补上负数 id / url / forceLocalPlayback），否则存进去点开播不了
     * @return 实际新增到收藏的数量
     */
    public int addFavorites(List<Song> songs) {
        if (songs == null || songs.isEmpty()) return 0;

        // 先把现有的收藏 id 收进一个集合，避免逐首查重时的重复读取。
        List<Song> list = getFavorites();
        Set<Long> existing = new HashSet<>();
        for (Song s : list) existing.add(s.getId());
        for (Long id : getFavoriteIds()) existing.add(id);

        List<Song> added = new ArrayList<>();
        for (Song song : songs) {
            if (song == null) continue;
            // 注意：本地曲目用的是「路径派生的负数 id」，**不能**按 id <= 0 丢掉 ——
            // 那正是「本地音乐里多选收藏点了没反应」的原因。要不要写进 id 索引见下方。
            if (existing.add(song.getId())) added.add(song);
        }
        if (added.isEmpty()) return 0;

        // 新增项放到列表头部，保持「最近收藏在最前」的相对顺序。
        list.addAll(0, added);
        saveFavorites(list);

        // 同步 id 索引文件：只写云端曲目 id。本地曲目的负数 id 写进去会污染云同步与
        // 快速查重 —— 与单曲 addFavorite 的处理保持一致。
        try {
            JSONArray arr = loadIdArray();
            JSONArray newArr = new JSONArray();
            for (Song s : added) {
                if (s.getId() > 0) newArr.put(s.getId());
            }
            for (int i = 0; i < arr.length(); i++) {
                long id = arr.getLong(i);
                boolean duplicated = false;
                for (Song s : added) {
                    if (s.getId() == id) {
                        duplicated = true;
                        break;
                    }
                }
                if (!duplicated) newArr.put(id);
            }
            saveIdFile(newArr);
        } catch (Exception e) {
            Log.w(TAG, "Error batch adding favorite IDs", e);
        }
        return added.size();
    }

    public boolean isFavorite(long songId) {
        // Check ids file first (faster, ID-only)
        try {
            JSONArray arr = loadIdArray();
            for (int i = 0; i < arr.length(); i++) {
                if (arr.getLong(i) == songId) return true;
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Error checking favorite ID, falling back", e);
        }
        // Fallback to full list
        List<Song> list = getFavorites();
        for (Song s : list) {
            if (s.getId() == songId) return true;
        }
        return false;
    }

    public List<Song> getFavorites() {
        List<Song> list = new ArrayList<>();
        String json = prefs.getString(KEY_FAVORITES, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Song song = new Song(
                        obj.getLong("id"),
                        obj.optString("name", ""),
                        obj.optString("artist", ""),
                        obj.optString("album", "")
                );
                String url = obj.has("url") && !obj.isNull("url") ? obj.getString("url") : null;
                song.setUrl(url);
                list.add(song);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error loading favorites", e);
        }

        // If SharedPreferences is empty, try to restore from external file backup
        if (list.isEmpty()) {
            List<Song> external = loadFromExternalFile();
            if (!external.isEmpty()) {
                Log.d(TAG, "Restoring " + external.size() + " favorites from external file");
                saveFavorites(external);
                return external;
            }
        }

        return list;
    }

    private void saveFavorites(List<Song> list) {
        try {
            JSONArray arr = buildJsonArray(list);
            // Save to SharedPreferences (primary)
            prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply();
            // Also save to external file
            saveToExternalFile(arr);
        } catch (Exception e) {
            Log.w(TAG, "Error saving favorites", e);
        }
    }

    private JSONArray buildJsonArray(List<Song> list) throws Exception {
        JSONArray arr = new JSONArray();
        for (Song s : list) {
            JSONObject obj = new JSONObject();
            obj.put("id", s.getId());
            obj.put("name", s.getName());
            obj.put("artist", s.getArtist());
            obj.put("album", s.getAlbum());
            if (s.getUrl() != null) {
                obj.put("url", s.getUrl());
            }
            arr.put(obj);
        }
        return arr;
    }

    /**
     * Save favorites JSON to /sdcard/163Music/favorites.json
     */
    private void saveToExternalFile(JSONArray arr) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), EXT_DIR_NAME);
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    Log.w(TAG, "Failed to create directory: " + dir.getAbsolutePath());
                    return;
                }
            }
            File file = new File(dir, EXT_FILE_NAME);
            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(arr.toString(2)); // Pretty-printed JSON
            writer.flush();
            writer.close();
            fos.close();
            Log.d(TAG, "Favorites saved to " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "Error saving favorites to external file", e);
        }
    }

    /**
     * Load favorites from /sdcard/163Music/favorites.json
     * Can be used to restore from file backup.
     */
    public List<Song> loadFromExternalFile() {
        List<Song> list = new ArrayList<>();
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), EXT_DIR_NAME);
            File file = new File(dir, EXT_FILE_NAME);
            if (!file.exists()) return list;

            FileInputStream fis = new FileInputStream(file);
            InputStreamReader reader = new InputStreamReader(fis, "UTF-8");
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            reader.close();
            fis.close();

            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Song song = new Song(
                        obj.getLong("id"),
                        obj.optString("name", ""),
                        obj.optString("artist", ""),
                        obj.optString("album", "")
                );
                String url = obj.has("url") && !obj.isNull("url") ? obj.getString("url") : null;
                song.setUrl(url);
                list.add(song);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error loading favorites from external file", e);
        }
        return list;
    }

    public void setCloudSync(boolean enabled) {
        prefs.edit().putBoolean(KEY_CLOUD_SYNC, enabled).apply();
    }

    public boolean isCloudSync() {
        return prefs.getBoolean(KEY_CLOUD_SYNC, false);
    }
}
