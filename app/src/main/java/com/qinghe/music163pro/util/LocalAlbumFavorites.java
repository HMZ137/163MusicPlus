package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.qinghe.music163pro.model.PlaylistInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地收藏的专辑（需求 2026-11-20）。
 *
 * <p>P0-8：专辑收藏改为<b>本地收藏</b>，不再调 {@code /api/album/sub}。
 * 为了和「收藏歌单」混排，专辑在这里被映射成 {@link PlaylistInfo}：
 * <b>id 取负数</b>（{@code -albumId}）作为「这是专辑」的标记，其余字段对应
 * 名称 / 歌手 / 封面 / 曲目数。业务侧用 {@link #isAlbumEntry(PlaylistInfo)} 判断。</p>
 *
 * <p>只存必要元信息，不缓存封面文件、不缓存歌曲列表（需求明确：不需要离线保存）。</p>
 */
public final class LocalAlbumFavorites {

    private static final String PREFS = "music163_settings";
    private static final String KEY = "local_favorite_albums";

    private LocalAlbumFavorites() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 列表项是不是「专辑」条目（负数 id 约定）。 */
    public static boolean isAlbumEntry(PlaylistInfo info) {
        return info != null && info.getId() < 0;
    }

    /** 专辑条目还原成真实专辑 id。 */
    public static long albumIdOf(PlaylistInfo info) {
        return info == null ? 0 : -info.getId();
    }

    public static List<PlaylistInfo> list(Context context) {
        List<PlaylistInfo> out = new ArrayList<>();
        if (context == null) return out;
        String raw = prefs(context).getString(KEY, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long id = o.optLong("id", 0);
                if (id <= 0) continue;
                PlaylistInfo info = new PlaylistInfo(-id, o.optString("name", ""),
                        o.optInt("count", 0), o.optString("artist", ""));
                info.setTrackCount(o.optInt("count", 0));
                info.setCreator(o.optString("artist", ""));
                info.setCoverUrl(o.optString("cover", ""));
                out.add(info);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static boolean isFavorite(Context context, long albumId) {
        if (context == null || albumId <= 0) return false;
        for (PlaylistInfo info : list(context)) {
            if (albumIdOf(info) == albumId) return true;
        }
        return false;
    }

    public static void add(Context context, long albumId, String name, String artist,
                           String cover, int trackCount) {
        if (context == null || albumId <= 0) return;
        List<PlaylistInfo> current = list(context);
        for (int i = 0; i < current.size(); i++) {
            if (albumIdOf(current.get(i)) == albumId) {
                current.remove(i);
                break;
            }
        }
        PlaylistInfo info = new PlaylistInfo(-albumId, name == null ? "" : name,
                trackCount, artist == null ? "" : artist);
        info.setCreator(artist == null ? "" : artist);
        info.setCoverUrl(cover == null ? "" : cover);
        info.setTrackCount(trackCount);
        current.add(0, info);   // 新收藏置顶
        write(context, current);
    }

    public static void remove(Context context, long albumId) {
        if (context == null || albumId <= 0) return;
        List<PlaylistInfo> current = list(context);
        boolean changed = false;
        for (int i = current.size() - 1; i >= 0; i--) {
            if (albumIdOf(current.get(i)) == albumId) {
                current.remove(i);
                changed = true;
            }
        }
        if (changed) write(context, current);
    }

    /** @return 收藏后是否处于「已收藏」状态 */
    public static boolean toggle(Context context, long albumId, String name, String artist,
                                 String cover, int trackCount) {
        if (isFavorite(context, albumId)) {
            remove(context, albumId);
            return false;
        }
        add(context, albumId, name, artist, cover, trackCount);
        return true;
    }

    private static void write(Context context, List<PlaylistInfo> list) {
        try {
            JSONArray arr = new JSONArray();
            for (PlaylistInfo info : list) {
                JSONObject o = new JSONObject();
                o.put("id", albumIdOf(info));
                o.put("name", info.getName() == null ? "" : info.getName());
                o.put("artist", info.getCreator() == null ? "" : info.getCreator());
                o.put("cover", info.getCoverUrl() == null ? "" : info.getCoverUrl());
                o.put("count", info.getTrackCount());
                arr.put(o);
            }
            // 用 commit 同步落盘：收藏是低频操作，且要防止快速杀进程丢数据
            prefs(context).edit().putString(KEY, arr.toString()).commit();
        } catch (Exception ignored) {
        }
    }
}
