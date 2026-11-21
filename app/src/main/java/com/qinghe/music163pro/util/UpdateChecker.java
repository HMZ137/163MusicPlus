package com.qinghe.music163pro.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 更新检查（2026-11-21）：数据源从第三方 API 换成本仓库根目录的 {@code update.json}。
 *
 * <p>清单地址按顺序尝试：jsDelivr（国内一般可达，带 CDN 缓存）→ raw.githubusercontent（备用）。
 * 清单格式见仓库根目录的 update.json：versionCode / versionName / apkUrl / notes / mirrors。</p>
 *
 * <p>所有回调都切回主线程，调用方可以直接刷新 UI。</p>
 */
public final class UpdateChecker {

    /**
     * 清单地址（按顺序尝试，先成功者生效）。
     *
     * <p>2026-11-21：jsDelivr 对 {@code @main} 的缓存最长 12 小时、purge 也刷不干净，
     * 导致改了清单线上仍是旧内容。这里把新版本清单放到 {@code updates/manifest.json}
     * （新路径没有历史缓存，立即生效）；旧的 {@code update.json} 保留给已发布的旧版本 App。</p>
     */
    private static final String[] MANIFEST_URLS = {
            "https://cdn.jsdelivr.net/gh/HMZ137/163MusicPlus@main/updates/manifest.json",
            "https://raw.githubusercontent.com/HMZ137/163MusicPlus/main/updates/manifest.json",
            "https://cdn.jsdelivr.net/gh/HMZ137/163MusicPlus@main/update.json",
            "https://raw.githubusercontent.com/HMZ137/163MusicPlus/main/update.json"
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {
    }

    /** 版本信息（保留原类的形状，避免上层改动）。 */
    public static class VersionInfo {
        private final String versionName;
        private final int versionCode;
        private final String notes;
        private final int localVersionCode;

        public VersionInfo(String versionName, int versionCode, String notes, int localVersionCode) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.notes = notes;
            this.localVersionCode = localVersionCode;
        }

        public String getVersionName() {
            return versionName;
        }

        public int getVersionCode() {
            return versionCode;
        }

        public String getNotes() {
            return notes;
        }

        /**
         * 当前安装版本是否已是最新。
         *
         * <p>优先按 versionCode 比较；清单没填版本码时保守判定为「最新」，
         * 避免把「检查不出结果」误报成有新版本。</p>
         */
        public boolean isLatest() {
            if (versionCode > 0 && localVersionCode > 0) {
                return localVersionCode >= versionCode;
            }
            return true;
        }
    }

    public interface VersionCheckCallback {
        void onResult(VersionInfo info);

        void onError(String error);
    }

    public interface SourcesCallback {
        void onResult(List<String> urls);

        void onError(String error);
    }

    /** 下载回调（进度/完成/失败均切主线程）。 */
    public interface DownloadCallback {
        void onProgress(int percent);

        void onComplete(String filePath);

        void onError(String error);
    }

    /**
     * 下载文件到指定路径。
     *
     * <p>保留这个通用下载接口：小天才模块等页面在用它下模块包，与本项目的 APK 下载共用同一套实现。</p>
     */
    public static void downloadUpdate(String url, String savePath, DownloadCallback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            java.io.OutputStream out = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "163MusicPlus");
                conn.connect();
                if (conn.getResponseCode() != 200) {
                    throw new java.io.IOException("HTTP " + conn.getResponseCode());
                }
                long total = conn.getContentLength();
                java.io.File target = new java.io.File(savePath);
                java.io.File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                in = conn.getInputStream();
                out = new java.io.FileOutputStream(target);
                byte[] buf = new byte[8192];
                long done = 0;
                int n;
                int lastPercent = -1;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int percent = (int) (done * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            final int p = percent;
                            MAIN.post(() -> callback.onProgress(p));
                        }
                    }
                }
                out.flush();
                MAIN.post(() -> callback.onComplete(savePath));
            } catch (Throwable t) {
                final String msg = t.getMessage() == null ? "下载失败" : t.getMessage();
                MAIN.post(() -> callback.onError(msg));
            } finally {
                try {
                    if (out != null) out.close();
                } catch (Throwable ignored) {
                }
                try {
                    if (in != null) in.close();
                } catch (Throwable ignored) {
                }
                if (conn != null) conn.disconnect();
            }
        }, "update-download").start();
    }

    /** 拉取清单；全部源都失败返回 null。 */
    public static JSONObject fetchManifest() {
        for (String url : MANIFEST_URLS) {
            JSONObject obj = fetchJson(url);
            if (obj != null) {
                return obj;
            }
        }
        return null;
    }

    private static JSONObject fetchJson(String urlStr) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "163MusicPlus");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.connect();
            if (conn.getResponseCode() != 200) {
                return null;
            }
            in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            if (conn != null) conn.disconnect();
        }
    }

    /** 版本信息（后台线程拉清单，回调切主线程）。 */
    public static void checkVersionInfo(Context context, VersionCheckCallback callback) {
        new Thread(() -> {
            final JSONObject obj = fetchManifest();
            MAIN.post(() -> {
                if (obj == null) {
                    callback.onError("无法连接更新服务器");
                    return;
                }
                String name = obj.optString("versionName", "").trim();
                if (name.isEmpty()) {
                    callback.onError("清单缺少 versionName");
                    return;
                }
                int localCode = 0;
                try {
                    localCode = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0).versionCode;
                } catch (Throwable ignored) {
                }
                callback.onResult(new VersionInfo(name,
                        obj.optInt("versionCode", 0),
                        obj.optString("notes", ""),
                        localCode));
            });
        }, "update-check").start();
    }

    /** 下载源：清单里的 apkUrl + mirrors。 */
    public static void fetchSources(SourcesCallback callback) {
        new Thread(() -> {
            final JSONObject obj = fetchManifest();
            final List<String> urls = new ArrayList<>();
            String error = null;
            if (obj == null) {
                error = "无法连接更新服务器";
            } else {
                // 2026-11-21：国内加速源优先（GitHub 直链多数网络环境裸连不通），
                // 直链放最后兜底。
                JSONArray mirrors = obj.optJSONArray("mirrors");
                if (mirrors != null) {
                    for (int i = 0; i < mirrors.length(); i++) {
                        String m = mirrors.optString(i, "").trim();
                        if (!m.isEmpty() && !urls.contains(m)) {
                            urls.add(m);
                        }
                    }
                }
                String apk = obj.optString("apkUrl", "").trim();
                if (!apk.isEmpty() && !urls.contains(apk)) {
                    urls.add(apk);
                }
                if (urls.isEmpty()) {
                    error = "清单未配置下载地址";
                }
            }
            final String finalError = error;
            MAIN.post(() -> {
                if (finalError != null) {
                    callback.onError(finalError);
                } else {
                    callback.onResult(urls);
                }
            });
        }, "update-sources").start();
    }
}
