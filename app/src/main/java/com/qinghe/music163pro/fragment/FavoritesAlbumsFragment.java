package com.qinghe.music163pro.fragment;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.AlbumDetailActivity;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.util.NetworkImageLoader;

import java.util.List;

/**
 * 4-15：收藏的「专辑」页（原 {@code FavoritesListActivity} 的第三个标签）。
 *
 * <p>收藏的专辑只存在于账号侧（weapi {@code /api/album/sublist}），所以本页**始终走云端**，
 * 与「收藏模式」开关无关。点击进 {@code AlbumDetailActivity}（可在那里取消收藏）。</p>
 */
public class FavoritesAlbumsFragment extends FavoritesPageFragment<MusicApiHelper.AlbumBrief> {

    /** 一次拉取的收藏专辑条数（与原实现一致；接口支持 limit/offset，本页未做滚动分页）。 */
    private static final int PAGE_LIMIT = 100;

    @Override
    public int pageIndex() {
        return PAGE_ALBUMS;
    }

    @Override
    protected int itemLayoutRes() {
        return R.layout.item_playlist;
    }

    @Override
    protected int itemTextRes() {
        return R.id.tv_playlist_name;
    }

    @Override
    protected String coverUrl(MusicApiHelper.AlbumBrief item) {
        return item.picUrl;
    }

    @Override
    protected CharSequence emptyText() {
        return "暂无收藏的专辑";
    }

    @Override
    protected CharSequence pageName() {
        return "专辑";
    }

    @Override
    protected CharSequence countUnit() {
        return "张";
    }

    @Override
    protected void bindRow(View row, MusicApiHelper.AlbumBrief album) {
        TextView tvName = row.findViewById(R.id.tv_playlist_name);
        TextView tvInfo = row.findViewById(R.id.tv_playlist_info);
        ImageView ivCover = row.findViewById(R.id.iv_cover);
        tvName.setText(album.name);
        StringBuilder info = new StringBuilder();
        if (album.size > 0) {
            info.append(album.size).append("\u9996");
        }
        if (album.artist != null && !album.artist.isEmpty()) {
            if (info.length() > 0) {
                info.append(" \u00b7 ");
            }
            info.append(album.artist);
        }
        tvInfo.setText(info.toString());
        if (ivCover != null) {
            NetworkImageLoader.load(ivCover, album.picUrl);
        }
    }

    @Override
    protected void onItemClicked(int position, MusicApiHelper.AlbumBrief album) {
        Intent intent = new Intent(requireContext(), AlbumDetailActivity.class);
        intent.putExtra("album_id", album.id);
        intent.putExtra("album_name", album.name);
        intent.putExtra("album_cover_url", album.picUrl);
        startActivity(intent);
    }

    @Override
    protected void loadData() {
        showStatus("正在加载收藏的专辑...");
        String cookie = cookie();
        if (cookie == null || cookie.isEmpty()) {
            showStatus("请先登录以查看收藏的专辑");
            return;
        }
        MusicApiHelper.getSubscribedAlbums(PAGE_LIMIT, 0, cookie,
                new MusicApiHelper.SubscribedAlbumsCallback() {
                    @Override
                    public void onResult(int total, boolean hasMore,
                                         List<MusicApiHelper.AlbumBrief> albums) {
                        if (isViewReady()) {
                            applyItems(albums);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (isViewReady()) {
                            showStatus("加载失败: " + message);
                        }
                    }
                });
    }
}
