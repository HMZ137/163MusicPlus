package com.qinghe.music163pro.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.util.Linkify;

import androidx.core.content.ContextCompat;

import com.qinghe.music163pro.R;


/**
 * About page - shows app name, version, developer, description, update summary.
 * Adapted for watch DPI with scrollable content.
 */
public class AboutActivity extends BaseWatchActivity {

    /**
     * 软件概述（2026-11-28 需求：简介改成本地内置文案）。
     *
     * <p>以前这一段和更新日志都是从在线接口拉的，断网/接口挂了就只剩「加载中…」。
     * 简介本来就不常变，直接内置；更新日志再单独去更新清单里取。</p>
     */
    private static final String OVERVIEW =
            "163音乐Pro 是基于「163MusicPro」修改的智能手表版网易云音乐客户端。\n"
            + "目标是把「听歌」这件事在手表小屏上做完整：在线与本地播放、逐行/翻译歌词、"
            + "下载与导出、局域网传歌、B 站音频、均衡器与表冠音量，都能在表盘上直接操作。\n"
            + "界面按 320×360 级小屏重排，支持界面缩放与页面字号、主题配色与背景自定义。";

    private TextView overviewTextView;
    private LinearLayout updateContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Apply keep screen on setting
        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Build UI programmatically for DPI adaptation
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF121212);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(12), px(10), px(12), px(16));

        // Title
        TextView theTitle = makeText("关于", 0xFFFFFFFF, px(22), true, Gravity.CENTER);
        content.addView(theTitle);
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, theTitle);

        // App name
        content.addView(makeSpacer(px(10)));
        content.addView(makeText("163音乐Pro", 0xFFFFFFFF, px(20), true, Gravity.CENTER));

        // Version
        content.addView(makeSpacer(px(4)));
        String versionName = "unknown";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        content.addView(makeText("版本: " + versionName, 0xFFCCCCCC, px(16), false, Gravity.CENTER));

        // Developer
        content.addView(makeSpacer(px(4)));
        content.addView(makeText("开发者: Qinghe（上游）· HMZ137（本修改版）",
                0xFFCCCCCC, px(16), false, Gravity.CENTER));
        content.addView(makeSpacer(px(4)));
        content.addView(makeText("官网: ", 0xFFCCCCCC, px(16), false, Gravity.CENTER));
        TextView linkTv = makeText("https://163.imoow.com", 0xFF5599CC, px(16), false, Gravity.CENTER);
        Linkify.addLinks(linkTv, Linkify.WEB_URLS);
        content.addView(linkTv);

        // Divider
        content.addView(makeSpacer(px(8)));
        content.addView(makeDivider());

        // Description
        content.addView(makeSpacer(px(8)));
        content.addView(makeText("软件概述", 0xFFFFFFFF, px(18), true, Gravity.START));
        content.addView(makeSpacer(px(4)));
        overviewTextView = makeText(OVERVIEW, 0xFFAAAAAA, px(15), false, Gravity.START);
        content.addView(overviewTextView);

        // Divider
        content.addView(makeSpacer(px(8)));
        content.addView(makeDivider());

        // Feedback section
        content.addView(makeSpacer(px(8)));
        content.addView(makeText("问题反馈", 0xFFFFFFFF, px(18), true, Gravity.START));
        content.addView(makeSpacer(px(4)));
        content.addView(makeText("遇到问题或有建议，欢迎通过以下方式反馈：",
                0xFFAAAAAA, px(14), false, Gravity.START));
        content.addView(makeSpacer(px(6)));
        // Email row
        content.addView(makeIconTextRow(R.drawable.ic_email, "邮箱：soupcomein@outlook.com",
                0xFF5599CC, px(14), true));
        content.addView(makeSpacer(px(4)));
        // QQ row
        content.addView(makeIconTextRow(R.drawable.ic_chat_bubble, "QQ：3686072365",
                0xFF5599CC, px(14), false));

        // Divider
        content.addView(makeSpacer(px(8)));
        content.addView(makeDivider());

        updateContainer = new LinearLayout(this);
        updateContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(updateContainer);

        scrollView.addView(content);
        setContentView(scrollView);
        // 简介已内置，只有「更新日志」需要联网（取更新清单里的 notes）
        renderUpdateLog(null, null);
        loadUpdateLog();
    }

    /**
     * 更新日志改用「更新清单」（updates/manifest.json → 兜底 update.json）里的 notes。
     *
     * <p>清单是 App 检查更新时本来就要读的那份，改动集中在 notes 一个字段里，
     * 发版时改一处，检测更新和关于页就同时更新了 —— 不再依赖第三方 about 接口。</p>
     */
    private void loadUpdateLog() {
        new Thread(() -> {
            final org.json.JSONObject manifest =
                    com.qinghe.music163pro.util.UpdateChecker.fetchManifest();
            final String version = manifest == null ? null
                    : manifest.optString("versionName", "");
            final String notes = manifest == null ? null : manifest.optString("notes", "");
            runOnUiThread(() -> renderUpdateLog(version, notes));
        }, "about-updatelog").start();
    }

    /**
     * 渲染「最新改动」。notes 的格式是更新清单里那种「1. xxx\n2. xxx」，
     * 这里拆成逐条圆点；没有网络/没有 notes 时给一句明确的状态，而不是一直转圈。
     */
    private void renderUpdateLog(String version, String notes) {
        updateContainer.removeAllViews();
        updateContainer.addView(makeSpacer(px(8)));
        updateContainer.addView(makeText(
                version == null || version.isEmpty() ? "最新改动" : version + " 更新内容",
                0xFFFFFFFF, px(18), true, Gravity.START));
        updateContainer.addView(makeSpacer(px(4)));

        String body = formatNotes(notes);
        updateContainer.addView(makeText(body, 0xFFAAAAAA, px(15), false, Gravity.START));
    }

    private String formatNotes(String notes) {
        if (notes == null || notes.trim().isEmpty()) {
            return "• 未获取到最新改动（可能是离线，或清单里还没填 notes）";
        }
        StringBuilder builder = new StringBuilder();
        for (String line : notes.split("\\r?\\n")) {
            String item = line.trim();
            if (item.isEmpty()) {
                continue;
            }
            // 清单里是「1. xxx」这种编号，转成圆点后去掉原编号
            item = item.replaceFirst("^\\d+[.、)]\\s*", "");
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("• ").append(item);
        }
        return builder.length() == 0
                ? "• 未获取到最新改动（可能是离线，或清单里还没填 notes）"
                : builder.toString();
    }

    private TextView makeText(String text, int color, int sizePx, boolean bold, int gravity) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setGravity(gravity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(params);
        return tv;
    }

    /**
     * Creates a horizontal row with a small vector icon on the left and text on the right.
     * @param iconRes       drawable resource id (vector icon)
     * @param text          text to display
     * @param textColor     text color
     * @param textSizePx    text size in pixels
     * @param linkify       whether to auto-linkify the text (email/URL)
     */
    private LinearLayout makeIconTextRow(int iconRes, String text, int textColor,
                                         int textSizePx, boolean linkify) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rowParams);

        int iconSize = px(18);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(ContextCompat.getDrawable(this, iconRes));
        icon.setColorFilter(textColor);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconParams.rightMargin = px(6);
        icon.setLayoutParams(iconParams);
        row.addView(icon);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (linkify) Linkify.addLinks(tv, Linkify.EMAIL_ADDRESSES | Linkify.WEB_URLS);
        row.addView(tv);

        return row;
    }

    private android.view.View makeSpacer(int heightPx) {
        android.view.View spacer = new android.view.View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        return spacer;
    }

    private android.view.View makeDivider() {
        android.view.View divider = new android.view.View(this);
        divider.setBackgroundColor(0xFF2D2D2D);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(1)));
        return divider;
    }

    /**
     * Convert a value scaled for a 320px-wide watch screen to actual pixels.
     */
}
