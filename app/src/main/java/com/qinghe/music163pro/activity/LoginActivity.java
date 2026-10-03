package com.qinghe.music163pro.activity;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;


import com.qinghe.music163pro.R;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.CookieBackup;
import com.qinghe.music163pro.util.WatchConfirmDialog;

/**
 * Login activity - contains Cookie input, QR login and SMS login.
 * Moved from the old SettingsActivity.
 *
 * <p>另外提供 Cookie 的「导出 / 读取」：导出会把当前登录用的 Cookie 写到公共外部存储
 * （{@code /sdcard/163Music/cookie.txt}，卸载应用也不会被清），重装后在同一个页面点
 * 「读取 Cookie」即可恢复登录，免得重新扫码。细节见 {@link CookieBackup}。</p>
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

    /**
     * 申请存储权限后要补做的动作。
     *
     * <p>权限对话框是异步的，用户在按按钮那一刻的意图必须存下来，否则授权回来后不知道
     * 该继续导出还是继续读取。</p>
     */
    private Runnable pendingStorageAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // 标题栏要撑到浮动返回按钮的占位高度：否则下面那行左对齐的「Cookie」会被按钮压住
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
        TextView btnQrLogin = findViewById(R.id.btn_qr_login);
        TextView btnExport = findViewById(R.id.btn_export_cookie);
        TextView btnImport = findViewById(R.id.btn_import_cookie);
        // 归零 MaterialButton 默认上下 6dp inset：按钮间距只由 margin 决定，三行按钮更紧凑
        zeroVerticalInsets(btnSave);
        zeroVerticalInsets(btnQrLogin);
        zeroVerticalInsets(btnExport);
        zeroVerticalInsets(btnImport);

        // 密码 / 验证码登录入口：跳对应登录页
        findViewById(R.id.btn_password_login_entry).setOnClickListener(v ->
                startActivity(new Intent(this, PasswordLoginActivity.class)));
        findViewById(R.id.btn_captcha_login_entry).setOnClickListener(v ->
                startActivity(new Intent(this, CaptchaLoginActivity.class)));

        // Load saved values
        etCookie.setText(prefs.getString(CookieBackup.KEY_COOKIE, ""));

        btnSave.setOnClickListener(v -> saveCookie());

        btnQrLogin.setOnClickListener(v ->
            startActivity(new Intent(this, QrLoginActivity.class))
        );

        // 两个备份动作都要写/读 SD 卡，所以先确保存储权限
        if (btnExport != null) {
            btnExport.setOnClickListener(v -> withStoragePermission(this::exportCookie));
        }
        if (btnImport != null) {
            btnImport.setOnClickListener(v -> withStoragePermission(this::importCookie));
        }

        updateBackupHint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh cookie field in case QR/SMS login updated it
        etCookie.setText(prefs.getString(CookieBackup.KEY_COOKIE, ""));
        // 上次导出的时间可能变了（比如用户刚在别的页面导出过）
        updateBackupHint();
    }

    // ── Cookie 的保存 ──────────────────────────────────────────────────────

    /** MaterialButton 默认自带上下 6dp inset，会让「按钮看起来比声明的高度矮、行距虚胖」；归零后底衬铺满声明高度。 */
    private static void zeroVerticalInsets(android.view.View v) {
        if (v instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton b =
                    (com.google.android.material.button.MaterialButton) v;
            b.setInsetTop(0);
            b.setInsetBottom(0);
        }
    }

    private void saveCookie() {
        String cookie = etCookie.getText() == null ? "" : etCookie.getText().toString().trim();
        if (cookie.isEmpty()) {
            Toast.makeText(this, "Cookie为空，未保存", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(CookieBackup.KEY_COOKIE, cookie).apply();
        playerManager.setCookie(cookie);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    // ── Cookie 备份：导出 / 读取 ───────────────────────────────────────────

    /**
     * 导出当前登录用的 Cookie。
     *
     * <p>导出的口径是「已保存的那一份」而不是输入框里正在编辑的内容 —— 因为只有已保存的
     * 那份才是真正生效的登录状态。输入框有未保存的改动时会额外提示，避免用户以为导出的
     * 是新粘的那串。</p>
     */
    private void exportCookie() {
        String saved = prefs.getString(CookieBackup.KEY_COOKIE, "");
        saved = saved == null ? "" : saved.trim();
        String typed = etCookie.getText() == null ? "" : etCookie.getText().toString().trim();

        // 还没保存过任何 Cookie 时，退一步用输入框里的内容，别让用户白跑一趟
        boolean usedDraft = false;
        String toExport = saved;
        if (toExport.isEmpty() && !typed.isEmpty()) {
            toExport = typed;
            usedDraft = true;
        }
        if (toExport.isEmpty()) {
            Toast.makeText(this,
                    "还没有 Cookie 可导出，请先扫码登录或粘贴后保存", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean overwriting = CookieBackup.exists(this);
        CookieBackup.Result result = CookieBackup.save(this, toExport);
        if (result != CookieBackup.Result.OK) {
            Toast.makeText(this, describe(result), Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, message.toString(), Toast.LENGTH_SHORT).show();
    }

    /**
     * 从备份文件读回 Cookie，填进输入框并直接生效。
     *
     * <p><b>已登录时会先确认</b>：设备上已有 Cookie 且与备份不同的话，读取会把它覆盖掉。
     * 备份可能是很久以前导的（甚至已经失效），手滑一下就把能用的登录态冲掉了 ——
     * 所以这一步用破坏性样式的确认框拦一下。内容一致时不必打扰用户。</p>
     */
    private void importCookie() {
        CookieBackup.ReadResult read = CookieBackup.load(this);
        if (read.status != CookieBackup.Result.OK || read.cookie == null || read.cookie.isEmpty()) {
            Toast.makeText(this, describe(read.status), Toast.LENGTH_SHORT).show();
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

        if (cookie.contains(COOKIE_MARK)) {
            Toast.makeText(this, "已读取并保存 Cookie，登录状态已恢复", Toast.LENGTH_SHORT).show();
        } else {
            // 少一个 MUSIC_U 就不算登录成功，多数情况下是文件被改坏了
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
            // 理论上 hasPermission 已经返回 true 了，这里只是兜底
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
            Toast.makeText(this, "没有存储权限，无法读写 Cookie 备份", Toast.LENGTH_SHORT).show();
        }
    }
}
