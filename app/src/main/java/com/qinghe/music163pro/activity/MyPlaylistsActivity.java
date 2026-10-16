package com.qinghe.music163pro.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.LocalPlaylistManager;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.PinyinUtils;
import com.qinghe.music163pro.util.PlaybackMode;
import com.qinghe.music163pro.util.UiKit;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 「我的歌单」页：内部分两个二级界面（Tab）——「在线歌单」与「本地歌单」。
 *
 * <p>在线歌单走网易云接口（创建 / 改名 / 删除 / 取消收藏，点开进 {@link PlaylistDetailActivity}）；
 * 本地歌单走 {@link LocalPlaylistManager}（创建 / 改名 / 删除 / 导入 / 导出，点开进
 * {@link LocalPlaylistDetailActivity}）。两个 Tab 始终并存，互不替换 —— 在线功能在任何模式下
 * 都不隐藏，本地功能在纯本地场景下也能直接用到。</p>
 *
 * <p>默认落在哪个 Tab：若当前「不走云端收藏」（本地播放模式，或用户选了本地收藏），默认开本地
 * Tab；否则默认在线 Tab。用户可随时手动切换。</p>
 *
 * <p>顶栏右侧「+」按当前 Tab 语义创建；「A-Z」首字母跳转与两套列表通用；「导入歌单」按钮仅在
 * 本地 Tab 出现。设计尺寸针对手表屏（约 320×360）。</p>
 */
public class MyPlaylistsActivity extends BaseWatchActivity {

    /** 二级界面：在线歌单。 */
    private static final int TAB_ONLINE = 0;
    /** 二级界面：本地歌单。 */
    private static final int TAB_LOCAL = 1;

    private ListView lvPlaylists;
    private final List<PlaylistInfo> displayList = new ArrayList<>();
    private ArrayAdapter<PlaylistInfo> adapter;
    private TextView tvStatus;
    private long currentUserId = -1;

    /**
     * 「听歌排行」入口的伪歌单 id。它作为 {@link #displayList} 的第一个条目，
     * 与歌单条目同款外观、**随列表一起滚动**（不是固定行）；真实歌单 id 为正值或 -1，
     * 故用 -1001 区分。它不参与排序，也不进 A-Z 字母分组。
     */
    private static final long RANK_ENTRY_ID = -1001L;
    /** 排行入口副标题的缓存键（本页与 ListeningRankActivity 共同读写）。 */
    private static final String KEY_RANK_NICK = "rank_entry_nickname";
    private static final String KEY_RANK_COUNT = "rank_entry_count";

    /** 当前选中的二级界面（默认见下方 onCreate 内的判定）。 */
    private int activeTab = TAB_ONLINE;
    private TextView tabOnline;
    private TextView tabLocal;
    /** 「导入歌单」按钮所在的整行，仅本地 Tab 显示。 */
    private View importRow;

    /** 打开字母选择页的请求码。 */
    private static final int REQUEST_LETTER_PICKER = 0x52;
    /** 分组首字母（与 starts/counts 等长），传给字母选择页。 */
    private final List<Character> groupLetters = new ArrayList<>();
    private int[] groupStarts = new int[0];
    private int[] groupCounts = new int[0];
    /** 字母跳转后，onResume 会重新拉列表，这里先记下目标位，等拉完再跳过去。 */
    private int pendingScrollTo = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // 纯本地播放模式：页内只保留「本地歌单」一个二级界面 —— 在线歌单必然依赖账号与网络，
        // 在这种模式下即便显示也只会是「请先登录」，索性不出现，避免误导。
        boolean localOnlyMode = PlaybackMode.isLocalOnly(this);
        // 纯在线模式：本地入口整体收起 → 只保留「在线歌单」这一栏
        final boolean onlineOnlyMode = PlaybackMode.isOnlineOnly(this);
        // 默认 Tab：不走云端收藏（本地播放模式 / 本地收藏）时默认本地，否则默认在线。
        activeTab = onlineOnlyMode ? TAB_ONLINE
                : ((!localOnlyMode && PlaybackMode.isCloudFavoriteEnabled(this)) ? TAB_ONLINE : TAB_LOCAL);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(px(6), px(6), px(6), px(6));

        // ---- 标题行：标题（固定「我的歌单」）+ A-Z + 新建 ----
        android.widget.RelativeLayout titleRow = new android.widget.RelativeLayout(this);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("我的歌单");
        tvTitle.setTextColor(0xFFFFFFFF);
        // 字号直接取全局标题资源（@dimen/watch_title_text_size = 16sp），与「收藏」
        // 「下载」「本地音乐」等 XML 页面的标题完全一致。
        // 早先这里写的是 px(15) —— px() 按屏宽比例缩放（320px 基准 → 本机 ×1.18），
        // 实际只有约 18px，比其它页面小了一档，看着偏小（用户反馈）。
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.watch_title_text_size));
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        // 只留很小的左右内边距：返回按钮的占位已由 applyTitleBarInset 加到标题行（titleRow）的
        // paddingStart 上，这里再叠 28px 就是「双重预留」—— 全局缩放后可用宽度被压到 100px 上下，
        // 「我的歌单」四个字放不下就被挤成竖排。单行 + 截断兜底，保证任何宽度下都不会竖排。
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvTitle.setPadding(px(4), 0, px(4), px(4));
        android.widget.RelativeLayout.LayoutParams titleParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        titleParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        tvTitle.setLayoutParams(titleParams);
        titleRow.addView(tvTitle);

        // "+" button (top-right)
        // 放大到 40px 并保留点击热区：此前只有 24px，低于触控下限，表盘上极易点不中。
        ImageView btnCreate = new ImageView(this);
        btnCreate.setImageResource(R.drawable.ic_add_box);
        btnCreate.setColorFilter(0xFFBB86FC);
        btnCreate.setClickable(true);
        btnCreate.setFocusable(true);
        android.widget.RelativeLayout.LayoutParams btnParams = new android.widget.RelativeLayout.LayoutParams(
                px(40), px(40));
        btnParams.setMarginEnd(px(2));
        btnParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
        btnParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        int idCreate = View.generateViewId();
        btnCreate.setId(idCreate);
        btnCreate.setLayoutParams(btnParams);
        btnCreate.setOnClickListener(v -> showCreatePlaylistDialog());
        titleRow.addView(btnCreate);

        // 「A-Z」首字母跳转：复用 LetterPickerActivity + PinyinUtils（与本地音乐页同一套）。
        // 放在「+」左侧，并让标题用 LEFT_OF 让位，避免标题文字压住 A-Z。
        // 放大字号 + 加大热区 + 卡片底，表盘上能稳定点中。
        TextView btnAZ = new TextView(this);
        btnAZ.setText("A-Z");
        btnAZ.setTextColor(0xFFBB86FC);
        btnAZ.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(16));
        btnAZ.setPadding(px(12), px(6), px(12), px(6));
        btnAZ.setMinWidth(px(40));
        btnAZ.setMinHeight(px(34));
        btnAZ.setGravity(Gravity.CENTER);
        btnAZ.setBackgroundResource(R.drawable.bg_letter_cell);
        btnAZ.setClickable(true);
        btnAZ.setFocusable(true);
        int idAZ = View.generateViewId();
        btnAZ.setId(idAZ);
        android.widget.RelativeLayout.LayoutParams azParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        azParams.addRule(android.widget.RelativeLayout.LEFT_OF, idCreate);
        azParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
        azParams.setMarginEnd(px(4));
        btnAZ.setLayoutParams(azParams);
        btnAZ.setOnClickListener(v -> openLetterPicker());
        titleRow.addView(btnAZ);

        // 标题在 A-Z 左侧收尾，不再与标题文字重叠。
        titleParams.addRule(android.widget.RelativeLayout.LEFT_OF, idAZ);
        // 标题字号换成全局 16sp 后变宽，居中时会往左伸到浮动返回键上（实机截图：「我」压住 X）。
        // 这里只给**标题这一行**加一个按键宽度的左外边距，让它在「返回键右缘 ↔ A-Z 左缘」
        // 之间居中；标题行整体与下面的标签行仍然不避让（标签行保持满宽）。
        titleParams.leftMargin =
                com.qinghe.music163pro.util.WatchBackButton.getBackButtonSize(this);

        root.addView(titleRow);
        // 标题行只做**竖直**避让：撑到浮动返回键底边之下（否则 X 按钮会探进标题行 /
        // 压在第一个标签上），横向不加内边距 —— 本页标题居中、标签行要占满整行宽度，
        // 整行横向避让会让标题偏右、标签被推窄（用户明确要求「取消避让」）。
        // 额外压 px(8)：本页只有两个等宽标签，「在线歌单」中心在 x≈98，横向压根碰不到
        // 返回键（它只占 x≤86），所以标签行整体上移一点是安全的（详见方法注释）。
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarTopInset(this, titleRow, px(8));

        // ---- 二级界面切换（在线 / 本地），等宽平分 ----
        // 样式统一走 UiKit.tab()：纯文字、无底衬、仅颜色区分选中（与「收藏」页一致）。
        LinearLayout tabRow = UiKit.tabRow(this);
        // 纯本地播放模式下不添加在线 Tab（tabOnline 保持 null，updateTabUi 会跳过其样式设置），
        // 「本地歌单」占满整行。
        if (!localOnlyMode) {
            tabOnline = makeTab("在线歌单", TAB_ONLINE);
            tabRow.addView(tabOnline, UiKit.tabRowParams());
        }
        // 纯在线模式反之：不添加「本地歌单」Tab，只留在线
        if (!onlineOnlyMode) {
            tabLocal = makeTab("本地歌单", TAB_LOCAL);
            tabRow.addView(tabLocal, UiKit.tabRowParams());
        }
        root.addView(tabRow);

        // ---- 「N 个歌单」+「导入歌单」同一行 ----
        // 早先是两个独立行：导入按钮自己占一行、状态文案再占一行，白白吃掉两行高度。
        // 现在合成一行：文案占满剩余宽度（在线 Tab 下就是整行），按钮贴右（仅本地 Tab 显示）。
        TextView btnImport = new TextView(this);
        btnImport.setText("导入歌单");
        btnImport.setTextColor(0xFFBB86FC);
        btnImport.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        btnImport.setPadding(px(14), px(4), px(14), px(4));
        btnImport.setBackgroundResource(R.drawable.bg_letter_cell);
        btnImport.setClickable(true);
        btnImport.setFocusable(true);
        btnImport.setOnClickListener(v -> onImportClicked());
        btnImport.setVisibility(View.GONE);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, px(1), 0, px(1));

        // Status text：左对齐当分节标题用（在线 Tab 整行只有它，字号与内边距都收窄了）
        tvStatus = new TextView(this);
        tvStatus.setText("正在加载...");
        tvStatus.setTextColor(0x80FFFFFF);
        tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        tvStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tvStatus.setSingleLine(true);
        tvStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvStatus.setPadding(px(2), 0, px(2), 0);
        statusRow.addView(tvStatus, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusRow.addView(btnImport);
        root.addView(statusRow);
        // importRow 现在只指**按钮**本身：切换 Tab 时只收起按钮，这一行始终在
        importRow = btnImport;

        lvPlaylists = new ListView(this);
        lvPlaylists.setDividerHeight(1);
        lvPlaylists.setDivider(getResources().getDrawable(android.R.color.transparent));
        LinearLayout.LayoutParams lvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        lvPlaylists.setLayoutParams(lvParams);
        lvPlaylists.setVisibility(View.GONE);
        root.addView(lvPlaylists);

        setContentView(root);

        adapter = new ArrayAdapter<PlaylistInfo>(this,
                R.layout.item_my_playlist, R.id.tv_name, displayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    view = getLayoutInflater().inflate(R.layout.item_my_playlist, parent, false);
                }
                PlaylistInfo pl = getItem(position);
                if (isRankEntry(pl)) {
                    // 排行入口行：与「本地歌单」条目同样式（音符图标 + 主色），文案格式也一致。
                    ImageView rIcon = view.findViewById(R.id.iv_playlist_icon);
                    rIcon.clearColorFilter();
                    rIcon.setImageResource(R.drawable.ic_music_note);
                    rIcon.setColorFilter(0xFFBB86FC);
                    TextView rName = view.findViewById(R.id.tv_name);
                    rName.setText(pl.getName());
                    rName.setTextColor(0xFFFFFFFF);
                    rName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
                    String d = pl.getTrackCount() + "首";
                    if (pl.getCreator() != null && !pl.getCreator().isEmpty()) {
                        d += " · " + pl.getCreator();
                    }
                    TextView rDetail = view.findViewById(R.id.tv_detail);
                    rDetail.setText(d);
                    rDetail.setTextColor(0xB3FFFFFF);
                    rDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
                    return view;
                }
                if (pl != null) {
                    ImageView icon = view.findViewById(R.id.iv_playlist_icon);
                    TextView tvName = view.findViewById(R.id.tv_name);
                    TextView tvDetail = view.findViewById(R.id.tv_detail);

                    // Load playlist cover image; fall back to static drawable if no cover URL
                    if (activeTab == TAB_LOCAL) {
                        // 本地歌单统一用音符图标，不区分归属。
                        icon.setImageResource(R.drawable.ic_music_note);
                        icon.setColorFilter(0xFFBB86FC);
                    } else if (pl.getCoverUrl() != null && !pl.getCoverUrl().isEmpty()) {
                        icon.clearColorFilter();
                        NetworkImageLoader.load(icon, pl.getCoverUrl());
                    } else if (pl.isLikedPlaylist()) {
                        icon.setImageResource(R.drawable.ic_favorite);
                        icon.setColorFilter(0xFFFF4081);
                    } else if (pl.getUserId() == currentUserId) {
                        icon.setImageResource(R.drawable.ic_music_note);
                        icon.setColorFilter(0xFFBB86FC);
                    } else {
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
                return view;
            }
        };
        lvPlaylists.setAdapter(adapter);
        NetworkImageLoader.attachListViewOptimization(lvPlaylists, adapter, position ->
                position >= 0 && position < displayList.size()
                        ? displayList.get(position).getCoverUrl() : null);

        lvPlaylists.setOnItemClickListener((parent, view, position, id) -> {
            PlaylistInfo pl = displayList.get(position);
            if (isRankEntry(pl)) {
                openRankPage();
                return;
            }
            if (activeTab == TAB_LOCAL) {
                Intent intent = new Intent(this, LocalPlaylistDetailActivity.class);
                intent.putExtra("playlist_name", pl.getName());
                startActivity(intent);
                return;
            }
            Intent intent = new Intent(this, PlaylistDetailActivity.class);
            intent.putExtra("playlist_id", pl.getId());
            intent.putExtra("playlist_name", pl.getName());
            intent.putExtra("track_count", pl.getTrackCount());
            intent.putExtra("creator", pl.getCreator());
            intent.putExtra("creator_user_id", pl.getUserId());
            intent.putExtra("is_liked_playlist", pl.isLikedPlaylist());
            startActivity(intent);
        });

        lvPlaylists.setOnItemLongClickListener((parent, view, position, id) -> {
            PlaylistInfo pl = displayList.get(position);
            if (isRankEntry(pl)) {
                return true; // 入口行无长按菜单
            }
            if (activeTab == TAB_LOCAL) {
                showLocalPlaylistOptions(pl);
                return true;
            }
            if (pl.isLikedPlaylist()) {
                // "我喜欢的音乐" – no actions
                return true;
            }
            String cookie = MusicPlayerManager.getInstance().getCookie();
            if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
                android.widget.Toast.makeText(this, "请先登录", android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
            if (pl.getUserId() == currentUserId) {
                // Own playlist: rename or delete
                showOwnPlaylistOptions(pl, cookie);
            } else {
                // Subscribed playlist: unsubscribe
                showUnsubscribeDialog(pl, cookie);
            }
            return true;
        });

        updateTabUi();
        loadPlaylists();
        ensureRankNicknameCached();
    }

    /** 造一个二级 Tab 视图（统一样式见 {@link UiKit#tab}）。 */
    private TextView makeTab(String text, int tab) {
        TextView t = UiKit.tab(this, text);
        t.setOnClickListener(v -> switchTab(tab));
        return t;
    }

    /** 切换二级界面：重设样式 + 按新 Tab 重新加载。 */
    private void switchTab(int tab) {
        if (activeTab == tab) {
            return;
        }
        activeTab = tab;
        pendingScrollTo = -1;
        updateTabUi();
        displayList.clear();
        adapter.notifyDataSetChanged();
        loadPlaylists();
    }

    /** 按 activeTab 刷新 Tab 样式与「导入歌单」行可见性。 */
    private void updateTabUi() {
        boolean local = activeTab == TAB_LOCAL;
        UiKit.setTabSelected(tabOnline, !local);
        UiKit.setTabSelected(tabLocal, local);
        if (importRow != null) {
            importRow.setVisibility(local ? View.VISIBLE : View.GONE);
        }
        refreshRankRow(local);
    }

    /** 造一个「听歌排行」伪歌单（承载入口行的文案与副标题，外观交给通用 getView）。 */
    private PlaylistInfo createRankEntry(boolean local) {
        int count;
        if (local) {
            // 本地排行：直接用本机统计的曲目数（无需联网）。
            count = com.qinghe.music163pro.util.PlaybackStats.getSummary(this).trackedSongCount;
        } else {
            // 在线排行：用缓存条数（由 ListeningRankActivity 加载后写入；未打开过则为 0）。
            count = rankPrefs().getInt(KEY_RANK_COUNT, 0);
        }
        String nick = rankPrefs().getString(KEY_RANK_NICK, null);
        PlaylistInfo entry = new PlaylistInfo(RANK_ENTRY_ID,
                local ? "本地听歌排行" : "我的听歌排行", count,
                nick != null && !nick.isEmpty() ? nick : "");
        entry.setUserId(-1);
        return entry;
    }

    private boolean isRankEntry(PlaylistInfo pl) {
        return pl != null && pl.getId() == RANK_ENTRY_ID;
    }

    /** 把入口行放到列表首位（已存在则替换，保持位置不变）。须在 {@link #sortAndIndex()} 之前调用。 */
    private void insertRankEntry(boolean local) {
        PlaylistInfo entry = createRankEntry(local);
        if (!displayList.isEmpty() && isRankEntry(displayList.get(0))) {
            displayList.set(0, entry);
        } else {
            displayList.add(0, entry);
        }
    }

    /** 刷新入口行的副标题（从排行页返回、或昵称取到后调用）。 */
    private void refreshRankRow(boolean local) {
        if (!displayList.isEmpty() && isRankEntry(displayList.get(0))) {
            displayList.set(0, createRankEntry(local));
            adapter.notifyDataSetChanged();
        }
    }

    /** 入口行点击：在线 Tab → 服务端听歌排行；本地 Tab → 本机播放统计。 */
    private void openRankPage() {
        if (activeTab == TAB_LOCAL) {
            startActivity(new Intent(this, PlaybackStatsActivity.class));
        } else {
            startActivity(new Intent(this, ListeningRankActivity.class));
        }
    }

    private SharedPreferences rankPrefs() {
        return getSharedPreferences("music163_settings", MODE_PRIVATE);
    }

    /**
     * 副标题的「昵称」来源：优先用缓存（由听歌排行页写入）；首次未缓存时取一次账号信息
     * （轻量）并缓存，取到后刷新入口行。
     */
    private void ensureRankNicknameCached() {
        String nick = rankPrefs().getString(KEY_RANK_NICK, null);
        if (nick != null && !nick.isEmpty()) return;
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            // 未登录不取账号信息（避免无意义请求）
            return;
        }
        MusicApiHelper.getUserAccount(cookie,
                new MusicApiHelper.AccountCallback() {
                    @Override
                    public void onResult(org.json.JSONObject accountJson) {
                        org.json.JSONObject profile = accountJson.optJSONObject("profile");
                        String n = profile != null ? profile.optString("nickname", null) : null;
                        if (n != null && !n.isEmpty()) {
                            rankPrefs().edit().putString(KEY_RANK_NICK, n).apply();
                            refreshRankRow(activeTab == TAB_LOCAL);
                        }
                    }

                    @Override
                    public void onError(String message) {
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh list when returning from detail (e.g. after delete/unsub)
        loadPlaylists();
        // 从「听歌排行」页返回时，副标题的「N首」可能已被刷新 → 同步更新入口行
        refreshRankRow(activeTab == TAB_LOCAL);
    }

    /** 按当前 Tab 分发加载。 */
    private void loadPlaylists() {
        if (activeTab == TAB_LOCAL) {
            loadLocalPlaylists();
        } else {
            loadCloudPlaylists();
        }
    }

    /** 在线歌单：从网易云拉取「我的歌单」（创建 + 收藏）。 */
    private void loadCloudPlaylists() {
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            displayList.clear();
            adapter.notifyDataSetChanged();
            lvPlaylists.setVisibility(View.GONE);
            tvStatus.setText("请先登录");
            return;
        }

        MusicApiHelper.getUserPlaylists(cookie, new MusicApiHelper.UserPlaylistsCallback() {
            @Override
            public void onResult(List<PlaylistInfo> playlists) {
                displayList.clear();
                displayList.addAll(playlists);
                // 排行入口固定为列表首个条目（不参与排序 / A-Z 分组）
                insertRankEntry(false);
                // 按拼音首字母排序并建立首字母分组，供「A-Z」跳转使用
                sortAndIndex();
                // Detect current user ID from the first playlist's creator (if it's their "liked" playlist)
                if (!playlists.isEmpty() && playlists.get(0).isLikedPlaylist()) {
                    currentUserId = playlists.get(0).getUserId();
                }
                adapter.notifyDataSetChanged();
                // 字母跳转后回来：等列表重建完成再滚到目标位置
                if (pendingScrollTo >= 0) {
                    final int pos = pendingScrollTo;
                    pendingScrollTo = -1;
                    lvPlaylists.setSelection(pos);
                }
                if (playlists.isEmpty()) {
                    tvStatus.setText("暂无歌单");
                } else {
                    tvStatus.setText(playlists.size() + " 个歌单");
                    lvPlaylists.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onError(String message) {
                // 拉取失败，清掉待跳转，避免下次成功加载时误跳
                pendingScrollTo = -1;
                tvStatus.setText("加载失败: " + message);
            }
        });
    }

    /** 本地歌单：把 /sdcard/163Music/Playlists 下的本地歌单列出来。 */
    private void loadLocalPlaylists() {
        List<String> names = LocalPlaylistManager.listPlaylistNames();
        displayList.clear();
        for (String name : names) {
            // 用 PlaylistInfo 承载，userId 设为 -1 使其在本页图标逻辑里被当成「自己的歌单」。
            PlaylistInfo pl = new PlaylistInfo(-1, name,
                    LocalPlaylistManager.getPlaylistSongCount(name), "本地");
            pl.setUserId(-1);
            pl.setCoverUrl(null);
            displayList.add(pl);
        }
        insertRankEntry(true); // 排行入口固定为列表首个条目（不参与排序 / A-Z 分组）
        sortAndIndex(); // 按歌单名排序 + 建立首字母分组（A-Z 跳转可复用）
        adapter.notifyDataSetChanged();
        if (names.isEmpty()) {
            tvStatus.setText("暂无本地歌单，点「导入歌单」或「+」新建");
            lvPlaylists.setVisibility(View.GONE);
        } else {
            tvStatus.setText(names.size() + " 个本地歌单");
            lvPlaylists.setVisibility(View.VISIBLE);
        }
    }

    // ==================== 首字母索引（复用 PinyinUtils + LetterPickerActivity） ====================

    /**
     * 按歌单名的拼音首字母排序，并建立「首字母 → 第一首下标」的分组。
     * 同字母内再按中文排序规则比名称，保证稳定字典序。
     */
    private void sortAndIndex() {
        final Collator collator = Collator.getInstance(Locale.CHINA);
        // 首个条目若是「听歌排行」入口，则不参与排序（保持钉在首位）
        final int from = (!displayList.isEmpty() && isRankEntry(displayList.get(0))) ? 1 : 0;
        if (displayList.size() - from > 1) {
            Collections.sort(displayList.subList(from, displayList.size()), (left, right) -> {
                char leftInitial = PinyinUtils.initialOfWord(left.getName());
                char rightInitial = PinyinUtils.initialOfWord(right.getName());
                if (leftInitial != rightInitial) {
                    // '#'（数字 / 符号 / 非汉字）永远排在最后
                    if (leftInitial == PinyinUtils.OTHER) return 1;
                    if (rightInitial == PinyinUtils.OTHER) return -1;
                    return Character.compare(leftInitial, rightInitial);
                }
                int byName = collator.compare(left.getName(), right.getName());
                return byName;
            });
        }
        buildLetterGroups();
    }

    /** 按排好序的列表生成分组信息，供字母选择页使用。 */
    private void buildLetterGroups() {
        java.util.LinkedHashMap<Character, Integer> firstIndex = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<Character, Integer> counts = new java.util.LinkedHashMap<>();
        // 跳过首位的「听歌排行」入口行（它不是歌单，不该出现在 A-Z 分组里）
        int from = (!displayList.isEmpty() && isRankEntry(displayList.get(0))) ? 1 : 0;
        for (int i = from; i < displayList.size(); i++) {
            char initial = PinyinUtils.initialOfWord(displayList.get(i).getName());
            if (!firstIndex.containsKey(initial)) {
                firstIndex.put(initial, i);
            }
            Integer count = counts.get(initial);
            counts.put(initial, count == null ? 1 : count + 1);
        }
        groupLetters.clear();
        groupStarts = new int[firstIndex.size()];
        groupCounts = new int[firstIndex.size()];
        int index = 0;
        for (java.util.Map.Entry<Character, Integer> entry : firstIndex.entrySet()) {
            groupLetters.add(entry.getKey());
            groupStarts[index] = entry.getValue();
            Integer count = counts.get(entry.getKey());
            groupCounts[index] = count == null ? 0 : count;
            index++;
        }
    }

    /** 打开独立的字母选择页（与本地音乐页同一套交互）。 */
    private void openLetterPicker() {
        if (groupLetters.isEmpty()) {
            Toast.makeText(this, "还没有可跳转的字母", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder letters = new StringBuilder(groupLetters.size());
        for (Character letter : groupLetters) {
            letters.append(letter.charValue());
        }
        Intent intent = new Intent(this, LetterPickerActivity.class);
        intent.putExtra(LetterPickerActivity.EXTRA_LETTERS, letters.toString());
        intent.putExtra(LetterPickerActivity.EXTRA_STARTS, groupStarts);
        intent.putExtra(LetterPickerActivity.EXTRA_COUNTS, groupCounts);
        intent.putExtra(LetterPickerActivity.EXTRA_CURRENT, lvPlaylists.getFirstVisiblePosition());
        startActivityForResult(intent, REQUEST_LETTER_PICKER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LETTER_PICKER || resultCode != RESULT_OK || data == null) {
            return;
        }
        int target = data.getIntExtra(LetterPickerActivity.EXTRA_RESULT_INDEX, -1);
        if (target < 0) {
            return;
        }
        // 记下来：onResume 会重新拉取列表，等拉取完成（列表重建）后再跳过去，
        // 否则刚 setSelection 就被 reload 冲掉位置。
        pendingScrollTo = Math.max(0, Math.min(target, displayList.size() - 1));
        lvPlaylists.setSelection(pendingScrollTo);
    }

    // ==================== 在线歌单：改名 / 删除 / 取消收藏 / 创建 ====================

    private void showOwnPlaylistOptions(PlaylistInfo pl, String cookie) {
        // 统一菜单弹层：第 1 项（删除）为危险项，红色显示
        com.qinghe.music163pro.util.WatchMenuDialog.show(this, pl.getName(),
                new String[]{"改名", "删除"}, com.qinghe.music163pro.util.WatchMenuDialog.NO_SELECTION, 1,
                which -> {
                    if (which == 0) {
                        showRenamePlaylistDialog(pl, cookie);
                    } else {
                        showDeletePlaylistDialog(pl, cookie);
                    }
                });
    }

    private void showRenamePlaylistDialog(PlaylistInfo pl, String cookie) {
        EditText input = new EditText(this);
        input.setText(pl.getName());
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);
        input.setSelectAllOnFocus(true);

        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(input, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("改名")
                .setView(container)
                .setPositiveButton("确认", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) {
                        android.widget.Toast.makeText(this, "名称不能为空", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    MusicApiHelper.updatePlaylistName(pl.getId(), newName, cookie,
                            new MusicApiHelper.PlaylistActionCallback() {
                                @Override
                                public void onResult(boolean success) {
                                    if (success) {
                                        android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                                "已重命名", android.widget.Toast.LENGTH_SHORT).show();
                                        loadPlaylists();
                                    } else {
                                        android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                                "改名失败", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                }
                                @Override
                                public void onError(String message) {
                                    android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                            "改名失败: " + message, android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeletePlaylistDialog(PlaylistInfo pl, String cookie) {
        new AlertDialog.Builder(this)
                .setTitle("删除歌单")
                .setMessage("确定删除「" + pl.getName() + "」？此操作不可撤销。")
                .setPositiveButton("删除", (d, w) -> {
                    MusicApiHelper.deletePlaylist(pl.getId(), cookie,
                            new MusicApiHelper.PlaylistActionCallback() {
                                @Override
                                public void onResult(boolean success) {
                                    if (success) {
                                        android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                                "已删除", android.widget.Toast.LENGTH_SHORT).show();
                                        loadPlaylists();
                                    } else {
                                        android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                                "删除失败", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                }
                                @Override
                                public void onError(String message) {
                                    android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                            "删除失败: " + message, android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showUnsubscribeDialog(PlaylistInfo pl, String cookie) {
        new AlertDialog.Builder(this)
                .setTitle("取消收藏")
                .setMessage("确定取消收藏歌单「" + pl.getName() + "」？")
                .setPositiveButton("确认", (d, w) -> {
                    MusicApiHelper.subscribePlaylist(pl.getId(), false, cookie,
                            new MusicApiHelper.PlaylistActionCallback() {
                                @Override
                                public void onResult(boolean success) {
                                    if (success) {
                                        android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                                "已取消收藏", android.widget.Toast.LENGTH_SHORT).show();
                                        loadPlaylists();
                                    } else {
                                        android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                                "操作失败", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                }
                                @Override
                                public void onError(String message) {
                                    android.widget.Toast.makeText(MyPlaylistsActivity.this,
                                            "操作失败: " + message, android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 顶栏「+」：按当前 Tab 语义创建（在线建云歌单 / 本地建本地歌单）。 */
    private void showCreatePlaylistDialog() {
        if (activeTab == TAB_LOCAL) {
            showCreateLocalPlaylistDialog();
            return;
        }
        String cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null || cookie.isEmpty() || !cookie.contains("MUSIC_U")) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }

        EditText input = new EditText(this);
        input.setHint("输入歌单名称");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);

        LinearLayout container = new LinearLayout(this);
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("创建歌单")
                .setView(container)
                .setPositiveButton("创建", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "请输入歌单名称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createPlaylist(name, cookie);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createPlaylist(String name, String cookie) {
        Toast.makeText(this, "正在创建...", Toast.LENGTH_SHORT).show();
        MusicApiHelper.createPlaylist(name, 0, cookie, new MusicApiHelper.PlaylistCreateCallback() {
            @Override
            public void onResult(long playlistId, String resultName) {
                Toast.makeText(MyPlaylistsActivity.this,
                        "已创建「" + resultName + "」", Toast.LENGTH_SHORT).show();
                loadPlaylists();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MyPlaylistsActivity.this,
                        "创建失败: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }

    // ==================== 本地歌单：创建 / 改名 / 删除 / 导入 / 导出 ====================

    private void showCreateLocalPlaylistDialog() {
        EditText input = new EditText(this);
        input.setHint("输入歌单名称");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);

        LinearLayout container = new LinearLayout(this);
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("新建本地歌单")
                .setView(container)
                .setPositiveButton("创建", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "请输入歌单名称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (LocalPlaylistManager.playlistExists(name)) {
                        Toast.makeText(this, "歌单已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (LocalPlaylistManager.createPlaylist(name)) {
                        Toast.makeText(this, "已创建「" + name + "」", Toast.LENGTH_SHORT).show();
                        loadLocalPlaylists();
                    } else {
                        Toast.makeText(this, "创建失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showLocalPlaylistOptions(PlaylistInfo pl) {
        // 统一菜单弹层：第 2 项（删除）为危险项
        com.qinghe.music163pro.util.WatchMenuDialog.show(this, pl.getName(),
                new String[]{"改名", "导出", "删除"}, com.qinghe.music163pro.util.WatchMenuDialog.NO_SELECTION, 2,
                which -> {
                    if (which == 0) {
                        showRenameLocalDialog(pl);
                    } else if (which == 1) {
                        exportLocalPlaylist(pl);
                    } else {
                        showDeleteLocalDialog(pl);
                    }
                });
    }

    private void showRenameLocalDialog(PlaylistInfo pl) {
        EditText input = new EditText(this);
        input.setText(pl.getName());
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        input.setPadding(px(8), px(4), px(8), px(4));
        input.setBackgroundColor(0xFF333333);
        input.setSelectAllOnFocus(true);

        LinearLayout container = new LinearLayout(this);
        container.setPadding(px(12), px(8), px(12), px(4));
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("改名")
                .setView(container)
                .setPositiveButton("确认", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (newName.equals(pl.getName())) return;
                    if (LocalPlaylistManager.playlistExists(newName)) {
                        Toast.makeText(this, "歌单名已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (LocalPlaylistManager.renamePlaylist(pl.getName(), newName)) {
                        Toast.makeText(this, "已重命名", Toast.LENGTH_SHORT).show();
                        loadLocalPlaylists();
                    } else {
                        Toast.makeText(this, "改名失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteLocalDialog(PlaylistInfo pl) {
        new AlertDialog.Builder(this)
                .setTitle("删除歌单")
                .setMessage("确定删除「" + pl.getName() + "」？此操作不可撤销。")
                .setPositiveButton("删除", (d, w) -> {
                    if (LocalPlaylistManager.deletePlaylist(pl.getName())) {
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                        loadLocalPlaylists();
                    } else {
                        Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 导出：把本地歌单复制一份到 /sdcard/Download/<歌单名>.json，方便拷到电脑。 */
    private void exportLocalPlaylist(PlaylistInfo pl) {
        String safe = pl.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        File dest = new File(Environment.getExternalStorageDirectory(), "Download/" + safe + ".json");
        if (LocalPlaylistManager.exportToFile(pl.getName(), dest)) {
            Toast.makeText(this, "已导出到 " + dest.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 导入：只扫描**约定目录** {@code /sdcard/163Music/Import/} 里符合格式的 json 歌单文件，
     * 让用户挑一个读进来。
     *
     * <p>约定目录不存在时由 {@link LocalPlaylistManager#getImportDir()} 自动创建；候选为空时
     * 把目录路径直接提示出来，方便用户把 json 放进去（不再扫描 Music / Download 全树 ——
     * 那是旧实现「极慢、完全不可用」的根因）。</p>
     */
    private void onImportClicked() {
        List<File> candidates = LocalPlaylistManager.findImportCandidates();
        if (candidates.isEmpty()) {
            Toast.makeText(this,
                    "未找到可导入的歌单\n请把 json 放到："
                            + LocalPlaylistManager.getImportDir().getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            names[i] = candidates.get(i).getName();
        }
        com.qinghe.music163pro.util.WatchMenuDialog.show(this, "导入歌单", names, which -> {
                    File src = candidates.get(which);
                    if (LocalPlaylistManager.importFromFile(src, null)) {
                        Toast.makeText(this, "已导入「" + names[which] + "」", Toast.LENGTH_SHORT).show();
                        loadLocalPlaylists();
                    } else {
                        Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show();
                    }
                });
    }
}
