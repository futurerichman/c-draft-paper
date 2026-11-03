# C语言草稿纸 (C Draft Paper)

一款 Android 端的 C 语言编辑器与运行环境，内置 TCC 编译器，在手机上即可编写、编译、运行 C 代码。

## 功能

- **代码编辑**：等宽字体编辑区，VS Code 深色配色，行号显示，自动缩进，括号自动补全，C 语法高亮
- **文件操作**：新建 / 打开 / 保存（SAF 存储访问框架，零权限），支持 UTF-8 与 GBK/GB18030 自动识别
- **编译运行**：内置 TCC 编译器，一键运行，交互式 scanf 输入，错误标色
- **调试支持**：断点、单步执行（CDebugInstrumenter）
- **字号调节**：双指捏合或工具栏 −/+ 按钮，10–30sp 持久化
- **布局适配**：竖屏 / 横屏自动切换，工具栏和控制台可折叠
- **零权限**：不需要存储权限、不需要网络权限、不需要 root

## 最新版本

**v1.28.25** — 详见 [Releases](https://github.com/futurerichman/c-draft-paper/releases)

APK 直接下载安装即可使用，无需额外配置。

## 从源码构建

### 依赖

- Android SDK Build-Tools 36.0.0
- Android Platform android-34（android.jar）
- JDK 17
- 签名密钥（自行生成 keystore）

### 二进制运行时资源

仓库**不包含**以下二进制文件（体积大、非源码），需从 [Releases](https://github.com/futurerichman/c-draft-paper/releases) 下载后放入对应目录：

```
assets/
├── sysroot.zip          # 系统头文件与库
└── tcc/
    ├── tcc.gz           # TCC 编译器 ARM64 二进制（gzip 压缩）
    └── tcc_runtime.zip  # TCC 运行时（libtcc1.a + crt + 头文件）
```

下载后解压到源码目录的 `assets/` 下，保持目录结构。

### 构建命令

```bash
# Linux / macOS
./build.sh

# Windows PowerShell
powershell -File build.ps1 -SrcDir <源码目录> -OutApk output.apk
```

产物为单个 APK 文件。

## 技术要点

- 包名 `com.ccode.mintest`，minSdk 26 / targetSdk 34
- 零第三方库、零 WebView、纯原生 Android
- 非 Gradle 构建流水线：aapt2 + javac + d8 + zipalign + apksigner
- TCC 编译器通过动态链接器加载执行，适配 Android 10+ 的执行限制

## 版本历史

| 版本 | 说明 |
|---|---|
| v1.0-UI | 界面预览版（工具栏 + 编辑区 + 控制台） |
| v1.1-UI | 工具栏可收起；控制台可收起 |
| v1.2-UI | 修复旋转屏幕后折叠状态丢失 |
| v1.3-UI | 双指捏合缩放字号；底部常用 C 符号栏 |
| v1.4-UI | 新增保存文件（SAF） |
| v1.5-UI | 接入 TCC 编译引擎，运行/停止、交互式 scanf |
| v1.6-UI | 控制台文字可选取/复制；运行中旋转自动停止 |
| v1.7-UI | 控制台收起改为隐藏+展开窄带；工具栏字号 −/+ 按钮 |
| v1.8-UI | 修复 Permission denied（tcc 解压后补执行权限） |
| v1.8.1~1.8.7 | 多轮修复 TCC 执行兼容性（动态链接器、ELF 校验、头文件路径） |
| v1.28.x | 语法高亮优化、调试器集成、行号视图、自动缩进完善、横屏布局 |

## License

MIT
