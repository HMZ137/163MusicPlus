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
     * P9：流体背景 —— 不跟随封面的动态色团背景（{@link FluidBackgroundDrawable}）。
     * 手表上不依赖任何图片素材，无封面 / 未播放时也能用。
     */
    public static final String MODE_FLUID = "fluid";
    /**
     * 封面优先：有专辑封面时用封面，没有（无封面 / 封面获取失败）时回退到用户
     * 选定的图片（{@link #MODE_CUSTOM} 的同款自定义图）；两者皆无则用默认背景。
     * 用于「想让有封面的歌自动漂亮、没封面的歌也能有个背景」的场景。
     */
    public static final String MODE_COVER_OR_CUSTOM = "cover_or_custom";

    // ==================== P9：模糊强度 ====================

    /** 模糊档位：0=关闭（原样，只加深），1=弱，2=中（默认），3=强。 */
    public static final int BLUR_OFF = 0;
    public static final int BLUR_LOW = 1;
    public static final int BLUR_MID = 2;
    public static final int BLUR_HIGH = 3;
    private static final int[] BLUR_STEPS = {BLUR_OFF, BLUR_LOW, BLUR_MID, BLUR_HIGH};
    private static final String[] BLUR_LABELS = {"关闭", "弱", "中", "强"};
    private static final String KEY_BLUR = "background_blur_level";
    /** 默认档位：需求方口径「参考 Main1.png 做模糊背景」⇒ 默认就是模糊的（中）。 */
    private static final int DEFAULT_BLUR = BLUR_MID;
    /** 模糊前的降采样宽度：先缩到 64px 再模糊，等于「大半径 + 平滑」而且极省 CPU。 */
    private static final int BLUR_SOURCE_WIDTH = 64;

    private static final String PREFS_NAME = "music163_settings";
    private static final String KEY_MODE = "background_mode";
    private static final String FILE_NAME = "custom_background.jpg";
    private static final int DEFAULT_BG_COLOR = 0xFF121212;
    private static final int SCRIM_ALPHA = 150; // 0..255
    /** 本地文件地址前缀。本地音乐的内嵌封面用它（见 LocalMetadata）。 */
    private static final String LOCAL_FILE_PREFIX = "file://";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Album-cover background, kept in memory only.
    /** 封面背景的**显示位图**（已按当前模糊档位模糊 + 加深），直接拿去当背景 / 预览。 */
    private static Bitmap sCoverBg;
    /** 封面背景的**原始位图**（未模糊、未加深）：改模糊档位时用它重算显示位图。 */
    private static Bitmap sCoverRaw;
    private static String sCoverBgUrl;
    /**
     * 封面加载的请求序号。异步解码乱序返回时，用它判断「这次结果是不是已经过期」——
     * 见 {@link #loadCoverBackground}。
     */
    private static int sCoverRequestSeq;

    /** P9：自定义图片背景的缓存（原图 + 显示图），避免每次 applyBackground 都解码 + 重模糊。 */
    private static Bitmap sCustomRaw;
    private static String sCustomRawKey;
    private static Bitmap sCustomBg;
    private static int sCustomBgBlur = -1;

    /** P9：流体背景实例按 View 复用；这里只记最近一个，避免每次重设背景都重新起动画。 */
    private static FluidBackgroundDrawable sFluidDrawable;

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

    // ==================== P9：模糊强度 ====================

    /** 当前模糊档位（{@link #BLUR_OFF} / {@link #BLUR_LOW} / {@link #BLUR_MID} / {@link #BLUR_HIGH}）。 */
    public static int getBlurLevel(Context context) {
        int level = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_BLUR, DEFAULT_BLUR);
        if (level < BLUR_OFF || level > BLUR_HIGH) {
            level = DEFAULT_BLUR;
        }
        return level;
    }

    /** 设置模糊档位；封面 / 自定义图已缓存时立即用新档位重算（回到主界面即生效）。 */
    public static void setBlurLevel(Context context, int level) {
        if (level < BLUR_OFF || level > BLUR_HIGH) {
            level = DEFAULT_BLUR;
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_BLUR, level).apply();
        if (sCoverRaw != null && !sCoverRaw.isRecycled()) {
            sCoverBg = renderBackground(sCoverRaw, getBlurLevel(context));
            LyricsTint.refresh(sCoverBg);
        }
        // 自定义图片按需重算（下一次 applyBackground 时重新解码 + 模糊）
        sCustomBg = null;
        sCustomBgBlur = -1;
    }

    /** 循环切到下一档，返回切换后的档位。 */
    public static int cycleBlurLevel(Context context) {
        int current = getBlurLevel(context);
        int next = BLUR_STEPS[0];
        for (int i = 0; i < BLUR_STEPS.length; i++) {
            if (BLUR_STEPS[i] == current) {
                next = BLUR_STEPS[(i + 1) % BLUR_STEPS.length];
                break;
            }
        }
        setBlurLevel(context, next);
        return next;
    }

    /** 档位文案（关闭 / 弱 / 中 / 强）。 */
    public static String getBlurLabel(Context context) {
        return getBlurLabel(getBlurLevel(context));
    }

    public static String getBlurLabel(int level) {
        if (level < BLUR_OFF || level > BLUR_HIGH) {
            level = DEFAULT_BLUR;
        }
        return BLUR_LABELS[level];
    }

    /** 模糊档位对应的色团半径（在降采样图上；1 档 ≈ 全屏 8px）。 */
    private static int blurRadiusFor(int level) {
        switch (level) {
            case BLUR_LOW:
                return 1;
            case BLUR_HIGH:
                return 4;
            case BLUR_MID:
                return 2;
            default:
                return 0;
        }
    }

    /**
     * 把原图渲染成「背景显示位图」：按当前模糊档位模糊 + 加深遮罩。
     *
     * <p>做法是「先降采样到 64px 宽 → 3 遍盒式模糊 → 加深」。降采样后再模糊等价于全屏大半径模糊，
     * 但计算量只有原图的 1/30；放大由 {@link CenterCropDrawable} 的双线性过滤完成，
     * 不会出现马赛克边。模糊强度只影响这一步，不改任何调用方。</p>
     *
     * @param raw 原始位图（**不会被 recycle**，改档位重算时还要用）
     */
    private static Bitmap renderBackground(Bitmap raw, int blurLevel) {
        if (raw == null || raw.isRecycled()) {
            return null;
        }
        int radius = blurRadiusFor(blurLevel);
        int w = Math.max(48, raw.getWidth());
        int h = Math.max(48, raw.getHeight());
        int smallW = Math.max(24, Math.min(BLUR_SOURCE_WIDTH, w));
        int smallH = Math.max(24, Math.round((float) h * smallW / w));
        Bitmap small = Bitmap.createScaledBitmap(raw, smallW, smallH, true);
        if (radius > 0) {
            small = boxBlur(small, radius);
        }
        Bitmap out = Bitmap.createBitmap(small.getWidth(), small.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawBitmap(small, 0, 0, null);
        Paint scrim = new Paint();
        scrim.setColor(Color.argb(SCRIM_ALPHA, 0, 0, 0));
        canvas.drawRect(0, 0, out.getWidth(), out.getHeight(), scrim);
        if (small != raw) {
            small.recycle();
        }
        return out;
    }

    /** 3 遍盒式模糊（每遍「水平 → 垂直」，交替写两块缓冲；对 64px 图是微秒级）。 */
    private static Bitmap boxBlur(Bitmap src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 1 || h <= 1 || radius <= 0) {
            return src;
        }
        int[] cur = new int[w * h];
        int[] tmp = new int[w * h];
        src.getPixels(cur, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            blurHorizontal(cur, tmp, w, h, radius);   // cur -> tmp
            blurVertical(tmp, cur, w, h, radius);     // tmp -> cur
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(cur, 0, w, 0, 0, w, h);
        return result;
    }

    private static void blurHorizontal(int[] in, int[] out, int w, int h, int radius) {
        int div = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = in[row + clamp(i, w)];
                sa += (p >>> 24) & 0xFF;
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[row + clamp(x - radius, w)];
                int pIn = in[row + clamp(x + radius + 1, w)];
                sa += ((pIn >>> 24) & 0xFF) - ((pOut >>> 24) & 0xFF);
                sr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                sg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                sb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static void blurVertical(int[] in, int[] out, int w, int h, int radius) {
        int div = radius * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = in[clamp(i, h) * w + x];
                sa += (p >>> 24) & 0xFF;
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[clamp(y - radius, h) * w + x];
                int pIn = in[clamp(y + radius + 1, h) * w + x];
                sa += ((pIn >>> 24) & 0xFF) - ((pOut >>> 24) & 0xFF);
                sr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                sg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                sb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
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
        sCoverRaw = null;
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
            // P9：在后台线程按当前模糊档位渲染显示位图（原图保留给「换档位重算」用）
            final Bitmap rendered = raw != null
                    ? renderBackground(raw, getBlurLevel(appContext)) : null;
            MAIN.post(() -> {
                if (requestSeq != sCoverRequestSeq) {
                    // 已被更新的请求（或一次 clear）作废，丢弃结果
                    return;
                }
                if (rendered != null) {
                    sCoverRaw = raw;
                    sCoverBg = rendered;
                    sCoverBgUrl = targetUrl;
                } else {
                    // 直接置空，不要调 clearCoverBackground() —— 那会把序号再推进一格
                    sCoverRaw = null;
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

    // ==================== Apply ====================

    public static void applyBackground(Context context, View root) {
        if (root == null) return;
        String mode = getMode(context);
        if (MODE_FLUID.equals(mode)) {
            // P9：流体背景 —— 动态色团，不需要任何图片；歌词用默认白色（深底）。
            if (!(root.getBackground() instanceof FluidBackgroundDrawable)) {
                sFluidDrawable = new FluidBackgroundDrawable();
                root.setBackground(sFluidDrawable);
            }
            LyricsTint.useDefault();
            return;
        }
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
     *
     * <p>P9：加了「原图 / 显示图」两级缓存 —— 以前每次 applyBackground 都重新解码 + 加深，
     * 改成模糊之后这一步更重，而 applyBackground 会随换歌 / 回前台频繁调用。</p>
     */
    private static void applyCustomOrDefault(Context context, View root) {
        if (!hasCustomBackground(context)) {
            root.setBackgroundColor(DEFAULT_BG_COLOR);
            LyricsTint.useDefault();
            return;
        }
        try {
            File file = getBackgroundFile(context);
            String key = file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
            int blur = getBlurLevel(context);
            if (sCustomBg != null && key.equals(sCustomRawKey) && blur == sCustomBgBlur) {
                root.setBackground(new CenterCropDrawable(sCustomBg));
                LyricsTint.refresh(sCustomBg);
                return;
            }
            int reqW = context.getResources().getDisplayMetrics().widthPixels;
            int reqH = context.getResources().getDisplayMetrics().heightPixels;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            opts.inSampleSize = calculateSampleSize(opts, reqW, reqH);
            opts.inJustDecodeBounds = false;
            Bitmap src = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (src == null) {
                root.setBackgroundColor(DEFAULT_BG_COLOR);
                LyricsTint.useDefault();
                return;
            }
            Bitmap rendered = renderBackground(src, blur);
            sCustomRaw = src;
            sCustomRawKey = key;
            sCustomBg = rendered;
            sCustomBgBlur = blur;
            LyricsTint.refresh(rendered);   // F2：自定义背景（已模糊 + 加深）⇒ 按主色决定歌词颜色
            root.setBackground(new CenterCropDrawable(rendered));
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
