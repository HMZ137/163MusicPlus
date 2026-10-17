package com.qinghe.music163pro.fragment;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.activity.ListeningRankActivity;
import com.qinghe.music163pro.activity.PlaylistDetailActivity;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.WatchMenuDialog;

import java.util.Collections;
import java.util.List;

/**
 * 4-16：我的歌单 —— 「在线歌单」页。
 *
 * <p>改造前是 MyPlaylistsActivity 的 TAB_ONLINE 分支：拉账号歌单（创建 + 收藏）、
 * 首个条目为「我的听歌排行」入口、长按分「本人创建（改名/复制链接/删除）」与
 * 「收藏的（复制链接/取消收藏）」两套菜单、顶栏 + 新建云歌单。</p>
 */
public class OnlinePlaylistsFragment extends MyPlaylistsPageFragment {

    private static final String PREFS = "music163_settings";
    private static final String KEY_RANK_NICK = "rank_entry_nickname";
    private static final String KEY_RANK_COUNT = "rank_entry_count";

    /** 当前登录用户 id（从「我喜欢的音乐」歌单的 creatorId 推断，用于区分本人/收藏歌单）。 */
    private long currentUserId = -1;

    @Override
    public int pageIndex() {
        return PAGE_ONLINE;
    }

    @Override
    protected CharSequence pageName() {
        return "在线歌单";
    }

    // ── 加载 ────────────────────────────────────────────────────────────────

    @Override
    protected void loadData() {
        String cookie = cookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            applyItems(Collections.emptyList());
            setStatus("请先登录", false);
            return;
        }
        MusicApiHelper.getUserPlaylists(cookie, new MusicApiHelper.UserPlaylistsCallback() {
            @Override
            public void onResult(List<PlaylistInfo> playlists) {
                if (!isViewReady()) {
                    return;
                }
                // 先识别当前用户：行图标要按「本人 / 收藏 / 我喜欢」分三种画法
                if (!playlists.isEmpty() && playlists.get(0).isLikedPlaylist()) {
                    currentUserId = playlists.get(0).getUserId();
                }
                applyItems(playlists);
                updateCountStatus("暂无歌单");
                ensureRankNicknameCached();
            }

            @Override
            public void onError(String message) {
                if (!isViewReady()) {
                    return;
                }
                clearPendingScroll();
                setStatus("加载失败: " + message, false);
            }
        });
    }

    @Override
    protected PlaylistInfo createRankEntry() {
        int count = rankPrefs().getInt(KEY_RANK_COUNT, 0);
        String nick = rankPrefs().getString(KEY_RANK_NICK, null);
        PlaylistInfo entry = new PlaylistInfo(RANK_ENTRY_ID, "我的听歌排行", count,
                nick != null && !nick.isEmpty() ? nick : "");
        entry.setUserId(-1);
        return entry;
    }

    @Override
    protected void openRankPage() {
        startActivity(new Intent(requireContext(), ListeningRankActivity.class));
    }

    // ── 行渲染 / 交互 ───────────────────────────────────────────────────────

    @Override
    protected void bindRow(View row, PlaylistInfo pl) {
        ImageView icon = row.findViewById(R.id.iv_playlist_icon);
        TextView tvName = row.findViewById(R.id.tv_name);
        TextView tvDetail = row.findViewById(R.id.tv_detail);
        if (pl.getCoverUrl() != null && !pl.getCoverUrl().isEmpty()) {
            icon.clearColorFilter();
            NetworkImageLoader.load(icon, pl.getCoverUrl());
        } else if (pl.isLikedPlaylist()) {
            icon.clearColorFilter();
            icon.setImageResource(R.drawable.ic_favorite);
            icon.setColorFilter(0xFFFF4081);
        } else if (pl.getUserId() == currentUserId) {
            icon.clearColorFilter();
            icon.setImageResource(R.drawable.ic_music_note);
            icon.setColorFilter(0xFFBB86FC);
        } else {
            icon.clearColorFilter();
            icon.setImageResource(R.drawable.ic_queue_music);
            icon.setColorFilter(0x80FFFFFF);
        }
        tvName.setText(pl.getName());
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        String detail = pl.getTrackCount() + "首";
        if (pl.getCreator() != null && !pl.getCreator().isEmpty()) {
            detail += " · " + pl.getCreator();
        }
        tvDetail.setText(detail);
        tvDetail.setTextColor(0xB3FFFFFF);
        tvDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
    }

    @Override
    protected void onRowClicked(PlaylistInfo pl) {
        Intent intent = new Intent(requireContext(), PlaylistDetailActivity.class);
        intent.putExtra("playlist_id", pl.getId());
        intent.putExtra("playlist_name", pl.getName());
        intent.putExtra("track_count", pl.getTrackCount());
        intent.putExtra("creator", pl.getCreator());
        intent.putExtra("creator_user_id", pl.getUserId());
        intent.putExtra("is_liked_playlist", pl.isLikedPlaylist());
        startActivity(intent);
    }

    @Override
    protected boolean onRowLongClicked(PlaylistInfo pl) {
        if (pl.isLikedPlaylist()) {
            // 「我喜欢的音乐」不支持改名/删除
            return true;
        }
        String cookie = cookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            toast("请先登录");
            return true;
        }
        if (pl.getUserId() == currentUserId) {
            showOwnPlaylistOptions(pl, cookie);
        } else {
            showSubscribedPlaylistOptions(pl, cookie);
        }
        return true;
    }

    // ── 新建（云歌单） ──────────────────────────────────────────────────────

    @Override
    public void showCreatePlaylistDialog() {
        String cookie = cookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            toast("请先登录");
            return;
        }
        EditText input = new EditText(requireContext());
        input.setHint("输入歌单名称");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);

        LinearLayout container = new LinearLayout(requireContext());
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(requireContext())
                .setTitle("创建歌单")
                .setView(container)
                .setPositiveButton("创建", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("请输入歌单名称");
                        return;
                    }
                    createPlaylist(name, cookie);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createPlaylist(String name, String cookie) {
        toast("正在创建...");
        MusicApiHelper.createPlaylist(name, 0, cookie, new MusicApiHelper.PlaylistCreateCallback() {
            @Override
            public void onResult(long playlistId, String resultName) {
                if (!isViewReady()) {
                    return;
                }
                toast("已创建「" + resultName + "」");
                loadData();
            }

            @Override
            public void onError(String message) {
                if (isViewReady()) {
                    toast("创建失败: " + message);
                }
            }
        });
    }

    // ── 长按菜单与二级操作（改名 / 复制链接 / 删除 / 取消收藏） ──────────────

    private void showOwnPlaylistOptions(PlaylistInfo pl, String cookie) {
        WatchMenuDialog.show(requireContext(), pl.getName(),
                new String[]{"改名", "复制链接", "删除"},
                WatchMenuDialog.NO_SELECTION, 2,
                which -> {
                    if (which == 0) {
                        showRenamePlaylistDialog(pl, cookie);
                    } else if (which == 1) {
                        copyPlaylistLink(pl);
                    } else {
                        showDeletePlaylistDialog(pl, cookie);
                    }
                });
    }

    private void showSubscribedPlaylistOptions(PlaylistInfo pl, String cookie) {
        WatchMenuDialog.show(requireContext(), pl.getName(),
                new String[]{"复制链接", "取消收藏"},
                WatchMenuDialog.NO_SELECTION, 1,
                which -> {
                    if (which == 0) {
                        copyPlaylistLink(pl);
                    } else {
                        showUnsubscribeDialog(pl, cookie);
                    }
                });
    }

    private void copyPlaylistLink(PlaylistInfo pl) {
        if (pl == null || pl.getId() <= 0) {
            toast("该歌单没有可用链接");
            return;
        }
        String url = "https://music.163.com/playlist?id=" + pl.getId();
        ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            toast("复制失败");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("网易云歌单链接", url));
        toastLong("已复制链接：" + url);
    }

    private void showRenamePlaylistDialog(PlaylistInfo pl, String cookie) {
        EditText input = new EditText(requireContext());
        input.setText(pl.getName());
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);
        input.setSelectAllOnFocus(true);

        LinearLayout container = new LinearLayout(requireContext());
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(requireContext())
                .setTitle("改名")
                .setView(container)
                .setPositiveButton("确认", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) {
                        toast("名称不能为空");
                        return;
                    }
                    MusicApiHelper.updatePlaylistName(pl.getId(), newName, cookie,
                            new MusicApiHelper.PlaylistActionCallback() {
                                @Override
                                public void onResult(boolean success) {
                                    if (!isViewReady()) {
                                        return;
                                    }
                                    if (success) {
                                        toast("已重命名");
                                        loadData();
                                    } else {
                                        toast("改名失败");
                                    }
                                }

                                @Override
                                public void onError(String message) {
                                    if (isViewReady()) {
                                        toast("改名失败: " + message);
                                    }
                                }
                            });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeletePlaylistDialog(PlaylistInfo pl, String cookie) {
        new AlertDialog.Builder(requireContext())
                .setTitle("删除歌单")
                .setMessage("确定删除「" + pl.getName() + "」？此操作不可撤销。")
                .setPositiveButton("删除", (d, w) -> MusicApiHelper.deletePlaylist(pl.getId(), cookie,
                        new MusicApiHelper.PlaylistActionCallback() {
                            @Override
                            public void onResult(boolean success) {
                                if (!isViewReady()) {
                                    return;
                                }
                                if (success) {
                                    toast("已删除");
                                    loadData();
                                } else {
                                    toast("删除失败");
                                }
                            }

                            @Override
                            public void onError(String message) {
                                if (isViewReady()) {
                                    toast("删除失败: " + message);
                                }
                            }
                        }))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showUnsubscribeDialog(PlaylistInfo pl, String cookie) {
        new AlertDialog.Builder(requireContext())
                .setTitle("取消收藏")
                .setMessage("确定取消收藏歌单「" + pl.getName() + "」？")
                .setPositiveButton("确认", (d, w) -> MusicApiHelper.subscribePlaylist(pl.getId(), false,
                        cookie, new MusicApiHelper.PlaylistActionCallback() {
                            @Override
                            public void onResult(boolean success) {
                                if (!isViewReady()) {
                                    return;
                                }
                                if (success) {
                                    toast("已取消收藏");
                                    loadData();
                                } else {
                                    toast("操作失败");
                                }
                            }

                            @Override
                            public void onError(String message) {
                                if (isViewReady()) {
                                    toast("操作失败: " + message);
                                }
                            }
                        }))
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 账号昵称缓存（排行入口副标题用） ─────────────────────────────────────

    private SharedPreferences rankPrefs() {
        return requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String cookie() {
        return MusicPlayerManager.getInstance().getCookie();
    }

    /**
     * 副标题的「昵称」来源：优先用缓存（由听歌排行页写入）；首次未缓存时取一次账号信息并缓存。
     */
    private void ensureRankNicknameCached() {
        String nick = rankPrefs().getString(KEY_RANK_NICK, null);
        if (nick != null && !nick.isEmpty()) {
            return;
        }
        String cookie = cookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            return;
        }
        MusicApiHelper.getUserAccount(cookie, new MusicApiHelper.AccountCallback() {
            @Override
            public void onResult(org.json.JSONObject accountJson) {
                if (!isViewReady()) {
                    return;
                }
                org.json.JSONObject profile = accountJson.optJSONObject("profile");
                String n = profile != null ? profile.optString("nickname", null) : null;
                if (n != null && !n.isEmpty()) {
                    rankPrefs().edit().putString(KEY_RANK_NICK, n).apply();
                    refreshRankEntry();
                }
            }

            @Override
            public void onError(String message) {
            }
        });
    }
}
