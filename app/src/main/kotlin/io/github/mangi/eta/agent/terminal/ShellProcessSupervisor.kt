package io.github.mangi.eta.agent.terminal

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 负责 Shell 进程的启动接纳、所有权识别、进程树终止与回收。 */
internal class ShellProcessSupervisor(
    private val allowTreeFallback: Boolean = !isAndroidRuntime(),
    private val setsidCommand: String = "setsid",
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
    private val userPtyExecutable: () -> File? = { TerminalRuntime.nativeExecutable("libeta_pty.so") },
) {
    private companion object {
        const val PROCESS_REAP_TIMEOUT_MS = 1_000L
        const val PROCESS_OWNERSHIP_WAIT_MS = 500L
        const val PROCESS_SIGNAL_TIMEOUT_MS = 1_000L
        const val DEFAULT_PTY_COLS = 120
        const val DEFAULT_PTY_ROWS = 40
        const val PTY_TERM_TYPE = "xterm-256color"

        fun isAndroidRuntime(): Boolean =
            System.getProperty("java.vm.name").orEmpty().equals("Dalvik", ignoreCase = true) ||
                System.getProperty("java.runtime.name").orEmpty().contains("Android", ignoreCase = true)
    }

    private val activeProcesses = mutableSetOf<Process>()
    private val processMetadata = mutableMapOf<Process, ProcessMetadata>()

    @Volatile
    var isClosing: Boolean = false
        private set

    /**
     * ProcessBuilder.start() 必须在锁外执行；启动完成后再以短临界区完成接纳或拒绝。
     * 每个 Shell 优先进入独立 session，并把真实 Shell PID 写入仅本进程使用的临时文件。
     */
    fun startShellProcess(
        identity: String,
        command: String?,
        mergeStderr: Boolean,
        environment: TerminalEnvironment = TerminalEnvironment.ANDROID,
        linuxRootfsPath: String? = null,
        linuxSharedMounts: List<SharedFolderMount> = emptyList(),
        pty: Boolean = false,
        ptyCols: Int = DEFAULT_PTY_COLS,
        ptyRows: Int = DEFAULT_PTY_ROWS,
    ): Process? {
        if (isClosing) return null
        require(identity == "root" || identity == "user") { "identity 仅支持 root/user" }
        require(!environment.isLinux || identity == "root" || LinuxEnvironmentPaths.backendOf(linuxRootfsPath) == LinuxExecutionBackend.PROOT) {
            "Linux 工具环境仅支持 root identity"
        }
        if (environment.isLinux && identity == "root" && LinuxEnvironmentPaths.backendOf(linuxRootfsPath) == LinuxExecutionBackend.PROOT) return null
        if (identity == "root" && !rootAvailable()) return null
        val ownershipFile = runCatching {
            File.createTempFile("eta-terminal-", ".owner")
        }.getOrNull() ?: return null
        val ownershipToken = UUID.randomUUID().toString().replace("-", "")
        val launcher = try {
            buildTrackedShellLauncher(
                ownershipFile = ownershipFile,
                ownershipToken = ownershipToken,
                command = command,
                identity = identity,
                environment = environment,
                linuxRootfsPath = linuxRootfsPath,
                linuxSharedMounts = linuxSharedMounts,
                pty = pty,
                ptyCols = ptyCols,
                ptyRows = ptyRows,
            )
        } catch (_: IllegalArgumentException) {
            ownershipFile.delete()
            return null
        } catch (_: java.io.IOException) {
            ownershipFile.delete()
            return null
        }
        val process = runCatching {
            val builder = if (identity == "root") {
                ProcessBuilder("su", "-c", launcher)
            } else {
                ProcessBuilder("sh", "-c", launcher)
            }
            if (identity == "user" && environment == TerminalEnvironment.ANDROID) {
                builder.environment()["HOME"] = TerminalRuntime.userWorkspacePath
            }
            builder.redirectErrorStream(mergeStderr).start()
        }.getOrElse {
            ownershipFile.delete()
            return null
        }
        val metadata = ProcessMetadata(identity, ownershipFile, ownershipToken)
        val accepted = synchronized(activeProcesses) {
            if (isClosing) {
                false
            } else {
                activeProcesses += process
                processMetadata[process] = metadata
                true
            }
        }
        if (!accepted) {
            terminateAndReap(process, metadata)
            metadata.ownershipFile.delete()
            return null
        }
        val ownership = resolveProcessOwnership(metadata)
        val stillAccepted = synchronized(activeProcesses) {
            processMetadata[process] === metadata && !isClosing
        }
        if (ownership == null || !stillAccepted) {
            terminateAndReap(process, metadata)
            unregisterProcess(process)
            return null
        }
        return process
    }

    fun transferActiveProcess(process: Process, transfer: () -> Unit): Boolean =
        synchronized(activeProcesses) {
            if (isClosing) return false
            transfer()
            activeProcesses -= process
            true
        }

    fun beginClosing() {
        synchronized(activeProcesses) {
            isClosing = true
        }
    }

    fun takeRemainingProcesses(): List<Process> = synchronized(activeProcesses) {
        activeProcesses.toList().also { activeProcesses.clear() }
    }

    fun terminateProcessTree(process: Process) {
        terminateProcessTree(process, metadataOverride = null)
    }

    fun terminateAndReap(process: Process) {
        terminateAndReap(process, metadataOverride = null)
    }

    fun reapProcess(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.waitFor(PROCESS_REAP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
    }

    fun unregisterProcess(process: Process) {
        val metadata = synchronized(activeProcesses) {
            activeProcesses -= process
            processMetadata.remove(process)
        }
        metadata?.ownershipFile?.delete()
    }

    /** leader 已退出后只废止所有权；禁止再向可能复用的 PID/PGID 发信号。 */
    fun retireExitedProcess(process: Process) {
        unregisterProcess(process)
    }

    internal fun buildTrackedShellLauncher(
        ownershipFile: File,
        ownershipToken: String,
        command: String?,
        identity: String,
        environment: TerminalEnvironment,
        linuxRootfsPath: String?,
        linuxSharedMounts: List<SharedFolderMount> = emptyList(),
        pty: Boolean = false,
        ptyCols: Int = DEFAULT_PTY_COLS,
        ptyRows: Int = DEFAULT_PTY_ROWS,
    ): String {
        val managedCommand = command?.let { value ->
            "$value\neta_status=${'$'}?\nwait\nexit ${'$'}eta_status"
        }
        val payload = when (environment) {
            TerminalEnvironment.ANDROID -> buildAndroidPayload(
                identity = identity,
                command = managedCommand,
            )
            else -> buildLinuxPayload(
                rootfsPath = requireNotNull(linuxRootfsPath) {
                    "Linux 工具环境 rootfs 未配置"
                },
                command = managedCommand,
                sharedMounts = linuxSharedMounts,
                termType = if (pty) PTY_TERM_TYPE else "dumb",
            )
        }

        val path = shellQuote(ownershipFile.absolutePath)
        val exportOwner = "export $ETA_PROCESS_OWNER_ENV=${shellQuote(ownershipToken)}"
        val cleanupGroup =
                "eta_cleanup_proc_group() { " +
                "for stat_file in /proc/[0-9]*/stat; do " +
                "[ -r \"${'$'}stat_file\" ] || continue; " +
                "IFS= read -r stat < \"${'$'}stat_file\" || continue; " +
                "pid=${'$'}{stat%% *}; rest=${'$'}{stat##*) }; set -- ${'$'}rest; " +
                "[ \"${'$'}3\" = \"${'$'}${'$'}\" ] || continue; " +
                "[ \"${'$'}pid\" = \"${'$'}${'$'}\" ] || kill -9 \"${'$'}pid\" 2>/dev/null; " +
                "done; }; " +
                "eta_cleanup_ps_group() { " +
                "for pid in ${'$'}(ps -axo pid=,pgid= | " +
                "awk -v group=\"${'$'}${'$'}\" '${'$'}2 == group && ${'$'}1 != group { print ${'$'}1 }'); do " +
                "kill -9 \"${'$'}pid\" 2>/dev/null; done; }; " +
                "if [ -d /proc/${'$'}${'$'} ]; then " +
                "eta_cleanup_proc_group; eta_cleanup_proc_group; " +
                "else eta_cleanup_ps_group; eta_cleanup_ps_group; fi"
        val groupScript =
            "printf '%s group\\n' \"${'$'}${'$'}\" > $path; " +
                "$payload; eta_status=${'$'}?; $cleanupGroup; exit ${'$'}eta_status"
        val treeScript =
            "printf '%s tree\\n' \"${'$'}${'$'}\" > $path; $payload"
        val fallback = if (allowTreeFallback) {
            treeScript
        } else {
            "printf '%s unavailable\\n' \"${'$'}${'$'}\" > $path; exit 126"
        }
        val safeSetsid = shellQuote(setsidCommand)
        if (pty) {
            if (identity == "user") {
                userPtyExecutable()?.let { executable ->
                    val script = "printf '%s group\\n' \"${'$'}${'$'}\" > $path; export TERM=$PTY_TERM_TYPE; $payload"
                    return "$exportOwner; exec ${shellQuote(executable.absolutePath)} $ptyRows $ptyCols -- sh -c ${shellQuote(script)}"
                }
            }
            return buildPtyLauncher(
                exportOwner = exportOwner,
                path = path,
                groupScript = groupScript,
                safeSetsid = safeSetsid,
                cols = ptyCols,
                rows = ptyRows,
            )
        }
        return "$exportOwner; if command -v $safeSetsid >/dev/null 2>&1; then " +
            "exec $safeSetsid -w sh -c ${shellQuote(groupScript)}; else $fallback; fi"
    }

    /**
     * PTY 控制台启动器：经 BusyBox script 为负载分配伪终端（stty 设定初始尺寸、TERM 宣告全彩）。
     * script 缺失时写入 unavailable，由调用方拒绝接纳，控制台入口依赖 [ptySupported] 提前探测。
     */
    private fun buildPtyLauncher(
        exportOwner: String,
        path: String,
        groupScript: String,
        safeSetsid: String,
        cols: Int,
        rows: Int,
    ): String {
        val discovery = AndroidBusyBox.discoveryScript()
        val ptyScript = "stty rows $rows cols $cols 2>/dev/null; export TERM=$PTY_TERM_TYPE; $groupScript"
        val unavailable = "printf '%s unavailable\\n' \"\$\$\" > $path; exit 126"
        val run = "\"\$eta_busybox\" script -qfc ${shellQuote(ptyScript)} /dev/null"
        return "$exportOwner; $discovery; [ -n \"\$eta_busybox\" ] || { $unavailable; }; " +
            "if command -v $safeSetsid >/dev/null 2>&1; then " +
            "exec $safeSetsid -w $run; else $run; fi"
    }

    /** Root 会话优先进入 Magisk/KernelSU/APatch BusyBox standalone ash，补齐 Android PATH 外的 applet。 */
    internal fun buildAndroidPayload(identity: String, command: String?): String {
        val shellArgument = command?.let { "-c ${shellQuote(it)}" }.orEmpty()
        if (identity != "root") {
            return "sh $shellArgument".trimEnd()
        }
        val discovery = AndroidBusyBox.discoveryScript()
        return "$discovery; " +
            "if [ -n \"${'$'}eta_busybox\" ]; then " +
            "export ETA_BUSYBOX=\"${'$'}eta_busybox\" ASH_STANDALONE=1; " +
            "\"${'$'}eta_busybox\" ash $shellArgument; " +
            "else sh $shellArgument; fi"
    }

    /**
     * Linux 工具环境始终在独立 mount namespace 中启动，避免 bind mount 泄漏到 Android 全局。
     * chroot 不是安全沙箱；它只负责提供完整 Linux userland，Android 系统操作仍应走 android 环境。
     * [sharedMounts] 在 namespace 建立时按当前配置逐个 bind 到 rootfs 的 workspace/mounts/<name>，
     * 会话结束即随 namespace 回收，Android 侧不留需要卸载的全局挂载。
     */
    internal fun buildLinuxPayload(
        rootfsPath: String,
        command: String?,
        sharedMounts: List<SharedFolderMount> = emptyList(),
        termType: String = "dumb",
    ): String {
        if (LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT) {
            return ProotCommandBuilder.payload(rootfsPath, command, sharedMounts, termType)
        }
        val rootfs = shellQuote(rootfsPath)
        val mode = if (command == null) "session" else "command"
        val payload = shellQuote(command.orEmpty())
        // name 经 SharedFolderMounts 校验只含 [A-Za-z0-9._-]，可安全拼进双引号路径。
        val mountsBlock = sharedMounts.joinToString("\n") { mount ->
            "eta_mount_optional ${shellQuote(mount.sourcePath)} " +
                "\"\$eta_rootfs${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${mount.name}\" bind"
        }
        val innerScriptHead = """
            eta_rootfs=${'$'}1
            eta_busybox=${'$'}2
            eta_mode=${'$'}3
            eta_payload=${'$'}4
            eta_mount_required() {
              eta_source=${'$'}1
              eta_target=${'$'}2
              eta_options=${'$'}3
              "${'$'}eta_busybox" mkdir -p "${'$'}eta_target" || exit 125
              "${'$'}eta_busybox" mount -o "${'$'}eta_options" "${'$'}eta_source" "${'$'}eta_target" || exit 125
            }
            eta_mount_optional() {
              eta_source=${'$'}1
              eta_target=${'$'}2
              eta_options=${'$'}3
              "${'$'}eta_busybox" mkdir -p "${'$'}eta_target" 2>/dev/null || return 0
              "${'$'}eta_busybox" mount -o "${'$'}eta_options" "${'$'}eta_source" "${'$'}eta_target" 2>/dev/null || true
            }
            "${'$'}eta_busybox" mount -t proc proc "${'$'}eta_rootfs/proc" || exit 125
            eta_mount_required /dev "${'$'}eta_rootfs/dev" rbind
            eta_mount_optional /sys "${'$'}eta_rootfs/sys" rbind
            if [ -d /storage/emulated/0 ]; then
              eta_mount_optional /storage/emulated/0 "${'$'}eta_rootfs/storage/emulated/0" bind
            fi
            [ -d /data/local/tmp ] || exit 125
            eta_mount_required /data/local/tmp "${'$'}eta_rootfs/data/local/tmp" bind
            "${'$'}eta_busybox" mkdir -p /data/local/tmp/eta || exit 125
            eta_mount_required /data/local/tmp/eta "${'$'}eta_rootfs/workspace" bind
        """.trimIndent()
        val innerScriptTail = """
            if [ "${'$'}eta_mode" = command ]; then
              if [ -x "${'$'}eta_rootfs/usr/bin/env" ]; then
                exec "${'$'}eta_busybox" chroot "${'$'}eta_rootfs" /usr/bin/env -i \
                  HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=$termType NO_COLOR=1 \
                  LANG=C.UTF-8 LC_ALL=C.UTF-8 \
                  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                  /bin/sh -lc "${'$'}eta_payload"
              fi
              exec "${'$'}eta_busybox" chroot "${'$'}eta_rootfs" /bin/busybox env -i \
                HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=$termType NO_COLOR=1 \
                LANG=C.UTF-8 LC_ALL=C.UTF-8 \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                /bin/sh -lc "${'$'}eta_payload"
            fi
            if [ -x "${'$'}eta_rootfs/usr/bin/env" ]; then
              exec "${'$'}eta_busybox" chroot "${'$'}eta_rootfs" /usr/bin/env -i \
                HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=$termType \
                LANG=C.UTF-8 LC_ALL=C.UTF-8 \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                /bin/sh
            fi
            exec "${'$'}eta_busybox" chroot "${'$'}eta_rootfs" /bin/busybox env -i \
              HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=$termType \
              LANG=C.UTF-8 LC_ALL=C.UTF-8 \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              /bin/sh
        """.trimIndent()
        val innerScript = if (mountsBlock.isEmpty()) {
            "$innerScriptHead\n$innerScriptTail"
        } else {
            "$innerScriptHead\n$mountsBlock\n$innerScriptTail"
        }
        val discovery = AndroidBusyBox.discoveryScript()
        // Alpine 的 /bin/sh 是指向 /bin/busybox 的绝对符号链接，Android 侧 -x 会按宿主根目录
        // 解析链接目标而误判缺失；符号链接视为存在，真实可执行性由 chroot 后的内核解析兜底。
        return "$discovery; " +
            "[ -n \"${'$'}eta_busybox\" ] || { echo 'ETA_LINUX_BUSYBOX_MISSING' >&2; exit 127; }; " +
            "eta_rootfs=$rootfs; " +
            "[ -f \"${'$'}eta_rootfs/${LinuxEnvironmentPaths.READY_MARKER}\" ] && " +
            "{ [ -x \"${'$'}eta_rootfs/bin/sh\" ] || [ -h \"${'$'}eta_rootfs/bin/sh\" ]; } && " +
            "( [ -x \"${'$'}eta_rootfs/usr/bin/env\" ] || [ -x \"${'$'}eta_rootfs/bin/busybox\" ] ) || " +
            "{ echo 'ETA_LINUX_ENVIRONMENT_NOT_READY' >&2; exit 127; }; " +
            "\"${'$'}eta_busybox\" unshare -m --propagation private " +
            "\"${'$'}eta_busybox\" sh -c ${shellQuote(innerScript)} eta-linux " +
            "\"${'$'}eta_rootfs\" \"${'$'}eta_busybox\" $mode $payload"
    }

    private fun terminateProcessTree(
        process: Process,
        metadataOverride: ProcessMetadata?,
    ) {
        val metadata = metadataOverride ?: synchronized(activeProcesses) { processMetadata[process] }
        val ownership = metadata?.let(::resolveProcessOwnership)
        if (metadata != null && ownership != null) {
            if (ownership.isolatedGroup) {
                signalProcessGroup(metadata, ownership)
            } else {
                signalProcessTree(metadata, ownership)
            }
        }
        runCatching { if (process.isAlive) process.destroy() }
        runCatching { if (process.isAlive) process.destroyForcibly() }
    }

    private fun terminateAndReap(
        process: Process,
        metadataOverride: ProcessMetadata?,
    ) {
        terminateProcessTree(process, metadataOverride)
        reapProcess(process)
    }

    private fun resolveProcessOwnership(metadata: ProcessMetadata): ProcessOwnership? {
        metadata.ownership?.let { ownership -> return ownership }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_OWNERSHIP_WAIT_MS)
        do {
            metadata.ownership?.let { ownership -> return ownership }
            val content = runCatching { metadata.ownershipFile.readText().trim() }.getOrNull().orEmpty()
            if (content.isNotEmpty()) {
                val ownership = parseProcessOwnership(content) ?: return null
                metadata.ownership = ownership
                metadata.ownershipFile.delete()
                return ownership
            }
            if (System.nanoTime() >= deadline) return null
            Thread.sleep(10)
        } while (true)
    }

    private fun parseProcessOwnership(content: String): ProcessOwnership? {
        val parts = content.split(Regex("\\s+"))
        val pid = parts.getOrNull(0)?.toLongOrNull()?.takeIf { value -> value > 1 } ?: return null
        val isolatedGroup = when (parts.getOrNull(1)) {
            "group" -> true
            "tree" -> false
            else -> return null
        }
        return ProcessOwnership(pid = pid, isolatedGroup = isolatedGroup)
    }

    private fun signalProcessGroup(metadata: ProcessMetadata, ownership: ProcessOwnership) {
        runSignalCommand(
            metadata = metadata,
            ownership = ownership,
            command = "kill -9 -${ownership.pid} 2>/dev/null || true",
            requireOwnershipProof = true,
        )
    }

    private fun signalProcessTree(metadata: ProcessMetadata, ownership: ProcessOwnership) {
        val script =
            "kill_tree() { " +
                "children=${'$'}(ps -axo pid=,ppid= | " +
                "awk -v parent=\"${'$'}1\" '${'$'}2 == parent { print ${'$'}1 }'); " +
                "for child in ${'$'}children; do kill_tree \"${'$'}child\"; done; " +
                "kill -9 \"${'$'}1\" 2>/dev/null || true; " +
                "}; kill_tree ${ownership.pid}"
        runSignalCommand(
            metadata = metadata,
            ownership = ownership,
            command = script,
            requireOwnershipProof = false,
        )
    }

    private fun runSignalCommand(
        metadata: ProcessMetadata,
        ownership: ProcessOwnership,
        command: String,
        requireOwnershipProof: Boolean,
    ) {
        val procPath = "/proc/${ownership.pid}"
        val expectedOwner = shellQuote("$ETA_PROCESS_OWNER_ENV=${metadata.ownershipToken}")
        val guardedCommand = if (requireOwnershipProof) {
            "[ -e $procPath ] || exit 0; " +
                "[ -r $procPath/environ ] || exit 0; " +
                "tr '\\000' '\\n' < $procPath/environ | grep -Fqx $expectedOwner || exit 0; " +
                command
        } else {
            command
        }
        val process = runCatching {
            val builder = if (metadata.identity == "root") {
                ProcessBuilder("su", "-c", guardedCommand)
            } else {
                ProcessBuilder("sh", "-c", guardedCommand)
            }
            builder
                .redirectOutput(File("/dev/null"))
                .redirectError(File("/dev/null"))
                .start()
        }.getOrNull() ?: return
        runCatching { process.outputStream.close() }
        val finished = runCatching {
            process.waitFor(PROCESS_SIGNAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) runCatching { process.destroyForcibly() }
    }

    private class ProcessMetadata(
        val identity: String,
        val ownershipFile: File,
        val ownershipToken: String,
    ) {
        @Volatile
        var ownership: ProcessOwnership? = null
    }

    private data class ProcessOwnership(
        val pid: Long,
        val isolatedGroup: Boolean,
    )
}

/** 写入托管进程环境块的归属标记；巡检与停止前用它防止 PID 复用误杀。 */
internal const val ETA_PROCESS_OWNER_ENV = "ETA_PROCESS_OWNER"

internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

internal data class OneShotShellResult(
    val exitCode: Int,
    val output: ByteArray,
    val stderr: ByteArray,
)

/**
 * 探测控制台 PTY 的前提：Root 侧 BusyBox 带 script applet。
 * 用 --list 精确匹配 applet 名；--help 的首行是版本横幅，不含 applet 名。
 * 只在控制台入口调用，不在进程启动热路径使用。
 */
internal fun ptySupported(processSupervisor: ShellProcessSupervisor): Boolean {
    val command = AndroidBusyBox.discoveryScript() +
        "; [ -n \"\$eta_busybox\" ] || exit 1; \"\$eta_busybox\" --list 2>/dev/null | grep -qx script"
    val result = runOneShotShell(
        processSupervisor = processSupervisor,
        identity = "root",
        command = command,
        timeoutSeconds = 10,
    )
    return result.exitCode == 0
}

/**
 * 一次性 Shell 命令原语：带超时回收与有界输出收集。调用方负责命令构造与输出解释；
 * 超时或 supervisor 关闭时整棵进程树由 [ShellProcessSupervisor] 回收。
 */
internal fun runOneShotShell(
    processSupervisor: ShellProcessSupervisor,
    identity: String,
    command: String,
    timeoutSeconds: Long,
    stdin: ByteArray? = null,
    environment: TerminalEnvironment = TerminalEnvironment.ANDROID,
    linuxRootfsPath: String? = null,
    linuxSharedMounts: List<SharedFolderMount> = emptyList(),
): OneShotShellResult {
    val process = processSupervisor.startShellProcess(
        identity = identity,
        command = command,
        mergeStderr = false,
        environment = environment,
        linuxRootfsPath = linuxRootfsPath,
        linuxSharedMounts = linuxSharedMounts,
    ) ?: return OneShotShellResult(
        if (processSupervisor.isClosing) -3 else -1,
        ByteArray(0),
        if (processSupervisor.isClosing) "操作已取消".toByteArray() else "无法启动进程".toByteArray(),
    )

    try {
        val output = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val outputThread = thread(name = "agent-terminal-stdout") {
            process.inputStream.use { input -> output.readFrom(input) }
        }
        val stderrThread = thread(name = "agent-terminal-stderr") {
            process.errorStream.use { input -> stderr.readFrom(input) }
        }
        val stdinThread = thread(name = "agent-terminal-stdin") {
            process.outputStream.use { out ->
                if (stdin != null) out.write(stdin)
            }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            processSupervisor.terminateProcessTree(process)
            outputThread.join(500)
            stderrThread.join(500)
            stdinThread.join(500)
            processSupervisor.reapProcess(process)
            return OneShotShellResult(-2, output.bytes(), "命令执行超时".toByteArray())
        }

        outputThread.join(500)
        stderrThread.join(500)
        stdinThread.join(500)
        return OneShotShellResult(process.exitValue(), output.bytes(), stderr.bytes())
    } finally {
        if (processSupervisor.isClosing) {
            processSupervisor.terminateAndReap(process)
        } else {
            processSupervisor.reapProcess(process)
        }
        processSupervisor.unregisterProcess(process)
    }
}
