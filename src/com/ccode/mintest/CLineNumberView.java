package com.ccode.mintest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import java.util.HashSet;
import java.util.Set;

/**
 * v1.28.9：编辑区左侧行号栏（类似 VS Code / ACodeEditor / CodeView 的行号面板）。
 *
 * <p>实现要点（参考开源安卓代码编辑器 ACodeEditor（ibrahimkaraca/ACodeEditor）与
 * CodeView（AmrDeveloper/CodeView）的行号面板设计）：</p>
 * <ul>
 *   <li>与 etCode 垂直滚动同步：通过 {@link View.OnScrollChangeListener} 接收滚动回调；</li>
 *   <li>行号按「逻辑行」（\n 计数）绘制，并映射到 etCode 的 {@link android.text.Layout}
 *       布局行取精确行顶位置 —— 即使开启自动换行（wrap），行号也显示在逻辑行首、与文本对齐；</li>
 *   <li>字号变化（双指捏合）实时跟随：onDraw 时取 {@link EditText#getLineHeight()}；</li>
 *   <li>点击行号在该行切换断点红点（参考 ACodeEditor 的断点展示：红点在行号左侧）。</li>
 * </ul>
 */
public class CLineNumberView extends View {

    /** 与行号栏绑定的编辑区（只读其文本、布局、滚动状态） */
    private EditText editor;
    /** etCode 当前垂直滚动偏移（由 OnScrollChangeListener 同步） */
    private int scrollY;
    /** 断点行集合（1-based 逻辑行号） */
    private final Set<Integer> breakpoints = new HashSet<Integer>();

    /** v1.28.18：行号字号 = 代码字号 × 该比例（参考 v1.28.16 的 onEditorFontSizeChanged 0.85f 等比联动） */
    private static final float LINE_NUMBER_SCALE = 0.85f;

    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint breakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 断点切换回调（MainActivity 用于持久化 / 同步调试指令） */
    public interface OnBreakpointToggleListener {
        void onBreakpointToggled(int line, boolean added);
    }

    private OnBreakpointToggleListener toggleListener;

    public CLineNumberView(Context context) {
        super(context);
        init();
    }

    public CLineNumberView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bgPaint.setColor(0xFF2D2D30); // v1.28.11：实心深灰黑，与工具栏/控制台一致（不透视频背景）
        numberPaint.setColor(0xFF858585); // VS Code 行号灰
        numberPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 12f, getResources().getDisplayMetrics()));
        numberPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        breakPaint.setColor(0xFFE51400); // VS Code 断点红
    }

    /** 绑定编辑区：文本变化、滚动变化、字号变化都会触发重绘 */
    public void setEditor(EditText ed) {
        if (editor == ed) return;
        editor = ed;
        if (editor != null) {
            editor.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { invalidate(); }
            });
            // API 23+：EditText 滚动（含触摸/光标移动导致的滚动）会回调这里
            editor.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override
                public void onScrollChange(View v, int scrollX, int newScrollY,
                                           int oldScrollX, int oldScrollY) {
                    scrollY = newScrollY;
                    invalidate();
                }
            });
            // v1.28.18：绑定后立即按当前代码字号同步行号字号（恢复保存字号/旋转重建后行号也正确）
            updateNumberPaintSize(editor.getTextSize());
        }
        invalidate();
    }

    /**
     * v1.28.18：代码字号变化（双指捏合缩放）时联动行号栏字号 —— 行号数字大小随代码
     * 字号等比增大（LINE_NUMBER_SCALE），并立即重绘。行距无需在此处理：onDraw 每帧
     * 取 editor.getLineHeight()，天然随代码字号联动，行号与代码行始终在同一水平线。
     */
    public void updateNumberPaintSize(float codeTextSizePx) {
        numberPaint.setTextSize(codeTextSizePx * LINE_NUMBER_SCALE);
        invalidate();
    }

    public Set<Integer> getBreakpoints() {
        return new HashSet<Integer>(breakpoints);
    }

    public void setBreakpoints(Set<Integer> lines) {
        breakpoints.clear();
        if (lines != null) {
            for (Integer l : lines) {
                if (l != null && l >= 1) breakpoints.add(l);
            }
        }
        invalidate();
    }

    public void setOnBreakpointToggleListener(OnBreakpointToggleListener l) {
        toggleListener = l;
    }

    /** 切换某逻辑行断点，返回添加（true）还是移除（false） */
    public boolean toggleBreakpoint(int line) {
        boolean added;
        if (breakpoints.contains(line)) {
            breakpoints.remove(line);
            added = false;
        } else {
            breakpoints.add(line);
            added = true;
        }
        invalidate();
        if (toggleListener != null) toggleListener.onBreakpointToggled(line, added);
        return added;
    }

    /** 静默设置/清除某行断点（不回调 toggleListener），供工具栏「断点」按钮做视觉同步 */
    public void setBreakpointSilently(int line, boolean add) {
        if (add) {
            breakpoints.add(line);
        } else {
            breakpoints.remove(line);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        if (editor == null) return;
        android.text.Layout layout = editor.getLayout();
        if (layout == null) return;
        Editable text = editor.getText();
        if (text == null) return;

        int lineHeight = editor.getLineHeight();
        int total = text.length();
        int offset = 0;
        int logicalLine = 1;
        String full = text.toString();
        while (offset <= total) {
            int nl = full.indexOf('\n', offset);
            int lineEnd = (nl < 0) ? total : nl;
            if (lineEnd < offset) break;
            // 逻辑行首字符在布局中的行与顶边（支持 wrap：行号显示在逻辑行首）
            int layoutLine = layout.getLineForOffset(offset);
            // v1.28.19：行号 y 需与 etCode 内容区第一行严格同水平线。
            // Layout.getLineTop() 是相对 etCode「内容区」（padding 内）的坐标，TextView 绘制
            // 时先按 padding 平移到内容区再叠加 View 滚动偏移，因此屏幕 y =
            // getLineTop(i) + editor.getPaddingTop() - scrollY。此前漏加 paddingTop
            // （布局 paddingVertical=8dp），导致行号整体比代码高 8dp、首行起就不对齐。
            // 参考 ACodeEditor（ibrahimkaraca/ACodeEditor）行号面板 y 计算：
            // lineNumberView 与 editor 同容器顶部对齐时，行号 y = editor.getLayout()
            // .getLineTop(i) - editor.getScrollY() + editor.getPaddingTop()。
            int top = layout.getLineTop(layoutLine) + editor.getPaddingTop() - scrollY;
            int bottom = top + lineHeight;
            if (bottom >= 0 && top <= getHeight()) {
                Paint.FontMetrics fm = numberPaint.getFontMetrics();
                // v1.28.12：红点与行号数字垂直居中对齐。
                // 红点圆心 cy = top + lineHeight/2（行中心）；
                // 文字视觉中心 = baseline + (ascent + descent)/2，
                // 令其等于 cy => baseline = top + (lineHeight - ascent - descent)/2。
                float baseline = top + (lineHeight - fm.ascent - fm.descent) / 2f;
                String num = String.valueOf(logicalLine);
                float x = getWidth() - numberPaint.measureText(num) - dp(8);
                canvas.drawText(num, x, baseline, numberPaint);
                if (breakpoints.contains(logicalLine)) {
                    float cy = top + lineHeight / 2f;
                    canvas.drawCircle(dp(10), cy, dp(4), breakPaint);
                }
            }
            if (nl < 0) break;
            offset = nl + 1;
            logicalLine++;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && editor != null) {
            android.text.Layout layout = editor.getLayout();
            if (layout != null) {
                int layoutLine = layout.getLineForVertical(scrollY + (int) e.getY());
                int startOffset = layout.getLineStart(layoutLine);
                int logicalLine = logicalLineOfOffset(editor.getText() == null
                        ? "" : editor.getText().toString(), startOffset);
                toggleBreakpoint(logicalLine);
            }
            return true;
        }
        return super.onTouchEvent(e);
    }

    /** 由字符偏移反查逻辑行号（1-based），与 MainActivity.lineOfOffsetInText 一致 */
    private static int logicalLineOfOffset(String text, int offset) {
        int line = 1;
        int n = Math.min(offset, text.length());
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
