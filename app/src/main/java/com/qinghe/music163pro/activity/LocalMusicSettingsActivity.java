package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.LoudnessBalance;
import com.qinghe.music163pro.util.LoudnessScan;
import com.qinghe.music163pro.util.WatchBackButton;

/**
 * T1：「本地歌曲设置」页 —— 只影响**本地文件**的设置集中在这里。
 *
 * <p>按文档 T1 的归类原则（以「用户会去哪里找这个设置」为准，而非代码归属），
 * 本页承载：黑名单（F3）、响度平衡开关与响度分析（F5）。后续 L6 的扫描范围、F4 的内嵌、
 * F6 的导出目标也应落到本页。</p>
 *
 * <p><b>为什么现在才建：</b>F3/F5 落地时先在 {@code SettingsActivity} 里临时挂了入口
 * （配置页当时还没有），本页建好后把这些条目**原样迁过来**，行 id 与接线逻辑都不变，
 * 设置页只留一个「本地歌曲设置」入口。</p>
 */
public class LocalMusicSettingsActivity extends BaseWatchActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local_music_settings);

        View titleBar = findViewById(R.id.layout_local_music_settings_title_bar);
        WatchBackButton.applyTitleBarInset(this, titleBar);

        // ==================== F5：响度平衡总开关 ====================
        SwitchMaterial swLoudnessBalance = findViewById(R.id.switch_loudness_balance);
        if (swLoudnessBalance != null) {
            swLoudnessBalance.setChecked(LoudnessBalance.isEnabled(this));
            swLoudnessBalance.setOnCheckedChangeListener((buttonView, isChecked) ->
                    LoudnessBalance.setEnabled(this, isChecked));
        }

        // ==================== F5：响度分析（手动触发；再点一次=中断） ====================
        View btnLoudness = findViewById(R.id.btn_settings_loudness);
        final TextView tvLoudnessValue = findViewById(R.id.tv_settings_loudness_value);
        if (btnLoudness != null) {
            btnLoudness.setOnClickListener(v -> {
                if (LoudnessScan.isRunning()) {
                    LoudnessScan.cancel();
                    Toast.makeText(this, "正在中断（已完成的部分已保存）", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean started = LoudnessScan.start(getApplicationContext(),
                        new LoudnessScan.Listener() {
                            @Override
                            public void onProgress(int done, int total, int analyzed, int cached,
                                                   int skipped, String name) {
                                runOnUiThread(() -> {
                                    if (isFinishing() || tvLoudnessValue == null) {
                                        return;
                                    }
                                    tvLoudnessValue.setText(done + "/" + total);
                                });
                            }

                            @Override
                            public void onFinished(int analyzed, int cached, int skipped,
                                                   boolean cancelled, long ms) {
                                runOnUiThread(() -> {
                                    if (isFinishing()) {
                                        return;
                                    }
                                    if (tvLoudnessValue != null) {
                                        tvLoudnessValue.setText("");
                                    }
                                    Toast.makeText(LocalMusicSettingsActivity.this,
                                            (cancelled ? "已中断：" : "完成：") + "新分析 " + analyzed
                                                    + " 首，命中缓存 " + cached + " 首，跳过 " + skipped + " 首",
                                            Toast.LENGTH_LONG).show();
                                });
                            }
                        });
                Toast.makeText(this, started ? "开始分析响度（后台）…" : "已有分析在跑",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // ==================== F3：本地黑名单 ====================
        View btnHiddenTracks = findViewById(R.id.btn_settings_hidden);
        if (btnHiddenTracks != null) {
            btnHiddenTracks.setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, HiddenTracksActivity.class)));
        }
    }
}
