package com.qinghe.music163pro.manager;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 「听bilibili」打开过的 BV 号历史。
 *
 * <p>记录**所有打开过的 BV 号**（不论是从 BV 号输入、搜索、收藏，还是从播放列表点进去的），
 * 目的是「刚才那个视频叫什么来着」—— B 站的 BV 号本身完全记不住，没有历史就只能靠翻收藏。</p>
 *
 * <p>同一 BV 只留一条：再次打开时把它移到最前并刷新标题 / UP 主 / 时间，所以列表天然
 * 是「最近打开的在前」。上限 {@link #MAX_ENTRIES} 条，超出丢最旧的。</p>
 *
 * <p>存放位置与项目其它数据一致：{@code /sdcard/163Music/bilibili-history.json}。</p>
 */
public final class BilibiliHistoryManager {

    private static final String TAG = "BilibiliHistory";
    private static final String DIR_NAME = "163Music";
    private static final String FILE_NAME = "bilibili-history.json";
    /** 最多保留多少条（BV 号很轻，200 条也才几十 KB）。 */
    private static final int MAX_ENTRIES = 200;

    private BilibiliHistoryManager() {
    }

    /** 一条历史。字段只读，避免外部改坏列表。 */
    public static final class Entry {
        public final String bvid;
        public final String title;
        public final String owner;
        /** 最近一次打开的时间戳（毫秒）。 */
        public final long timestamp;

        public Entry(String bvid, String title, String owner, long timestamp) {
            this.bvid = bvid == null ? "" : bvid;
            this.title = title == null ? "" : title;
            this.owner = owner == null ? "" : owner;
            this.timestamp = timestamp;
        }

        /** 列表里显示的名字：没标题就退回 BV 号。 */
        public String displayName() {
            return TextUtils.isEmpty(title) ? bvid : title;
        }
    }

    private static File file() {
        return new File(new File(Environment.getExternalStorageDirectory(), DIR_NAME), FILE_NAME);
    }

    /** 记录一次打开。返回 true 表示是新记录（此前没有这个 BV）。 */
    public static boolean record(Context context, String bvid, String title, String owner) {
        if (TextUtils.isEmpty(bvid)) {
            return false;
        }
        List<Entry> all = getAll(context);
        boolean isNew = true;
        for (int i = 0; i < all.size(); i++) {
            if (bvid.equalsIgnoreCase(all.get(i).bvid)) {
                all.remove(i);
                isNew = false;
                break;
            }
        }
        all.add(0, new Entry(bvid, title, owner, System.currentTimeMillis()));
        while (all.size() > MAX_ENTRIES) {
            all.remove(all.size() - 1);
        }
        save(all);
        return isNew;
    }

    /** 全部历史，最近打开的在前。 */
    public static List<Entry> getAll(Context context) {
        List<Entry> list = new LinkedList<>();
        File f = file();
        if (!f.isFile()) {
            return list;
        }
        try (FileInputStream fis = new FileInputStream(f);
             InputStreamReader reader = new InputStreamReader(fis, "UTF-8")) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[2048];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String bvid = o.optString("bvid", "");
                if (TextUtils.isEmpty(bvid)) {
                    continue;
                }
                list.add(new Entry(bvid, o.optString("title", ""),
                        o.optString("owner", ""), o.optLong("ts", 0L)));
            }
        } catch (Exception e) {
            Log.w(TAG, "读取 B 站历史失败", e);
        }
        return list;
    }

    /** 删除某一条。 */
    public static boolean remove(Context context, String bvid) {
        if (TextUtils.isEmpty(bvid)) {
            return false;
        }
        List<Entry> all = getAll(context);
        for (int i = 0; i < all.size(); i++) {
            if (bvid.equalsIgnoreCase(all.get(i).bvid)) {
                all.remove(i);
                save(all);
                return true;
            }
        }
        return false;
    }

    /** 清空历史。 */
    public static void clear(Context context) {
        File f = file();
        if (f.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static void save(List<Entry> entries) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("bvid", e.bvid);
                o.put("title", e.title);
                o.put("owner", e.owner);
                o.put("ts", e.timestamp);
                arr.put(o);
            }
            File f = file();
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                Log.w(TAG, "创建目录失败: " + parent.getAbsolutePath());
                return;
            }
            try (FileOutputStream fos = new FileOutputStream(f);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8")) {
                writer.write(arr.toString(2));
                writer.flush();
            }
        } catch (Exception e) {
            Log.w(TAG, "保存 B 站历史失败", e);
        }
    }

    /** 把时间戳格式化成「刚刚 / 12 分钟前 / 3 小时前 / 2 天前 / 09-28」这种人话。 */
    public static String formatTime(long ts) {
        if (ts <= 0L) {
            return "";
        }
        long delta = System.currentTimeMillis() - ts;
        if (delta < 60_000L) {
            return "刚刚";
        }
        if (delta < 3_600_000L) {
            return (delta / 60_000L) + " 分钟前";
        }
        if (delta < 86_400_000L) {
            return (delta / 3_600_000L) + " 小时前";
        }
        if (delta < 7 * 86_400_000L) {
            return (delta / 86_400_000L) + " 天前";
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault());
        return fmt.format(new java.util.Date(ts));
    }

    /** 便于外部做防御性拷贝。 */
    public static List<Entry> copyOf(List<Entry> entries) {
        return entries == null ? new ArrayList<>() : new ArrayList<>(entries);
    }
}
