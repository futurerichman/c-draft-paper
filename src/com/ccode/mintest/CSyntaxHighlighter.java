package com.ccode.mintest;

import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.widget.EditText;

import java.util.HashSet;
import java.util.Set;

/**
 * C 语言语法高亮器（VSCode Dark+ 配色）。
 *
 * <p>设计要点（参考 sora-editor / CodeEditor 等开源安卓编辑器的做法）：
 * <ol>
 *   <li><b>增量着色</b>：监听 {@link TextWatcher#afterTextChanged}，只重新扫描"受影响的行区间"，
 *       而不是每次输入都全量重刷整篇文本。</li>
 *   <li><b>块注释状态传播</b>：维护"每行扫描结束后是否仍处于块注释中"的状态数组。
 *       修改某行后，从该行向后重新推导状态，直到"行号已越过修改区且新旧状态一致"为止
 *       （不动点），因此块注释后面的行只在状态真的发生变化时才会被重刷。</li>
 *   <li><b>行首偏移表</b>：维护一张随编辑增量更新的行首偏移数组（插入/删除行时用
 *       {@link System#arraycopy} 整体搬移），把"字符偏移 → 行号"的查找降到 O(log n)。</li>
 *   <li><b>Span 不跨行</b>：所有 {@link ForegroundColorSpan} 都被限制在单行内，
 *       删除旧 span 与重新着色使用同一个行区间，避免 span 丢失或堆积。</li>
 * </ol>
 *
 * <p>着色规则：关键字、基础类型、预处理指令、字符串/字符字面量、数字字面量、注释。
 */
public final class CSyntaxHighlighter implements TextWatcher {

    /* ==================== 配色表（VSCode Dark+） ==================== */

    /** 普通文本色（不额外加 span，直接用 EditText 的 textColor） */
    public static final int COLOR_PLAIN = 0xFFD4D4D4;
    /** 普通关键字：蓝 */
    public static final int COLOR_KEYWORD = 0xFF569CD6;
    /** 控制流关键字：紫 */
    public static final int COLOR_CONTROL = 0xFFC586C0;
    /** 基础类型：青 */
    public static final int COLOR_TYPE = 0xFF4EC9B0;
    /** 字符串 / 字符字面量：橙 */
    public static final int COLOR_STRING = 0xFFCE9178;
    /** 数字字面量：浅绿 */
    public static final int COLOR_NUMBER = 0xFFB5CEA8;
    /** 注释：绿 */
    public static final int COLOR_COMMENT = 0xFF6A9955;
    /** 预处理指令：紫 */
    public static final int COLOR_PREPROC = 0xFFC586C0;

    /* ==================== 词表 ==================== */

    /** 控制流关键字（紫色） */
    private static final Set<String> CONTROL_KEYWORDS = buildSet(new String[]{
            "if", "else", "for", "while", "do", "switch", "case", "default",
            "break", "continue", "return", "goto"
    });

    /** 其它 C 关键字（蓝色） */
    private static final Set<String> KEYWORDS = buildSet(new String[]{
            "auto", "sizeof", "static", "extern", "const", "volatile", "register",
            "inline", "typedef", "struct", "union", "enum", "restrict", "asm",
            "_Alignas", "_Alignof", "_Atomic", "_Generic", "_Noreturn",
            "_Static_assert", "_Thread_local", "_Complex", "_Imaginary",
            "__attribute__", "__inline__", "__asm__"
    });

    /** 基础类型 / 常用标准库类型（青色） */
    private static final Set<String> TYPES = buildSet(new String[]{
            "int", "char", "float", "double", "long", "short", "signed", "unsigned",
            "void", "_Bool", "bool", "size_t", "ssize_t", "ptrdiff_t", "wchar_t",
            "int8_t", "int16_t", "int32_t", "int64_t",
            "uint8_t", "uint16_t", "uint32_t", "uint64_t",
            "intptr_t", "uintptr_t", "intmax_t", "uintmax_t",
            "FILE", "time_t", "clock_t", "div_t", "ldiv_t", "va_list", "jmp_buf",
            "fpos_t", "sig_atomic_t"
    });

    /* ==================== 常量 ==================== */

    /** 超过该长度的文本自动降级（不再着色），保证大文件下打字流畅 */
    private static final int MAX_HIGHLIGHT_CHARS = 2 * 1024 * 1024;

    /* ==================== 行索引 & 块注释状态缓存 ==================== */

    /** 行首偏移表：mLineStarts[i] 为第 i 行行首；mLineStarts[mLineCount] 为文本长度（哨兵） */
    private int[] mLineStarts = new int[256];
    /** mBlockStates[i]：扫描完第 i 行之后是否仍处于块注释中 */
    private boolean[] mBlockStates = new boolean[256];
    /** 当前缓存的逻辑行数（空文本也记 1 行） */
    private int mLineCount = 1;

    /* ==================== 单次编辑的增量信息 ==================== */

    private final EditText mEditor;
    /** 正在着色中（防止重入） */
    private boolean mHighlighting;
    /** 文本过大已降级（只清一次旧 span，之后不再着色） */
    private boolean mDegraded;
    /** 外部代码正在直接修改 Editable：挂起被动监听，改由显式 API 同步 */
    private boolean mExternal;
    /** 外部修改登记：插入点所属逻辑行号（插入前的坐标系） */
    private int mExtLine = 0;
    /** 本次变更起始行（旧文本坐标系） */
    private int mChangeLine = 0;
    /** 本次变更被删除的换行符个数 */
    private int mRemovedNewlines = 0;
    /** 本次变更新增的换行符个数 */
    private int mAddedNewlines = 0;
    /** 本次变更导致的字符总数变化量 */
    private int mCharDelta = 0;

    /**
     * 构造并挂载到指定编辑器上。
     *
     * @param editor 需要高亮的编辑框（等宽字体 + 深色背景）
     */
    public CSyntaxHighlighter(EditText editor) {
        mEditor = editor;
        mEditor.addTextChangedListener(this);
        rebuildLines(mEditor.getText());
    }

    /** 解除挂载（界面销毁时调用，避免空转） */
    public void detach() {
        mEditor.removeTextChangedListener(this);
    }

    /** 全量重新高亮：setText / 打开文件之后调用一次即可 */
    public void rehighlightAll() {
        Editable e = mEditor.getText();
        mDegraded = e.length() > MAX_HIGHLIGHT_CHARS;
        rebuildLines(e);
        if (mDegraded) {
            removeHighlights(e, 0, e.length());
            return;
        }
        highlight(e, 0, Math.max(0, mLineCount - 1));
    }

    /* ==================== 外部修改入口 ==================== */

    /**
     * 外部代码（如自动缩进）即将直接修改 Editable 之前调用。
     *
     * <p>作用有两点：
     * <ol>
     *   <li>挂起本类的被动监听，避免「插入 → 回调 → 再插入」的嵌套把增量行索引打乱；</li>
     *   <li>记下插入点所属的逻辑行号（插入前的坐标系），供 {@link #endExternalInsert} 使用。
     *       行号只能在插入前查，插入后行已经位移了。</li>
     * </ol>
     *
     * <p>必须与 {@link #endExternalInsert} 成对调用。
     *
     * @param e     即将被修改的 Editable
     * @param start 插入位置
     */
    public void beginExternalInsert(Editable e, int start) {
        // 保险：行索引哨兵必须等于当前文本长度，否则说明缓存与文本已失配，先全量重建
        if (mLineCount < 1 || mLineStarts[mLineCount] != e.length()) {
            rebuildLines(e);
        }
        if (start < 0) start = 0;
        if (start > e.length()) start = e.length();
        mExternal = true;
        mExtLine = lineOfOffset(start);
    }

    /**
     * 外部插入完成后调用：按登记信息一次性同步行索引并重新着色（只做一遍）。
     *
     * @param e                被修改的 Editable
     * @param insertedChars    插入的字符数
     * @param insertedNewlines 插入的换行符个数
     */
    public void endExternalInsert(Editable e, int insertedChars, int insertedNewlines) {
        mExternal = false;
        if (insertedChars <= 0 || e.length() > MAX_HIGHLIGHT_CHARS) return;
        spliceLines(e, mExtLine, 0, Math.max(0, insertedNewlines), insertedChars);
        int startLine = Math.max(0, Math.min(mExtLine, mLineCount - 1));
        int endLine = Math.min(mLineCount - 1,
                startLine + Math.max(0, insertedNewlines));
        highlight(e, startLine, endLine);
    }

    /* ==================== TextWatcher ==================== */

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        if (mHighlighting || mExternal) return;
        // 旧文本：记录变更起始行与被删掉的换行数
        mChangeLine = lineOfOffset(start);
        mRemovedNewlines = countNewlines(s, start, start + count);
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (mHighlighting || mExternal) return;
        // 新文本：记录新增的换行数与字符增量
        mAddedNewlines = countNewlines(s, start, start + count);
        mCharDelta = count - before;
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (mHighlighting || mExternal) return;
        if (s.length() > MAX_HIGHLIGHT_CHARS) {
            // 超大文件降级：清掉旧 span 后不再着色，保证打字流畅
            if (!mDegraded) {
                mDegraded = true;
                removeHighlights(s, 0, s.length());
            }
            return;
        }
        mDegraded = false;
        // 1) 增量更新行首偏移表（行增删时整体搬移）
        spliceLines(s, mChangeLine, mRemovedNewlines, mAddedNewlines, mCharDelta);
        // 2) 受影响区间：变更起始行 ~ 起始行 + 新增行数
        int startLine = mChangeLine;
        int endLine = mChangeLine + mAddedNewlines;
        if (endLine >= mLineCount) endLine = mLineCount - 1;
        highlight(s, startLine, endLine);
    }

    /* ==================== 核心着色流程 ==================== */

    /**
     * 对 [startLine, endLine] 行区间重新着色，并把块注释状态向后传播到不动点。
     *
     * @param e          当前可编辑文本
     * @param startLine  起始行（含）
     * @param endLine    结束行（含），超出范围会被夹紧
     */
    private void highlight(Editable e, int startLine, int endLine) {
        final int textLen = e.length();
        final int lineCount = mLineCount;
        if (startLine < 0) startLine = 0;
        if (startLine >= lineCount) startLine = lineCount - 1;
        if (endLine < startLine) endLine = startLine;
        if (endLine >= lineCount) endLine = lineCount - 1;

        mHighlighting = true;
        try {
            // 先清掉"变更区"的旧 span（后面逐行重新着色时会补回来）
            int clearFrom = mLineStarts[startLine];
            int clearTo = (endLine + 1 <= lineCount) ? mLineStarts[endLine + 1] : textLen;
            removeHighlights(e, clearFrom, clearTo);

            boolean inBlock = startLine > 0 && mBlockStates[startLine - 1];
            int line = startLine;
            while (line < lineCount) {
                int ls = mLineStarts[line];
                // 行内容区间为 [ls, le)：非末行要去掉结尾的 '\n'，末行直接取到文末
                int le = (line + 1 < lineCount) ? mLineStarts[line + 1] - 1 : textLen;
                if (le < ls) le = ls;

                if (line > endLine) {
                    // 已越过变更区：这一行只有在状态发生变化时才需要重刷
                    removeHighlights(e, ls, le);
                }
                boolean newState = colorizeLine(e, ls, le, inBlock);
                boolean oldState = mBlockStates[line];
                mBlockStates[line] = newState;
                if (line > endLine && newState == oldState) break;
                inBlock = newState;
                line++;
            }
        } finally {
            mHighlighting = false;
        }
    }

    /**
     * 对单行做词法扫描并着色。
     *
     * @param e       可编辑文本
     * @param start   行首偏移（含）
     * @param end     行尾偏移（不含结尾的 '\n'）
     * @param inBlock 进入本行时是否已经处于块注释中
     * @return 行末是否仍处于块注释中
     */
    private boolean colorizeLine(Editable e, int start, int end, boolean inBlock) {
        int i = start;
        boolean block = inBlock;
        while (i < end) {
            char c = e.charAt(i);

            // 1) 块注释内部：一路找到 "*/"
            if (block) {
                int close = indexOf(e, "*/", i, end);
                if (close < 0) {
                    setColor(e, i, end, COLOR_COMMENT);
                    return true;
                }
                setColor(e, i, close + 2, COLOR_COMMENT);
                i = close + 2;
                block = false;
                continue;
            }

            // 2) 注释起始：// 或 /*
            if (c == '/' && i + 1 < end) {
                char n = e.charAt(i + 1);
                if (n == '/') {
                    setColor(e, i, end, COLOR_COMMENT);
                    return false;
                }
                if (n == '*') {
                    int close = indexOf(e, "*/", i + 2, end);
                    if (close < 0) {
                        setColor(e, i, end, COLOR_COMMENT);
                        return true;
                    }
                    setColor(e, i, close + 2, COLOR_COMMENT);
                    i = close + 2;
                    continue;
                }
            }

            // 3) 预处理指令：行内第一个非空白字符为 '#'
            if (c == '#' && isBlankBefore(e, start, i)) {
                setColor(e, i, end, COLOR_PREPROC);
                return false;
            }

            // 4) 字符串 / 字符字面量（支持 \ 转义）
            if (c == '"' || c == '\'') {
                i = colorizeQuoted(e, i, end, c);
                continue;
            }

            // 5) 数字字面量（十进制 / 八进制 / 十六进制 / 浮点 / 后缀）
            if (isDigit(c) || (c == '.' && i + 1 < end && isDigit(e.charAt(i + 1)))) {
                i = colorizeNumber(e, i, end);
                continue;
            }

            // 6) 标识符 / 关键字
            if (isIdStart(c)) {
                int j = i + 1;
                while (j < end && isIdPart(e.charAt(j))) j++;
                int color = colorOfWord(e.subSequence(i, j).toString());
                if (color != 0) setColor(e, i, j, color);
                i = j;
                continue;
            }

            i++;
        }
        return block;
    }

    /**
     * 着色字符串 / 字符字面量（不跨行，C 语言规则）。
     *
     * @return 字面量结束后的下一个字符位置
     */
    private static int colorizeQuoted(Editable e, int start, int end, char quote) {
        int i = start + 1;
        while (i < end) {
            char c = e.charAt(i);
            if (c == '\\') {
                i += 2; // 跳过转义字符
                continue;
            }
            if (c == quote) {
                i++;
                break;
            }
            i++;
        }
        if (i > end) i = end;
        setColor(e, start, i, COLOR_STRING);
        return i;
    }

    /**
     * 着色数字字面量，支持 123 / 0x1F / 0755 / 1.5 / .5 / 1e-3 / 1.5f / 123ULL。
     *
     * @return 数字结束后的下一个字符位置
     */
    private static int colorizeNumber(Editable e, int start, int end) {
        int i = start;
        boolean hex = false;

        if (e.charAt(i) == '0' && i + 1 < end) {
            char n = e.charAt(i + 1);
            if (n == 'x' || n == 'X') {
                hex = true;
                i += 2;
                while (i < end && isHexDigit(e.charAt(i))) i++;
            } else if (n == '.') {
                i++;
                while (i < end && isDigit(e.charAt(i))) i++;
                i = scanExponent(e, i, end);
            } else if (n >= '0' && n <= '7') {
                i++; // 八进制
                while (i < end && e.charAt(i) >= '0' && e.charAt(i) <= '7') i++;
            } else {
                i++; // 单独的 0
            }
        } else {
            while (i < end && isDigit(e.charAt(i))) i++;
            if (i < end && e.charAt(i) == '.') {
                i++;
                while (i < end && isDigit(e.charAt(i))) i++;
            }
            i = scanExponent(e, i, end);
        }

        // 整数 / 浮点后缀：u U l L f F（十六进制不支持 f 后缀）
        if (!hex) {
            while (i < end) {
                char c = e.charAt(i);
                if (c == 'u' || c == 'U' || c == 'l' || c == 'L'
                        || c == 'f' || c == 'F') {
                    i++;
                } else {
                    break;
                }
            }
        }

        if (i <= start) i = start + 1; // 兜底，保证前进
        setColor(e, start, i, COLOR_NUMBER);
        return i;
    }

    /** 扫描科学计数法指数部分，如 e+12 / E-3 */
    private static int scanExponent(CharSequence s, int i, int end) {
        if (i >= end) return i;
        char c = s.charAt(i);
        if (c != 'e' && c != 'E') return i;
        int j = i + 1;
        if (j < end && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
        if (j < end && isDigit(s.charAt(j))) {
            j++;
            while (j < end && isDigit(s.charAt(j))) j++;
            return j;
        }
        return i; // 不是合法指数，回退
    }

    /* ==================== Span 工具 ==================== */

    /** 为 [start, end) 区间设置前景色 */
    private static void setColor(Editable e, int start, int end, int color) {
        if (end <= start) return;
        e.setSpan(new ForegroundColorSpan(color), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 移除指定区间内所有前景色 span（只删颜色，不动文字） */
    private static void removeHighlights(Editable e, int start, int end) {
        if (end <= start) return;
        ForegroundColorSpan[] spans = e.getSpans(start, end, ForegroundColorSpan.class);
        if (spans == null) return;
        for (int i = 0; i < spans.length; i++) {
            e.removeSpan(spans[i]);
        }
    }

    /* ==================== 行索引维护 ==================== */

    /** 全量重建行首偏移表 */
    private void rebuildLines(CharSequence text) {
        int len = text.length();
        int needed = len + 2; // 每行至少一个字符，留足哨兵位置
        if (mLineStarts.length < needed) mLineStarts = new int[needed];
        if (mBlockStates.length < needed) mBlockStates = new boolean[needed];

        int count = 1;
        mLineStarts[0] = 0;
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == '\n') mLineStarts[count++] = i + 1;
        }
        mLineStarts[count] = len; // 哨兵
        mLineCount = count;
    }

    /**
     * 二分查找字符偏移所属的逻辑行号。
     *
     * @param offset 字符偏移（0 ~ 文本长度）
     * @return 满足 mLineStarts[line] &le; offset 的最大行号
     */
    private int lineOfOffset(int offset) {
        if (mLineCount <= 1) return 0;
        int lo = 0;
        int hi = mLineCount - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >> 1;
            if (mLineStarts[mid] <= offset) lo = mid;
            else hi = mid - 1;
        }
        if (mLineStarts[lo] > offset && lo > 0) lo--; // 兜底
        return lo;
    }

    /**
     * 增量更新行首偏移表与块注释状态数组。
     *
     * @param text            变更后的完整文本
     * @param firstLine       变更起始行（旧文本坐标系，取值来自 {@link #lineOfOffset}）
     * @param removedNewlines 被替换掉的区间内的换行符个数
     * @param addedNewlines   新插入文本中的换行符个数
     * @param charDelta       文本总长度变化量（新长度 - 旧长度）
     */
    private void spliceLines(CharSequence text, int firstLine, int removedNewlines,
                             int addedNewlines, int charDelta) {
        if (firstLine < 0) firstLine = 0;
        if (firstLine >= mLineCount) firstLine = mLineCount - 1;

        int newLineCount = mLineCount - removedNewlines + addedNewlines;
        int keepFrom = firstLine + removedNewlines + 1;   // 旧表中第一个保留的尾部下标
        int newKeepFrom = firstLine + addedNewlines + 1;  // 新表中对应的下标
        int tailLen = mLineCount + 1 - keepFrom;

        // 缓存与文本不一致时，直接全量重建，保证正确性
        if (newLineCount < 1 || tailLen < 0 || newKeepFrom + tailLen > newLineCount + 1) {
            rebuildLines(text);
            return;
        }

        int need = newLineCount + 1;
        if (mLineStarts.length < need) {
            int[] ns = new int[Math.max(need, mLineStarts.length * 2)];
            System.arraycopy(mLineStarts, 0, ns, 0, mLineCount + 1);
            mLineStarts = ns;
        }
        if (mBlockStates.length < need) {
            boolean[] nb = new boolean[Math.max(need, mBlockStates.length * 2)];
            System.arraycopy(mBlockStates, 0, nb, 0, mLineCount);
            mBlockStates = nb;
        }

        // 1) 搬移尾部（行首偏移 + 块注释状态）
        if (tailLen > 0) {
            System.arraycopy(mLineStarts, keepFrom, mLineStarts, newKeepFrom, tailLen);
            System.arraycopy(mBlockStates, keepFrom, mBlockStates, newKeepFrom,
                    Math.max(0, mLineCount - keepFrom));
        }

        // 2) 尾部所有偏移整体平移 charDelta（尾部行内容本身没变，只是位置移动了）
        for (int i = newKeepFrom; i <= newLineCount; i++) {
            mLineStarts[i] += charDelta;
        }

        // 3) 填入新增行的行首偏移（逐 '\n' 扫描新插入的文本）
        int idx = firstLine + 1;
        int scan = (firstLine <= mLineCount) ? mLineStarts[firstLine] : 0;
        for (int k = 0; k < addedNewlines; k++) {
            int nl = indexOfNewline(text, scan);
            if (nl < 0) break; // 兜底：理论上不会发生
            scan = nl + 1;
            if (idx <= newLineCount) mLineStarts[idx++] = scan;
        }

        // 4) 更新哨兵与行数
        mLineStarts[newLineCount] = text.length();
        mLineCount = newLineCount;
    }

    /* ==================== 字符 / 字符串工具 ==================== */

    /** 统计 [from, to) 区间内的换行符个数 */
    private static int countNewlines(CharSequence s, int from, int to) {
        int n = 0;
        if (to > s.length()) to = s.length();
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    /** 从 from 开始查找下一个 '\n'，找不到返回 -1 */
    private static int indexOfNewline(CharSequence s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '\n') return i;
        }
        return -1;
    }

    /** 在 [from, to) 区间内查找子串 */
    private static int indexOf(CharSequence s, String needle, int from, int to) {
        char first = needle.charAt(0);
        int last = to - needle.length();
        for (int i = from; i <= last; i++) {
            if (s.charAt(i) != first) continue;
            boolean ok = true;
            for (int k = 1; k < needle.length(); k++) {
                if (s.charAt(i + k) != needle.charAt(k)) {
                    ok = false;
                    break;
                }
            }
            if (ok) return i;
        }
        return -1;
    }

    /** [start, index) 是否全为空白（用于判断 '#' 是不是行首指令） */
    private static boolean isBlankBefore(CharSequence s, int start, int index) {
        for (int i = start; i < index; i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r') return false;
        }
        return true;
    }

    /** 标识符首字符：字母或下划线 */
    private static boolean isIdStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    /** 标识符后续字符：字母、数字或下划线 */
    private static boolean isIdPart(char c) {
        return isIdStart(c) || isDigit(c);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isDigit(c)
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
    }

    /**
     * 查询标识符对应的颜色。
     *
     * @param word 标识符文本
     * @return 配色值；返回 0 表示普通标识符，不需要着色
     */
    private static int colorOfWord(String word) {
        if (CONTROL_KEYWORDS.contains(word)) return COLOR_CONTROL;
        if (KEYWORDS.contains(word)) return COLOR_KEYWORD;
        if (TYPES.contains(word)) return COLOR_TYPE;
        return 0;
    }

    /** 把字符串数组转成哈希集合 */
    private static Set<String> buildSet(String[] words) {
        Set<String> set = new HashSet<String>(words.length * 2);
        for (int i = 0; i < words.length; i++) {
            set.add(words[i]);
        }
        return set;
    }
}
