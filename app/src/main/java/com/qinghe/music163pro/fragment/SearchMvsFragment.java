package com.qinghe.music163pro.fragment;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.MvDetailActivity;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.MvInfo;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;

import java.util.List;
import java.util.Locale;

/** S1：搜索结果的「MV」标签页。 */
public class SearchMvsFragment extends SearchListFragment<MvInfo> {

    @Override
    protected int pageIndex() {
        return 2;
    }

    @Override
    protected CharSequence pageName() {
        return getString(R.string.search_mv);
    }

    @Override
    protected int itemLayoutRes() {
        return R.layout.item_mv;
    }

    @Override
    protected int itemTextRes() {
        return R.id.tv_mv_name;
    }

    @Override
    protected void bindRow(View row, MvInfo mvInfo) {
        TextView tvName = row.findViewById(R.id.tv_mv_name);
        TextView tvInfo = row.findViewById(R.id.tv_mv_info);
        ImageView ivCover = row.findViewById(R.id.iv_cover);
        tvName.setText(mvInfo.getName());
        StringBuilder info = new StringBuilder();
        if (mvInfo.getArtist() != null && !mvInfo.getArtist().isEmpty()) {
            info.append(mvInfo.getArtist());
        }
        if (mvInfo.getDurationMs() > 0) {
            if (info.length() > 0) {
                info.append(" · ");
            }
            info.append(formatDuration(mvInfo.getDurationMs()));
        }
        tvInfo.setText(info.length() > 0 ? info.toString()
                : getString(R.string.tap_to_view_detail));
        if (ivCover != null) {
            NetworkImageLoader.load(ivCover, mvInfo.getCoverUrl());
        }
    }

    @Override
    protected String coverUrl(MvInfo mvInfo) {
        return mvInfo.getCoverUrl();
    }

    @Override
    protected void fetchPage(int offset, PageCallback<MvInfo> callback) {
        MusicApiHelper.searchMvs(keyword(), offset, cookie(),
                new MusicApiHelper.SearchMvCallback() {
                    @Override
                    public void onResult(List<MvInfo> mvs) {
                        callback.onResult(mvs);
                    }

                    @Override
                    public void onError(String message) {
                        callback.onError(message);
                    }
                });
    }

    @Override
    protected void onItemClicked(int position, MvInfo mvInfo) {
        Intent intent = new Intent(requireContext(), MvDetailActivity.class);
        intent.putExtra("mv_id", mvInfo.getId());
        intent.putExtra("mv_name", mvInfo.getName());
        intent.putExtra("cookie", MusicPlayerManager.getInstance().getCookie());
        startActivity(intent);
    }

    @Override
    protected void onEmptyFirstPage() {
        com.qinghe.music163pro.util.UiKit.toast(requireContext(), getString(R.string.no_mv));
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
