package com.qinghe.music163pro.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.qinghe.music163pro.model.AiLyricResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词的 AI 分析与翻译（DeepSeek）。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>先查本地缓存（键 = 歌曲键 + 歌词哈希）：命中直接回调，<b>不消耗 token</b>；</li>
 *   <li>未命中 → 按 {@link #LINES_PER_BATCH} 行一批请求；</li>
 *   <li><b>记忆轮数衔接</b>：从第 2 批起，把前面几批累积的「术语表 + 风格说明」作为额外的一轮
 *       用户消息带进去（{@link #MAX_MEMORY_BATCHES} 轮以内），保证同一术语/人名前后译法一致；</li>
 *   <li>合并各批结果 → 写缓存 → 回调。</li>
 * </ol>
 *
 * <p>失败（无 Key / 网络错 / JSON 解析失败）只回调错误，不抛异常、不影响歌词正常显示。</p>
 */
public final class AiLyricAnalyzer {

    private static final String TAG = "AiLyricAnalyzer";

    /** 每批最多多少行（控制单次 token）。 */
    private static final int LINES_PER_BATCH = 40;
    /** 最多带几批记忆（轮数上限，避免上下文无限膨胀）。 */
    private static final int MAX_MEMORY_BATCHES = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 用于取消过期请求：切歌后旧请求的结果直接丢弃。 */
    private static volatile int generation = 0;

    private AiLyricAnalyzer() {
    }

    public interface Callback {
        /** @param fromCache true = 命中本地缓存（未消耗 token） */
        void onResult(AiLyricResult result, boolean fromCache);

        void onError(String message);
    }

    /** 取消正在进行/即将回调的分析（切歌时调用）。 */
    public static void cancel() {
        generation++;
    }

    /**
     * 分析一首歌的歌词（优先缓存）。
     *
     * @param songKey    缓存键：网易云 ID / 本地路径派生键 / bvid
     * @param lyricLines 原文歌词逐行文本（顺序即行号）
     * @param officialTranslations 官方翻译行（可为空；有则让 AI 参考沿用）
     */
    public static void analyze(final Context context, final String songKey,
                               final String songTitle, final String artist, final String album,
                               final List<String> lyricLines,
                               final List<String> officialTranslations,
                               final Callback callback) {
        if (lyricLines == null || lyricLines.isEmpty()) {
            callback.onError("没有可分析的歌词");
            return;
        }
        final Context appContext = context.getApplicationContext();
        final String lyricsKey = joinLyrics(lyricLines);

        // 1) 缓存优先
        final AiLyricResult cached = AiLyricResult.fromJson(
                AiLyricCache.read(songKey, lyricsKey));
        if (cached != null) {
            MusicLog.i(TAG, "命中 AI 歌词缓存: " + AiLyricCache.fileFor(songKey, lyricsKey));
            MAIN.post(() -> callback.onResult(cached, true));
            return;
        }

        if (!AiLyricSettings.hasApiKey(appContext)) {
            callback.onError("未填写 DeepSeek API Key");
            return;
        }

        final int myGeneration = ++generation;
        MusicLog.i(TAG, "开始 AI 分析: " + songKey + " 行数=" + lyricLines.size()
                + " 缓存路径=" + AiLyricCache.fileFor(songKey, lyricsKey));

        // 2) 分批请求
        final int totalBatches = (lyricLines.size() + LINES_PER_BATCH - 1) / LINES_PER_BATCH;
        final AiLyricResult merged = new AiLyricResult();
        merged.songId = songKey;
        merged.model = AiLyricSettings.getModel(appContext);
        merged.createdAt = System.currentTimeMillis();
        final List<String> memory = new ArrayList<>();   // 累积的术语记忆（每条 = 一批的摘要）

        requestBatch(appContext, songKey, songTitle, artist, album, lyricLines,
                officialTranslations, merged, memory, 0, totalBatches, lyricsKey,
                myGeneration, callback);
    }

    private static void requestBatch(final Context appContext, final String songKey,
                                     final String songTitle, final String artist, final String album,
                                     final List<String> lyricLines,
                                     final List<String> officialTranslations,
                                     final AiLyricResult merged, final List<String> memory,
                                     final int batchIndex, final int totalBatches,
                                     final String lyricsKey, final int myGeneration,
                                     final Callback callback) {
        int from = batchIndex * LINES_PER_BATCH;
        int to = Math.min(lyricLines.size(), from + LINES_PER_BATCH);

        List<String> turns = new ArrayList<>();
        // 记忆轮：把此前批次的术语表带上（最多 MAX_MEMORY_BATCHES 条）
        int memFrom = Math.max(0, memory.size() - MAX_MEMORY_BATCHES);
        for (int i = memFrom; i < memory.size(); i++) {
            turns.add("【前文记忆】\n" + memory.get(i));
        }
        // 本轮内容轮
        turns.add(buildBatchPrompt(songTitle, artist, album, lyricLines,
                officialTranslations, from, to, totalBatches, batchIndex));

        AiLyricApi.chat(appContext, SYSTEM_PROMPT, turns.toArray(new String[0]),
                new AiLyricApi.Callback() {
                    @Override
                    public void onResult(AiLyricApi.Reply reply) {
                        if (myGeneration != generation) {
                            return;   // 已切歌，丢弃
                        }
                        merged.tokens += reply.totalTokens;
                        ParsedBatch parsed = parseBatch(reply.content, lyricLines, from, to);
                        if (parsed == null) {
                            callback.onError("AI 返回内容无法解析（第 " + (batchIndex + 1) + " 批）");
                            return;
                        }
                        for (AiLyricResult.Line line : parsed.lines) {
                            AiLyricResult.Line existing = merged.lineAt(line.index);
                            if (existing == null) {
                                merged.lines.add(line);
                            } else {
                                existing.translation = line.translation;
                                existing.tokens.clear();
                                existing.tokens.addAll(line.tokens);
                            }
                        }
                        if (!parsed.glossary.isEmpty()) {
                            memory.add(parsed.glossary);
                        }

                        if (batchIndex + 1 < totalBatches) {
                            requestBatch(appContext, songKey, songTitle, artist, album, lyricLines,
                                    officialTranslations, merged, memory, batchIndex + 1,
                                    totalBatches, lyricsKey, myGeneration, callback);
                            return;
                        }

                        // 3) 收尾：写缓存 → 回调
                        boolean written = AiLyricCache.write(songKey, lyricsKey, merged.toJson());
                        MusicLog.i(TAG, "AI 分析完成: " + songKey + " 行=" + merged.lines.size()
                                + " tokens=" + merged.tokens + " 缓存写入=" + written);
                        MAIN.post(() -> callback.onResult(merged, false));
                    }

                    @Override
                    public void onError(String message) {
                        if (myGeneration != generation) {
                            return;
                        }
                        callback.onError(message);
                    }
                });
    }

    // ==================== Prompt ====================

    private static final String SYSTEM_PROMPT =
            "你是歌词翻译助手，为外语歌词做「词性标注 + 翻译」。只输出 JSON，不要任何解释或 markdown。\n"
                    + "输出格式：\n"
                    + "{\"lines\":[{\"i\":行号,\"translation\":\"该项目整行译文\","
                    + "\"tokens\":[{\"t\":\"原文中的词或短语（必须与原文逐字一致）\","
                    + "\"c\":\"词类\",\"tr\":\"该词在译文中的对应词（必须出现在 translation 里，可留空）\"}]}],"
                    + "\"glossary\":[{\"src\":\"原文术语\",\"dst\":\"统一译名\"}]}\n"
                    + "要求：\n"
                    + "1) c 只能取这 8 个值之一：pron(代词) verb(动词) noun(名词) adj(形容词) adv(副词) "
                    + "prep(介词/连词) phrase(短语/固定搭配/习语) other(助词、语气词、数字等)；\n"
                    + "2) 只为「值得学的实词、代词、短语」输出 tokens，一行 2~8 个，不要逐字标注虚词；"
                    + "短语块最多 4 个词、不超过 18 个字符，更长的请拆成多个较小的块（整块过长会被截断）；\n"
                    + "3) t 必须是原文的子串（不要改写大小写、不要加标点）；\n"
                    + "4) 若提供了官方翻译，translation 必须沿用官方译文；否则自行翻译成简体中文；\n"
                    + "5) glossary 收录本批出现的人名、专有名词、反复出现的术语，便于后文保持一致；\n"
                    + "6) tr 必须是 translation 里<b>连续出现的原文片段</b>（可以比原词短，但不能改写用词、不能加字），"
                    + "例如 translation 为「用更宏观的视野看这世界」时，look at things 的 tr 可写「看这世界」或「视野」；"
                    + "tr 要给出「该词在译文中的对应片段」，允许意译/艺术化处理：只要译文中确实存在与它语义"
                    + "对应的片段（哪怕是比喻、改写），就把那段文字原样写进 tr（必须与 translation 逐字一致）；"
                    + "若译文完全无法对应（被删省、打乱重组、只剩意象），tr 留空字符串 —— 前端不会在译文里标注它，"
                    + "但原文该词仍会着色。宁可留空，也不要硬凑一个对不上的词。";

    private static String buildBatchPrompt(String songTitle, String artist, String album,
                                           List<String> lyricLines,
                                           List<String> officialTranslations,
                                           int from, int to, int totalBatches, int batchIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("歌曲信息：").append(songTitle == null ? "" : songTitle);
        if (artist != null && !artist.isEmpty()) {
            sb.append(" / ").append(artist);
        }
        if (album != null && !album.isEmpty()) {
            sb.append(" / 专辑《").append(album).append("》");
        }
        sb.append('\n');
        if (totalBatches > 1) {
            sb.append("这是第 ").append(batchIndex + 1).append(" / ").append(totalBatches)
                    .append(" 批，请与前面批次的译名保持一致。\n");
        }
        sb.append("歌词（行号: 原文");
        if (officialTranslations != null && !officialTranslations.isEmpty()) {
            sb.append(" ; 官方译文");
        }
        sb.append("）：\n");
        for (int i = from; i < to; i++) {
            sb.append(i).append(": ").append(lyricLines.get(i));
            if (officialTranslations != null && i < officialTranslations.size()) {
                String off = officialTranslations.get(i);
                if (off != null && !off.isEmpty()) {
                    sb.append(" ; ").append(off);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ==================== 解析 ====================

    /** 单批解析结果。 */
    private static final class ParsedBatch {
        final List<AiLyricResult.Line> lines = new ArrayList<>();
        String glossary = "";
    }

    private static ParsedBatch parseBatch(String content, List<String> lyricLines, int from, int to) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        try {
            String json = content.trim();
            // 容错：模型偶尔会带 ```json 包裹
            if (json.startsWith("```")) {
                int nl = json.indexOf('\n');
                int end = json.lastIndexOf("```");
                if (nl > 0 && end > nl) {
                    json = json.substring(nl + 1, end);
                }
            }
            JSONObject root = new JSONObject(json);
            ParsedBatch parsed = new ParsedBatch();

            JSONArray arr = root.optJSONArray("lines");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject l = arr.optJSONObject(i);
                    if (l == null) {
                        continue;
                    }
                    int index = l.optInt("i", -1);
                    if (index < from || index >= to) {
                        continue;   // 越界的行丢弃，避免错位
                    }
                    // 行文本以本地歌词为准（模型可能不回 text 或回得不完整）
                    String realText = (index >= 0 && index < lyricLines.size())
                            ? lyricLines.get(index) : l.optString("text", "");
                    AiLyricResult.Line line = new AiLyricResult.Line(index,
                            realText, l.optString("translation", ""));
                    JSONArray tk = l.optJSONArray("tokens");
                    if (tk != null) {
                        for (int j = 0; j < tk.length(); j++) {
                            JSONObject t = tk.optJSONObject(j);
                            if (t == null) {
                                continue;
                            }
                            String text = t.optString("t", "");
                            if (text.isEmpty()) {
                                continue;
                            }
                            line.tokens.add(new AiLyricResult.Token(text,
                                    AiLyricPalette.normalize(t.optString("c", "other")),
                                    t.optString("tr", "")));
                        }
                    }
                    parsed.lines.add(line);
                }
            }

            JSONArray glossary = root.optJSONArray("glossary");
            if (glossary != null && glossary.length() > 0) {
                StringBuilder sb = new StringBuilder("已知术语（后文必须沿用）：");
                for (int i = 0; i < glossary.length(); i++) {
                    JSONObject g = glossary.optJSONObject(i);
                    if (g == null) {
                        continue;
                    }
                    sb.append(g.optString("src", "")).append(" = ")
                            .append(g.optString("dst", "")).append("；");
                }
                parsed.glossary = sb.toString();
            }
            return parsed;
        } catch (Exception e) {
            MusicLog.w(TAG, "解析 AI 返回失败", e);
            return null;
        }
    }

    // ==================== 工具 ====================

    /** 歌词文本 → 缓存哈希用的键（行间用 \n 连接）。 */
    public static String joinLyrics(List<String> lyricLines) {
        if (lyricLines == null || lyricLines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lyricLines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lyricLines.get(i) == null ? "" : lyricLines.get(i));
        }
        return sb.toString();
    }
}
