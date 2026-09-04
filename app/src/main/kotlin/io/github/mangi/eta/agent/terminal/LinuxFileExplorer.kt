package io.github.mangi.eta.agent.terminal

import java.io.File

/**
 * 面向用户的 Linux rootfs 只读文件浏览后端。
 *
 * Root 环境沿用宿主 Root Shell；免 Root 环境通过 PRoot 访问 guest 路径，
 * 保留符号链接、工作区和共享目录的 Linux 语义。路径只做词法归一化。
 */
internal object LinuxFileExplorer {
    const val DEFAULT_MAX_READ_BYTES = 256L * 1024L

    // 脚本内约定退出码；其余非零退出码一律视为命令失败。
    private const val EXIT_NOT_DIRECTORY = 41
    private const val EXIT_UNREADABLE = 42

    data class Entry(
        val name: String,
        val isDir: Boolean,
        val sizeBytes: Long,
        val mtimeEpochSeconds: Long,
    )

    sealed interface ListResult {
        data class Success(val entries: List<Entry>) : ListResult
        data object NotInstalled : ListResult
        data object NotDirectory : ListResult
        data object Unreadable : ListResult
        data object CommandFailed : ListResult
    }

    sealed interface ReadResult {
        data class Text(val content: String, val truncated: Boolean) : ReadResult
        data object Binary : ReadResult
        data object NotInstalled : ReadResult
        data object NotFile : ReadResult
        data object Unreadable : ReadResult
        data object CommandFailed : ReadResult
    }

    /**
     * 把 chroot 内绝对路径映射为宿主路径。只接受 `/` 开头的路径（空白归一为 `/`）；
     * `..` 弹栈、弹到根之上或相对路径返回 null。
     */
    fun resolveHostPath(rootfsDir: File, linuxPath: String): String? {
        val trimmed = linuxPath.trim()
        if (trimmed.isEmpty()) return rootfsDir.path
        if (!trimmed.startsWith("/") || '\n' in trimmed || '\r' in trimmed) return null
        val segments = mutableListOf<String>()
        trimmed.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        val normalized = "/" + segments.joinToString("/")
        // 子段已剔除 ..，File(parent, child) 的拼接不可能逃逸出 rootfsDir。
        return File(rootfsDir, normalized).path
    }

    /** 同步阻塞；协程切换由调用侧负责。rootfs 未就绪时不执行任何 Shell。 */
    fun list(
        supervisor: ShellProcessSupervisor,
        rootfsDir: File,
        linuxPath: String,
    ): ListResult {
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)) return ListResult.NotInstalled
        val hostPath = resolveHostPath(rootfsDir, linuxPath) ?: return ListResult.NotDirectory
        val rootless = LinuxEnvironmentPaths.backendOf(rootfsDir.absolutePath) == LinuxExecutionBackend.PROOT
        val quoted = shellQuote(if (rootless) "/" + File(rootfsDir.path).toPath().relativize(File(hostPath).toPath()).toString() else hostPath)
        // 空目录时通配符原样传给 stat，报错进 stderr 被吞掉，stdout 为空即空列表。
        val script = """
            if [ ! -d $quoted ]; then exit $EXIT_NOT_DIRECTORY; fi
            if [ ! -r $quoted ] || [ ! -x $quoted ]; then exit $EXIT_UNREADABLE; fi
            cd $quoted || exit $EXIT_UNREADABLE
            stat -c '%F|%s|%Y|%n' -- .[!.]* * 2>/dev/null
            exit 0
        """.trimIndent()
        val result = runOneShotShell(
            processSupervisor = supervisor,
            identity = if (LinuxEnvironmentPaths.backendOf(rootfsDir.absolutePath) == LinuxExecutionBackend.PROOT) "user" else "root",
            command = script,
            timeoutSeconds = 15,
            environment = if (rootless) TerminalEnvironment.ALPINE else TerminalEnvironment.ANDROID,
            linuxRootfsPath = rootfsDir.absolutePath,
        )
        return when (result.exitCode) {
            0 -> ListResult.Success(parseStatOutput(result.output.decodeToString()))
            EXIT_NOT_DIRECTORY -> ListResult.NotDirectory
            EXIT_UNREADABLE -> ListResult.Unreadable
            else -> ListResult.CommandFailed
        }
    }

    /** 同步阻塞；读取上限 [maxBytes]，多出 1 字节用于判定截断。 */
    fun readText(
        supervisor: ShellProcessSupervisor,
        rootfsDir: File,
        linuxPath: String,
        maxBytes: Long = DEFAULT_MAX_READ_BYTES,
    ): ReadResult {
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)) return ReadResult.NotInstalled
        val hostPath = resolveHostPath(rootfsDir, linuxPath) ?: return ReadResult.NotFile
        val rootless = LinuxEnvironmentPaths.backendOf(rootfsDir.absolutePath) == LinuxExecutionBackend.PROOT
        val quoted = shellQuote(if (rootless) "/" + File(rootfsDir.path).toPath().relativize(File(hostPath).toPath()).toString() else hostPath)
        val script = """
            if [ ! -f $quoted ]; then exit $EXIT_NOT_DIRECTORY; fi
            if [ ! -r $quoted ]; then exit $EXIT_UNREADABLE; fi
            head -c ${maxBytes + 1} $quoted
            exit 0
        """.trimIndent()
        val result = runOneShotShell(
            processSupervisor = supervisor,
            identity = if (LinuxEnvironmentPaths.backendOf(rootfsDir.absolutePath) == LinuxExecutionBackend.PROOT) "user" else "root",
            command = script,
            timeoutSeconds = 15,
            environment = if (rootless) TerminalEnvironment.ALPINE else TerminalEnvironment.ANDROID,
            linuxRootfsPath = rootfsDir.absolutePath,
        )
        if (result.exitCode != 0) {
            return when (result.exitCode) {
                EXIT_NOT_DIRECTORY -> ReadResult.NotFile
                EXIT_UNREADABLE -> ReadResult.Unreadable
                else -> ReadResult.CommandFailed
            }
        }
        val truncated = result.output.size.toLong() > maxBytes
        val payload = if (truncated) result.output.copyOf(maxBytes.toInt()) else result.output
        // 含 NUL 字节按二进制处理，不把乱码塞进查看器。
        if (payload.contains(0.toByte())) return ReadResult.Binary
        return ReadResult.Text(content = payload.decodeToString(), truncated = truncated)
    }

    /**
     * 解析 `stat -c '%F|%s|%Y|%n'` 输出：按前 3 个分隔符切分，其余全部归入文件名，
     * 容忍文件名含 `|`；畸形行与通配符残留行跳过。
     */
    internal fun parseStatOutput(output: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        output.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) return@forEach
            val name = parts[3]
            if (name == "*" || name == ".[!.]*") return@forEach
            val size = parts[1].toLongOrNull() ?: return@forEach
            val mtime = parts[2].toLongOrNull() ?: return@forEach
            entries += Entry(
                name = name,
                isDir = parts[0] == "directory",
                sizeBytes = size,
                mtimeEpochSeconds = mtime,
            )
        }
        return sortEntries(entries)
    }

    /** 目录在前，各自按名称排序。 */
    internal fun sortEntries(entries: List<Entry>): List<Entry> =
        entries.sortedWith(compareBy<Entry> { !it.isDir }.thenBy { it.name })
}
