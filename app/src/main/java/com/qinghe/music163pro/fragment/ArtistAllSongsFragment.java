package com.qinghe.music163pro.fragment;

import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.Song;

import java.util.List;

/**
 * P8：歌手页第 1 页 —— 全部歌曲（{@code /api/v1/artist/songs}，order=hot，上限 100 首）。
 *
 * <p>与原实现一致：只在真正需要时请求一次并缓存，之后滑回来直接复用；
 * 标题栏 ☰ → 刷新通过 {@link #onRefresh()} 强制重拉。</p>
 */
public class ArtistAllSongsFragment extends ArtistPageFragment {

    /** 与原实现一致：全部歌曲取前 100 首。 */
    private static final int ALL_SONGS_LIMIT = 100;

    private boolean loaded;
    private boolean loading;

    @Override
    public int pageIndex() {
        return PAGE_ALL;
    }

    @Override
    protected CharSequence pageName() {
        return "全部";
    }

    @Override
    protected CharSequence emptyText() {
        return "暂无歌曲";
    }

    @Override
    protected void loadData() {
        if (loaded) {
            reportSummary();
            return;
        }
        if (loading) {
            return;
        }
        loading = true;
        MusicApiHelper.getArtistSongs(artistId(), 0, ALL_SONGS_LIMIT, "hot", cookie(),
                new MusicApiHelper.ArtistSongsCallback() {
                    @Override
                    public void onResult(List<Song> songs) {
                        loading = false;
                        loaded = true;
                        if (!isViewReady()) {
                            return;
                        }
                        applyItems(songs);
                    }

                    @Override
                    public void onError(String message) {
                        loading = false;
                        if (!isViewReady()) {
                            return;
                        }
                        showStatus("全部歌曲加载失败：" + message);
                    }
                });
    }

    /**
     * 歌手详情回来后不做任何事：本页数据不依赖详情，且按「滑到才加载」口径，
     * 未滑到的页不该因为详情到达就提前打网络请求。
     */
    @Override
    protected void onDetailReady() {
    }

    @Override
    protected void onRefresh() {
        loaded = false;
        loading = false;
        loadData();
    }
}
