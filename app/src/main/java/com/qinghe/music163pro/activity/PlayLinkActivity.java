package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.DirectPlayback;
import com.qinghe.music163pro.util.NeteaseLinkParser;
import com.qinghe.music163pro.util.ShortLinkResolver;
import com.qinghe.music163pro.util.UiKit;
import com.qinghe.music163pro.util.WatchBackButton;

/**
 * 「打开链接」页（2026-11-28）。
 *
 * <p>一个输入框吃三种东西：</p>
 * <ul>
 *   <li>音频直链（{@code http://ip:port/xx.mp3}）→ 直接出流播放；</li>
 *   <li>网易云歌曲链接 / 纯 ID（{@code music.163.com/song?id=…}）→ 按 ID 取歌播放；</li>
 *   <li>短链（{@code 163cn.tv/xxx}）→ 联网跟跳转拿 ID 再播。</li>
 * </ul>
 *
 * <p>这个页面也给「搜索框粘贴链接」兜底：搜索只认 ID/短链/直链，其它杂七杂八的文本
 * 还是走搜索，不想搜就直接来这儿播。</p>
 */
public class PlayLinkActivity extends BaseWatchActivity {

    private EditText etLink;
    private TextView tvStatus;
    private TextView btnPlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.bg_dark));
        root.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(10));

        TextView title = new TextView(this);
        title.setText("打开链接");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.watch_title_text_size));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dpToPx(4), 0, dpToPx(8));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        WatchBackButton.applyTitleBarInset(this, title);

        TextView hint = new TextView(this);
        hint.setText("直链 / 网易云歌曲链接 / 歌曲 ID / 163cn.tv 短链");
        hint.setTextColor(UiKit.COLOR_TEXT_MUTED);
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dpToPx(8));
        root.addView(hint);

        etLink = new EditText(this);
        etLink.setHint("https://…  或  1974443814");
        etLink.setTextColor(0xFFFFFFFF);
        etLink.setHintTextColor(UiKit.COLOR_TEXT_MUTED);
        etLink.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        etLink.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etLink.setBackgroundResource(R.drawable.bg_search_box);
        etLink.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
        etLink.setMinLines(2);
        etLink.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.bottomMargin = dpToPx(8);
        etLink.setLayoutParams(etLp);
        root.addView(etLink);

        tvStatus = new TextView(this);
        tvStatus.setTextColor(UiKit.COLOR_TEXT_MUTED);
        tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, 0, 0, dpToPx(6));
        root.addView(tvStatus);

        btnPlay = UiKit.m3Button(this, "解析并播放", true);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.bottomMargin = dpToPx(6);
        btnPlay.setLayoutParams(btnLp);
        btnPlay.setOnClickListener(v -> parseAndPlay());
        root.addView(btnPlay);

        TextView btnPaste = UiKit.m3Button(this, "从剪贴板填入", false);
        btnPaste.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (text != null && text.length() > 0) {
                    etLink.setText(text.toString().trim());
                    tvStatus.setText("已填入剪贴板内容");
                    return;
                }
            }
            UiKit.toast(this, "剪贴板是空的");
        });
        root.addView(btnPaste);

        setContentView(root);
    }

    private void parseAndPlay() {
        String raw = etLink.getText() == null ? "" : etLink.getText().toString().trim();
        if (raw.isEmpty()) {
            UiKit.toast(this, "先粘贴一个链接或歌曲 ID");
            return;
        }
        final NeteaseLinkParser.Result link = NeteaseLinkParser.parse(raw);
        switch (link.type) {
            case DIRECT_AUDIO:
                tvStatus.setText("正在播放网络直链…");
                if (DirectPlayback.play(this, link.url)) {
                    openPlayer();
                } else {
                    tvStatus.setText("直链播放失败");
                }
                return;
            case SONG_ID:
                tvStatus.setText("正在按 ID 加载歌曲…");
                playById(link.songId);
                return;
            case SHORT_LINK:
                tvStatus.setText("正在解析短链…");
                resolveShortLink(link.url);
                return;
            default:
                // 网易云歌单 / 专辑 / 认不出的文本：明确告知，而不是静默失败
                tvStatus.setText("这条链接不是单曲，暂不支持（只支持歌曲/直链）");
        }
    }

    private void resolveShortLink(final String shortUrl) {
        new Thread(() -> {
            String id = null;
            try {
                ShortLinkResolver.Target target = ShortLinkResolver.resolve(shortUrl);
                if (target != null && target.id > 0) {
                    id = String.valueOf(target.id);
                }
            } catch (Throwable ignored) {
            }
            final String resolved = id;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (resolved == null) {
                    tvStatus.setText("短链解析失败，可换成完整链接或歌曲 ID");
                    return;
                }
                playById(Long.parseLong(resolved));
            });
        }, "play-link-short").start();
    }

    private void playById(final long songId) {
        android.content.SharedPreferences prefs =
                getSharedPreferences("music163_settings", MODE_PRIVATE);
        final String cookie = prefs.getString("cookie", "");
        com.qinghe.music163pro.api.MusicApiHelper.getSongById(songId, cookie,
                new com.qinghe.music163pro.api.MusicApiHelper.SearchCallback() {
                    @Override
                    public void onResult(java.util.List<com.qinghe.music163pro.model.Song> result) {
                        if (isFinishing()) {
                            return;
                        }
                        if (result == null || result.isEmpty()) {
                            tvStatus.setText("没取到这首歌（可能已下架）");
                            return;
                        }
                        com.qinghe.music163pro.model.Song song = result.get(0);
                        com.qinghe.music163pro.player.MusicPlayerManager pm =
                                com.qinghe.music163pro.player.MusicPlayerManager.getInstance();
                        pm.setContext(PlayLinkActivity.this);
                        pm.setPlaylist(java.util.Collections.singletonList(song), 0);
                        pm.playCurrent();
                        openPlayer();
                    }

                    @Override
                    public void onError(String message) {
                        if (isFinishing()) {
                            return;
                        }
                        tvStatus.setText("取歌失败：" + message);
                    }
                });
    }

    private void openPlayer() {
        startActivity(new android.content.Intent(this, MainActivity.class));
        finish();
    }
}
