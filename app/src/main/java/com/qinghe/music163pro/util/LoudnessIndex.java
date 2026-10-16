package com.qinghe.music163pro.util;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F5 限音器：响度分析结果的**持久化索引**。
 *
 * <p>沿用 {@link LocalMetadataIndex} 的范式：以「文件路径」为键，
 * 用 <b>size + lastModified</b> 判失效 —— 文件被替换/改动过就重算，否则直接复用。</p>
 *
 * <p>存放位置与项目其它数据一致：{@code /sdcard/163Music/loudness-index.json}。
 * 结构：{@code {"路径": {"rms":-18.2,"peak":-1.3,"size":123456,"mtime":1699999999999}}}
 * （rms/peak 单位 dBFS，0 为满刻度）。</p>
 *
 * <p><b>增量写盘</b>：每分析完一首就落盘一次，所以中途被杀（可中断）不会丢掉已完成的部分。</p>
 */
public final class LoudnessIndex {

    private static final String TAG = "Loudness";
    private static final String DIR_NAME = "163Music";
    private static final String FILE_NAME = "loudness-index.json";

    /** 增益范围：文档要求只做衰减与有限提升，±6 dB 内。 */
    public static final float MAX_BOOST_DB = 6f;
    public static final float MAX_CUT_DB = -6f;
    /** 目标响度（dBFS）。取常见移动端听感目标，偏低不刺耳。 */
    public static final float TARGET_RMS_DB = -18f;

    private static File file() {
        return new File(new File(Environment.getExternalStorageDirectory(), DIR_NAME), FILE_NAME);
    }

    /** 一条分析结果。 */
    public static final class Entry {
        public float rmsDb;
        public float peakDb;
        public long size;
        public long mtime;
    }

    /** 读取整个索引（键 = 绝对路径）。 */
    public static Map<String, Entry> load(Context context) {
        Map<String, Entry> map = new LinkedHashMap<>();
        File f = file();
        if (!f.isFile()) {
            return map;
        }
        try (FileInputStream fis = new FileInputStream(f);
             InputStreamReader reader = new InputStreamReader(fis, "UTF-8")) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONArray names = root.names();
            if (names == null) {
                return map;
            }
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, null);
                JSONObject o = root.optJSONObject(key);
                if (TextUtils.isEmpty(key) || o == null) {
                    continue;
                }
                Entry e = new Entry();
                e.rmsDb = (float) o.optDouble("rms", 0);
                e.peakDb = (float) o.optDouble("peak", 0);
                e.size = o.optLong("size", -1);
                e.mtime = o.optLong("mtime", -1);
                map.put(key, e);
            }
        } catch (Exception e) {
            MusicLog.w(TAG, "读取响度索引失败（当作空索引继续）", e);
        }
        return map;
    }

    /** 增量落盘：把内存里的索引整体写出（调用方保证 map 是最新的）。 */
    public static void save(Map<String, Entry> map) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Entry> kv : map.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("rms", round2(kv.getValue().rmsDb));
                o.put("peak", round2(kv.getValue().peakDb));
                o.put("size", kv.getValue().size);
                o.put("mtime", kv.getValue().mtime);
                root.put(kv.getKey(), o);
            }
            File f = file();
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                MusicLog.w(TAG, "创建目录失败: " + parent.getAbsolutePath());
                return;
            }
            try (FileOutputStream fos = new FileOutputStream(f);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8")) {
                writer.write(root.toString(1));
                writer.flush();
            }
        } catch (Exception e) {
            MusicLog.w(TAG, "保存响度索引失败", e);
        }
    }

    /**
     * 该文件是否已有**仍然有效**的分析结果（size 与 mtime 都一致）。
     *
     * @return 有效则返回已存的 Entry，否则 null（= 需要重算）
     */
    public static Entry cachedFor(Map<String, Entry> index, File audio) {
        if (index == null || audio == null || !audio.isFile()) {
            return null;
        }
        Entry e = index.get(audio.getAbsolutePath());
        if (e == null) {
            return null;
        }
        if (e.size != audio.length() || e.mtime != audio.lastModified()) {
            return null;   // 文件被换过/改过 ⇒ 失效
        }
        return e;
    }

    /**
     * 由分析结果算**播放增益**（dB），供后续挂 LoudnessEnhancer 用。
     *
     * <p>策略（文档 F5 改动要点 3）：目标 {@link #TARGET_RMS_DB}，但
     * <b>只做衰减与有限提升</b>；另外用峰值兜底 —— 若「增益后的峰值」会超过 -0.3 dBFS，
     * 就退到不削波的那个增益。返回的增益恒在 [{@link #MAX_CUT_DB}, {@link #MAX_BOOST_DB}] 内。</p>
     */
    public static float gainDbFor(Entry e) {
        if (e == null) {
            return 0f;
        }
        float gain = TARGET_RMS_DB - e.rmsDb;
        // 峰值兜底：留 0.3 dB 余量，避免削波
        float peakHeadroom = -0.3f - e.peakDb;
        if (gain > peakHeadroom) {
            gain = peakHeadroom;
        }
        if (gain > MAX_BOOST_DB) {
            gain = MAX_BOOST_DB;
        }
        if (gain < MAX_CUT_DB) {
            gain = MAX_CUT_DB;
        }
        return gain;
    }

    /** 调试/日志用。 */
    public static String describe(Entry e) {
        if (e == null) {
            return "(null)";
        }
        return String.format(java.util.Locale.US, "rms=%.2fdB peak=%.2fdB gain=%+.2fdB",
                e.rmsDb, e.peakDb, gainDbFor(e));
    }

    private static double round2(float v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private LoudnessIndex() {
    }
}
