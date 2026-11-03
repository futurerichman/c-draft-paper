package com.ccode.mintest;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

/**
 * C 语言「换行自动缩进」（VSCode 风格）。
 *
 * <p>规则（对应需求）：
 * <ol>
 *   <li>新行继承上一行的前导空白，空格 / 制表符原样继承；</li>
 *   <li>上一行去掉尾部空白后若以 <code>{</code> 结尾，新行再追加一级缩进；</li>
 *   <li>缩进单位自适应：上一行前导空白含 Tab → 加 1 个 Tab，否则加 4 个空格；</li>
 *   <li>在行中间按回车同样生效：光标之后的内容留在新行，缩进补在它前面；</li>
 *   <li>不做自动补 <code>}</code>（避免误伤既有输入习惯）。</li>
 * </ol>
 *
 * <h2>为什么走 TextWatcher，而不是重写 onCreateInputConnection 包装 InputConnection</h2>
 *
 * <p>两条路线都可行，但 TextWatcher 方案在「真机可靠性」上有决定性优势：
 * <ol>
 *   <li><b>入口唯一且必然覆盖</b>：不论换行符是来自软键盘
 *       {@code InputConnection.commitText("\n")}、硬件回车、粘贴多行文本、
 *       还是程序化 {@code Editable.insert()}，最终都必须修改 {@code Editable}；
 *       而 {@code Editable} 一旦被修改，框架就<b>必然</b>同步派发
 *       {@link TextWatcher} 的三个回调。也就是说 TextWatcher 是「所有输入路径的
 *       收敛点」，覆盖率是框架保证的 100%，不需要关心 IME 走的是哪条通道。</li>
 *   <li><b>不依赖无法验证的设备假设</b>：{@code onCreateInputConnection} 只能覆盖
 *       IME 通信通道；硬件回车是否回落到 InputConnection，取决于 ROM / IME 绑定
 *       状态。本次开发无法上机验证（模拟器与 adb 由 QA 占用），不应引入无法核实
 *       的设备相关分支。</li>
 *   <li><b>不新增 View 类型</b>：无需自定义 EditText 子类、无需改 layout 的
 *       标签，对现有 {@code findViewById(R.id.etCode)}、双指缩放
 *       {@code OnTouchListener}、字号持久化逻辑零影响。</li>
 * </ol>
 *
 * <h2>与 CSyntaxHighlighter 的协作</h2>
 *
 * <p>本类必须<b>后于</b> {@link CSyntaxHighlighter} 注册到同一个 EditText 上。
 * 由于 TextView 按注册顺序依次派发回调，每次文本变更的执行顺序恒为：
 * <pre>
 *   1. 高亮器 afterTextChanged → 补齐行索引 + 重着色（此时行表已是最新）
 *   2. 本类   afterTextChanged → 判断是否换行，插入缩进
 *      ├─ beginExternalInsert：挂起高亮器监听，登记插入点行号
 *      ├─ Editable.insert(...)
 *      └─ endExternalInsert：显式同步一次行索引 + 重着色
 * </pre>
 * 缩进插入通过 {@link CSyntaxHighlighter#beginExternalInsert} /
 * {@link #endExternalInsert} 显式 bracket，而不是让高亮器被动触发——
 * 因为缩进插入发生在高亮器的回调栈内，若走被动回调会被其重入保护吞掉，
 * 导致行索引漏更新。
 */
public final class CAutoIndent implements TextWatcher {

    /** 缩进单位：一个 Tab */
    private static final String UNIT_TAB = "\t";
    /** 缩进单位：四个空格 */
    private static final String UNIT_SPACE = "    ";

    private final EditText mEditor;
    /** 高亮器引用：插入缩进后显式通知它同步，避免被动回调被重入保护吞掉 */
    private final CSyntaxHighlighter mHighlighter;

    /** 正在补缩进，防重入（避免 TextWatcher 自触发形成死循环） */
    private boolean mApplying;
    /** 本次变更是否"恰好插入了一个换行符" */
    private boolean mNewlineInserted;
    /** 刚插入的换行符下标 */
    private int mNewlinePos = -1;

    /**
     * 构造并挂载自动缩进。
     *
     * @param editor      需要自动缩进的编辑框
     * @param highlighter 已挂载在同一编辑框上的高亮器（用于插入缩进后同步着色）
     */
    public CAutoIndent(EditText editor, CSyntaxHighlighter highlighter) {
        mEditor = editor;
        mHighlighter = highlighter;
        // 必须在 highlighter 之后注册，保证每次变更先由高亮器同步行索引与着色
        mEditor.addTextChangedListener(this);
    }

    /** 解除挂载（界面销毁时调用） */
    public void detach() {
        mEditor.removeTextChangedListener(this);
    }

    /* ==================== TextWatcher ==================== */

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        if (mApplying) return;
        mNewlineInserted = false;
        mNewlinePos = -1;
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (mApplying) return;
        // 只有"本次插入的内容恰好是一个换行符"才补缩进。
        // 这样粘贴多行代码、输入法整批提交等场景不会被误加缩进。
        mNewlineInserted = count == 1 && start < s.length()
                && s.charAt(start) == '\n';
        mNewlinePos = mNewlineInserted ? start : -1;
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (mApplying) return;
        if (!mNewlineInserted) return;
        mNewlineInserted = false;

        final int nl = mNewlinePos;
        if (nl < 0 || nl >= s.length()) return;

        final String indent = computeIndent(s, nl);
        if (indent.length() == 0) return; // 上一行无缩进，无需补

        final int target = nl + 1 + indent.length();

        mApplying = true;
        try {
            // 插入前登记：挂起高亮器被动监听，并取到插入点所属行号（旧坐标系）
            mHighlighter.beginExternalInsert(s, nl + 1);
            // 插入缩进（纯空白，不改变着色结果，但会改变后续行的字符偏移）
            s.insert(nl + 1, indent);
        } finally {
            // 即使 insert 抛异常也必须收尾，否则高亮器会永久停在"外部修改"挂起态
            mApplying = false;
            // 显式同步：行索引更新 + 重着色只做一遍
            mHighlighter.endExternalInsert(s, indent.length(), 0);
        }

        // 需求：补完缩进后光标落在缩进之后。
        // 先同步设置一次（覆盖绝大多数输入法）；再 post 一次兜底——
        // 部分 IME 的 commitText 会在 Editable.replace 返回后，按 newCursorPosition
        // 重新定位光标，这一动作发生在本回调之后，会覆盖同步设置的结果；
        // post 到消息队列尾部即可在其之后执行并纠正回正确位置。
        mEditor.setSelection(target);
        mEditor.post(new Runnable() {
            @Override
            public void run() {
                if (target <= mEditor.length()) mEditor.setSelection(target);
            }
        });
    }

    /* ==================== 缩进计算 ==================== */

    /**
     * 计算换行后新行应有的前导缩进。
     *
     * <p>取「上一行中位于光标之前的那一段」作为依据：
     * 其前导空白原样继承；去掉尾部空白后若以 <code>{</code> 结尾，再追加一级。
     *
     * @param text       当前完整文本（换行符已插入）
     * @param newlinePos 刚插入的 '\n' 在 text 中的下标
     * @return 需要插入到新行行首的空白字符串，可能为 ""
     */
    private static String computeIndent(CharSequence text, int newlinePos) {
        // 1) 上一行行首：newlinePos 之前最近一个 '\n' 的下一个位置（没有则 0）
        int prevStart = 0;
        for (int i = newlinePos - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n') {
                prevStart = i + 1;
                break;
            }
        }

        // 2) 继承上一行的前导空白（空格 / Tab 原样保留）
        int p = prevStart;
        while (p < newlinePos && isIndentChar(text.charAt(p))) p++;
        String indent = text.subSequence(prevStart, p).toString();

        // 3) 去掉尾部空白后以 '{' 结尾 → 再加一级缩进
        int end = newlinePos;
        while (end > prevStart && isTrailingBlank(text.charAt(end - 1))) end--;
        if (end > prevStart && text.charAt(end - 1) == '{') {
            indent += indent.indexOf('\t') >= 0 ? UNIT_TAB : UNIT_SPACE;
        }
        return indent;
    }

    /* ==================== 字符工具 ==================== */

    /** 前导缩进字符：空格或 Tab */
    private static boolean isIndentChar(char c) {
        return c == ' ' || c == '\t';
    }

    /** 行尾空白：空格、Tab 或回车 */
    private static boolean isTrailingBlank(char c) {
        return c == ' ' || c == '\t' || c == '\r';
    }
}
