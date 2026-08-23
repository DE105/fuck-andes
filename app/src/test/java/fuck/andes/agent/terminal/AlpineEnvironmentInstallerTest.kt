package fuck.andes.agent.terminal

import fuck.andes.data.model.AlpineMirror
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlpineEnvironmentInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun artifactSelectionUsesFirstSupportedAbiWithPinnedIntegrityMetadata() {
        val artifact = AlpineEnvironmentInstaller.artifactForAbis(
            listOf("armeabi-v7a", "arm64-v8a", "x86_64"),
        )

        requireNotNull(artifact)
        assertEquals("3.24.1", artifact.version)
        assertTrue(artifact.fileName.endsWith("-aarch64.tar.gz"))
        assertTrue(artifact.url.startsWith("https://dl-cdn.alpinelinux.org/alpine/v3.24/"))
        assertEquals(64, artifact.sha256.length)
        assertEquals(4_023_732L, artifact.sizeBytes)
    }

    @Test
    fun unsupportedAbiDoesNotGuessAnArtifact() {
        assertNull(AlpineEnvironmentInstaller.artifactForAbis(listOf("armeabi-v7a", "x86")))
    }

    @Test
    fun mirrorBaseUrlRewritesArtifactUrls() {
        val artifact = AlpineEnvironmentInstaller.artifactForAbis(
            listOf("arm64-v8a"),
            baseUrl = "https://mirrors.aliyun.com/alpine",
        )

        requireNotNull(artifact)
        assertTrue(
            artifact.url.startsWith("https://mirrors.aliyun.com/alpine/v3.24/releases/aarch64/"),
        )
        assertEquals(64, artifact.sha256.length)
    }

    @Test
    fun alpineMirrorFallsBackToOfficialForUnknownPersistedValue() {
        assertEquals(AlpineMirror.OFFICIAL, AlpineMirror.fromPersistedValue(null))
        assertEquals(AlpineMirror.OFFICIAL, AlpineMirror.fromPersistedValue("unknown"))
        assertEquals(AlpineMirror.ALIYUN, AlpineMirror.fromPersistedValue("aliyun"))
        assertEquals(AlpineMirror.TUNA, AlpineMirror.fromPersistedValue("tuna"))
        assertEquals(AlpineMirror.USTC, AlpineMirror.fromPersistedValue("ustc"))
        assertEquals(AlpineMirror.CUSTOM, AlpineMirror.fromPersistedValue("custom"))
    }

    @Test
    fun customMirrorFallsBackToOfficialWhenBlankOrInvalid() {
        assertEquals(AlpineMirror.OFFICIAL.baseUrl, AlpineMirror.CUSTOM.effectiveBaseUrl(null))
        assertEquals(AlpineMirror.OFFICIAL.baseUrl, AlpineMirror.CUSTOM.effectiveBaseUrl("  "))
        assertEquals(AlpineMirror.OFFICIAL.baseUrl, AlpineMirror.CUSTOM.effectiveBaseUrl("not a url"))
        assertEquals(
            "https://mirrors.example.com/alpine",
            AlpineMirror.CUSTOM.effectiveBaseUrl("https://mirrors.example.com/alpine/"),
        )
        assertEquals(
            AlpineMirror.ALIYUN.baseUrl,
            AlpineMirror.ALIYUN.effectiveBaseUrl("https://mirrors.example.com/alpine"),
        )
    }

    @Test
    fun readinessRequiresMarkerAndBusyBoxAndTracksCommonToolsSeparately() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val bin = File(rootfs, "bin").apply { mkdirs() }
        val busyBox = File(bin, "busybox")
        val ready = File(rootfs, AlpineEnvironmentPaths.READY_MARKER)

        assertFalse(AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        busyBox.writeText("busybox")
        assertFalse(AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        ready.writeText("version=3.24.1\n")
        assertTrue(AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        assertFalse(AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath))

        File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).writeText("3.24.1\n")
        assertFalse(AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath))

        File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).writeText(
            "alpine=3.24.1\ntoolset=${AlpineEnvironmentPaths.TOOLSET_REVISION}\nprofiles=agent,python\n",
        )
        assertTrue(AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath))
    }

    @Test
    fun defaultToolsetContainsAgentAndPythonEssentialsWithoutInteractiveEditors() {
        val packages = AlpineEnvironmentInstaller.DEFAULT_PACKAGES

        assertTrue(packages.containsAll(listOf("ripgrep", "fd", "diffutils", "patch", "rsync")))
        assertTrue(packages.containsAll(listOf("python3", "py3-virtualenv", "pipx", "uv", "ruff")))
        assertFalse(packages.contains("vim"))
        assertFalse(packages.contains("nano"))
        assertEquals(packages.distinct(), packages)
    }

    @Test
    fun apkPackageProgressParsesInstallingSteps() {
        assertEquals(
            1 to 36,
            parseApkPackageProgress(
                "(1/36) Installing ca-certificates (20240603-r0)",
            ),
        )
        assertEquals(
            36 to 36,
            parseApkPackageProgress(
                "(36/36) Installing zstd (1.5.6-r0)",
            ),
        )
        assertEquals(
            3 to 40,
            parseApkPackageProgress(
                "(3/40) Upgrading python3 (3.12.9-r0)",
            ),
        )
    }

    @Test
    fun apkPackageProgressIgnoresNonProgressLines() {
        assertNull(parseApkPackageProgress("fetch https://dl-cdn.alpinelinux.org/..."))
        assertNull(parseApkPackageProgress("OK: 126 packages"))
        assertNull(parseApkPackageProgress("(x/36) Installing ca-certificates"))
        assertNull(parseApkPackageProgress("(1/0) Installing broken"))
        assertNull(parseApkPackageProgress(""))
        assertNull(parseApkPackageProgress("(1/36)"))
    }

    @Test
    fun downloadProgressFractionFollowsBytes() {
        val progress = AlpineInstallProgress(
            stage = AlpineInstallStage.DOWNLOADING,
            downloadedBytes = 25,
            totalBytes = 100,
        )
        assertEquals(0.25f, requireNotNull(progress.progressFraction()), 0.0001f)
    }

    @Test
    fun downloadProgressFractionIsIndeterminateWithoutTotal() {
        val progress = AlpineInstallProgress(
            stage = AlpineInstallStage.DOWNLOADING,
            downloadedBytes = 10,
            totalBytes = 0,
        )
        assertNull(progress.progressFraction())
    }

    @Test
    fun toolInstallProgressFractionFollowsPackages() {
        val progress = AlpineInstallProgress(
            stage = AlpineInstallStage.INSTALLING_TOOLS,
            currentPackage = 3,
            totalPackages = 36,
        )
        assertEquals(3f / 36f, requireNotNull(progress.progressFraction()), 0.0001f)
    }

    @Test
    fun toolInstallProgressFractionIsIndeterminateBeforeFirstPackage() {
        val progress = AlpineInstallProgress(stage = AlpineInstallStage.INSTALLING_TOOLS)
        assertNull(progress.progressFraction())
    }

    @Test
    fun unknownAndCompleteStagesMapToDeterminateEndpoints() {
        assertEquals(null, AlpineInstallProgress(AlpineInstallStage.CHECKING).progressFraction())
        assertEquals(null, AlpineInstallProgress(AlpineInstallStage.EXTRACTING).progressFraction())
        assertEquals(null, AlpineInstallProgress(AlpineInstallStage.UPDATING_INDEX).progressFraction())
        assertEquals(1f, requireNotNull(AlpineInstallProgress(AlpineInstallStage.COMPLETE).progressFraction()), 0.0001f)
    }

    @Test
    fun apkAnalysisReadinessRequiresCurrentMarkerAndManagedFiles() {
        val rootfs = temporaryFolder.newFolder("analysis-rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/busybox").writeText("busybox")
        File(rootfs, AlpineEnvironmentPaths.READY_MARKER).writeText("version=3.24.1\n")
        File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).writeText(
            "toolset=${AlpineEnvironmentPaths.TOOLSET_REVISION}\n",
        )
        val current = File(rootfs, "opt/eta/apk-analysis/current")
        listOf("bin/java", "jadx/bin/jadx", "bin/apktool", "bin/smali", "bin/baksmali").forEach { path ->
            File(current, path).apply {
                parentFile?.mkdirs()
                writeText(path)
            }
        }

        File(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_MARKER).writeText("profile=0\n")
        assertFalse(AlpineEnvironmentPaths.apkAnalysisReady(rootfs.absolutePath))

        File(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_MARKER).writeText(
            "profile=${AlpineEnvironmentPaths.APK_ANALYSIS_REVISION}\n",
        )
        assertTrue(AlpineEnvironmentPaths.apkAnalysisReady(rootfs.absolutePath))
        File(current, "jadx/bin/jadx").delete()
        assertFalse(AlpineEnvironmentPaths.apkAnalysisReady(rootfs.absolutePath))
    }
}
