package com.qinghe.music163pro.activity;

import com.qinghe.music163pro.util.UiKit;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.player.MusicPlayerManager;
import com.qinghe.music163pro.util.BackgroundUtil;

/**
 * Custom background settings: pick an image or use the current album cover as
 * the main player / lyrics background, or reset to default.
 */
public class BackgroundSettingsActivity extends BaseWatchActivity {

    private static final int REQ_PICK_IMAGE = 3001;

    private ImageView ivPreview;
    /** P9：预览下方的状态行（当前模式 · 模糊档位）。 */
    private TextView tvStatus;
    /** P9：「模糊强度」按钮，文案里带当前档位。 */
    private TextView btnBlur;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF121212);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        setContentView(scroll);

        View titleBar = buildTitleBar();
        root.addView(titleBar);
        com.qinghe.music163pro.util.WatchBackButton.applyTitleBarInset(this, titleBar);

        ivPreview = new ImageView(this);
        ivPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivPreview.setBackgroundColor(0xFF1E1E1E);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(180));
        previewParams.setMargins(px(14), px(8), px(14), px(8));
        ivPreview.setLayoutParams(previewParams);
        root.addView(ivPreview);

        // P9：状态行 —— 一眼看出当前是哪种背景、模糊几档
        tvStatus = new TextView(this);
        tvStatus.setTextColor(0xFFBB86FC);
        tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(px(10), 0, px(10), px(6));
        root.addView(tvStatus);

        TextView hint = new TextView(this);
        hint.setText("背景：跟随专辑封面 / 封面优先（无封面用选定图片）/ 选定图片 / 流体背景 / 默认。"
                + "封面与图片背景按「模糊强度」自动模糊 + 加深；流体背景＝动态色团，颜色取自当前封面背景。");
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(12));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(px(10), 0, px(10), px(8));
        root.addView(hint);

        root.addView(buildButton("选择图片", v -> pickImage()));
        root.addView(buildButton("使用专辑封面（模糊）", v -> useAlbumCover()));
        root.addView(buildButton("封面优先（无封面用图片）", v -> useCoverOrCustom()));
        root.addView(buildButton("流体背景", v -> useFluidBackground()));
        btnBlur = buildButton("模糊强度", v -> cycleBlurStrength());
        root.addView(btnBlur);
        root.addView(buildButton("恢复默认背景", v -> resetBackground()));

        refreshPreview();
    }

    private View buildTitleBar() {
        TextView title = new TextView(this);
        title.setText("自定义背景");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(15));
        title.setGravity(Gravity.CENTER);
        title.setPadding(px(12), px(10), px(12), px(6));
        return title;
    }

    private TextView buildButton(String label, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(0xFFBB86FC);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(14));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(px(12), px(13), px(12), px(13));
        btn.setClickable(true);
        btn.setFocusable(true);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF2D2D2D);
        bg.setCornerRadius(UiKit.dp(this, UiKit.DP_BUTTON_RADIUS));
        btn.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(px(14), 0, px(14), px(10));
        btn.setLayoutParams(params);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQ_PICK_IMAGE);
    }

    private void useAlbumCover() {
        BackgroundUtil.setMode(this, BackgroundUtil.MODE_COVER);
        final Song song = MusicPlayerManager.getInstance().getBackgroundSong();
        if (song == null) {
            BackgroundUtil.clearCoverBackground();
            refreshPreview();
            Toast.makeText(this, "暂无歌曲，播放歌曲后自动更新", Toast.LENGTH_SHORT).show();
            return;
        }
        String coverUrl = song.getCoverUrl();
        if (coverUrl != null && !coverUrl.isEmpty()) {
            loadCoverAndPreview(coverUrl);
            return;
        }
        if (song.getId() > 0) {
            Toast.makeText(this, "正在获取专辑封面...", Toast.LENGTH_SHORT).show();
            final long songId = song.getId();
            MusicApiHelper.fetchSongsDetails(java.util.Collections.singletonList(songId),
                    MusicPlayerManager.getInstance().getCookie(),
                    new MusicApiHelper.BatchSongDetailsCallback() {
                        @Override
                        public void onResult(java.util.Map<Long, Song> songMap) {
                            Song detail = songMap != null ? songMap.get(songId) : null;
                            String url = detail != null ? detail.getCoverUrl() : null;
                            if (url != null && !url.isEmpty()) {
                                song.setCoverUrl(url);
                                loadCoverAndPreview(url);
                            } else {
                                BackgroundUtil.clearCoverBackground();
                                refreshPreview();
                                Toast.makeText(BackgroundSettingsActivity.this,
                                        "获取封面失败，已使用默认背景", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onError(String message) {
                            BackgroundUtil.clearCoverBackground();
                            refreshPreview();
                            Toast.makeText(BackgroundSettingsActivity.this,
                                    "获取封面失败，已使用默认背景", Toast.LENGTH_SHORT).show();
                        }
                    });
        } else {
            BackgroundUtil.clearCoverBackground();
            refreshPreview();
            Toast.makeText(this, "当前无歌曲封面", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadCoverAndPreview(final String coverUrl) {
        Toast.makeText(this, "正在获取专辑封面...", Toast.LENGTH_SHORT).show();
        BackgroundUtil.loadCoverBackground(this, coverUrl, () -> {
            refreshPreview();
            boolean ok = BackgroundUtil.hasCoverBackground(coverUrl);
            Toast.makeText(this, ok ? "背景已跟随专辑封面" : "获取封面失败，已使用默认背景",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void useCoverOrCustom() {
        BackgroundUtil.setMode(this, BackgroundUtil.MODE_COVER_OR_CUSTOM);
        final Song song = MusicPlayerManager.getInstance().getBackgroundSong();
        if (song == null) {
            refreshPreview();
            Toast.makeText(this, "暂无歌曲，有封面时自动用封面", Toast.LENGTH_SHORT).show();
            return;
        }
        String coverUrl = song.getCoverUrl();
        if (coverUrl != null && !coverUrl.isEmpty()) {
            Toast.makeText(this, "正在获取专辑封面...", Toast.LENGTH_SHORT).show();
            BackgroundUtil.loadCoverBackground(this, coverUrl, () -> {
                refreshPreview();
                boolean ok = BackgroundUtil.hasCoverBackground(coverUrl);
                Toast.makeText(this, ok ? "有封面时用封面，无封面时用图片"
                        : "暂无封面，已使用选定图片", Toast.LENGTH_SHORT).show();
            });
        } else {
            refreshPreview();
            Toast.makeText(this, "暂无封面，已使用选定图片", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== P9：流体背景 / 模糊强度 ====================

    /** 切到「流体背景」：动态色团，不需要图片，也不跟封面走。 */
    private void useFluidBackground() {
        BackgroundUtil.setMode(this, BackgroundUtil.MODE_FLUID);
        refreshPreview();
        Toast.makeText(this, "已切换到流体背景", Toast.LENGTH_SHORT).show();
    }

    /** 循环切换模糊档位（关闭 → 弱 → 中 → 强 → 关闭），封面 / 图片背景立即按新档位重算。 */
    private void cycleBlurStrength() {
        int next = BackgroundUtil.cycleBlurLevel(this);
        refreshPreview();
        Toast.makeText(this, "模糊强度：" + BackgroundUtil.getBlurLabel(next), Toast.LENGTH_SHORT).show();
    }

    private void resetBackground() {
        BackgroundUtil.setMode(this, BackgroundUtil.MODE_DEFAULT);
        BackgroundUtil.clearBackground(this);
        BackgroundUtil.clearCoverBackground();
        refreshPreview();
        Toast.makeText(this, "已恢复默认背景", Toast.LENGTH_SHORT).show();
    }

    private void refreshPreview() {
        String mode = BackgroundUtil.getMode(this);
        updateStatus(mode);
        if (BackgroundUtil.MODE_FLUID.equals(mode)) {
            // P9：流体背景的预览直接用一个动态色团 Drawable（和主界面同一套实现）
            ivPreview.setImageDrawable(null);
            if (!(ivPreview.getBackground() instanceof com.qinghe.music163pro.util.FluidBackgroundDrawable)) {
                ivPreview.setBackground(BackgroundUtil.buildFluidDrawable(this));
            }
            return;
        }
        ivPreview.setBackgroundColor(0xFF1E1E1E);
        if (BackgroundUtil.MODE_COVER.equals(mode)
                || BackgroundUtil.MODE_COVER_OR_CUSTOM.equals(mode)) {
            Bitmap cover = BackgroundUtil.getCoverBackground();
            if (cover != null) {
                ivPreview.setBackgroundColor(0xFF1E1E1E);
                ivPreview.setImageDrawable(new BitmapDrawable(getResources(), cover));
                return;
            }
            // 封面优先：封面空白时，若有自定义图则预览自定义图，否则灰底。
            if (BackgroundUtil.MODE_COVER_OR_CUSTOM.equals(mode) && decodeAndShowCustom()) {
                return;
            }
            ivPreview.setImageDrawable(null);
            ivPreview.setBackgroundColor(0xFF1E1E1E);
            return;
        }
        if (BackgroundUtil.MODE_CUSTOM.equals(mode) && decodeAndShowCustom()) {
            return;
        }
        ivPreview.setImageDrawable(null);
        ivPreview.setBackgroundColor(0xFF1E1E1E);
    }

    /** P9：状态行 + 「模糊强度」按钮文案（按当前模式显示，一眼可见）。 */
    private void updateStatus(String mode) {
        if (tvStatus != null) {
            String modeLabel;
            if (BackgroundUtil.MODE_COVER.equals(mode)) {
                modeLabel = "跟随专辑封面";
            } else if (BackgroundUtil.MODE_COVER_OR_CUSTOM.equals(mode)) {
                modeLabel = "封面优先";
            } else if (BackgroundUtil.MODE_CUSTOM.equals(mode)) {
                modeLabel = "选定图片";
            } else if (BackgroundUtil.MODE_FLUID.equals(mode)) {
                modeLabel = "流体背景";
            } else {
                modeLabel = "默认背景";
            }
            if (BackgroundUtil.MODE_FLUID.equals(mode) || BackgroundUtil.MODE_DEFAULT.equals(mode)) {
                tvStatus.setText("当前：" + modeLabel);
            } else {
                tvStatus.setText("当前：" + modeLabel + " · 模糊：" + BackgroundUtil.getBlurLabel(this));
            }
        }
        if (btnBlur != null) {
            btnBlur.setText("模糊强度：" + BackgroundUtil.getBlurLabel(this));
        }
    }

    /**
     * 解码已选定的自定义图并填入预览；无图或解码失败返回 false。
     */
    private boolean decodeAndShowCustom() {
        if (!BackgroundUtil.hasCustomBackground(this)) {
            return false;
        }
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            Bitmap bmp = BitmapFactory.decodeFile(
                    BackgroundUtil.getBackgroundFile(this).getAbsolutePath(), opts);
            if (bmp != null) {
                ivPreview.setBackgroundColor(0xFF1E1E1E);
                ivPreview.setImageDrawable(new BitmapDrawable(getResources(), bmp));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                BackgroundUtil.saveBackground(this, uri);
                BackgroundUtil.setMode(this, BackgroundUtil.MODE_CUSTOM);
                refreshPreview();
                Toast.makeText(this, "背景已设置", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
