package io.github.mangi.eta.agent.terminal

import android.content.Context
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
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

internal enum class AlpineInstallStage {
    CHECKING,
    DOWNLOADING,
    EXTRACTING,
    INSTALLING_TOOLS,
    COMPLETE,
}

internal data class AlpineInstallProgress(
    val stage: AlpineInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface AlpineInstallResult {
    data object AlreadyReady : AlpineInstallResult
    data class BaseInstalled(val version: String) : AlpineInstallResult
    data class ToolsInstalled(val version: String) : AlpineInstallResult
    data object BaseNotInstalled : AlpineInstallResult
    data class UnsupportedAbi(val abi: String) : AlpineInstallResult
    data object RootUnavailable : AlpineInstallResult
    data object BusyBoxUnavailable : AlpineInstallResult
    data object EnvironmentUnavailable : AlpineInstallResult
    data class Failed(val stage: AlpineInstallStage, val code: String? = null, val message: String? = null) : AlpineInstallResult
}

/**
 * 下载官方 Alpine minirootfs，并在 Root 授权边界内完成原子解压。
 * 下载内容先校验固定 SHA-256；安装过程不会扩大到 App 私有环境目录之外。
 */
internal class AlpineEnvironmentInstaller(
    private val context: Context,
    httpClient: OkHttpClient = VerifiedArtifactDownloader.defaultHttpClient(),
) {
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

    suspend fun installBase(
        onProgress: suspend (AlpineInstallProgress) -> Unit = {},
    ): AlpineInstallResult {
        installMutex.lock()
        return try {
            installBaseLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    suspend fun installTools(
        onProgress: suspend (AlpineInstallProgress) -> Unit = {},
    ): AlpineInstallResult {
        installMutex.lock()
        return try {
            installToolsLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installBaseLocked(
        onProgress: suspend (AlpineInstallProgress) -> Unit,
    ): AlpineInstallResult = withContext(Dispatchers.IO) {
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        if (AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
            return@withContext AlpineInstallResult.AlreadyReady
        }
        io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository.selectBackend(
            LinuxDistribution.ALPINE, LinuxEnvironmentPaths.backendOf(rootfs.absolutePath),
        )
        val artifact = artifactForAbis(Build.SUPPORTED_ABIS.toList())
            ?: return@withContext AlpineInstallResult.UnsupportedAbi(
                Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
            )

        onProgress(AlpineInstallProgress(AlpineInstallStage.CHECKING))
        preflightFailure()?.let { return@withContext it }

        val archive = File(context.cacheDir, artifact.fileName + ".download")
        try {
            onProgress(AlpineInstallProgress(AlpineInstallStage.DOWNLOADING))
            val downloaded = artifactDownloader.download(artifact, archive) { downloadedBytes, totalBytes ->
                onProgress(
                    AlpineInstallProgress(
                        stage = AlpineInstallStage.DOWNLOADING,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            if (!downloaded) return@withContext AlpineInstallResult.Failed(AlpineInstallStage.DOWNLOADING)
            coroutineContext.ensureActive()
            onProgress(AlpineInstallProgress(AlpineInstallStage.EXTRACTING))
            if (!installRootfs(artifact, archive, rootfs)) {
                return@withContext AlpineInstallResult.Failed(AlpineInstallStage.EXTRACTING)
            }
        } catch (failure: RootlessInstallFailure) {
            return@withContext AlpineInstallResult.Failed(AlpineInstallStage.EXTRACTING, failure.code, failure.message)
        } catch (_: java.io.IOException) {
            return@withContext AlpineInstallResult.Failed(AlpineInstallStage.EXTRACTING, "INSTALL_IO_FAILED", "安装文件无法读写，请检查内部存储空间并重试")
        } catch (_: IllegalArgumentException) {
            return@withContext AlpineInstallResult.Failed(AlpineInstallStage.EXTRACTING, "INVALID_ARCHIVE", "环境归档无效或包含不安全路径，请重新下载后重试")
        } finally {
            archive.delete()
        }

        onProgress(AlpineInstallProgress(AlpineInstallStage.COMPLETE))
        AlpineInstallResult.BaseInstalled(artifact.version)
    }

    private suspend fun installToolsLocked(
        onProgress: suspend (AlpineInstallProgress) -> Unit,
    ): AlpineInstallResult = withContext(Dispatchers.IO) {
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        if (!AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
            return@withContext AlpineInstallResult.BaseNotInstalled
        }
        if (AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath)) {
            return@withContext AlpineInstallResult.AlreadyReady
        }
        onProgress(AlpineInstallProgress(AlpineInstallStage.CHECKING))
        preflightFailure()?.let { return@withContext it }
        onProgress(AlpineInstallProgress(AlpineInstallStage.INSTALLING_TOOLS))
        if (!installCommonTools(rootfs)) {
            return@withContext AlpineInstallResult.Failed(AlpineInstallStage.INSTALLING_TOOLS)
        }
        onProgress(AlpineInstallProgress(AlpineInstallStage.COMPLETE))
        AlpineInstallResult.ToolsInstalled(readInstalledVersion(rootfs) ?: ALPINE_VERSION)
    }

    private suspend fun preflightFailure(): AlpineInstallResult? = when (runPreflight().exitCode) {
        0 -> null
        PREFLIGHT_ROOT_UNAVAILABLE -> AlpineInstallResult.RootUnavailable
        PREFLIGHT_BUSYBOX_UNAVAILABLE, PREFLIGHT_BUSYBOX_INCOMPLETE ->
            AlpineInstallResult.BusyBoxUnavailable
        PREFLIGHT_ENVIRONMENT_UNAVAILABLE -> AlpineInstallResult.EnvironmentUnavailable
        else -> AlpineInstallResult.Failed(AlpineInstallStage.CHECKING)
    }

    private suspend fun runPreflight(): InstallerCommandResult {
        if (LinuxEnvironmentPaths.backendOf(AlpineEnvironmentPaths.rootfsDir(context).absolutePath) == LinuxExecutionBackend.PROOT) {
            return InstallerCommandResult(if (ProotCommandBuilder.available()) 0 else PREFLIGHT_ENVIRONMENT_UNAVAILABLE, "")
        }
        if (!TerminalRuntime.rootAvailable) return InstallerCommandResult(PREFLIGHT_ROOT_UNAVAILABLE, "")
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
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            return RootlessLinuxInstaller.installBase(artifact, archive, rootfs, LinuxDistribution.ALPINE)
        }
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
            nameserver 223.5.5.5
            nameserver 119.29.29.29
            nameserver 1.1.1.1
            ETA_RESOLV_EOF
            printf '%s\n' \
              ${shellQuote("${APK_MIRROR_BASE_URLS.first()}/v3.24/main")} \
              ${shellQuote("${APK_MIRROR_BASE_URLS.first()}/v3.24/community")} > "${'$'}eta_temporary/etc/apk/repositories"
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_temporary/usr/local/bin"
            printf '%s\n' '#!/bin/sh' > "${'$'}eta_temporary/usr/local/bin/eta-apk"
            printf %s ${shellQuote(apkMirrorScriptBody())} >> "${'$'}eta_temporary/usr/local/bin/eta-apk"
            "${'$'}eta_busybox" chmod 0755 "${'$'}eta_temporary/usr/local/bin/eta-apk"
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
        return result.exitCode == 0
    }

    private suspend fun installCommonTools(rootfs: File): Boolean {
        val packages = AGENT_PACKAGES.joinToString(" ")
        val command = """
            mkdir -p /usr/local/bin
            printf '%s\n' '#!/bin/sh' > /usr/local/bin/eta-apk
            printf %s ${shellQuote(apkMirrorScriptBody())} >> /usr/local/bin/eta-apk
            chmod 0755 /usr/local/bin/eta-apk
            /usr/local/bin/eta-apk install $packages || exit 70
            cat > /${AlpineEnvironmentPaths.COMMON_TOOLS_MARKER} <<'ETA_TOOLSET_EOF'
            alpine=$ALPINE_VERSION
            toolset=${AlpineEnvironmentPaths.TOOLSET_REVISION}
            profiles=agent
            ETA_TOOLSET_EOF
            chmod 0644 /${AlpineEnvironmentPaths.COMMON_TOOLS_MARKER}
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = COMMON_TOOLS_TIMEOUT_SECONDS,
            environment = TerminalEnvironment.ALPINE,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Alpine environment action=install_tools " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
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

        /** 真机链路只保留一个国内镜像，避免可访问但过慢的源阻塞后续尝试。 */
        internal val APK_MIRROR_BASE_URLS = listOf(
            "https://mirrors.aliyun.com/alpine",
            "https://dl-cdn.alpinelinux.org/alpine",
        )

        /** 逐个尝试镜像并把成功者写回 repositories，后续 profile 安装会复用它。 */
        internal fun apkMirrorScript(): String = "#!/bin/sh\n${apkMirrorScriptBody()}"

        private fun apkMirrorScriptBody(): String = buildString {
            append("set -u; ")
            append("case \"${'$'}{1:-}\" in ")
            append("install) shift; [ \"${'$'}#\" -gt 0 ] || exit 64; ")
            append("for eta_apk_mirror in ${APK_MIRROR_BASE_URLS.joinToString(" ")}; do ")
            append("printf '%s\\n' \"${'$'}eta_apk_mirror/v3.24/main\" \"${'$'}eta_apk_mirror/v3.24/community\" > /etc/apk/repositories || exit 65; ")
            append("if apk update && apk add --no-cache \"${'$'}@\"; then exit 0; fi; ")
            append("done; exit 1;; ")
            append("update) for eta_apk_mirror in ${APK_MIRROR_BASE_URLS.joinToString(" ")}; do ")
            append("printf '%s\\n' \"${'$'}eta_apk_mirror/v3.24/main\" \"${'$'}eta_apk_mirror/v3.24/community\" > /etc/apk/repositories || exit 65; ")
            append("apk update && exit 0; done; exit 1;; ")
            append("*) echo \"usage: eta-apk install PACKAGE... | update\" >&2; exit 64;; esac")
        }

        internal fun artifactForAbis(abis: List<String>): VerifiedArtifact? =
            abis.firstNotNullOfOrNull { abi ->
                when (abi) {
                    "arm64-v8a" -> VerifiedArtifact(
                        id = "alpine-minirootfs-aarch64",
                        version = ALPINE_VERSION,
                        fileName = "alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz",
                        url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/" +
                            "alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz",
                        sha256 = "f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259",
                        sizeBytes = 4_023_732L,
                        preferredUrls = listOf(
                            "https://mirrors.aliyun.com/alpine/v3.24/releases/aarch64/" +
                                "alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz",
                        ),
                    )
                    "x86_64" -> VerifiedArtifact(
                        id = "alpine-minirootfs-x86_64",
                        version = ALPINE_VERSION,
                        fileName = "alpine-minirootfs-$ALPINE_VERSION-x86_64.tar.gz",
                        url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/" +
                            "alpine-minirootfs-$ALPINE_VERSION-x86_64.tar.gz",
                        sha256 = "41f73e3cf5fa919b8aa5ca6b30dc48f0da2720776d7423e2a7748211456fe081",
                        sizeBytes = 3_698_422L,
                        preferredUrls = listOf(
                            "https://mirrors.aliyun.com/alpine/v3.24/releases/x86_64/" +
                                "alpine-minirootfs-$ALPINE_VERSION-x86_64.tar.gz",
                        ),
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

internal object InstallerShellRunner {
    private const val MAX_OUTPUT_BYTES = 64 * 1024

    suspend fun run(
        command: String,
        timeoutSeconds: Long,
        environment: TerminalEnvironment,
        linuxRootfsPath: String? = null,
        identity: String? = null,
    ): InstallerCommandResult = runInterruptible(Dispatchers.IO) {
        val supervisor = ShellProcessSupervisor()
        val process = supervisor.startShellProcess(
            identity = identity ?: TerminalRuntime.defaultIdentity(environment, linuxRootfsPath),
            command = command,
            mergeStderr = true,
            environment = environment,
            linuxRootfsPath = linuxRootfsPath,
        ) ?: return@runInterruptible InstallerCommandResult(exitCode = -1, output = "")
        val output = ByteArrayOutputStream()
        val reader = thread(name = "eta-alpine-installer-output", isDaemon = true) {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = (MAX_OUTPUT_BYTES - output.size()).coerceAtLeast(0)
                            if (remaining > 0) output.write(buffer, 0, count.coerceAtMost(remaining))
                        }
                    }
                }
            }
        }
        runCatching { process.outputStream.close() }
        try {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                supervisor.terminateProcessTree(process)
                reader.join(1_000)
                InstallerCommandResult(exitCode = -2, output = output.text())
            } else {
                reader.join(1_000)
                InstallerCommandResult(exitCode = process.exitValue(), output = output.text())
            }
        } finally {
            if (process.isAlive) {
                supervisor.terminateAndReap(process)
            } else {
                supervisor.reapProcess(process)
            }
            supervisor.unregisterProcess(process)
        }
    }

    private fun ByteArrayOutputStream.text(): String =
        synchronized(this) { toByteArray().decodeToString().trimEnd() }
}
