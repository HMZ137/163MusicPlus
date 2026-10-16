package com.qinghe.music163pro.activity;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.UiScale;
import com.qinghe.music163pro.util.WatchBackButton;
import com.qinghe.music163pro.util.WatchUiUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Base activity for shared watch UI behavior.
 *
 * <p>所有二级页面都在这里统一挂载左上角浮动返回按钮：高版本 Android 手表系统不再
 * 把「右滑」映射成返回键，只靠手势的页面会没有返回入口。</p>
 */
public abstract class BaseWatchActivity extends AppCompatActivity {

    /**
     * 返回动作拦截器。页面（例如多选模式）可以先于 Activity 的默认返回逻辑
     * 消费掉这一次返回。
     */
    public interface BackInterceptor {
        /** @return true 表示本次返回已被消费，不再执行默认逻辑。 */
        boolean onBack();
    }

    private final List<BackInterceptor> backInterceptors = new ArrayList<>();

    /**
     * 应用用户自定义的界面缩放。
     *
     * <p>必须在 {@code super.onCreate} 之前生效，所以放在 {@code attachBaseContext}：
     * 这里改写 densityDpi / fontScale，让该页面的 XML 布局整体按缩放系数排布。
     * 代码里拼的界面由 {@link WatchUiUtils#px} 同比例放大，两边保持一致。</p>
     */
    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(UiScale.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WatchUiUtils.applyKeepScreenOnPreference(this);
    }

    // ── 浮动返回按钮 ────────────────────────────────────────────────────────

    /**
     * 页面是否需要左上角浮动返回按钮。默认需要；页面自己已经提供了返回控件
     * （例如 B 站系列标题栏、MV 播放器控制条）时返回 false，避免出现两个返回按钮。
     */
    protected boolean shouldShowBackButton() {
        return true;
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        attachBackButton();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        attachBackButton();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        attachBackButton();
    }

    private void attachBackButton() {
        if (shouldShowBackButton()) {
            WatchBackButton.attach(this);
        }
    }

    /**
     * G3：观察竖直手势（<b>下滑收起 / 上滑展开</b> 标题栏）。
     *
     * <p>放在基类的 {@code dispatchTouchEvent} 里，是为了<b>只观察、不消费</b>事件 ——
     * 返回 {@code super} 的结果，列表的滚动与点击完全不受影响。
     * 标题栏由 {@link WatchBackButton} 的统一时机登记，页面无需自己接。</p>
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        com.qinghe.music163pro.util.TitleBarCollapse.observe(this, ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // G3 验收要求「返回再进入时状态复位」：进页面一律展开标题栏（收起态不持久化）
        com.qinghe.music163pro.util.TitleBarCollapse.reset(this);
    }

    // ── 返回键拦截 ──────────────────────────────────────────────────────────

    /**
     * 注册一个返回键拦截器。拦截器按注册顺序的逆序执行，先注册的优先级更低。
     */
    protected final void addBackInterceptor(BackInterceptor interceptor) {
        if (interceptor != null && !backInterceptors.contains(interceptor)) {
            backInterceptors.add(interceptor);
        }
    }

    protected final void removeBackInterceptor(BackInterceptor interceptor) {
        backInterceptors.remove(interceptor);
    }

    @Override
    public void onBackPressed() {
        for (int i = backInterceptors.size() - 1; i >= 0; i--) {
            if (backInterceptors.get(i).onBack()) {
                return;
            }
        }
        super.onBackPressed();
    }

    // ── 尺寸工具 ────────────────────────────────────────────────────────────

    protected final int px(int baseValue) {
        return WatchUiUtils.px(this, baseValue);
    }

    /**
     * Convert dp to pixels using the device's display density.
     * Unlike px() which scales by screen width ratio, this follows
     * Android's standard dp-to-px conversion, ensuring consistency
     * with XML-defined layout dimensions (e.g., login page buttons at 36dp).
     */
    protected final int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    /**
     * Create a MaterialButton styled for watch screens.
     * Uses dp-based height (matching the XML login buttons at 36dp, 13sp text)
     * to ensure text is always fully visible regardless of screen density.
     */
    protected final MaterialButton createWatchButton(String text, boolean outlined) {
        int styleAttr = outlined
                ? com.google.android.material.R.attr.materialButtonOutlinedStyle
                : com.google.android.material.R.attr.materialButtonStyle;
        MaterialButton button = new MaterialButton(this, null, styleAttr);
        button.setText(text);
        button.setTextColor(getResources().getColor(R.color.text_primary));
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        // Use dp-based minHeight (matching XML button layout_height="36dp")
        int minHeightPx = dpToPx(36);
        button.setMinHeight(minHeightPx);
        button.setMinimumHeight(minHeightPx);
        return button;
    }

    /**
     * 给「贴顶栏」的横向条（B 站系列等的自建标题栏）做圆角内缩。
     *
     * <p>这些页面自带左上返回控件、不走浮动返回按钮，也没有调用
     * {@link WatchBackButton#applyTitleBarInset}，所以单独用这个 helper 统一处理：
     * 把左 / 上 / 右外边距撑到 {@link WatchUiUtils#cornerInset} 以上，让左上返回、
     * 右端按钮不进物理圆角的切除区。</p>
     *
     * <p><b>用 margin 而非 padding：</b>这些标题栏是固定高度（约 {@code px(38)}），
     * 若用 padding 会把内部按钮竖向挤扁；用 margin 只是把整条下移 + 内缩，不动内容。</p>
     *
     * <p>调用点只需在 {@code bar.setLayoutParams(...)} 之后加一行
     * {@code insetTitleBar(bar);}。</p>
     */
    protected final void insetTitleBar(ViewGroup bar) {
        if (bar == null) {
            return;
        }
        ViewGroup.LayoutParams lp = bar.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        int c = WatchUiUtils.cornerInset(this);
        m.leftMargin = Math.max(m.leftMargin, c);
        m.topMargin = Math.max(m.topMargin, c);
        m.rightMargin = Math.max(m.rightMargin, c);
        bar.setLayoutParams(m);
    }
}
