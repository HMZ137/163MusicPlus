package com.qinghe.music163pro.util;

/**
 * 语言学习模式的词类配色（精简 8 色，见需求方案文档）。
 *
 * <p>类别枚举与 AI 的约定一致：{@code pron / verb / noun / adj / adv / prep / phrase / other}。
 * 颜色只在这里定义一次，渲染（词块底衬）与设置页图例共用，避免两处色值漂移。</p>
 */
public final class AiLyricPalette {

    public static final String CLS_PRON = "pron";
    public static final String CLS_VERB = "verb";
    public static final String CLS_NOUN = "noun";
    public static final String CLS_ADJ = "adj";
    public static final String CLS_ADV = "adv";
    public static final String CLS_PREP = "prep";
    public static final String CLS_PHRASE = "phrase";
    public static final String CLS_OTHER = "other";

    /** 8 个类别的固定顺序（图例按此顺序展示）。 */
    public static final String[] CLASSES = {
            CLS_PRON, CLS_VERB, CLS_NOUN, CLS_ADJ,
            CLS_ADV, CLS_PREP, CLS_PHRASE, CLS_OTHER,
    };

    private static final int COLOR_PRON = 0xFF4C9AFF;   // 代词：蓝
    private static final int COLOR_VERB = 0xFFFF6B6B;   // 动词：红
    private static final int COLOR_NOUN = 0xFF37C978;   // 名词：绿
    private static final int COLOR_ADJ = 0xFFB57BFF;    // 形容词：紫
    private static final int COLOR_ADV = 0xFF2FD3C9;    // 副词：青
    private static final int COLOR_PREP = 0xFFFFC145;   // 介词/连词：黄
    private static final int COLOR_PHRASE = 0xFFE0A33E; // 短语/习语：琥珀
    private static final int COLOR_OTHER = 0xFF8C9BB5;  // 其它：蓝灰

    private AiLyricPalette() {
    }

    /** 词类 → 主色（给图例、描边用）。 */
    public static int colorOf(String cls) {
        if (cls == null) {
            return COLOR_OTHER;
        }
        switch (cls) {
            case CLS_PRON: return COLOR_PRON;
            case CLS_VERB: return COLOR_VERB;
            case CLS_NOUN: return COLOR_NOUN;
            case CLS_ADJ:  return COLOR_ADJ;
            case CLS_ADV:  return COLOR_ADV;
            case CLS_PREP: return COLOR_PREP;
            case CLS_PHRASE: return COLOR_PHRASE;
            default: return COLOR_OTHER;
        }
    }

    /** 底衬填充色：同色相、低透明度（不刺眼，文字仍然清晰）。 */
    public static int fillOf(String cls) {
        return withAlpha(colorOf(cls), 0x3A);
    }

    /** 底衬描边色：同色相、更高一点的透明度，让色块边界可辨。 */
    public static int strokeOf(String cls) {
        return withAlpha(colorOf(cls), 0x8C);
    }

    /** 中文名（设置页图例用）。 */
    public static String labelOf(String cls) {
        if (cls == null) {
            return "其它";
        }
        switch (cls) {
            case CLS_PRON: return "代词";
            case CLS_VERB: return "动词";
            case CLS_NOUN: return "名词";
            case CLS_ADJ: return "形容词";
            case CLS_ADV: return "副词";
            case CLS_PREP: return "介词 / 连词";
            case CLS_PHRASE: return "短语 / 习语";
            default: return "其它";
        }
    }

    /** 把任意 AI 返回的类名收敛到 8 类以内（未知一律 other）。 */
    public static String normalize(String rawCls) {
        if (rawCls == null) {
            return CLS_OTHER;
        }
        String c = rawCls.trim().toLowerCase();
        switch (c) {
            case "pron":
            case "pronoun":
            case "det":
            case "article":
                return CLS_PRON;
            case "verb":
            case "aux":
            case "auxiliary":
                return CLS_VERB;
            case "noun":
            case "proper":
            case "proper_noun":
            case "num":
            case "number":
                return CLS_NOUN;
            case "adj":
            case "adjective":
                return CLS_ADJ;
            case "adv":
            case "adverb":
                return CLS_ADV;
            case "prep":
            case "conj":
            case "preposition":
            case "conjunction":
                return CLS_PREP;
            case "phrase":
            case "idiom":
            case "collocation":
                return CLS_PHRASE;
            default:
                for (String known : CLASSES) {
                    if (known.equals(c)) {
                        return known;
                    }
                }
                return CLS_OTHER;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
