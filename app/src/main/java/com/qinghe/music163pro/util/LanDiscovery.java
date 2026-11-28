package com.qinghe.music163pro.util;

import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局域网设备发现（2026-11-28，组网/互传用）。
 *
 * <p>协议极其简单，不引入「组主」：</p>
 * <ul>
 *   <li>每个开着接收服务的手表在 UDP {@value #PORT} 上监听；</li>
 *   <li>发送端广播一行 {@code 163MUSICPLUS_LAN/1 DISCOVER}；</li>
 *   <li>接收端看到后向来源地址单播回一行 JSON：
 *       <code>{"name":"手表型号","port":8080,"rx":true}</code>；</li>
 *   <li>发送端收集 {@value #SCAN_MS}ms 内的应答，按 IP 去重。</li>
 * </ul>
 *
 * <p>网页端不能发 UDP，所以它通过手表的 {@code /api/devices} 拿同一份扫描结果 ——
 * 由手表代扫。</p>
 */
public final class LanDiscovery {

    private static final String TAG = "LanDiscovery";

    /** 发现用的 UDP 端口。 */
    public static final int PORT = 45871;
    private static final String MAGIC = "163MUSICPLUS_LAN/1";
    private static final String DISCOVER = MAGIC + " DISCOVER";
    /** 单次扫描收集应答的时长。 */
    private static final int SCAN_MS = 1200;

    /** 一台被发现的对端。 */
    public static class Peer {
        public final String name;
        public final String ip;
        public final int port;
        public final long lastSeen;

        public Peer(String name, String ip, int port, long lastSeen) {
            this.name = name;
            this.ip = ip;
            this.port = port;
            this.lastSeen = lastSeen;
        }

        public String url() {
            return "http://" + ip + ":" + port;
        }
    }

    private LanDiscovery() {
    }

    /** 本机在网络上显示的名字。 */
    public static String selfName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        return model.isEmpty() ? "163MusicPlus 手表" : model;
    }

    /**
     * 广播扫描一次（阻塞，最长 {@link #SCAN_MS}ms）。
     *
     * <p>必须放在后台线程调用。</p>
     */
    public static List<Peer> scan(int myHttpPort, String selfIp) {
        Map<String, Peer> found = new LinkedHashMap<>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(300);

            byte[] probe = DISCOVER.getBytes("UTF-8");
            // 同时发受限广播与 255.255.255.255：部分 ROM 只认其中一种
            for (String target : new String[]{"255.255.255.255"}) {
                try {
                    socket.send(new DatagramPacket(probe, probe.length,
                            InetAddress.getByName(target), PORT));
                } catch (Exception e) {
                    Log.w(TAG, "广播失败: " + target + " " + e);
                }
            }

            long deadline = System.currentTimeMillis() + SCAN_MS;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(resp);
                } catch (SocketTimeoutException te) {
                    continue;
                }
                String text = new String(resp.getData(), 0, resp.getLength(), "UTF-8").trim();
                if (!text.startsWith("{")) {
                    continue;
                }
                try {
                    JSONObject json = new JSONObject(text);
                    String ip = resp.getAddress().getHostAddress();
                    int port = json.optInt("port", 0);
                    String name = json.optString("name", ip);
                    if (port <= 0) {
                        continue;
                    }
                    // 过滤自己：同 IP 且同端口（selfIp 由调用方传入，拿不到时不建议过滤）
                    if (selfIp != null && selfIp.equals(ip) && port == myHttpPort) {
                        continue;
                    }
                    found.put(ip + ":" + port, new Peer(name, ip, port, System.currentTimeMillis()));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "扫描失败", e);
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return new ArrayList<>(found.values());
    }

    /**
     * 应答器：在接收服务运行期间常驻，收到 DISCOVER 就回自己的信息。
     */
    public static final class Responder {
        private volatile boolean running;
        private DatagramSocket socket;
        private Thread thread;

        public void start(final int httpPort) {
            if (running) {
                return;
            }
            running = true;
            thread = new Thread(() -> {
                try {
                    socket = new DatagramSocket(null);
                    socket.setReuseAddress(true);
                    socket.setBroadcast(true);
                    socket.bind(new InetSocketAddress(PORT));
                    socket.setSoTimeout(1000);
                    byte[] buf = new byte[512];
                    while (running) {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        try {
                            socket.receive(pkt);
                        } catch (SocketTimeoutException te) {
                            continue;
                        }
                        String text = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8").trim();
                        if (!DISCOVER.equals(text)) {
                            continue;
                        }
                        try {
                            JSONObject reply = new JSONObject();
                            reply.put("name", selfName());
                            reply.put("port", httpPort);
                            reply.put("rx", true);
                            byte[] out = reply.toString().getBytes("UTF-8");
                            socket.send(new DatagramPacket(out, out.length,
                                    pkt.getAddress(), pkt.getPort()));
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "发现应答器启动失败: " + e);
                }
            }, "lan-discovery");
            thread.setDaemon(true);
            thread.start();
        }

        public void stop() {
            running = false;
            if (socket != null) {
                socket.close();
                socket = null;
            }
            if (thread != null) {
                thread.interrupt();
                thread = null;
            }
        }
    }
}
