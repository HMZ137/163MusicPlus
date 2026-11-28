package com.qinghe.music163pro.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字母分组（2026-11-28 新增，从 {@code LocalMusicFragment.computeGroups} 抽出来复用）。
 *
 * <p>输入是**已按当前排序方式排好序**的一组名称（曲名 / 曲师 / 歌单名都行），
 * 输出：分组字母、每组首个下标、每组条数。</p>
 *
 * <p>分组键统一走 {@link ScriptSort#groupLabel(String)} —— 汉字取拼音首字母、
 * 西里尔与假名折算成拉丁字母后取首字母，所以 {@code Б}/{@code か}/{@code 卡} 会归到同一组。
 * 数字与纯符号归到 {@code #}，并且**永远排在最后**。</p>
 */
public final class LetterGroups {

    public final List<Character> letters = new ArrayList<>();
    public int[] starts = new int[0];
    public int[] counts = new int[0];

    private LetterGroups() {
    }

    /** 从名称列表建分组（顺序即列表顺序，调用前请先排序）。 */
    public static LetterGroups fromNames(List<String> names) {
        LetterGroups out = new LetterGroups();
        if (names == null || names.isEmpty()) {
            return out;
        }

        Map<Character, Integer> firstIndex = new LinkedHashMap<>();
        Map<Character, Integer> counters = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String label = ScriptSort.groupLabel(names.get(i));
            char letter = label.isEmpty() ? PinyinUtils.OTHER : label.charAt(0);
            if (!firstIndex.containsKey(letter)) {
                firstIndex.put(letter, i);
            }
            Integer c = counters.get(letter);
            counters.put(letter, c == null ? 1 : c + 1);
        }

        // A→Z，最后补 '#'
        List<Character> ordered = new ArrayList<>(firstIndex.size());
        for (char c = 'A'; c <= 'Z'; c++) {
            if (firstIndex.containsKey(c)) {
                ordered.add(c);
            }
        }
        if (firstIndex.containsKey(PinyinUtils.OTHER)) {
            ordered.add(PinyinUtils.OTHER);
        }

        out.letters.addAll(ordered);
        out.starts = new int[ordered.size()];
        out.counts = new int[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            char letter = ordered.get(i);
            out.starts[i] = firstIndex.get(letter);
            Integer c = counters.get(letter);
            out.counts[i] = c == null ? 0 : c;
        }
        return out;
    }
}
