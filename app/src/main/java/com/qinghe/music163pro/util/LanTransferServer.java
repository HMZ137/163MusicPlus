package com.qinghe.music163pro.util;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.util.Log;

import com.qinghe.music163pro.model.Song;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 极简 HTTP 服务：同一局域网下的电脑 / 手机打开 {@code http://<手表IP>:<端口>}，
 * 选好文件直接传上来。
 *
 * <p><b>目录分流</b>：音乐文件（mp3/wav/flac/m4a/aac/ogg）落盘到 {@code /sdcard/Music}
 * （本机音乐页本来就在扫这个目录，所以传完不用做任何额外操作就能在列表里看到）；
 * 其它文件（图片、视频、压缩包、文档等）落盘到 {@code /sdcard/TransFiles}，
 * 与音乐库分开，避免污染「本机音乐」列表。</p>
 *
 * <h3>为什么手写而不用第三方 HTTP 库</h3>
 * <p>只需要「一个上传页 + 接收一个文件流」，引一整个 HTTP 框架（还要依赖仓库能拉到）
 * 不划算，也会把 APK 撑大。这里用 {@link ServerSocket} 就够了。</p>
 *
 * <h3>为什么不用 multipart</h3>
 * <p>标准表单上传是 {@code multipart/form-data}，服务端要按边界切分、解析各段头部；
 * 而浏览器可以用 XHR **把文件本体直接当请求体** POST 上来（见 {@link #FALLBACK_HTML} 里的
 * 前端代码，正式页面在 assets/lan_upload.html 里注入），服务端按 {@code Content-Length}
 * 读满字节写盘即可 —— 省掉一整层解析。</p>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>头部必须**逐字节**读到 {@code \r\n\r\n} 为止。用 {@code BufferedReader} 会把
 *       紧随其后的请求体一起缓冲掉，导致文件缺字节。</li>
 *   <li>响应一律 {@code Connection: close}，处理完就关 socket，不支持 keep-alive
 *       （顺序处理即可，不需要并发）。</li>
 *   <li>文件名只取最后一段并去掉 {@code ..}，防止 {@code ../../} 路径穿越写到别处。</li>
 * </ul>
 */
public final class LanTransferServer {

    private static final String TAG = "LanTransferServer";
    /** 默认端口。被占用时会从它开始往后找空位。 */
    public static final int DEFAULT_PORT = 9527;
    private static final int PORT_SEARCH_LIMIT = 20;
    private static final int BUFFER_SIZE = 64 * 1024;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile int port = DEFAULT_PORT;
    /** 局域网发现应答器（收到 UDP DISCOVER 就回自己的信息）。 */
    private final LanDiscovery.Responder discovery = new LanDiscovery.Responder();
    private volatile int uploadedCount;
    private volatile String lastFileName;
    private final File saveDir;
    /**
     * 歌词内嵌后台线程（需求方 2026-11-15）：embedLyricsInto 要读整段音频、写标签、
     * 备份 .bak，是重 I/O；以前在 HTTP 请求线程里同步做，接收端会长时间无响应。
     * 用单线程串行后台执行——既不让请求线程阻塞，也避免并发写同一个音频文件。
     */
    private java.util.concurrent.ExecutorService embedExecutor;
    /** 并行处理上传连接：以前 acceptLoop 单线程串行，歌词批量上传会排队到超时。 */
    private java.util.concurrent.ExecutorService requestExecutor;
    /** 同一音频文件的内嵌互斥锁（避免两个 .lrc 并发改同一个文件的标签）。 */
    /** 音频 stem(小写) -> File：目录只扫一次，之后歌词匹配 O(1)。 */
    private final java.util.concurrent.ConcurrentHashMap<String, File> audioIndex =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean audioIndexBuilt;
    /** 未配对歌词：内存优先；磁盘 pending 目录仅作旧流程兼容。 */
    private final java.util.concurrent.ConcurrentHashMap<String, byte[]> pendingLyrics =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 懒加载：首次需要匹配时递归扫一次 Music（最多 4 层），之后全走内存表。 */
    private synchronized void ensureAudioIndex() {
        if (audioIndexBuilt) {
            return;
        }
        indexAudioRecursive(saveDir, 0);
        audioIndexBuilt = true;
    }

    private void indexAudioRecursive(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 4) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isFile() && isMusicFile(f.getName())) {
                audioIndex.put(stripExt(f.getName()).toLowerCase(Locale.ROOT), f);
            }
        }
        for (File f : children) {
            if (f.isDirectory()) {
                indexAudioRecursive(f, depth + 1);
            }
        }
    }

    private final java.util.HashMap<String, Object> fileLocks = new java.util.HashMap<>();
    private synchronized Object lockFor(String path) {
        Object lock = fileLocks.get(path);
        if (lock == null) {
            lock = new Object();
            fileLocks.put(path, lock);
        }
        return lock;
    }

    /** 美化后的上传页（从 assets 读取注入；为空时回退到内置精简页）。 */
    private String indexHtml;

    /** 应用上下文：读音量 / 电量 / 网络 / 播放状态用，由 {@code LanTransferService} 注入。 */
    private static volatile android.content.Context appContext;

    public static void setAppContext(android.content.Context context) {
        appContext = context == null ? null : context.getApplicationContext();
    }
    /** 已接收文件列表，供「收件箱」面板展示与回下载。单线程处理连接，synchronized 仅作保险。 */
    private final List<ReceivedFile> received = Collections.synchronizedList(new ArrayList<>());

    // ==================== APK 安装队列（2026-11-28） ====================
    //
    // 发送端传过来的 APK 落在 /sdcard/TransFiles，系统不会替我们装。
    // 这里排队交给 UI：每收一个 apk 就入队 + 通知监听者，UI 弹「是否安装」，
    // 装完（或用户跳过）再来取下一个 —— 支持一次传多个 APK 连续安装。

    /** 收到 APK 的回调（在 UI 线程回调）。 */
    public interface ApkReceivedListener {
        void onApkReceived(File apk);
    }

    private static final List<File> PENDING_APKS =
            Collections.synchronizedList(new ArrayList<File>());
    private static volatile ApkReceivedListener sApkListener;
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** 注册 APK 到达回调（Activity 在 onStart / onStop 里成对设置，避免泄漏）。 */
    public static void setApkReceivedListener(ApkReceivedListener listener) {
        sApkListener = listener;
    }

    /** 取走队首的待安装 APK；没有则返回 null。 */
    public static File pollPendingApk() {
        synchronized (PENDING_APKS) {
            return PENDING_APKS.isEmpty() ? null : PENDING_APKS.remove(0);
        }
    }

    public static int pendingApkCount() {
        synchronized (PENDING_APKS) {
            return PENDING_APKS.size();
        }
    }

    private static void enqueueApk(File apk) {
        if (apk == null) {
            return;
        }
        PENDING_APKS.add(apk);
        final ApkReceivedListener listener = sApkListener;
        if (listener != null) {
            MAIN.post(() -> listener.onApkReceived(apk));
        }
    }
    private final java.util.concurrent.atomic.AtomicInteger rxSeq =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 已接收到的文件（用于「收件箱」面板展示与回下载）。 */
    private static final class ReceivedFile {
        final String id;
        final String name;
        final long size;
        final long time;
        final String from;
        final String path;

        ReceivedFile(String id, String name, long size, long time, String from, String path) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.time = time;
            this.from = from;
            this.path = path;
        }
    }

    public LanTransferServer() {
        this(defaultSaveDir());
    }

    public LanTransferServer(File saveDir) {
        this.saveDir = saveDir != null ? saveDir : defaultSaveDir();
    }

    /** 默认落盘目录：/sdcard/Music —— 与本机音乐页的扫描根目录保持一致。 */
    public static File defaultSaveDir() {
        return new File(Environment.getExternalStorageDirectory(), "Music");
    }

    /** 非音乐文件的落盘目录：/sdcard/TransFiles —— 与音乐目录分开，避免污染本机音乐库。 */
    public static File defaultTransFilesDir() {
        return new File(Environment.getExternalStorageDirectory(), "TransFiles");
    }

    /**
     * 是否为可识别的音乐文件（仅这些扩展名会被登记进「本机音乐」库）。
     * 与 {@link #defaultSaveDir()} / {@link #defaultTransFilesDir()} 的分工保持一致。
     */
    private static boolean isMusicFile(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        switch (ext) {
            case "mp3":
            case "wav":
            case "flac":
            case "m4a":
            case "aac":
            case "ogg":
                return true;
            default:
                return false;
        }
    }

    public File getSaveDir() {
        return saveDir;
    }

    /** 是否为安装包（接收端要单独提示安装）。 */
    private static boolean isApkFile(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    /**
     * 把手表本地的文件登记成「网页端可下载」（2026-11-28）。
     *
     * <p>手表没法主动推给浏览器，所以「发给网页端」= 登记进收件箱，
     * 网页的「已接收」里立刻出现，点下载即可取走。</p>
     */
    public boolean publishToWeb(File file, String from) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String rxId = "rx" + rxSeq.incrementAndGet();
        received.add(new ReceivedFile(rxId, file.getName(), file.length(),
                System.currentTimeMillis(), from == null ? "本机" : from,
                file.getAbsolutePath()));
        return true;
    }

    public int getUploadedCount() {
        return uploadedCount;
    }

    public String getLastFileName() {
        return lastFileName;
    }

    /** 注入美化后的上传页内容（通常来自 assets/lan_upload.html）。 */
    public void setIndexHtml(String html) {
        this.indexHtml = html;
    }

    private String getIndexHtml() {
        String html = indexHtml != null ? indexHtml : FALLBACK_HTML;
        // P1-10：网页主题色跟随 App 主题（每次请求动态注入，切主题后刷新页面即可看到）
        return applyTheme(html);
    }

    /**
     * 把上传页 {@code :root} 里的强调色变量换成 App 当前主题色。
     *
     * <p>页面里写死的是初音绿（39c5bb）占位，这里按 {@link ThemeManager#accentColor}
     * 派生四个变量：accent / accent-strong（向白推 25%）/ accent-soft（13%）/ accent-line（34%）。</p>
     */
    private String applyTheme(String html) {
        try {
            if (html == null) return html;
            int accent = com.qinghe.music163pro.util.ThemeManager.accentColor(appContext);
            int r = (accent >> 16) & 0xFF;
            int g = (accent >> 8) & 0xFF;
            int b = accent & 0xFF;
            int sr = r + (255 - r) / 4;
            int sg = g + (255 - g) / 4;
            int sb = b + (255 - b) / 4;
            String hex = String.format(java.util.Locale.ROOT, "#%02X%02X%02X", r, g, b);
            String strong = String.format(java.util.Locale.ROOT, "#%02X%02X%02X", sr, sg, sb);
            String soft = String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,.13)", r, g, b);
            String dim = String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,.12)", r, g, b);
            String line = String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,.34)", r, g, b);
            html = html
                    .replace("--accent:#39c5bb", "--accent:" + hex)
                    .replace("--accent-strong:#5fe3d8", "--accent-strong:" + strong)
                    .replace("--accent-bright:#5fe3d8", "--accent-bright:" + strong)
                    .replace("--accent-soft:rgba(57,197,187,.13)", "--accent-soft:" + soft)
                    .replace("--accent-dim:rgba(57,197,187,.12)", "--accent-dim:" + dim)
                    .replace("--accent-line:rgba(57,197,187,.34)", "--accent-line:" + line)
                    .replace("--accent-edge:rgba(57,197,187,.34)", "--accent-edge:" + line);
            // 兜底：页面里可能还有以字面量形式出现的旧主题色（不在 CSS 变量里的），全部换掉
            html = html.replace("#39C5BB", hex).replace("#39c5bb", hex)
                    .replace("#5FE3D8", strong).replace("#5fe3d8", strong)
                    .replace("rgba(57,197,187", "rgba(" + r + "," + g + "," + b)
                    .replace("rgba(95,227,216", "rgba(" + sr + "," + sg + "," + sb);
            // 需求 2026-11-21 任务 1：设置过「局域网网页背景」时，给页面注入背景样式
            if (LanPageBackground.has(appContext)) {
                html = html.replace("</head>",
                        "<style>body{background:url('/api/lan-bg') center/cover no-repeat fixed;"
                                + "}</style></head>");
            }
            return html;
        } catch (Throwable ignored) {
            return html;
        }
    }

    /**
     * 启动服务。端口被占用时自动往后找（最多 {@link #PORT_SEARCH_LIMIT} 个）。
     *
     * @return 实际监听的端口；启动失败返回 -1
     */
    public synchronized boolean start() {
        if (running) {
            return true;
        }
        if (embedExecutor == null || embedExecutor.isShutdown()) {
            // 歌词内嵌并行（I/O 密集，手表不宜开太多；同文件用 lockFor 互斥）
            embedExecutor = java.util.concurrent.Executors.newFixedThreadPool(2,
                    r -> {
                        Thread t = new Thread(r, "LanTransfer-embed");
                        t.setDaemon(true);
                        return t;
                    });
        }
        if (requestExecutor == null || requestExecutor.isShutdown()) {
            requestExecutor = java.util.concurrent.Executors.newFixedThreadPool(4,
                    r -> {
                        Thread t = new Thread(r, "LanTransfer-request");
                        t.setDaemon(true);
                        return t;
                    });
        }
        for (int candidate = DEFAULT_PORT;
             candidate < DEFAULT_PORT + PORT_SEARCH_LIMIT;
             candidate++) {
            try {
                serverSocket = new ServerSocket(candidate);
                serverSocket.setReuseAddress(true);
                port = candidate;
                running = true;
                acceptThread = new Thread(this::acceptLoop, "LanTransfer-accept");
                acceptThread.setDaemon(true);
                acceptThread.start();
                // 2026-11-28：同时开 UDP 发现，其它手表/网页才能「扫描」到自己
                try {
                    discovery.start(candidate);
                } catch (Throwable t) {
                    Log.w(TAG, "发现应答器启动失败（不影响传文件）: " + t);
                }
                return true;
            } catch (Exception e) {
                // 端口占用，试下一个
                Log.i(TAG, "端口 " + candidate + " 不可用，尝试下一个", e);
            }
        }
        return false;
    }

    public synchronized void stop() {
        running = false;
        try {
            discovery.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        } finally {
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        if (embedExecutor != null) {
            embedExecutor.shutdownNow();
            embedExecutor = null;
        }
        if (requestExecutor != null) {
            requestExecutor.shutdownNow();
            requestExecutor = null;
        }
    }

    // ==================== 请求处理 ====================

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                java.util.concurrent.ExecutorService executor = requestExecutor;
                if (executor == null || executor.isShutdown()) {
                    closeQuietly(socket);
                    continue;
                }
                // 并行处理每个上传：歌词批量上传不再排队到浏览器超时
                executor.execute(() -> {
                    try {
                        handle(socket);
                    } catch (Exception e) {
                        if (running) {
                            Log.w(TAG, "处理连接时出错", e);
                        }
                    } finally {
                        closeQuietly(socket);
                    }
                });
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "接受连接时出错", e);
                }
            }
        }
    }

    private void handle(Socket socket) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = socket.getInputStream();
            out = socket.getOutputStream();

            // 逐字节读请求头，遇到 \r\n\r\n 就停。
            // 不能用 BufferedReader：它会把紧跟着的请求体一起读进自己的缓冲区。
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int state = 0;
            int b;
            while ((b = in.read()) != -1) {
                headBuf.write(b);
                if (b == '\r') {
                    state = (state == 2) ? 3 : 1;
                } else if (b == '\n') {
                    if (state == 1) {
                        state = 2;
                    } else if (state == 3) {
                        break;
                    } else {
                        state = 0;
                    }
                } else {
                    state = 0;
                }
                if (headBuf.size() > 64 * 1024) {
                    // 头部异常地长，别再读了
                    return;
                }
            }
            String head = new String(headBuf.toByteArray(), "ISO-8859-1");
            if (head.isEmpty()) {
                return;
            }
            String[] lines = head.split("\r\n");
            String requestLine = lines.length > 0 ? lines[0] : "";
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String uri = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim()
                        .equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            String path = uri;
            String query = "";
            int q = uri.indexOf('?');
            if (q >= 0) {
                path = uri.substring(0, q);
                query = uri.substring(q + 1);
            }

            if ("GET".equalsIgnoreCase(method) && ("/".equals(path) || "/index.html".equals(path))) {
                byte[] body = getIndexHtml().getBytes("UTF-8");
                writeResponse(out, 200, "OK", "text/html; charset=utf-8", body);
            } else if ("POST".equalsIgnoreCase(method) && "/upload".equals(path)) {
                handleUpload(socket, in, out, query, contentLength);
            } else if ("GET".equalsIgnoreCase(method) && "/api/status".equals(path)) {
                handleStatus(out);
            } else if ("GET".equalsIgnoreCase(method) && "/api/events".equals(path)) {
                handleEvents(out);
            } else if ("GET".equalsIgnoreCase(method) && "/api/inbox".equals(path)) {
                handleInboxList(out);
            } else if ("OPTIONS".equalsIgnoreCase(method)) {
                writeResponse(out, 204, "No Content", "text/plain; charset=utf-8", new byte[0]);
            } else if ("GET".equalsIgnoreCase(method) && "/api/devices".equals(path)) {
                handleDevices(socket, out);
            } else if ("GET".equalsIgnoreCase(method) && "/api/files".equals(path)) {
                handleFileList(out, query);
            } else if ("GET".equalsIgnoreCase(method) && "/api/file".equals(path)) {
                handleFileDownload(out, query);
            } else if ("GET".equalsIgnoreCase(method) && "/api/lan-bg".equals(path)) {
                // 需求 2026-11-21 任务 1：网页背景图（独立存储 LanPageBackground，与播放页背景无关）
                handleLanBackground(out);
            } else if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/download/")) {
                handleDownload(out, path.substring("/api/download/".length()));
            } else if ("DELETE".equalsIgnoreCase(method) && path.startsWith("/api/inbox/")) {
                handleInboxDelete(out, path.substring("/api/inbox/".length()));
            } else {
                byte[] body = "{\"ok\":false,\"error\":\"not found\"}".getBytes("UTF-8");
                writeResponse(out, 404, "Not Found", "application/json; charset=utf-8", body);
            }
        } catch (Exception e) {
            Log.w(TAG, "处理请求失败", e);
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }
    }


    // ==================== 组网：设备发现 / 文件列表（2026-11-28） ====================

    /**
     * GET /api/devices —— 代网页做一次 UDP 扫描，返回同网内其它接收端。
     *
     * <p>网页本身发不了 UDP 广播，所以由手表代扫后再以 JSON 返回。</p>
     */
    private void handleDevices(Socket socket, OutputStream out) throws Exception {
        String selfIp = socket != null && socket.getLocalAddress() != null
                ? socket.getLocalAddress().getHostAddress() : null;
        List<LanDiscovery.Peer> peers = LanDiscovery.scan(getPort(), selfIp);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"self\":{\"name\":\"")
                .append(jsonEscape(LanDiscovery.selfName()))
                .append("\",\"ip\":\"").append(jsonEscape(selfIp == null ? "" : selfIp))
                .append("\",\"port\":").append(getPort()).append("},\"devices\":[");
        for (int i = 0; i < peers.size(); i++) {
            LanDiscovery.Peer p = peers.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(jsonEscape(p.name))
                    .append("\",\"ip\":\"").append(jsonEscape(p.ip))
                    .append("\",\"port\":").append(p.port).append('}');
        }
        sb.append("]}");
        writeResponse(out, 200, "OK", "application/json; charset=utf-8",
                sb.toString().getBytes("UTF-8"));
    }

    /**
     * GET /api/files?dir=music|trans —— 列出可下载的文件（网页端「接收」用）。
     *
     * <p>网页不能被动监听端口，所以「网页接收」= 从手表拉：这里给出文件清单，
     * 点击后走 /api/file 下载。这样不需要第二套服务，也不需要先开接收开关。</p>
     */
    private void handleFileList(OutputStream out, String query) throws Exception {
        File dir = "trans".equalsIgnoreCase(queryValue(query, "dir"))
                ? defaultTransFilesDir() : saveDir;
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"dir\":\"")
                .append(dir == null ? "" : jsonEscape(dir.getName())).append("\",\"files\":[");
        File[] files = dir == null ? null : dir.listFiles();
        boolean first = true;
        if (files != null) {
            java.util.Arrays.sort(files,
                    (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) {
                if (!f.isFile() || f.getName().startsWith(".")) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"name\":\"").append(jsonEscape(f.getName()))
                        .append("\",\"size\":").append(f.length())
                        .append(",\"time\":").append(f.lastModified()).append('}');
            }
        }
        sb.append("]}");
        writeResponse(out, 200, "OK", "application/json; charset=utf-8",
                sb.toString().getBytes("UTF-8"));
    }

    /** GET /api/file?dir=music|trans&name=xxx —— 下载单个文件。 */
    private void handleFileDownload(OutputStream out, String query) throws Exception {
        File dir = "trans".equalsIgnoreCase(queryValue(query, "dir"))
                ? defaultTransFilesDir() : saveDir;
        String raw = queryValue(query, "name");
        String safe = sanitizeFileName(raw);
        if (dir == null || safe == null || safe.isEmpty()) {
            writeResponse(out, 400, "Bad Request", "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"bad name\"}".getBytes("UTF-8"));
            return;
        }
        File file = new File(dir, safe);
        // 防目录穿越：解析后必须仍在该目录下
        if (!file.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)
                || !file.isFile()) {
            writeResponse(out, 404, "Not Found", "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"not found\"}".getBytes("UTF-8"));
            return;
        }
        String ascii = safe.replaceAll("[^\\x20-\\x7e]", "_");
        String encoded = java.net.URLEncoder.encode(safe, "UTF-8").replace("+", "%20");
        String disposition = "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
        streamFile(out, file, mimeOf(safe), disposition);
    }

    private void handleUpload(Socket socket, InputStream in, OutputStream out, String query, int contentLength)
            throws Exception {
        String name = queryValue(query, "name");
        String safeName = sanitizeFileName(name);

        // 2026-10-17：局域网传歌「歌词上传」—— .lrc 按文件名与音频匹配后合并，
        // 不落进音乐库 / 收件箱（它不是音频，也不是普通文件）。
        if (safeName != null && safeName.toLowerCase(Locale.ROOT).endsWith(".lrc")) {
            handleLyricsUpload(out, safeName, in, contentLength, query);
            return;
        }

        // 目录分流：音乐文件进 Music，其它文件进 TransFiles（与音乐库隔离）
        File dir = isMusicFile(safeName) ? saveDir : defaultTransFilesDir();
        if (!dir.exists() && !dir.mkdirs()) {
            byte[] body = "{\"ok\":false,\"error\":\"无法创建保存目录\"}".getBytes("UTF-8");
            writeResponse(out, 500, "Server Error", "application/json; charset=utf-8", body);
            return;
        }
        File target = uniqueFile(dir, safeName);

        FileOutputStream fos = null;
        boolean ok = false;
        long written = 0;
        try {
            fos = new FileOutputStream(target);
            byte[] buf = new byte[BUFFER_SIZE];
            long remaining = contentLength;
            if (remaining > 0) {
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int n = in.read(buf, 0, toRead);
                    if (n < 0) {
                        break;
                    }
                    fos.write(buf, 0, n);
                    written += n;
                    remaining -= n;
                }
            } else {
                // 2026-11-28：没有 Content-Length（chunked，来自系统选择器拿不到大小的文件）
                // 时一直读到流结束，否则会存成 0 字节
                int n;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    written += n;
                }
            }
            ok = true;
        } finally {
            closeQuietly(fos);
        }

        if (!ok) {
            target.delete();
            byte[] body = "{\"ok\":false,\"error\":\"写入失败\"}".getBytes("UTF-8");
            writeResponse(out, 500, "Server Error", "application/json; charset=utf-8", body);
            return;
        }

        // 仅音乐文件登记进本地音乐库缓存，让它立刻可被解析（不必等下一次扫描）；
        // 非音乐文件只落盘、不登记，避免污染「本机音乐」列表。
        if (isMusicFile(safeName)) {
            // 新上传的音频立即进索引（不设 audioIndexBuilt，让首次匹配仍会扫一次已有库）
            audioIndex.put(stripExt(safeName).toLowerCase(Locale.ROOT), target);
            try {
                Song song = new Song(LocalTracks.idForPath(target.getAbsolutePath()),
                        safeName, "", "");
                song.setUrl(target.getAbsolutePath());
                song.setForceLocalPlayback(true);
                LocalLibraryCache.put(song);
            } catch (Exception ignored) {
            }
            // 若之前先传过同名 .lrc（暂存在 Music/__lyrics__/），这里立即配对合并
            matchPendingLyric(target);
        } else {
            Log.i(TAG, "非音乐文件，仅落盘不登记音乐库: " + target.getAbsolutePath());
        }

        uploadedCount++;
        lastFileName = safeName;
        Log.i(TAG, "收到文件: " + target.getAbsolutePath() + " (" + written + " 字节)");

        // 登记进「收件箱」，前端可在网页上看到、回下载、删除
        String from = (socket.getInetAddress() != null)
                ? socket.getInetAddress().getHostAddress() : "未知设备";
        String rxId = "rx" + rxSeq.incrementAndGet();
        received.add(new ReceivedFile(rxId, safeName, written, System.currentTimeMillis(), from,
                target.getAbsolutePath()));

        // 2026-11-28：APK 单独入队，交给 UI 弹「是否安装」（系统不允许后台静默装）
        if (isApkFile(safeName)) {
            Log.i(TAG, "收到 APK，等待用户确认安装: " + target.getAbsolutePath());
            enqueueApk(target);
        }

        String json = "{\"ok\":true,\"name\":\"" + jsonEscape(safeName) + "\",\"size\":" + written + "}";
        writeResponse(out, 200, "OK", "application/json; charset=utf-8", json.getBytes("UTF-8"));
    }

    // ==================== 歌词上传（.lrc → 按文件名匹配音频并合并） ====================

    /** 暂存「还没找到同名音频」的歌词目录（音频后传时自动配对）。 */
    private static File lyricsPendingDir() {
        return new File(Environment.getExternalStorageDirectory(), "Music/__lyrics__");
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 处理 .lrc 上传：
     * <ol>
     *   <li>按「去掉扩展名的文件名」在 Music 目录（递归）里找同名音频；</li>
     *   <li>找到 ⇒ 在音频旁边写同名 .lrc（本地播放器优先读取），
     *       并调用 AudioTagEmbedder.embedLyricsInto 把歌词内嵌进音频标签（原文件备份 .bak）；</li>
     *   <li>没找到 ⇒ 暂存到 Music/__lyrics__/，等音频上传后自动配对。</li>
     * </ol>
     */
    private void handleLyricsUpload(OutputStream out, String safeName, InputStream in,
                                    int contentLength, String query) throws Exception {
        if (contentLength <= 0 || contentLength > 4 * 1024 * 1024) {
            byte[] body = "{\"ok\":false,\"error\":\"歌词文件过大或为空\"}".getBytes("UTF-8");
            writeResponse(out, 400, "Bad Request", "application/json; charset=utf-8", body);
            return;
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER_SIZE];
        int remaining = contentLength;
        while (remaining > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, remaining));
            if (n < 0) {
                break;
            }
            bos.write(buf, 0, n);
            remaining -= n;
        }
        byte[] data = bos.toByteArray();
        String text = new String(data, "UTF-8");
        String stem = stripExt(safeName).trim();
        if (stem.isEmpty()) {
            stem = "lyrics";
        }

        // 需求方 2026-11-15：匹配改在发送端做（配对参数 pair），接收端用一次建好的索引
        // O(1) 命中；不再每次 .lrc 都递归扫 4 层 Music 目录（几十首歌时是 O(N²) I/O）。
        ensureAudioIndex();
        String stemKey = stem.toLowerCase(Locale.ROOT);
        String pair = queryValue(query, "pair");   // 发送端显式指定的配对音频名（同批待上传）
        boolean waitForUpload = pair != null && !pair.isEmpty();
        // 需求（2026-11-20）：带 pair 说明歌词与音频同批上传，先暂存、等这首音频落盘再配对，
        // 不能拿设备里已有的同名旧曲目顶替（匹配对象必须是「待上传曲目」）。
        File audio = waitForUpload ? null : audioIndex.get(stemKey);
        boolean matched = false;
        String audioName = "";
        if (audio != null) {
            File sidecar = new File(audio.getParentFile(), stripExt(audio.getName()) + ".lrc");
            writeBytes(sidecar, data);
            final File audioToEmbed = audio;
            final String lyricText = text;
            embedInBackground(audioToEmbed, lyricText);
            matched = true;
            audioName = audio.getName();
            Log.i(TAG, "歌词已匹配: " + safeName + " -> " + audio.getAbsolutePath()
                    + " (sidecar=" + sidecar.getName() + ", embed=后台进行中)");
        } else {
            File dir = lyricsPendingDir();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            writeBytes(new File(dir, stem + ".lrc"), data);
            pendingLyrics.put(stemKey, data);
            Log.i(TAG, "歌词暂存（内存+磁盘，等音频上传）: " + safeName);
        }

        // 响应立即返回：内嵌是后台任务，别让上传端/接收端 UI 卡住
        String json = "{\"ok\":true,\"type\":\"lrc\",\"matched\":" + matched
                + ",\"embed\":\"pending\",\"audio\":\"" + jsonEscape(audioName)
                + "\",\"name\":\"" + jsonEscape(safeName) + "\"}";
        writeResponse(out, 200, "OK", "application/json; charset=utf-8", json.getBytes("UTF-8"));
    }

    /** 递归找「去掉扩展名后文件名等于 stem」的音乐文件（最多 4 层）。 */
    private static File findAudioByStem(File dir, String stem) {
        if (dir == null || !dir.isDirectory() || stem == null || stem.isEmpty()) {
            return null;
        }
        return findAudioByStem(dir, stem, 0);
    }

    private static File findAudioByStem(File dir, String stem, int depth) {
        if (depth > 4 || dir == null) {
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File f : children) {
            if (f.isFile() && isMusicFile(f.getName())
                    && stripExt(f.getName()).equalsIgnoreCase(stem)) {
                return f;
            }
        }
        for (File f : children) {
            if (f.isDirectory()) {
                File hit = findAudioByStem(f, stem, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 把歌词内嵌丢到后台线程；HTTP 请求线程只负责落盘与应答。 */
    private void embedInBackground(File audio, String lyricText) {
        java.util.concurrent.ExecutorService executor = embedExecutor;
        if (executor == null || executor.isShutdown()) {
            executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            embedExecutor = executor;
        }
        final Object fileLock = lockFor(audio.getAbsolutePath());
        executor.execute(() -> {
            synchronized (fileLock) {
                try {
                    int embedded = AudioTagEmbedder.embedLyricsInto(audio, lyricText);
                    Log.i(TAG, "歌词内嵌(后台)完成: " + audio.getName() + " embed=" + embedded);
                } catch (Exception e) {
                    Log.w(TAG, "歌词内嵌(后台)失败: " + audio.getName() + " -> " + e);
                }
            }
        });
    }

    /** 音频落盘后，看看 Music/__lyrics__/ 里有没有同名歌词，有就配对合并。 */
    private void matchPendingLyric(File audio) {
        try {
            File pendingDir = lyricsPendingDir();
            if (!pendingDir.isDirectory() || audio == null) {
                return;
            }
            String stem = stripExt(audio.getName());
            byte[] data = pendingLyrics.remove(stem.toLowerCase(Locale.ROOT));
            File lrc = new File(pendingDir, stem + ".lrc");
            if (data == null || data.length == 0) {
                // 兼容旧流程：磁盘上一次读（不扫描目录）
                if (!lrc.isFile()) {
                    return;
                }
                data = readFileBytes(lrc);
            }
            if (data == null || data.length == 0) {
                return;
            }
            File sidecar = new File(audio.getParentFile(), stem + ".lrc");
            writeBytes(sidecar, data);
            embedInBackground(audio, new String(data, "UTF-8"));
            if (lrc.isFile()) {
                lrc.delete();
            }
            Log.i(TAG, "音频上传后自动配对歌词: " + audio.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "自动配对歌词失败: " + e);
        }
    }

    private static void writeBytes(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        }
    }

    private static byte[] readFileBytes(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 收件箱（已接收文件）接口 ====================

    /** 手表实时状态（播放 / 音量 / 电量 / 网络 / 定时 / 下载），供上传页的「状态监控」面板用。 */
    /** 需求 2026-11-21 任务 1：输出网页背景图（JPEG）。未设置时返回 404，网页保持默认深色。 */
    private void handleLanBackground(OutputStream out) throws Exception {
        android.graphics.Bitmap bmp = LanPageBackground.load(appContext);
        if (bmp == null) {
            byte[] notFound = "{\"ok\":false,\"error\":\"no background\"}".getBytes("UTF-8");
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + notFound.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes("UTF-8"));
            out.write(notFound);
            return;
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos);
        byte[] body = bos.toByteArray();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\n"
                + "Content-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(body);
    }

    private void handleStatus(OutputStream out) throws Exception {        byte[] body = buildStatusJson().getBytes("UTF-8");
        writeResponse(out, 200, "OK", "application/json; charset=utf-8", body);
    }

    /** SSE：每秒推一次状态；客户端断开后 write 抛异常，循环自然结束。 */
    private void handleEvents(OutputStream out) throws Exception {
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: keep-alive\r\n"
                + "Access-Control-Allow-Origin: *\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.flush();
        while (running) {
            out.write(("data: " + buildStatusJson() + "\n\n").getBytes("UTF-8"));
            out.flush();
            Thread.sleep(1000L);
        }
    }

    private String buildStatusJson() {
        boolean playing = false;
        String songName = "";
        String artist = "";
        long position = 0;
        long duration = 0;
        String playMode = "";
        boolean timerActive = false;
        long timerRemain = 0;
        String timerMode = "";
        try {
            com.qinghe.music163pro.player.MusicPlayerManager pm =
                    com.qinghe.music163pro.player.MusicPlayerManager.getInstance();
            if (pm != null) {
                playing = pm.isPlaying();
                com.qinghe.music163pro.model.Song song = pm.getCurrentSong();
                if (song != null) {
                    songName = song.getName() == null ? "" : song.getName();
                    artist = song.getArtist() == null ? "" : song.getArtist();
                }
                position = Math.max(0, pm.getCurrentPosition());
                duration = Math.max(0, pm.getDuration());
                if (pm.getPlayMode() != null) {
                    playMode = pm.getPlayMode().name();
                }
                timerActive = pm.isSleepTimerActive();
                if (timerActive) {
                    timerRemain = Math.max(0, pm.getSleepTimerRemainingMs());
                    timerMode = pm.isSleepTimerStopAfterCurrentPending()
                            ? "本曲结束后停" : "倒计时";
                }
            }
        } catch (Throwable ignored) {
        }

        int volume = -1;
        int battery = -1;
        boolean charging = false;
        String network = "";
        String networkDetail = "";
        try {
            android.content.Context ctx = appContext;
            if (ctx != null) {
                android.media.AudioManager am = (android.media.AudioManager)
                        ctx.getSystemService(android.content.Context.AUDIO_SERVICE);
                if (am != null) {
                    int max = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
                    volume = Math.round(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                            * 100f / max);
                }
                android.os.BatteryManager bm = (android.os.BatteryManager)
                        ctx.getSystemService(android.content.Context.BATTERY_SERVICE);
                if (bm != null) {
                    battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                }
                android.content.Intent batt = ctx.registerReceiver(null,
                        new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                if (batt != null) {
                    int status = batt.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                    charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                            || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                }
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                    if (ni != null && ni.isConnected()) {
                        network = ni.getType() == android.net.ConnectivityManager.TYPE_WIFI
                                ? "WiFi" : "移动网络";
                        networkDetail = ni.getExtraInfo() == null ? "" : ni.getExtraInfo();
                    } else {
                        network = "未连接";
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        int downloading = 0;
        String downloadName = "";
        int downloadPercent = -1;
        try {
            java.util.List<com.qinghe.music163pro.manager.DownloadManager.DownloadTask> tasks =
                    com.qinghe.music163pro.manager.DownloadManager.getActiveDownloads();
            if (tasks != null && !tasks.isEmpty()) {
                downloading = tasks.size();
                com.qinghe.music163pro.manager.DownloadManager.DownloadTask t = tasks.get(0);
                downloadName = t.songName == null ? "" : t.songName;
                downloadPercent = t.percent;
            }
        } catch (Throwable ignored) {
        }

        // 2026-11-21：内存 / CPU 占用（网页监控面板展示）
        int memUsedMb = 0;
        int memTotalMb = 0;
        int memMaxMb = 0;
        try {
            Runtime rt = Runtime.getRuntime();
            memTotalMb = (int) (rt.totalMemory() / (1024 * 1024));
            memMaxMb = (int) (rt.maxMemory() / (1024 * 1024));
            memUsedMb = (int) ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        } catch (Throwable ignored) {
        }
        int cpuPercent = readCpuPercent();

        return "{"
                + "\"playing\":" + playing + ","
                + "\"song\":\"" + jsonEscape(songName) + "\","
                + "\"artist\":\"" + jsonEscape(artist) + "\","
                + "\"positionMs\":" + position + ","
                + "\"durationMs\":" + duration + ","
                + "\"playMode\":\"" + jsonEscape(playMode) + "\","
                + "\"volume\":" + volume + ","
                + "\"battery\":" + battery + ","
                + "\"charging\":" + charging + ","
                + "\"network\":\"" + jsonEscape(network) + "\","
                + "\"networkDetail\":\"" + jsonEscape(networkDetail) + "\","
                + "\"sleepTimerActive\":" + timerActive + ","
                + "\"sleepTimerRemainingMs\":" + timerRemain + ","
                + "\"sleepTimerMode\":\"" + jsonEscape(timerMode) + "\","
                + "\"downloading\":" + downloading + ","
                + "\"downloadName\":\"" + jsonEscape(downloadName) + "\","
                + "\"downloadPercent\":" + downloadPercent + ","
                + "\"memUsedMb\":" + memUsedMb + ","
                + "\"memTotalMb\":" + memTotalMb + ","
                + "\"memMaxMb\":" + memMaxMb + ","
                + "\"cpuPercent\":" + cpuPercent + ","
                // 2026-11-28：网页监控的「更新于」靠这个时间戳，没有它就只能显示静态文案
                + "\"ts\":" + System.currentTimeMillis() + ","
                + "\"ts\":" + System.currentTimeMillis()
                + "}";
    }

    private static long sLastCpuJiffies = -1;
    private static long sLastCpuSampleMs = 0;

    /**
     * 进程 CPU 占用百分比（两次采样间隔计算，首帧返回 -1）。
     * 读 /proc/self/stat 的 utime+stime；USER_HZ 按 100 处理（1 jiffy = 10ms）。
     */
    private static int readCpuPercent() {
        try {
            String stat = readOneLine("/proc/self/stat");
            if (stat == null) {
                return -1;
            }
            int close = stat.lastIndexOf(')');
            if (close < 0) {
                return -1;
            }
            String[] parts = stat.substring(close + 2).split("\\s+");
            if (parts.length < 13) {
                return -1;
            }
            long jiffies = Long.parseLong(parts[11]) + Long.parseLong(parts[12]);
            long now = android.os.SystemClock.elapsedRealtime();
            if (sLastCpuJiffies < 0 || now <= sLastCpuSampleMs) {
                sLastCpuJiffies = jiffies;
                sLastCpuSampleMs = now;
                return -1;
            }
            long deltaJ = Math.max(0, jiffies - sLastCpuJiffies);
            long deltaMs = Math.max(1, now - sLastCpuSampleMs);
            sLastCpuJiffies = jiffies;
            sLastCpuSampleMs = now;
            double cpuMs = deltaJ * 10.0;
            return (int) Math.max(0, Math.min(100, Math.round(cpuMs * 100.0 / deltaMs)));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String readOneLine(String path) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader(path))) {
            return br.readLine();
        } catch (Throwable t) {
            return null;
        }
    }

    private void handleInboxList(OutputStream out) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        synchronized (received) {
            for (int i = 0; i < received.size(); i++) {
                ReceivedFile f = received.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"id\":\"").append(f.id).append('"')
                  .append(",\"name\":\"").append(jsonEscape(f.name)).append('"')
                  .append(",\"size\":").append(f.size)
                  .append(",\"from\":\"").append(jsonEscape(f.from)).append('"')
                  .append(",\"time\":").append(f.time)
                  .append('}');
            }
        }
        sb.append(']');
        writeResponse(out, 200, "OK", "application/json; charset=utf-8", sb.toString().getBytes("UTF-8"));
    }

    private void handleInboxDelete(OutputStream out, String id) throws Exception {
        ReceivedFile hit = null;
        synchronized (received) {
            for (int i = 0; i < received.size(); i++) {
                if (received.get(i).id.equals(id)) {
                    hit = received.remove(i);
                    break;
                }
            }
        }
        if (hit != null) {
            try { new File(hit.path).delete(); } catch (Exception ignored) { }
        }
        String json = "{\"ok\":true,\"removed\":" + (hit != null) + "}";
        writeResponse(out, 200, "OK", "application/json; charset=utf-8", json.getBytes("UTF-8"));
    }

    private void handleDownload(OutputStream out, String id) throws Exception {
        ReceivedFile hit = null;
        synchronized (received) {
            for (ReceivedFile f : received) {
                if (f.id.equals(id)) { hit = f; break; }
            }
        }
        if (hit == null) {
            byte[] body = "{\"ok\":false,\"error\":\"not found\"}".getBytes("UTF-8");
            writeResponse(out, 404, "Not Found", "application/json; charset=utf-8", body);
            return;
        }
        File file = new File(hit.path);
        if (!file.exists()) {
            byte[] body = "{\"ok\":false,\"error\":\"file missing\"}".getBytes("UTF-8");
            writeResponse(out, 404, "Not Found", "application/json; charset=utf-8", body);
            return;
        }
        String ascii = hit.name.replaceAll("[^\\x20-\\x7e]", "_");
        String encoded = URLEncoder.encode(hit.name, "UTF-8").replace("+", "%20");
        String disposition = "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
        streamFile(out, file, mimeOf(hit.name), disposition);
    }

    /** 以分块方式把文件写回 socket（避免大文件一次性读入内存）。 */
    private void streamFile(OutputStream out, File file, String contentType, String disposition)
            throws Exception {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(contentType).append("\r\n");
        h.append("Content-Length: ").append(file.length()).append("\r\n");
        h.append("Content-Disposition: ").append(disposition).append("\r\n");
        h.append("Connection: close\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes("ISO-8859-1"));
        byte[] buf = new byte[BUFFER_SIZE];
        try (FileInputStream fis = new FileInputStream(file)) {
            int n;
            while ((n = fis.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        out.flush();
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        switch (ext) {
            case "mp3":  return "audio/mpeg";
            case "wav":  return "audio/wav";
            case "flac": return "audio/flac";
            case "m4a":
            case "aac":  return "audio/mp4";
            case "ogg":  return "audio/ogg";
            case "png":  return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "gif":  return "image/gif";
            case "webp": return "image/webp";
            case "mp4":  return "video/mp4";
            case "txt":  return "text/plain; charset=utf-8";
            case "json": return "application/json";
            default:     return "application/octet-stream";
        }
    }

    private static void writeResponse(OutputStream out, int code, String status,
                                      String contentType, byte[] body) throws Exception {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n");
        h.append("Content-Type: ").append(contentType).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Connection: close\r\n");
        // 2026-11-28：页面/接口一律不缓存 —— 否则手机上会一直跑旧版 HTML（改了也看不到）
        h.append("Cache-Control: no-store, no-cache, must-revalidate\r\n");
        h.append("Pragma: no-cache\r\n");
        // 2026-11-28 组网：网页要跨设备读 /api/devices、/api/status，必须允许跨域
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n");
        h.append("Access-Control-Allow-Headers: Content-Type\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }

    // ==================== 工具方法 ====================

    /** 只取文件名的最后一段，并去掉路径穿越片段。 */
    static String sanitizeFileName(String rawName) {
        String name = rawName == null ? "" : rawName;
        try {
            name = URLDecoder.decode(name, "UTF-8");
        } catch (Exception ignored) {
        }
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replace("..", "").trim();
        if (name.isEmpty()) {
            name = "upload_" + System.currentTimeMillis() + ".mp3";
        }
        return name;
    }

    /** 同名文件不覆盖：加 (1) (2) …… */
    private static synchronized File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    private static String queryValue(String query, String key) {
        if (query.isEmpty()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return "";
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 取本机在局域网里的 IPv4 地址。
     *
     * <p>先问 WifiManager（最贴近「连的那个 WiFi」），拿不到再遍历网卡。</p>
     *
     * @return 形如 {@code 192.168.1.23}；都拿不到返回 null
     */
    public static String getLanAddress(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format(Locale.US, "%d.%d.%d.%d",
                            ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "WifiManager 取地址失败，改用网卡遍历", e);
        }
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "遍历网卡取地址失败", e);
        }
        return null;
    }

    /**
     * 内置回退页：仅在 assets/lan_upload.html 没能注入成功时才用上（极少发生，
     * 比如资源被裁剪）。保持最精简可传即可，不追求美观 —— 真正的美化页在 assets 里。
     *
     * <p>文件仍以「原始字节流」POST 到 /upload，服务端免掉 multipart 解析。</p>
     */
    private static final String FALLBACK_HTML = ""
            + "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>163MusicPlus · 传歌到手表</title>"
            + "<style>body{font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,"
            + "sans-serif;background:#121212;color:#eee;margin:0;padding:24px;line-height:1.6}"
            + "h1{font-size:20px;margin:0 0 4px}.sub{color:#9a9a9a;font-size:13px;"
            + "margin-bottom:20px}.box{border:1px dashed #4a4a4a;border-radius:10px;"
            + "padding:24px;text-align:center}button{background:#39C5BB;color:#1d1d1d;"
            + "border:0;border-radius:8px;padding:10px 18px;font-size:14px;cursor:pointer;"
            + "margin-top:12px}button:disabled{opacity:.5;cursor:default}"
            + "ul{list-style:none;padding:0;margin-top:18px;font-size:13px}"
            + "li{padding:6px 0;border-bottom:1px solid #2a2a2a}"
            + ".ok{color:#7ee2a8}.err{color:#ff8a80}.muted{color:#9a9a9a}</style></head>"
            + "<body><h1>传歌到手表</h1>"
            + "<div class=\"sub\">音乐文件保存到 <code>/sdcard/Music</code>，"
            + "其它文件保存到 <code>/sdcard/TransFiles</code>（与音乐库分开）。</div>"
            + "<div class=\"box\"><input id=\"f\" type=\"file\" accept=\"*/*\" multiple>"
            + "<div><button id=\"b\">开始上传</button></div></div>"
            + "<ul id=\"list\"></ul><script>"
            + "var f=document.getElementById('f'),b=document.getElementById('b'),"
            + "list=document.getElementById('list');"
            + "function one(file){return new Promise(function(res,rej){"
            + "var x=new XMLHttpRequest();"
            + "x.open('POST','/upload?name='+encodeURIComponent(file.name));"
            + "x.setRequestHeader('Content-Type','application/octet-stream');"
            + "var li=document.createElement('li');"
            + "li.textContent=file.name+' … 0%';list.appendChild(li);"
            + "x.upload.onprogress=function(e){if(e.lengthComputable){"
            + "li.textContent=file.name+' … '+Math.round(e.loaded/e.total*100)+'%';}};"
            + "x.onload=function(){if(x.status===200){li.className='ok';"
            + "li.textContent=file.name+' — 已上传';res();}"
            + "else{li.className='err';li.textContent=file.name+' — 失败';rej();}};"
            + "x.onerror=function(){li.className='err';"
            + "li.textContent=file.name+' — 网络错误';rej();};"
            + "x.send(file);});}"
            + "b.onclick=function(){var files=f.files;if(!files.length){"
            + "var li=document.createElement('li');li.className='muted';"
            + "li.textContent='请先选择文件';list.appendChild(li);return;}"
            + "b.disabled=true;var p=Promise.resolve();"
            + "for(var i=0;i<files.length;i++){(function(file){"
            + "p=p.then(function(){return one(file);});})(files[i]);}"
            + "p.then(function(){b.disabled=false;var li=document.createElement('li');"
            + "li.className='ok';li.textContent='全部完成';list.appendChild(li);})"
            + ".catch(function(){b.disabled=false;});};</script></body></html>";
}
