package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.player.MusicPlayerManager;

import java.util.Locale;

/**
 * 短信验证码登录：手机号 → 发送验证码（60s 倒计时）→ 输入验证码登录。
 * 登录成功后 Cookie 写入本地偏好并热更新到播放器，与 Cookie/密码登录一致。
 */
public class CaptchaLoginActivity extends BaseWatchActivity {

    private EditText etPhone;
    private EditText etCode;
    private TextView btnSend;
    private TextView btnLogin;
    private TextView tvStatus;

    private CountDownTimer sendTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_captcha_login);

        // 标题栏撑到浮动返回按钮占位（与其它设置页一致）
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(
                this, findViewById(R.id.tv_captcha_login_title));

        etPhone = findViewById(R.id.et_captcha_phone);
        etCode = findViewById(R.id.et_captcha_code);
        btnSend = findViewById(R.id.btn_send_captcha);
        btnLogin = findViewById(R.id.btn_captcha_login);
        tvStatus = findViewById(R.id.tv_captcha_status);

        btnSend.setOnClickListener(v -> sendCode());
        btnLogin.setOnClickListener(v -> doLogin());
    }

    private void sendCode() {
        String phone = etPhone.getText().toString().trim();
        if (phone.length() < 5) {
            tvStatus.setText("请先输入正确的手机号");
            return;
        }
        btnSend.setEnabled(false);
        btnSend.setText("发送中…");
        MusicApiHelper.sendSmsCode(phone, "86", new MusicApiHelper.SmsCallback() {
            @Override
            public void onResult(boolean ok, String message) {
                if (ok) {
                    tvStatus.setText(message);
                    startCountdown();
                } else {
                    tvStatus.setText(message);
                    resetSendButton();
                }
            }

            @Override
            public void onError(String message) {
                tvStatus.setText("发送失败: " + message);
                resetSendButton();
            }
        });
    }

    /** 60s 倒计时，防止连续请求。 */
    private void startCountdown() {
        if (sendTimer != null) {
            sendTimer.cancel();
        }
        sendTimer = new CountDownTimer(60_000L, 1_000L) {
            @Override
            public void onTick(long ms) {
                btnSend.setText(String.format(Locale.CHINA, "重新发送(%ds)", ms / 1000));
            }

            @Override
            public void onFinish() {
                resetSendButton();
            }
        }.start();
    }

    private void resetSendButton() {
        if (sendTimer != null) {
            sendTimer.cancel();
            sendTimer = null;
        }
        btnSend.setEnabled(true);
        btnSend.setText("发送验证码");
    }

    private void doLogin() {
        String phone = etPhone.getText().toString().trim();
        String code = etCode.getText().toString().trim();

        if (phone.length() < 5) {
            tvStatus.setText("请输入正确的手机号");
            return;
        }
        if (code.isEmpty()) {
            tvStatus.setText("请输入验证码");
            return;
        }

        btnLogin.setEnabled(false);
        tvStatus.setText("正在登录...");

        MusicApiHelper.loginByCellphone(phone, code, "86",
                new MusicApiHelper.LoginCallback() {
                    @Override
                    public void onResult(int resultCode, String message, String cookie) {
                        if (resultCode == 200) {
                            if (cookie == null || cookie.isEmpty()) {
                                tvStatus.setText("登录成功但未获取到Cookie");
                                btnLogin.setEnabled(true);
                                return;
                            }
                            tvStatus.setText("登录成功!");
                            saveCookie(cookie);
                            com.qinghe.music163pro.util.UiKit.toastLong(CaptchaLoginActivity.this, "登录成功，Cookie已保存");
                            if (sendTimer != null) sendTimer.cancel();
                            findViewById(android.R.id.content).postDelayed(() -> finish(), 1500);
                        } else {
                            tvStatus.setText("登录失败: " + message);
                            btnLogin.setEnabled(true);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        tvStatus.setText("登录失败: " + message);
                        btnLogin.setEnabled(true);
                    }
                });
    }

    private void saveCookie(String cookie) {
        if (cookie == null || cookie.isEmpty()) return;
        getSharedPreferences("music163_settings", MODE_PRIVATE)
                .edit().putString("cookie", cookie).apply();
        MusicPlayerManager.getInstance().setCookie(cookie);
    }

    @Override
    protected void onDestroy() {
        if (sendTimer != null) {
            sendTimer.cancel();
        }
        super.onDestroy();
    }
}
