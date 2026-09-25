package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;


import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.MoreMenuPreferences;
import com.qinghe.music163pro.util.PlaybackMode;

import java.util.ArrayList;
import java.util.List;

/**
 * More menu activity - shows a flat tile list of functions:
 * 收藏列表, 搜索, 下载列表, 铃声管理, 排行榜, 历史记录,
 * 个人中心(登录后), 私人漫游, 登录, 设置
 *
 * Supports right-swipe gesture to go back to the player screen.
 */
public class MoreActivity extends BaseWatchActivity {

    private View btnLocalMusic;
    private View btnProfile;
    private View btnPersonalFM;
    private View btnMyPlaylists;
    private View btnDailyRecommend;
    private View btnRadarPlaylist;
    private View btnMusicCloud;
    private View btnFavorites;
    private View btnSearch;
    private View btnSongRecognition;
    private View btnDownloads;
    private View btnBilibili;
    private View btnRingtones;
    private View btnTopList;
    private View btnHistory;
    private View btnLogin;
    private View btnSettings;
    private GestureDetector gestureDetector;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_more);

        // Apply keep screen on setting
        prefs = getSharedPreferences(MoreMenuPreferences.PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        btnLocalMusic = findViewById(R.id.btn_menu_local_music);
        btnFavorites = findViewById(R.id.btn_menu_favorites);
        btnMyPlaylists = findViewById(R.id.btn_menu_my_playlists);
        btnDailyRecommend = findViewById(R.id.btn_menu_daily_recommend);
        btnRadarPlaylist = findViewById(R.id.btn_menu_radar_playlist);
        btnMusicCloud = findViewById(R.id.btn_menu_music_cloud);
        btnSearch = findViewById(R.id.btn_menu_search);
        btnSongRecognition = findViewById(R.id.btn_menu_song_recognition);
        btnDownloads = findViewById(R.id.btn_menu_downloads);
        btnBilibili = findViewById(R.id.btn_menu_bilibili);
        btnRingtones = findViewById(R.id.btn_menu_ringtones);
        btnTopList = findViewById(R.id.btn_menu_toplist);
        btnHistory = findViewById(R.id.btn_menu_history);
        btnProfile = findViewById(R.id.btn_menu_profile);
        btnPersonalFM = findViewById(R.id.btn_menu_personal_fm);
        btnLogin = findViewById(R.id.btn_menu_login);
        btnSettings = findViewById(R.id.btn_menu_settings);

        // 本机音乐：本地播放模式下的主入口（云音乐模式下这个磁贴不显示）
        if (btnLocalMusic != null) {
            btnLocalMusic.setOnClickListener(v ->
                    startActivity(new Intent(this, LocalMusicActivity.class)));
        }

        btnFavorites.setOnClickListener(v ->
                startActivity(new Intent(this, FavoritesListActivity.class)));

        btnMyPlaylists.setOnClickListener(v ->
                startActivity(new Intent(this, MyPlaylistsActivity.class)));

        btnDailyRecommend.setOnClickListener(v ->
                startActivity(new Intent(this, DailyRecommendActivity.class)));

        btnRadarPlaylist.setOnClickListener(v -> openRadarPlaylist());

        btnMusicCloud.setOnClickListener(v ->
                startActivity(new Intent(this, MusicCloudActivity.class)));

        btnSearch.setOnClickListener(v ->
                startActivity(new Intent(this, SearchActivity.class)));

        btnSongRecognition.setOnClickListener(v ->
                startActivity(new Intent(this, SongRecognitionActivity.class)));

        btnDownloads.setOnClickListener(v ->
                startActivity(new Intent(this, DownloadListActivity.class)));

        // 听bilibili：只保留「从BV号打开」与「本机收藏」两条不依赖账号的链路
        // 听bilibili：只保留「从BV号打开」与「本机收藏」两条不依赖账号的链路
        if (btnBilibili != null) {
            btnBilibili.setOnClickListener(v ->
                    startActivity(new Intent(this, BilibiliActivity.class)));
        }

        btnRingtones.setOnClickListener(v ->
                startActivity(new Intent(this, RingtoneListActivity.class)));

        btnTopList.setOnClickListener(v ->
                startActivity(new Intent(this, TopListActivity.class)));

        btnHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));

        btnProfile.setOnClickListener(v ->
                startActivity(new Intent(this, ProfileActivity.class)));

        btnPersonalFM.setOnClickListener(v ->
                startActivity(new Intent(this, PersonalFMActivity.class)));

        btnLogin.setOnClickListener(v ->
                startActivity(new Intent(this, LoginActivity.class)));

        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // Set up right-swipe to go back to player
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 != null && e2 != null) {
                    float diffX = e2.getX() - e1.getX();
                    float diffY = Math.abs(e2.getY() - e1.getY());
                    if (diffX > 80 && diffY < 200 && Math.abs(velocityX) > 200) {
                        finish();
                        return true;
                    }
                }
                return false;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLoginDependentVisibility();
        updateMenuVisibility();
    }

    private void updateLoginDependentVisibility() {
        String cookie = MusicPlayerManager.getInstance().getCookie();
        boolean loggedIn = cookie != null && !cookie.isEmpty() && cookie.contains("MUSIC_U");
        btnProfile.setTag(loggedIn);
        btnMyPlaylists.setTag(loggedIn);
        btnDailyRecommend.setTag(loggedIn);
        btnRadarPlaylist.setTag(loggedIn);
        btnMusicCloud.setTag(loggedIn);
        btnPersonalFM.setTag(true);
        btnLogin.setTag(true);
    }

    private void updateMenuVisibility() {
        // 本机音乐磁贴的显隐完全由播放模式决定，不参与「编辑更多菜单」的勾选体系
        if (btnLocalMusic != null) {
            btnLocalMusic.setVisibility(
                    PlaybackMode.isLocalOnly(this) ? View.VISIBLE : View.GONE);
        }
        applyVisibility(btnFavorites, MoreMenuPreferences.KEY_FAVORITES, false);
        applyVisibility(btnMyPlaylists, MoreMenuPreferences.KEY_MY_PLAYLISTS, true);
        applyVisibility(btnDailyRecommend, MoreMenuPreferences.KEY_DAILY_RECOMMEND, true);
        applyVisibility(btnRadarPlaylist, MoreMenuPreferences.KEY_RADAR_PLAYLIST, true);
        applyVisibility(btnMusicCloud, MoreMenuPreferences.KEY_MUSIC_CLOUD, true);
        applyVisibility(btnSearch, MoreMenuPreferences.KEY_SEARCH, false);
        applyVisibility(btnSongRecognition, MoreMenuPreferences.KEY_SONG_RECOGNITION, false);
        applyVisibility(btnDownloads, MoreMenuPreferences.KEY_DOWNLOADS, false);
        // 不需要登录，所以 requireLogin = false
        applyVisibility(btnBilibili, MoreMenuPreferences.KEY_BILIBILI, false);
        applyVisibility(btnRingtones, MoreMenuPreferences.KEY_RINGTONES, false);
        applyVisibility(btnTopList, MoreMenuPreferences.KEY_TOPLIST, false);
        applyVisibility(btnHistory, MoreMenuPreferences.KEY_HISTORY, false);
        applyVisibility(btnProfile, MoreMenuPreferences.KEY_PROFILE, true);
        applyVisibility(btnPersonalFM, MoreMenuPreferences.KEY_PERSONAL_FM, false);
        applyVisibility(btnLogin, MoreMenuPreferences.KEY_LOGIN, false);
        applyMenuOrder();
    }

    private void applyMenuOrder() {
        if (btnFavorites == null || btnFavorites.getParent() == null) return;
        ViewGroup parent = (ViewGroup) btnFavorites.getParent();
        View titleView = parent.getChildAt(0);

        java.util.Map<String, View> viewByKey = new java.util.LinkedHashMap<>();
        viewByKey.put(MoreMenuPreferences.KEY_FAVORITES, btnFavorites);
        viewByKey.put(MoreMenuPreferences.KEY_MY_PLAYLISTS, btnMyPlaylists);
        viewByKey.put(MoreMenuPreferences.KEY_DAILY_RECOMMEND, btnDailyRecommend);
        viewByKey.put(MoreMenuPreferences.KEY_RADAR_PLAYLIST, btnRadarPlaylist);
        viewByKey.put(MoreMenuPreferences.KEY_MUSIC_CLOUD, btnMusicCloud);
        viewByKey.put(MoreMenuPreferences.KEY_SEARCH, btnSearch);
        viewByKey.put(MoreMenuPreferences.KEY_SONG_RECOGNITION, btnSongRecognition);
        viewByKey.put(MoreMenuPreferences.KEY_DOWNLOADS, btnDownloads);
        viewByKey.put(MoreMenuPreferences.KEY_BILIBILI, btnBilibili);
        viewByKey.put(MoreMenuPreferences.KEY_RINGTONES, btnRingtones);
        viewByKey.put(MoreMenuPreferences.KEY_TOPLIST, btnTopList);
        viewByKey.put(MoreMenuPreferences.KEY_HISTORY, btnHistory);
        viewByKey.put(MoreMenuPreferences.KEY_PROFILE, btnProfile);
        viewByKey.put(MoreMenuPreferences.KEY_PERSONAL_FM, btnPersonalFM);
        viewByKey.put(MoreMenuPreferences.KEY_LOGIN, btnLogin);

        java.util.List<String> order = MoreMenuPreferences.getOrder(prefs);
        parent.removeAllViews();
        parent.addView(titleView);
        // 本地播放模式下把「本机音乐」钉在最前 —— 纯本地播放器最常用的入口就是它。
        // 注意这里必须显式补回：removeAllViews() 之后只会按 order 重建，不在 order 里的
        // 视图会永久消失（这正是「漏注册 ≠ 排到后面，而是整块不见了」的那个坑）。
        if (btnLocalMusic != null
                && btnLocalMusic.getVisibility() == View.VISIBLE) {
            parent.addView(btnLocalMusic);
        }
        for (String key : order) {
            View view = viewByKey.get(key);
            if (view != null) parent.addView(view);
        }
        parent.addView(btnSettings);
    }

    private void applyVisibility(View target, String key, boolean requireLogin) {
        if (target == null) return;
        boolean enabled = MoreMenuPreferences.isEnabled(prefs, key);
        boolean loggedIn = !requireLogin || Boolean.TRUE.equals(target.getTag());
        // 本地播放模式下，依赖账号/网络的云端入口一律隐藏。这里只做「藏起来」，
        // 不动任何播放链路 —— 云音乐模式下所有判定都与改动前完全一致。
        boolean cloudAllowed = !PlaybackMode.isLocalOnly(this)
                || !PlaybackMode.isCloudFeature(key);
        target.setVisibility(enabled && loggedIn && cloudAllowed ? View.VISIBLE : View.GONE);
    }

    private void startPersonalFM() {
        // Kept for compatibility but no longer called; PersonalFMActivity handles this
        startActivity(new Intent(this, PersonalFMActivity.class));
    }

    private void openRadarPlaylist() {
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在获取雷达歌单...", Toast.LENGTH_SHORT).show();
        MusicApiHelper.getRadarPlaylist(cookie, new MusicApiHelper.DailyRecommendPlaylistCallback() {
            @Override
            public void onResult(PlaylistInfo playlist) {
                Intent intent = new Intent(MoreActivity.this, PlaylistDetailActivity.class);
                intent.putExtra("playlist_id", playlist.getId());
                intent.putExtra("playlist_name", playlist.getName());
                intent.putExtra("track_count", playlist.getTrackCount());
                intent.putExtra("creator", playlist.getCreator());
                intent.putExtra("creator_user_id", playlist.getUserId());
                intent.putExtra("is_liked_playlist", playlist.isLikedPlaylist());
                startActivity(intent);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MoreActivity.this, "获取失败: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
