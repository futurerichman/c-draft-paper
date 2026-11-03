package com.ccode.mintest;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import java.util.HashMap;
import java.util.Map;

/**
 * v1.28.7：成对符号自动补全。
 * 用户在编辑区输入 ( ) [ ] { } " " ' ' ` ` 等符号的前一半时，
 * 自动补全另一半，并把光标停留在两个符号中间。
 * <p>
 * 与既有编辑逻辑的兼容性设计：
 * 1. 借鉴 CAutoIndent 的 mApplying 防重入模式：本类自己修改文本时置 mApplying，
 *    避免被自身 afterTextChanged 再次触发造成死循环；
 * 2. 只在 afterTextChanged 里修改文本（TextWatcher 规范），并在插入前校验
 *    「目标字符仍在原位 + 光标未移走」，避免干扰删除 / 粘贴 / 选区替换；
 * 3. 与 CAutoIndent 互不感知：本类插入的字符不含换行，缩进器对其为 no-op；
 *    高亮器收到文本变更会正常重新着色，既有功能不受影响。
 */
public class CBracketAutoComplete implements TextWatcher {

    /** 左符号 -> 右符号 */
    private static final Map<Character, Character> PAIRS = new HashMap<Character, Character>();

    static {
        PAIRS.put('(', ')');
        PAIRS.put('[', ']');
        PAIRS.put('{', '}');
        PAIRS.put('"', '"');
        PAIRS.put('\'', '\'');
        PAIRS.put('`', '`');
    }

    private final EditText mEditor;
    /** 防重入：本类修改文本期间为 true，避免自身递归触发 */
    private boolean mApplying = false;
    /** 待补全的左符号位置（-1 表示无待办） */
    private int mPendingStart = -1;
    /** 待补全的左符号 */
    private char mPendingOpen = 0;

    public CBracketAutoComplete(EditText editor) {
        mEditor = editor;
        mEditor.addTextChangedListener(this);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // 不需要
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (mApplying) return;
        // 仅处理「逐字插入单个字符」：排除删除(before>0 且 count==0)、
        // 粘贴(count>1)、选区替换(before>0 && count==1)
        if (before != 0 || count != 1) return;
        char c = s.charAt(start);
        if (!PAIRS.containsKey(c)) return;
        mPendingStart = start;
        mPendingOpen = c;
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (mApplying) return;
        if (mPendingStart < 0) return;
        char open = mPendingOpen;
        int pos = mPendingStart;
        mPendingStart = -1;
        mPendingOpen = 0;
        // 校验：目标位置仍是那个左符号、且光标未移走（光标应紧跟在左符号后）
        if (pos >= s.length()) return;
        if (s.charAt(pos) != open) return;
        if (mEditor.getSelectionStart() != pos + 1) return;
        char close = PAIRS.get(open);
        mApplying = true;
        s.insert(pos + 1, String.valueOf(close));
        // 光标停在两个符号中间（左符号之后、右符号之前）
        mEditor.setSelection(pos + 1);
        mApplying = false;
    }
}
