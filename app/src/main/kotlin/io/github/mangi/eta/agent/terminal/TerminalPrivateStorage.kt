package io.github.mangi.eta.agent.terminal

import java.io.File

/** 普通模式独立于旧版由 Root 创建的 terminal 父目录，不修改 chroot 的路径和属主。 */
internal object TerminalPrivateStorage {
    fun workspace(filesDir: File): File = directory(filesDir, "workspace")

    fun prootEnvironment(filesDir: File, distribution: LinuxDistribution): File =
        directory(filesDir, "proot/${distribution.wireName}")

    private fun directory(filesDir: File, relative: String): File {
        val independent = File(filesDir, "terminal-user/$relative")
        val legacy = File(filesDir, "terminal/$relative")
        // 已有普通环境继续使用原位置；路径选择不读取 Root 授权，也不搬动已有数据。
        return if (!independent.exists() && legacy.exists()) legacy else independent
    }

    fun isProotPath(path: String?): Boolean =
        path?.let { "/terminal-user/proot/" in it || "/terminal/proot/" in it } == true
}
