package com.qinghe.music163pro.activity;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.api.NeteaseWatchApi;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.CookieBackup;
import com.qinghe.music163pro.util.WatchConfirmDialog;
import com.qinghe.music163pro.util.WatchMenuDialog;

import org.json.JSONObject;

/**
 * 登录页（1016 合并 + 需求方 2026-10-17 口径）。
 *
 * <p><b>版式</b>（需求方原话「主页保留现有的样式，然后把验证码\密码等加到保存cookie按钮的下面，
 * 在读取/导出按钮上面」）：</p>
 * <ol>
 *   <li>已登录时最上面是账号面板（用户ID / 昵称 / 复制 Cookie / 退出登录）；</li>
 *   <li>Cookie 输入框（可编辑）+ 「保存Cookie」；</li>
 *   <li>登录入口：扫码 / 密码 / 验证码（手表网关）/ Cookie登录 —— 夹在「保存」与「导出/读取」之间；</li>
 *   <li>「导出 Cookie」「读取 Cookie」+ 备份位置提示，放在最下面。</li>
 * </ol>
 */
public class LoginActivity extends BaseWatchActivity {

    private static final String PREFS_NAME = "music163_settings";
    private static final int REQ_STORAGE_PERMISSION = 0x91;
    /** 有效 Cookie 的特征串 —— 与 MoreActivity 判断是否已登录的口径一致。 */
    private static final String COOKIE_MARK = "MUSIC_U";

    private EditText etCookie;
    private SharedPreferences prefs;
    private MusicPlayerManager playerManager;
    private TextView tvBackupHint;

    // 1016：已登录 / 未登录两套界面
    private LinearLayout llLoggedIn;
    private LinearLayout llNotLoggedIn;
    private TextView tvUserId;
    private TextView tvNickname;
    /** 已拉取过账号信息的 cookie（避免每次 onResume 都重复请求）。 */
    private String fetchedAccountCookie;

    /** 申请存储权限后要补做的动作（异步授权）。 */
    private Runnable pendingStorageAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // 标题栏要撑到浮动返回按钮的占位高度：否则下面左对齐的「Cookie」会被按钮压住
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(
                this, findViewById(R.id.tv_login_title));

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        playerManager = MusicPlayerManager.getInstance();

        // Apply keep screen on setting
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        etCookie = findViewById(R.id.et_cookie);
        tvBackupHint = findViewById(R.id.tv_cookie_backup_hint);
        TextView btnSave = findViewById(R.id.btn_save_cookie);
        TextView btnMoreLogin = findViewById(R.id.btn_more_login_options);
        TextView btnExport = findViewById(R.id.btn_export_cookie);
        TextView btnImport = findViewById(R.id.btn_import_cookie);
        // 归零 MaterialButton 默认上下 6dp inset：按钮间距只由 margin 决定，行距更紧凑
        zeroVerticalInsets(btnSave);
        zeroVerticalInsets(btnMoreLogin);
        zeroVerticalInsets(btnExport);
        zeroVerticalInsets(btnImport);

        // 1016：已登录 / 未登录两套界面
        llLoggedIn = findViewById(R.id.ll_logged_in);
        llNotLoggedIn = findViewById(R.id.ll_not_logged_in);
        tvUserId = findViewById(R.id.tv_user_id);
        tvNickname = findViewById(R.id.tv_nickname);
        // 账号信息可能很长：开跑马灯（与上游一致）
        if (tvUserId != null) {
            tvUserId.setSelected(true);
        }
        if (tvNickname != null) {
            tvNickname.setSelected(true);
        }
        TextView btnCopy = findViewById(R.id.btn_copy_cookie);
        TextView btnLogout = findViewById(R.id.btn_logout);
        zeroVerticalInsets(btnCopy);
        zeroVerticalInsets(btnLogout);
        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> copyCookie());
        }
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> confirmLogout());
        }

        // Cookie 保存：确认后落盘（1016 上游口径）
        btnSave.setOnClickListener(v -> confirmSaveCookie());

        // 登录入口（需求方 2026-10-17 晚：扫码/密码/验证码全部收进「更多登录选项」浮窗，选中后跳对应页面）
        if (btnMoreLogin != null) {
            btnMoreLogin.setOnClickListener(v -> showMoreLoginOptions());
        }
        // 1016：Cookie 登录（粘贴完整 Cookie 并验证）
        findViewById(R.id.btn_cookie_login_entry).setOnClickListener(v ->
                startActivity(new Intent(this, CookieLoginActivity.class)));

        // Load saved values
        etCookie.setText(prefs.getString(CookieBackup.KEY_COOKIE, ""));

        // 两个备份动作都要写/读 SD 卡，所以先确保存储权限
        if (btnExport != null) {
            btnExport.setOnClickListener(v -> withStoragePermission(this::exportCookie));
        }
        if (btnImport != null) {
            btnImport.setOnClickListener(v -> withStoragePermission(this::importCookie));
        }

        refreshLoginState();
        updateBackupHint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh cookie field in case QR/SMS login updated it
        etCookie.setText(prefs.getString(CookieBackup.KEY_COOKIE, ""));
        // 1016：登录态可能变了（扫码/验证码/Cookie 登录返回），刷新两套界面
        refreshLoginState();
        // 上次导出的时间可能变了（比如用户刚在别的页面导出过）
        updateBackupHint();
    }

    // ── 1016：已登录 / 未登录状态 ──────────────────────────────────────────

    /** 按保存的 cookie 切换两套界面；已登录时拉一次账号信息。 */
    private void refreshLoginState() {
        String cookie = prefs.getString(CookieBackup.KEY_COOKIE, "");
        boolean loggedIn = cookie != null && cookie.contains(COOKIE_MARK);
        if (llLoggedIn != null) {
            llLoggedIn.setVisibility(loggedIn ? LinearLayout.VISIBLE : LinearLayout.GONE);
        }
        if (llNotLoggedIn != null) {
            llNotLoggedIn.setVisibility(loggedIn ? LinearLayout.GONE : LinearLayout.VISIBLE);
        }
        if (!loggedIn) {
            fetchedAccountCookie = null;
            return;
        }
        if (tvUserId != null) {
            tvUserId.setText("--");
        }
        if (tvNickname != null) {
            tvNickname.setText("--");
        }
        if (!cookie.equals(fetchedAccountCookie)) {
            fetchedAccountCookie = cookie;
            fetchAccount(cookie);
        }
    }

    /** 拉取账号信息（用户ID / 昵称）。 */
    private void fetchAccount(String cookie) {
        MusicApiHelper.getUserAccount(cookie, new MusicApiHelper.AccountCallback() {
            @Override
            public void onResult(JSONObject json) {
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    JSONObject profile = json != null ? json.optJSONObject("profile") : null;
                    long userId = profile != null ? profile.optLong("userId", 0) : 0;
                    String nickname = profile != null ? profile.optString("nickname", "") : "";
                    if (tvUserId != null) {
                        tvUserId.setText(userId > 0 ? String.valueOf(userId) : "--");
                    }
                    if (tvNickname != null) {
                        tvNickname.setText(nickname == null || nickname.isEmpty() ? "--" : nickname);
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    if (tvNickname != null) {
                        tvNickname.setText("账号信息获取失败");
                    }
                });
            }
        });
    }

    /**
     * 需求方 2026-10-17 晚：登录方式不再平铺在页面上，统一收进「更多登录选项」浮窗
     * （扫码登录 / 密码登录 / 验证码登录），选中后跳到对应登录页面。
     */
    private void showMoreLoginOptions() {
        WatchMenuDialog.show(this, "登录方式",
                new String[]{"扫码登录", "密码登录", "验证码登录"},
                index -> {
                    Intent intent;
                    if (index == 0) {
                        intent = new Intent(this, QrLoginActivity.class);
                    } else if (index == 1) {
                        intent = new Intent(this, PasswordLoginActivity.class);
                    } else {
                        // 1016：验证码登录改走手表网关（SmsLoginActivity 已重写为 NeteaseWatchApi 流程）
                        intent = new Intent(this, SmsLoginActivity.class);
                    }
                    startActivity(intent);
                });
    }

    /** 复制当前保存的 Cookie 到剪贴板。 */
    private void copyCookie() {
        String cookie = prefs.getString(CookieBackup.KEY_COOKIE, "");
        if (cookie == null || cookie.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "还没有 Cookie 可复制");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Cookie", cookie));
        }
        com.qinghe.music163pro.util.UiKit.toast(this, "已复制 Cookie");
    }

    /** 退出登录：先确认（破坏性），再走服务端 /api/logout + 清本地。 */
    private void confirmLogout() {
        WatchConfirmDialog.show(this, "退出登录",
                "确定退出当前账号吗？Cookie 将被清除。",
                this::doLogout,
                WatchConfirmDialog.Options.destructiveOptions());
    }

    private void doLogout() {
        final String cookie = prefs.getString(CookieBackup.KEY_COOKIE, "");
        // 先在服务端登出（失败也继续清本地：本地登出不能被网络问题挡住）
        if (cookie != null && !cookie.isEmpty()) {
            MusicApiHelper.logout(cookie, new MusicApiHelper.LikeCallback() {
                @Override
                public void onResult(boolean success) {
                }

                @Override
                public void onError(String message) {
                }
            });
        }
        prefs.edit()
                .remove(CookieBackup.KEY_COOKIE)
                .remove("current_user_id")
                .apply();
        playerManager.setCookie("");
        NeteaseWatchApi.clearSession();
        etCookie.setText("");
        fetchedAccountCookie = null;
        refreshLoginState();
        com.qinghe.music163pro.util.UiKit.toast(this, "已退出登录");
    }

    // ── Cookie 的保存 ──────────────────────────────────────────────────────

    /** 保存前确认（1016 上游口径），确认后再落盘。 */
    private void confirmSaveCookie() {
        String cookie = etCookie.getText() == null ? "" : etCookie.getText().toString().trim();
        if (cookie.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "Cookie为空，未保存");
            return;
        }
        WatchConfirmDialog.show(this, "保存Cookie",
                "确定保存当前编辑的Cookie吗？",
                this::saveCookie,
                WatchConfirmDialog.Options.defaultOptions());
    }

    private void saveCookie() {
        String cookie = etCookie.getText() == null ? "" : etCookie.getText().toString().trim();
        if (cookie.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "Cookie为空，未保存");
            return;
        }
        prefs.edit().putString(CookieBackup.KEY_COOKIE, cookie).apply();
        playerManager.setCookie(cookie);
        fetchedAccountCookie = null;
        refreshLoginState();
        com.qinghe.music163pro.util.UiKit.toast(this, "已保存");
    }

    // ── Cookie 备份：导出 / 读取 ───────────────────────────────────────────

    /**
     * 导出当前登录用的 Cookie。
     *
     * <p>导出的口径是「已保存的那一份」而不是输入框里正在编辑的内容 —— 只有已保存的那份
     * 才是真正生效的登录状态。输入框有未保存的改动时会额外提示。</p>
     */
    private void exportCookie() {
        String saved = prefs.getString(CookieBackup.KEY_COOKIE, "");
        saved = saved == null ? "" : saved.trim();
        String typed = etCookie.getText() == null ? "" : etCookie.getText().toString().trim();

        boolean usedDraft = false;
        String toExport = saved;
        if (toExport.isEmpty() && !typed.isEmpty()) {
            toExport = typed;
            usedDraft = true;
        }
        if (toExport.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, "还没有 Cookie 可导出，请先扫码登录或粘贴后保存");
            return;
        }

        boolean overwriting = CookieBackup.exists(this);
        CookieBackup.Result result = CookieBackup.save(this, toExport);
        if (result != CookieBackup.Result.OK) {
            com.qinghe.music163pro.util.UiKit.toast(this, describe(result));
            updateBackupHint();
            return;
        }

        updateBackupHint();
        StringBuilder message = new StringBuilder();
        message.append(overwriting ? "已更新备份" : "已导出到 SD 卡");
        if (usedDraft) {
            message.append("（用的是输入框里未保存的内容）");
        } else if (!typed.isEmpty() && !typed.equals(saved)) {
            message.append("；输入框的改动尚未保存，导出的是已保存的 Cookie");
        }
        com.qinghe.music163pro.util.UiKit.toast(this, message.toString());
    }

    /**
     * 从备份文件读回 Cookie，填进输入框并直接生效。
     *
     * <p><b>已登录时会先确认</b>：设备上已有 Cookie 且与备份不同的话，读取会把它覆盖掉，
     * 所以用破坏性样式的确认框拦一下。</p>
     */
    private void importCookie() {
        CookieBackup.ReadResult read = CookieBackup.load(this);
        if (read.status != CookieBackup.Result.OK || read.cookie == null || read.cookie.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, describe(read.status));
            updateBackupHint();
            return;
        }

        String current = prefs.getString(CookieBackup.KEY_COOKIE, "");
        current = current == null ? "" : current.trim();
        if (!current.isEmpty() && !current.equals(read.cookie)) {
            final String fromBackup = read.cookie;
            WatchConfirmDialog.show(this,
                    "覆盖当前登录？",
                    "设备上已有 Cookie，读取备份会覆盖它。若那份备份已失效，需要重新登录。",
                    () -> applyImportedCookie(fromBackup),
                    WatchConfirmDialog.Options.destructiveOptions());
            return;
        }
        applyImportedCookie(read.cookie);
    }

    /** 把读到的 Cookie 落到输入框、SharedPreferences 与播放器，并给反馈。 */
    private void applyImportedCookie(String cookie) {
        etCookie.setText(cookie);
        prefs.edit().putString(CookieBackup.KEY_COOKIE, cookie).apply();
        playerManager.setCookie(cookie);
        fetchedAccountCookie = null;
        refreshLoginState();

        if (cookie.contains(COOKIE_MARK)) {
            com.qinghe.music163pro.util.UiKit.toast(this, "已读取并保存 Cookie，登录状态已恢复");
        } else {
            Toast.makeText(this,
                    "已读取，但内容里没有 " + COOKIE_MARK + "，可能不是有效 Cookie",
                    Toast.LENGTH_LONG).show();
        }
        updateBackupHint();
    }

    /** 把结果码翻译成给用户看的话。 */
    private String describe(CookieBackup.Result result) {
        switch (result) {
            case NO_PERMISSION:
                return "需要存储权限才能读写 Cookie 备份";
            case STORAGE_UNAVAILABLE:
                return "外部存储当前不可用（未挂载？）";
            case NOT_FOUND:
                return "未找到备份文件，请先在原设备上「导出 Cookie」";
            case EMPTY_COOKIE:
                return "还没有 Cookie 可导出";
            case IO_ERROR:
                return "读写备份文件失败";
            case OK:
            default:
                return "操作完成";
        }
    }

    /** 备份位置 + 最近导出时间的提示行。 */
    private void updateBackupHint() {
        if (tvBackupHint == null) {
            return;
        }
        StringBuilder hint = new StringBuilder(
                getString(R.string.cookie_backup_path, CookieBackup.backupPath()));
        String when = CookieBackup.lastModifiedText(this);
        hint.append('\n');
        if (when.isEmpty()) {
            hint.append("尚未导出过 · 该位置卸载应用后仍会保留");
        } else {
            hint.append("上次导出：").append(when).append(" · 卸载后仍会保留");
        }
        tvBackupHint.setText(hint.toString());
    }

    // ── 存储权限 ───────────────────────────────────────────────────────────

    /**
     * 确保有存储权限再执行动作；没有就先申请，授权成功后自动补做。
     *
     * <p>Android 6 以下安装即授予，直接执行。用户选「不再询问」时，
     * {@code requestPermissions} 会立刻以「拒绝」回调，走同一个失败提示分支。</p>
     */
    private void withStoragePermission(Runnable action) {
        if (action == null) {
            return;
        }
        if (CookieBackup.hasPermission(this)) {
            action.run();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            action.run();
            return;
        }
        pendingStorageAction = action;
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
        }, REQ_STORAGE_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE_PERMISSION) {
            return;
        }
        Runnable pending = pendingStorageAction;
        pendingStorageAction = null;
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            if (pending != null) {
                pending.run();
            }
        } else {
            com.qinghe.music163pro.util.UiKit.toast(this, "没有存储权限，无法读写 Cookie 备份");
        }
    }

    /** MaterialButton 默认自带上下 6dp inset，会让按钮看起来比声明高度矮；归零后底衬铺满。 */
    private static void zeroVerticalInsets(android.view.View v) {
        if (v instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton b =
                    (com.google.android.material.button.MaterialButton) v;
            b.setInsetTop(0);
            b.setInsetBottom(0);
        }
    }
}
