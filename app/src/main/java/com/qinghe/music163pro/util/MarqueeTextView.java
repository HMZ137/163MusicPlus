package com.qinghe.music163pro.util;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * 跑马灯文字：挂载后自动 {@code setSelected(true)}。
 *
 * <p>系统 TextView 的 marquee 只在 selected 状态下滚动，各列表 adapter 逐一
 * {@code setSelected(true)} 太散（需求方 2026-11-14：全部页面的歌名要跑马灯，
 * 不要「截断+…」）。用这个控件替换布局里的歌名 TextView 即可全站生效。</p>
 */
public class MarqueeTextView extends AppCompatTextView {

    public MarqueeTextView(Context context) {
        super(context);
    }

    public MarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 宽度未知，等一次布局后再判断
        post(this::updateMarquee);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        post(this::updateMarquee);
    }

    /** 只有文字确实超宽才开启滚动；短标题保持静止（marquee 选中态会持续重绘）。 */
    private void updateMarquee() {
        int avail = getWidth() - getPaddingLeft() - getPaddingRight();
        if (avail <= 0) {
            return;
        }
        boolean overflow = getPaint().measureText(getText().toString()) > avail;
        if (isSelected() != overflow) {
            setSelected(overflow);
        }
    }
}
