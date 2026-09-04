package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** Linux 发行版选择的单一持久化入口。 */
internal object LinuxEnvironmentSettingsRepository {
    @Volatile
    private var cachedSelection: LinuxDistribution? = null
    private val cachedBackends = java.util.concurrent.ConcurrentHashMap<LinuxDistribution, LinuxExecutionBackend>()

    fun backend(context: Context, distribution: LinuxDistribution): LinuxExecutionBackend =
        cachedBackends[distribution] ?: defaultBackend(context, distribution)

    fun backendFlow(context: Context, distribution: LinuxDistribution): Flow<LinuxExecutionBackend> =
        SettingsDataStore.linuxBackendFlow(distribution.wireName).map { value ->
            LinuxExecutionBackend.entries.firstOrNull { it.wireName == value }
                ?.also { cachedBackends[distribution] = it }
                ?: defaultBackend(context, distribution)
        }

    suspend fun selectBackend(distribution: LinuxDistribution, backend: LinuxExecutionBackend) {
        SettingsDataStore.setLinuxBackend(distribution.wireName, backend.wireName)
        cachedBackends[distribution] = backend
    }

    private fun defaultBackend(context: Context, distribution: LinuxDistribution): LinuxExecutionBackend = resolveBackend(
        legacyReady = LinuxEnvironmentPaths.rootfsReady(LinuxEnvironmentPaths.legacyRootfsDir(context, distribution).absolutePath),
        prootReady = LinuxEnvironmentPaths.rootfsReady(LinuxEnvironmentPaths.rootfsDir(context, distribution, LinuxExecutionBackend.PROOT).absolutePath),
        rootAvailable = TerminalRuntime.rootAvailable,
    )

    internal fun resolveBackend(legacyReady: Boolean, prootReady: Boolean, rootAvailable: Boolean): LinuxExecutionBackend = when {
        legacyReady -> LinuxExecutionBackend.CHROOT
        prootReady -> LinuxExecutionBackend.PROOT
        rootAvailable -> LinuxExecutionBackend.CHROOT
        else -> LinuxExecutionBackend.PROOT
    }

    fun selectedFlow(context: Context): Flow<LinuxDistribution> =
        SettingsDataStore.linuxDistributionFlow()
            .map { persisted ->
                decode(persisted) ?: defaultSelection(context.applicationContext)
            }
            .onEach { cachedSelection = it }

    fun current(context: Context): LinuxDistribution =
        cachedSelection ?: defaultSelection(context.applicationContext)

    suspend fun initialize(context: Context) {
        LinuxDistribution.entries.forEach { distribution ->
            val value = SettingsDataStore.linuxBackendFlow(distribution.wireName).first()
            val selected = LinuxExecutionBackend.entries.firstOrNull { it.wireName == value }
            if (selected != null) cachedBackends[distribution] = selected else cachedBackends.remove(distribution)
        }
        cachedSelection = selectedFlow(context.applicationContext).first()
    }

    suspend fun select(distribution: LinuxDistribution) {
        cachedSelection = distribution
        SettingsDataStore.setLinuxDistribution(distribution.wireName)
    }

    private fun decode(value: String?): LinuxDistribution? =
        LinuxDistribution.entries.firstOrNull { distribution -> distribution.wireName == value }

    private fun defaultSelection(context: Context): LinuxDistribution {
        val alpineReady = LinuxEnvironmentPaths.rootfsReady(
            LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.ALPINE).absolutePath,
        )
        val debianReady = LinuxEnvironmentPaths.rootfsReady(
            LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.DEBIAN).absolutePath,
        )
        return if (alpineReady && !debianReady) LinuxDistribution.ALPINE else LinuxDistribution.DEBIAN
    }
}
