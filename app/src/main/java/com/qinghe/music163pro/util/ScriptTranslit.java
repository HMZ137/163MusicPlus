package com.qinghe.music163pro.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 假名 / 西里尔字母的拉丁转写，供**搜索**与**排序**共用。
 *
 * <p>解决两个实际痛点：</p>
 * <ul>
 *   <li>日文曲名/曲师（如「かがやき」「初音ミク」）没法用键盘上的拉丁字母搜 —— 现在输入
 *       {@code k} 能命中「かがやき」（转写成 {@code kagayaki}）；输入 {@code miku} 也能命中
 *       「初音ミク」。</li>
 *   <li>俄文/西里尔曲名（如「Служить России」）同理：输入 {@code s} 或 {@code sluzhit} 都能命中，
 *       反过来输入西里尔字母也能命中拉丁曲名 —— 因为**两边都先转写成拉丁再比对**。</li>
 * </ul>
 *
 * <p><b>为什么自己写表而不是用 ICU 的 Transliterator：</b>手表上体积与启动开销都要省，
 * 而这里只需要「能搜到、排序能归组」这一档精度，几十个字符的映射表足够，且没有额外依赖。</p>
 *
 * <p>转写是**近似**的（尤其西里尔有多种罗马化方案），但对「按关键词找歌」这个用途足够：
 * 关键是辅音骨架要对得上。</p>
 */
public final class ScriptTranslit {

    private ScriptTranslit() {
    }

    /** 平假名（含浊音/半浊音）→ 罗马字。片假名会先按下标偏移折到平假名再查这张表。 */
    private static final String[] HIRA = {
            "あa", "いi", "うu", "えe", "おo",
            "かka", "きki", "くku", "けke", "こko",
            "がga", "ぎgi", "ぐgu", "げge", "ごgo",
            "さsa", "しshi", "すsu", "せse", "そso",
            "ざza", "じji", "ずzu", "ぜze", "ぞzo",
            "たta", "ちchi", "つtsu", "てte", "とto",
            "だda", "ぢji", "づzu", "でde", "どdo",
            "なna", "にni", "ぬnu", "ねne", "のno",
            "はha", "ひhi", "ふfu", "へhe", "ほho",
            "ばba", "びbi", "ぶbu", "べbe", "ぼbo",
            "ぱpa", "ぴpi", "ぷpu", "ぺpe", "ぽpo",
            "まma", "みmi", "むmu", "めme", "もmo",
            "やya", "ゆyu", "よyo",
            "らra", "りri", "るru", "れre", "ろro",
            "わwa", "ゐwi", "ゑwe", "をo", "んn",
            "ぁa", "ぃi", "ぅu", "ぇe", "ぉo",
            "ゃya", "ゅyu", "ょyo", "っ", "ー",
    };

    /** 拗音（两字组合）→ 罗马字。必须先于单字表匹配，否则 きゃ 会变成 kiya。 */
    private static final String[] DIGRAPHS = {
            "きゃkya", "きゅkyu", "きょkyo",
            "ぎゃgya", "ぎゅgyu", "ぎょgyo",
            "しゃsha", "しゅshu", "しょsho",
            "じゃja", "じゅju", "じょjo",
            "ちゃcha", "ちゅchu", "ちょcho",
            "ぢゃja", "ぢゅju", "ぢょjo",
            "にゃnya", "にゅnyu", "にょnyo",
            "ひゃhya", "ひゅhyu", "ひょhyo",
            "びゃbya", "びゅbyu", "びょbyo",
            "ぴゃpya", "ぴゅpyu", "ぴょpyo",
            "みゃmya", "みゅmyu", "みょmyo",
            "りゃrya", "りゅryu", "りょryo",
            "ふぁfa", "ふぃfi", "ふぇfe", "ふぉfo",
            "てぃti", "でぃdi", "ヴvu",
            // 常见的外来语音节：让 ti / di / du / vu / fa / she 这类输入都能命中对应假名
            "てゅtyu", "でゅdyu", "とぅtu", "どぅdu", "でぇde",
            "つぁtsa", "つぃtsi", "つぉtso", "しぇshe", "じぇje", "ちぇche",
            "すぃsi", "ずぃzi", "ほぅho", "うぃwi", "うぇwe", "うぉwo",
            "くぁkwa", "くぉkwo", "ぐぁgwa", "ヴぁva", "ヴぃvi", "ヴぇve", "ヴぉvo",
    };

    /** 西里尔 → 拉丁（俄语为主体，顺带乌克兰/塞尔维亚/保加利亚的常见字母）。 */
    private static final String[] CYRILLIC = {
            "аa", "бb", "вv", "гg", "дd", "еe", "ёe", "жzh", "зz", "иi",
            "йi", "кk", "лl", "мm", "нn", "оo", "пp", "рr", "сs", "тt",
            "уu", "фf", "хkh", "цts", "чch", "шsh", "щshch", "ъ", "ыy", "ь",
            "эe", "юyu", "яya",
            // 乌克兰语
            "іi", "їyi", "єye", "ґg",
            // 塞尔维亚语 / 马其顿语
            "јj", "љlj", "њnj", "ћc", "ђdj", "џdz", "ѓgj", "ќkj", "ѕdz",
    };

    private static final Map<Character, String> HIRA_MAP = new HashMap<>();
    private static final Map<String, String> DIGRAPH_MAP = new HashMap<>();
    private static final Map<Character, String> CYRILLIC_MAP = new HashMap<>();

    static {
        for (String pair : HIRA) {
            HIRA_MAP.put(pair.charAt(0), pair.substring(1));
        }
        for (String triple : DIGRAPHS) {
            DIGRAPH_MAP.put(triple.substring(0, 2), triple.substring(2));
        }
        for (String pair : CYRILLIC) {
            CYRILLIC_MAP.put(pair.charAt(0), pair.substring(1));
        }
    }

    /**
     * 把文本转成「拉丁近似」——用于搜索比对（内部会转小写）。
     *
     * <p>汉字原样保留（拼音不在本类职责内，由 {@link PinyinUtils} 负责首字母）；
     * 假名与西里尔会换成罗马字；其余字符原样小写输出。</p>
     */
    public static String toLatin(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() * 2);
        int i = 0;
        while (i < text.length()) {
            char c = normalizeKana(text.charAt(i));
            // 先看两字组合（拗音）
            if (i + 1 < text.length()) {
                String two = new String(new char[]{c, normalizeKana(text.charAt(i + 1))});
                String digraph = DIGRAPH_MAP.get(two);
                if (digraph != null) {
                    sb.append(digraph);
                    i += 2;
                    continue;
                }
            }
            String hira = HIRA_MAP.get(c);
            if (hira != null) {
                sb.append(hira);
                i++;
                continue;
            }
            String cyr = CYRILLIC_MAP.get(Character.toLowerCase(text.charAt(i)));
            if (cyr == null) {
                cyr = CYRILLIC_MAP.get(text.charAt(i));
            }
            if (cyr != null) {
                sb.append(cyr);
                i++;
                continue;
            }
            sb.append(Character.toLowerCase(text.charAt(i)));
            i++;
        }
        return sb.toString();
    }

    /**
     * 取文本的「拉丁首字母」，用于 A–Z 排序与字母索引；认不出返回 0。
     *
     * <p>跳过前导的非字母（与 {@link PinyinUtils#initialOfWord} 行为一致），
     * 这样「《雪の華》」这类带前缀的曲名也能正确归到 S。</p>
     */
    public static char latinInitialOf(String text) {
        if (text == null) {
            return 0;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            String latin = kanaOrCyrillicSingle(c);
            if (latin != null && !latin.isEmpty()) {
                char first = Character.toUpperCase(latin.charAt(0));
                if (first >= 'A' && first <= 'Z') {
                    return first;
                }
            }
            // 拗音的首字母
            if (i + 1 < text.length()) {
                String two = new String(new char[]{normalizeKana(c), normalizeKana(text.charAt(i + 1))});
                String digraph = DIGRAPH_MAP.get(two);
                if (digraph != null && !digraph.isEmpty()) {
                    char first = Character.toUpperCase(digraph.charAt(0));
                    if (first >= 'A' && first <= 'Z') {
                        return first;
                    }
                }
            }
        }
        return 0;
    }

    /**
     * 拉丁近似的**全部候选写法**（用于搜索比对）。
     *
     * <p>促音符（っ / ッ）的处理是这里唯一有多种合理写法的地方：</p>
     * <ul>
     *   <li>大多数用户按「忽略促音」来打：{@code まっちゃ → matcha}（本方法返回的第一项）；</li>
     *   <li>也有人按罗马字的双写规则来打：{@code まっちゃ → maccha}（第二项）。</li>
     * </ul>
     * <p>所以两种都产出，搜索时任一命中即算命中，避免「明明有这首歌却搜不到」。</p>
     */
    public static java.util.List<String> toLatinVariants(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String base = toLatin(text);
        if (base == null) {
            return out;
        }
        out.add(base);
        String doubled = toLatinWithSokuonDoubling(text);
        if (doubled != null && !doubled.isEmpty() && !doubled.equals(base)) {
            out.add(doubled);
        }
        return out;
    }

    /** 促音符写作「重复后续辅音」的罗马字（っ → 下一音的辅音字母）。 */
    private static String toLatinWithSokuonDoubling(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = normalizeKana(text.charAt(i));
            if (c == 'っ' || c == 'ッ') {
                // 取后一个音的罗马字首字母补一次（っ 后面没有音就直接忽略）
                if (i + 1 < text.length()) {
                    String next = singleRomaji(normalizeKana(text.charAt(i + 1)));
                    if (next != null && !next.isEmpty()) {
                        sb.append(next.charAt(0));
                    }
                }
                i++;
                continue;
            }
            if (i + 1 < text.length()) {
                String two = new String(new char[]{c, normalizeKana(text.charAt(i + 1))});
                String digraph = DIGRAPH_MAP.get(two);
                if (digraph != null) {
                    sb.append(digraph);
                    i += 2;
                    continue;
                }
            }
            String single = singleRomaji(c);
            if (single == null) {
                sb.append(Character.toLowerCase(text.charAt(i)));
            } else {
                sb.append(single);
            }
            i++;
        }
        return sb.toString();
    }

    /** 单个假名的罗马字；不是假名返回 null。 */
    private static String singleRomaji(char hira) {
        String h = HIRA_MAP.get(hira);
        if (h != null) {
            return h;
        }
        String cyr = CYRILLIC_MAP.get(Character.toLowerCase(hira));
        return cyr;
    }

    /** 单个假名/西里尔字符的拉丁近似；不是这两类字时返回 {@code null}。 */
    private static String kanaOrCyrillicSingle(char c) {
        String hira = HIRA_MAP.get(normalizeKana(c));
        if (hira != null) {
            return hira;
        }
        return CYRILLIC_MAP.get(Character.toLowerCase(c));
    }

    /** 片假名按下标偏移折成平假名；其余字符原样返回。 */
    private static char normalizeKana(char c) {
        if (c >= 0x30A1 && c <= 0x30F6) {
            return (char) (c - 0x60);
        }
        return c;
    }
}
