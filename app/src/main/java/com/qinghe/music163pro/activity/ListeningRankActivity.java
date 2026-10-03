package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.UiKit;
import com.qinghe.music163pro.util.WatchBackButton;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的听歌排行（服务端数据，与账号同步）。
 *
 * <p>数据源：{@code /api/v1/play/record}（type=1 本周 / 0 全部时间）；
 * uid 通过 {@code /api/w/nuser/account/get} 取当前登录账号。</p>
 *
 * <p>配合播放器里的「听歌打卡」，手表上听过的歌也会出现在这里的排行中。</p>
 */
public class ListeningRankActivity extends BaseWatchActivity {

    private MusicPlayerManager playerManager;

    private TextView btnWeek;
    private TextView btnAll;
    private ListView lvSongs;
    private RankAdapter adapter;
    private TextView tvEmpty;

    private final List<MusicApiHelper.RecordItem> items = new ArrayList<>();
    /** 1 = 本周，0 = 全部时间。 */
    private int type = 1;
    private long uid = -1L;
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playerManager = MusicPlayerManager.getInstance();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(px(8), px(6), px(8), px(6));
        setContentView(root);

        // 顶栏：返回占位 + 标题 + 周/全部切换
        android.widget.RelativeLayout titleBar = new android.widget.RelativeLayout(this);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        WatchBackButton.applyTitleBarInset(this, titleBar);

        TextView title = new TextView(this);
        title.setText("听歌排行");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        android.widget.RelativeLayout.LayoutParams titleLp =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleLp.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT);
        titleLp.setMargins(0, 0, px(92), 0);
        title.setLayoutParams(titleLp);
        titleBar.addView(title);

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.RelativeLayout.LayoutParams switchLp =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        switchLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        switchLp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        switchLp.setMarginEnd(px(6));
        switchRow.setLayoutParams(switchLp);

        btnWeek = buildSwitchButton("本周");
        btnAll = buildSwitchButton("全部");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginEnd(px(4));
        switchRow.addView(btnWeek, btnLp);
        switchRow.addView(btnAll);
        titleBar.addView(switchRow);
        root.addView(titleBar);

        btnWeek.setOnClickListener(v -> switchType(1));
        btnAll.setOnClickListener(v -> switchType(0));
        updateSwitchStyle();

        tvEmpty = new TextView(this);
        tvEmpty.setText("加载中…");
        tvEmpty.setTextColor(0x99FFFFFF);
        tvEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(0, px(24), 0, 0);
        root.addView(tvEmpty);

        lvSongs = new ListView(this);
        lvSongs.setDivider(null);
        lvSongs.setDividerHeight(0);
        lvSongs.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new RankAdapter();
        lvSongs.setAdapter(adapter);
        lvSongs.setOnItemClickListener((parent, view, position, id) -> playFrom(position));
        root.addView(lvSongs);

        loadUidThenRank();
    }

    /** 先拿登录 uid，再拉排行（uid 未知时无法查询）。 */
    private void loadUidThenRank() {
        MusicApiHelper.getUserAccount(playerManager.getCookie(), new MusicApiHelper.AccountCallback() {
            @Override
            public void onResult(JSONObject accountJson) {
                JSONObject profile = accountJson.optJSONObject("profile");
                if (profile != null) {
                    uid = profile.optLong("userId", -1L);
                }
                if (uid <= 0) {
                    JSONObject account = accountJson.optJSONObject("account");
                    if (account != null) {
                        uid = account.optLong("id", -1L);
                    }
                }
                if (uid <= 0) {
                    showEmpty("未取到账号信息，请先登录");
                    return;
                }
                loadRank();
            }

            @Override
            public void onError(String message) {
                showEmpty("账号信息获取失败：" + message);
            }
        });
    }

    private void switchType(int newType) {
        if (type == newType || loading) {
            return;
        }
        type = newType;
        updateSwitchStyle();
        loadRank();
    }

    private void loadRank() {
        if (uid <= 0) {
            return;
        }
        loading = true;
        showEmpty("加载中…");
        MusicApiHelper.getUserRecord(uid, type, playerManager.getCookie(),
                new MusicApiHelper.UserRecordCallback() {
                    @Override
                    public void onResult(List<MusicApiHelper.RecordItem> result) {
                        loading = false;
                        items.clear();
                        if (result != null) {
                            items.addAll(result);
                        }
                        adapter.notifyDataSetChanged();
                        if (items.isEmpty()) {
                            showEmpty(type == 1 ? "本周还没有听歌记录" : "还没有听歌记录");
                        } else {
                            tvEmpty.setVisibility(View.GONE);
                            lvSongs.setVisibility(View.VISIBLE);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        loading = false;
                        showEmpty("加载失败：" + message);
                    }
                });
    }

    private void showEmpty(String text) {
        tvEmpty.setText(text);
        tvEmpty.setVisibility(View.VISIBLE);
        lvSongs.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void playFrom(int position) {
        if (items.isEmpty() || position < 0 || position >= items.size()) {
            return;
        }
        List<Song> playlist = new ArrayList<>();
        for (MusicApiHelper.RecordItem item : items) {
            playlist.add(item.song);
        }
        playerManager.setPlaylistFromSource(playlist, position,
                0L, "听歌排行", playlist.size(), "", 0, false);
        playerManager.playCurrent();
        startActivity(new Intent(this, MainActivity.class));
    }

    // ==================== 构件 ====================

    private TextView buildSwitchButton(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(px(8), px(2), px(8), px(2));
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }

    private void updateSwitchStyle() {
        applySwitchStyle(btnWeek, type == 1);
        applySwitchStyle(btnAll, type == 0);
    }

    private void applySwitchStyle(TextView btn, boolean on) {
        if (on) {
            btn.setBackgroundResource(R.drawable.bg_btn_accent);
            btn.setTextColor(UiKit.COLOR_ON_ACCENT);
        } else {
            btn.setBackgroundResource(R.drawable.bg_btn_outline);
            btn.setTextColor(UiKit.COLOR_ACCENT);
        }
    }

    /** 排行列表：复用 item_song，并把「播放 N 次」放在第三行。 */
    private class RankAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).song.getId();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_song, parent, false);
            }
            MusicApiHelper.RecordItem item = items.get(position);
            Song song = item.song;
            TextView tvName = view.findViewById(R.id.tv_item_name);
            TextView tvArtist = view.findViewById(R.id.tv_item_artist);
            TextView tvProgress = view.findViewById(R.id.tv_item_progress);
            ImageView ivCover = view.findViewById(R.id.iv_cover);
            View ivCheck = view.findViewById(R.id.iv_item_check);

            tvName.setText((position + 1) + ". " + song.getDisplayName());
            tvArtist.setText(song.getArtist());
            if (tvProgress != null) {
                tvProgress.setText("播放 " + item.playCount + " 次");
                tvProgress.setVisibility(View.VISIBLE);
            }
            if (ivCheck != null) {
                ivCheck.setVisibility(View.GONE);
            }
            if (ivCover != null && song.getCoverUrl() != null && !song.getCoverUrl().isEmpty()) {
                NetworkImageLoader.load(ivCover, song.getCoverUrl());
            }
            return view;
        }
    }
}
