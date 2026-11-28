package com.qinghe.music163pro.util;

import java.nio.charset.Charset;

/**
 * 取一个词的拼音首字母，用于本地音乐按字母排序与 A–Z 索引。
 *
 * <p>不引入拼音库：汉字在 **GBK 的一级汉字区（0xB0A1–0xD7F9）是按拼音顺序排列的**，
 * 所以只要记住每个声母起始字的区位码，就能用二分/线性比较求出首字母。
 * 这张界限表是中文信息处理里的经典做法，体积只有几十字节。</p>
 *
 * <p>不认识的字符（数字、符号、日文、二级汉字）统一归到 {@link #OTHER}，
 * 在列表里显示为 {@code #}。</p>
 */
public final class PinyinUtils {

    /** 归不进 A–Z 的分组（数字、符号、生僻字等）。 */
    public static final char OTHER = '#';

    /**
     * A–Z 索引全集（2026-11-28 改）：**含 I / U / V**。
     *
     * <p>原来按汉语拼音声母裁掉了这三个，但西里尔（Иван→I）与假名（ヴァイオリン→V）
     * 转写后会产生它们，裁掉就搜不到。实际展示时由页面按「当前列表存在的字母」再筛。</p>
     */
    public static final String INDEX_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** 每个声母起始汉字的 GBK 编码（升序）。 */
    private static final int[] GB_BOUNDARIES = {
            0xB0A1, // A
            0xB0C5, // B
            0xB2C1, // C
            0xB4EE, // D
            0xB6EA, // E
            0xB7A2, // F
            0xB8C1, // G
            0xB9FE, // H
            0xBBF7, // J
            0xBFA6, // K
            0xC0AC, // L
            0xC2E8, // M
            0xC4C3, // N
            0xC5B6, // O
            0xC5BE, // P
            0xC6DA, // Q
            0xC8BB, // R
            0xC8F6, // S
            0xCBFA, // T
            0xCDDA, // W
            0xCEF4, // X
            0xD1B9, // Y
            0xD4D1, // Z
    };

    private static final char[] LETTERS = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'W', 'X', 'Y', 'Z'
    };

    private static final Charset GBK = Charset.forName("GBK");

    private PinyinUtils() {
    }

    /**
     * 取单个字符的首字母；非汉字或无法识别时返回 {@link #OTHER}。
     */
    public static char initialOf(char c) {
        if (c >= 'a' && c <= 'z') {
            return (char) (c - 'a' + 'A');
        }
        if (c >= 'A' && c <= 'Z') {
            return c;
        }
        // 常见全角字母也认一下，避免「Ａ」被归到 #
        if (c >= 'Ａ' && c <= 'Ｚ') {
            return (char) ('A' + (c - 'Ａ'));
        }
        if (c >= 'ａ' && c <= 'ｚ') {
            return (char) ('A' + (c - 'ａ'));
        }
        if (c < 0x4E00 || c > 0x9FA5) {
            return OTHER;
        }
        try {
            byte[] gbk = String.valueOf(c).getBytes(GBK);
            if (gbk.length != 2) {
                return OTHER;
            }
            int code = ((gbk[0] & 0xFF) << 8) | (gbk[1] & 0xFF);
            if (code < GB_BOUNDARIES[0] || code > 0xD7F9) {
                // 0xD7F9 之后是二级汉字，不按拼音排列，只能归到 #
                return OTHER;
            }
            for (int i = GB_BOUNDARIES.length - 1; i >= 0; i--) {
                if (code >= GB_BOUNDARIES[i]) {
                    return LETTERS[i];
                }
            }
            return OTHER;
        } catch (Exception e) {
            return OTHER;
        }
    }

    /**
     * 取一个词的首字母。
     *
     * <p>跳过前导的非字母数字字符（例如「《xxx》」「[MV]」这种前缀），
     * 否则整首歌都会被归到 {@code #}。</p>
     *
     * <p>汉字与拉丁字母都认不出时，再用 {@link ScriptTranslit} 试一次**假名 / 西里尔的拉丁转写** ——
     * 这样「かがやき」归到 K、「Служить России」归到 S，日文/俄文曲名在 A–Z 索引里也能正常分组。</p>
     */
    public static char initialOfWord(String text) {
        if (text == null) {
            return OTHER;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            char initial = initialOf(c);
            if (initial != OTHER) {
                return initial;
            }
        }
        char latin = ScriptTranslit.latinInitialOf(text);
        return latin != 0 ? latin : OTHER;
    }

    /** 分组标题的展示文本：{@link #OTHER} 显示为 {@code #}。 */
    public static String labelOf(char initial) {
        return initial == OTHER ? "#" : String.valueOf(initial);
    }
}
