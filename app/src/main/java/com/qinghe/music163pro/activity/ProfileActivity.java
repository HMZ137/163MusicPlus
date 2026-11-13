package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.NetworkImageLoader;
import com.qinghe.music163pro.util.UiKit;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Profile activity - shows and edits user account info.
 * Editable fields (avatar, nickname, gender, signature) are grouped together
 * with larger rows; other info (age, follows, fans, register time, VIP) is
 * shown read-only.
 */
public class ProfileActivity extends BaseWatchActivity {

    private static final String PREFS_NAME = "music163_settings";
    private static final int REQ_PICK_AVATAR = 4001;

    private LinearLayout contentLayout;
    private ImageView avatarView;
    private String cookie = "";
    private String avatarUrl = "";
    private String nickname = "";
    private int gender = 0;
    private String signature = "";
    /** 收藏专辑数（/album/sublist 的 count）；-1 = 未取到，不显示该行。 */
    private int subscribedAlbumCount = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        cookie = MusicPlayerManager.getInstance().getCookie();
        if (cookie == null) cookie = "";

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF121212);
        scrollView.setFillViewport(true);
        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(px(10), px(10), px(10), px(10));
        scrollView.addView(contentLayout);
        setContentView(scrollView);

        TextView title = new TextView(this);
        title.setText("个人中心");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, px(8));
        contentLayout.addView(title);
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, title);

        buildAvatarHeader();

        TextView loading = new TextView(this);
        loading.setText("加载中...");
        loading.setTextColor(0xFF757575);
        loading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        loading.setGravity(Gravity.CENTER);
        loading.setTag("loading");
        contentLayout.addView(loading);

        fetchAccount();
    }

    private void buildAvatarHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, px(8));

        avatarView = new ImageView(this);
        int avatarSize = px(64);
        avatarView.setLayoutParams(new LinearLayout.LayoutParams(avatarSize, avatarSize));
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(0xFF333333);
        avatarView.setBackground(avatarBg);
        avatarView.setClipToOutline(true);
        avatarView.setClickable(true);
        avatarView.setFocusable(true);
        avatarView.setOnClickListener(v -> changeAvatar());
        header.addView(avatarView);

        TextView hint = new TextView(this);
        hint.setText("点头像可更换\n登录后显示信息");
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        hint.setPadding(px(12), 0, 0, 0);
        header.addView(hint);

        contentLayout.addView(header);
    }

    private void fetchAccount() {
        if (cookie.isEmpty()) {
            removeLoading();
            addInfoRow("状态", "未登录");
            return;
        }
        MusicApiHelper.getUserAccount(cookie, new MusicApiHelper.AccountCallback() {
            @Override
            public void onResult(JSONObject json) {
                runOnUiThread(() -> {
                    removeLoading();
                    displayAccountInfo(json);
                    fetchVipInfo();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    removeLoading();
                    addInfoRow("状态", "加载失败");
                });
            }
        });
    }

    private void fetchVipInfo() {
        MusicApiHelper.getVipInfo(cookie, new MusicApiHelper.VipInfoCallback() {
            @Override
            public void onResult(JSONObject json) {
                runOnUiThread(() -> {
                    displayVipInfo(json);
                    // VIP 之后再拉等级/统计，保证卡片顺序稳定（各接口是异步的）
                    fetchLevel();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(ProfileActivity.this::fetchLevel);
            }
        });
    }

    /** 账号等级卡片（/user/level）。 */
    private void fetchLevel() {
        MusicApiHelper.getUserLevel(cookie, new MusicApiHelper.UserLevelCallback() {
            @Override
            public void onResult(JSONObject data) {
                runOnUiThread(() -> {
                    addLevelCard(data);
                    fetchSubscribedAlbumCount();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(ProfileActivity.this::fetchSubscribedAlbumCount);
            }
        });
    }

    /**
     * 专辑数要单独拉（{@code /api/subcount} 里没有 albumCount），而且要先于统计卡片
     * 拿到，才能把它排在其他收藏数中间。
     */
    private void fetchSubscribedAlbumCount() {
        MusicApiHelper.getSubscribedAlbums(1, 0, cookie,
                new MusicApiHelper.SubscribedAlbumsCallback() {
                    @Override
                    public void onResult(int total, boolean hasMore, java.util.List<MusicApiHelper.AlbumBrief> albums) {
                        runOnUiThread(() -> {
                            subscribedAlbumCount = total;
                            fetchSubcount();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(ProfileActivity.this::fetchSubcount);
                    }
                });
    }

    /** 统计卡片：收藏歌手 / 自建歌单 / 收藏歌单 / 收藏专辑 / MV（{@code /api/subcount}）。 */
    private void fetchSubcount() {
        MusicApiHelper.getUserSubcount(cookie, new MusicApiHelper.UserSubcountCallback() {
            @Override
            public void onResult(JSONObject data) {
                runOnUiThread(() -> addStatsSection(data));
            }

            @Override
            public void onError(String message) {
                // 统计拿不到就整块不显示
            }
        });
    }

    /** 等级卡片：大号 Lv + 进度条 + 听歌/登录进度。 */
    private void addLevelCard(JSONObject d) {
        if (d == null) {
            return;
        }
        int level = d.optInt("level", 0);
        if (level <= 0) {
            return;
        }
        double progress = d.optDouble("progress", 0d);
        int nowPlay = d.optInt("nowPlayCount", 0);
        int nextPlay = d.optInt("nextPlayCount", 0);
        int nowLogin = d.optInt("nowLoginCount", 0);
        int nextLogin = d.optInt("nextLoginCount", 0);
        String info = d.optString("info", "");

        addSectionTitle("账号等级");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E1E1E);
        bg.setCornerRadius(px(12));
        card.setBackground(bg);
        card.setPadding(px(12), px(10), px(12), px(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(4);
        card.setLayoutParams(lp);

        // 第一行：Lv.x + 听歌次数
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvLevel = new TextView(this);
        tvLevel.setText("Lv." + level);
        tvLevel.setTextColor(0xFFBB86FC);
        tvLevel.setTypeface(null, android.graphics.Typeface.BOLD);
        tvLevel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(20));
        row.addView(tvLevel);

        TextView tvPlay = new TextView(this);
        tvPlay.setText(nextPlay > 0 ? "听歌 " + nowPlay + " / " + nextPlay : "听歌 " + nowPlay);
        tvPlay.setTextColor(0xFFAAAAAA);
        tvPlay.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        tvPlay.setGravity(Gravity.END);
        tvPlay.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvPlay);
        card.addView(row);

        // 进度条：用权重切成 (已达成 | 剩余) 两段，不必等测量就能按比例显示
        double ratio = progress;
        if (ratio < 0) ratio = 0;
        if (ratio > 1) ratio = 1;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(6));
        barLp.topMargin = px(6);
        barLp.bottomMargin = px(6);
        bar.setLayoutParams(barLp);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xFF333333);
        barBg.setCornerRadius(px(3));
        bar.setBackground(barBg);
        // 权重必须是 >0 的浮点，进度为 0 时给个极小值占位
        float done = (float) Math.max(ratio, 0.0001d);
        float left = (float) Math.max(1d - ratio, 0.0001d);
        View filled = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(0xFFBB86FC);
        fillBg.setCornerRadius(px(3));
        filled.setBackground(fillBg);
        bar.addView(filled, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, done));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, left));
        card.addView(bar);

        // 第三段：登录天数 / 权益说明
        if (nextLogin > 0) {
            TextView tvLogin = new TextView(this);
            tvLogin.setText("登录 " + nowLogin + " / " + nextLogin + " 天");
            tvLogin.setTextColor(0xFFAAAAAA);
            tvLogin.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
            card.addView(tvLogin);
        }
        if (!info.isEmpty()) {
            TextView tvInfo = new TextView(this);
            tvInfo.setText(info);
            tvInfo.setTextColor(0xFF888888);
            tvInfo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
            tvInfo.setPadding(0, px(4), 0, 0);
            card.addView(tvInfo);
        }

        contentLayout.addView(card);
    }

    /** 统计卡片：各类收藏数量。{@code subscribedAlbumCount} 为 -1 表示没取到。 */
    private void addStatsSection(JSONObject data) {
        addSectionTitle("我的收藏");
        addInfoRow("歌单", data.optInt("createdPlaylistCount", 0) + " 个");
        addInfoRow("收藏歌单", data.optInt("subPlaylistCount", 0) + " 个");
        addInfoRow("收藏歌手", data.optInt("artistCount", 0) + " 位");
        if (subscribedAlbumCount >= 0) {
            addInfoRow("收藏专辑", subscribedAlbumCount + " 张");
        }
        addInfoRow("收藏 MV", data.optInt("mvCount", 0) + " 个");
        int djRadio = data.optInt("djRadioCount", 0);
        if (djRadio > 0) {
            addInfoRow("收藏电台", djRadio + " 个");
        }
    }

    private void removeLoading() {
        for (int i = contentLayout.getChildCount() - 1; i >= 0; i--) {
            if ("loading".equals(contentLayout.getChildAt(i).getTag())) {
                contentLayout.removeViewAt(i);
            }
        }
    }

    private void displayAccountInfo(JSONObject json) {
        JSONObject profile = json.optJSONObject("profile");
        if (profile == null) {
            addInfoRow("状态", "获取信息失败");
            return;
        }

        nickname = profile.optString("nickname", "");
        gender = profile.optInt("gender", 0);
        signature = profile.optString("signature", "");
        avatarUrl = profile.optString("avatarUrl", "");
        long userId = profile.optLong("userId", 0);

        if (userId > 0) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putLong("current_user_id", userId).apply();
        }

        if (!avatarUrl.isEmpty()) {
            NetworkImageLoader.load(avatarView, avatarUrl);
        }

        // Read-only info
        addSectionTitle("基本信息");
        addInfoRow("昵称", nickname.isEmpty() ? "未设置" : nickname);
        addInfoRow("性别", genderText(gender));
        addInfoRow("签名", signature.isEmpty() ? "未设置" : signature);
        addInfoRow("用户ID", String.valueOf(userId));

        long birthday = profile.optLong("birthday", 0);
        if (birthday > 0) {
            long ageMillis = System.currentTimeMillis() - birthday;
            int age = (int) (ageMillis / (1000L * 60 * 60 * 24 * 365));
            if (age > 0) addInfoRow("年龄", age + " 岁");
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            addInfoRow("生日", sdf.format(new java.util.Date(birthday)));
        }

        int follows = profile.optInt("follows", 0);
        int followeds = profile.optInt("followeds", 0);
        addInfoRow("关注", String.valueOf(follows));
        addInfoRow("粉丝", String.valueOf(followeds));

        long createTime = profile.optLong("createTime", 0);
        if (createTime > 0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            addInfoRow("注册时间", sdf.format(new java.util.Date(createTime)));
        }

        addSectionTitle("VIP");
        // VIP expiry is shown via dedicated endpoint below.
    }

    private void displayVipInfo(JSONObject json) {
        try {
            JSONObject data = json.optJSONObject("data");
            if (data == null) return;
            boolean found = false;
            String[] keys = {"associator", "redVipLevel", "musicPackage"};
            String[] labels = {"黑胶VIP", "红钻VIP", "音乐包"};
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            long now = System.currentTimeMillis();
            for (int i = 0; i < keys.length; i++) {
                JSONObject obj = data.optJSONObject(keys[i]);
                if (obj == null) continue;
                long exp = obj.optLong("expireTime", obj.optLong("expTime", 0));
                if (exp <= 0) continue;
                boolean expired = exp < now;
                addInfoRow(labels[i] + "到期",
                        sdf.format(new java.util.Date(exp)) + (expired ? "（已过期）" : ""));
                found = true;
            }
            if (!found) {
                addInfoRow("VIP状态", "未开通");
            }
        } catch (Exception ignored) {
        }
    }

    private String genderText(int g) {
        return g == 1 ? "男" : (g == 2 ? "女" : "未设置");
    }

    private void reloadAccount() {
        contentLayout.removeAllViews();
        TextView title = new TextView(this);
        title.setText("个人中心");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, px(8));
        contentLayout.addView(title);
        buildAvatarHeader();
        TextView loading = new TextView(this);
        loading.setText("加载中...");
        loading.setTextColor(0xFF757575);
        loading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        loading.setGravity(Gravity.CENTER);
        loading.setTag("loading");
        contentLayout.addView(loading);
        fetchAccount();
    }

    private void changeAvatar() {
        if (cookie.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "请先登录");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQ_PICK_AVATAR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_AVATAR && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            File avatarFile = new File(getCacheDir(), "avatar_tmp.jpg");
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                FileOutputStream fos = new FileOutputStream(avatarFile);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                fos.close();
                is.close();
            } catch (Exception e) {
                com.qinghe.music163pro.util.UiKit.toast(this, "读取图片失败");
                return;
            }
            com.qinghe.music163pro.util.UiKit.toast(this, "正在上传头像...");
            MusicApiHelper.uploadAvatar(avatarFile, cookie, new MusicApiHelper.CommentActionCallback() {
                @Override
                public void onResult(boolean success) {
                    runOnUiThread(() -> {
                        com.qinghe.music163pro.util.UiKit.toast(ProfileActivity.this, "头像已更新");
                        reloadAccount();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> Toast.makeText(ProfileActivity.this,
                            "头像更新失败: " + message, Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    private void addSectionTitle(String title) {
        TextView tv = new TextView(this);
        tv.setText("── " + title + " ──");
        tv.setTextColor(0xFFBB86FC);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, px(8), 0, px(4));
        contentLayout.addView(tv);
    }

    private void addInfoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(12), px(14), px(12), px(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E1E1E);
        bg.setCornerRadius(UiKit.cardRadiusPx(this));
        row.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = px(3);
        row.setLayoutParams(params);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(0xFF999999);
        tvLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
        row.addView(tvLabel);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextColor(0xFFFFFFFF);
        tvValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        tvValue.setGravity(Gravity.END);
        tvValue.setPadding(px(8), 0, 0, 0);
        tvValue.setSingleLine(true);
        tvValue.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvValue.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvValue);

        contentLayout.addView(row);
    }

}
