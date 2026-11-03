package com.ccode.mintest;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v1.8.5：编译运行引擎，调用内置 TCC 的 -run 模式。
 * 精确校验 tcc 必须是文件；-B 指定运行库（libtcc1.a/crt/内置 include）。
 * 执行策略：直接执行 → 系统动态链接器（linker64）加载执行。
 */
public class RunEngine {

    public interface Callback {
        void onOutput(String text, boolean isError);
        void onExit(int code);
        void onError(String message);
    }

    /** 调试模式下的行暂停回调：程序每暂停在一行时通知 UI */
    public interface DebugCallback {
        void onDebugLine(int line);
        /** v1.19：暂停行处的变量值串（由 C 端 DEBUG_HOOK 格式化上报，形如 "a=1, b=2.5"） */
        void onDebugVars(String vars);
    }

    /** 候选动态链接器（Android 11+ 在 /apex，旧系统在 /system/bin） */
    private static final String[] LINKERS = {
            "/apex/com.android.runtime/bin/linker64",
            "/system/bin/linker64"
    };

    private Process process;
    private OutputStream stdin;
    private volatile boolean stopped;

    /** 调试模式：与子进程通信的 socket 写入端与已连接 socket */
    private BufferedWriter dbgWriter;
    private LocalSocket dbgSock;

    /** 启动编译运行（后台线程执行），srcFile 为待编译的 .c 文件 */
    public void run(final File tccBin, final File sysroot, final File runtimeDir,
                    final File srcFile, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                runSync(tccBin, sysroot, runtimeDir, srcFile, cb);
            }
        }, "ccode-run").start();
    }

    private void runSync(File tccBin, File sysroot, File runtimeDir,
                         File srcFile, Callback cb) {
        stopped = false;
        if (!tccBin.isFile()) {
            cb.onError("tcc 路径不是文件（是目录或缺失）：" + tccBin.getAbsolutePath()
                    + "，请重新安装本版本");
            return;
        }
        File incBase = new File(sysroot, "include");
        File incArch = new File(incBase, "aarch64-linux-android");
        List<String> base = buildBaseCommand(tccBin, sysroot, runtimeDir, srcFile);

        // 尝试序列：直接执行 → 动态链接器执行（方案 2 兜底）
        List<List<String>> attempts = new ArrayList<>();
        attempts.add(base);
        for (String linker : LINKERS) {
            if (new File(linker).isFile()) {
                List<String> lcmd = new ArrayList<>();
                lcmd.add(linker);          // linker <tcc> <args...>
                lcmd.addAll(base);
                attempts.add(lcmd);
            }
        }

        IOException last = null;
        for (List<String> cmd : attempts) {
            try {
                runOnce(cmd, srcFile, incBase, incArch, cb);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        cb.onError(last == null ? "无法启动 tcc"
                : (last.getMessage() == null ? last.toString() : last.getMessage()));
    }

    /**
     * 构造 tcc 基础命令（普通运行与调试运行共用）。
     * tcc -B<runtime> -L<runtime> -I<runtimeInclude> -I<include> -I<arch> -run <src>
     */
    private List<String> buildBaseCommand(File tccBin, File sysroot,
                                         File runtimeDir, File srcFile) {
        File incBase = new File(sysroot, "include");
        File incArch = new File(incBase, "aarch64-linux-android");
        List<String> base = new ArrayList<>();
        base.add(tccBin.getAbsolutePath());
        if (new File(runtimeDir, "libtcc1.a").isFile()) {
            base.add("-L");
            base.add(runtimeDir.getAbsolutePath());
        }
        if (new File(runtimeDir, "libtcc1.a").isFile()) {
            base.add("-B");
            base.add(runtimeDir.getAbsolutePath());
        }
        File runtimeInclude = new File(runtimeDir, "include");
        if (runtimeInclude.isDirectory()) {
            base.add("-I");
            base.add(runtimeInclude.getAbsolutePath());
        }
        base.add("-I");
        base.add(incBase.getAbsolutePath());
        if (incArch.exists()) {
            base.add("-I");
            base.add(incArch.getAbsolutePath());
        }
        base.add("-run");
        base.add(srcFile.getAbsolutePath());
        return base;
    }

    /** 用给定命令启动一次并驱动到结束 */
    private void runOnce(List<String> cmd, File srcFile,
                         File incBase, File incArch, Callback cb) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(srcFile.getParentFile());
        Map<String, String> env = pb.environment();
        try {
            env.put("C_INCLUDE_PATH", incBase.getAbsolutePath()
                    + ":" + incArch.getAbsolutePath());
            env.put("LIBRARY_PATH", "/system/lib64");
        } catch (Exception ignored) {
        }
        final Process p = pb.start();
        process = p;
        stdin = p.getOutputStream();

        Thread outThread = new Thread(() -> readLoop(p.getInputStream(), cb, false));
        Thread errThread = new Thread(() -> readLoop(p.getErrorStream(), cb, true));
        outThread.start();
        errThread.start();

        try {
            int code = p.waitFor();
            outThread.join(3000);
            errThread.join(3000);
            cb.onExit(code);
        } catch (InterruptedException e) {
            cb.onError("运行被中断");
        }
    }

    /** 调试模式启动：编译并运行插桩后的源码，建立抽象 LocalSocket 通信 */
    public void runDebug(final File tccBin, final File sysroot, final File runtimeDir,
                        final File srcFile, final String sockName,
                        final LocalServerSocket server, final Callback cb,
                        final DebugCallback dcb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                runDebugSync(tccBin, sysroot, runtimeDir, srcFile, sockName, server, cb, dcb);
            }
        }, "ccode-debug").start();
    }

    private void runDebugSync(File tccBin, File sysroot, File runtimeDir, File srcFile,
                             String sockName, LocalServerSocket server, Callback cb,
                             DebugCallback dcb) {
        stopped = false;
        if (!tccBin.isFile()) {
            cb.onError("tcc 路径不是文件（是目录或缺失）：" + tccBin.getAbsolutePath()
                    + "，请重新安装本版本");
            return;
        }
        List<String> base = buildBaseCommand(tccBin, sysroot, runtimeDir, srcFile);
        File incBase = new File(sysroot, "include");
        File incArch = new File(incBase, "aarch64-linux-android");
        List<List<String>> attempts = new ArrayList<>();
        attempts.add(base);
        for (String linker : LINKERS) {
            if (new File(linker).isFile()) {
                List<String> lcmd = new ArrayList<>();
                lcmd.add(linker);
                lcmd.addAll(base);
                attempts.add(lcmd);
            }
        }
        IOException last = null;
        for (List<String> cmd : attempts) {
            try {
                runDebugOnce(cmd, srcFile, incBase, incArch, cb, dcb, server, sockName);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        cb.onError(last == null ? "无法启动 tcc"
                : (last.getMessage() == null ? last.toString() : last.getMessage()));
    }

    private void runDebugOnce(List<String> cmd, File srcFile, File incBase, File incArch,
                             Callback cb, DebugCallback dcb, LocalServerSocket server,
                             String sockName) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(srcFile.getParentFile());
        Map<String, String> env = pb.environment();
        try {
            env.put("C_INCLUDE_PATH", incBase.getAbsolutePath()
                    + ":" + incArch.getAbsolutePath());
            env.put("LIBRARY_PATH", "/system/lib64");
            env.put("CCODE_DBG_SOCK", sockName);
        } catch (Exception ignored) {
        }

        // 先起监听线程，保证子进程首次 DEBUG_HOOK 连接时服务端已 ready
        Thread acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LocalSocket s = server.accept();
                    dbgSock = s;
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(s.getInputStream()));
                    dbgWriter = new BufferedWriter(
                            new OutputStreamWriter(s.getOutputStream()));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("LINE ")) {
                            try {
                                final int ln = Integer.parseInt(line.substring(5).trim());
                                dcb.onDebugLine(ln);
                            } catch (NumberFormatException ignore) {
                            }
                        } else if (line.startsWith("VARS ")) {
                            // v1.19：变量值串，去掉前缀后交给 UI 显示
                            final String vars = line.substring(5);
                            dcb.onDebugVars(vars);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }, "ccode-dbg-accept");
        acceptThread.start();

        final Process p = pb.start();
        process = p;
        stdin = p.getOutputStream();

        Thread outThread = new Thread(new Runnable() {
            @Override
            public void run() { readLoop(p.getInputStream(), cb, false); }
        });
        Thread errThread = new Thread(new Runnable() {
            @Override
            public void run() { readLoop(p.getErrorStream(), cb, true); }
        });
        outThread.start();
        errThread.start();

        try {
            int code = p.waitFor();
            outThread.join(3000);
            errThread.join(3000);
            cb.onExit(code);
        } catch (InterruptedException e) {
            cb.onError("运行被中断");
        } finally {
            try { if (dbgSock != null) dbgSock.close(); } catch (Exception ignore) {
            }
            try { server.close(); } catch (Exception ignore) {
            }
        }
    }

    /** 向调试中的子进程发送一条指令（STEP / CONT / BREAK <line>） */
    public void sendDebugCommand(String cmd) {
        BufferedWriter w = dbgWriter;
        if (w == null) return;
        try {
            synchronized (w) {
                w.write(cmd);
                w.flush();
            }
        } catch (IOException ignored) {
        }
    }

    /** 向运行中的程序标准输入写入一行（末尾自动补换行，支持 scanf） */
    public void sendInput(String line) {
        OutputStream os = stdin;
        if (os == null) return;
        try {
            synchronized (os) {
                os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException ignored) {
        }
    }

    /** 请求停止运行（普通运行与调试通用） */
    public void stop() {
        stopped = true;
        Process p = process;
        if (p != null) {
            p.destroy();
            try {
                p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
            if (p.isAlive()) p.destroyForcibly();
        }
        try { if (dbgSock != null) dbgSock.close(); } catch (Exception ignored) {
        }
    }

    private void readLoop(InputStream in, Callback cb, boolean isError) {
        byte[] buf = new byte[4096];
        int n;
        try {
            while (!stopped && (n = in.read(buf)) > 0) {
                cb.onOutput(new String(buf, 0, n, StandardCharsets.UTF_8), isError);
            }
        } catch (IOException ignored) {
        }
    }
}
