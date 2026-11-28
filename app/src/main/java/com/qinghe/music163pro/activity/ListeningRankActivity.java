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
        root.setBackgroundColor(com.qinghe.music163pro.util.UiKit.COLOR_BG_SURFACE);
        root.setPadding(px(8), px(6), px(8), px(6));
        setContentView(root);

        // 顶栏：只有标题。「本周 / 全部」原来做成了标题行里的一对胶囊按钮，挤在标题右侧 ——
        // 实机上两个胶囊直接压在「听歌排行」上面（标题只能靠 setMargins(0,0,92,0) 往左躲）。
        // 现在改成标题下方的页内标签行（与「收藏」「我的歌单」同一套样式），
        // 标题得以真正居中，标题行也能压到最小高度。
        android.widget.RelativeLayout titleBar = new android.widget.RelativeLayout(this);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // 只做竖直避让 + 压到最小：标题居中、横向范围（x≈119 起）本就避开返回键（x≤86）；
        // 下方两个等宽标签的首个标签中心在 x≈98，也在按键横向范围之外 —— 可以放心压。
        WatchBackButton.applyTitleBarTopInset(this, titleBar, px(22));

        TextView title = new TextView(this);
        title.setText("听歌排行");
        title.setTextColor(0xFFFFFFFF);
        // 与全站标题一致（16sp）。原先写 px(15) —— px() 按屏宽比例缩放，实际只有约 18px，
        // 比别的页面小一档。
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.watch_title_text_size));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        android.widget.RelativeLayout.LayoutParams titleLp =
                new android.widget.RelativeLayout.LayoutParams(
                        android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
titleLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
        titleLp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        title.setLayoutParams(titleLp);
        titleBar.addView(title);
        // G5：标题居左；避让由统一入口按返回按钮占位动态补齐（勿手写像素）
        WatchBackButton.alignTitleStart(this, title);
        root.addView(titleBar);

        // ---- 页内标签：本周 / 全部（等宽平分、纯文字、仅颜色区分选中） ----
        LinearLayout switchRow = UiKit.tabRow(this);
        btnWeek = UiKit.tab(this, "本周");
        btnAll = UiKit.tab(this, "全部");
        switchRow.addView(btnWeek, UiKit.tabRowParams());
        switchRow.addView(btnAll, UiKit.tabRowParams());
        root.addView(switchRow);

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
                    // 缓存昵称：供「我的歌单」页排行入口行的副标题（「N首 · 昵称」）使用。
                    String nick = profile.optString("nickname", null);
                    if (nick != null && !nick.isEmpty()) {
                        getSharedPreferences("music163_settings", MODE_PRIVATE)
                                .edit().putString("rank_entry_nickname", nick).apply();
                    }
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
                        // 缓存条数：供「我的歌单」页排行入口行的「N首」使用。
                        getSharedPreferences("music163_settings", MODE_PRIVATE)
                                .edit().putInt("rank_entry_count", items.size()).apply();
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

    /** 刷新两个标签的选中态（纯文字，仅颜色区分 —— 与其它页面的页内标签一致）。 */
    private void updateSwitchStyle() {
        UiKit.setTabSelected(btnWeek, type == 1);
        UiKit.setTabSelected(btnAll, type == 0);
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
