package com.qinghe.music163pro.util;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本地歌曲去重（需求方 2026-10-17 深夜）。
 *
 * <h3>规则（宁可不删，也不误删）</h3>
 * <ol>
 *   <li>只看 {@code Music} 目录（递归）里的音频文件，忽略隐藏目录 / {@code __lyrics__} / {@code .bak}；</li>
 *   <li>先按**文件大小**分桶，大小不同的文件绝不可能重复；</li>
 *   <li>同大小的文件再算 **MD5（流式）**，MD5 相同才算「内容完全相同的副本」；</li>
 *   <li>同一组里**保留一份**（优先保留文件名没有副本后缀的、路径更浅的、名字更短的），
 *       其余移动到 {@code 163Music/DuplicatesTrash/<时间戳>/}（保留相对路径 + 生成 report.txt）；</li>
 *   <li>因此 {@code A专辑歌曲(1)/(2)/(3)} 这种**内容不同**的歌曲永远不会被判为重复；
 *       只有「内容一模一样」的副本才会被收走。</li>
 * </ol>
 *
 * <p>全部是只读判断 + 移动到回收站，不做不可逆删除。</p>
 */
public final class LocalDedupe {

    private static final String TAG = "LocalDedupe";

    private static final String[] AUDIO_EXTS = {
            ".mp3", ".flac", ".m4a", ".aac", ".wav", ".ogg", ".opus", ".ape"
    };

    /** 副本后缀： (1) （1） - 副本 copy copy1 _1 之类。 */
    private static final java.util.regex.Pattern COPY_SUFFIX = java.util.regex.Pattern.compile(
            "(?i)(\\s*[\\(（]\\d+[\\)）]|\\s*-\\s*副本|\\s*copy(\\s*\\d+)?|\\s*_\\d+)\\s*$");

    private LocalDedupe() {
    }

    public interface Listener {
        void onProgress(int scannedFiles, String currentName);
    }

    /** 一组内容完全相同的文件。 */
    public static final class Group {
        public final File keep;
        public final List<File> duplicates;
        public final long size;

        Group(File keep, List<File> duplicates, long size) {
            this.keep = keep;
            this.duplicates = duplicates;
            this.size = size;
        }
    }

    public static final class Plan {
        public final List<Group> groups = new ArrayList<>();
        public int scannedFiles;

        public int duplicateCount() {
            int n = 0;
            for (Group g : groups) {
                n += g.duplicates.size();
            }
            return n;
        }

        public long duplicateBytes() {
            long n = 0;
            for (Group g : groups) {
                n += g.size * g.duplicates.size();
            }
            return n;
        }
    }

    public static final class Result {
        public final List<File> moved = new ArrayList<>();
        public final List<Group> groups = new ArrayList<>();
        public long bytes;
        public String reportPath;
    }

    // ==================== 扫描 ====================

    /** 扫描 root（通常是 /sdcard/Music）里的重复音频（内容完全相同才算）。 */
    public static Plan scan(File root, Listener listener) {
        Plan plan = new Plan();
        if (root == null || !root.isDirectory()) {
            return plan;
        }
        List<File> audios = new ArrayList<>();
        collect(root, audios, 0);
        plan.scannedFiles = audios.size();

        // 1) 按大小分桶
        Map<Long, List<File>> bySize = new LinkedHashMap<>();
        for (File f : audios) {
            long len = f.length();
            if (len <= 0) {
                continue;
            }
            List<File> bucket = bySize.get(len);
            if (bucket == null) {
                bucket = new ArrayList<>();
                bySize.put(len, bucket);
            }
            bucket.add(f);
        }

        // 2) 同大小再比 MD5
        int done = 0;
        for (Map.Entry<Long, List<File>> entry : bySize.entrySet()) {
            List<File> sameSize = entry.getValue();
            if (sameSize.size() < 2) {
                continue;
            }
            Map<String, List<File>> byHash = new LinkedHashMap<>();
            for (File f : sameSize) {
                String hash = md5(f);
                if (hash == null) {
                    continue;
                }
                List<File> bucket = byHash.get(hash);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    byHash.put(hash, bucket);
                }
                bucket.add(f);
            }
            for (List<File> same : byHash.values()) {
                if (same.size() < 2) {
                    continue;
                }
                File keep = chooseKeep(same);
                List<File> dupes = new ArrayList<>(same);
                dupes.remove(keep);
                plan.groups.add(new Group(keep, dupes, entry.getKey()));
            }
            done += sameSize.size();
            if (listener != null) {
                listener.onProgress(done, sameSize.isEmpty() ? "" : sameSize.get(0).getName());
            }
        }
        Log.i(TAG, "扫描完成: 文件 " + plan.scannedFiles + " 个，重复组 " + plan.groups.size()
                + "，可回收 " + plan.duplicateCount() + " 个");
        return plan;
    }

    private static void collect(File dir, List<File> out, int depth) {
        if (depth > 5 || dir == null) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            String name = f.getName();
            if (f.isDirectory()) {
                if (name.startsWith(".") || "__lyrics__".equals(name)) {
                    continue;
                }
                collect(f, out, depth + 1);
            } else if (isAudio(name)) {
                out.add(f);
            }
        }
    }

    private static boolean isAudio(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".bak")) {
            return false;
        }
        for (String ext : AUDIO_EXTS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String stemOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 保留哪一份：非副本名 > 路径浅 > 名字短 > 字典序。 */
    private static File chooseKeep(List<File> files) {
        File best = files.get(0);
        for (File f : files) {
            if (better(f, best)) {
                best = f;
            }
        }
        return best;
    }

    private static boolean better(File a, File b) {
        boolean aCopy = COPY_SUFFIX.matcher(stemOf(a.getName())).find();
        boolean bCopy = COPY_SUFFIX.matcher(stemOf(b.getName())).find();
        if (aCopy != bCopy) {
            return !aCopy;
        }
        int aDepth = depthOf(a);
        int bDepth = depthOf(b);
        if (aDepth != bDepth) {
            return aDepth < bDepth;
        }
        int aLen = a.getName().length();
        int bLen = b.getName().length();
        if (aLen != bLen) {
            return aLen < bLen;
        }
        return a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath()) < 0;
    }

    private static int depthOf(File f) {
        String path = f.getAbsolutePath();
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    // ==================== 移到回收站 ====================

    /**
     * 把重复项移到回收站（保留相对 Music 的目录结构 + 写 report.txt），返回移动结果。
     * 不做不可逆删除，用户可自行从 163Music/DuplicatesTrash 里找回。
     */
    public static Result moveToTrash(Plan plan, File musicRoot, File trashRoot) {
        Result result = new Result();
        if (plan == null || musicRoot == null || trashRoot == null) {
            return result;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File sessionDir = new File(trashRoot, stamp);
        StringBuilder report = new StringBuilder();
        report.append("# 本地歌曲去重报告\n# 时间: ").append(stamp).append('\n');
        report.append("# 规则: 仅移动「文件大小 + MD5 完全相同」的副本；保留一份；A专辑歌曲(1)(2)(3) 这类内容不同的歌不会被删。\n\n");

        for (Group g : plan.groups) {
            report.append("[保留] ").append(relPath(musicRoot, g.keep)).append('\n');
            for (File dup : g.duplicates) {
                String rel = relPath(musicRoot, dup);
                File target = new File(sessionDir, rel);
                try {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        report.append("[失败] ").append(rel).append(" （无法创建目录）\n");
                        continue;
                    }
                    if (target.exists()) {
                        target = new File(parent, System.currentTimeMillis() + "-" + dup.getName());
                    }
                    if (!dup.renameTo(target)) {
                        report.append("[失败] ").append(rel).append(" （移动失败）\n");
                        continue;
                    }
                    result.moved.add(target);
                    result.bytes += g.size;
                    result.groups.add(g);
                    report.append("[移动] ").append(rel).append('\n');
                } catch (Exception e) {
                    report.append("[失败] ").append(rel).append(" （").append(e).append("）\n");
                }
            }
            report.append('\n');
        }

        try {
            if (!sessionDir.exists()) {
                sessionDir.mkdirs();
            }
            File reportFile = new File(sessionDir, "report.txt");
            writeText(reportFile, report.toString());
            result.reportPath = reportFile.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "写去重报告失败: " + e);
        }
        Log.i(TAG, "去重完成: 移动 " + result.moved.size() + " 个文件，回收 "
                + result.bytes + " 字节，回收站=" + sessionDir.getAbsolutePath());
        return result;
    }

    private static String relPath(File root, File file) {
        try {
            String rootPath = root.getAbsolutePath();
            String path = file.getAbsolutePath();
            if (path.startsWith(rootPath)) {
                String rel = path.substring(rootPath.length());
                if (rel.startsWith("/")) {
                    rel = rel.substring(1);
                }
                return rel;
            }
        } catch (Exception ignored) {
        }
        return file.getName();
    }

    private static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(text.getBytes("UTF-8"));
            fos.flush();
        }
    }

    // ==================== MD5 ====================

    private static String md5(File file) {
        FileInputStream fis = null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            fis = new FileInputStream(file);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 默认回收站目录：/sdcard/163Music/DuplicatesTrash。 */
    public static File defaultTrashDir() {
        return new File(android.os.Environment.getExternalStorageDirectory(),
                "163Music/DuplicatesTrash");
    }

    /** 默认音乐目录：/sdcard/Music（与局域网传歌落盘目录一致）。 */
    public static File defaultMusicDir() {
        return new File(android.os.Environment.getExternalStorageDirectory(), "Music");
    }
}
