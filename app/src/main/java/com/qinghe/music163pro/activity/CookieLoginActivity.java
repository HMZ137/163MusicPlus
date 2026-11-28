package com.qinghe.music163pro.activity;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.CookieBackup;
import com.qinghe.music163pro.util.WatchBackButton;
import com.qinghe.music163pro.util.WatchConfirmDialog;

import org.json.JSONObject;

/**
 * 1016 合并：Cookie 登录页（上游新增，按我方口径适配）。
 *
 * <p>需求方 2026-10-17 补充：登录主页只留一个**只读** Cookie 展示框，
 * 所有 Cookie 相关操作都收拢到本页：</p>
 * <ul>
 *   <li>粘贴完整 Cookie → 「登录」（调 {@code /api/w/nuser/account/get} 验证通过才保存）；</li>
 *   <li>「保存」：不验证，直接把输入框内容落盘（老入口，适合已知有效的 Cookie）；</li>
 *   <li>「导出 / 读取」：Cookie 备份（/sdcard/163Music/cookie.txt，卸载不清）。</li>
 * </ul>
 */
public class CookieLoginActivity extends BaseWatchActivity {

    private static final String PREFS_NAME = "music163_settings";
    private static final int REQ_STORAGE_PERMISSION = 0x92;

    private EditText etCookie;
    private TextView btnLogin;
    private TextView btnSave;
    private TextView tvStatus;
    private TextView tvBackupHint;
    private final Handler handler = new Handler();

    /** 申请存储权限后要补做的动作（备份导出 / 读取是异步授权的）。 */
    private Runnable pendingStorageAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cookie_login);

        // 标题栏撑到浮动返回按钮占位（与其它二级页一致）
        WatchBackButton.applyTitleBarInset(this, findViewById(R.id.tv_cookie_login_title));

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        etCookie = findViewById(R.id.et_cookie_input);
        btnLogin = findViewById(R.id.btn_cookie_login);
        btnSave = findViewById(R.id.btn_save_cookie);
        tvStatus = findViewById(R.id.tv_cookie_status);
        tvBackupHint = findViewById(R.id.tv_cookie_backup_hint);
        TextView btnExport = findViewById(R.id.btn_export_cookie);
        TextView btnImport = findViewById(R.id.btn_import_cookie);

        btnLogin.setOnClickListener(v -> doLogin());
        btnSave.setOnClickListener(v -> saveCookieQuietly());
        if (btnExport != null) {
            btnExport.setOnClickListener(v -> withStoragePermission(this::exportCookie));
        }
        if (btnImport != null) {
            btnImport.setOnClickListener(v -> withStoragePermission(this::importCookie));
        }

        // 预填当前已保存的 Cookie（方便看/改）
        etCookie.setText(prefs.getString(CookieBackup.KEY_COOKIE, ""));
        updateBackupHint();
    }

    // ==================== 登录（验证后保存） ====================

    private void doLogin() {
        String cookie = etCookie.getText().toString().trim();
        if (cookie.isEmpty()) {
            tvStatus.setText("请输入Cookie");
            return;
        }
        if (!cookie.contains("MUSIC_U")) {
            tvStatus.setText("Cookie缺少MUSIC_U，请检查是否完整");
            return;
        }

        btnLogin.setEnabled(false);
        tvStatus.setText("正在验证Cookie...");

        // Verify the cookie by fetching the account
        MusicApiHelper.getUserAccount(cookie, new MusicApiHelper.AccountCallback() {
            @Override
            public void onResult(JSONObject json) {
                JSONObject profile = json != null ? json.optJSONObject("profile") : null;
                if (profile == null) {
                    fail("Cookie无效或已过期");
                    return;
                }
                long userId = profile.optLong("userId", 0);
                String nickname = profile.optString("nickname", "");
                saveCookie(cookie, userId);
                String tip = "成功登录：" + (nickname.isEmpty() ? "未知用户" : nickname);
                tvStatus.setText(tip);
                com.qinghe.music163pro.util.UiKit.toastLong(CookieLoginActivity.this, tip);
                handler.postDelayed(() -> finish(), 1500);
            }

            @Override
            public void onError(String message) {
                fail("验证失败: " + message);
            }
        });
    }

    private void fail(String msg) {
        runOnUiThread(() -> {
            tvStatus.setText(msg);
            btnLogin.setEnabled(true);
        });
    }

    private void saveCookie(String cookie, long userId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit().putString(CookieBackup.KEY_COOKIE, cookie);
        if (userId > 0) {
            editor.putLong("current_user_id", userId);
        }
        editor.apply();
        MusicPlayerManager.getInstance().setCookie(cookie);
    }

    /** 不验证、直接保存输入框内容（从登录主页挪过来的老入口）。 */
    private void saveCookieQuietly() {
        String cookie = etCookie.getText().toString().trim();
        if (cookie.isEmpty()) {
            tvStatus.setText("Cookie为空，未保存");
            return;
        }
        saveCookie(cookie, 0L);
        tvStatus.setText("已保存");
        com.qinghe.music163pro.util.UiKit.toast(this, "已保存");
    }

    // ==================== Cookie 备份：导出 / 读取（从登录主页挪来） ====================

    /**
     * 导出当前登录用的 Cookie。
     *
     * <p>导出的口径是「已保存的那一份」而不是输入框里正在编辑的内容 —— 只有已保存的那份
     * 才是真正生效的登录状态。输入框有未保存改动时会额外提示。</p>
     */
    private void exportCookie() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
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
            Toast.makeText(this, "还没有 Cookie 可导出，请先登录或粘贴后保存",
                    Toast.LENGTH_SHORT).show();
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
        // 2026-11-28：提示压缩到最短，并用 60% 字号的小号 toast，
        // 否则小屏手表上会被这串说明挤出屏幕
        StringBuilder message = new StringBuilder();
        message.append(overwriting ? "已更新备份" : "已导出到 SD 卡");
        if (usedDraft) {
            message.append("· 用的是未保存内容");
        } else if (!typed.isEmpty() && !typed.equals(saved)) {
            message.append("· 导出的是已保存版本");
        }
        com.qinghe.music163pro.util.UiKit.toastSmall(this, message.toString());
    }

    /** 从备份文件读回 Cookie，填进输入框并直接生效（覆盖已有登录态前先确认）。 */
    private void importCookie() {
        CookieBackup.ReadResult read = CookieBackup.load(this);
        if (read.status != CookieBackup.Result.OK || read.cookie == null || read.cookie.isEmpty()) {
            com.qinghe.music163pro.util.UiKit.toast(this, describe(read.status));
            updateBackupHint();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
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
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(CookieBackup.KEY_COOKIE, cookie).apply();
        MusicPlayerManager.getInstance().setCookie(cookie);

        if (cookie.contains("MUSIC_U")) {
            com.qinghe.music163pro.util.UiKit.toast(this, "已读取并保存 Cookie，登录状态已恢复");
        } else {
            Toast.makeText(this,
                    "已读取，但内容里没有 MUSIC_U，可能不是有效 Cookie",
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

    // ==================== 存储权限 ====================

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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
