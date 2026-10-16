package com.qinghe.music163pro.util;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * F5 限音器：整批响度分析（后台、可中断、增量落盘）。
 *
 * <p>文档 F5 改动要点 4：分析是重任务，必须后台执行、可中断、有进度，
 * 且**不得**在进入页面时自动全量分析 —— 所以这里只提供「手动触发一次」的入口。</p>
 *
 * <p>每一步都用 {@link LocalScanScope} 决定扫描范围（与本地音乐页同一套口径），
 * 但不套用「从列表移除」的黑名单：响度是**文件自身的属性**，与用户是否想在列表里看到它无关。</p>
 */
public final class LoudnessScan {

    private static final String TAG = "Loudness";

    private static volatile boolean sRunning = false;
    private static volatile boolean sCancel = false;

    /** 与本地音乐页一致的最大递归深度。 */
    private static final int MAX_DEPTH = 4;

    public interface Listener {
        void onProgress(int done, int total, int analyzed, int cached, int skipped, String currentName);

        void onFinished(int analyzed, int cached, int skipped, boolean cancelled, long elapsedMs);
    }

    private LoudnessScan() {
    }

    public static boolean isRunning() {
        return sRunning;
    }

    /** 请求中断；已完成的条目已落盘，不会白跑。 */
    public static void cancel() {
        sCancel = true;
    }

    /** 收集待分析的音频文件（同一套扫描口径）。 */
    public static List<File> collectAudioFiles(Context context) {
        List<File> out = new ArrayList<>();
        List<File> roots = LocalScanScope.getScanRoots(context);
        for (File root : roots) {
            collect(root, 0, out);
        }
        return out;
    }

    private static void collect(File dir, int depth, List<File> out) {
        if (dir == null || depth > MAX_DEPTH || out.size() >= LocalScanScope.MAX_TRACKS) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (out.size() >= LocalScanScope.MAX_TRACKS) {
                return;
            }
            if (child.isDirectory()) {
                if (!LocalScanScope.isDownloadSubtree(child)) {   // 与本地页同一口径
                    collect(child, depth + 1, out);
                }
            } else if (isAudio(child.getName())) {
                out.add(child);
            }
        }
    }

    private static boolean isAudio(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".m4a")
                || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".wav")
                || lower.endsWith(".opus") || lower.endsWith(".wma");
    }

    /**
     * 启动整批分析（同一时刻只允许一个）。
     *
     * @return false 表示已有任务在跑
     */
    public static boolean start(final Context appContext, final Listener listener) {
        if (sRunning) {
            return false;
        }
        sRunning = true;
        sCancel = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                long t0 = SystemClock.elapsedRealtime();
                int analyzed = 0;
                int cached = 0;
                int skipped = 0;
                boolean cancelled = false;
                try {
                    List<File> files = collectAudioFiles(appContext);
                    Map<String, LoudnessIndex.Entry> index = LoudnessIndex.load(appContext);
                    int total = files.size();
                    MusicLog.i(TAG, "响度分析开始: 待分析 " + total + " 首，已有索引 " + index.size() + " 条");
                    for (int i = 0; i < total; i++) {
                        if (sCancel) {
                            cancelled = true;
                            break;
                        }
                        File f = files.get(i);
                        LoudnessIndex.Entry hit = LoudnessIndex.cachedFor(index, f);
                        if (hit != null) {
                            cached++;
                            continue;   // 已有且未失效 ⇒ 跳过，不重复算
                        }
                        float[] r = LoudnessAnalyzer.analyze(f.getAbsolutePath());
                        if (r == null) {
                            skipped++;
                            MusicLog.w(TAG, "跳过（分析失败）: " + f.getName());
                            continue;
                        }
                        LoudnessIndex.Entry e = new LoudnessIndex.Entry();
                        e.rmsDb = r[0];
                        e.peakDb = r[1];
                        e.size = f.length();
                        e.mtime = f.lastModified();
                        index.put(f.getAbsolutePath(), e);
                        analyzed++;
                        // 每首增量落盘：中途被杀也不丢已完成的部分
                        LoudnessIndex.save(index);
                        MusicLog.i(TAG, "分析 " + (i + 1) + "/" + total + " " + f.getName()
                                + " -> " + LoudnessIndex.describe(e));
                        if (listener != null) {
                            listener.onProgress(i + 1, total, analyzed, cached, skipped, f.getName());
                        }
                    }
                } catch (Throwable t) {
                    MusicLog.w(TAG, "响度分析异常退出", t);
                } finally {
                    sRunning = false;
                    long ms = SystemClock.elapsedRealtime() - t0;
                    MusicLog.i(TAG, "响度分析结束: 新分析 " + analyzed + " 首、命中缓存 " + cached
                            + " 首、跳过 " + skipped + " 首、中断=" + cancelled + " 耗时 " + ms + "ms");
                    if (listener != null) {
                        listener.onFinished(analyzed, cached, skipped, cancelled, ms);
                    }
                }
            }
        }, "loudness-scan").start();
        return true;
    }
}
