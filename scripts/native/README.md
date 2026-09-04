# 终端原生组件补丁

`proot-bionic-headers.patch` 补齐 Android NDK 编译所需的标准库声明，按 PRoot 的 GPL-2.0-or-later 许可证提供。

`shmem-app-temp.patch` 将共享内存键的临时文件放入调用方提供的 `PROOT_TMP_DIR`，校验路径长度，并在文件系统拒绝创建时返回错误，按 libandroid-shmem 的 BSD-3-Clause 许可证提供。

补丁只应用到构建副本。固定下载源码与 SHA-256 保存在上一级构建脚本中；源码缓存与编译产物放在 `.analysis/`。构建脚本采用 GPL-3.0-or-later，不改变 Eta 应用及独立 PTY 程序的许可证。
