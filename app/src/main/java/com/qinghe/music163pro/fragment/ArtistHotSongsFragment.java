package com.qinghe.music163pro.fragment;

import com.qinghe.music163pro.api.MusicApiHelper;

/**
 * P8：歌手页第 0 页 —— 热门 50 首。
 *
 * <p>数据直接用宿主 {@code ArtistDetailActivity} 已请求到的歌手详情里的 {@code hotSongs}
 * （与改造前「热门 50 首」标签用同一份数据），不额外打网络请求。</p>
 */
public class ArtistHotSongsFragment extends ArtistPageFragment {

    @Override
    public int pageIndex() {
        return PAGE_HOT;
    }

    @Override
    protected CharSequence pageName() {
        return "热门";
    }

    @Override
    protected CharSequence emptyText() {
        return "暂无热门歌曲";
    }

    @Override
    protected void loadData() {
        MusicApiHelper.ArtistDetail detail = detail();
        if (detail == null) {
            String error = detailError();
            showStatus(error != null ? "歌手信息加载失败：" + error : "正在加载歌手信息…");
            return;
        }
        applyItems(detail.hotSongs);
    }
}
