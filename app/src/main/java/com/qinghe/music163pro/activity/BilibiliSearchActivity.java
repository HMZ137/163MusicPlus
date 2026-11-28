package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.BilibiliApiHelper;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 听 bili 搜索页（需求 2026-11-21 重做）。
 *
 * <p>对照参考实现补齐了搜索历史、联想词、分页加载和失败重试；
 * 接口层换成 WBI 签名版本（见 {@link BilibiliApiHelper#searchVideos}）。</p>
 */
public class BilibiliSearchActivity extends BaseWatchActivity {

    private static final String PREFS = "music163_settings";
    private static final String KEY_HISTORY = "bili_search_history";
    private static final int HISTORY_LIMIT = 30;

    private EditText etKeyword;
    private MaterialButton btnSearch;
    private TextView tvStatus;
    private LinearLayout resultContainer;
    private LinearLayout historyContainer;
    private LinearLayout suggestContainer;
    private MaterialButton btnLoadMore;

    private final List<BilibiliApiHelper.BilibiliSearchVideo> searchResults = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 当前关键词与页码（分页用）。 */
    private String currentKeyword = "";
    private int currentPage = 0;
    private boolean loading = false;
    private boolean hasMore = true;

    @Override
    protected boolean shouldShowBackButton() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(getResources().getColor(R.color.bg_dark));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(10), px(8), px(10), px(10));
        root.addView(createTitleBar());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(com.qinghe.music163pro.util.UiKit.roundedFill(card.getContext(),
                getResources().getColor(R.color.surface_elevated)));
        card.setPadding(px(12), px(12), px(12), px(12));
        root.addView(card);

        TextView label = new TextView(this);
        label.setText("搜索视频");
        label.setTextColor(getResources().getColor(R.color.text_secondary));
        label.setTextSize(12);
        card.addView(label);

        etKeyword = new EditText(this);
        etKeyword.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(42)));
        ((LinearLayout.LayoutParams) etKeyword.getLayoutParams()).topMargin = px(6);
        etKeyword.setBackground(com.qinghe.music163pro.util.UiKit.roundedFill(
                etKeyword.getContext(), getResources().getColor(R.color.bg_dark)));
        etKeyword.setHint("输入标题、UP主、关键词");
        etKeyword.setHintTextColor(getResources().getColor(R.color.text_disabled));
        etKeyword.setTextColor(getResources().getColor(R.color.text_primary));
        etKeyword.setTextSize(13);
        etKeyword.setInputType(InputType.TYPE_CLASS_TEXT);
        etKeyword.setPadding(px(10), px(6), px(10), px(6));
        etKeyword.setSingleLine(true);
        card.addView(etKeyword);

        // 历史 / 联想（互斥显示，共用同一块区域）
        historyContainer = new LinearLayout(this);
        historyContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(historyContainer);

        suggestContainer = new LinearLayout(this);
        suggestContainer.setOrientation(LinearLayout.VERTICAL);
        suggestContainer.setVisibility(View.GONE);
        card.addView(suggestContainer);

        btnSearch = createWatchButton("搜索", false);
        btnSearch.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) btnSearch.getLayoutParams()).topMargin = px(8);
        btnSearch.setOnClickListener(v -> doSearch());
        card.addView(btnSearch);

        tvStatus = new TextView(this);
        tvStatus.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tvStatus.setTextColor(getResources().getColor(R.color.text_secondary));
        tvStatus.setTextSize(11);
        tvStatus.setPadding(0, px(10), 0, px(4));
        tvStatus.setVisibility(View.GONE);
        root.addView(tvStatus);

        resultContainer = new LinearLayout(this);
        resultContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultContainer);

        btnLoadMore = createWatchButton("加载更多", true);
        btnLoadMore.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) btnLoadMore.getLayoutParams()).topMargin = px(8);
        btnLoadMore.setVisibility(View.GONE);
        btnLoadMore.setOnClickListener(v -> loadPage(currentKeyword, currentPage + 1, false));
        root.addView(btnLoadMore);

        scrollView.addView(root);
        setContentView(scrollView);

        // 输入联想（300ms 防抖）
        etKeyword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                final String text = s == null ? "" : s.toString().trim();
                handler.removeCallbacksAndMessages(null);
                if (text.isEmpty()) {
                    suggestContainer.setVisibility(View.GONE);
                    renderHistory();
                    return;
                }
                historyContainer.setVisibility(View.GONE);
                handler.postDelayed(() -> {
                    // 2026-11-21：回调里再比对一次关键词，避免旧请求回来覆盖新输入
                    BilibiliApiHelper.searchSuggestions(text,
                            new BilibiliApiHelper.SuggestionsCallback() {
                                @Override
                                public void onResult(List<String> suggestions) {
                                    if (!text.equals(etKeyword.getText().toString().trim())) {
                                        return;
                                    }
                                    renderSuggestions(suggestions);
                                }

                                @Override
                                public void onError(String message) {
                                    suggestContainer.setVisibility(View.GONE);
                                }
                            });
                }, 300L);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        renderHistory();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ==================== 搜索与分页 ====================

    private void doSearch() {
        String keyword = etKeyword.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            com.qinghe.music163pro.util.UiKit.toast(this, "请输入搜索关键词");
            return;
        }
        // 相同关键词重复点搜索：不重复请求
        if (keyword.equals(currentKeyword) && !searchResults.isEmpty()) {
            hideOverlays();
            return;
        }
        hideOverlays();
        saveHistory(keyword);
        currentKeyword = keyword;
        currentPage = 0;
        hasMore = true;
        searchResults.clear();
        resultContainer.removeAllViews();
        loadPage(keyword, 1, true);
    }

    private void loadPage(final String keyword, final int page, final boolean replace) {
        if (loading) {
            return;
        }
        loading = true;
        btnSearch.setEnabled(false);
        btnLoadMore.setEnabled(false);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setOnClickListener(null);
        tvStatus.setText(page <= 1 ? "正在搜索…" : "正在加载第 " + page + " 页…");

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String cookie = prefs.getString("bilibili_cookie", "");
        BilibiliApiHelper.searchVideos(keyword, page, cookie,
                new BilibiliApiHelper.SearchVideosCallback() {
                    @Override
                    public void onResult(List<BilibiliApiHelper.BilibiliSearchVideo> videos) {
                        loading = false;
                        btnSearch.setEnabled(true);
                        btnLoadMore.setEnabled(true);
                        if (replace) {
                            searchResults.clear();
                        }
                        searchResults.addAll(videos);
                        currentPage = page;
                        hasMore = !videos.isEmpty();
                        renderResults();
                        if (searchResults.isEmpty()) {
                            tvStatus.setText("没有找到相关视频");
                        } else {
                            tvStatus.setText("共 " + searchResults.size() + " 个结果");
                        }
                        btnLoadMore.setVisibility(hasMore ? View.VISIBLE : View.GONE);
                    }

                    @Override
                    public void onError(String message) {
                        loading = false;
                        btnSearch.setEnabled(true);
                        btnLoadMore.setEnabled(true);
                        // 2026-11-21：失败不清空已有结果，页码也不前进（下次点重试同页）
                        tvStatus.setText("搜索失败：" + message + "（点此重试）");
                        tvStatus.setOnClickListener(v -> {
                            tvStatus.setOnClickListener(null);
                            loadPage(keyword, page, replace);
                        });
                        if (replace && searchResults.isEmpty()) {
                            btnLoadMore.setVisibility(View.GONE);
                        }
                    }
                });
    }

    private void renderResults() {
        resultContainer.removeAllViews();
        for (BilibiliApiHelper.BilibiliSearchVideo video : searchResults) {
            resultContainer.addView(createResultItem(video));
        }
    }

    // ==================== 历史 ====================

    private List<String> loadHistory() {
        List<String> out = new ArrayList<>();
        try {
            String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_HISTORY, "");
            if (raw != null && !raw.isEmpty()) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    String item = arr.optString(i, "");
                    if (!item.isEmpty()) {
                        out.add(item);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void saveHistory(String keyword) {
        List<String> list = loadHistory();
        list.remove(keyword);
        list.add(0, keyword);
        // 上限 30 条，避免长期使用后无限膨胀
        while (list.size() > HISTORY_LIMIT) {
            list.remove(list.size() - 1);
        }
        try {
            JSONArray arr = new JSONArray();
            for (String s : list) {
                arr.put(s);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_HISTORY, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private void deleteHistory(String keyword) {
        List<String> list = loadHistory();
        list.remove(keyword);
        try {
            JSONArray arr = new JSONArray();
            for (String s : list) {
                arr.put(s);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_HISTORY, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
        renderHistory();
    }

    private void renderHistory() {
        historyContainer.removeAllViews();
        List<String> list = loadHistory();
        if (list.isEmpty()) {
            historyContainer.setVisibility(View.GONE);
            return;
        }
        historyContainer.setVisibility(View.VISIBLE);
        TextView title = new TextView(this);
        title.setText("搜索历史（长按删除）");
        title.setTextColor(getResources().getColor(R.color.text_disabled));
        title.setTextSize(10);
        title.setPadding(0, px(8), 0, px(4));
        historyContainer.addView(title);
        for (String item : list) {
            TextView row = new TextView(this);
            row.setText(item);
            row.setTextColor(getResources().getColor(R.color.text_secondary));
            row.setTextSize(12);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(px(4), px(7), px(4), px(7));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                etKeyword.setText(item);
                etKeyword.setSelection(item.length());
                hideOverlays();
                doSearch();
            });
            row.setOnLongClickListener(v -> {
                deleteHistory(item);
                com.qinghe.music163pro.util.UiKit.toast(this, "已删除");
                return true;
            });
            historyContainer.addView(row);
        }
    }

    // ==================== 联想 ====================

    private void renderSuggestions(List<String> suggestions) {
        suggestContainer.removeAllViews();
        if (suggestions == null || suggestions.isEmpty()) {
            suggestContainer.setVisibility(View.GONE);
            return;
        }
        suggestContainer.setVisibility(View.VISIBLE);
        String typed = etKeyword.getText().toString().trim();
        for (String item : suggestions) {
            if (item.equals(typed)) {
                continue;
            }
            TextView row = new TextView(this);
            row.setText(item);
            row.setTextColor(getResources().getColor(R.color.text_secondary));
            row.setTextSize(12);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(px(4), px(7), px(4), px(7));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                etKeyword.setText(item);
                etKeyword.setSelection(item.length());
                hideOverlays();
                doSearch();
            });
            suggestContainer.addView(row);
        }
    }

    private void hideOverlays() {
        historyContainer.setVisibility(View.GONE);
        suggestContainer.setVisibility(View.GONE);
    }

    // ==================== 行渲染 ====================

    private View createResultItem(BilibiliApiHelper.BilibiliSearchVideo video) {
        LinearLayout item = new LinearLayout(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = px(8);
        item.setLayoutParams(params);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(px(12), px(10), px(12), px(10));
        item.setBackground(com.qinghe.music163pro.util.UiKit.roundedFill(item.getContext(),
                getResources().getColor(R.color.surface_elevated)));
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(v -> {
            Intent intent = new Intent(this, BilibiliPlaylistActivity.class);
            intent.putExtra("bvid", video.bvid);
            intent.putExtra("video_title", video.title);
            intent.putExtra("owner_name", video.ownerName);
            startActivity(intent);
        });

        TextView title = new TextView(this);
        title.setText(video.title);
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(13);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        item.addView(title);

        TextView sub = new TextView(this);
        sub.setText(video.bvid + " · " + video.ownerName + " · " + formatDuration(video.duration));
        sub.setTextColor(getResources().getColor(R.color.text_secondary));
        sub.setTextSize(11);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, px(3), 0, 0);
        item.addView(sub);

        if (!TextUtils.isEmpty(video.description)) {
            TextView desc = new TextView(this);
            desc.setText(video.description);
            desc.setTextColor(getResources().getColor(R.color.text_secondary));
            desc.setTextSize(10);
            desc.setMaxLines(2);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            desc.setPadding(0, px(3), 0, 0);
            item.addView(desc);
        }
        return item;
    }

    private LinearLayout createTitleBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(36)));
        insetTitleBar(bar);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView back = new ImageView(this);
        back.setLayoutParams(new LinearLayout.LayoutParams(px(30), px(30)));
        back.setPadding(px(5), px(5), px(5), px(5));
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setColorFilter(getResources().getColor(R.color.text_primary));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        title.setGravity(Gravity.CENTER);
        title.setText("搜索视频");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(title);

        ImageView placeholder = new ImageView(this);
        placeholder.setLayoutParams(new LinearLayout.LayoutParams(px(30), px(30)));
        bar.addView(placeholder);
        return bar;
    }

    private String formatDuration(int seconds) {
        int min = seconds / 60;
        int sec = seconds % 60;
        return String.format("%d:%02d", min, sec);
    }
}
