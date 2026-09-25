package com.qinghe.music163pro.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Cookie 备份文件的格式定义 —— <b>纯 JVM，不依赖任何 Android API</b>。
 *
 * <p>刻意和 {@link CookieBackup} 拆开：那边负责权限、外部存储、文件读写；这边只管
 * 「一个字符串怎么变成文件内容、又怎么变回来」。这样格式逻辑可以脱离 Android 直接
 * 编译运行做往返验证（见 {@code .workbuddy/cookie-format-probe/}）—— 单靠人眼读
 * 是看不出「注释行过滤会不会误伤 Cookie 本身」这类问题的。</p>
 *
 * <p>格式：以 {@code #} 开头的注释头 + 原始 Cookie。</p>
 */
public final class CookieFileFormat {

    /** 文件头的标记行前缀，用来在文件管理器里一眼认出这是什么。 */
    public static final String HEADER_MARK = "# 163音乐Pro";
    /** 注释行的起始字符。 */
    public static final char COMMENT_CHAR = '#';

    private CookieFileFormat() {
    }

    /**
     * 生成文件内容。
     *
     * <p>注释头里写明这是什么、何时导出、用来干什么、以及「含凭证勿分享」—— 用户哪天在
     * 文件管理器里翻到它时能立刻看懂，而不是当成乱码删掉。</p>
     *
     * @param cookie 原始 Cookie，调用方保证非空
     * @param stamp  导出时间文本，例如 {@code 2026-09-25 21:30}
     */
    public static String build(String cookie, String stamp) {
        String value = cookie == null ? "" : cookie.trim();
        String time = stamp == null ? "" : stamp;
        return HEADER_MARK + " Cookie 备份\n"
                + "# 导出时间: " + time + "\n"
                + "# 用途: 重装应用后在「登录」页点「读取 Cookie」即可恢复登录状态\n"
                + "# 注意: 本文件含账号凭证，请勿分享或上传\n"
                + "# 以下即为 Cookie 内容（不含本注释行）\n"
                + value + "\n";
    }

    /**
     * 解析文件内容，还原 Cookie。
     *
     * <p>同时接受三种形态，因为用户可能手工造/改这个文件：</p>
     * <ul>
     *   <li>本类 {@link #build} 的输出（带注释头）；</li>
     *   <li>裸 Cookie（直接粘一行进去）；</li>
     *   <li>被编辑器折成多行的 Cookie。</li>
     * </ul>
     *
     * <p><b>只丢弃「整行以 # 开头」的行</b>，不做「删除所有 # 字符」那种粗暴处理 ——
     * 否则 Cookie 值里万一含 # 就会被改坏。多行内容用空格拼回，与 Cookie 自身的
     * 分隔习惯（{@code a=1; b=2}）一致。</p>
     *
     * @return 还原出的 Cookie；内容为空或全是注释时返回空串
     */
    public static String parse(String raw) {
        if (raw == null || raw.length() == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String line : raw.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) == COMMENT_CHAR) {
                continue;
            }
            parts.add(trimmed);
        }
        if (parts.isEmpty()) {
            return "";
        }
        return String.join(" ", parts);
    }

    /** 内容是否像一份本类生成（或用户手工维护）的备份 —— 即解析后能拿到非空 Cookie。 */
    public static boolean looksUsable(String raw) {
        return !parse(raw).isEmpty();
    }

    /** 内容是否带本类生成的注释头。用于区分「我们导出的」和「用户手写的」。 */
    public static boolean hasHeader(String raw) {
        if (raw == null) {
            return false;
        }
        for (String line : raw.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            return trimmed.startsWith(HEADER_MARK);
        }
        return false;
    }
}
