package com.ccode.mintest;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

/**
 * v1.14-UI：关于作者页。
 *
 * <p>纯静态展示界面，不申请任何权限、不访问文件系统、不调用 SAF，
 * 只加载 {@code res/layout/activity_about.xml} 显示文字内容。
 * 返回交给系统返回键（Activity 默认行为），无需额外按钮。
 */
public class AboutActivity extends Activity {

    /** 读不到包信息时的兜底版本号（与 XML 里的占位值一致） */
    private static final String FALLBACK_VERSION = "v1.14-UI";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        // 版本号直接取自 APK 的 versionName，以后发版不用再手动改布局
        TextView tvVersion = findViewById(R.id.tvAboutVersion);
        tvVersion.setText(readVersionName());
    }

    /** 读取当前应用的 versionName，失败时回退到固定字符串 */
    private String readVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null && pi.versionName.length() > 0) {
                return "v" + pi.versionName;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // 理论不会发生（查的是自己），忽略即可
        }
        return FALLBACK_VERSION;
    }
}
