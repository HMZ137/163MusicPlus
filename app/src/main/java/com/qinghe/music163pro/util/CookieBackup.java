package com.qinghe.music163pro.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 登录 Cookie 的导出与读取 —— 用于「重装应用后快速恢复登录」。
 *
 * <p><b>为什么必须放在公共外部存储：</b>Android 上只有 {@code /sdcard} 这类公共外部存储
 * 能在卸载后保留。应用私有目录（{@code /data/data/<包名>}）和
 * {@code getExternalFilesDir()} 都会随卸载一起被清掉 —— 那正是我们要对抗的场景，
 * 所以不能用它们。</p>
 *
 * <p>位置沿用项目已有的约定：与 {@code favorites.json} / {@code favorite-ids.json} 等
 * 放在同一个 {@code /sdcard/163Music/} 下。那样用户在一个地方就能找到全部持久化数据，
 * 不必再记一个新目录。</p>
 *
 * <p><b>安全提醒：</b>Cookie 等同于账号凭证，而这个文件在公共外部存储上是明文的 ——
 * 任何能访问这张 SD 卡的人（USB 连线、文件管理器、别的应用）都能读到。所以文件头里
 * 写了警示，界面上也提示了不要分享。若不需要跨重装保留，用一次后手动删掉该文件即可。</p>
 *
 * <p>文件格式：以 {@code #} 开头的注释行 + 原始 Cookie。读取时会跳过注释行 ——
 * 这样文件本身可读可改（用户能直接看懂这是什么），而且把整份文件粘进 Cookie 输入框
 * 也能用。</p>
 */
public final class CookieBackup {

    /** 与 FavoritesManager / HistoryManager / PlaylistManager 共用的目录名。 */
    private static final String DIR_NAME = "163Music";
    private static final String FILE_NAME = "cookie.txt";
    /** 登录 Cookie 在 SharedPreferences 里的键（与 LoginActivity 保持一致）。 */
    public static final String KEY_COOKIE = "cookie";

    /** 操作结果。区分开「没权限」和「写失败」，界面才能给出有用的提示。 */
    public enum Result {
        /** 成功。 */
        OK,
        /** 缺少外部存储读写权限（Android 6+ 需要运行时授予）。 */
        NO_PERMISSION,
        /** 外部存储当前不可用（未挂载）。 */
        STORAGE_UNAVAILABLE,
        /** 文件不存在 / 内容为空（仅读取时）。 */
        NOT_FOUND,
        /** 读写过程出错。 */
        IO_ERROR,
        /** 传进来的 Cookie 是空的（仅保存时）。 */
        EMPTY_COOKIE,
    }

    /** 读取结果。{@link #status} == {@link Result#OK} 时 {@link #cookie} 才有意义。 */
    public static final class ReadResult {
        public final Result status;
        public final String cookie;

        ReadResult(Result status, String cookie) {
            this.status = status;
            this.cookie = cookie;
        }
    }

    private CookieBackup() {
    }

    /** 备份文件位置。目录不存在时也会返回路径（用于界面提示），不创建目录。 */
    public static File backupFile() {
        File dir = new File(Environment.getExternalStorageDirectory(), DIR_NAME);
        return new File(dir, FILE_NAME);
    }

    /** 备份文件的绝对路径，给界面显示用。 */
    public static String backupPath() {
        return backupFile().getAbsolutePath();
    }

    /** 是否已具备外部存储读写权限。Android 6 以下恒为 true。 */
    public static boolean hasPermission(Context context) {
        if (context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** 外部存储是否已挂载可写。 */
    private static boolean storageReady() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    /**
     * 把 Cookie 写到备份文件（覆盖已有备份）。
     *
     * @param cookie 原始 Cookie 串；空串或纯空白直接返回 {@link Result#EMPTY_COOKIE}
     */
    public static Result save(Context context, String cookie) {
        if (context == null) {
            return Result.IO_ERROR;
        }
        if (!hasPermission(context)) {
            return Result.NO_PERMISSION;
        }
        if (!storageReady()) {
            return Result.STORAGE_UNAVAILABLE;
        }
        String value = cookie == null ? "" : cookie.trim();
        if (value.isEmpty()) {
            return Result.EMPTY_COOKIE;
        }

        File file = backupFile();
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return Result.IO_ERROR;
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(buildContent(value).getBytes("UTF-8"));
            out.flush();
            return Result.OK;
        } catch (Exception e) {
            return Result.IO_ERROR;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 读取备份文件里的 Cookie。文件不存在或内容为空时返回 {@link Result#NOT_FOUND}，
     * 不抛异常。
     */
    public static ReadResult load(Context context) {
        if (context == null) {
            return new ReadResult(Result.IO_ERROR, null);
        }
        if (!hasPermission(context)) {
            return new ReadResult(Result.NO_PERMISSION, null);
        }
        if (!storageReady()) {
            return new ReadResult(Result.STORAGE_UNAVAILABLE, null);
        }
        File file = backupFile();
        if (!file.isFile()) {
            return new ReadResult(Result.NOT_FOUND, null);
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) {
                buffer.write(buf, 0, read);
            }
            String cookie = CookieFileFormat.parse(new String(buffer.toByteArray(), "UTF-8"));
            if (cookie.isEmpty()) {
                return new ReadResult(Result.NOT_FOUND, null);
            }
            return new ReadResult(Result.OK, cookie);
        } catch (Exception e) {
            return new ReadResult(Result.IO_ERROR, null);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 备份文件是否已存在且非空。界面用它决定按钮文案（首次导出 / 覆盖）。 */
    public static boolean exists(Context context) {
        if (context == null || !hasPermission(context)) {
            return false;
        }
        File file = backupFile();
        return file.isFile() && file.length() > 0;
    }

    /** 备份文件的最后修改时间；不存在时返回 0。 */
    public static long lastModified(Context context) {
        if (context == null) {
            return 0L;
        }
        File file = backupFile();
        return file.isFile() ? file.lastModified() : 0L;
    }

    /** 备份文件的修改时间文本，例如 {@code 2026-09-25 21:30}；不存在时返回空串。 */
    public static String lastModifiedText(Context context) {
        long time = lastModified(context);
        if (time <= 0L) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(time));
    }

    /**
     * 文件内容：注释头 + 原始 Cookie。
     *
     * <p>格式本身在 {@link CookieFileFormat} 里（纯 JVM，可独立跑验证）；这里只负责
     * 生成时间戳。</p>
     */
    private static String buildContent(String cookie) {
        return CookieFileFormat.build(cookie, timestamp());
    }

    /** 当前时间文本，例如 {@code 2026-09-25 21:30}。 */
    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date());
    }
}
