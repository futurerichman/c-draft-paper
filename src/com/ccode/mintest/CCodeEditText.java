package com.ccode.mintest;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.EditText;

/**
 * v1.28.18：自定义编辑区 EditText（触摸穿透）。
 *
 * <p>背景：视频背景置顶（全屏模式）时，{@code liftCodeToRoot()} 把 etCode 挂到根层且
 * 全屏透明铺满，视觉上仍能看到下层行号栏，但 etCode 作为 EditText 会消费整个区域的
 * 触摸事件，导致点击左侧行号栏无法切换断点（Bug2）。</p>
 *
 * <p>修复思路（参考开源代码编辑器 ACodeEditor / CodeView 的行号面板与编辑区同容器
 * 兄弟视图触摸分发设计）：行号栏宽度为 44dp，本类在左侧 {@link #LINE_NUMBER_WIDTH_DP}
 * 区域内不消费触摸 —— {@link #onTouchEvent} 返回 {@code false}，事件沿 ViewGroup
 * 分发链回传，最终落到下层行号栏 {@link CLineNumberView} 切换断点。布局中 etCode
 * 的 {@code paddingStart} 为 54dp（&gt; 44dp），左侧 44dp 内无文字/光标，穿透不会误触
 * 编辑文本。</p>
 *
 * <p>DOWN 返回 {@code false} 后，Android 事件分发机制会把完整 DOWN/MOVE/UP 序列
 * 重新派发给真正消费 DOWN 的下层视图（本项目中为行号栏），保证序列完整穿透；同时
 * 视觉上 etCode 保持透明、置顶图层效果不变。</p>
 */
public class CCodeEditText extends EditText {

    /** 行号栏宽度（dp），与布局中 lineNumbers 的宽度保持一致 */
    private static final float LINE_NUMBER_WIDTH_DP = 44f;

    public CCodeEditText(Context context) {
        super(context);
    }

    public CCodeEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CCodeEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && event.getX() <= dp(LINE_NUMBER_WIDTH_DP)) {
            // 按下发生在左侧行号栏区域：不消费触摸，事件回传并穿透到下层行号栏。
            // 系统会把后续 MOVE/UP 完整序列重派发给实际消费 DOWN 的下层视图。
            return false;
        }
        return super.onTouchEvent(event);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
