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
 * <h3>规则（需求方 2026-11-15：不要求 100% 不误收，但要求清理重复有效）</h3>
 * <p>三级匹配，逐级放宽；每个文件只归入最先命中的一组：</p>
 * <ol>
 *   <li><b>精确层</b>：文件大小 + 全文件 MD5 完全相同（下载产生的字节级副本）；</li>
 *   <li><b>音频指纹层</b>：跳过 ID3v2 标签，对音频数据取 头/中/尾 三段 64KB 算 MD5。
 *       能抓到「同一音频、标签不同/内嵌歌词后」的副本（全文件 MD5 会变，指纹不变）；</li>
 *   <li><b>归一化曲名层（清理主力）</b>：去副本后缀 {@code (1)/（1）/- 副本/copy/_1}、
 *       去括号内容、去开头编号、去标点空白、小写，按「曲名(+曲师)」分桶。
 *       能抓到不同码率 / 不同来源 / 不同转码的同一首歌 —— 这是旧算法完全漏掉的部分。</li>
 * </ol>
 * <p>每组**保留一份**：优先无副本后缀、其次无损/大文件（音质更好）、再路径浅/名字短。
 * 其余移动到 {@code 163Music/DuplicatesTrash/<时间戳>/}（保留相对路径 + report.txt 标注判定理由），
 * **不做不可逆删除**，误收可随时找回。</p>
 * <p>刻意容忍：同名不同版本（现场版/伴奏）可能被归为一组，故报告里写明理由、保留最优一份。</p>
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
        /** 判定理由：精确 / 音频指纹 / 归一化曲名。 */
        public final String reason;

        Group(File keep, List<File> duplicates, long size, String reason) {
            this.keep = keep;
            this.duplicates = duplicates;
            this.size = size;
            this.reason = reason;
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
    /** 三级宽容扫描：精确 → 音频指纹 → 归一化曲名。 */
    public static Plan scan(File root, Listener listener) {
        Plan plan = new Plan();
        if (root == null || !root.isDirectory()) {
            return plan;
        }
        List<File> audios = new ArrayList<>();
        collect(root, audios, 0);
        plan.scannedFiles = audios.size();

        // 已归入某组的文件（一个文件只处理一次，避免跨层重复收走）
        java.util.HashSet<String> claimed = new java.util.HashSet<>();
        int done = 0;

        // ---- 第 1 级：大小 + 全文件 MD5（精确副本） ----
        Map<Long, List<File>> bySize = new LinkedHashMap<>();
        for (File f : audios) {
            if (f.length() <= 0) {
                continue;
            }
            List<File> bucket = bySize.get(f.length());
            if (bucket == null) {
                bucket = new ArrayList<>();
                bySize.put(f.length(), bucket);
            }
            bucket.add(f);
        }
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
                addGroup(plan, claimed, same, entry.getKey(), "精确(大小+MD5)");
                done += same.size();
            }
            if (listener != null) {
                listener.onProgress(done, sameSize.isEmpty() ? "" : sameSize.get(0).getName());
            }
        }

        // ---- 第 2 级：音频指纹（跳过 ID3 标签，头/中/尾三段采样） ----
        Map<String, List<File>> byFingerprint = new LinkedHashMap<>();
        for (File f : audios) {
            if (claimed.contains(f.getAbsolutePath())) {
                continue;
            }
            String fp = audioFingerprint(f);
            if (fp == null) {
                continue;
            }
            List<File> bucket = byFingerprint.get(fp);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byFingerprint.put(fp, bucket);
            }
            bucket.add(f);
        }
        for (List<File> same : byFingerprint.values()) {
            if (same.size() < 2) {
                continue;
            }
            addGroup(plan, claimed, same, same.get(0).length(), "音频指纹(标签不同也命中)");
            done += same.size();
        }

        // ---- 第 3 级：归一化曲名（清理主力：不同码率/不同来源同一首歌） ----
        Map<String, List<File>> byName = new LinkedHashMap<>();
        for (File f : audios) {
            if (claimed.contains(f.getAbsolutePath())) {
                continue;
            }
            String key = normalizeKey(f.getName());
            if (key == null || key.length() < 2) {
                continue;
            }
            List<File> bucket = byName.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byName.put(key, bucket);
            }
            bucket.add(f);
        }
        for (List<File> same : byName.values()) {
            if (same.size() < 2) {
                continue;
            }
            addGroup(plan, claimed, same, same.get(0).length(), "归一化曲名(容忍版本差异)");
            done += same.size();
        }

        if (listener != null) {
            listener.onProgress(plan.scannedFiles, "扫描完成");
        }
        Log.i(TAG, "扫描完成: 文件 " + plan.scannedFiles + " 个，重复组 " + plan.groups.size()
                + "，可回收 " + plan.duplicateCount() + " 个");
        return plan;
    }

    /** 把一组文件登记进 plan，并标记已处理；保留策略见 {@link #chooseKeep}。 */
    private static void addGroup(Plan plan, java.util.HashSet<String> claimed,
                                 List<File> same, long size, String reason) {
        List<File> free = new ArrayList<>();
        for (File f : same) {
            if (!claimed.contains(f.getAbsolutePath())) {
                free.add(f);
            }
        }
        if (free.size() < 2) {
            return;
        }
        File keep = chooseKeep(free);
        List<File> dupes = new ArrayList<>(free);
        dupes.remove(keep);
        plan.groups.add(new Group(keep, dupes, size, reason));
        for (File f : free) {
            claimed.add(f.getAbsolutePath());
        }
    }

    /** 归一化曲名 key：先去副本后缀，再只取「曲名」（曲师写法差异不影响判重）。 */
    private static String normalizeKey(String fileName) {
        String stem = stemOf(fileName);
        // 先去副本后缀，避免「曲名 - 曲师 - 副本」被错误拆成曲师=副本
        stem = COPY_SUFFIX.matcher(stem).replaceAll("");
        // 先去开头曲目编号（"01 - " 不是曲名/曲师分隔符，别把它当 " - " 切开）
        stem = stem.replaceAll("^\\d+[\\s.\\-_]*", "");
        // 约定「曲名 - 曲师」：只取曲名；忽略曲师能抓到「晴天.mp3」与「晴天 - 周杰伦.mp3」
        // 这类实际重复（不同歌手同名歌可能被误收，需求方明确允许，且全部可找回）
        int dash = stem.indexOf(" - ");
        if (dash > 0) {
            stem = stem.substring(0, dash);
        }
        return normalizeText(stem);
    }

    private static String normalizeText(String raw) {
        String x = raw.toLowerCase(Locale.ROOT);
        x = x.replaceAll("[（(【\\[].*?[)）】\\]]", "");          // 去括号内容（feat./版本）
        x = x.replaceAll("(?i)\\s*[\\(（]\\d+[\\)）]|\\s*-\\s*副本|\\s*copy\\s*\\d*|\\s*_\\d+", "");
        x = x.replaceAll("^\\d+[\\s.\\-_]*", "");                  // 去开头曲目编号
        x = x.replaceAll("[^\\p{L}\\p{N}]+", "");                   // 只留字母/数字/汉字
        return x;
    }

    /** 音频流指纹：跳过 ID3v2，对 头/中/尾 各 64KB 采样算 MD5（不读整文件）。 */
    private static String audioFingerprint(File file) {
        java.io.RandomAccessFile raf = null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            raf = new java.io.RandomAccessFile(file, "r");
            long len = raf.length();
            if (len < 128 * 1024) {
                return null;                       // 太短的文件交给曲名层
            }
            long start = 0;
            byte[] head = new byte[10];
            raf.seek(0);
            if (raf.read(head) == 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                long tag = ((head[6] & 0x7fL) << 21) | ((head[7] & 0x7fL) << 14)
                        | ((head[8] & 0x7fL) << 7) | (head[9] & 0x7fL);
                start = 10 + tag;                 // 跳过标签，只比音频数据
            }
            byte[] buf = new byte[64 * 1024];
            for (long pos : new long[]{start, start + (len - start) / 2, len - buf.length}) {
                raf.seek(pos);
                int n = raf.read(buf);
                if (n > 0) {
                    md.update(buf, 0, n);
                }
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
            if (raf != null) {
                try { raf.close(); } catch (IOException ignored) {}
            }
        }
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
        // 音质优先：无损格式 > 有损；同等级时文件更大通常码率更高
        int aQ = qualityRank(a.getName());
        int bQ = qualityRank(b.getName());
        if (aQ != bQ) {
            return aQ > bQ;
        }
        if (a.length() != b.length()) {
            return a.length() > b.length();
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

    /** 音质档位：无损 3 / 中等 2 / 有损 1；未知按扩展名兜底 1。 */
    private static int qualityRank(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".flac") || n.endsWith(".ape") || n.endsWith(".wav")) {
            return 3;
        }
        if (n.endsWith(".m4a") || n.endsWith(".ogg") || n.endsWith(".opus")) {
            return 2;
        }
        return 1;
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
        report.append("# 规则: 三级宽容匹配（精确大小+MD5 / 音频指纹 / 归一化曲名）；每组保留最优一份（优先无损与大文件）；"
                + "同名不同版本可能被归为一组，误收可从本目录找回。\n\n");

        for (Group g : plan.groups) {
            report.append("[").append(g.reason == null ? "重复" : g.reason).append("]\n");
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
