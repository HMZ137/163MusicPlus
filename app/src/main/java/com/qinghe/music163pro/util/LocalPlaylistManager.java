package com.qinghe.music163pro.util;

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
import java.io.BufferedReader;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本地歌单（用于给本地歌曲分类）的持久化与导入导出。
 *
 * <p>每条本地歌单就是一个 JSON 文件，放在 {@code /sdcard/163Music/Playlists/&lt;歌单名&gt;.json}，
 * 文件内容就是与「去二化.json」完全一致的格式：</p>
 * <pre>{"list":[{"album":"..","artist":"..","id":0,"path":"/sdcard/Music/xxx.mp3","title":".."}, ...]}</pre>
 *
 * <p>之所以直接把歌单存成这种「导入/导出」同构的文件，是为了让导入导出零成本：外部给一个
 * 这种格式的 json，读进来就是一份歌单；导出就是把这份文件再复制出去。本地歌曲没有网易云
 * id，所以 {@code id} 字段对本地曲目恒为 0；歌单内部做去重与播放定位用的是按路径哈希造的
 * 负数 id（见 {@link LocalTracks#idForPath}），但写回文件时仍按格式要求落 0。</p>
 */
public final class LocalPlaylistManager {

    private static final String TAG = "LocalPlaylistManager";
    private static final String DIR_163 = "163Music";
    private static final String PLAYLISTS_SUBDIR = "Playlists";
    /** 歌单导入的约定目录（与 Playlists 同级）：用户把待导入的 json 放这里。 */
    private static final String IMPORT_SUBDIR = "Import";
    private static final String EXT = ".json";
    private static final String KEY_LIST = "list";

    private LocalPlaylistManager() {
    }

    /** 歌单目录，不存在时自动创建。 */
    public static File getPlaylistsDir() {
        File dir = new File(new File(Environment.getExternalStorageDirectory(), DIR_163), PLAYLISTS_SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建歌单目录: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * 歌单**导入**约定目录 {@code /sdcard/163Music/Import/}，不存在时自动创建。
     *
     * <p>与 {@link #getPlaylistsDir()} 同级，符合项目已有的 {@code 163Music/} 目录约定。
     * 导入功能只扫这一层（见 {@link #findImportCandidates()}），不再触碰
     * {@code Music/}、{@code Download/}、{@code 163Music/} 全树。</p>
     */
    public static File getImportDir() {
        File dir = new File(new File(Environment.getExternalStorageDirectory(), DIR_163), IMPORT_SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建导入目录: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /** 某个歌单名对应的文件（文件名会做非法字符清洗）。 */
    public static File playlistFile(String name) {
        return new File(getPlaylistsDir(), sanitizeName(name) + EXT);
    }

    /** 把歌单名清洗成可用的文件名（去掉路径分隔符等）。 */
    private static String sanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "未命名歌单";
        }
        return name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** 列出所有本地歌单名（不含 .json），按中文排序。 */
    public static List<String> listPlaylistNames() {
        File dir = getPlaylistsDir();
        List<String> names = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(EXT));
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                names.add(n.substring(0, n.length() - EXT.length()));
            }
        }
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(names, collator);
        return names;
    }

    public static boolean playlistExists(String name) {
        return playlistFile(name).exists();
    }

    /** 创建一个空歌单。已存在则视为成功（幂等）。 */
    public static boolean createPlaylist(String name) {
        try {
            File f = playlistFile(name);
            if (f.exists()) {
                return true;
            }
            if (!getPlaylistsDir().exists() && !getPlaylistsDir().mkdirs()) {
                return false;
            }
            writeList(f, new JSONArray());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "创建歌单失败: " + name, e);
            return false;
        }
    }

    /** 读取歌单曲目数（不必反序列化成 Song）。 */
    public static int getPlaylistSongCount(String name) {
        try {
            JSONArray arr = readList(playlistFile(name));
            return arr.length();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 读取歌单内的全部歌曲。 */
    public static List<Song> getPlaylistSongs(String name) {
        List<Song> songs = new ArrayList<>();
        try {
            JSONArray arr = readList(playlistFile(name));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                songs.add(jsonToSong(obj));
            }
        } catch (Exception e) {
            Log.w(TAG, "读取歌单失败: " + name, e);
        }
        return songs;
    }

    /**
     * 往歌单里追加歌曲（按路径去重）。
     *
     * @return 实际新增的曲目数
     */
    public static int addSongs(String name, List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return 0;
        }
        try {
            File f = playlistFile(name);
            JSONArray arr = readList(f);
            Set<String> existing = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String p = o.optString("path", "");
                if (!p.isEmpty()) existing.add(normalizePath(p));
            }
            int added = 0;
            for (Song s : songs) {
                String p = s.getUrl();
                if (p == null || p.isEmpty()) continue;
                if (existing.add(normalizePath(p))) {
                    arr.put(songToJson(s));
                    added++;
                }
            }
            if (added > 0) {
                writeList(f, arr);
            }
            return added;
        } catch (Exception e) {
            Log.w(TAG, "添加歌曲到歌单失败: " + name, e);
            return 0;
        }
    }

    /** 按位置删除一首（位置从 0 起）。 */
    public static boolean removeSongAt(String name, int index) {
        try {
            File f = playlistFile(name);
            JSONArray arr = readList(f);
            if (index < 0 || index >= arr.length()) return false;
            arr.remove(index);
            writeList(f, arr);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "删除歌单曲目失败: " + name, e);
            return false;
        }
    }

    /**
     * 用给定顺序整体重写歌单（多选里的「上移 / 下移」这类**自定义排序**后调用）。
     *
     * <p>歌单文件本身就是一份有序 json，所以自定义排序不需要额外的排序字段 ——
     * 把新顺序整体写回即可，下次打开还是这个顺序。</p>
     *
     * @return 是否写入成功
     */
    public static boolean saveOrder(String name, List<Song> songs) {
        if (songs == null) {
            return false;
        }
        try {
            JSONArray arr = new JSONArray();
            for (Song s : songs) {
                if (s != null) {
                    arr.put(songToJson(s));
                }
            }
            writeList(playlistFile(name), arr);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "重写歌单顺序失败: " + name, e);
            return false;
        }
    }

    /** 按路径删除一首。 */
    public static boolean removeSongByPath(String name, String path) {
        if (path == null) return false;
        try {
            File f = playlistFile(name);
            JSONArray arr = readList(f);
            String target = normalizePath(path);
            JSONArray out = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!removed && normalizePath(o.optString("path", "")).equals(target)) {
                    removed = true;
                    continue;
                }
                out.put(o);
            }
            if (removed) writeList(f, out);
            return removed;
        } catch (Exception e) {
            Log.w(TAG, "按路径删除歌单曲目失败: " + name, e);
            return false;
        }
    }

    /** 改名（同时移动底层文件）。 */
    public static boolean renamePlaylist(String oldName, String newName) {
        if (newName == null || newName.trim().isEmpty()) return false;
        File oldF = playlistFile(oldName);
        File newF = playlistFile(newName);
        if (!oldF.exists()) return false;
        if (newF.exists()) return false; // 目标已存在，避免覆盖
        return oldF.renameTo(newF);
    }

    /** 删除歌单文件。 */
    public static boolean deletePlaylist(String name) {
        File f = playlistFile(name);
        return !f.exists() || f.delete();
    }

    /**
     * 从一个外部 json 文件导入为一份本地歌单。
     *
     * <p>要求文件内容是 {@code {"list":[...]}} 这种格式；歌单名取文件名（去掉 .json），
     * 若与已有歌单重名则追加序号。</p>
     */
    public static boolean importFromFile(File src, String desiredName) {
        try {
            if (src == null || !src.exists()) return false;
            JSONArray arr = readList(src);
            if (arr.length() == 0) return false;
            String base = (desiredName == null || desiredName.trim().isEmpty())
                    ? stripExt(src.getName()) : desiredName.trim();
            String name = base;
            int n = 1;
            while (playlistFile(name).exists()) {
                name = base + " (" + (++n) + ")";
            }
            File dest = playlistFile(name);
            if (!getPlaylistsDir().exists() && !getPlaylistsDir().mkdirs()) return false;
            writeList(dest, arr);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "导入歌单失败: " + src, e);
            return false;
        }
    }

    /** 把一份歌单导出（复制）到指定目标文件。 */
    public static boolean exportToFile(String name, File dest) {
        try {
            File src = playlistFile(name);
            if (!src.exists()) return false;
            if (dest.getParentFile() != null && !dest.getParentFile().exists()) {
                dest.getParentFile().mkdirs();
            }
            FileInputStream fis = new FileInputStream(src);
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) fos.write(buf, 0, len);
            fis.close();
            fos.close();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "导出歌单失败: " + name, e);
            return false;
        }
    }

    /**
     * 扫描可导入的候选文件：**只扫约定目录 {@code /sdcard/163Music/Import/} 的一层**
     * （不递归，也不再触碰 {@code Music/}、{@code Download/}、{@code 163Music/} 全树），
     * 找出后缀为 .json 且内容是歌单格式的文件，并排除已经是本地歌单的那些（按文件名去重）。
     *
     * <p><b>为什么必须改成约定目录</b>：旧实现递归遍历三棵目录树，并对**每一个** json 调用
     * {@link #isPlaylistJson}(File) —— 而它会用 {@link #readList}(File) 把文件**完整读入并解析**
     * 才能判断格式。等于把这三棵树下的所有 json 全部读一遍，实机表现为「扫描全部文件、极慢、
     * 完全不可用」（需求方原话）。改成只扫一层约定目录后，开销只与本目录内文件数成正比。</p>
     *
     * <p><b>行为变更（需求方明确要求）</b>：原先散落在 {@code Music/}、{@code Download/} 等处的
     * 歌单 json <b>不再被自动发现</b>，必须先移动到约定目录。</p>
     */
    public static List<File> findImportCandidates() {
        List<File> candidates = new ArrayList<>();
        Set<String> existing = new HashSet<>();
        for (String n : listPlaylistNames()) existing.add(n.toLowerCase());
        File[] children = getImportDir().listFiles();
        if (children == null) {
            return candidates;
        }
        for (File child : children) {
            if (child == null || !child.isFile()) continue; // 只扫一层，不递归
            if (!child.getName().toLowerCase().endsWith(EXT)) continue;
            String base = stripExt(child.getName());
            if (existing.contains(base.toLowerCase())) continue; // 已是本地歌单，跳过
            if (isPlaylistJson(child)) candidates.add(child);
        }
        Collections.sort(candidates, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return candidates;
    }

    /** 文件内容是否是歌单格式（含 list 数组）。 */
    private static boolean isPlaylistJson(File f) {
        try {
            JSONArray arr = readList(f);
            return arr.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── 底层 JSON 读写 ──────────────────────────────────────────────────────

    private static JSONArray readList(File f) throws Exception {
        if (f == null || !f.exists()) return new JSONArray();
        FileInputStream fis = new FileInputStream(f);
        InputStreamReader reader = new InputStreamReader(fis, "UTF-8");
        BufferedReader br = new BufferedReader(reader);
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[1024];
        int len;
        while ((len = br.read(buf)) != -1) sb.append(buf, 0, len);
        br.close();
        fis.close();
        JSONObject root = new JSONObject(sb.toString());
        return root.optJSONArray(KEY_LIST);
    }

    private static void writeList(File f, JSONArray list) throws Exception {
        JSONObject root = new JSONObject();
        root.put(KEY_LIST, list);
        FileOutputStream fos = new FileOutputStream(f);
        OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
        writer.write(root.toString(2)); // 美化输出，方便用户在电脑上查看
        writer.flush();
        writer.close();
        fos.close();
    }

    // ── JSON ↔ Song 映射（与「去二化.json」格式严格一致） ──────────────────────

    private static Song jsonToSong(JSONObject obj) {
        String path = obj.optString("path", "");
        Song s = new Song();
        // 应用内部定位/去重用按路径哈希的负数 id；落盘时仍写 0（见 songToJson）。
        s.setId(LocalTracks.idForPath(path));
        String title = obj.optString("title", "");
        if (title.isEmpty() && !path.isEmpty()) {
            title = stripExt(new File(path).getName());
        }
        s.setName(title);
        s.setArtist(obj.optString("artist", ""));
        s.setAlbum(obj.optString("album", ""));
        s.setUrl(path);
        s.setForceLocalPlayback(true);
        return s;
    }

    private static JSONObject songToJson(Song s) throws Exception {
        JSONObject obj = new JSONObject();
        // 本地曲目没有网易云 id，按格式要求落 0；有正 id 的（理论上不会出现在本地歌单）原样保留。
        obj.put("id", s.getId() > 0 ? s.getId() : 0);
        obj.put("title", s.getName() == null ? "" : s.getName());
        obj.put("artist", s.getArtist() == null ? "" : s.getArtist());
        obj.put("album", s.getAlbum() == null ? "" : s.getAlbum());
        obj.put("path", s.getUrl() == null ? "" : s.getUrl());
        return obj;
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private static String stripExt(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        int sep = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (dot > sep && dot >= 0) return name.substring(0, dot);
        return name;
    }

    /** 路径归一化：去末尾分隔符、统一斜杠、小写，用于去重比较。 */
    private static String normalizePath(String p) {
        if (p == null) return "";
        String s = p.trim().replace('\\', '/');
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase();
    }
}
