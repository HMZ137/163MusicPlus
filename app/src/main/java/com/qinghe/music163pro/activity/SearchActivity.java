package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.MvInfo;
import com.qinghe.music163pro.model.PlaylistInfo;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.WatchConfirmDialog;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Search activity - search songs, playlists and MVs.
 */
public class SearchActivity extends BaseWatchActivity {

    private static final String PREFS_NAME = "music163_settings";
    private static final String KEY_SEARCH_HISTORY = "search_history";
    private static final int MAX_HISTORY = 20;
    private static final int PAGE_SIZE = 20;

    private static final int TAB_SONGS = 0;
    private static final int TAB_PLAYLISTS = 1;
    private static final int TAB_MVS = 2;

    private EditText etSearch;
    private ListView lvSongs;
    private ListView lvPlaylists;
    private ListView lvMvs;
    private ListView lvHistory;
    private LinearLayout llSearchTabs;
    private TextView tabSongs;
    private TextView tabPlaylists;
    private TextView tabMvs;
    private ArrayAdapter<Song> songAdapter;
    private ArrayAdapter<PlaylistInfo> playlistAdapter;
    private ArrayAdapter<MvInfo> mvAdapter;
    private ArrayAdapter<String> historyAdapter;

    // 搜索联想：复用同一个列表控件显示建议（零布局改动）
    private boolean showingSuggestions = false;
    private final java.util.List<String> suggestList = new java.util.ArrayList<>();
    private ArrayAdapter<String> suggestAdapter;
    private final android.os.Handler suggestHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingSuggest;
    private android.widget.AdapterView.OnItemClickListener historyClickListener;
    private android.widget.AdapterView.OnItemLongClickListener historyLongClickListener;
    private final List<Song> songList = new ArrayList<>();
    private final List<PlaylistInfo> playlistList = new ArrayList<>();
    private final List<MvInfo> mvList = new ArrayList<>();
    private final List<String> historyList = new ArrayList<>();
    private MusicPlayerManager playerManager;
    private SharedPreferences prefs;

    private String currentKeyword = "";
    private int currentSongOffset = 0;
    private int currentPlaylistOffset = 0;
    private int currentMvOffset = 0;
    private boolean isLoadingMoreSongs = false;
    private boolean isLoadingMorePlaylists = false;
    private boolean isLoadingMoreMvs = false;
    private boolean hasMoreSongs = true;
    private boolean hasMorePlaylists = true;
    private boolean hasMoreMvs = true;
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
        lvSongs = findViewById(R.id.lv_songs);
        lvPlaylists = findViewById(R.id.lv_playlists);
        lvMvs = findViewById(R.id.lv_mvs);
        lvHistory = findViewById(R.id.lv_history);
        llSearchTabs = findViewById(R.id.ll_search_tabs);
        tabSongs = findViewById(R.id.tab_songs);
        tabPlaylists = findViewById(R.id.tab_playlists);
        tabMvs = findViewById(R.id.tab_mvs);
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

        initAdapters();
        initListeners(btnSearch);
        loadSearchHistory();

        historyAdapter = new ArrayAdapter<>(this, R.layout.item_history, R.id.tv_history_text, historyList);
        lvHistory.setAdapter(historyAdapter);
        updateHistoryVisibility();
        updateTabAppearance();
    }

    private void initAdapters() {
        songAdapter = new ArrayAdapter<Song>(this, R.layout.item_song, R.id.tv_item_name, songList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Song song = getItem(position);
                if (song != null) {
                    TextView tvName = view.findViewById(R.id.tv_item_name);
                    TextView tvArtist = view.findViewById(R.id.tv_item_artist);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText(song.getDisplayName());
                    tvArtist.setText(song.getArtist());
                    if (ivCover != null) {
                        NetworkImageLoader.load(ivCover, song.getCoverUrl());
                    }
                }
                return view;
            }
        };
        lvSongs.setAdapter(songAdapter);

        playlistAdapter = new ArrayAdapter<PlaylistInfo>(this, R.layout.item_playlist, R.id.tv_playlist_name, playlistList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                PlaylistInfo playlistInfo = getItem(position);
                if (playlistInfo != null) {
                    TextView tvName = view.findViewById(R.id.tv_playlist_name);
                    TextView tvInfo = view.findViewById(R.id.tv_playlist_info);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText(playlistInfo.getName());
                    String info = playlistInfo.getTrackCount() + "首";
                    if (playlistInfo.getCreator() != null && !playlistInfo.getCreator().isEmpty()) {
                        info += " · " + playlistInfo.getCreator();
                    }
                    tvInfo.setText(info);
                    if (ivCover != null) {
                        NetworkImageLoader.load(ivCover, playlistInfo.getCoverUrl());
                    }
                }
                return view;
            }
        };
        lvPlaylists.setAdapter(playlistAdapter);

        mvAdapter = new ArrayAdapter<MvInfo>(this, R.layout.item_mv, R.id.tv_mv_name, mvList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                MvInfo mvInfo = getItem(position);
                if (mvInfo != null) {
                    TextView tvName = view.findViewById(R.id.tv_mv_name);
                    TextView tvInfo = view.findViewById(R.id.tv_mv_info);
                    ImageView ivCover = view.findViewById(R.id.iv_cover);
                    tvName.setText(mvInfo.getName());
                    StringBuilder info = new StringBuilder();
                    if (mvInfo.getArtist() != null && !mvInfo.getArtist().isEmpty()) {
                        info.append(mvInfo.getArtist());
                    }
                    if (mvInfo.getDurationMs() > 0) {
                        if (info.length() > 0) {
                            info.append(" · ");
                        }
                        info.append(formatDuration(mvInfo.getDurationMs()));
                    }
                    tvInfo.setText(info.length() > 0 ? info.toString() : getString(R.string.tap_to_view_detail));
                    if (ivCover != null) {
                        NetworkImageLoader.load(ivCover, mvInfo.getCoverUrl());
                    }
                }
                return view;
            }
        };
        lvMvs.setAdapter(mvAdapter);
    }

    private void initListeners(TextView btnSearch) {
        // 点击：建议模式下点建议词（直接搜），否则点历史词
        historyClickListener = (parent, view, position, id) -> {
            String keyword = showingSuggestions ? suggestList.get(position) : historyList.get(position);
            etSearch.setText(keyword);
            etSearch.setSelection(keyword.length());
            doSearch();
        };
        lvHistory.setOnItemClickListener(historyClickListener);

        historyLongClickListener = (parent, view, position, id) -> {
            if (showingSuggestions) {
                return true;   // 建议项不支持删除
            }
            String keyword = historyList.get(position);
            showConfirmDialog("确认删除", "确定删除搜索记录「" + keyword + "」？", () -> {
                historyList.remove(position);
                saveSearchHistory();
                historyAdapter.notifyDataSetChanged();
                updateHistoryVisibility();
            });
            return true;
        };
        lvHistory.setOnItemLongClickListener(historyLongClickListener);

        // 输入联想：防抖 280ms 后拉建议词（手表打字难，靠联想补全）
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (pendingSuggest != null) {
                    suggestHandler.removeCallbacks(pendingSuggest);
                }
                final String keyword = s == null ? "" : s.toString().trim();
                if (keyword.isEmpty()) {
                    exitSuggestionMode();
                    return;
                }
                pendingSuggest = () -> requestSuggestions(keyword);
                suggestHandler.postDelayed(pendingSuggest, 280);
            }
        });

        btnSearch.setOnClickListener(v -> doSearch());
        tabSongs.setOnClickListener(v -> switchTab(TAB_SONGS));
        tabPlaylists.setOnClickListener(v -> switchTab(TAB_PLAYLISTS));
        tabMvs.setOnClickListener(v -> switchTab(TAB_MVS));

        lvSongs.setOnItemClickListener((parent, view, position, id) -> {
            Song song = songList.get(position);
            List<Song> playlist = new ArrayList<>(songList);
            playerManager.setPlaylist(playlist, position);
            playerManager.playCurrent();
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        lvPlaylists.setOnItemClickListener((parent, view, position, id) -> {
            PlaylistInfo playlistInfo = playlistList.get(position);
            Intent intent = new Intent(this, PlaylistDetailActivity.class);
            intent.putExtra("playlist_id", playlistInfo.getId());
            intent.putExtra("playlist_name", playlistInfo.getName());
            intent.putExtra("track_count", playlistInfo.getTrackCount());
            intent.putExtra("creator", playlistInfo.getCreator());
            intent.putExtra("creator_user_id", playlistInfo.getUserId());
            intent.putExtra("is_liked_playlist", playlistInfo.isLikedPlaylist());
            startActivity(intent);
        });

        lvMvs.setOnItemClickListener((parent, view, position, id) -> {
            MvInfo mvInfo = mvList.get(position);
            Intent intent = new Intent(this, MvDetailActivity.class);
            intent.putExtra("mv_id", mvInfo.getId());
            intent.putExtra("mv_name", mvInfo.getName());
            intent.putExtra("cookie", playerManager.getCookie());
            startActivity(intent);
        });

        lvSongs.setOnScrollListener(buildScrollListener(() -> {
            if (currentTab == TAB_SONGS && !isLoadingMoreSongs && hasMoreSongs) {
                loadMoreSongs();
            }
        }, songAdapter, position -> position >= 0 && position < songList.size()
                ? songList.get(position).getCoverUrl() : null));

        lvPlaylists.setOnScrollListener(buildScrollListener(() -> {
            if (currentTab == TAB_PLAYLISTS && !isLoadingMorePlaylists && hasMorePlaylists) {
                loadMorePlaylists();
            }
        }, playlistAdapter, position -> position >= 0 && position < playlistList.size()
                ? playlistList.get(position).getCoverUrl() : null));

        lvMvs.setOnScrollListener(buildScrollListener(() -> {
            if (currentTab == TAB_MVS && !isLoadingMoreMvs && hasMoreMvs) {
                loadMoreMvs();
            }
        }, mvAdapter, position -> position >= 0 && position < mvList.size()
                ? mvList.get(position).getCoverUrl() : null));
    }

    private AbsListView.OnScrollListener buildScrollListener(Runnable loadMoreAction,
                                                             android.widget.BaseAdapter adapter,
                                                             NetworkImageLoader.CoverUrlProvider provider) {
        return new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                NetworkImageLoader.onListScrollStateChanged(view, adapter, scrollState, provider);
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount) {
                    loadMoreAction.run();
                }
                NetworkImageLoader.onListScrolled(firstVisibleItem, visibleItemCount,
                        totalItemCount, provider);
            }
        };
    }

    private void switchTab(int targetTab) {
        currentTab = targetTab;
        updateTabAppearance();
        showCurrentResultList();
        if (currentKeyword.isEmpty()) {
            updateHistoryVisibility();
            return;
        }
        if (currentTab == TAB_SONGS && songList.isEmpty()) {
            doSearchSongs();
        } else if (currentTab == TAB_PLAYLISTS && playlistList.isEmpty()) {
            doSearchPlaylists();
        } else if (currentTab == TAB_MVS && mvList.isEmpty()) {
            doSearchMvs();
        }
    }

    private void updateTabAppearance() {
        tabSongs.setTextColor(currentTab == TAB_SONGS ? 0xFFBB86FC : 0xB3FFFFFF);
        tabPlaylists.setTextColor(currentTab == TAB_PLAYLISTS ? 0xFFBB86FC : 0xB3FFFFFF);
        tabMvs.setTextColor(currentTab == TAB_MVS ? 0xFFBB86FC : 0xB3FFFFFF);
    }

    private void showCurrentResultList() {
        lvSongs.setVisibility(currentTab == TAB_SONGS && !songList.isEmpty() ? View.VISIBLE : View.GONE);
        lvPlaylists.setVisibility(currentTab == TAB_PLAYLISTS && !playlistList.isEmpty() ? View.VISIBLE : View.GONE);
        lvMvs.setVisibility(currentTab == TAB_MVS && !mvList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 拉取搜索建议并复用历史列表控件展示。 */
    private void requestSuggestions(final String keyword) {
        MusicApiHelper.suggestKeywords(keyword, playerManager.getCookie(),
                new MusicApiHelper.KeywordSuggestCallback() {
                    @Override
                    public void onResult(java.util.List<String> keywords) {
                        if (isFinishing() || keywords == null || keywords.isEmpty()) {
                            return;
                        }
                        suggestList.clear();
                        suggestList.addAll(keywords);
                        if (suggestAdapter == null) {
                            suggestAdapter = new ArrayAdapter<>(SearchActivity.this,
                                    R.layout.item_history, R.id.tv_history_text, suggestList);
                        } else {
                            suggestAdapter.notifyDataSetChanged();
                        }
                        showingSuggestions = true;
                        lvHistory.setAdapter(suggestAdapter);
                        lvSongs.setVisibility(View.GONE);
                        lvPlaylists.setVisibility(View.GONE);
                        lvMvs.setVisibility(View.GONE);
                        llSearchTabs.setVisibility(View.GONE);
                        lvHistory.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onError(String message) {
                        // 联想失败静默处理，不打扰输入
                    }
                });
    }

    /** 退出联想模式，恢复搜索历史列表。 */
    private void exitSuggestionMode() {
        if (!showingSuggestions) {
            return;
        }
        showingSuggestions = false;
        lvHistory.setAdapter(historyAdapter);
        lvHistory.setOnItemClickListener(historyClickListener);
        lvHistory.setOnItemLongClickListener(historyLongClickListener);
        updateHistoryVisibility();
    }

    private void doSearch() {
        String keyword = etSearch.getText().toString().trim();
        if (keyword.isEmpty()) {
            return;
        }

        currentKeyword = keyword;
        addToSearchHistory(keyword);

        llSearchTabs.setVisibility(View.VISIBLE);
        lvHistory.setVisibility(View.GONE);

        songList.clear();
        playlistList.clear();
        mvList.clear();
        songAdapter.notifyDataSetChanged();
        playlistAdapter.notifyDataSetChanged();
        mvAdapter.notifyDataSetChanged();
        showCurrentResultList();

        currentSongOffset = 0;
        currentPlaylistOffset = 0;
        currentMvOffset = 0;
        hasMoreSongs = true;
        hasMorePlaylists = true;
        hasMoreMvs = true;

        if (currentTab == TAB_SONGS) {
            doSearchSongs();
        } else if (currentTab == TAB_PLAYLISTS) {
            doSearchPlaylists();
        } else {
            doSearchMvs();
        }
    }

    private void doSearchSongs() {
        currentSongOffset = 0;
        hasMoreSongs = true;
        MusicApiHelper.searchSongs(currentKeyword, 0, playerManager.getCookie(), new MusicApiHelper.SearchCallback() {
            @Override
            public void onResult(List<Song> songs) {
                songList.clear();
                songList.addAll(songs);
                songAdapter.notifyDataSetChanged();
                currentSongOffset = songs.size();
                hasMoreSongs = songs.size() >= PAGE_SIZE;
                showCurrentResultList();
                if (songs.isEmpty()) {
                    Toast.makeText(SearchActivity.this, R.string.no_song, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(SearchActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doSearchPlaylists() {
        currentPlaylistOffset = 0;
        hasMorePlaylists = true;
        MusicApiHelper.searchPlaylists(currentKeyword, 0, playerManager.getCookie(), new MusicApiHelper.SearchPlaylistCallback() {
            @Override
            public void onResult(List<PlaylistInfo> playlists) {
                playlistList.clear();
                playlistList.addAll(playlists);
                playlistAdapter.notifyDataSetChanged();
                currentPlaylistOffset = playlists.size();
                hasMorePlaylists = playlists.size() >= PAGE_SIZE;
                showCurrentResultList();
                if (playlists.isEmpty()) {
                    Toast.makeText(SearchActivity.this, "暂无歌单", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(SearchActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doSearchMvs() {
        currentMvOffset = 0;
        hasMoreMvs = true;
        MusicApiHelper.searchMvs(currentKeyword, 0, playerManager.getCookie(), new MusicApiHelper.SearchMvCallback() {
            @Override
            public void onResult(List<MvInfo> mvs) {
                mvList.clear();
                mvList.addAll(mvs);
                mvAdapter.notifyDataSetChanged();
                currentMvOffset = mvs.size();
                hasMoreMvs = mvs.size() >= PAGE_SIZE;
                showCurrentResultList();
                if (mvs.isEmpty()) {
                    Toast.makeText(SearchActivity.this, R.string.no_mv, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(SearchActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadMoreSongs() {
        if (currentKeyword.isEmpty() || isLoadingMoreSongs || !hasMoreSongs) {
            return;
        }
        isLoadingMoreSongs = true;
        MusicApiHelper.searchSongs(currentKeyword, currentSongOffset, playerManager.getCookie(), new MusicApiHelper.SearchCallback() {
            @Override
            public void onResult(List<Song> songs) {
                isLoadingMoreSongs = false;
                if (songs.isEmpty()) {
                    hasMoreSongs = false;
                    return;
                }
                songList.addAll(songs);
                songAdapter.notifyDataSetChanged();
                currentSongOffset += songs.size();
                hasMoreSongs = songs.size() >= PAGE_SIZE;
            }

            @Override
            public void onError(String message) {
                isLoadingMoreSongs = false;
                Toast.makeText(SearchActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadMorePlaylists() {
        if (currentKeyword.isEmpty() || isLoadingMorePlaylists || !hasMorePlaylists) {
            return;
        }
        isLoadingMorePlaylists = true;
        MusicApiHelper.searchPlaylists(currentKeyword, currentPlaylistOffset, playerManager.getCookie(), new MusicApiHelper.SearchPlaylistCallback() {
            @Override
            public void onResult(List<PlaylistInfo> playlists) {
                isLoadingMorePlaylists = false;
                if (playlists.isEmpty()) {
                    hasMorePlaylists = false;
                    return;
                }
                playlistList.addAll(playlists);
                playlistAdapter.notifyDataSetChanged();
                currentPlaylistOffset += playlists.size();
                hasMorePlaylists = playlists.size() >= PAGE_SIZE;
            }

            @Override
            public void onError(String message) {
                isLoadingMorePlaylists = false;
                Toast.makeText(SearchActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadMoreMvs() {
        if (currentKeyword.isEmpty() || isLoadingMoreMvs || !hasMoreMvs) {
            return;
        }
        isLoadingMoreMvs = true;
        MusicApiHelper.searchMvs(currentKeyword, currentMvOffset, playerManager.getCookie(), new MusicApiHelper.SearchMvCallback() {
            @Override
            public void onResult(List<MvInfo> mvs) {
                isLoadingMoreMvs = false;
                if (mvs.isEmpty()) {
                    hasMoreMvs = false;
                    return;
                }
                mvList.addAll(mvs);
                mvAdapter.notifyDataSetChanged();
                currentMvOffset += mvs.size();
                hasMoreMvs = mvs.size() >= PAGE_SIZE;
            }

            @Override
            public void onError(String message) {
                isLoadingMoreMvs = false;
                Toast.makeText(SearchActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60);
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

    private void updateHistoryVisibility() {
        boolean noSearchResults = songList.isEmpty() && playlistList.isEmpty() && mvList.isEmpty();
        if (noSearchResults && !historyList.isEmpty() && currentKeyword.isEmpty()) {
            lvHistory.setVisibility(View.VISIBLE);
            lvSongs.setVisibility(View.GONE);
            lvPlaylists.setVisibility(View.GONE);
            lvMvs.setVisibility(View.GONE);
            llSearchTabs.setVisibility(View.GONE);
        } else {
            lvHistory.setVisibility(View.GONE);
        }
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
