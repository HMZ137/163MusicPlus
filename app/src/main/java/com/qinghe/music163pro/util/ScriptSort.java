package com.qinghe.music163pro.util;

import java.text.Collator;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 本地音乐的**排序口径**：先按「文字」分成几大段，段内再用各自的字母表排。
 *
 * <p>为什么要这么改：以前是把假名、西里尔统统转写成拉丁再按 A–Z 排，结果
 * 俄文 П、日文 ゴ / お、拉丁 S 会落到同一段里 —— 不同文字混着排，既难找也难看。</p>
 *
 * <p>现在的规则（用户拍板）：</p>
 * <ol>
 *   <li>段顺序固定：**拉丁 → 汉字 → 假名 → 西里尔 → 其它（#）**；</li>
 *   <li>汉字段：**拼音 + 声调**（ICU 的 zh 排序规则即拼音序，声调作为次级差别参与比较）；</li>
 *   <li>假名段：**五十音顺**（浊音紧跟清音，拗音 / 促音 / 长音符放最后）；</li>
 *   <li>西里尔段：**西里尔字母表顺序**（不再转写成拉丁）；</li>
 *   <li>拉丁段：拉丁字母序；</li>
 *   <li>开头是**数字或符号**的（以及空串）单独归到 `#` 段，排最后 —— 不再"跳过符号"。</li>
 * </ol>
 */
public final class ScriptSort {

    public static final int SCRIPT_LATIN = 0;
    public static final int SCRIPT_HAN = 1;
    public static final int SCRIPT_KANA = 2;
    public static final int SCRIPT_CYRILLIC = 3;
    public static final int SCRIPT_OTHER = 4;

    private static final Collator LATIN = Collator.getInstance(Locale.ENGLISH);
    /** 汉字段：CLDR zh 的排序规则就是拼音序，声调是次级差别，默认 strength 下会参与比较。 */
    private static final Collator HAN = Collator.getInstance(Locale.CHINA);
    /** `#` 段内部：没有字母表可言，按码点排，保证顺序稳定即可。 */
    private static final Collator RAW = Collator.getInstance(Locale.ROOT);

    /** 五十音顺：清音 → 浊音/半浊音 → 拗音/促音/长音符。 */
    private static final String GOJUON = "あいうゔえお"
            + "かがきぎくぐけげこご"
            + "さざしじすずせぜそぞ"
            + "ただちぢつづてでとど"
            + "なにぬねの"
            + "はばぱひびぴふぶぷへべぺほぼぽ"
            + "まみむめも"
            + "やゆよ"
            + "らりるれろ"
            + "わをん"
            + "ぁぃぅぇぉゃゅょっゎーゐゑ";

    private static final String CYRILLIC_ORDER = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";

    private static final Map<Character, Integer> KANA_RANK = new HashMap<>();
    private static final Map<Character, Integer> CYRILLIC_RANK = new HashMap<>();
    /** 假名 → 所属「行」的头一个假名，用于字母索引分组（が 归入 か 行）。 */
    private static final Map<Character, Character> KANA_ROW_HEAD = new HashMap<>();

    static {
        for (int i = 0; i < GOJUON.length(); i++) {
            KANA_RANK.put(GOJUON.charAt(i), i);
        }
        for (int i = 0; i < CYRILLIC_ORDER.length(); i++) {
            CYRILLIC_RANK.put(CYRILLIC_ORDER.charAt(i), i);
        }
        fillRow("あいうえおぁぃぅぇぉ", 'あ');
        fillRow("かがきぎくぐけげこご", 'か');
        fillRow("さざしじすずせぜそぞ", 'さ');
        fillRow("ただちぢつづてでとどっ", 'た');
        fillRow("なにぬねの", 'な');
        fillRow("はばぱひびぴふぶぷへべぺほぼぽ", 'は');
        fillRow("まみむめも", 'ま');
        fillRow("やゆよゃゅょ", 'や');
        fillRow("らりるれろ", 'ら');
        fillRow("わをゎゐゑ", 'わ');
        KANA_ROW_HEAD.put('ん', 'ん');
        KANA_ROW_HEAD.put('ゔ', 'う');
        KANA_ROW_HEAD.put('ー', 'ー');
    }

    private static void fillRow(String kana, char head) {
        for (int i = 0; i < kana.length(); i++) {
            KANA_ROW_HEAD.put(kana.charAt(i), head);
        }
    }

    private ScriptSort() {
    }

    // ==================== 文字判定 ====================

    /** 文本首字符（跳过空白）所属的文字；空串 / 只有空白返回 {@link #SCRIPT_OTHER}。 */
    public static int scriptOf(String text) {
        if (text == null) {
            return SCRIPT_OTHER;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (isLatin(c)) return SCRIPT_LATIN;
            if (isHan(c)) return SCRIPT_HAN;
            if (isKana(c)) return SCRIPT_KANA;
            if (isCyrillic(c)) return SCRIPT_CYRILLIC;
            // 数字与符号：按用户定的口径，直接归 # 段（不再往后找第一个文字）
            return SCRIPT_OTHER;
        }
        return SCRIPT_OTHER;
    }

    public static boolean isLatin(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    public static boolean isHan(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    public static boolean isKana(char c) {
        return (c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF);
    }

    public static boolean isCyrillic(char c) {
        return c >= 0x0400 && c <= 0x04FF;
    }

    // ==================== 排序 ====================

    /**
     * 完整的排序规则（用户拍板的最终口径）。
     *
     * <p>核心是**只有一个比较器**：所有文字都先转成自己的「字母串」，再用**统一的字母序**比。
     * 西里尔并不是被单独隔离，它用自己的字母参与混排 —— 只是在统一字母表里西里尔字母排在拉丁字母之前，
     * 所以西里尔标题自然落在前面。</p>
     *
     * <p>字母串的来源：</p>
     * <ul>
     *   <li>西里尔 → 西里尔字母本身（不转拉丁）；</li>
     *   <li>拉丁 → 拉丁字母本身；</li>
     *   <li>假名 → 前两个假名的罗马字；</li>
     *   <li>汉字 → 前两个汉字的**完整拼音（带声调数字）**，如 {@code 雪降 → "xue3 jiang4"}；</li>
     *   <li>数字 / 符号开头 → 排在最后。</li>
     * </ul>
     *
     * <p>统一字母序的排位（越小越靠前）：西里尔字母 100+ → 拉丁字母 1000+ → 数字 2000+ → 其它 3000+。
     * 汉字内部再依次用：**拼音 → 声调 → 汉字本身**。</p>
     */
    /**
     * 完整的排序规则（用户拍板）：**按文字分组，组内各自用自己的字母表**。
     *
     * <p>段顺序固定：拉丁 → 汉字 → 假名 → 西里尔 → 其它（符号/数字）。</p>
     *
     * <p>段内依据：</p>
     * <ul>
     *   <li>拉丁：拉丁字母序；</li>
     *   <li>汉字：**完整拼音（带声调数字）→ 声调 → 汉字本身**（例：{@code 雪降 → "xue3 jiang4"}）；</li>
     *   <li>假名：**五十音顺**，「基本假名 + 小假名」算一个音单位（ヴ 在 う 后、ティ 在 て 后、ドゥ 在 ど 后），促音符不占位；</li>
     *   <li>西里尔：**西里尔字母表**顺序（不再转拉丁）；</li>
     *   <li>其它：原样比较，排最后。</li>
     * </ul>
     */
    /**
     * 完整的排序规则（用户真实口径）：**西里尔参与混排，且排在前面**。
     *
     * <p>所有文字都先转成自己的「字母串」，再用**同一个比较器**比 —— 不是按文字分段隔离。
     * 西里尔用的是西里尔字母本身（不转拉丁）；西里尔字母在统一字母表里排在拉丁字母之前，
     * 所以西里尔标题自然落在前面，段内按西里尔字母表排。</p>
     *
     * <p>字母串来源：西里尔→西里尔字母；拉丁→拉丁字母；假名→前两个假名的罗马字；
     * 汉字→前两个汉字的**完整拼音（带声调数字）**，如 {@code 雪降 → "xue3 jiang4"}。
     * 排序效果就是混在一起的：{@code Ka1、西里尔K1、カ1、Ka2、西里尔K2、カ2}。</p>
     *
     * <p>统一字母序的排位（越小越靠前）：西里尔字母 100+ → 拉丁字母 1000+ → 数字 2000+ → 其它 3000+。
     * 汉字内部再依次用：**拼音 → 声调 → 汉字本身**。</p>
     */
    public static final Comparator<String> TEXT_ORDER = new Comparator<String>() {
        @Override
        public int compare(String left, String right) {
            String l = left == null ? "" : left;
            String r = right == null ? "" : right;
            int ls = scriptOf(l);
            int rs = scriptOf(r);
            // 数字 / 符号开头的曲目：归到 # 段，**排在所有文字之后**（用户规则）
            boolean lo = ls == SCRIPT_OTHER;
            boolean ro = rs == SCRIPT_OTHER;
            if (lo != ro) {
                return lo ? 1 : -1;
            }
            if (lo) {
                return RAW.compare(l, r);
            }
            String lk = sortKeyOf(l);
            String rk = sortKeyOf(r);

            // 统一按「字母串」比（拉丁字母序）—— 西里尔已折算成拉丁、假名是罗马字、汉字是拼音，
            // 于是拉丁 K / 西里尔 К / 假名 カ 会交错排列，而不是各占一段。
            int byLetter = LATIN.compare(lk, rk);
            if (byLetter != 0) {
                return byLetter;
            }
            // 同一个字母：西里尔排前面，其余按文字次序
            if (ls != rs) {
                if (ls == SCRIPT_CYRILLIC) {
                    return -1;
                }
                if (rs == SCRIPT_CYRILLIC) {
                    return 1;
                }
                return Integer.compare(ls, rs);
            }
            // 汉字：拼音相同 → 比声调 → 比汉字本身
            if (scriptOf(l) == SCRIPT_HAN && scriptOf(r) == SCRIPT_HAN) {
                int byTone = digits(toneKey(l)).compareTo(digits(toneKey(r)));
                if (byTone != 0) {
                    return byTone;
                }
            }
            return RAW.compare(l, r);
        }
    };

    /** 排序用的字母串。 */
    public static String sortKeyOf(String text) {
        int script = scriptOf(text);
        switch (script) {
            case SCRIPT_HAN:
                return hanPinyinKey(text);
            case SCRIPT_CYRILLIC:
                // 西里尔**折算成拉丁字母**再参与比较 —— 这样西里尔 К 与拉丁 K、假名 カ 会挨在一起，
                // 而不是整段排在前面（用户要的是"全部混排"）。
                return cyrillicToLatinKey(text);
            case SCRIPT_KANA:
                return kanaRomajiKey(text);
            case SCRIPT_LATIN:
                return takeLetters(text, SCRIPT_LATIN, 2);
            default:
                return text == null ? "" : text;   // 数字/符号：靠排位落到最后
        }
    }

    /** 西里尔：取前两个字母，并折算成对应的拉丁字母（К→k、Ш→sh、Я→ya）。 */
    private static String cyrillicToLatinKey(String text) {
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = 0; i < text.length() && taken < 2; i++) {
            char c = text.charAt(i);
            if (!isCyrillic(c)) {
                break;
            }
            sb.append(ScriptTranslit.toLatin(String.valueOf(c)));
            taken++;
        }
        return sb.toString();
    }

    private static String takeLetters(String text, int script, int max) {
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = 0; i < text.length() && taken < max; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            boolean hit = script == SCRIPT_LATIN ? isLatin(c) : isCyrillic(c);
            if (!hit) {
                break;
            }
            sb.append(Character.toLowerCase(c));
            taken++;
        }
        return sb.toString();
    }

    /** 假名：取前两个假名的罗马字（促音符不占一个音）。 */
    private static String kanaRomajiKey(String text) {
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = 0; i < text.length() && taken < 2; i++) {
            char c = text.charAt(i);
            if (c == 'っ') {
                continue;
            }
            if (!isKana(c)) {
                break;
            }
            sb.append(ScriptTranslit.toLatin(String.valueOf(c)));
            taken++;
        }
        return sb.toString();
    }

    /** 汉字：取前两个汉字的完整拼音（带声调数字），空格分隔。 */
    private static String hanPinyinKey(String text) {
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = 0; i < text.length() && taken < 2; i++) {
            char c = text.charAt(i);
            if (!isHan(c)) {
                continue;
            }
            String py = PinyinTable.pinyinOf(c);
            if (py == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(py);
            taken++;
        }
        return sb.length() > 0 ? sb.toString() : (text == null ? "" : text);
    }

    /** 汉字：整段拼音串（用于声调比较）。 */
    private static String toneKey(String text) {
        return PinyinTable.pinyinOf(text);
    }

    /** 统一字母序的排位：西里尔 100+、拉丁 1000+、数字 2000+、其它 3000+。 */
    private static int rankOf(char c) {
        if (isCyrillic(c)) {
            Integer idx = CYRILLIC_RANK.get(Character.toLowerCase(c));
            return 100 + (idx == null ? 99 : idx);
        }
        if (c >= 'a' && c <= 'z') {
            return 1000 + (c - 'a');
        }
        if (c >= '0' && c <= '9') {
            return 2000 + (c - '0');
        }
        return 3000 + (c % 1000);
    }

    /** 取字符串里的数字序列，用于声调比较（{@code "xue3 jiang4" → "34"}）。 */
    private static String digits(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 片假名折成平假名，便于统一比较（字母索引的分组也用它）。 */
    private static char toHiragana(char c) {
        if (c >= 0x30A1 && c <= 0x30F6) {
            return (char) (c - 0x60);
        }
        return c;
    }

    // ==================== 字母索引：分组 ====================

    /**
     * 分组键：**同字面的组在不同文字里算两组**（如拉丁的 A 与汉字的 A）。
     *
     * <p>因为排序本身就是先分文字段，所以索引也必须带文字前缀，否则两段的 A 会被并成一组。</p>
     */
    public static String groupKey(String text) {
        // 分组键 = 显示标签 = **拉丁字母**（A-Z）：
        // 西里尔 Б→B、假名 か→K、汉字卡→K，读音相近的字母合并成一个，减少索引页的字母数量
        return groupLabel(text);
    }

    /** 分组的显示文字（索引页 / 分组标题上看到的那个字）。 */
    public static String groupLabel(String text) {
        int script = scriptOf(text);
        switch (script) {
            case SCRIPT_LATIN:
                return String.valueOf(firstLatinUpper(text));
            case SCRIPT_HAN:
                return PinyinUtils.labelOf(PinyinUtils.initialOfWord(text));
            case SCRIPT_KANA:
                // 假名：罗马字的首字母（か→K、は→H），读音相近就归入同一个拉丁字母
                return firstLatinInitialOf(text);
            case SCRIPT_CYRILLIC:
                // 西里尔：音近的拉丁字母（Б→B、Г→G、Ж→Z、Ц/Ч→C、Ш/Щ→S、Я→Y）
                return firstLatinInitialOf(text);
            default:
                return "#";
        }
    }

    /** 取文本里第一个能转出拉丁字母的字符，返回其大写首字母；转不出返回 '#'。 */
    private static String firstLatinInitialOf(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            String latin = ScriptTranslit.toLatin(String.valueOf(c));
            if (latin != null && !latin.isEmpty()) {
                char head = Character.toUpperCase(latin.charAt(0));
                if (head >= 'A' && head <= 'Z') {
                    return String.valueOf(head);
                }
            }
        }
        return "#";
    }

    /** 段的名称，用于索引页的分区标题。 */
    public static String scriptName(int script) {
        switch (script) {
            case SCRIPT_LATIN:
                return "拉丁";
            case SCRIPT_HAN:
                return "汉字";
            case SCRIPT_KANA:
                return "假名";
            case SCRIPT_CYRILLIC:
                return "西里尔";
            default:
                return "其它";
        }
    }

    private static char firstLatinUpper(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isLatin(c)) {
                return Character.toUpperCase(c);
            }
        }
        return '#';
    }

    private static char firstCyrillicUpper(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCyrillic(c)) {
                return Character.toUpperCase(c);
            }
        }
        return '#';
    }

    private static char kanaRowHead(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = toHiragana(text.charAt(i));
            if (isKana(c)) {
                Character head = KANA_ROW_HEAD.get(c);
                return head == null ? c : head;
            }
        }
        return '#';
    }
}
