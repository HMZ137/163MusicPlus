package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.WatchUiUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Set;

public class OpenShareLinkActivity extends BaseWatchActivity {

    private static final String TAG = "OpenShareLinkActivity";
    private static final String EXTRA_SHARE_TYPE = "_xtc_api_share_type";
    private static final int MAX_SHARE_TYPE_LENGTH = 64;

    private TextView tvStatus;
    private View errorContainer;
    private TextView tvErrorMessage;
    private TextView tvIntentDump;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_open_share_link);
        // G5：标题居左 + 返回按钮占位（标题原先无 id，识别不到，见技术文档 [1-12]）。
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(
                this, findViewById(R.id.tv_open_share_title));

        tvStatus = findViewById(R.id.tv_status);
        errorContainer = findViewById(R.id.error_container);
        tvErrorMessage = findViewById(R.id.tv_error_message);
        tvIntentDump = findViewById(R.id.tv_intent_dump);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        showLoading("正在解析分享参数…");
        String shareType = getShareTypeValue(intent);
        if (TextUtils.isEmpty(shareType)) {
            // 2026-11-28：除了车机分享（_xtc_api_share_type），也接普通分享文本 / 链接：
            //   · 网易云链接或纯数字 ID → 按 ID 取歌播放
            //   · 音频直链（.mp3/.flac/.m4a…）→ 直接播这个 URL
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (TextUtils.isEmpty(text) && intent.getData() != null) {
                text = intent.getData().toString();
            }
            if (!TextUtils.isEmpty(text)) {
                com.qinghe.music163pro.util.NeteaseLinkParser.Result link =
                        com.qinghe.music163pro.util.NeteaseLinkParser.parse(text);
                if (link.type == com.qinghe.music163pro.util.NeteaseLinkParser.Result.Type.SONG_ID) {
                    playSongById(link.songId, intent);
                    return;
                }
                if (link.type == com.qinghe.music163pro.util.NeteaseLinkParser.Result.Type.DIRECT_AUDIO) {
                    playDirectUrl(link.url, intent);
                    return;
                }
                if (link.type == com.qinghe.music163pro.util.NeteaseLinkParser.Result.Type.SHORT_LINK) {
                    resolveShortLink(link.url, intent);
                    return;
                }
            }
            showError("未获取到可解析的分享内容", intent);
            return;
        }

        shareType = shareType.trim();
        if (shareType.length() > MAX_SHARE_TYPE_LENGTH) {
            showError("参数长度异常: " + shareType.length(), intent);
            return;
        }

        if (!shareType.startsWith("MUSIC_")) {
            showError("参数格式错误: " + shareType, intent);
            return;
        }

        String songIdText = shareType.substring("MUSIC_".length());
        if (TextUtils.isEmpty(songIdText) || !TextUtils.isDigitsOnly(songIdText)) {
            showError("参数格式错误: " + shareType, intent);
            return;
        }

        long songId;
        try {
            songId = Long.parseLong(songIdText);
        } catch (NumberFormatException e) {
            showError("无法解析歌曲ID: " + shareType, intent);
            return;
        }

        playSongById(songId, intent);
    }

    private void playSongById(long songId, Intent sourceIntent) {
        tvStatus.setText("正在加载歌曲信息并跳转主界面…");

        SharedPreferences preferences = getSharedPreferences(WatchUiUtils.SETTINGS_PREFERENCES, MODE_PRIVATE);
        String cookie = preferences.getString("cookie", "");
        MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
        playerManager.setContext(this);
        playerManager.setCookie(cookie);

        MusicApiHelper.getSongDetail(songId, cookie, new MusicApiHelper.SongDetailCallback() {
            @Override
            public void onResult(JSONObject songJson) {
                if (isActivityUnavailable()) {
                    return;
                }
                if (songJson == null) {
                    showError("服务器返回数据为空，无法播放", sourceIntent);
                    return;
                }
                Song song = parseSong(songJson);
                if (song == null) {
                    showError("歌曲信息解析失败，无法播放", sourceIntent);
                    return;
                }

                playerManager.setPlaylist(Collections.singletonList(song), 0);
                playerManager.playCurrent();
                openMainActivity();
            }

            @Override
            public void onError(String error) {
                if (isActivityUnavailable()) {
                    return;
                }
                showError("获取歌曲信息失败: " + error, sourceIntent);
            }
        });
    }

    /**
     * 短链解析 + 按类型路由（2026-11-28 新增）。
     *
     * <p>{@code 163cn.tv/xxx} 跟跳转后可能落到单曲 / 歌单 / 专辑 / MV，
     * 分别打开对应页面；只有单曲走「按 ID 播放」。</p>
     */
    private void resolveShortLink(final String shortUrl, final Intent sourceIntent) {
        tvStatus.setText("正在解析短链…");
        new Thread(() -> {
            com.qinghe.music163pro.util.ShortLinkResolver.Target target = null;
            String error = null;
            try {
                target = com.qinghe.music163pro.util.ShortLinkResolver.resolve(shortUrl);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final com.qinghe.music163pro.util.ShortLinkResolver.Target result = target;
            final String err = error;
            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                if (result == null) {
                    showError("短链解析失败: " + (err != null ? err : "网络异常"), sourceIntent);
                    return;
                }
                routeShortLinkTarget(result, sourceIntent);
            });
        }).start();
    }

    /** 按短链指向的资源类型打开对应页面。 */
    private void routeShortLinkTarget(com.qinghe.music163pro.util.ShortLinkResolver.Target target,
                                      Intent sourceIntent) {
        switch (target.type) {
            case SONG:
                playSongById(target.id, sourceIntent);
                break;
            case PLAYLIST: {
                Intent i = new Intent(this, PlaylistDetailActivity.class);
                i.putExtra("playlist_id", target.id);
                startActivity(i);
                finish();
                break;
            }
            case ALBUM: {
                Intent i = new Intent(this, AlbumDetailActivity.class);
                i.putExtra("album_id", target.id);
                startActivity(i);
                finish();
                break;
            }
            case MV: {
                Intent i = new Intent(this, MvDetailActivity.class);
                i.putExtra("mv_id", target.id);
                startActivity(i);
                finish();
                break;
            }
            default:
                showError("无法识别短链指向的内容：" + target.finalUrl, sourceIntent);
                break;
        }
    }

    /**
     * 直接播放一个网络音频直链（2026-11-28 新增）。
     *
     * <p>不经过网易云接口：构造一个「直链」歌曲塞进播放队列，交给播放器自己流式拉流。</p>
     */
    private void playDirectUrl(String url, Intent sourceIntent) {
        tvStatus.setText("正在播放网络直链…");
        try {
            MusicPlayerManager playerManager = MusicPlayerManager.getInstance();
            playerManager.setContext(this);
            Song song = new Song(0L, "网络直链", "直链播放", "");
            song.setUrl(url);
            song.setSource("url");
            playerManager.setPlaylist(Collections.singletonList(song), 0);
            playerManager.playCurrent();
            openMainActivity();
        } catch (Exception e) {
            showError("直链播放失败: " + e.getMessage(), sourceIntent);
        }
    }

    private void openMainActivity() {
        if (isActivityUnavailable()) {
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void showLoading(String message) {
        tvStatus.setText(message);
        errorContainer.setVisibility(View.GONE);
    }

    private void showError(String message, Intent intent) {
        if (isActivityUnavailable()) {
            return;
        }
        String intentDump = dumpIntent(intent);
        Log.e(TAG, message + " action=" + (intent != null ? intent.getAction() : null)
                + " data=" + (intent != null ? intent.getDataString() : null));
        tvStatus.setText("分享链接打开失败");
        errorContainer.setVisibility(View.VISIBLE);
        tvErrorMessage.setText(message);
        tvIntentDump.setText(intentDump);
    }

    private boolean isActivityUnavailable() {
        return isFinishing() || isDestroyed();
    }

    private String getShareTypeValue(Intent intent) {
        if (intent == null) {
            return null;
        }
        Bundle extras = intent.getExtras();
        if (extras == null || !extras.containsKey(EXTRA_SHARE_TYPE)) {
            return null;
        }
        Object value = extras.get(EXTRA_SHARE_TYPE);
        return value != null ? String.valueOf(value) : null;
    }

    private Song parseSong(JSONObject songJson) {
        if (songJson == null) {
            return null;
        }
        long id = songJson.optLong("id");
        String name = songJson.optString("name", "");
        String album = "";
        JSONObject albumObj = songJson.optJSONObject("al");
        if (albumObj == null) {
            albumObj = songJson.optJSONObject("album");
        }
        if (albumObj != null) {
            album = albumObj.optString("name", "");
        }

        JSONArray artists = songJson.optJSONArray("ar");
        if (artists == null) {
            artists = songJson.optJSONArray("artists");
        }

        StringBuilder artistBuilder = new StringBuilder();
        if (artists != null) {
            for (int i = 0; i < artists.length(); i++) {
                JSONObject artistObj = artists.optJSONObject(i);
                if (artistObj == null) {
                    continue;
                }
                String artistName = artistObj.optString("name", "");
                if (TextUtils.isEmpty(artistName)) {
                    continue;
                }
                if (artistBuilder.length() > 0) {
                    artistBuilder.append(" / ");
                }
                artistBuilder.append(artistName);
            }
        }

        if (id <= 0 || TextUtils.isEmpty(name)) {
            return null;
        }
        return new Song(id, name, artistBuilder.toString(), album);
    }

    private String dumpIntent(Intent intent) {
        if (intent == null) {
            return "Intent = null";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("action = ").append(intent.getAction()).append('\n');
        builder.append("data = ").append(intent.getDataString()).append('\n');
        builder.append("type = ").append(intent.getType()).append('\n');
        builder.append("package = ").append(intent.getPackage()).append('\n');
        builder.append("component = ").append(intent.getComponent()).append('\n');

        Set<String> categories = intent.getCategories();
        builder.append("categories = ").append(categories == null ? "[]" : categories.toString()).append('\n');

        Bundle extras = intent.getExtras();
        if (extras == null || extras.isEmpty()) {
            builder.append("extras = {}");
            return builder.toString();
        }

        builder.append("extras = {\n");
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            builder.append("  ").append(key).append(" = ").append(String.valueOf(value)).append('\n');
        }
        builder.append('}');
        return builder.toString();
    }
}
