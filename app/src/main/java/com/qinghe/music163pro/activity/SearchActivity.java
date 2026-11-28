package com.qinghe.music163pro.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.fragment.SearchListFragment;
import com.qinghe.music163pro.fragment.SearchMvsFragment;
import com.qinghe.music163pro.fragment.SearchPlaylistsFragment;
import com.qinghe.music163pro.fragment.SearchSongsFragment;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.WatchConfirmDialog;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索页（S1：三个结果标签改用 ViewPager2 + Fragment；S1 收尾：按需求方口径去掉多 Tab 标签行）。
 *
 * <p>本类只保留「搜索框 / 搜索历史 / 结果页信息行 / 翻页」，三个结果列表分别由
 * {@link SearchSongsFragment} / {@link SearchPlaylistsFragment} / {@link SearchMvsFragment} 承载，
 * 它们共用一个分页与滚动加载的基类 {@link SearchListFragment}。</p>
 *
 * <p><b>去 Tab 口径</b>（需求方 2026-10-17：其他 ViewPager2 + Fragment 的页面同 收藏页）：
 * 原「单曲 / 歌单 / MV」标签行删除，结果页靠左右滑切换；当前是哪一页、已加载多少条，
 * 由搜索框下方的独立信息行显示（`多Tab实例_1.bmp` 的「其他需要展示的内容」）。</p>
 *
 * <p><b>搜索历史</b>不属于标签页（文档 S1 的口径），仍留在本页：未搜索时显示历史列表，
 * 搜索后隐藏；表单与历史键 {@code search_history} 的持久化行为与改造前完全一致。</p>
 */
public class SearchActivity extends BaseWatchActivity implements SearchListFragment.Host {

    private static final String PREFS_NAME = "music163_settings";
    private static final String KEY_SEARCH_HISTORY = "search_history";
    /** 提示词轮播（2026-11-28）。 */
    private boolean hintRotationStarted;
    private Runnable hintRunnable;
    /** 输入框初始提示（静态文案），轮播时用来还原。 */
    private CharSequence originalHint;
    private static final int MAX_HISTORY = 20;

    private static final int TAB_SONGS = 0;
    private static final int TAB_PLAYLISTS = 1;
    private static final int TAB_MVS = 2;

    /** 信息行里的结果页名字，下标与 ViewPager2 的页一一对应。 */
    private static final String[] PAGE_NAMES = {"单曲", "歌单", "MV"};

    private EditText etSearch;
    private ListView lvHistory;
    private TextView tvSearchInfo;
    private ViewPager2 vpSearchTabs;
    private ArrayAdapter<String> historyAdapter;
    /** 每个结果页最近一次回写的摘要（切页时直接取用，避免显示上一页的条数）。 */
    private final String[] pageSummaries = new String[PAGE_NAMES.length];

    private final List<String> historyList = new ArrayList<>();
    private MusicPlayerManager playerManager;
    private SharedPreferences prefs;

    private String currentKeyword = "";
    /** 官方默认搜索词（/search/default），用于输入框提示与空输入兜底。 */
    private String defaultKeyword = "";
    private int currentTab = TAB_SONGS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        // 搜索栏左边的留白原本在布局里写死 40dp，是按 36px 的返回按钮估的。
        // 按钮放大到 48px 之后实际占位是 58px，写死的值就不够了（输入框左边缘会被压住），
        // 所以改成运行时按真实占位设置 —— 这样以后调整按钮尺寸也不用再改布局。
        View searchBar = findViewById(R.id.layout_search_bar);
        if (searchBar != null) {
            searchBar.setPadding(
                    com.qinghe.music163pro.util.WatchBackButton.getReservedStartPadding(this),
                    searchBar.getPaddingTop(),
                    searchBar.getPaddingEnd(),
                    searchBar.getPaddingBottom());
        }

        etSearch = findViewById(R.id.et_search);
        lvHistory = findViewById(R.id.lv_history);
        tvSearchInfo = findViewById(R.id.tv_search_info);
        vpSearchTabs = findViewById(R.id.vp_search_tabs);
        TextView btnSearch = findViewById(R.id.btn_search);
        // 归零 MaterialButton 默认上下 inset，让按钮底衬铺满 30dp，与输入框等高
        if (btnSearch instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton mb =
                    (com.google.android.material.button.MaterialButton) btnSearch;
            mb.setInsetTop(0);
            mb.setInsetBottom(0);
        }

        playerManager = MusicPlayerManager.getInstance();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // S1：结果区改成 ViewPager2，三个标签各一个 Fragment
        vpSearchTabs.setAdapter(new SearchTabsAdapter(this));
        vpSearchTabs.setOffscreenPageLimit(1);
        vpSearchTabs.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentTab = position;
                updateInfoRow(position);
            }
        });

        initListeners(btnSearch);
        loadSearchHistory();

        historyAdapter = new ArrayAdapter<>(this, R.layout.item_history,
                R.id.tv_history_text, historyList);
        lvHistory.setAdapter(historyAdapter);
        updateHistoryVisibility();
        updateInfoRow(currentTab);

        loadDefaultKeyword();
    }

    /** S1：ViewPager2 的页面适配器（第 0/1/2 页 = 单曲 / 歌单 / MV）。 */
    private final class SearchTabsAdapter extends FragmentStateAdapter {
        SearchTabsAdapter(@NonNull androidx.fragment.app.FragmentActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case TAB_PLAYLISTS:
                    return new SearchPlaylistsFragment();
                case TAB_MVS:
                    return new SearchMvsFragment();
                case TAB_SONGS:
                default:
                    return new SearchSongsFragment();
            }
        }

        @Override
        public int getItemCount() {
            return 3;
        }
    }

    /**
     * 拉一个官方默认搜索词填进输入框提示。
     *
     * <p>手表上打字很难，有默认词至少能让人「不输入也能搜一下」。拿到后同时存进
     * {@link #defaultKeyword}，空输入时按搜索键会直接用它，而不是毫无反应。</p>
     */
    private void loadDefaultKeyword() {
        originalHint = etSearch.getHint();
        MusicApiHelper.getDefaultSearchKeyword(playerManager.getCookie(),
                new MusicApiHelper.DefaultKeywordCallback() {
                    @Override
                    public void onResult(String keyword) {
                        if (keyword == null || keyword.isEmpty()) {
                            return;
                        }
                        defaultKeyword = keyword;
                        // 用户已经输入了内容就不要改动提示，避免干扰。
                        // 不写「试试：」前缀 —— 输入框在表盘上只有 ~200px，
                        // 多两个汉字就把关键词本身挤没了，直接显示词更有用。
                        if (etSearch.getText().length() == 0) {
                            etSearch.setHint(keyword);
                        }
                        // 2026-11-28：提示词在两个文案间轮播，告诉用户可以粘贴链接/ID，
                        // 用 hint 轮播而不是加控件 —— 完全不改变页面布局
                        startHintRotation();
                    }

                    @Override
                    public void onError(String message) {
                        // 拿不到就保持原来的静态提示
                        if (etSearch.getText().length() == 0) {
                            etSearch.setHint(originalHint);
                        }
                    }
                });
    }

    /** 提示词轮播：默认搜索词 ↔ 「可粘贴链接/ID/直链」（2026-11-28）。 */
    private void startHintRotation() {
        if (hintRotationStarted) {
            return;
        }
        hintRotationStarted = true;
        hintRunnable = new Runnable() {
            private boolean showTip;

            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                // 用户已经在输入就不打扰，等下一轮
                if (etSearch.getText().length() == 0) {
                    showTip = !showTip;
                    etSearch.setHint(showTip
                            ? getString(R.string.search_hint_tip)
                            : (defaultKeyword != null ? defaultKeyword : originalHint));
                }
                etSearch.postDelayed(this, 4000);
            }
        };
        etSearch.postDelayed(hintRunnable, 4000);
    }

    private void initListeners(TextView btnSearch) {
        lvHistory.setOnItemClickListener((parent, view, position, id) -> {
            String keyword = historyList.get(position);
            etSearch.setText(keyword);
            etSearch.setSelection(keyword.length());
            doSearch();
        });

        lvHistory.setOnItemLongClickListener((parent, view, position, id) -> {
            String keyword = historyList.get(position);
            showConfirmDialog("确认删除", "确定删除搜索记录「" + keyword + "」？", () -> {
                historyList.remove(position);
                saveSearchHistory();
                historyAdapter.notifyDataSetChanged();
                updateHistoryVisibility();
            });
            return true;
        });

        btnSearch.setOnClickListener(v -> doSearch());
    }

    // 去 Tab 后不再有标签按钮，结果页靠左右滑切换；当前页信息由信息行显示。
    // 注意：currentTab 仍保留 —— 点搜索时把结果区复位到上次看的那一页（改造前同款行为）。

    private void doSearch() {
        String keyword = etSearch.getText().toString().trim();
        if (keyword.isEmpty()) {
            // 空输入：退回官方默认词（/search/default），让搜索键始终有反应
            if (defaultKeyword != null && !defaultKeyword.isEmpty()) {
                keyword = defaultKeyword;
                etSearch.setText(keyword);
                etSearch.setSelection(keyword.length());
            } else {
                return;
            }
        }

        // 2026-11-28：短链（163cn.tv/xxx）先联网跟跳转拿到真实 ID，再当关键词搜
        com.qinghe.music163pro.util.NeteaseLinkParser.Result linkHit =
                com.qinghe.music163pro.util.NeteaseLinkParser.parse(keyword);
        if (linkHit.type == com.qinghe.music163pro.util.NeteaseLinkParser.Result.Type.SHORT_LINK) {
            resolveShortLinkThenSearch(linkHit.url);
            return;
        }
        // 2026-11-28：音频直链（.mp3/.flac…）直接播，不再当关键词搜（搜文本必然搜不到）
        if (linkHit.type == com.qinghe.music163pro.util.NeteaseLinkParser.Result.Type.DIRECT_AUDIO) {
            if (com.qinghe.music163pro.util.DirectPlayback.play(this, linkHit.url)) {
                startActivity(new android.content.Intent(this,
                        com.qinghe.music163pro.activity.MainActivity.class));
                finish();
            } else {
                com.qinghe.music163pro.util.UiKit.toast(this, "直链播放失败");
            }
            return;
        }

        currentKeyword = keyword;
        addToSearchHistory(keyword);

        tvSearchInfo.setVisibility(View.VISIBLE);
        vpSearchTabs.setVisibility(View.VISIBLE);
        lvHistory.setVisibility(View.GONE);
        vpSearchTabs.setCurrentItem(currentTab, false);
        // 已创建的页各自重搜；之后才创建的页会在 onViewCreated 里用当前关键词补搜
        searchCreatedFragments(keyword);
    }

    /**
     * 短链解析：{@code 163cn.tv/xxx} → 真实 ID（2026-11-28 新增）。
     *
     * <p>解析成功后把 ID 填回搜索框再走正常搜索 —— 这样「结果第一位是该歌」的逻辑
     * 与「直接输 ID」完全一致，不用在结果页里再写一套。</p>
     */
    private void resolveShortLinkThenSearch(final String shortUrl) {
        tvSearchInfo.setVisibility(View.VISIBLE);
        tvSearchInfo.setText("正在解析短链…");
        new Thread(() -> {
            String keyword = null;
            try {
                com.qinghe.music163pro.util.ShortLinkResolver.Target target =
                        com.qinghe.music163pro.util.ShortLinkResolver.resolve(shortUrl);
                if (target.id > 0) {
                    keyword = String.valueOf(target.id);
                }
            } catch (Exception ignored) {
                // 解析失败落到下面的提示
            }
            final String resolved = keyword;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (resolved == null) {
                    tvSearchInfo.setText("短链解析失败，请检查网络或直接粘贴歌曲 ID");
                    return;
                }
                etSearch.setText(resolved);
                etSearch.setSelection(resolved.length());
                doSearch();
            });
        }).start();
    }

    /** 把新关键词分发给当前已经创建的结果页（后面才创建的页由 Fragment 自己补）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void searchCreatedFragments(String keyword) {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof SearchListFragment && fragment.isAdded()) {
                ((SearchListFragment) fragment).search(keyword);
            }
        }
    }

    private void loadSearchHistory() {
        historyList.clear();
        String json = prefs.getString(KEY_SEARCH_HISTORY, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                historyList.add(arr.getString(i));
            }
        } catch (Exception ignored) {
        }
    }

    private void saveSearchHistory() {
        JSONArray arr = new JSONArray();
        for (String keyword : historyList) {
            arr.put(keyword);
        }
        prefs.edit().putString(KEY_SEARCH_HISTORY, arr.toString()).apply();
    }

    private void addToSearchHistory(String keyword) {
        historyList.remove(keyword);
        historyList.add(0, keyword);
        if (historyList.size() > MAX_HISTORY) {
            historyList.remove(historyList.size() - 1);
        }
        saveSearchHistory();
        if (historyAdapter != null) {
            historyAdapter.notifyDataSetChanged();
        }
    }

    /** 未搜索时显示历史；搜索后隐藏（历史不属于结果标签页）。 */
    private void updateHistoryVisibility() {
        boolean showHistory = currentKeyword.isEmpty() && !historyList.isEmpty();
        lvHistory.setVisibility(showHistory ? View.VISIBLE : View.GONE);
        if (showHistory) {
            tvSearchInfo.setVisibility(View.GONE);
            vpSearchTabs.setVisibility(View.GONE);
        }
    }

    @Override
    public String getSearchKeyword() {
        return currentKeyword;
    }

    @Override
    public String getCookie() {
        return playerManager.getCookie();
    }

    // ==================== 结果页信息行（去 Tab 后的「其他需要展示的内容」） ====================

    @Override
    public void onSearchPageSummary(int page, CharSequence summary) {
        if (page < 0 || page >= pageSummaries.length) {
            return;
        }
        pageSummaries[page] = summary == null ? "" : summary.toString();
        // 只回写当前可见页；其余页的摘要先存着，滑过去时再显示。
        if (vpSearchTabs != null && vpSearchTabs.getCurrentItem() == page) {
            updateInfoRow(page);
        }
    }

    /** 信息行：显示「结果页 · 已加载条数」；还没结果时先显示页名，不留空白行。 */
    private void updateInfoRow(int page) {
        if (tvSearchInfo == null || page < 0 || page >= PAGE_NAMES.length) {
            return;
        }
        String text = pageSummaries[page];
        if (text == null || text.isEmpty()) {
            text = PAGE_NAMES[page];
        }
        tvSearchInfo.setText(text);
    }

    private void showConfirmDialog(String title, String message, Runnable onConfirm) {
        WatchConfirmDialog.show(this, title, message, onConfirm,
                WatchConfirmDialog.Options.defaultOptions());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetworkImageLoader.cancelAll();
    }
}
