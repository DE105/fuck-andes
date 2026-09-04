package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File

/** 两个 Linux rootfs 共用的磁盘布局和就绪判定。 */
internal object LinuxEnvironmentPaths {
    const val READY_MARKER = ".eta-environment-ready"

    fun environmentDir(context: Context, distribution: LinuxDistribution): File =
        environmentDir(context, distribution, LinuxEnvironmentSettingsRepository.backend(context, distribution))

    fun environmentDir(context: Context, distribution: LinuxDistribution, backend: LinuxExecutionBackend): File =
        if (backend == LinuxExecutionBackend.CHROOT) {
            File(context.filesDir, "terminal/${distribution.wireName}")
        } else {
            TerminalPrivateStorage.prootEnvironment(context.filesDir, distribution)
        }

    fun rootfsDir(context: Context, distribution: LinuxDistribution): File =
        File(environmentDir(context, distribution), "rootfs")

    fun rootfsDir(context: Context, distribution: LinuxDistribution, backend: LinuxExecutionBackend): File =
        File(environmentDir(context, distribution, backend), "rootfs")

    fun legacyRootfsDir(context: Context, distribution: LinuxDistribution): File =
        rootfsDir(context, distribution, LinuxExecutionBackend.CHROOT)

    fun backendOf(rootfsPath: String?): LinuxExecutionBackend =
        if (TerminalPrivateStorage.isProotPath(rootfsPath)) LinuxExecutionBackend.PROOT else LinuxExecutionBackend.CHROOT

    fun rootfsReady(rootfsPath: String?): Boolean {
        if (rootfsPath.isNullOrBlank()) return false
        return File(rootfsPath, READY_MARKER).isFile
    }
}
