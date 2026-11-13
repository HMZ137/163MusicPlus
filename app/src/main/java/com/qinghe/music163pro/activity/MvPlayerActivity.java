package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.MusicLog;

/**
 * ExoPlayer-based MV playback page.
 */
public class MvPlayerActivity extends BaseWatchActivity {

    private static final String TAG = "MvPlayerActivity";

    private long mvId;
    private String mvName;
    private String cookie;

    private PlayerView playerView;
    private ImageButton btnBack;
    private TextView tvTitle;
    private TextView tvLoading;
    private ExoPlayer player;
    private String playbackUrl;
    private long playbackPosition;
    private boolean playWhenReady = true;
    private boolean activityStarted;

    /**
     * 页面标题栏里已经有返回控件，不再叠加左上角浮动返回按钮，避免出现两个返回入口。
     */
    @Override
    protected boolean shouldShowBackButton() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mv_player);

        mvId = getIntent().getLongExtra("mv_id", 0);
        mvName = getIntent().getStringExtra("mv_name");
        cookie = getIntent().getStringExtra("cookie");
        if (cookie == null || cookie.isEmpty()) {
            cookie = MusicPlayerManager.getInstance().getCookie();
        }

        playerView = findViewById(R.id.player_view_mv);
        btnBack = findViewById(R.id.btn_mv_player_back);
        tvTitle = findViewById(R.id.tv_mv_player_title);
        tvLoading = findViewById(R.id.tv_mv_player_loading);

        playerView.setKeepScreenOn(true);
        btnBack.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        tvTitle.setText(mvName != null && !mvName.isEmpty() ? mvName : "MV播放");

        if (mvId <= 0) {
            com.qinghe.music163pro.util.UiKit.toast(this, "MV地址无效");
            finish();
            return;
        }

        MusicLog.op(TAG, "打开MV播放器", "mvId=" + mvId + " mvName=" + mvName);
        fetchMvUrlAndPlay();
    }

    private void fetchMvUrlAndPlay() {
        showLoading(true, "正在获取MV地址...");
        MusicApiHelper.getMvUrl(mvId, cookie, new MusicApiHelper.UrlCallback() {
            @Override
            public void onResult(String url) {
                if (url == null || url.isEmpty()) {
                    onError("MV地址为空");
                    return;
                }
                if (!activityStarted) {
                    playbackUrl = url;
                    showLoading(false, "");
                    return;
                }
                startPlayback(url);
            }

            @Override
            public void onError(String message) {
                showLoading(false, "");
                com.qinghe.music163pro.util.UiKit.toast(MvPlayerActivity.this, "播放失败: " + message);
            }
        });
    }

    private void startPlayback(String url) {
        playbackUrl = url;
        releasePlayer();
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        if (playbackPosition > 0) {
            player.seekTo(playbackPosition);
        }
        player.setPlayWhenReady(playWhenReady);
        showLoading(false, "");
    }

    private void showLoading(boolean loading, String text) {
        tvLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        tvLoading.setText(text);
        playerView.setUseController(!loading);
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        if (player == null && playbackUrl != null && !playbackUrl.isEmpty()) {
            startPlayback(playbackUrl);
        }
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        super.onStop();
        releasePlayer();
    }

    private void releasePlayer() {
        if (player != null) {
            playbackPosition = player.getCurrentPosition();
            playWhenReady = player.getPlayWhenReady();
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
    }
}
