package com.ccode.mintest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.18：单步调试源码插桩器。
 * v1.19：新增「变量数值检测」——插桩时用轻量词法扫描收集作用域内的变量，
 * 把「变量名=格式符」串随 {@code DEBUG_HOOK} 一起生成（例如
 * {@code DEBUG_HOOK(5, "a=%d, b=%g", a, b);}），暂停时由 C 端格式化并通过
 * LocalSocket 以 {@code VARS <值串>} 上报，App 端显示在控制台。
 *
 * <p>思路（参考 my_gdb / dmce 等开源「插桩式」调试器，并针对本项目「设备端 TCC、零第三方库」
 * 的约束做了裁剪）：在用户 C 源码的每条语句前插入 {@code DEBUG_HOOK(<原行号>);} 调用，并把这个
 * C 函数（DEBUG_SHIM）拼接到源码最前面一起交给 TCC 编译。运行时 DEBUG_HOOK 通过抽象命名空间
 * LocalSocket 把「当前行号 + 变量值」上报给 App，并阻塞等待 STEP / CONT / BREAK 指令，从而实现
 * 「逐行暂停 + 步过 + 继续 + 断点 + 变量查看」，完全不需要 gdb / NDK / 大体积原生二进制。
 *
 * <p>插桩是近似的（轻量词法扫描，非完整 C 解析），以「绝不产生语法错误」为第一原则：只在
 * 「函数体内、非续行、非标签、非纯花括号」的行前插入；变量收集同样只认「能可靠识别类型」的
 * 简单声明（int/float/double/char/指针/数组等），复杂类型一律跳过，避免生成错误的格式化参数
 * 导致编译失败。代价是极少数跨行的语句或复杂类型变量可能不被显示，对教学型单步已足够。
 */
public final class CDebugInstrumenter {

    /**
     * 调试运行时 C 源码：定义 DEBUG_HOOK，并通过抽象 LocalSocket 与 App 通信。
     * 该文本会被拼接到用户源码之前，因此 DEBUG_HOOK 的定义在用户代码调用它之前。
     *
     * <p>v1.19：DEBUG_HOOK 增加可变参数 {@code fmt, ...}——由插桩器静态生成
     * 「变量名=格式符」串及对应变量实参，暂停时用 vsnprintf 格式化后随 LINE 消息
     * 之后发送一条 {@code VARS <值串>} 消息；fmt 传 0 表示本行无可监视变量。
     */
    public static final String DEBUG_SHIM =
            "#include <stdio.h>\n"
            + "#include <stdlib.h>\n"
            + "#include <string.h>\n"
            + "#include <stdarg.h>\n"
            + "#include <unistd.h>\n"
            + "#include <sys/socket.h>\n"
            + "#include <sys/un.h>\n"
            + "#include <stddef.h>\n"
            + "\n"
            + "static int  g_sock = -1;\n"
            + "static int  g_bp[1024];\n"
            + "static int  g_nbp = 0;\n"
            + "static int  g_step = 1;   /* 启动即处于单步模式，使第一行就暂停 */\n"
            + "\n"
            + "static void dbg_connect(void) {\n"
            + "    const char *name = getenv(\"CCODE_DBG_SOCK\");\n"
            + "    if (!name) return;\n"
            + "    int fd = socket(AF_UNIX, SOCK_STREAM, 0);\n"
            + "    if (fd < 0) return;\n"
            + "    struct sockaddr_un a;\n"
            + "    memset(&a, 0, sizeof(a));\n"
            + "    a.sun_family = AF_UNIX;\n"
            + "    size_t n = strlen(name);\n"
            + "    if (n > sizeof(a.sun_path) - 2) n = sizeof(a.sun_path) - 2;\n"
            + "    a.sun_path[0] = 0;                 /* 抽象命名空间：首字节为 0 */\n"
            + "    memcpy(a.sun_path + 1, name, n);\n"
            + "    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);\n"
            + "    if (connect(fd, (struct sockaddr *)&a, len) == 0) {\n"
            + "        g_sock = fd;\n"
            + "    } else {\n"
            + "        close(fd);\n"
            + "    }\n"
            + "}\n"
            + "\n"
            + "void DEBUG_HOOK(int line, const char *fmt, ...) {\n"
            + "    if (g_sock < 0) dbg_connect();\n"
            + "    if (g_sock < 0) return;            /* 未挂载调试器：原样运行 */\n"
            + "    int hit = g_step;\n"
            + "    for (int i = 0; i < g_nbp; i++) if (g_bp[i] == line) hit = 1;\n"
            + "    if (!hit) return;\n"
            + "    g_step = 0;\n"
            + "    char buf[64];\n"
            + "    int n = snprintf(buf, sizeof(buf), \"LINE %d\\n\", line);\n"
            + "    if (send(g_sock, buf, n, 0) != n) { close(g_sock); g_sock = -1; return; }\n"
            + "    if (fmt != NULL) {\n"
            + "        char vbuf[512];\n"
            + "        va_list ap;\n"
            + "        va_start(ap, fmt);\n"
            + "        int vn = vsnprintf(vbuf, sizeof(vbuf), fmt, ap);\n"
            + "        va_end(ap);\n"
            + "        if (vn < 0) vn = 0;\n"
            + "        if (vn >= (int)sizeof(vbuf)) vn = (int)sizeof(vbuf) - 1;\n"
            + "        char msg[576];\n"
            + "        int mn = snprintf(msg, sizeof(msg), \"VARS %s\\n\", vbuf);\n"
            + "        if (mn > 0 && send(g_sock, msg, mn, 0) != mn) {\n"
            + "            close(g_sock); g_sock = -1; return;\n"
            + "        }\n"
            + "    }\n"
            + "    char cmd[32];\n"
            + "    int r = recv(g_sock, cmd, sizeof(cmd) - 1, 0);\n"
            + "    if (r <= 0) { close(g_sock); g_sock = -1; return; }\n"
            + "    cmd[r] = 0;\n"
            + "    if (strncmp(cmd, \"STEP\", 4) == 0)      { g_step = 1; }\n"
            + "    else if (strncmp(cmd, \"CONT\", 4) == 0) { g_step = 0; }\n"
            + "    else if (strncmp(cmd, \"BREAK\", 5) == 0) {\n"
            + "        int ln = atoi(cmd + 5);\n"
            + "        if (g_nbp < 1024) g_bp[g_nbp++] = ln;\n"
            + "        g_step = 0;\n"
            + "    }\n"
            + "}\n";

    /** 简单声明行的类型识别（基础类型关键字，允许 const 修饰） */
    private static final Pattern TYPE_PREFIX = Pattern.compile(
            "^(?:const\\s+)?(?:(?:unsigned|signed|short|long|char|int|float|double|void|bool)\\b\\s*)+");

    /** 单行内简单声明：类型 + 可选指针 + 变量名 + 可选数组 */
    private static final Pattern SIMPLE_DECL = Pattern.compile(
            "^(?:const\\s+)?(?:(?:unsigned|signed|short|long|char|int|float|double|void|bool)\\b\\s*)+"
                    + "(\\*{0,2})\\s*([A-Za-z_]\\w*)\\s*(\\[[^\\]]*\\])?");

    /** for / if / while 头部里的声明：如 {@code for (int i = 0; ...)} */
    private static final Pattern HEADER_DECL = Pattern.compile(
            "^(?:for|if|while|switch)\\s*\\(\\s*"
                    + "(?:const\\s+)?(?:(?:unsigned|signed|short|long|char|int|float|double|void|bool)\\b\\s*)+"
                    + "(\\*{0,2})\\s*([A-Za-z_]\\w*)\\s*(\\[[^\\]]*\\])?");

    /** 把用户源码插桩，返回「调试运行时 + 插桩后源码」拼接而成的完整可编译文本 */
    public static String instrument(String src) {
        StringBuilder out = new StringBuilder();
        out.append(DEBUG_SHIM).append('\n');

        // 变量作用域栈：每层一个 Map<变量名, 格式符>；栈底是全局/顶层
        List<Map<String, String>> scopes = new ArrayList<Map<String, String>>();
        scopes.add(new LinkedHashMap<String, String>());

        String[] lines = src.split("\n", -1);
        int depth = 0;          // 花括号深度（近似，忽略字符串/注释内的括号）
        boolean stmtOpen = false;// 上一行是否留下「未结束的语句」（续行）
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String trimmed = raw.trim();

            boolean isBlank = trimmed.isEmpty();
            boolean isPre = trimmed.startsWith("#");
            boolean isLineComment = trimmed.startsWith("//");
            boolean isBracesOnly = trimmed.equals("{") || trimmed.equals("}")
                    || trimmed.equals("};") || trimmed.equals("){");
            boolean isLabel = trimmed.endsWith(":") && !trimmed.contains("?");
            // 「} else {」/「else if」等以 } 或 else 开头的行不能插桩（前面加语句会破坏语法）
            boolean isElseBranch = trimmed.startsWith("}") || trimmed.startsWith("else");

            boolean instrumentable = !isBlank && !isPre && !isLineComment
                    && !isBracesOnly && !isLabel && !isElseBranch
                    && depth >= 1 && !stmtOpen;

            if (instrumentable) {
                String indent = raw.substring(0, raw.length() - trimmed.length());
                out.append(indent).append(buildHook(i + 1, scopes)).append('\n');
            }
            out.append(raw).append('\n');

            // 更新作用域与深度（仅对「真实代码行」近似统计，忽略行内注释/字符串）
            if (!isLineComment && !isPre) {
                String code = stripLineComment(trimmed);
                processBraces(code, scopes);
                collectDecls(code, scopes);
                depth += netBraces(trimmed);
                if (depth < 0) depth = 0;
                char last = code.isEmpty() ? ' ' : code.charAt(code.length() - 1);
                stmtOpen = !(last == ';' || last == '{' || last == '}');
            }
        }
        return out.toString();
    }

    /** 构造一行 DEBUG_HOOK 调用：带当前可见变量（名字=格式符, 实参列表） */
    private static String buildHook(int line, List<Map<String, String>> scopes) {
        List<Map.Entry<String, String>> vars = visibleVars(scopes);
        if (vars.isEmpty()) {
            return "DEBUG_HOOK(" + line + ", 0);";
        }
        StringBuilder fmt = new StringBuilder();
        StringBuilder args = new StringBuilder();
        for (Map.Entry<String, String> e : vars) {
            if (fmt.length() > 0) fmt.append(", ");
            fmt.append(e.getKey()).append('=').append(e.getValue());
            if (args.length() > 0) args.append(", ");
            args.append(e.getKey());
        }
        return "DEBUG_HOOK(" + line + ", \"" + fmt + "\", " + args + ");";
    }

    /** 合并所有作用域的可见变量（内层覆盖外层，保持声明顺序） */
    private static List<Map.Entry<String, String>> visibleVars(
            List<Map<String, String>> scopes) {
        Map<String, String> merged = new LinkedHashMap<String, String>();
        for (int i = 0; i < scopes.size(); i++) {
            Map<String, String> scope = scopes.get(i);
            for (Map.Entry<String, String> e : scope.entrySet()) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        return new ArrayList<Map.Entry<String, String>>(merged.entrySet());
    }

    /** 逐字符扫描一行代码，维护花括号作用域栈（忽略字符串/字符/行注释内括号） */
    private static void processBraces(String code, List<Map<String, String>> scopes) {
        boolean inStr = false, inChr = false, inCmt = false;
        for (int k = 0; k < code.length(); k++) {
            char c = code.charAt(k);
            if (inCmt) continue;
            if (inStr) { if (c == '\\') k++; else if (c == '"') inStr = false; continue; }
            if (inChr) { if (c == '\\') k++; else if (c == ''') inChr = false; continue; }
            if (c == '/' && k + 1 < code.length() && code.charAt(k + 1) == '/') { inCmt = true; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == ''') { inChr = true; continue; }
            if (c == '{') {
                scopes.add(new LinkedHashMap<String, String>());
            } else if (c == '}') {
                if (scopes.size() > 1) scopes.remove(scopes.size() - 1);
            }
        }
    }

    /** 从一行代码中收集简单声明（普通声明行 + for/if/while 头部声明） */
    private static void collectDecls(String code, List<Map<String, String>> scopes) {
        String line = code.trim();
        if (line.isEmpty()) return;
        Matcher m = SIMPLE_DECL.matcher(line);
        if (m.find() && m.start() == 0) {
            collectFromDeclLine(line, scopes.get(scopes.size() - 1));
            return;
        }
        Matcher hm = HEADER_DECL.matcher(line);
        if (hm.find() && hm.start() == 0) {
            collectFromDeclLine(line.substring(line.indexOf('(') + 1),
                    scopes.get(scopes.size() - 1));
        }
    }

    /** 解析「类型 变量[=初值][, 变量...];」并把变量加入作用域 */
    private static void collectFromDeclLine(String line, Map<String, String> scope) {
        String s = line.trim();
        // 去掉 for(...) 的收尾右括号
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        if (open >= 0 && close > open && close == s.length() - 1) {
            s = s.substring(open + 1, close);
        }
        int semi = s.lastIndexOf(';');
        if (semi >= 0) s = s.substring(0, semi);

        Matcher first = SIMPLE_DECL.matcher(s);
        if (!first.find() || first.start() != 0) return;
        String typePrefix = first.group(0);
        boolean isPtr = first.group(1) != null && !first.group(1).isEmpty();
        String firstName = first.group(2);
        boolean isArray = first.group(3) != null;

        // 把第一个变量名拼回 rest 前面，保证「int a = 1, b = 2;」中 a 也能被收集
        String rest = firstName
                + (first.group(3) == null ? "" : first.group(3))
                + s.substring(first.end());
        String[] parts = splitTopLevel(rest);
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            // 每个 part 形如：名称 [数组] [= 初始化]
            String name = p;
            boolean arr = isArray;
            String init = null;
            int eq = indexOfTopLevel(p, '=');
            if (eq >= 0) {
                name = p.substring(0, eq);
                init = p.substring(eq + 1).trim();
            }
            // 数组声明可能把 [] 写在变量名后：int a[10]
            int br = name.indexOf('[');
            if (br >= 0) {
                arr = true;
                name = name.substring(0, br).trim();
            }
            if (!isValidName(name)) continue;

            String fmt = fmtFor(typePrefix, isPtr, arr, init);
            if (fmt != null) {
                scope.put(name, fmt);
            }
        }
    }

    /** 按变量名选取格式符：标量 %d/%g，指针 %p（char* 用 %s），char 数组有字符串初值用 %s */
    private static String fmtFor(String type, boolean isPtr, boolean isArray, String init) {
        boolean isFloating = type.contains("float") || type.contains("double");
        boolean isChar = type.contains("char");
        if (isPtr) {
            return isChar ? "%s" : "%p";
        }
        if (isArray) {
            if (isChar && init != null && init.startsWith("\"")) return "%s";
            return "%p";
        }
        if (isFloating) return "%g";
        return "%d";
    }

    private static boolean isValidName(String name) {
        if (name == null || name.isEmpty()) return false;
        char c = name.charAt(0);
        if (!(c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) return false;
        for (int i = 1; i < name.length(); i++) {
            char x = name.charAt(i);
            if (!(x == '_' || (x >= 'a' && x <= 'z') || (x >= 'A' && x <= 'Z')
                    || (x >= '0' && x <= '9'))) {
                return false;
            }
        }
        return true;
    }

    /** 按逗号切分变量列表（忽略数组初始化 {} 内的逗号） */
    private static String[] splitTopLevel(String s) {
        List<String> parts = new ArrayList<String>();
        int depthBrace = 0;
        StringBuilder cur = new StringBuilder();
        boolean inStr = false, inChr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) { cur.append(c); if (c == '\\') i++; else if (c == '"') inStr = false; continue; }
            if (inChr) { cur.append(c); if (c == '\\') i++; else if (c == ''') inChr = false; continue; }
            if (c == '"') { inStr = true; cur.append(c); continue; }
            if (c == ''') { inChr = true; cur.append(c); continue; }
            if (c == '{') { depthBrace++; cur.append(c); continue; }
            if (c == '}') { depthBrace--; cur.append(c); continue; }
            if (c == ',' && depthBrace == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        parts.add(cur.toString());
        return parts.toArray(new String[0]);
    }

    /** 在顶层（忽略字符串/字符/括号内）查找某字符 */
    private static int indexOfTopLevel(String s, char target) {
        boolean inStr = false, inChr = false;
        int paren = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) { if (c == '\\') i++; else if (c == '"') inStr = false; continue; }
            if (inChr) { if (c == '\\') i++; else if (c == ''') inChr = false; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == ''') { inChr = true; continue; }
            if (c == '(') { paren++; continue; }
            if (c == ')') { paren--; continue; }
            if (c == target && paren == 0) return i;
        }
        return -1;
    }

    /** 统计一行内净增的花括号数，忽略字符串/字符字面量/行内注释 */
    private static int netBraces(String s) {
        int net = 0;
        boolean inStr = false, inChr = false, inCmt = false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (inCmt) continue;
            if (inStr) { if (c == '\\') k++; else if (c == '"') inStr = false; continue; }
            if (inChr) { if (c == '\\') k++; else if (c == ''') inChr = false; continue; }
            if (c == '/' && k + 1 < s.length() && s.charAt(k + 1) == '/') { inCmt = true; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == ''') { inChr = true; continue; }
            if (c == '{') net++;
            else if (c == '}') net--;
        }
        return net;
    }

    /** 去掉行内 // 注释（不处理块注释，插桩已规避块注释行） */
    private static String stripLineComment(String s) {
        int idx = -1;
        boolean inStr = false, inChr = false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (inStr) { if (c == '\\') k++; else if (c == '"') inStr = false; continue; }
            if (inChr) { if (c == '\\') k++; else if (c == ''') inChr = false; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == ''') { inChr = true; continue; }
            if (c == '/' && k + 1 < s.length() && s.charAt(k + 1) == '/') { idx = k; break; }
        }
        return idx < 0 ? s : s.substring(0, idx);
    }

    private CDebugInstrumenter() {
    }
}
