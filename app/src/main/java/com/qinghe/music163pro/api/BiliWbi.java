package com.qinghe.music163pro.api;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * B 站 WBI 签名（2023 年后接口风控必需）。
 *
 * <p>流程：{@code /x/web-interface/nav} 拿 img_key / sub_key → 拼接后按固定表重排出 mixinKey
 * → 参数按 key 排序 + wts 时间戳 → md5(query + mixinKey) 得到 w_rid。</p>
 *
 * <p>需求 2026-11-21：听 bili 的搜索接口改成 wbi 版本，否则会被风控拦截（412 / 空结果）。</p>
 */
final class BiliWbi {

    /** WBI mixinKey 的重排表（B 站前端固定常量）。 */
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private BiliWbi() {
    }

    /** 由 img_key + sub_key 推出 mixinKey（取前 32 位）。 */
    static String mixinKey(String imgKey, String subKey) {
        String raw = (imgKey == null ? "" : imgKey) + (subKey == null ? "" : subKey);
        StringBuilder sb = new StringBuilder();
        for (int idx : MIXIN_KEY_ENC_TAB) {
            if (idx < raw.length()) {
                sb.append(raw.charAt(idx));
            }
        }
        return sb.length() >= 32 ? sb.substring(0, 32) : sb.toString();
    }

    /**
     * 给参数签名，返回完整的 query 串（不含 URL 前缀）。
     *
     * <p>注意：value 里的 {@code ! ' ( ) *} 会被剔除，这是 B 站前端的行为，签名与请求必须一致。</p>
     */
    static String signQuery(Map<String, String> params, String imgKey, String subKey) {
        params.put("wts", String.valueOf(System.currentTimeMillis() / 1000L));
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder query = new StringBuilder();
        for (String key : keys) {
            String value = params.get(key);
            if (value == null) {
                continue;
            }
            value = value.replace("!", "").replace("'", "")
                    .replace("(", "").replace(")", "").replace("*", "");
            try {
                query.append(key).append('=')
                        .append(URLEncoder.encode(value, "UTF-8")).append('&');
            } catch (Exception ignored) {
            }
        }
        String wRid = md5(query.toString() + mixinKey(imgKey, subKey));
        query.append("w_rid=").append(wRid);
        return query.toString();
    }

    /** 从 nav 接口返回的 url 里抠出文件名（去掉路径与扩展名）。 */
    static String keyFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
