package com.qinghe.music163pro.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一首歌的 AI 歌词分析结果（词性标注 + 译文）。
 *
 * <p>结构对应缓存 JSON：
 * <pre>
 * {
 *   "v": 1, "songId": "123", "model": "deepseek-flash", "createdAt": 1690000000000,
 *   "tokens": 1234,
 *   "lines": [
 *     { "i": 0, "text": "Вместо тепла — зелень стекла",
 *       "translation": "代替温暖的是玻璃的绿",
 *       "tokens": [ {"t":"Вместо","c":"prep","tr":"代替"} ] }
 *   ]
 * }
 * </pre>
 * {@code i} 是歌词行号（与解析出来的 LRC 行一一对应）；{@code t}/{@code tr} 分别是原文与译文里的
 * 子串，渲染时按子串定位上色。</p>
 */
public class AiLyricResult {

    public static final int VERSION = 1;

    public String songId = "";
    public String model = "";
    public long createdAt;
    public int tokens;

    public final List<Line> lines = new ArrayList<>();

    /** 单行：原文 + 译文 + 词块。 */
    public static class Line {
        public int index;
        public String text = "";
        public String translation = "";
        public final List<Token> tokens = new ArrayList<>();

        public Line() {
        }

        public Line(int index, String text, String translation) {
            this.index = index;
            this.text = text == null ? "" : text;
            this.translation = translation == null ? "" : translation;
        }
    }

    /** 词块：原文子串 + 词类 + 对应译文子串。 */
    public static class Token {
        public String text = "";
        public String cls = "other";
        public String trans = "";

        public Token() {
        }

        public Token(String text, String cls, String trans) {
            this.text = text == null ? "" : text;
            this.cls = cls == null ? "other" : cls;
            this.trans = trans == null ? "" : trans;
        }
    }

    /** 按歌词行号取（没有返回 null）。 */
    public Line lineAt(int index) {
        for (Line line : lines) {
            if (line.index == index) {
                return line;
            }
        }
        return null;
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("v", VERSION);
            root.put("songId", songId == null ? "" : songId);
            root.put("model", model == null ? "" : model);
            root.put("createdAt", createdAt);
            root.put("tokens", tokens);

            JSONArray arr = new JSONArray();
            for (Line line : lines) {
                JSONObject l = new JSONObject();
                l.put("i", line.index);
                l.put("text", line.text);
                l.put("translation", line.translation);
                JSONArray tk = new JSONArray();
                for (Token token : line.tokens) {
                    JSONObject t = new JSONObject();
                    t.put("t", token.text);
                    t.put("c", token.cls);
                    t.put("tr", token.trans);
                    tk.put(t);
                }
                l.put("tokens", tk);
                arr.put(l);
            }
            root.put("lines", arr);
            return root.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static AiLyricResult fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("v", 0) != VERSION) {
                return null;
            }
            AiLyricResult result = new AiLyricResult();
            result.songId = root.optString("songId", "");
            result.model = root.optString("model", "");
            result.createdAt = root.optLong("createdAt", 0);
            result.tokens = root.optInt("tokens", 0);

            JSONArray arr = root.optJSONArray("lines");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject l = arr.optJSONObject(i);
                    if (l == null) {
                        continue;
                    }
                    Line line = new Line(l.optInt("i", i), l.optString("text", ""),
                            l.optString("translation", ""));
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
                            line.tokens.add(new Token(text, t.optString("c", "other"),
                                    t.optString("tr", "")));
                        }
                    }
                    if (!line.text.isEmpty() || !line.tokens.isEmpty()
                            || !line.translation.isEmpty()) {
                        result.lines.add(line);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }
}
