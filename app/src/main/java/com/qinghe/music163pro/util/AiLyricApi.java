package com.qinghe.music163pro.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DeepSeek Chat Completions 的最小客户端（HTTP 直连，不引第三方库）。
 *
 * <p>职责：发一条（或多轮）对话请求，拿回模型输出的文本与 token 用量。
 * 设置页的「测试连接」与歌词分析（{@link AiLyricAnalyzer}）都走这里，
 * 保证密钥、模型、超时、用量统计只有一处实现。</p>
 *
 * <p>密钥/模型由 {@link AiLyricSettings} 提供；每次调用都会累加用量统计。</p>
 */
public final class AiLyricApi {

    private static final String TAG = "AiLyricApi";
    private static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AiLyricApi() {
    }

    /** 一次对话的结果。 */
    public static final class Reply {
        public final String content;
        public final int totalTokens;

        Reply(String content, int totalTokens) {
            this.content = content;
            this.totalTokens = totalTokens;
        }
    }

    public interface Callback {
        void onResult(Reply reply);

        void onError(String message);
    }

    /** 单轮请求。 */
    public static void chatOnce(Context context, String systemPrompt, String userPrompt,
                                Callback callback) {
        chat(context, systemPrompt, new String[]{userPrompt}, callback);
    }

    /**
     * 多轮请求（分批分析时，后一批把前面的术语/风格记忆放进前面的对话轮次里）。
     *
     * @param userTurns 依次发送的用户消息；最后一条是本次要处理的内容
     */
    public static void chat(final Context context, final String systemPrompt,
                            final String[] userTurns, final Callback callback) {
        final Context appContext = context.getApplicationContext();
        final String apiKey = AiLyricSettings.getApiKey(appContext);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            MAIN.post(() -> callback.onError("未填写 DeepSeek API Key"));
            return;
        }
        final String model = AiLyricSettings.getModel(appContext);

        EXECUTOR.execute(() -> {
            HttpURLConnection conn = null;
            try {
                JSONArray messages = new JSONArray();
                if (systemPrompt != null && !systemPrompt.isEmpty()) {
                    messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
                }
                if (userTurns != null) {
                    for (String turn : userTurns) {
                        messages.put(new JSONObject().put("role", "user").put("content", turn));
                    }
                }

                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("messages", messages);
                body.put("temperature", 0.2);
                body.put("stream", false);
                body.put("response_format", new JSONObject().put("type", "json_object"));

                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                    os.flush();
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String response = readAll(is);

                if (code < 200 || code >= 300) {
                    String message = "HTTP " + code;
                    try {
                        JSONObject err = new JSONObject(response);
                        JSONObject error = err.optJSONObject("error");
                        if (error != null) {
                            message = code + ": " + error.optString("message", message);
                        }
                    } catch (Exception ignored) {
                    }
                    final String finalMessage = message;
                    MAIN.post(() -> callback.onError(finalMessage));
                    return;
                }

                JSONObject json = new JSONObject(response);
                JSONArray choices = json.optJSONArray("choices");
                String content = "";
                if (choices != null && choices.length() > 0) {
                    JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                    if (msg != null) {
                        content = msg.optString("content", "");
                    }
                }
                int tokens = 0;
                JSONObject usage = json.optJSONObject("usage");
                if (usage != null) {
                    tokens = usage.optInt("total_tokens", 0);
                }
                AiLyricSettings.addUsage(appContext, tokens);

                final String finalContent = content;
                final int finalTokens = tokens;
                MAIN.post(() -> callback.onResult(new Reply(finalContent, finalTokens)));
            } catch (Exception e) {
                MusicLog.w(TAG, "DeepSeek 请求失败", e);
                final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                MAIN.post(() -> callback.onError(message));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /** 设置页「测试连接」：一次最小请求，验证 Key / 模型 / 网络。 */
    public static void testConnection(Context context, Callback callback) {
        chatOnce(context,
                "你是连通性测试助手，只按要求返回 JSON。",
                "请只返回 {\"ok\":true}，不要任何解释。",
                callback);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
