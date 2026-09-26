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
 * 选好音乐文件直接传上来，落盘到 {@code /sdcard/Music}（本机音乐页本来就在扫这个目录，
 * 所以传完不用做任何额外操作就能在列表里看到）。
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
    private volatile int uploadedCount;
    private volatile String lastFileName;
    private final File saveDir;

    /** 美化后的上传页（从 assets 读取注入；为空时回退到内置精简页）。 */
    private String indexHtml;
    /** 已接收文件列表，供「收件箱」面板展示与回下载。单线程处理连接，synchronized 仅作保险。 */
    private final List<ReceivedFile> received = Collections.synchronizedList(new ArrayList<>());
    private long rxSeq = 0;

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

    public File getSaveDir() {
        return saveDir;
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
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
        return indexHtml != null ? indexHtml : FALLBACK_HTML;
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
    }

    // ==================== 请求处理 ====================

    private void acceptLoop() {
        while (running) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                handle(socket);
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "处理连接时出错", e);
                }
            } finally {
                closeQuietly(socket);
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
            } else if ("GET".equalsIgnoreCase(method) && "/api/inbox".equals(path)) {
                handleInboxList(out);
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

    private void handleUpload(Socket socket, InputStream in, OutputStream out, String query, int contentLength)
            throws Exception {
        String name = queryValue(query, "name");
        String safeName = sanitizeFileName(name);

        File dir = saveDir;
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

        // 存下来就登记进本地音乐库缓存，让它立刻可被解析（不必等下一次扫描）
        try {
            Song song = new Song(LocalTracks.idForPath(target.getAbsolutePath()),
                    safeName, "", "");
            song.setUrl(target.getAbsolutePath());
            song.setForceLocalPlayback(true);
            LocalLibraryCache.put(song);
        } catch (Exception ignored) {
        }

        uploadedCount++;
        lastFileName = safeName;
        Log.i(TAG, "收到文件: " + target.getAbsolutePath() + " (" + written + " 字节)");

        // 登记进「收件箱」，前端可在网页上看到、回下载、删除
        String from = (socket.getInetAddress() != null)
                ? socket.getInetAddress().getHostAddress() : "未知设备";
        String rxId = "rx" + (++rxSeq);
        received.add(new ReceivedFile(rxId, safeName, written, System.currentTimeMillis(), from,
                target.getAbsolutePath()));

        String json = "{\"ok\":true,\"name\":\"" + jsonEscape(safeName) + "\",\"size\":" + written + "}";
        writeResponse(out, 200, "OK", "application/json; charset=utf-8", json.getBytes("UTF-8"));
    }

    // ==================== 收件箱（已接收文件）接口 ====================

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
    private static File uniqueFile(File dir, String name) {
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
            + "<title>163音乐Pro · 传歌到手表</title>"
            + "<style>body{font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,"
            + "sans-serif;background:#121212;color:#eee;margin:0;padding:24px;line-height:1.6}"
            + "h1{font-size:20px;margin:0 0 4px}.sub{color:#9a9a9a;font-size:13px;"
            + "margin-bottom:20px}.box{border:1px dashed #4a4a4a;border-radius:10px;"
            + "padding:24px;text-align:center}button{background:#BB86FC;color:#1d1d1d;"
            + "border:0;border-radius:8px;padding:10px 18px;font-size:14px;cursor:pointer;"
            + "margin-top:12px}button:disabled{opacity:.5;cursor:default}"
            + "ul{list-style:none;padding:0;margin-top:18px;font-size:13px}"
            + "li{padding:6px 0;border-bottom:1px solid #2a2a2a}"
            + ".ok{color:#7ee2a8}.err{color:#ff8a80}.muted{color:#9a9a9a}</style></head>"
            + "<body><h1>传歌到手表</h1>"
            + "<div class=\"sub\">文件会保存到手表的 <code>/sdcard/Music</code>，"
            + "传完即可在「本机音乐」里看到。</div>"
            + "<div class=\"box\"><input id=\"f\" type=\"file\" accept=\"audio/*\" multiple>"
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
