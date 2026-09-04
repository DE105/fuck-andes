# 终端原生组件

Eta 将终端辅助程序作为独立 ELF 可执行文件打包在 `jniLibs` 中。文件使用 `.so` 后缀以便由 Android 安装器提取到只读的 `nativeLibraryDir`，不会通过 JNI 加载，也不从可写目录执行 Android 原生辅助程序。

| 程序 | ABI | 用途 |
| --- | --- | --- |
| `libeta_pty.so` | arm64-v8a、x86_64、armeabi-v7a、x86 | 为普通 Android Shell 和 Linux 会话创建真实伪终端 |
| `libproot_exec.so` | arm64-v8a、x86_64 | 在 App UID 下运行 Linux 用户空间 |
| `libproot_loader.so` | arm64-v8a、x86_64 | 与 PRoot 同一次编译产生的同 ABI 加载器 |

PRoot 只支持本机对应的 64 位 Linux rootfs，不执行异构指令集，也不提供设备 Root 权限。PRoot 静态链接 talloc 和 libandroid-shmem，运行时只依赖 Android 系统库。构建的 ELF 使用 16 KiB 页对齐。

## 运行接口

PTY 参数为 `rows cols -- executable args...`，标准输入与输出传输原始终端字节。Ctrl-C 经 PTY 发送到当前前台进程组；输入管道关闭时发送终端 EOF；关闭窗口、父进程退出或输出管道断开时清理终端进程组。退出码保留子进程状态。交互窗口尺寸在启动时传入，调用方通过真实终端发送 SIGWINCH 时可同步其尺寸。

PRoot 调用方必须设置 `PROOT_LOADER` 为当前 `nativeLibraryDir/libproot_loader.so`，设置 `PROOT_TMP_DIR` 为 Eta 可写的私有临时目录。缺失加载器不会转而寻找其他应用的安装路径。共享内存补丁也使用同一临时目录，不依赖全局 `/tmp`。

## 重建

构建需要与 `gradle/libs.versions.toml` 的 `ndk` 条目一致的 Android NDK、Python 3、GNU make、patch 与 tar，在 macOS 或 Linux 上运行：

```sh
ANDROID_NDK_HOME=/path/to/android-ndk-r29 scripts/build-terminal-native.sh
```

脚本先核对 NDK 的 `source.properties` 与版本目录，再使用 APK 同步附带的固定源码压缩包，并校验 SHA-256；缺少源码时才从上游固定版本地址下载。第三方源码版本、URL、摘要和编译参数以脚本为准。`ETA_NATIVE_SOURCES` 与 `ETA_NATIVE_BUILD` 可以分别指定源码缓存和临时构建目录。编译日志保存在构建目录中，不包含模型配置或用户文件。

脚本生成全部 ELF，并把实际脚本、版本目录、PTY 源码及补丁自动打入 `app/src/main/assets/native-sources/eta-native-build.tgz`，该包不应手工修改。APK 的 `assets/native-sources` 还包含 PRoot、talloc 和 libandroid-shmem 的原始源码；源码资源使用 `.tgz` 后缀，避免 Android 资源打包自动解压 `.gz` 文件后改变名称与校验值。`assets/licenses` 包含各自许可证。解压构建包恢复仓库相对路径，再把原始源码所在目录传给 `ETA_NATIVE_SOURCES`，即可重建随包组件。

源码包与重新生成的二进制需要一起更新。相关第三方程序和补丁适用各自开源许可证，Eta 主项目的非商业许可证不限制这些独立第三方程序授予的权利；详见[第三方声明](THIRD_PARTY_NOTICES.md)。
