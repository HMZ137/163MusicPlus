package com.qinghe.music163pro.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 「本地播放模式 / 云音乐模式」的单一判据。
 *
 * <p>开启本地播放模式后，这个 App 就是一个纯本地播放器：所有依赖网易云账号或网络的
 * 入口都不再出现（搜索、排行榜、每日推荐、云盘、云端收藏、私人漫游、评论、听歌识曲、
 * B站、登录……），收藏也强制走本地库。</p>
 *
 * <p><b>为什么集中在一个类里：</b>「这个功能算不算云端功能」的判断被更多页的磁贴注册、
 * 播放页的二级菜单、收藏分流三处用到。分散写很容易出现「磁贴藏了但二级菜单里还能点进去」
 * 这类不一致 —— 和 {@link LocalTracks} 把「算不算本地音乐」收在一处是同一个理由。</p>
 *
 * <p>实现上刻意只做「入口隐藏 + 分流兜底」，不动播放内核：本地播放链路（下载列表、
 * 本地音乐、播放队列、定时关闭、播放模式、倍速、歌词）在两种模式下完全一致。</p>
 */
public final class PlaybackMode {

    public static final String PREFS_NAME = "music163_settings";
    /** 本地播放模式开关（false = 云音乐模式，默认）。 */
    public static final String PREF_LOCAL_ONLY = "local_only_mode";
    /** 收藏模式：true = 走云端接口。 */
    public static final String PREF_FAV_MODE_CLOUD = "fav_mode_cloud";
    /** 进入本地模式前用户选的收藏模式，退出时还原用。 */
    private static final String PREF_FAV_MODE_CLOUD_SAVED = "fav_mode_cloud_saved";

    private PlaybackMode() {
    }

    /** 当前是否处于本地播放模式。 */
    public static boolean isLocalOnly(Context context) {
        if (context == null) {
            return false;
        }
        return prefs(context).getBoolean(PREF_LOCAL_ONLY, false);
    }

    /**
     * 切换本地播放模式。
     *
     * <p><b>顺带把「收藏走云端」这个底层开关也改对。</b>{@code fav_mode_cloud} 的读取点散在
     * 收藏列表、歌单详情、播放页、批量收藏助手四个文件共 7 处，都直接读这个键。与其把 7 处
     * 逐一改成「先查模式再决定」，不如在**切模式这一个地方**把键写对 —— 读点一行都不用动，
     * 也就不会漏掉某一处。进模式时把原值存起来，退出时还原，用户不会丢设置。</p>
     */
    public static void setLocalOnly(Context context, boolean localOnly) {
        if (context == null) {
            return;
        }
        SharedPreferences p = prefs(context);
        if (p.getBoolean(PREF_LOCAL_ONLY, false) == localOnly) {
            return;
        }
        SharedPreferences.Editor editor = p.edit()
                .putBoolean(PREF_LOCAL_ONLY, localOnly);
        if (localOnly) {
            // 关掉云端收藏，并记住用户原本的选择
            editor.putBoolean(PREF_FAV_MODE_CLOUD_SAVED,
                    p.getBoolean(PREF_FAV_MODE_CLOUD, false));
            editor.putBoolean(PREF_FAV_MODE_CLOUD, false);
        } else if (p.contains(PREF_FAV_MODE_CLOUD_SAVED)) {
            editor.putBoolean(PREF_FAV_MODE_CLOUD,
                    p.getBoolean(PREF_FAV_MODE_CLOUD_SAVED, false));
            editor.remove(PREF_FAV_MODE_CLOUD_SAVED);
        }
        editor.apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 某个「更多」页磁贴是否属于云端功能 —— 本地模式下应当隐藏。
     *
     * <p>保留的是真正离网可用的那些：收藏列表（本地收藏库）、下载列表、铃声管理、
     * 历史记录。其余一律算云端。</p>
     *
     * @param menuKey {@link MoreMenuPreferences} 里的 {@code KEY_*}
     */
    public static boolean isCloudFeature(String menuKey) {
        if (menuKey == null) {
            return false;
        }
        switch (menuKey) {
            // 离线可用的本地功能，两种模式下都保留
            case MoreMenuPreferences.KEY_FAVORITES:
            case MoreMenuPreferences.KEY_DOWNLOADS:
            case MoreMenuPreferences.KEY_RINGTONES:
            case MoreMenuPreferences.KEY_HISTORY:
                return false;
            default:
                return true;
        }
    }

    /**
     * 收藏是否应走云端。
     *
     * <p>本地播放模式下强制本地收藏。注意 {@link #setLocalOnly} 进入该模式时已经把
     * {@code fav_mode_cloud} 写成 false，所以这里其实是在「模式被绕过、直接改过键」
     * 的情况下兜底 —— 判定集中在一处，调用方不必自己组合两个条件。</p>
     */
    public static boolean isCloudFavoriteEnabled(Context context) {
        if (context == null || isLocalOnly(context)) {
            return false;
        }
        return prefs(context).getBoolean(PREF_FAV_MODE_CLOUD, false);
    }
}
