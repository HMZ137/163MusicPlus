package com.qinghe.music163pro.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.qinghe.music163pro.R;

/**
 * 「本地」标签页的占位 + 能力桥（任务 <b>4-1</b>；真实内容由 <b>4-3</b> 迁入）。
 *
 * <p><b>为什么先放占位</b>：{@code LocalMusicActivity} 是一个成熟页面（扫描 / 排序 / 搜索 /
 * 局域网传歌 / 两阶段加载，1300 余行）。4-3 才把它迁成 Fragment；在那之前这里放一个
 * <b>桥接按钮</b>，点击仍走原路径 {@code DownloadListActivity.openLocalMusicTab()}，
 * 保证「本地」这个能力<b>一刻都不丢</b>。4-3 完成后本页会被真实内容替换、桥按钮删除。</p>
 */
public class LocalMusicTabFragment extends Fragment {

    /** 宿主需要实现的回调。 */
    public interface Host {
        /** 用户点了桥接按钮：按原有路径打开本机音乐页。 */
        void onOpenLocalMusicTab();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_local_music_tab, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        View open = view.findViewById(R.id.btn_open_local_music);
        if (open != null) {
            open.setOnClickListener(v -> ((Host) requireActivity()).onOpenLocalMusicTab());
        }
    }
}
