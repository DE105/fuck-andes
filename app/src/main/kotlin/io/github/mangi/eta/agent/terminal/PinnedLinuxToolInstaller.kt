package io.github.mangi.eta.agent.terminal

import android.content.Context
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ManagedLinuxTool {
    UV,
    NODE,
}

internal class PinnedLinuxToolInstaller(
    private val context: Context,
) {
    private val downloader = VerifiedArtifactDownloader()

    suspend fun install(
        tool: ManagedLinuxTool,
        distribution: LinuxDistribution,
        rootfs: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Boolean {
        val artifact = PinnedLinuxToolArtifacts.artifactFor(
            tool = tool,
            distribution = distribution,
            abis = Build.SUPPORTED_ABIS.toList(),
        ) ?: return false
        val archive = File(context.cacheDir, "linux-installer/profiles/${artifact.fileName}.download")
        return try {
            onProgress(0, artifact.sizeBytes)
            if (!downloader.download(artifact, archive, onProgress)) return false
            coroutineContext.ensureActive()
            activate(tool, artifact, archive, rootfs)
        } catch (_: java.io.IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            archive.delete()
        }
    }

    private suspend fun activate(
        tool: ManagedLinuxTool,
        artifact: VerifiedArtifact,
        archive: File,
        rootfs: File,
    ): Boolean {
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            return activateRootless(tool, artifact, archive, rootfs)
        }
        val command = when (tool) {
            ManagedLinuxTool.UV -> uvActivationCommand(artifact, archive, rootfs)
            ManagedLinuxTool.NODE -> nodeActivationCommand(artifact, archive, rootfs)
        }
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 180,
            environment = TerminalEnvironment.ANDROID,
        )
        AndroidAgentLogger.info(
            "Managed Linux tool action=activate tool=${tool.name.lowercase()} version=${artifact.version} " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    private suspend fun activateRootless(tool: ManagedLinuxTool, artifact: VerifiedArtifact, archive: File, rootfs: File): Boolean {
        val name = tool.name.lowercase()
        val versions = File(rootfs, "opt/eta/$name")
        val target = File(versions, artifact.version)
        val staging = File(rootfs, "opt/eta/$name.installing")
        try {
            if (staging.exists() && !staging.deleteRecursively()) return false
            RootlessLinuxInstaller.extract(archive, staging, xz = tool == ManagedLinuxTool.NODE, stripComponents = 1)
            require(versions.mkdirs() || versions.isDirectory)
            if (target.exists() && !target.deleteRecursively()) return false
            if (!staging.renameTo(target)) return false
            val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
            when (tool) {
                ManagedLinuxTool.UV -> listOf("uv", "uvx").forEach { command ->
                    File(localBin, command).apply { writeText(uvWrapper(artifact.version, command)); require(setExecutable(true, false)) }
                }
                ManagedLinuxTool.NODE -> listOf("node", "npm", "npx").forEach { command ->
                    val link = File(localBin, command).toPath()
                    java.nio.file.Files.deleteIfExists(link)
                    java.nio.file.Files.createSymbolicLink(link, java.nio.file.Path.of("../../../opt/eta/node/${artifact.version}/bin/$command"))
                }
            }
            return true
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun uvActivationCommand(
        artifact: VerifiedArtifact,
        archive: File,
        rootfs: File,
    ): String {
        val target = File(rootfs, "opt/eta/uv/${artifact.version}")
        val staging = File(rootfs, "opt/eta/uv.installing")
        val versionsRoot = target.parentFile!!
        val uvWrapper = uvWrapper(artifact.version, "uv")
        val uvxWrapper = uvWrapper(artifact.version, "uvx")
        val uvPath = File(rootfs, "usr/local/bin/uv")
        val uvxPath = File(rootfs, "usr/local/bin/uvx")
        return """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            eta_archive=${shellQuote(archive.absolutePath)}
            eta_staging=${shellQuote(staging.absolutePath)}
            eta_target=${shellQuote(target.absolutePath)}
            "${'$'}eta_busybox" rm -rf "${'$'}eta_staging"
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_staging" || exit 66
            "${'$'}eta_busybox" tar -xzf "${'$'}eta_archive" -C "${'$'}eta_staging" --strip-components=1 || exit 67
            "${'$'}eta_busybox" mkdir -p ${shellQuote(versionsRoot.parentFile!!.absolutePath)} ${shellQuote(File(rootfs, "usr/local/bin").absolutePath)} || exit 66
            "${'$'}eta_busybox" rm -rf ${shellQuote(versionsRoot.absolutePath)}
            "${'$'}eta_busybox" mkdir -p ${shellQuote(versionsRoot.absolutePath)} || exit 66
            "${'$'}eta_busybox" mv "${'$'}eta_staging" "${'$'}eta_target" || exit 69
            "${'$'}eta_busybox" chmod 0755 "${'$'}eta_target/uv" "${'$'}eta_target/uvx" || exit 70
            printf %s ${shellQuote(uvWrapper)} > ${shellQuote(uvPath.absolutePath)} || exit 71
            printf %s ${shellQuote(uvxWrapper)} > ${shellQuote(uvxPath.absolutePath)} || exit 71
            "${'$'}eta_busybox" chmod 0755 ${shellQuote(uvPath.absolutePath)} ${shellQuote(uvxPath.absolutePath)} || exit 71
        """.trimIndent()
    }

    private fun uvWrapper(version: String, command: String): String = """
        #!/bin/sh
        export UV_PYTHON_INSTALL_DIR=/opt/eta/python
        export UV_PYTHON_BIN_DIR=/usr/local/bin
        export UV_PYTHON_INSTALL_BIN=1
        export UV_TOOL_DIR=/opt/eta/uv-tools
        export UV_TOOL_BIN_DIR=/usr/local/bin
        exec /opt/eta/uv/$version/$command "${'$'}@"
    """.trimIndent() + "\n"

    private fun nodeActivationCommand(
        artifact: VerifiedArtifact,
        archive: File,
        rootfs: File,
    ): String {
        val target = File(rootfs, "opt/eta/node/${artifact.version}")
        val staging = File(rootfs, "opt/eta/node.installing")
        val versionsRoot = target.parentFile!!
        val localBin = File(rootfs, "usr/local/bin")
        return """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            eta_archive=${shellQuote(archive.absolutePath)}
            eta_staging=${shellQuote(staging.absolutePath)}
            eta_target=${shellQuote(target.absolutePath)}
            "${'$'}eta_busybox" rm -rf "${'$'}eta_staging"
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_staging" || exit 66
            "${'$'}eta_busybox" tar -xJf "${'$'}eta_archive" -C "${'$'}eta_staging" --strip-components=1 || exit 67
            "${'$'}eta_busybox" mkdir -p ${shellQuote(versionsRoot.parentFile!!.absolutePath)} ${shellQuote(localBin.absolutePath)} || exit 66
            "${'$'}eta_busybox" rm -rf ${shellQuote(versionsRoot.absolutePath)}
            "${'$'}eta_busybox" mkdir -p ${shellQuote(versionsRoot.absolutePath)} || exit 66
            "${'$'}eta_busybox" mv "${'$'}eta_staging" "${'$'}eta_target" || exit 69
            "${'$'}eta_busybox" ln -sfn ${shellQuote("/opt/eta/node/${artifact.version}/bin/node")} ${shellQuote(File(localBin, "node").absolutePath)} || exit 71
            "${'$'}eta_busybox" ln -sfn ${shellQuote("/opt/eta/node/${artifact.version}/bin/npm")} ${shellQuote(File(localBin, "npm").absolutePath)} || exit 71
            "${'$'}eta_busybox" ln -sfn ${shellQuote("/opt/eta/node/${artifact.version}/bin/npx")} ${shellQuote(File(localBin, "npx").absolutePath)} || exit 71
        """.trimIndent()
    }
}

internal object PinnedLinuxToolArtifacts {
    private const val UV_VERSION = "0.12.7"
    private const val NODE_VERSION = "26.8.1"
    private const val GITHUB_PROXY_PREFIX = "https://gh-proxy.com/"

    fun artifactFor(
        tool: ManagedLinuxTool,
        distribution: LinuxDistribution,
        abis: List<String>,
    ): VerifiedArtifact? = when (tool) {
        ManagedLinuxTool.UV -> uvArtifact(distribution, abis)
        ManagedLinuxTool.NODE -> nodeArtifact(distribution, abis)
    }

    private fun uvArtifact(
        distribution: LinuxDistribution,
        abis: List<String>,
    ): VerifiedArtifact? {
        val libc = if (distribution == LinuxDistribution.ALPINE) "musl" else "gnu"
        return abis.firstNotNullOfOrNull { abi ->
            when (abi) {
                "arm64-v8a" -> uvArtifact(
                    architecture = "aarch64",
                    libc = libc,
                    sha256 = if (libc == "musl") {
                        "6dcf60e3c085de88ace3671b949ca99f0652be561ff5627f0d21394140f041db"
                    } else {
                        "66393193038dd7eb108abd7a218d9cec04ac70ab98242b0720fa94de19223b7c"
                    },
                    sizeBytes = if (libc == "musl") 20_492_921L else 18_594_128L,
                )
                "x86_64" -> uvArtifact(
                    architecture = "x86_64",
                    libc = libc,
                    sha256 = if (libc == "musl") {
                        "3d64d44ed67da7908dc7f5c4d64ebb44bad326fa17f8a0a52fc9a7793017bbe1"
                    } else {
                        "788f18abea7c5f55d6216e4f5613fd89d4d59b631efeec117b2b07fe72f1da21"
                    },
                    sizeBytes = if (libc == "musl") 22_282_878L else 19_419_908L,
                )
                else -> null
            }
        }
    }

    private fun uvArtifact(
        architecture: String,
        libc: String,
        sha256: String,
        sizeBytes: Long,
    ): VerifiedArtifact {
        val fileName = "uv-$architecture-unknown-linux-$libc.tar.gz"
        val officialUrl = "https://github.com/astral-sh/uv/releases/download/$UV_VERSION/$fileName"
        return VerifiedArtifact(
            id = "uv-$architecture-$libc",
            version = UV_VERSION,
            fileName = fileName,
            url = officialUrl,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            preferredUrls = listOf(GITHUB_PROXY_PREFIX + officialUrl),
        )
    }

    private fun nodeArtifact(
        distribution: LinuxDistribution,
        abis: List<String>,
    ): VerifiedArtifact? {
        if (distribution != LinuxDistribution.DEBIAN) return null
        return abis.firstNotNullOfOrNull { abi ->
            when (abi) {
                "arm64-v8a" -> nodeArtifact(
                    architecture = "arm64",
                    sha256 = "23c1b4d19e2f12a7d06fe8aa3d6e0e4923cf77a47e13c5ccdf32fadaa33960f2",
                    sizeBytes = 32_709_132L,
                )
                "x86_64" -> nodeArtifact(
                    architecture = "x64",
                    sha256 = "3e301118d7df53d563b7e96c1617545f26e2f76f9724be668d6cab65c15dda5d",
                    sizeBytes = 33_703_524L,
                )
                else -> null
            }
        }
    }

    private fun nodeArtifact(
        architecture: String,
        sha256: String,
        sizeBytes: Long,
    ): VerifiedArtifact {
        val fileName = "node-v$NODE_VERSION-linux-$architecture.tar.xz"
        return VerifiedArtifact(
            id = "node-$architecture",
            version = NODE_VERSION,
            fileName = fileName,
            url = "https://nodejs.org/download/release/v$NODE_VERSION/$fileName",
            sha256 = sha256,
            sizeBytes = sizeBytes,
            preferredUrls = listOf("https://cdn.npmmirror.com/binaries/node/v$NODE_VERSION/$fileName"),
        )
    }
}
