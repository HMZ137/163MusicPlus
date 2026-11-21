package com.qinghe.music163pro.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地音乐歌曲信息缓存（需求 2026-11-21 任务 2 / 任务 5）。
 *
 * <p>除了曲目列表，还缓存**排好序的顺序 + 字母分组**——这两样都要跑拼音比较，
 * 每次进页重算是「点本地音乐卡几秒」的根因。缓存命中时直接填充，零排序开销。</p>
 */
public final class LocalMusicCache {

    private static final String FILE_NAME = "local_music_cache.json";

    private LocalMusicCache() {
    }

    /** 缓存快照。 */
    public static final class Snapshot {
        public final List<com.qinghe.music163pro.model.Song> songs = new ArrayList<>();
        public int sortMode = -1;
        public char[] letters = new char[0];
        public int[] starts = new int[0];
        public int[] counts = new int[0];

        public boolean hasGroups() {
            return letters.length > 0 && starts.length == letters.length
                    && counts.length == letters.length;
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /** 读缓存；无缓存或损坏返回空快照。 */
    public static Snapshot read(Context context) {
        Snapshot out = new Snapshot();
        if (context == null) {
            return out;
        }
        File f = file(context);
        if (!f.exists() || f.length() == 0) {
            return out;
        }
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = fis.read(buf);
            String raw = new String(buf, 0, Math.max(0, n), "UTF-8");
            JSONObject obj = new JSONObject(raw);
            out.sortMode = obj.optInt("sortMode", -1);
            JSONArray arr = obj.optJSONArray("songs");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    com.qinghe.music163pro.model.Song s = new com.qinghe.music163pro.model.Song(
                            o.optLong("id", 0),
                            o.optString("name", ""),
                            o.optString("artist", ""),
                            o.optString("album", ""));
                    s.setUrl(o.optString("url", ""));
                    s.setCoverUrl(o.optString("cover", ""));
                    out.songs.add(s);
                }
            }
            JSONArray lettersArr = obj.optJSONArray("letters");
            JSONArray startsArr = obj.optJSONArray("starts");
            JSONArray countsArr = obj.optJSONArray("counts");
            if (lettersArr != null && startsArr != null && countsArr != null
                    && lettersArr.length() == startsArr.length()
                    && lettersArr.length() == countsArr.length()) {
                int size = lettersArr.length();
                out.letters = new char[size];
                out.starts = new int[size];
                out.counts = new int[size];
                for (int i = 0; i < size; i++) {
                    String s = lettersArr.optString(i, "#");
                    out.letters[i] = s.isEmpty() ? '#' : s.charAt(0);
                    out.starts[i] = startsArr.optInt(i, 0);
                    out.counts[i] = countsArr.optInt(i, 0);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 写缓存（含排序方式与字母分组）。 */
    public static void write(Context context, List<com.qinghe.music163pro.model.Song> songs,
                             int sortMode, List<Character> letters,
                             int[] starts, int[] counts) {
        if (context == null || songs == null || songs.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (com.qinghe.music163pro.model.Song s : songs) {
                JSONObject o = new JSONObject();
                o.put("id", s.getId());
                o.put("name", s.getName() == null ? "" : s.getName());
                o.put("artist", s.getArtist() == null ? "" : s.getArtist());
                o.put("album", s.getAlbum() == null ? "" : s.getAlbum());
                o.put("url", s.getUrl() == null ? "" : s.getUrl());
                o.put("cover", s.getCoverUrl() == null ? "" : s.getCoverUrl());
                arr.put(o);
            }
            JSONObject obj = new JSONObject();
            obj.put("sortMode", sortMode);
            obj.put("savedAt", System.currentTimeMillis());
            obj.put("songs", arr);
            if (letters != null && starts != null && counts != null
                    && letters.size() == starts.length && letters.size() == counts.length) {
                JSONArray la = new JSONArray();
                JSONArray sa = new JSONArray();
                JSONArray ca = new JSONArray();
                for (int i = 0; i < letters.size(); i++) {
                    la.put(String.valueOf(letters.get(i)));
                    sa.put(starts[i]);
                    ca.put(counts[i]);
                }
                obj.put("letters", la);
                obj.put("starts", sa);
                obj.put("counts", ca);
            }
            FileOutputStream fos = new FileOutputStream(file(context));
            fos.write(obj.toString(1).getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /** 手动失效（下次进页必须重新扫描）。 */
    public static void invalidate(Context context) {
        if (context == null) {
            return;
        }
        file(context).delete();
    }
}
