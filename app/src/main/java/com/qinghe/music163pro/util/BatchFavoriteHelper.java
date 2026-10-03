package com.qinghe.music163pro.util;

import android.app.Activity;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.manager.FavoritesManager;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量收藏工具。
 *
 * <p>把「单曲收藏」（{@code MainActivity.onFuncFavorite(Song)}）里的两套行为抽出来，
 * 供列表页在多选状态下批量调用，口径与单曲完全一致：</p>
 *
 * <ul>
 *   <li><b>本地模式</b>（{@code music163_settings} 下 {@code fav_mode_cloud == false}）：
 *       走 {@link FavoritesManager#addFavorites(List)}，一次性写入；已收藏的歌曲跳过。</li>
 *   <li><b>云端模式</b>：走 {@link MusicApiHelper#likeTrack}，需要登录 cookie。
 *       云端接口一次只能提交一首，这里串行逐首提交（避免并发触发风控），
 *       失败的不影响后面继续。</li>
 * </ul>
 *
 * <p>回调都发生在主线程：{@code likeTrack} 内部已经把结果 post 到主线程。</p>
 */
public final class BatchFavoriteHelper {

    /** 批量收藏的结果回调。 */
    public interface Callback {
        /**
         * @param added   本次新收藏成功的数量
         * @param skipped 因为「已收藏」或「没有有效歌曲 ID」而跳过的数量
         * @param failed  云端接口返回失败的数量（本地模式恒为 0）
         * @param total   传入的歌曲总数
         */
        void onFinished(int added, int skipped, int failed, int total);

        /** 云端模式下没有登录态，无法批量收藏。 */
        void onNeedLogin();

        /**
         * 需要登录才能收藏云端曲目，但本次选中的**本地曲目已经先按本地收藏处理好了**。
         *
         * <p>默认实现直接转给 {@link #onNeedLogin()}，既有调用方不用改；想给出更准确提示
         * （“本地 N 首已收藏，云端需登录”）的页面可以覆盖它。</p>
         *
         * @param addedLocal 已经加入本地收藏的数量
         */
        default void onNeedLogin(int addedLocal) {
            onNeedLogin();
        }
    }

    private BatchFavoriteHelper() {
    }

    /**
     * 当前是否使用云端收藏。与单曲收藏读取的是同一份设置，保证行为一致。
     *
     * <p>判定收在 {@link PlaybackMode#isCloudFavoriteEnabled} 里：本地播放模式下批量收藏
     * 也必须落本地库，否则会出现「列表页批量收藏写了云端、播放页单曲收藏写了本地」
     * 这种同一个按钮两种结果的怪事。</p>
     */
    public static boolean isCloudFavoriteMode(Activity activity) {
        return PlaybackMode.isCloudFavoriteEnabled(activity);
    }

    /**
     * 批量收藏。回调必定被调用一次（{@link Callback#onFinished} 或
     * {@link Callback#onNeedLogin}）；{@code songs} 为空时直接返回，不回调。
     */
    public static void favoriteAll(Activity activity, List<Song> songs, Callback callback) {
        if (activity == null || callback == null || songs == null || songs.isEmpty()) {
            return;
        }

        final int total = songs.size();

        // 本地曲目（路径派生的负数 id）无论当前是哪种收藏模式，都只能进本地收藏库 ——
        // 云端接口认不了它，本地库才是它真正的家。这与播放页单曲收藏的口径一致
        // （本地歌走 onFuncFavoriteLocal）。先规范化成「本地收藏条目」，否则存进去点开播不了。
        List<Song> localTargets = new ArrayList<>();
        List<Song> cloudTargets = new ArrayList<>();
        for (Song song : songs) {
            if (song == null) {
                continue;
            }
            if (LocalTracks.isLocalOnly(song)) {
                Song entry = LocalTracks.asLocalFavoriteEntry(song);
                if (entry != null) {
                    localTargets.add(entry);
                }
            } else {
                cloudTargets.add(song);
            }
        }

        FavoritesManager manager = new FavoritesManager(activity);
        int addedLocal = localTargets.isEmpty() ? 0 : manager.addFavorites(localTargets);

        if (!isCloudFavoriteMode(activity)) {
            // 本地收藏模式：云端曲目也写本地库，一次结算
            int added = addedLocal
                    + (cloudTargets.isEmpty() ? 0 : manager.addFavorites(cloudTargets));
            callback.onFinished(added, total - added, 0, total);
            return;
        }

        // 云端收藏模式：没有云端曲目时直接结算（本地部分已入库）
        if (cloudTargets.isEmpty()) {
            callback.onFinished(addedLocal, total - addedLocal, 0, total);
            return;
        }
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null || cookie.isEmpty()) {
            callback.onNeedLogin(addedLocal);
            return;
        }
        likeSequentially(cloudTargets, cookie, 0, 0, addedLocal, total, callback);
    }

    /**
     * 逐首串行提交云端收藏，全部处理完后回调一次。
     *
     * @param valid     待提交云端的曲目（已滤掉本地曲目）
     * @param failed    已经失败的数量
     * @param baseAdded 进入本方法前已经成功加入**本地**收藏的数量，结算时并入 added
     * @param total     用户实际选中的总数
     */
    private static void likeSequentially(final List<Song> valid, final String cookie,
                                        final int index, final int failed, final int baseAdded,
                                        final int total, final Callback callback) {
        if (index >= valid.size()) {
            int added = baseAdded + (valid.size() - failed);
            callback.onFinished(added, Math.max(0, total - added - failed), failed, total);
            return;
        }
        MusicApiHelper.likeTrack(valid.get(index).getId(), true, cookie,
                new MusicApiHelper.LikeCallback() {
                    @Override
                    public void onResult(boolean success) {
                        likeSequentially(valid, cookie, index + 1,
                                success ? failed : failed + 1, baseAdded, total, callback);
                    }

                    @Override
                    public void onError(String message) {
                        likeSequentially(valid, cookie, index + 1,
                                failed + 1, baseAdded, total, callback);
                    }
                });
    }

    /** 把 {@link Callback#onFinished} 的结果拼成一句可以直接 Toast 的提示。 */
    public static String buildResultMessage(Activity activity, int added, int failed) {
        if (added > 0 && failed > 0) {
            return activity.getString(R.string.multi_select_favorite_partial, added, failed);
        }
        if (added > 0) {
            return activity.getString(R.string.multi_select_favorite_done, added);
        }
        if (failed > 0) {
            return activity.getString(R.string.multi_select_favorite_failed, failed);
        }
        return activity.getString(R.string.multi_select_favorite_existed);
    }
}
