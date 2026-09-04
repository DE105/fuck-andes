package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import java.io.File
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
class LinuxEnvironmentSettingsRepositoryTest {
    @Test
    fun backendSelectionPersistsWithoutMovingOrDeletingEitherEnvironment() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        val distribution = LinuxDistribution.DEBIAN
        val before = SettingsDataStore.linuxBackendFlow(distribution.wireName).first()
        val legacy = LinuxEnvironmentPaths.rootfsDir(context, distribution, LinuxExecutionBackend.CHROOT)
        val ordinary = LinuxEnvironmentPaths.rootfsDir(context, distribution, LinuxExecutionBackend.PROOT)
        legacy.mkdirs()
        ordinary.mkdirs()
        val legacyData = File(legacy, "backend-test-data")
        val ordinaryData = File(ordinary, "backend-test-data")
        legacyData.writeText("existing root data")
        ordinaryData.writeText("ordinary data")
        try {
            LinuxEnvironmentSettingsRepository.selectBackend(distribution, LinuxExecutionBackend.PROOT)
            LinuxEnvironmentSettingsRepository.initialize(context)
            assertEquals(ordinary, LinuxEnvironmentPaths.rootfsDir(context, distribution))
            assertEquals("proot", SettingsDataStore.linuxBackendFlow(distribution.wireName).first())
            LinuxEnvironmentSettingsRepository.selectBackend(distribution, LinuxExecutionBackend.CHROOT)
            assertEquals(legacy, LinuxEnvironmentPaths.rootfsDir(context, distribution))
            assertEquals("existing root data", legacyData.readText())
            assertEquals("ordinary data", ordinaryData.readText())
        } finally {
            legacyData.delete()
            ordinaryData.delete()
            SettingsDataStore.setLinuxBackend(distribution.wireName, before)
            LinuxEnvironmentSettingsRepository.initialize(context)
        }
    }

    @Test
    fun selectionRoundTripsThroughDataStore() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        val before = SettingsDataStore.linuxDistributionFlow().first()
        try {
            LinuxEnvironmentSettingsRepository.select(LinuxDistribution.ALPINE)
            assertEquals(LinuxDistribution.ALPINE, LinuxEnvironmentSettingsRepository.selectedFlow(context).first())

            LinuxEnvironmentSettingsRepository.select(LinuxDistribution.DEBIAN)
            assertEquals(LinuxDistribution.DEBIAN, LinuxEnvironmentSettingsRepository.selectedFlow(context).first())
            assertEquals(LinuxDistribution.DEBIAN, LinuxEnvironmentSettingsRepository.current(context))
        } finally {
            SettingsDataStore.setLinuxDistribution(before)
            LinuxEnvironmentSettingsRepository.initialize(context)
        }
    }
}
