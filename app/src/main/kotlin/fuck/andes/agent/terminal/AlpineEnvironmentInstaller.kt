package fuck.andes.agent.terminal

import android.content.Context
import android.os.Build
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.data.model.AlpineMirror
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlin.coroutines.coroutineContext

internal enum class AlpineEnvironmentState {
    NOT_INSTALLED,
    BASE_READY,
    READY,
}

internal data class AlpineEnvironmentStatus(
    val state: AlpineEnvironmentState,
    val version: String? = null,
)

internal data class AlpineEnvironmentHealth(
    val healthy: Boolean,
    val availableTools: List<String>,
    val missingTools: List<String>,
    val workspaceReady: Boolean,
    val sharedStorageReady: Boolean,
    val availableBytes: Long,
)

internal enum class AlpineInstallStage {
    CHECKING,
    DOWNLOADING,
    EXTRACTING,
    UPDATING_INDEX,
    INSTALLING_TOOLS,
    COMPLETE,
}

internal data class AlpineInstallProgress(
    val stage: AlpineInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentPackage: Int = 0,
    val totalPackages: Int = 0,
) {
    fun progressFraction(): Float? = when (stage) {
        AlpineInstallStage.DOWNLOADING ->
            if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
        AlpineInstallStage.INSTALLING_TOOLS ->
            if (totalPackages > 0) {
                (currentPackage.toFloat() / totalPackages.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
        AlpineInstallStage.COMPLETE -> 1f
        else -> null
    }
}

internal sealed interface AlpineInstallResult {
    data object AlreadyReady : AlpineInstallResult
    data class Installed(val version: String) : AlpineInstallResult
    data class UnsupportedAbi(val abi: String) : AlpineInstallResult
    data object RootUnavailable : AlpineInstallResult
    data object BusyBoxUnavailable : AlpineInstallResult
    data object EnvironmentUnavailable : AlpineInstallResult
    data class Failed(val stage: AlpineInstallStage) : AlpineInstallResult
}

/**
 * 下载官方 Alpine minirootfs，并在 Root 授权边界内完成原子解压与常用工具安装。
 * 下载内容先校验固定 SHA-256；安装过程不会扩大到 App 私有环境目录之外。
 */
internal class AlpineEnvironmentInstaller(
    private val context: Context,
    private val mirror: AlpineMirror = AlpineMirror.OFFICIAL,
    customMirrorUrl: String? = null,
    httpClient: OkHttpClient = VerifiedArtifactDownloader.defaultHttpClient(),
) {
    private val mirrorBaseUrl: String = mirror.effectiveBaseUrl(customMirrorUrl)
    private val artifactDownloader = VerifiedArtifactDownloader(httpClient)
    fun status(): AlpineEnvironmentStatus {
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        val version = readInstalledVersion(rootfs)
        val state = when {
            AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath) -> AlpineEnvironmentState.READY
            AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath) -> AlpineEnvironmentState.BASE_READY
            else -> AlpineEnvironmentState.NOT_INSTALLED
        }
        return AlpineEnvironmentStatus(state = state, version = version)
    }

    suspend fun install(
        forceToolInstall: Boolean = false,
        onProgress: suspend (AlpineInstallProgress) -> Unit = {},
    ): AlpineInstallResult {
        installMutex.lock()
        return try {
            installLocked(forceToolInstall, onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    suspend fun inspectHealth(): AlpineEnvironmentHealth = withContext(Dispatchers.IO) {
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        val availableBytes = rootfs.parentFile?.usableSpace ?: context.filesDir.usableSpace
        if (!AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
            return@withContext AlpineEnvironmentHealth(
                healthy = false,
                availableTools = emptyList(),
                missingTools = HEALTH_CHECK_COMMANDS,
                workspaceReady = false,
                sharedStorageReady = false,
                availableBytes = availableBytes,
            )
        }
        val command = buildString {
            append("for eta_tool in ")
            append(HEALTH_CHECK_COMMANDS.joinToString(" "))
            append("; do command -v \"${'$'}eta_tool\" >/dev/null 2>&1 && printf 'tool:%s\\n' \"${'$'}eta_tool\"; done\n")
            append("mountpoint -q /workspace && printf 'mount:workspace\\n'\n")
            append("mountpoint -q /storage/emulated/0 && printf 'mount:sdcard\\n'\n")
            append("true")
        }
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 30,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfs.absolutePath,
        )
        val facts = result.output.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        val availableTools = HEALTH_CHECK_COMMANDS.filter { tool -> "tool:$tool" in facts }
        val missingTools = HEALTH_CHECK_COMMANDS - availableTools.toSet()
        val workspaceReady = "mount:workspace" in facts
        val sharedStorageReady = "mount:sdcard" in facts
        AlpineEnvironmentHealth(
            healthy = result.exitCode == 0 && missingTools.isEmpty() && workspaceReady,
            availableTools = availableTools,
            missingTools = missingTools,
            workspaceReady = workspaceReady,
            sharedStorageReady = sharedStorageReady,
            availableBytes = availableBytes,
        )
    }

    private suspend fun installLocked(
        forceToolInstall: Boolean,
        onProgress: suspend (AlpineInstallProgress) -> Unit,
    ): AlpineInstallResult = withContext(Dispatchers.IO) {
        if (!forceToolInstall && status().state == AlpineEnvironmentState.READY) {
            return@withContext AlpineInstallResult.AlreadyReady
        }
        val artifact = artifactForAbis(Build.SUPPORTED_ABIS.toList(), mirrorBaseUrl)
            ?: return@withContext AlpineInstallResult.UnsupportedAbi(
                Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
            )

        onProgress(AlpineInstallProgress(AlpineInstallStage.CHECKING))
        when (runPreflight().exitCode) {
            0 -> Unit
            PREFLIGHT_ROOT_UNAVAILABLE -> return@withContext AlpineInstallResult.RootUnavailable
            PREFLIGHT_BUSYBOX_UNAVAILABLE, PREFLIGHT_BUSYBOX_INCOMPLETE ->
                return@withContext AlpineInstallResult.BusyBoxUnavailable
            PREFLIGHT_ENVIRONMENT_UNAVAILABLE ->
                return@withContext AlpineInstallResult.EnvironmentUnavailable
            else -> return@withContext AlpineInstallResult.Failed(AlpineInstallStage.CHECKING)
        }

        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        if (!AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
            val archive = AlpineEnvironmentPaths.cachedArtifact(context, artifact.fileName)
            onProgress(AlpineInstallProgress(AlpineInstallStage.DOWNLOADING))
            val cacheHit = artifactDownloader.verify(artifact, archive)
            if (cacheHit) {
                onProgress(
                    AlpineInstallProgress(
                        stage = AlpineInstallStage.DOWNLOADING,
                        downloadedBytes = artifact.sizeBytes,
                        totalBytes = artifact.sizeBytes,
                    ),
                )
            } else {
                val downloaded = artifactDownloader.download(artifact, archive) { downloadedBytes, totalBytes ->
                    onProgress(
                        AlpineInstallProgress(
                            stage = AlpineInstallStage.DOWNLOADING,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                        ),
                    )
                }
                if (!downloaded) {
                    return@withContext AlpineInstallResult.Failed(AlpineInstallStage.DOWNLOADING)
                }
            }
            coroutineContext.ensureActive()
            onProgress(AlpineInstallProgress(AlpineInstallStage.EXTRACTING))
            val extracted = installRootfs(artifact, archive, rootfs)
            if (!extracted) {
                return@withContext AlpineInstallResult.Failed(AlpineInstallStage.EXTRACTING)
            }
        }

        coroutineContext.ensureActive()
        if (!installCommonTools(rootfs, onProgress)) {
            return@withContext AlpineInstallResult.Failed(AlpineInstallStage.INSTALLING_TOOLS)
        }
        onProgress(AlpineInstallProgress(AlpineInstallStage.COMPLETE))
        AlpineInstallResult.Installed(artifact.version)
    }

    private suspend fun runPreflight(): InstallerCommandResult {
        val requiredApplets = listOf(
            "ash",
            "chroot",
            "gzip",
            "mount",
            "sha256sum",
            "tar",
            "unshare",
        ).joinToString(" ")
        val command = """
            if [ "${'$'}(id -u)" != 0 ]; then exit $PREFLIGHT_ROOT_UNAVAILABLE; fi
            ${AndroidBusyBox.discoveryScript()}
            if [ -z "${'$'}eta_busybox" ]; then exit $PREFLIGHT_BUSYBOX_UNAVAILABLE; fi
            for eta_applet in $requiredApplets; do
              "${'$'}eta_busybox" --list | "${'$'}eta_busybox" grep -qx "${'$'}eta_applet" || exit $PREFLIGHT_BUSYBOX_INCOMPLETE
            done
            "${'$'}eta_busybox" unshare -m --propagation private \
              "${'$'}eta_busybox" chroot / /system/bin/sh -c ':' || exit $PREFLIGHT_ENVIRONMENT_UNAVAILABLE
        """.trimIndent()
        return InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 15,
            environment = TerminalEnvironment.ANDROID,
        )
    }

    private suspend fun installRootfs(
        artifact: VerifiedArtifact,
        archive: File,
        rootfs: File,
    ): Boolean {
        val parent = rootfs.parentFile ?: return false
        val temporaryRootfs = File(parent, "rootfs.installing")
        val markerBody = "version=${artifact.version}\\nsha256=${artifact.sha256}\\n"
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            eta_archive=${shellQuote(archive.absolutePath)}
            eta_parent=${shellQuote(parent.absolutePath)}
            eta_rootfs=${shellQuote(rootfs.absolutePath)}
            eta_temporary=${shellQuote(temporaryRootfs.absolutePath)}
            eta_actual_sha=${'$'}("${'$'}eta_busybox" sha256sum "${'$'}eta_archive" | "${'$'}eta_busybox" awk '{print ${'$'}1}')
            [ "${'$'}eta_actual_sha" = ${shellQuote(artifact.sha256)} ] || exit 65
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_parent" || exit 66
            "${'$'}eta_busybox" rm -rf "${'$'}eta_temporary"
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_temporary" || exit 66
            "${'$'}eta_busybox" tar -xzf "${'$'}eta_archive" -C "${'$'}eta_temporary" || exit 67
            [ -x "${'$'}eta_temporary/bin/busybox" ] || exit 68
            "${'$'}eta_busybox" mkdir -p \
              "${'$'}eta_temporary/proc" \
              "${'$'}eta_temporary/sys" \
              "${'$'}eta_temporary/dev" \
              "${'$'}eta_temporary/workspace" \
              "${'$'}eta_temporary/storage/emulated/0" \
              "${'$'}eta_temporary/data/local/tmp" \
              "${'$'}eta_temporary/tmp"
            "${'$'}eta_busybox" chmod 1777 "${'$'}eta_temporary/tmp"
            "${'$'}eta_busybox" rm -f "${'$'}eta_temporary/sdcard"
            "${'$'}eta_busybox" ln -s /storage/emulated/0 "${'$'}eta_temporary/sdcard"
            cat > "${'$'}eta_temporary/etc/resolv.conf" <<'ETA_RESOLV_EOF'
            nameserver 1.1.1.1
            nameserver 8.8.8.8
            ETA_RESOLV_EOF
            cat > "${'$'}eta_temporary/etc/apk/repositories" <<'ETA_REPOSITORIES_EOF'
            ${mirrorBaseUrl}/v3.24/main
            ${mirrorBaseUrl}/v3.24/community
            ETA_REPOSITORIES_EOF
            printf ${shellQuote(markerBody)} > "${'$'}eta_temporary/${AlpineEnvironmentPaths.READY_MARKER}"
            "${'$'}eta_busybox" chmod 0644 "${'$'}eta_temporary/${AlpineEnvironmentPaths.READY_MARKER}"
            "${'$'}eta_busybox" rm -rf "${'$'}eta_rootfs"
            "${'$'}eta_busybox" mv "${'$'}eta_temporary" "${'$'}eta_rootfs" || exit 69
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 120,
            environment = TerminalEnvironment.ANDROID,
        )
        AndroidAgentLogger.info(
            "Alpine environment action=extract outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0 && AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath)
    }

    private suspend fun installCommonTools(
        rootfs: File,
        onProgress: suspend (AlpineInstallProgress) -> Unit,
    ): Boolean {
        onProgress(AlpineInstallProgress(AlpineInstallStage.UPDATING_INDEX))
        writeApkRepositories(rootfs)
        val indexResult = InstallerShellRunner.run(
            command = "apk update",
            timeoutSeconds = COMMON_TOOLS_TIMEOUT_SECONDS,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfs.absolutePath,
        )
        if (indexResult.exitCode != 0) {
            AndroidAgentLogger.info(
                "Alpine environment action=update_index outcome=failed " +
                    "exitCode=${indexResult.exitCode} outputChars=${indexResult.output.length}",
            )
            return false
        }

        val packages = DEFAULT_PACKAGES.joinToString(" ")
        val command = """
            apk add --no-cache $packages
            ln -sf /usr/bin/python3 /usr/local/bin/python
            cat > /${AlpineEnvironmentPaths.COMMON_TOOLS_MARKER} <<'ETA_TOOLSET_EOF'
            alpine=$ALPINE_VERSION
            toolset=${AlpineEnvironmentPaths.TOOLSET_REVISION}
            profiles=agent,python
            ETA_TOOLSET_EOF
            chmod 0644 /${AlpineEnvironmentPaths.COMMON_TOOLS_MARKER}
        """.trimIndent()
        onProgress(AlpineInstallProgress(AlpineInstallStage.INSTALLING_TOOLS))
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = COMMON_TOOLS_TIMEOUT_SECONDS,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfs.absolutePath,
        ) { line ->
            parseApkPackageProgress(line)?.let { (current, total) ->
                onProgress(
                    AlpineInstallProgress(
                        stage = AlpineInstallStage.INSTALLING_TOOLS,
                        currentPackage = current,
                        totalPackages = total,
                    ),
                )
            }
        }
        AndroidAgentLogger.info(
            "Alpine environment action=install_tools " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0 && AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath)
    }

    private fun writeApkRepositories(rootfs: File) {
        runCatching {
            val repositories = File(rootfs, "etc/apk/repositories")
            repositories.parentFile?.mkdirs()
            repositories.writeText(
                buildString {
                    append(mirrorBaseUrl).append("/v3.24/main\n")
                    append(mirrorBaseUrl).append("/v3.24/community\n")
                },
            )
        }.onFailure { throwable ->
            AndroidAgentLogger.warn(
                "Alpine environment action=write_repositories outcome=failed " +
                    "errorType=${throwable.javaClass.simpleName}",
            )
        }
    }

    private fun readInstalledVersion(rootfs: File): String? =
        runCatching {
            File(rootfs, AlpineEnvironmentPaths.READY_MARKER)
                .readLines()
                .firstOrNull { line -> line.startsWith("version=") }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { value -> value.matches(Regex("[0-9]+(?:\\.[0-9]+){1,2}")) }
        }.getOrNull()

    companion object {
        private const val ALPINE_VERSION = "3.24.1"
        private const val DEFAULT_MIRROR_BASE_URL = "https://dl-cdn.alpinelinux.org/alpine"
        private const val COMMON_TOOLS_TIMEOUT_SECONDS = 600L
        private const val PREFLIGHT_ROOT_UNAVAILABLE = 40
        private const val PREFLIGHT_BUSYBOX_UNAVAILABLE = 41
        private const val PREFLIGHT_BUSYBOX_INCOMPLETE = 42
        private const val PREFLIGHT_ENVIRONMENT_UNAVAILABLE = 43

        private val installMutex = Mutex()

        internal val AGENT_PACKAGES = listOf(
            "bash",
            "ca-certificates",
            "coreutils",
            "curl",
            "diffutils",
            "fd",
            "file",
            "findutils",
            "gawk",
            "git",
            "grep",
            "gzip",
            "jq",
            "less",
            "openssl",
            "openssh-client-default",
            "patch",
            "procps-ng",
            "ripgrep",
            "rsync",
            "sed",
            "sqlite",
            "tar",
            "unzip",
            "util-linux",
            "wget",
            "xz",
            "zip",
            "zstd",
        )

        internal val PYTHON_PACKAGES = listOf(
            "pipx",
            "py3-pip",
            "py3-virtualenv",
            "python3",
            "ruff",
            "uv",
        )

        internal val DEFAULT_PACKAGES = (AGENT_PACKAGES + PYTHON_PACKAGES).distinct()

        private val HEALTH_CHECK_COMMANDS = listOf(
            "bash",
            "curl",
            "diff",
            "fd",
            "git",
            "jq",
            "patch",
            "python3",
            "rg",
            "rsync",
            "sqlite3",
            "ssh",
            "uv",
        )

        internal fun artifactForAbis(
            abis: List<String>,
            baseUrl: String = DEFAULT_MIRROR_BASE_URL,
        ): VerifiedArtifact? =
            abis.firstNotNullOfOrNull { abi ->
                when (abi) {
                    "arm64-v8a" -> VerifiedArtifact(
                        id = "alpine-minirootfs-aarch64",
                        version = ALPINE_VERSION,
                        fileName = "alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz",
                        url = "$baseUrl/v3.24/releases/aarch64/" +
                            "alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz",
                        sha256 = "f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259",
                        sizeBytes = 4_023_732L,
                    )
                    "x86_64" -> VerifiedArtifact(
                        id = "alpine-minirootfs-x86_64",
                        version = ALPINE_VERSION,
                        fileName = "alpine-minirootfs-$ALPINE_VERSION-x86_64.tar.gz",
                        url = "$baseUrl/v3.24/releases/x86_64/" +
                            "alpine-minirootfs-$ALPINE_VERSION-x86_64.tar.gz",
                        sha256 = "41f73e3cf5fa919b8aa5ca6b30dc48f0da2720776d7423e2a7748211456fe081",
                        sizeBytes = 3_698_422L,
                    )
                    else -> null
                }
            }

    }
}

internal data class InstallerCommandResult(
    val exitCode: Int,
    val output: String,
)

private val apkPackageProgressRegex = Regex("""^\((\d+)/(\d+)\)\s+""")

internal fun parseApkPackageProgress(line: String): Pair<Int, Int>? {
    val match = apkPackageProgressRegex.find(line) ?: return null
    val current = match.groupValues[1].toIntOrNull() ?: return null
    val total = match.groupValues[2].toIntOrNull() ?: return null
    if (current < 0 || total <= 0) return null
    return current to total
}

internal object InstallerShellRunner {
    private const val MAX_OUTPUT_BYTES = 64 * 1024
    private const val POLL_INTERVAL_MS = 100L
    private const val EOF_SENTINEL = "\u0000eta-installer-eof\u0000"

    suspend fun run(
        command: String,
        timeoutSeconds: Long,
        environment: TerminalEnvironment,
        linuxRootfsPath: String? = null,
        onOutputLine: suspend (String) -> Unit = {},
    ): InstallerCommandResult {
        val supervisor = ShellProcessSupervisor()
        val process = withContext(Dispatchers.IO) {
            supervisor.startShellProcess(
                identity = "root",
                command = command,
                mergeStderr = true,
                environment = environment,
                linuxRootfsPath = linuxRootfsPath,
            )
        } ?: return InstallerCommandResult(exitCode = -1, output = "")

        val output = ByteArrayOutputStream()
        val lines = LinkedBlockingQueue<String>()
        val reader = thread(name = "eta-alpine-installer-output", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        appendBoundedLine(output, line)
                        lines.put(line)
                    }
                }
            } finally {
                lines.put(EOF_SENTINEL)
            }
        }
        withContext(Dispatchers.IO) { runCatching { process.outputStream.close() } }

        return try {
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            var timedOut = false
            var reachedEof = false
            while (!reachedEof && !timedOut) {
                coroutineContext.ensureActive()
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) {
                    timedOut = true
                    break
                }
                val pollNanos = minOf(remainingNanos, TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MS))
                val line = withContext(Dispatchers.IO) {
                    lines.poll(pollNanos, TimeUnit.NANOSECONDS)
                } ?: continue
                if (line == EOF_SENTINEL) {
                    reachedEof = true
                } else {
                    onOutputLine(line)
                }
            }

            if (timedOut) {
                withContext(Dispatchers.IO) { supervisor.terminateProcessTree(process) }
                reader.join(1_000)
                InstallerCommandResult(exitCode = -2, output = output.text())
            } else {
                drainLines(lines, onOutputLine)
                reader.join(1_000)
                withContext(Dispatchers.IO) { process.waitFor(1, TimeUnit.SECONDS) }
                InstallerCommandResult(
                    exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
                    output = output.text(),
                )
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (process.isAlive) {
                    supervisor.terminateAndReap(process)
                } else {
                    supervisor.reapProcess(process)
                }
                supervisor.unregisterProcess(process)
            }
        }
    }

    private suspend fun drainLines(
        lines: LinkedBlockingQueue<String>,
        onOutputLine: suspend (String) -> Unit,
    ) {
        while (true) {
            val line = lines.poll() ?: return
            if (line == EOF_SENTINEL) return
            onOutputLine(line)
        }
    }

    private fun appendBoundedLine(output: ByteArrayOutputStream, line: String) {
        val bytes = (line + "\n").encodeToByteArray()
        synchronized(output) {
            val remaining = (MAX_OUTPUT_BYTES - output.size()).coerceAtLeast(0)
            if (remaining > 0) output.write(bytes, 0, bytes.size.coerceAtMost(remaining))
        }
    }

    private fun ByteArrayOutputStream.text(): String =
        synchronized(this) { toByteArray().decodeToString().trimEnd() }
}
