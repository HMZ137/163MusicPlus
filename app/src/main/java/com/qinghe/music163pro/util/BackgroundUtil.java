package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Background support for the main player and lyrics screens.
 *
 * Modes:
 * - MODE_COVER: background follows the playing (or current) song's album cover.
 *   The cover is kept only in memory (never saved to disk) and is always
 *   (re)loaded when the screen is shown, so it never stays blank.
 * - MODE_COVER_OR_CUSTOM: "cover-first" — uses the album cover when one is
 *   available, otherwise falls back to the user-picked custom image, and only
 *   then to the default background.
 * - MODE_CUSTOM: a user-picked static image saved to internal storage.
 * - MODE_DEFAULT: plain dark background.
 *
 * 显示方式统一为「填充」：等比放大到盖满整块区域、超出的部分居中裁掉
 * （{@link CenterCropDrawable}）。早期版本直接用 BitmapDrawable，其默认 gravity 为
 * Gravity.FILL，会把图片按 view 宽高非等比拉伸，在手表这种窄屏上变形很明显。
 */
public final class BackgroundUtil {

    public static final String MODE_COVER = "cover";
    public static final String MODE_CUSTOM = "custom";
    public static final String MODE_DEFAULT = "default";
    /**
     * 封面优先：有专辑封面时用封面，没有（无封面 / 封面获取失败）时回退到用户
     * 选定的图片（{@link #MODE_CUSTOM} 的同款自定义图）；两者皆无则用默认背景。
     * 用于「想让有封面的歌自动漂亮、没封面的歌也能有个背景」的场景。
     */
    public static final String MODE_COVER_OR_CUSTOM = "cover_or_custom";

    private static final String PREFS_NAME = "music163_settings";
    private static final String KEY_MODE = "background_mode";
    private static final String FILE_NAME = "custom_background.jpg";
    private static final int DEFAULT_BG_COLOR = 0xFF121212;
    private static final int SCRIM_ALPHA = 150; // 0..255
    /** 本地文件地址前缀。本地音乐的内嵌封面用它（见 LocalMetadata）。 */
    private static final String LOCAL_FILE_PREFIX = "file://";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Album-cover background, kept in memory only.
    private static Bitmap sCoverBg;
    private static String sCoverBgUrl;
    /**
     * 封面加载的请求序号。异步解码乱序返回时，用它判断「这次结果是不是已经过期」——
     * 见 {@link #loadCoverBackground}。
     */
    private static int sCoverRequestSeq;

    private BackgroundUtil() {
    }

    public static String getMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_MODE, MODE_COVER);
    }

    public static void setMode(Context context, String mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode).apply();
    }

    /**
     * 该模式是否需要加载（并跟随）专辑封面。
     * {@link #MODE_COVER} 与 {@link #MODE_COVER_OR_CUSTOM} 都需要封面在位，
     * 否则前者退化成默认背景、后者退化成选定图片。
     */
    public static boolean isCoverMode(String mode) {
        return MODE_COVER.equals(mode) || MODE_COVER_OR_CUSTOM.equals(mode);
    }

    public static File getBackgroundFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static boolean hasCustomBackground(Context context) {
        return getBackgroundFile(context).exists();
    }

    public static void saveBackground(Context context, Uri uri) {
        if (context == null || uri == null) return;
        try {
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) return;
            FileOutputStream fos = new FileOutputStream(getBackgroundFile(context));
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            fos.close();
            is.close();
        } catch (Exception ignored) {
        }
    }

    public static void clearBackground(Context context) {
        getBackgroundFile(context).delete();
    }

    // ==================== Album cover background (in memory) ====================

    public static Bitmap getCoverBackground() {
        return sCoverBg;
    }

    public static boolean hasCoverBackground(String url) {
        return url != null && !url.isEmpty() && url.equals(sCoverBgUrl) && sCoverBg != null;
    }

    /**
     * 清空封面背景，并让**在途的加载作废**。
     *
     * <p>只置空字段是不够的：清空意味着「当前不该显示任何封面」，此时若有一个更早发起的
     * 加载晚点返回，会把封面又贴回来。递增请求序号就能丢弃它。</p>
     */
    public static void clearCoverBackground() {
        sCoverRequestSeq++;
        sCoverBg = null;
        sCoverBgUrl = null;
    }

    /**
     * Download the cover and keep a darkened copy in memory (no disk write).
     * onReady runs on the main thread whether it succeeded or failed.
     *
     * <p><b>为什么要带请求序号：</b>每次调用都新起一个线程解码，而解码耗时取决于图片大小。
     * 快速连续切歌时，先发起的（大图，慢）可能晚于后发起的（小图，快）返回 ——
     * 若成功后无条件写缓存，就会把新封面顶掉，界面显示成<b>上一首的封面</b>，
     * 而且要等到下一次 {@code updateCoverBackground()} 才会被纠正。</p>
     *
     * <p>所以只认「最后一次请求」的结果。被作废的那次不写缓存、也不回调 ——
     * 回调的语义是「缓存已更新，可以重画背景了」，而它并没有更新什么；
     * 那次更新的请求会自己回调。</p>
     */
    public static void loadCoverBackground(final Context context, final String url,
                                           final Runnable onReady) {
        if (url == null || url.isEmpty()) {
            clearCoverBackground();
            if (onReady != null) MAIN.post(onReady);
            return;
        }
        if (hasCoverBackground(url)) {
            if (onReady != null) MAIN.post(onReady);
            return;
        }
        final String targetUrl = url;
        final Context appContext = context.getApplicationContext();
        final int requestSeq = ++sCoverRequestSeq;
        new Thread(() -> {
            Bitmap raw = downloadBitmap(appContext, targetUrl);
            final Bitmap dimmed = raw != null ? dimBitmap(raw, appContext) : null;
            MAIN.post(() -> {
                if (requestSeq != sCoverRequestSeq) {
                    // 已被更新的请求（或一次 clear）作废，丢弃结果
                    return;
                }
                if (dimmed != null) {
                    sCoverBg = dimmed;
                    sCoverBgUrl = targetUrl;
                } else {
                    // 直接置空，不要调 clearCoverBackground() —— 那会把序号再推进一格
                    sCoverBg = null;
                    sCoverBgUrl = null;
                }
                if (onReady != null) onReady.run();
            });
        }).start();
    }

    /**
     * 取封面的位图。支持 {@code http(s)://} 和 {@code file://} 两种地址。
     *
     * <p><b>为什么必须先判 file://：</b>本地音乐的内嵌封面是 {@code file://} 地址
     * （见 {@link LocalMetadata}），而 {@code file://} 的
     * {@code openConnection()} 返回的是 {@code FileURLConnection}，
     * 强转成 {@link HttpURLConnection} 会抛 {@code ClassCastException} ——
     * 它会被下面那个 {@code catch (Exception ignored)} 吞掉、方法返回 null，
     * 表现为「播本地歌时封面背景永远是默认深色」，而且一点报错都看不到。</p>
     *
     * <p>列表缩略图走的是 {@link NetworkImageLoader}，它早就分了两条路，
     * 所以只有播放页背景受影响 —— 这也是为什么这个 bug 一直没被察觉。</p>
     */
    private static Bitmap downloadBitmap(Context context, String urlStr) {
        String localPath = localPathOf(urlStr);
        if (localPath != null) {
            return decodeLocalImage(context, localPath);
        }
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.connect();
            is = conn.getInputStream();
            byte[] data = readAll(is);
            if (data.length == 0) return null;
            int reqW = context.getResources().getDisplayMetrics().widthPixels;
            int reqH = context.getResources().getDisplayMetrics().heightPixels;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calculateSampleSize(bounds, reqW, reqH);
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                if (is != null) is.close();
            } catch (Exception ignored) {
            }
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 把 {@code file://} 地址换成可直接读的文件路径；不是本地地址时返回 {@code null}。
     *
     * <p>刻意写成不碰任何 Android 类型的静态方法 —— 这样它能被独立跑起来验证
     * （见 {@code .workbuddy/background-util-probe/}）。这里最容易错的是前缀长度：
     * Linux 风格绝对路径拼出来是 {@code file:///data/...}（三个斜杠），
     * 去掉 {@code file://} 正好剩 {@code /data/...}。
     *
     * @param url 形如 {@code file:///data/data/<包名>/cache/local_cover/<md5>.img}
     */
    static String localPathOf(String url) {
        if (url == null || !url.startsWith(LOCAL_FILE_PREFIX)) {
            return null;
        }
        String path = url.substring(LOCAL_FILE_PREFIX.length());
        return path.isEmpty() ? null : path;
    }

    /**
     * 解码本地图片文件，按屏幕尺寸采样。
     *
     * <p>采样不能省：内嵌封面是 {@code MediaMetadataRetriever} 抽出来的原始尺寸图，
     * 常见几百 KB 到几 MB，直接整张进内存做背景没必要。</p>
     */
    private static Bitmap decodeLocalImage(Context context, String path) {
        try {
            File file = new File(path);
            if (!file.isFile()) {
                return null;
            }
            int reqW = context.getResources().getDisplayMetrics().widthPixels;
            int reqH = context.getResources().getDisplayMetrics().heightPixels;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calculateSampleSize(bounds, reqW, reqH);
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static Bitmap dimBitmap(Bitmap src, Context context) {
        if (src == null) return null;
        Bitmap dimmed = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(dimmed);
        canvas.drawBitmap(src, 0, 0, null);
        Paint scrim = new Paint();
        scrim.setColor(Color.argb(SCRIM_ALPHA, 0, 0, 0));
        canvas.drawRect(0, 0, dimmed.getWidth(), dimmed.getHeight(), scrim);
        src.recycle();
        return dimmed;
    }

    // ==================== Apply ====================

    public static void applyBackground(Context context, View root) {
        if (root == null) return;
        String mode = getMode(context);
        if (MODE_DEFAULT.equals(mode)) {
            root.setBackgroundColor(DEFAULT_BG_COLOR);
            LyricsTint.useDefault();
            return;
        }
        if (MODE_COVER.equals(mode)) {
            if (sCoverBg != null) {
                root.setBackground(new CenterCropDrawable(sCoverBg));
                LyricsTint.refresh(sCoverBg);   // F2：封面背景 ⇒ 按主色决定歌词颜色
            } else {
                root.setBackgroundColor(DEFAULT_BG_COLOR);
                LyricsTint.useDefault();
            LyricsTint.useDefault();
            }
            return;
        }
        if (MODE_COVER_OR_CUSTOM.equals(mode)) {
            // 有封面用封面；封面还没到位 / 获取失败时回退到自定义图，再不行用默认。
            if (sCoverBg != null) {
                root.setBackground(new CenterCropDrawable(sCoverBg));
                LyricsTint.refresh(sCoverBg);   // F2：封面背景 ⇒ 按主色决定歌词颜色
            } else {
                applyCustomOrDefault(context, root);
            }
            return;
        }
        // MODE_CUSTOM
        applyCustomOrDefault(context, root);
    }

    /**
     * 解码用户选定的自定义图并铺满显示；无自定义图 / 解码失败时用默认深色。
     * 被 {@link #MODE_CUSTOM} 与 {@link #MODE_COVER_OR_CUSTOM}（封面缺失时）共用。
     */
    private static void applyCustomOrDefault(Context context, View root) {
        if (!hasCustomBackground(context)) {
            root.setBackgroundColor(DEFAULT_BG_COLOR);
            LyricsTint.useDefault();
            return;
        }
        try {
            int reqW = context.getResources().getDisplayMetrics().widthPixels;
            int reqH = context.getResources().getDisplayMetrics().heightPixels;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(getBackgroundFile(context).getAbsolutePath(), opts);
            opts.inSampleSize = calculateSampleSize(opts, reqW, reqH);
            opts.inJustDecodeBounds = false;
            Bitmap src = BitmapFactory.decodeFile(getBackgroundFile(context).getAbsolutePath(), opts);
            if (src == null) {
                root.setBackgroundColor(DEFAULT_BG_COLOR);
                LyricsTint.useDefault();
            LyricsTint.useDefault();
                return;
            }
            Bitmap dimmed = dimBitmap(src, context);
            LyricsTint.refresh(dimmed);   // F2：自定义背景（已加深）⇒ 按主色决定歌词颜色
            root.setBackground(new CenterCropDrawable(dimmed));
        } catch (Exception ignored) {
            root.setBackgroundColor(DEFAULT_BG_COLOR);
            LyricsTint.useDefault();
        }
    }

    private static int calculateSampleSize(BitmapFactory.Options opts, int reqW, int reqH) {
        int sampleSize = 1;
        while (opts.outWidth / sampleSize > reqW * 2 || opts.outHeight / sampleSize > reqH * 2) {
            sampleSize *= 2;
        }
        return Math.max(1, sampleSize);
    }
}
