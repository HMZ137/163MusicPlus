package com.qinghe.music163pro.activity;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.BilibiliApiHelper;
import com.qinghe.music163pro.util.QrCodeGenerator;

/**
 * Bilibili QR code login activity.
 * Generates QR code for Bilibili web login and polls for scan status.
 */
public class BilibiliLoginActivity extends BaseWatchActivity {

    private ImageView ivQrCode;
    private TextView tvStatus;
    /** 2026-11-21：Cookie 登录输入框。 */
    private EditText etCookie;
    private MaterialButton btnRefresh;

    private String qrcodeKey = "";
    private final Handler pollHandler = new Handler();
    private boolean polling = false;

    private static final int POLL_INTERVAL_MS = 3000;

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

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(getResources().getColor(R.color.bg_dark));
        scrollView.setFillViewport(true);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(px(10), px(8), px(10), px(10));

        container.addView(createTitleBar());

        LinearLayout qrCard = new LinearLayout(this);
        qrCard.setOrientation(LinearLayout.VERTICAL);
        qrCard.setGravity(Gravity.CENTER_HORIZONTAL);
        qrCard.setBackground(com.qinghe.music163pro.util.UiKit.roundedFill(qrCard.getContext(), getResources().getColor(R.color.surface_elevated)));
        qrCard.setPadding(px(10), px(10), px(10), px(10));
        qrCard.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(qrCard);

        ivQrCode = new ImageView(this);
        int qrSize = px(140);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(qrSize, qrSize);
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        ivQrCode.setLayoutParams(qrParams);
        // 二维码本身是方形，圆角底色会把四角露白，反而影响扫码；这里保持直角纯色。
        ivQrCode.setBackgroundColor(getResources().getColor(R.color.qr_background));
        ivQrCode.setPadding(px(4), px(4), px(4), px(4));
        qrCard.addView(ivQrCode);

        tvStatus = new TextView(this);
        tvStatus.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tvStatus.setTextColor(getResources().getColor(R.color.text_secondary));
        tvStatus.setTextSize(11);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, px(8), 0, px(4));
        qrCard.addView(tvStatus);

        btnRefresh = createWatchButton("刷新二维码", true);
        btnRefresh.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btnRefresh.setOnClickListener(v -> startQrLogin());
        qrCard.addView(btnRefresh);

        // ── 2026-11-21：Cookie 登录（扫码不便时的备用方式）──
        LinearLayout cookieCard = new LinearLayout(this);
        cookieCard.setOrientation(LinearLayout.VERTICAL);
        cookieCard.setBackground(com.qinghe.music163pro.util.UiKit.roundedFill(
                cookieCard.getContext(), getResources().getColor(R.color.surface_elevated)));
        cookieCard.setPadding(px(10), px(10), px(10), px(10));
        LinearLayout.LayoutParams cookieLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cookieLp.topMargin = px(8);
        cookieCard.setLayoutParams(cookieLp);
        container.addView(cookieCard);

        TextView cookieLabel = new TextView(this);
        cookieLabel.setText("或粘贴 Cookie 登录");
        cookieLabel.setTextColor(getResources().getColor(R.color.text_secondary));
        cookieLabel.setTextSize(12);
        cookieCard.addView(cookieLabel);

        etCookie = new EditText(this);
        etCookie.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(64)));
        ((LinearLayout.LayoutParams) etCookie.getLayoutParams()).topMargin = px(4);
        etCookie.setBackground(com.qinghe.music163pro.util.UiKit.roundedFill(
                etCookie.getContext(), getResources().getColor(R.color.bg_dark)));
        etCookie.setHint("SESSDATA=... 或 {\"cookies\":\"...\"}");
        etCookie.setHintTextColor(getResources().getColor(R.color.text_disabled));
        etCookie.setTextColor(getResources().getColor(R.color.text_primary));
        etCookie.setTextSize(11);
        etCookie.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etCookie.setPadding(px(8), px(6), px(8), px(6));
        etCookie.setGravity(Gravity.TOP);
        cookieCard.addView(etCookie);

        MaterialButton btnCookieLogin = createWatchButton("使用 Cookie 登录", false);
        btnCookieLogin.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) btnCookieLogin.getLayoutParams()).topMargin = px(6);
        btnCookieLogin.setOnClickListener(v -> loginWithCookie(btnCookieLogin));
        cookieCard.addView(btnCookieLogin);

        scrollView.addView(container);
        setContentView(scrollView);

        startQrLogin();
    }

    private void startQrLogin() {
        stopPolling();
        tvStatus.setText("正在获取二维码...");
        ivQrCode.setImageBitmap(null);

        BilibiliApiHelper.generateQrCode(new BilibiliApiHelper.QrCodeCallback() {
            @Override
            public void onResult(String qrUrl, String key) {
                qrcodeKey = key;
                Bitmap qrBitmap = generateQrBitmap(qrUrl, 300);
                if (qrBitmap != null) {
                    ivQrCode.setImageBitmap(qrBitmap);
                    tvStatus.setText("请使用哔哩哔哩App扫码登录");
                    startPolling();
                } else {
                    tvStatus.setText("二维码生成失败");
                }
            }

            @Override
            public void onError(String message) {
                tvStatus.setText("获取二维码失败: " + message);
            }
        });
    }

    private Bitmap generateQrBitmap(String content, int size) {
        try {
            boolean[][] matrix = QrCodeGenerator.encode(content);
            if (matrix == null) return null;

            int matrixSize = matrix.length;
            int cellSize = size / matrixSize;
            int bitmapSize = cellSize * matrixSize;

            Bitmap bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < matrixSize; y++) {
                for (int x = 0; x < matrixSize; x++) {
                    int color = matrix[y][x] ? Color.BLACK : Color.WHITE;
                    for (int dy = 0; dy < cellSize; dy++) {
                        for (int dx = 0; dx < cellSize; dx++) {
                            bitmap.setPixel(x * cellSize + dx, y * cellSize + dy, color);
                        }
                    }
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private void startPolling() {
        polling = true;
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void stopPolling() {
        polling = false;
        pollHandler.removeCallbacks(pollRunnable);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling || qrcodeKey.isEmpty()) return;

            BilibiliApiHelper.pollQrLogin(qrcodeKey, new BilibiliApiHelper.QrPollCallback() {
                @Override
                public void onResult(int code, String message, String cookie) {
                    switch (code) {
                        case 86101:
                            tvStatus.setText("等待扫码...");
                            if (polling) {
                                pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
                            }
                            break;
                        case 86090:
                            tvStatus.setText("已扫码，请在手机上确认登录");
                            if (polling) {
                                pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
                            }
                            break;
                        case 0:
                            // Login successful
                            stopPolling();
                            if (cookie == null || cookie.isEmpty()) {
                                tvStatus.setText("登录成功但未获取到Cookie");
                                break;
                            }
                            tvStatus.setText("登录成功!");
                            saveBilibiliCookie(cookie);
                            com.qinghe.music163pro.util.UiKit.toastLong(BilibiliLoginActivity.this, "B站登录成功");
                            pollHandler.postDelayed(() -> finish(), 1500);
                            break;
                        case 86038:
                            tvStatus.setText("二维码已过期，请刷新");
                            stopPolling();
                            break;
                        default:
                            tvStatus.setText("状态: " + code + " " + message);
                            if (polling) {
                                pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
                            }
                            break;
                    }
                }

                @Override
                public void onError(String errMsg) {
                    tvStatus.setText("检查状态失败: " + errMsg);
                    if (polling) {
                        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
                    }
                }
            });
        }
    };

    /**
     * 2026-11-28：用粘贴的 Cookie 登录，<b>必须先验证</b>。
     *
     * <p>以前只检查字符串里有没有 {@code SESSDATA=}，乱填一个也能「登录成功」，
     * 结果后面所有需要登录的接口都 401。现在先做本地格式检查，再调
     * {@code /x/web-interface/nav} 让 B 站确认（返回 {@code isLogin=true} 才算登录成功），
     * 校验通过才落盘。</p>
     *
     * <p>支持两种输入：纯 Cookie 串（{@code SESSDATA=...; bili_jct=...}），
     * 或浏览器插件导出的 JSON（{@code {"cookies":"...","refresh_token":""}}）。</p>
     */
    private void loginWithCookie(MaterialButton button) {
        String raw = etCookie.getText() == null ? "" : etCookie.getText().toString().trim();
        if (raw.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "请先粘贴 Cookie");
            return;
        }
        String cookie = raw;
        if (raw.startsWith("{")) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(raw);
                String inner = obj.optString("cookies", "");
                if (!inner.isEmpty()) {
                    cookie = inner.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        String sessData = extractCookieValue(cookie, "SESSDATA");
        if (sessData.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "Cookie 里缺少 SESSDATA，无法登录");
            return;
        }
        // 本地先挡掉明显不是 SESSDATA 的东西（真实值是一长串 URL 编码字符，至少 20 位）
        if (sessData.length() < 20 || sessData.contains(" ") || sessData.contains("...")) {
            tvStatus.setText("Cookie 格式不对：SESSDATA 看起来是占位符或已截断");
            com.qinghe.music163pro.util.UiKit.toast(this, "SESSDATA 不完整，请从浏览器里完整复制");
            return;
        }

        final String finalCookie = cookie;
        if (button != null) {
            button.setEnabled(false);
        }
        tvStatus.setText("正在向B站验证 Cookie…");
        BilibiliApiHelper.getNavInfo(finalCookie, new BilibiliApiHelper.NavCallback() {
            @Override
            public void onResult(boolean loggedIn, String uname) {
                if (button != null) {
                    button.setEnabled(true);
                }
                if (!loggedIn) {
                    // 不落盘：无效 Cookie 存下来只会让后续每个接口都失败，且用户以为已登录
                    tvStatus.setText("Cookie 无效或已过期（B站返回未登录），请重新复制");
                    com.qinghe.music163pro.util.UiKit.toastLong(BilibiliLoginActivity.this,
                            "Cookie 无效或已过期，登录未生效");
                    return;
                }
                saveBilibiliCookie(finalCookie);
                String who = uname == null || uname.isEmpty() ? "" : "：" + uname;
                tvStatus.setText("登录成功" + who);
                com.qinghe.music163pro.util.UiKit.toastLong(BilibiliLoginActivity.this,
                        "B站登录成功" + who);
                finish();
            }

            @Override
            public void onError(String message) {
                if (button != null) {
                    button.setEnabled(true);
                }
                tvStatus.setText("校验失败：" + message + "（检查网络后重试）");
                com.qinghe.music163pro.util.UiKit.toastLong(BilibiliLoginActivity.this,
                        "无法连接B站校验 Cookie，请检查网络后重试");
            }
        });
    }

    /** 从 Cookie 串里取某个键的值（不区分大小写，值到 ; 或串尾为止）。 */
    private static String extractCookieValue(String cookie, String key) {
        if (cookie == null || cookie.isEmpty()) {
            return "";
        }
        for (String part : cookie.split(";")) {
            String item = part.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (item.substring(0, eq).trim().equalsIgnoreCase(key)) {
                return item.substring(eq + 1).trim();
            }
        }
        return "";
    }

    private void saveBilibiliCookie(String cookie) {
        if (cookie == null || cookie.isEmpty()) return;
        SharedPreferences prefs = getSharedPreferences("music163_settings", MODE_PRIVATE);
        prefs.edit().putString("bilibili_cookie", cookie).apply();
    }

    private LinearLayout createTitleBar() {
        LinearLayout bar = new LinearLayout(this);
        // 2026-11-28：标题栏高度用统一入口撑到浮动返回按钮的占位，
        // 否则列表第一行会被按钮压住（手算 max() 会算矮）
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        insetTitleBar(bar);
        // 2026-11-28：标题栏直接给固定高度 —— 浮动返回按钮实测占 y=8~88，
        // 用 applyTitleBarTopInset 会被「可收起标题栏」逻辑再压回一行高。
        ViewGroup.LayoutParams barLp = bar.getLayoutParams();
        barLp.height = px(80);
        bar.setLayoutParams(barLp);
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
        title.setText("B站扫码登录");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(title);

        ImageView placeholder = new ImageView(this);
        placeholder.setLayoutParams(new LinearLayout.LayoutParams(px(30), px(30)));
        bar.addView(placeholder);
        return bar;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
    }
}
