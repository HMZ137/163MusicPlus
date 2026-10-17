package com.qinghe.music163pro.util;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * F4：把下载目录里的封面（cover.jpg）与歌词（lyrics.lrc）内嵌进音频文件标签。
 *
 * <p>按需求方 2026-10-17 确认的方案实现：</p>
 * <ul>
 *   <li>歌词 + 封面**都写**；</li>
 *   <li>编码：<b>ID3v2.4 + UTF-8</b>（MP3）；FLAC 写 Vorbis Comment 的 LYRICS/UNSYNCEDLYRICS + PICTURE；</li>
 *   <li>容器：MP3 与 FLAC 都支持；</li>
 *   <li>安全：写前把原文件复制为 {@code xxx.bak}（已存在则复用，不覆盖旧备份），
 *       并在 {@code info.json} 里写 {@code embedded_tag=true} 作为「已内嵌」标记；</li>
 *   <li>已标记的目录默认跳过，{@code force=true} 时强制重写。</li>
 * </ul>
 *
 * <p>目录约定（见 DownloadManager）：{@code 163Music/Download/<歌名 - 歌手>/} 下的音频文件 +
 * {@code lyrics.lrc} + {@code cover.jpg} + {@code info.json}。</p>
 */
public final class AudioTagEmbedder {

    private static final String TAG = "AudioTagEmbedder";
    /** info.json 里的「已内嵌」标记键。 */
    public static final String MARKER_KEY = "embedded_tag";
    private static final String COVER_FILE = "cover.jpg";
    private static final String LYRICS_FILE = "lyrics.lrc";
    private static final String INFO_FILE = "info.json";
    private static final String[] AUDIO_EXTS = {".mp3", ".flac"};

    private AudioTagEmbedder() {
    }

    /** 汇总结果。 */
    public static final class Result {
        public int total;
        public int embedded;
        public int skipped;
        public int failed;

        public String summary() {
            return "共 " + total + " 首：内嵌 " + embedded + "，跳过 " + skipped + "，失败 " + failed;
        }
    }

    /** 进度回调（可选）：每处理完一个歌曲目录回调一次，供设置页回写副标题。 */
    public interface Listener {
        /**
         * @param done   已处理目录数
         * @param total  目录总数
         * @param folder 刚处理完的目录名
         */
        void onProgress(int done, int total, String folder);
    }

    /** 扫描下载根目录下的所有歌曲目录并内嵌。 */
    public static Result embedAll(File downloadRoot, boolean force) {
        return embedAll(downloadRoot, force, null);
    }

    /** 扫描下载根目录下的所有歌曲目录并内嵌（带进度回调）。 */
    public static Result embedAll(File downloadRoot, boolean force, Listener listener) {
        Result r = new Result();
        if (downloadRoot == null || !downloadRoot.isDirectory()) {
            return r;
        }
        File[] dirs = downloadRoot.listFiles(File::isDirectory);
        if (dirs == null) {
            return r;
        }
        int done = 0;
        for (File dir : dirs) {
            r.total++;
            try {
                int outcome = embedFolder(dir, force);
                if (outcome == 1) {
                    r.embedded++;
                } else if (outcome == 0) {
                    r.skipped++;
                } else {
                    r.failed++;
                }
            } catch (Exception e) {
                r.failed++;
                Log.w(TAG, "内嵌失败: " + dir.getName() + " -> " + e);
            }
            done++;
            if (listener != null) {
                listener.onProgress(done, dirs.length, dir.getName());
            }
        }
        return r;
    }

    /**
     * 处理单个歌曲目录。
     *
     * <p><b>真实下载目录结构</b>（2026-10-17 装机核对）：音频**不在**歌单目录根下，而在
     * {@code <歌名 - 歌手>/songs/<音质>/<文件>.mp3|flac}；{@code lyrics.lrc} / {@code cover.jpg} /
     * {@code info.json} 在歌单目录根下。所以这里递归找音频（最多 4 层），一个目录里的同曲多音质副本
     * 会**逐一内嵌**。</p>
     *
     * @return 1=已内嵌；0=跳过（无素材 / 已标记且未强制）；-1=失败
     */
    public static int embedFolder(File dir, boolean force) throws IOException {
        List<File> audios = findAudios(dir);
        if (audios.isEmpty()) {
            return 0;
        }
        File info = new File(dir, INFO_FILE);
        if (!force && isMarked(info)) {
            return 0;
        }
        File coverFile = new File(dir, COVER_FILE);
        File lyricsFile = new File(dir, LYRICS_FILE);
        if (!coverFile.isFile() && !lyricsFile.isFile()) {
            return 0;
        }
        byte[] cover = coverFile.isFile() ? readAll(coverFile) : null;
        String lyrics = lyricsFile.isFile()
                ? new String(readAll(lyricsFile), StandardCharsets.UTF_8) : null;
        if ((cover == null || cover.length == 0) && (lyrics == null || lyrics.isEmpty())) {
            return 0;
        }

        int embedded = 0;
        for (File audio : audios) {
            try {
                // 2026-10-17 修：原来 readAll 整文件进内存（64MB FLAC 直接 OOM 崩进程），
                // 改成流式写临时文件；同时保留原标签里的标题/歌手等帧。
                if (embedOneStreaming(audio, cover, lyrics) == 1) {
                    embedded++;
                }
            } catch (Throwable t) {
                // 单文件失败不能把整批内嵌打崩
                Log.w(TAG, "内嵌失败（跳过）: " + audio.getAbsolutePath() + " — " + t);
            }
        }
        if (embedded == 0) {
            return -1;
        }
        mark(info);
        return 1;
    }

    /**
     * F4b（2026-10-17 深夜，需求方「局域网传歌上传歌词后按文件名匹配并合并」）：
     * 把一段歌词**内嵌进单个音频文件**（只写歌词、不动封面）。
     *
     * <p>与 {@link #embedFolder(File, boolean)} 的区别：不依赖 {@code lyrics.lrc} 目录结构，
     * 直接把调用方给的歌词文本写进该音频（LAN 传歌上传 .lrc 后与同名音频合并用）。
     * 原文件首次写入前会备份成 {@code .bak}。</p>
     *
     * @return 1=成功；0=跳过（不支持该格式 / 歌词为空）；-1=失败
     */
    public static int embedLyricsInto(File audio, String lyrics) {
        if (audio == null || !audio.isFile() || lyrics == null || lyrics.trim().isEmpty()) {
            return 0;
        }
        String name = audio.getName().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".mp3") && !name.endsWith(".flac")) {
            return 0;   // 其它格式暂不支持内嵌歌词标签
        }
        try {
            return embedOneStreaming(audio, null, lyrics);
        } catch (Exception e) {
            return -1;
        }
    }

    // ── 流式内嵌（2026-10-17 修 OOM） ────────────────────────────────────────

    /**
     * 把封面/歌词写进单个音频文件（流式，内存占用与文件大小无关）。
     *
     * <p>步骤：写临时文件 → 备份原文件（流式）→ 删除原文件 → 临时文件改名占位。</p>
     *
     * @return 1=成功；0=跳过（格式不支持/没有素材）；-1=失败
     */
    private static int embedOneStreaming(File audio, byte[] cover, String lyrics) throws IOException {
        if (audio == null || !audio.isFile()) {
            return 0;
        }
        if ((cover == null || cover.length == 0) && (lyrics == null || lyrics.isEmpty())) {
            return 0;
        }
        String lower = audio.getName().toLowerCase(java.util.Locale.ROOT);
        boolean isMp3 = lower.endsWith(".mp3");
        boolean isFlac = lower.endsWith(".flac");
        if (!isMp3 && !isFlac) {
            return 0;
        }
        File tmp = new File(audio.getParentFile(), audio.getName() + ".embedtmp");
        boolean wrote = false;
        try {
            if (isMp3) {
                wrote = writeMp3Streaming(audio, tmp, cover, lyrics);
            } else {
                wrote = writeFlacStreaming(audio, tmp, cover, lyrics);
            }
            if (!wrote) {
                return -1;
            }
            backupOnce(audio);
            if (!audio.delete()) {
                Log.w(TAG, "删除原文件失败: " + audio.getAbsolutePath());
                return -1;
            }
            if (!tmp.renameTo(audio)) {
                Log.w(TAG, "临时文件改名失败: " + tmp.getAbsolutePath());
                return -1;
            }
            return 1;
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    // ── MP3：保留原 ID3 帧，替换 USLT/APIC，音频数据流式拷贝 ────────────────

    private static boolean writeMp3Streaming(File src, File dst, byte[] cover, String lyrics)
            throws IOException {
        byte[] head = new byte[10];
        long fileLen = src.length();
        long audioStart = 0;
        byte[] oldTag = null;
        try (RandomAccessFile in = new RandomAccessFile(src, "r")) {
            if (fileLen < 10) {
                return false;
            }
            in.readFully(head);
            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                int size = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14)
                        | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F);
                audioStart = 10L + size;
                if (audioStart >= fileLen) {
                    return false;
                }
                // 旧标签最多读 8MB（保留标题/歌手等帧；超大封面帧会被丢弃但不影响音频）
                if (size <= 8 * 1024 * 1024) {
                    oldTag = new byte[(int) size + 10];
                    in.seek(0);
                    in.readFully(oldTag);
                }
            }
            byte[] newTag = rebuildId3v24(oldTag, cover, lyrics);
            try (FileOutputStream out = new FileOutputStream(dst)) {
                out.write(newTag, 0, newTag.length);
                copyRange(in, audioStart, fileLen - audioStart, out);
                out.flush();
            }
        }
        return true;
    }

    /** 重建 ID3v2.4：保留旧标签里除 USLT/APIC 外的帧，再写新的歌词/封面帧。 */
    private static byte[] rebuildId3v24(byte[] oldTag, byte[] cover, String lyrics) {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        if (oldTag != null && oldTag.length >= 10
                && oldTag[0] == 'I' && oldTag[1] == 'D' && oldTag[2] == '3') {
            int ver = oldTag[3] & 0xFF;
            if (ver == 3 || ver == 4) {
                int flags = oldTag[5] & 0xFF;
                int off = 10;
                if ((flags & 0x40) != 0) {   // extended header：跳过
                    if (oldTag.length >= 14) {
                        int eh = ver == 4
                                ? (((oldTag[10] & 0x7F) << 21) | ((oldTag[11] & 0x7F) << 14)
                                   | ((oldTag[12] & 0x7F) << 7) | (oldTag[13] & 0x7F))
                                : (((oldTag[10] & 0xFF) << 24) | ((oldTag[11] & 0xFF) << 16)
                                   | ((oldTag[12] & 0xFF) << 8) | (oldTag[13] & 0xFF));
                        off += (ver == 4 ? 4 : 4) + Math.max(0, eh);
                    }
                }
                while (off + 10 <= oldTag.length) {
                    boolean empty = true;
                    for (int i = 0; i < 4; i++) {
                        if (oldTag[off + i] != 0) {
                            empty = false;
                            break;
                        }
                    }
                    if (empty) {
                        break;   // 进入 padding
                    }
                    String id = new String(oldTag, off, 4, StandardCharsets.ISO_8859_1);
                    int size;
                    if (ver == 4) {
                        size = ((oldTag[off + 4] & 0x7F) << 21) | ((oldTag[off + 5] & 0x7F) << 14)
                                | ((oldTag[off + 6] & 0x7F) << 7) | (oldTag[off + 7] & 0x7F);
                    } else {
                        size = ((oldTag[off + 4] & 0xFF) << 24) | ((oldTag[off + 5] & 0xFF) << 16)
                                | ((oldTag[off + 6] & 0xFF) << 8) | (oldTag[off + 7] & 0xFF);
                    }
                    if (size < 0 || off + 10 + size > oldTag.length) {
                        break;
                    }
                    if (!"USLT".equals(id) && !"APIC".equals(id)) {
                        // 统一写成 v2.4 帧头（size 用 syncsafe），避免 v2.3→v2.4 尺寸解释错位
                        byte[] data = new byte[size];
                        System.arraycopy(oldTag, off + 10, data, 0, size);
                        writeFrame(frames, id, data, true);
                    }
                    off += 10 + size;
                }
            }
        }
        // 新歌词/封面帧（沿用原有 writeFrame）
        if (lyrics != null && !lyrics.isEmpty()) {
            ByteArrayOutputStream d = new ByteArrayOutputStream();
            d.write(0x03);
            d.write('e'); d.write('n'); d.write('g');
            d.write(0x00);
            byte[] text = lyrics.getBytes(StandardCharsets.UTF_8);
            d.write(text, 0, text.length);
            writeFrame(frames, "USLT", d.toByteArray());
        }
        if (cover != null && cover.length > 0) {
            ByteArrayOutputStream d = new ByteArrayOutputStream();
            d.write(0x03);
            byte[] mime = "image/jpeg".getBytes(StandardCharsets.ISO_8859_1);
            d.write(mime, 0, mime.length);
            d.write(0x00);
            d.write(0x03);
            d.write(0x00);
            d.write(cover, 0, cover.length);
            writeFrame(frames, "APIC", d.toByteArray());
        }
        byte[] body = frames.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('I'); out.write('D'); out.write('3');
        out.write(0x04); out.write(0x00); out.write(0x00);
        int size = body.length;
        out.write((size >> 21) & 0x7F);
        out.write((size >> 14) & 0x7F);
        out.write((size >> 7) & 0x7F);
        out.write(size & 0x7F);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    /** 只写 frame 头 + 数据（跨版本统一 v2.4 头）。 */
    private static void writeFrame(ByteArrayOutputStream out, String id, byte[] data, boolean v24) {
        byte[] idBytes = id.getBytes(StandardCharsets.ISO_8859_1);
        out.write(idBytes, 0, Math.min(4, idBytes.length));
        int size = data.length;
        out.write((size >> 21) & 0x7F);
        out.write((size >> 14) & 0x7F);
        out.write((size >> 7) & 0x7F);
        out.write(size & 0x7F);
        out.write(0x00);
        out.write(0x00);
        out.write(data, 0, data.length);
    }

    // ── FLAC：保留其它元数据块，合并 VORBIS_COMMENT，音频帧流式拷贝 ──────────

    private static boolean writeFlacStreaming(File src, File dst, byte[] cover, String lyrics)
            throws IOException {
        long fileLen = src.length();
        try (RandomAccessFile in = new RandomAccessFile(src, "r");
             RandomAccessFile out = new RandomAccessFile(dst, "rw")) {
            if (fileLen < 8) {
                return false;
            }
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') {
                return false;
            }
            out.write(magic);
            boolean last = false;
            boolean firstBlock = true;
            byte[] oldComment = null;
            long lastFlagPos = -1;
            while (!last) {
                byte[] bh = new byte[4];
                in.readFully(bh);
                last = (bh[0] & 0x80) != 0;
                int type = bh[0] & 0x7F;
                int len = ((bh[1] & 0xFF) << 16) | ((bh[2] & 0xFF) << 8) | (bh[3] & 0xFF);
                if (firstBlock && type != 0) {
                    return false;   // 首块必须是 STREAMINFO
                }
                firstBlock = false;
                boolean keepOldComment = (lyrics == null || lyrics.isEmpty());
                boolean keepOldPicture = (cover == null || cover.length == 0);
                if (type == 4 && !keepOldComment) {   // VORBIS_COMMENT：读出来稍后合并（块通常几 KB）
                    if (len <= 8 * 1024 * 1024) {
                        byte[] data = new byte[len];
                        in.readFully(data);
                        if (oldComment == null) {
                            oldComment = data;
                        }
                    } else {
                        in.seek(in.getFilePointer() + len);
                    }
                } else if (type == 6 && !keepOldPicture) {   // 旧 PICTURE：丢弃，稍后重建
                    in.seek(in.getFilePointer() + len);
                } else {
                    lastFlagPos = out.getFilePointer();
                    out.write(0x00 | type);
                    out.write((len >> 16) & 0xFF);
                    out.write((len >> 8) & 0xFF);
                    out.write(len & 0xFF);
                    copyRange(in, in.getFilePointer(), len, out);
                }
            }
            // 追加 VORBIS_COMMENT（歌词）与 PICTURE（封面）
            if (lyrics != null && !lyrics.isEmpty()) {
                byte[] data = mergeVorbisComment(oldComment, lyrics);
                lastFlagPos = out.getFilePointer();
                out.write(0x04);
                out.write((data.length >> 16) & 0xFF);
                out.write((data.length >> 8) & 0xFF);
                out.write(data.length & 0xFF);
                out.write(data);
            }
            if (cover != null && cover.length > 0) {
                byte[] data = buildFlacPicture(cover);
                lastFlagPos = out.getFilePointer();
                out.write(0x06);
                out.write((data.length >> 16) & 0xFF);
                out.write((data.length >> 8) & 0xFF);
                out.write(data.length & 0xFF);
                out.write(data);
            }
            // 音频帧原样接在后面
            long pos = in.getFilePointer();
            copyRange(in, pos, fileLen - pos, out);
            // 最后把最后一块标记成 last
            if (lastFlagPos >= 0) {
                out.seek(lastFlagPos);
                out.write(0x80);
            }
            return true;
        }
    }

    /** 合并 VORBIS_COMMENT：保留原有 tag 字段（标题/歌手等），替换 LYRICS/UNSYNCEDLYRICS。 */
    private static byte[] mergeVorbisComment(byte[] oldComment, String lyrics) {
        List<byte[]> entries = new ArrayList<>();
        if (oldComment != null && oldComment.length >= 8) {
            try {
                int vendorLen = readLe32(oldComment, 0);
                int p = 4 + vendorLen;
                if (vendorLen >= 0 && p + 4 <= oldComment.length) {
                    int count = readLe32(oldComment, p);
                    p += 4;
                    for (int i = 0; i < count && p + 4 <= oldComment.length; i++) {
                        int len = readLe32(oldComment, p);
                        p += 4;
                        if (len < 0 || p + len > oldComment.length) {
                            break;
                        }
                        String s = new String(oldComment, p, len, StandardCharsets.UTF_8);
                        if (!s.startsWith("LYRICS=") && !s.startsWith("UNSYNCEDLYRICS=")) {
                            byte[] e = new byte[len];
                            System.arraycopy(oldComment, p, e, 0, len);
                            entries.add(e);
                        }
                        p += len;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String l = lyrics.replace("\r\n", "\n");
        entries.add(("LYRICS=" + l).getBytes(StandardCharsets.UTF_8));
        entries.add(("UNSYNCEDLYRICS=" + l).getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] vendor = "163MusicPro".getBytes(StandardCharsets.UTF_8);
        writeLe32(out, vendor.length);
        out.write(vendor, 0, vendor.length);
        writeLe32(out, entries.size());
        for (byte[] e : entries) {
            writeLe32(out, e.length);
            out.write(e, 0, e.length);
        }
        return out.toByteArray();
    }

    private static int readLe32(byte[] data, int off) {
        if (off + 4 > data.length) {
            return -1;
        }
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16) | ((data[off + 3] & 0xFF) << 24);
    }

    /** 从输入流当前位置起，把 length 字节流式拷到输出（8KB 缓冲）。 */
    private static void copyRange(RandomAccessFile in, long start, long length, RandomAccessFile out)
            throws IOException {
        in.seek(start);
        byte[] buf = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    private static void copyRange(RandomAccessFile in, long start, long length, FileOutputStream out)
            throws IOException {
        in.seek(start);
        byte[] buf = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    // ── MP3 / ID3v2.4 ───────────────────────────────────────────────────────

    private static byte[] embedMp3(byte[] file, byte[] cover, String lyrics) {
        if (file == null || file.length < 4) {
            return null;
        }
        int audioStart = 0;
        if (file[0] == 'I' && file[1] == 'D' && file[2] == '3') {
            if (file.length < 10) {
                return null;
            }
            int size = ((file[6] & 0x7F) << 21) | ((file[7] & 0x7F) << 14)
                    | ((file[8] & 0x7F) << 7) | (file[9] & 0x7F);
            audioStart = 10 + size;
        }
        if (audioStart < 0 || audioStart >= file.length) {
            return null;
        }
        byte[] tag = buildId3v24Tag(cover, lyrics);
        byte[] out = new byte[tag.length + (file.length - audioStart)];
        System.arraycopy(tag, 0, out, 0, tag.length);
        System.arraycopy(file, audioStart, out, tag.length, file.length - audioStart);
        return out;
    }

    /** 构造 ID3v2.4 标签（UTF-8）：USLT 歌词 + 可选 APIC 封面。 */
    private static byte[] buildId3v24Tag(byte[] cover, String lyrics) {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        if (lyrics != null && !lyrics.isEmpty()) {
            ByteArrayOutputStream d = new ByteArrayOutputStream();
            d.write(0x03);                                  // encoding UTF-8
            d.write('e'); d.write('n'); d.write('g');        // language
            d.write(0x00);                                  // empty descriptor
            byte[] text = lyrics.getBytes(StandardCharsets.UTF_8);
            d.write(text, 0, text.length);
            writeFrame(frames, "USLT", d.toByteArray());
        }
        if (cover != null && cover.length > 0) {
            ByteArrayOutputStream d = new ByteArrayOutputStream();
            d.write(0x03);                                  // encoding UTF-8
            byte[] mime = "image/jpeg".getBytes(StandardCharsets.ISO_8859_1);
            d.write(mime, 0, mime.length);
            d.write(0x00);
            d.write(0x03);                                  // FrontCover
            d.write(0x00);                                  // empty description
            d.write(cover, 0, cover.length);
            writeFrame(frames, "APIC", d.toByteArray());
        }
        byte[] body = frames.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('I'); out.write('D'); out.write('3');
        out.write(0x04);                                    // v2.4
        out.write(0x00);                                    // revision
        out.write(0x00);                                    // flags
        int size = body.length;
        out.write((size >> 21) & 0x7F);
        out.write((size >> 14) & 0x7F);
        out.write((size >> 7) & 0x7F);
        out.write(size & 0x7F);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static void writeFrame(ByteArrayOutputStream out, String id, byte[] data) {
        byte[] idBytes = id.getBytes(StandardCharsets.ISO_8859_1);
        out.write(idBytes, 0, 4);
        int size = data.length;
        out.write((size >> 21) & 0x7F);
        out.write((size >> 14) & 0x7F);
        out.write((size >> 7) & 0x7F);
        out.write(size & 0x7F);
        out.write(0x00);   // flags hi
        out.write(0x00);   // flags lo
        out.write(data, 0, data.length);
    }

    // ── FLAC ─────────────────────────────────────────────────────────────────

    private static final class FlacBlock {
        int type;
        boolean last;
        byte[] data;
    }

    private static byte[] embedFlac(byte[] file, byte[] cover, String lyrics) {
        if (file == null || file.length < 8
                || file[0] != 'f' || file[1] != 'L' || file[2] != 'a' || file[3] != 'C') {
            return null;
        }
        int pos = 4;
        List<FlacBlock> blocks = new ArrayList<>();
        while (pos + 4 <= file.length) {
            FlacBlock b = new FlacBlock();
            b.last = (file[pos] & 0x80) != 0;
            b.type = file[pos] & 0x7F;
            int len = ((file[pos + 1] & 0xFF) << 16) | ((file[pos + 2] & 0xFF) << 8)
                    | (file[pos + 3] & 0xFF);
            pos += 4;
            if (pos + len > file.length) {
                return null;
            }
            b.data = new byte[len];
            System.arraycopy(file, pos, b.data, 0, len);
            pos += len;
            blocks.add(b);
            if (b.last) {
                break;
            }
        }
        if (blocks.isEmpty() || blocks.get(0).type != 0) {
            return null;   // 第一个块必须是 STREAMINFO
        }
        // 去掉旧的 VORBIS_COMMENT(4) 与 PICTURE(6)，其余保留
        List<FlacBlock> kept = new ArrayList<>();
        for (FlacBlock b : blocks) {
            if (b.type != 4 && b.type != 6) {
                kept.add(b);
            }
        }
        // 新增 VORBIS_COMMENT（歌词）与 PICTURE（封面）
        if (lyrics != null && !lyrics.isEmpty()) {
            FlacBlock b = new FlacBlock();
            b.type = 4;
            b.data = buildVorbisComment(lyrics);
            kept.add(b);
        }
        if (cover != null && cover.length > 0) {
            FlacBlock b = new FlacBlock();
            b.type = 6;
            b.data = buildFlacPicture(cover);
            kept.add(b);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('f'); out.write('L'); out.write('a'); out.write('C');
        for (int i = 0; i < kept.size(); i++) {
            FlacBlock b = kept.get(i);
            boolean last = i == kept.size() - 1;
            int len = b.data.length;
            out.write((last ? 0x80 : 0x00) | (b.type & 0x7F));
            out.write((len >> 16) & 0xFF);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
            out.write(b.data, 0, len);
        }
        if (pos < file.length) {
            out.write(file, pos, file.length - pos);   // 音频帧原样接在后面
        }
        return out.toByteArray();
    }

    private static byte[] buildVorbisComment(String lyrics) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] vendor = "163MusicPro".getBytes(StandardCharsets.UTF_8);
        writeLe32(out, vendor.length);
        out.write(vendor, 0, vendor.length);
        List<byte[]> entries = new ArrayList<>();
        String l = lyrics.replace("\r\n", "\n");
        entries.add(("LYRICS=" + l).getBytes(StandardCharsets.UTF_8));
        entries.add(("UNSYNCEDLYRICS=" + l).getBytes(StandardCharsets.UTF_8));
        writeLe32(out, entries.size());
        for (byte[] e : entries) {
            writeLe32(out, e.length);
            out.write(e, 0, e.length);
        }
        return out.toByteArray();
    }

    private static byte[] buildFlacPicture(byte[] cover) {
        int[] wh = jpegSize(cover);
        int width = wh[0];
        int height = wh[1];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBe32(out, 3);                              // picture type: FrontCover
        byte[] mime = "image/jpeg".getBytes(StandardCharsets.ISO_8859_1);
        writeBe32(out, mime.length);
        out.write(mime, 0, mime.length);
        writeBe32(out, 0);                              // description length
        writeBe32(out, width);
        writeBe32(out, height);
        writeBe32(out, 24);                             // color depth
        writeBe32(out, 0);                              // indexed colors
        writeBe32(out, cover.length);
        out.write(cover, 0, cover.length);
        return out.toByteArray();
    }

    /** 读 JPEG 的宽高（SOF0/2 段）；解析失败返回 0,0（部分播放器对 0 容忍）。 */
    private static int[] jpegSize(byte[] jpeg) {
        int[] none = {0, 0};
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return none;
        }
        int i = 2;
        while (i + 9 < jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                int h = ((jpeg[i + 5] & 0xFF) << 8) | (jpeg[i + 6] & 0xFF);
                int w = ((jpeg[i + 7] & 0xFF) << 8) | (jpeg[i + 8] & 0xFF);
                return new int[]{w, h};
            }
            i += 2 + len;
        }
        return none;
    }

    private static void writeLe32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeBe32(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    // ── 标记 / 备份 / 文件工具 ──────────────────────────────────────────────

    private static boolean isMarked(File info) {
        if (!info.isFile()) {
            return false;
        }
        try {
            JSONObject o = new JSONObject(new String(readAll(info), StandardCharsets.UTF_8));
            return o.optBoolean(MARKER_KEY, false);
        } catch (Exception e) {
            return false;
        }
    }

    private static void mark(File info) {
        try {
            JSONObject o = info.isFile()
                    ? new JSONObject(new String(readAll(info), StandardCharsets.UTF_8))
                    : new JSONObject();
            o.put(MARKER_KEY, true);
            o.put("embedded_at", System.currentTimeMillis());
            writeAll(info, o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "写 info.json 标记失败: " + e);
        }
    }

    private static void backupOnce(File file) throws IOException {
        File bak = new File(file.getParentFile(), file.getName() + ".bak");
        if (bak.exists()) {
            return;
        }
        // 原实现 writeAll(bak, readAll(file)) 会把整个文件读进内存（.bak + 原文件 = 双份），
        // 大 FLAC 直接 OOM；改成 8KB 缓冲的流式复制。
        try (FileInputStream in = new FileInputStream(file);
             FileOutputStream out = new FileOutputStream(bak)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    /**
     * 递归找歌曲目录下的音频文件（真实结构是 {@code songs/<音质>/xxx.mp3}）。
     *
     * <p>深度上限 4 层，避免在异常目录结构下无限下钻；结果按路径排序保证每次处理顺序稳定
     * （多音质副本都要内嵌）。</p>
     */
    private static List<File> findAudios(File dir) {
        List<File> result = new ArrayList<>();
        collectAudios(dir, result, 0);
        java.util.Collections.sort(result, (a, b) ->
                a.getAbsolutePath().compareTo(b.getAbsolutePath()));
        return result;
    }

    private static void collectAudios(File dir, List<File> out, int depth) {
        if (dir == null || depth > 4) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            for (String ext : AUDIO_EXTS) {
                if (n.endsWith(ext)) {
                    out.add(f);
                    break;
                }
            }
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectAudios(f, out, depth + 1);
            }
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
