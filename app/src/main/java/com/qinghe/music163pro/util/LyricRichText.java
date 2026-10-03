package com.qinghe.music163pro.util;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;

import com.qinghe.music163pro.model.AiLyricResult;
import com.qinghe.music163pro.widget.LyricBlockSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把 AI 分析结果落到歌词文本上：按词块套 {@link LyricBlockSpan}。
 *
 * <p>定位规则：</p>
 * <ul>
 *   <li>忽略大小写；先精确匹配，再退化为「去掉首尾标点」后匹配；</li>
 *   <li>同一行内每个词块只占用一次出现位置（从上次结束处继续找），避免重复上色；</li>
 *   <li>找不到就不上色（宁可少画，也不猜错位置）。</li>
 * </ul>
 *
 * <p>译文行用同样的方式定位每个词块的 {@code tr}，因此原文词块与译文对应词块<b>同色</b>。</p>
 */
public final class LyricRichText {

    private LyricRichText() {
    }

    /**
     * 原文行：按 tokens 上色。
     *
     * <p>{@code textSizeSp} 会按 {@code context} 的 density 换算成 px —— 必须用当前界面的
     * Context（手表有界面缩放，用 Resources.getSystem() 会算小，导致词块里的字比同行的字小一圈）。</p>
     */
    public static CharSequence buildOriginal(CharSequence text, AiLyricResult.Line line,
                                             int textColor, float textSizeSp, Context context) {
        return build(text, line, textColor, textSizeSp, context, false);
    }

    /** 译文行：按 tokens 的 tr 上色（与原文同色，并带同一套关联角标）。 */
    public static CharSequence buildTranslation(CharSequence text, AiLyricResult.Line line,
                                                int textColor, float textSizeSp, Context context) {
        return build(text, line, textColor, textSizeSp, context, true);
    }

    private static CharSequence build(CharSequence text, AiLyricResult.Line line, int textColor,
                                      float textSizeSp, Context context, boolean useTranslation) {
        if (text == null || line == null || line.tokens.isEmpty()) {
            return text;
        }
        SpannableString sp = new SpannableString(text.toString());
        String src = text.toString();
        float density = context != null
                ? context.getResources().getDisplayMetrics().scaledDensity
                : 1f;
        float px = textSizeSp * density;

        // 同一句里同类词块出现多次时，按「类内序号」编号（原文与译文用同一编号，便于对应）
        int[] classCount = new int[AiLyricPalette.CLASSES.length];
        for (AiLyricResult.Token token : line.tokens) {
            classCount[classIndexOf(token.cls)]++;
        }
        int[] classSeen = new int[AiLyricPalette.CLASSES.length];

        for (AiLyricResult.Token token : line.tokens) {
            String needle = useTranslation ? token.trans : token.text;
            if (needle == null || needle.isEmpty()) {
                continue;
            }
            int clsIndex = classIndexOf(token.cls);
            classSeen[clsIndex]++;
            int badge = classCount[clsIndex] > 1 ? classSeen[clsIndex] : 0;
            applyNeedle(sp, src, needle, token.cls, textColor, px, badge);
        }
        return sp;
    }

    private static int classIndexOf(String cls) {
        for (int i = 0; i < AiLyricPalette.CLASSES.length; i++) {
            if (AiLyricPalette.CLASSES[i].equals(cls)) {
                return i;
            }
        }
        return AiLyricPalette.CLASSES.length - 1;
    }

    /** 超过这么多字符、且含空格的词块会被拆成小块（ReplacementSpan 不可断行，整块过长会溢出被裁）。 */
    private static final int LONG_TOKEN_CHARS = 13;

    /**
     * 给一个词块上色。
     *
     * <p>两个关键处理：</p>
     * <ol>
     *   <li><b>长短语按空格拆成小块</b> —— {@link LyricBlockSpan} 是 ReplacementSpan，
     *       一旦整块超过一行宽度就<b>无法换行</b>，会直接溢出被裁（英文长句踩过这个坑）；
     *       拆开后每块都能独立参与断行，视觉上仍是连成一片的色块。</li>
     *   <li>逐块定位，并记录"已消费到哪"，避免多个块重复匹配到同一个单词。</li>
     * </ol>
     */
    private static void applyNeedle(SpannableString sp, String haystack, String needle,
                                    String cls, int textColor, float textSizePx, int badge) {
        if (needle == null || needle.isEmpty()) {
            return;
        }
        if (needle.length() > LONG_TOKEN_CHARS && needle.indexOf(' ') >= 0) {
            int from = 0;
            for (String part : needle.split("\\s+")) {
                if (part.isEmpty()) {
                    continue;
                }
                from = applySingle(sp, haystack, part, cls, textColor, textSizePx, badge, from);
            }
            return;
        }
        applySingle(sp, haystack, needle, cls, textColor, textSizePx, badge, 0);
    }

    /**
     * 定位一个片段并上色，返回本次匹配的结束位置（供同一词块的后续片段从该处继续找）。
     *
     * <p>匹配顺序：精确（忽略大小写）→ 去首尾标点 → 去掉空格后比对 → 最长公共子串兜底。
     * 最后一步是为了救回「AI 给的对应译文与官方译文不是逐字一致」的情况
     * （例如 tr「用更宏观的视野」而译文是「用更宏观的视野看这世界」），
     * 只要最长公共片段不短于原词的一半（且至少 2 字），就用它标注，绝不硬凑。</p>
     */
    private static int applySingle(SpannableString sp, String haystack, String needle,
                                   String cls, int textColor, float textSizePx, int badge,
                                   int from) {
        if (needle == null || needle.isEmpty()) {
            return from;
        }
        int start = indexOfIgnoreCase(haystack, needle, from);
        int length = needle.length();

        if (start < 0) {
            String trimmed = trimPunct(needle);
            if (!trimmed.isEmpty() && !trimmed.equals(needle)) {
                int s2 = indexOfIgnoreCase(haystack, trimmed, from);
                if (s2 >= 0) {
                    start = s2;
                    length = trimmed.length();
                }
            }
        }

        if (start < 0) {
            // 去空格比对（译文里中文词之间常夹空格）
            String tightNeedle = needle.replace(" ", "");
            String tightHay = haystack.replace(" ", "");
            int tightIndex = indexOfIgnoreCase(tightHay, tightNeedle, 0);
            if (tightIndex >= 0) {
                // 把"紧凑串下标"映射回原文下标
                int realStart = -1;
                int count = 0;
                for (int i = 0; i < haystack.length(); i++) {
                    if (haystack.charAt(i) == ' ') {
                        continue;
                    }
                    if (count == tightIndex) {
                        realStart = i;
                        break;
                    }
                    count++;
                }
                if (realStart >= 0) {
                    start = realStart;
                    length = 0;
                    int seen = 0;
                    for (int i = realStart; i < haystack.length() && seen < tightNeedle.length(); i++) {
                        if (haystack.charAt(i) == ' ') {
                            continue;
                        }
                        seen++;
                        length = i - realStart + 1;
                    }
                }
            }
        }

        if (start < 0) {
            // 最长公共子串兜底：AI 略改措辞时仍能对应上
            int[] hit = longestCommonSubstring(needle, haystack);
            int hitLen = hit[1];
            int threshold = Math.max(2, needle.length() / 2);
            if (hit[0] >= 0 && hitLen >= threshold) {
                start = hit[0];
                length = hitLen;
            }
        }

        if (start < 0 || start + length > sp.length()) {
            return from;
        }
        int end = start + length;

        LyricBlockSpan[] existing = sp.getSpans(start, end, LyricBlockSpan.class);
        if (existing != null && existing.length > 0) {
            return end;
        }
        boolean emphasize = AiLyricPalette.CLS_PHRASE.equals(cls);
        sp.setSpan(new LyricBlockSpan(
                        AiLyricPalette.fillOf(cls),
                        AiLyricPalette.strokeOf(cls),
                        textColor,
                        textSizePx,
                        textSizePx * 0.32f,
                        textSizePx * 0.36f,
                        emphasize,
                        badge),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return end;
    }

    /** needle 在 haystack 里的最长公共子串，返回 {起始下标, 长度}；没有返回 {-1, 0}。 */
    private static int[] longestCommonSubstring(String needle, String haystack) {
        String a = needle.toLowerCase(Locale.ROOT);
        String b = haystack.toLowerCase(Locale.ROOT);
        int bestLen = 0;
        int bestEnd = -1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > bestLen) {
                        bestLen = cur[j];
                        bestEnd = j;
                    }
                } else {
                    cur[j] = 0;
                }
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
            java.util.Arrays.fill(cur, 0);
        }
        if (bestEnd < 0) {
            return new int[]{-1, 0};
        }
        return new int[]{bestEnd - bestLen, bestLen};
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return -1;
        }
        String h = haystack.toLowerCase(Locale.ROOT);
        String n = needle.toLowerCase(Locale.ROOT);
        return h.indexOf(n, Math.max(0, from));
    }

    private static String trimPunct(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isPunct(s.charAt(start))) {
            start++;
        }
        while (end > start && isPunct(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isPunct(char c) {
        return c == '—' || c == '-' || c == ',' || c == '.' || c == '!' || c == '?'
                || c == ';' || c == ':' || c == '"' || c == '\'' || c == '、' || c == '，'
                || c == '。' || c == '！' || c == '？';
    }

    /** 取某行要用到的词块类列表（调试用）。 */
    public static List<String> classesOf(AiLyricResult.Line line) {
        List<String> out = new ArrayList<>();
        if (line != null) {
            for (AiLyricResult.Token t : line.tokens) {
                out.add(t.text + "=" + t.cls);
            }
        }
        return out;
    }
}
