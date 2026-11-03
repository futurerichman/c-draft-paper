package com.ccode.mintest;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * v1.8.5：TCC 相关资源的部署与路径解析。
 * 资源：tcc 二进制（tcc.gz）、系统头文件（sysroot.zip）、tcc 运行库
 * （tcc_runtime.zip：libtcc1.a + crt/*.o + include/*.h，用 -B 指定）。
 * 优先尝试部署到 /data/local/tmp/ccode（执行限制较松），失败回退应用私有目录；
 * 运行前按"文件存在且可执行"精确解析 tcc 二进制路径（防止目录被当文件执行）。
 */
public class EnsureAssets {

    public interface Done {
        void run();
    }

    /** /data/local/tmp 部署目录 */
    private static final String LOCAL_TMP_BASE = "/data/local/tmp/ccode";

    public static void ensure(final Context ctx, final Done done) {
        final File dir = ctx.getFilesDir();
        final File tccBin = new File(dir, "tcc/tcc");
        final File sysroot = new File(dir, "sysroot/include");
        final File runtimeLib = new File(dir, "tcc_runtime/libtcc1.a");
        if (isElf(tccBin) && sysroot.isDirectory() && runtimeLib.isFile()) {
            setExecutable(tccBin);
            if (done != null) done.run();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = true;
                try {
                    // 用 GZIPInputStream 真正解压 tcc.gz（之前直接 copy 导致文件仍是 gzip 内容）
                    if (!isElf(tccBin)) {
                        File tf = new File(dir, "tcc");
                        tf.mkdirs();
                        InputStream gz = new GZIPInputStream(
                                ctx.getAssets().open("tcc/tcc.gz"));
                        OutputStream os = new FileOutputStream(tccBin);
                        copy(gz, os);
                        os.close();
                        gz.close();
                    }
                    if (!sysroot.isDirectory()) {
                        InputStream zf = ctx.getAssets().open("sysroot.zip");
                        unzip(zf, new File(dir, "sysroot"));
                        zf.close();
                    }
                    if (!runtimeLib.isFile()) {
                        InputStream zf = ctx.getAssets().open("tcc/tcc_runtime.zip");
                        unzip(zf, new File(dir, "tcc_runtime"));
                        zf.close();
                    }
                    setExecutable(tccBin);
                } catch (Exception e) {
                    ok = false;
                }
                // 尽力再部署一份到 /data/local/tmp（无权限则静默失败）
                tryDeployLocalTmp(ctx);
                if (ok && done != null) done.run();
            }
        }, "ccode-assets").start();
    }

    /** 部署到 /data/local/tmp/ccode（能写才做） */
    private static void tryDeployLocalTmp(Context ctx) {
        try {
            File dir = new File(LOCAL_TMP_BASE);
            if (!dir.exists() && !dir.mkdirs()) return; // 无写权限直接放弃
            File tccBin = new File(dir, "tcc");
            if (!isElf(tccBin)) {
                InputStream gz = new GZIPInputStream(
                        ctx.getAssets().open("tcc/tcc.gz"));
                OutputStream os = new FileOutputStream(tccBin);
                copy(gz, os);
                os.close();
                gz.close();
            }
            setExecutable(tccBin);
            if (!new File(dir, "sysroot/include").isDirectory()) {
                InputStream zf = ctx.getAssets().open("sysroot.zip");
                unzip(zf, new File(dir, "sysroot"));
                zf.close();
            }
            if (!new File(dir, "tcc_runtime/libtcc1.a").isFile()) {
                InputStream zf = ctx.getAssets().open("tcc/tcc_runtime.zip");
                unzip(zf, new File(dir, "tcc_runtime"));
                zf.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** 校验文件是否为合法 ELF（magic 0x7f 'E' 'L' 'F'），防止把 gzip/目录当可执行文件 */
    private static boolean isElf(File f) {
        try {
            if (!f.isFile() || f.length() < 4) return false;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] magic = new byte[4];
            int n = in.read(magic);
            in.close();
            return n == 4 && magic[0] == 0x7f && magic[1] == 'E'
                    && magic[2] == 'L' && magic[3] == 'F';
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 精确解析 tcc 二进制文件路径（必须是文件，防止目录被当文件执行） */
    public static File resolveTccBin(Context ctx) {
        File lt = new File(LOCAL_TMP_BASE, "tcc");
        if (lt.isFile() && lt.canExecute()) return lt;
        // 私有目录历史布局：files/tcc/tcc（二进制在子目录）
        File f1 = new File(ctx.getFilesDir(), "tcc/tcc");
        if (f1.isFile()) return f1;
        File f2 = new File(ctx.getFilesDir(), "tcc");
        if (f2.isFile()) return f2;
        return f1; // 均不存在：交给调用方报明确错误
    }

    /** tcc 运行库目录（libtcc1.a / crt / include，-B 指向它） */
    public static File resolveRuntimeDir(Context ctx) {
        File lt = new File(LOCAL_TMP_BASE, "tcc_runtime/libtcc1.a");
        if (lt.isFile()) return new File(LOCAL_TMP_BASE, "tcc_runtime");
        return new File(ctx.getFilesDir(), "tcc_runtime");
    }

    /** sysroot 目录（与 tcc 所在位置保持一致） */
    public static File resolveSysroot(Context ctx) {
        File lt = new File(LOCAL_TMP_BASE, "sysroot/include");
        if (lt.isDirectory()) return new File(LOCAL_TMP_BASE, "sysroot");
        return new File(ctx.getFilesDir(), "sysroot");
    }

    /** 给文件加执行权限：setExecutable + chmod 兜底 */
    private static void setExecutable(File f) {
        try {
            if (f.setExecutable(true, false)) return;
        } catch (Exception ignored) {
        }
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"chmod", "755", f.getAbsolutePath()});
            p.waitFor();
        } catch (Exception ignored) {
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** 解压 zip 到目标目录，条目名规范化，跳过穿越目录的条目 */
    private static void unzip(InputStream in, File dest) throws Exception {
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            if (name.contains("..") || name.startsWith("/")) continue;
            File f = new File(dest, name);
            if (e.isDirectory()) {
                f.mkdirs();
                continue;
            }
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            OutputStream os = new FileOutputStream(f);
            int n;
            while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
        }
        zis.close();
    }
}
