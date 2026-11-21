package com.qinghe.music163pro.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 本地扫描范围（任务 <b>3-9 / 3-10 / 3-11</b>）—— <b>全 App 唯一的扫描范围入口</b>。
 *
 * <p>改造前扫描范围写死在 {@code LocalMusicActivity}：{@code 163Music/}（跳过 Download 子树）、
 * {@code Music/}、{@code Download/} 三个根目录。L6 要求支持「自定义扫描目录」，
 * 于是把范围收口到这里：<b>内置根目录 ∪ 用户自定义目录</b>，供本地列表、元信息索引、
 * 黑名单以及后续 F4/F5 的批处理共用 —— 避免各处各写一份而产生不一致。</p>
 *
 * <p><b>持久化（3-10）</b>：自定义目录写在 {@code /sdcard/163Music/scan-dirs.json}
 * （沿用项目既有约定：本地相关配置放 {@code 163Music/} 下用 JSON，如 {@code Playlists/}、
 * {@code Import/}）。刻意<b>不写进 {@code music163_settings}</b>，
 * 因此不存在与既有键冲突的风险（见文档附录 8）。</p>
 *
 * <p><b>可访问性（3-11）</b>：自定义目录可能不存在、无读权限，或位于已卸载的存储卷。
 * {@link #getScanRoots(Context)} 会逐个校验并<b>静默跳过</b>失效项（不抛异常、不影响内置目录）；
 * 失效项通过 {@link #getSkippedDirs(Context)} 暴露给调用方去提示用户。</p>
 *
 * <p><b>性能上限必须沿用</b>：{@link #MAX_DEPTH}、{@link #MAX_TRACKS} 是为手表性能设的。
 * 自定义目录可能非常大，调用方必须继续按这两个上限截断，并保持「两阶段加载」；
 * 否则容易触发 ANR（本项目已在下载列表页因主线程读盘踩过一次，见文档 1.6 节）。</p>
 */
public final class LocalScanScope {

    private static final String TAG = "LocalScanScope";
    private static final String DIR_163 = "163Music";
    private static final String DOWNLOAD_SUBDIR = "Download";
    private static final String CONFIG_NAME = "scan-dirs.json";
    private static final String KEY_DIRS = "dirs";

    /** 递归深度上限（沿用改造前的值，勿随意放大）。 */
    public static final int MAX_DEPTH = 4;
    /**
     * 单次扫描曲目上限。
     *
     * <p>2026-11-20 需求：去掉 500 硬上限，目标支持 2–3k 曲库。本地列表是
     * {@code ListView + adapter}（只渲染可见行，天然懒加载），扫描在后台线程执行，
     * 因此把上限提到 2000；若后续实测仍不够再往上调。</p>
     */
    public static final int MAX_TRACKS = 2000;

    private LocalScanScope() {
    }

    // ==================== 内置根目录 ====================

    /** 内置扫描根目录：{@code 163Music/}、{@code Music/}、{@code Download/}。 */
    public static List<File> getBuiltinRoots() {
        File external = Environment.getExternalStorageDirectory();
        List<File> roots = new ArrayList<>(3);
        roots.add(new File(external, DIR_163));
        roots.add(new File(external, "Music"));
        roots.add(new File(external, "Download"));
        return roots;
    }

    /**
     * {@code 163Music/Download} 子树。那部分由「下载」列表负责展示，
     * 本地列表重复出现会让用户以为文件有两份，所以扫描时要跳过。
     */
    public static File getDownloadSubtree() {
        return new File(new File(Environment.getExternalStorageDirectory(), DIR_163),
                DOWNLOAD_SUBDIR);
    }

    /** 目录是否位于 {@code 163Music/Download} 子树下（含其自身）。 */
    public static boolean isDownloadSubtree(File dir) {
        if (dir == null) {
            return false;
        }
        try {
            String downloadPath = getDownloadSubtree().getCanonicalPath();
            String dirPath = dir.getCanonicalPath();
            return dirPath.equals(downloadPath) || dirPath.startsWith(downloadPath + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 持久化（3-10） ====================

    /** 自定义目录的配置文件：{@code /sdcard/163Music/scan-dirs.json}。 */
    public static File getConfigFile(Context context) {
        return new File(new File(Environment.getExternalStorageDirectory(), DIR_163), CONFIG_NAME);
    }

    /** 读原始配置（不过滤、不校验），保持写入顺序。 */
    public static List<String> getRawCustomDirs(Context context) {
        List<String> out = new ArrayList<>();
        File file = getConfigFile(context);
        if (!file.isFile()) {
            return out;
        }
        try {
            FileInputStream fis = new FileInputStream(file);
            BufferedReader br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = br.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            br.close();
            fis.close();
            JSONArray arr = new JSONObject(sb.toString()).optJSONArray(KEY_DIRS);
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null && !s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        } catch (Exception e) {
            // 配置坏了不能连累扫描：当作「没有自定义目录」处理
            Log.w(TAG, "读取自定义扫描目录失败: " + file.getAbsolutePath(), e);
        }
        return out;
    }

    /** 写配置；返回是否成功。 */
    private static boolean writeCustomDirs(Context context, List<String> dirs) {
        File file = getConfigFile(context);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "无法创建配置目录: " + parent.getAbsolutePath());
            return false;
        }
        try {
            JSONArray arr = new JSONArray();
            for (String d : dirs) {
                arr.put(d);
            }
            JSONObject root = new JSONObject();
            root.put(KEY_DIRS, arr);
            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(root.toString(2)); // 美化输出，方便用户在电脑上看
            writer.close();
            fos.close();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "保存自定义扫描目录失败: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * 添加自定义目录：先去重，再做可访问性校验，**通过才落盘**。
     *
     * @return 已是可用目录（含「本来就已存在」的幂等情形）返回 true；
     *         目录不可用或写盘失败返回 false
     */
    public static boolean addCustomDir(Context context, String path) {
        if (context == null || path == null) {
            return false;
        }
        File dir = new File(path.trim());
        if (!isUsableDir(dir)) {
            Log.w(TAG, "拒绝添加不可用的扫描目录: " + path);
            return false;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>(getRawCustomDirs(context));
        if (!set.add(dir.getAbsolutePath())) {
            return true; // 已存在，幂等
        }
        return writeCustomDirs(context, new ArrayList<>(set));
    }

    /** 移除自定义目录；返回是否确实移除了。 */
    public static boolean removeCustomDir(Context context, String path) {
        if (context == null || path == null) {
            return false;
        }
        String target = new File(path.trim()).getAbsolutePath();
        List<String> dirs = getRawCustomDirs(context);
        boolean removed = false;
        for (Iterator<String> it = dirs.iterator(); it.hasNext(); ) {
            if (new File(it.next()).getAbsolutePath().equals(target)) {
                it.remove();
                removed = true;
            }
        }
        return removed && writeCustomDirs(context, dirs);
    }

    // ==================== 可访问性校验（3-11） ====================

    /** 目录是否可用：存在、是目录、可读。 */
    public static boolean isUsableDir(File dir) {
        return dir != null && dir.exists() && dir.isDirectory() && dir.canRead();
    }

    /**
     * 失效的自定义目录（不存在 / 无权限 / 所在卷已卸载）。
     * 调用方可据此提示用户，但**不必**因此中断扫描。
     */
    public static List<String> getSkippedDirs(Context context) {
        List<String> skipped = new ArrayList<>();
        for (String p : getRawCustomDirs(context)) {
            if (!isUsableDir(new File(p))) {
                skipped.add(p);
            }
        }
        return skipped;
    }

    /** 可用的自定义目录（已过滤失效项）。 */
    public static List<File> getUsableCustomDirs(Context context) {
        List<File> out = new ArrayList<>();
        for (String p : getRawCustomDirs(context)) {
            File dir = new File(p);
            if (isUsableDir(dir)) {
                out.add(dir);
            }
        }
        return out;
    }

    // ==================== 唯一入口（3-9） ====================

    /**
     * 本次扫描的根目录 = **可用的自定义目录（在前）∪ 内置根目录（在后）**。
     *
     * <p><b>顺序是有意义的，别改成"内置在前"</b>：{@link #MAX_TRACKS} 在满库设备上会把额度吃光，
     * 自定义目录若排在后面，用户"加了目录却看不到新歌"（功能形同虚设）。
     * 把用户显式指定的目录放最前，才能保证它们一定进得了列表。</p>
     *
     * <p>失效的自定义目录被静默跳过，不影响内置目录、也不抛异常。
     * 需要提示用户时用 {@link #getSkippedDirs(Context)}。</p>
     */
    public static List<File> getScanRoots(Context context) {
        // ⚠️ 顺序**有意义**（D5 的修复，采用「自定义目录优先」方案）：
        //   MAX_TRACKS=500 在满库设备上会把额度吃光（实测日志每次都是「共500首」）。
        //   若自定义目录排在内置目录**之后**，用户"加了目录却看不到新歌"——
        //   功能生效了但**用户看不到任何效果**，等于形同虚设。
        //   把用户**显式指定**的目录放在最前，保证它们一定能进入额度；
        //   内置目录照常扫描，只是满库时尾部被截断（这与改造前的行为一致）。
        List<File> roots = new ArrayList<>();
        int customCount = 0;
        for (File dir : getUsableCustomDirs(context)) {
            if (!roots.contains(dir)) {
                roots.add(dir);
                customCount++;
            }
        }
        for (File root : getBuiltinRoots()) {
            if (!roots.contains(root)) {
                roots.add(root);
            }
        }
        MusicLog.i(TAG, "扫描根顺序: 自定义 " + customCount + " 个在前，内置 "
                + (roots.size() - customCount) + " 个在后（满库时优先保住自定义目录）");
        return roots;
    }
}
