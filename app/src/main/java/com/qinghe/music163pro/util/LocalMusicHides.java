package com.qinghe.music163pro.util;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「从本地音乐列表里移除」的隐藏名单。
 *
 * <p><b>为什么不删文件：</b>本地音乐页扫的是用户自己拷进卡里的音频。用户说「从这个列表里删掉」
 * 时，想要的是「别再出现在这里」，而不是把文件删掉 —— 误删用户自己的音乐是不可挽回的。
 * 所以这里**只记路径、不碰文件**：扫描时跳过名单里的路径即可。</p>
 *
 * <p>存放位置与项目其它数据一致：{@code /sdcard/163Music/hidden-local-tracks.json}，
 * 内容是一个路径字符串数组。同一个文件路径只会记一次。</p>
 */
public final class LocalMusicHides {

    private static final String TAG = "LocalMusicHides";
    private static final String DIR_NAME = "163Music";
    private static final String FILE_NAME = "hidden-local-tracks.json";

    private LocalMusicHides() {
    }

    private static File file() {
        return new File(new File(Environment.getExternalStorageDirectory(), DIR_NAME), FILE_NAME);
    }

    /** 读取全部被隐藏的路径（保持加入顺序，去重）。 */
    public static Set<String> getHidden(Context context) {
        Set<String> result = new LinkedHashSet<>();
        File f = file();
        if (!f.isFile()) {
            return result;
        }
        try (FileInputStream fis = new FileInputStream(f);
             InputStreamReader reader = new InputStreamReader(fis, "UTF-8")) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                String path = arr.optString(i, null);
                if (!TextUtils.isEmpty(path)) {
                    result.add(path);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "读取隐藏名单失败", e);
        }
        return result;
    }

    /** 隐藏若干路径，返回本次新增的数量。 */
    public static int hide(Context context, Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return 0;
        }
        Set<String> all = getHidden(context);
        int before = all.size();
        for (String path : paths) {
            if (!TextUtils.isEmpty(path)) {
                all.add(path);
            }
        }
        int added = all.size() - before;
        if (added > 0) {
            save(all);
        }
        return added;
    }

    /** 取消隐藏某个路径。 */
    public static boolean unhide(Context context, String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        Set<String> all = getHidden(context);
        if (all.remove(path)) {
            save(all);
            return true;
        }
        return false;
    }

    /** 清空隐藏名单（全部恢复）。 */
    public static boolean unhideAll(Context context) {
        File f = file();
        if (!f.isFile()) {
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        return f.delete();
    }

    /** 由「已隐藏路径」得到可读的文件名列表（用于恢复界面）。 */
    public static List<String> displayNames(Collection<String> paths) {
        List<String> names = new ArrayList<>();
        for (String path : paths) {
            names.add(new File(path).getName());
        }
        return names;
    }

    private static void save(Set<String> paths) {
        try {
            JSONArray arr = new JSONArray();
            for (String path : paths) {
                arr.put(path);
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
            Log.w(TAG, "保存隐藏名单失败", e);
        }
    }
}
