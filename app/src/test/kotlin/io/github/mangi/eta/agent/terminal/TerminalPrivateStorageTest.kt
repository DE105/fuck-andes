package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalPrivateStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun ordinaryDirectoriesCanBeCreatedWhenLegacyParentIsNotWritable() {
        val files = temporary.newFolder("files")
        val legacy = File(files, "terminal").apply { mkdirs() }
        val oldRootfs = File(legacy, "debian/rootfs").apply { mkdirs() }
        val marker = File(oldRootfs, LinuxEnvironmentPaths.READY_MARKER).apply { writeText("existing-root-environment") }
        assertTrue(legacy.setWritable(false, false))
        try {
            assertFalse(legacy.canWrite())
            val workspace = TerminalPrivateStorage.workspace(files)
            val environment = TerminalPrivateStorage.prootEnvironment(files, LinuxDistribution.DEBIAN)

            assertTrue(workspace.mkdirs())
            assertTrue(environment.mkdirs())
            File(workspace, "ordinary.txt").writeText("app-data")
            assertEquals("app-data", File(workspace, "ordinary.txt").readText())
            assertEquals("existing-root-environment", marker.readText())
            assertFalse(legacy.canWrite())
            assertFalse(File(legacy, "proot").exists())
            assertFalse(File(legacy, "workspace").exists())
        } finally {
            legacy.setWritable(true, true)
        }
    }

    @Test
    fun existingOrdinaryDataKeepsItsOriginalPath() {
        val files = temporary.newFolder("existing")
        val workspace = File(files, "terminal/workspace").apply { mkdirs() }
        val environment = File(files, "terminal/proot/debian").apply { mkdirs() }
        val saved = File(workspace, "saved.txt").apply { writeText("keep") }

        assertEquals(workspace, TerminalPrivateStorage.workspace(files))
        assertEquals(environment, TerminalPrivateStorage.prootEnvironment(files, LinuxDistribution.DEBIAN))
        assertEquals("keep", saved.readText())
        assertFalse(File(files, "terminal-user").exists())
    }

    @Test
    fun newDirectoryRemainsSelectedWhenBothLayoutsExist() {
        val files = temporary.newFolder("both")
        val workspace = TerminalPrivateStorage.workspace(files).apply { mkdirs() }
        val environment = TerminalPrivateStorage.prootEnvironment(files, LinuxDistribution.ALPINE).apply { mkdirs() }
        File(files, "terminal/workspace").mkdirs()
        File(files, "terminal/proot/alpine").mkdirs()

        assertEquals(workspace, TerminalPrivateStorage.workspace(files))
        assertEquals(environment, TerminalPrivateStorage.prootEnvironment(files, LinuxDistribution.ALPINE))
    }

    @Test
    fun bothOrdinaryLayoutsAreRecognizedWithoutChangingChrootIdentity() {
        listOf("terminal", "terminal-user").forEach { parent ->
            assertEquals(LinuxExecutionBackend.PROOT, LinuxEnvironmentPaths.backendOf("/data/user/0/app/files/$parent/proot/debian/rootfs"))
        }
        assertEquals(LinuxExecutionBackend.CHROOT, LinuxEnvironmentPaths.backendOf("/data/user/0/app/files/terminal/debian/rootfs"))
        assertEquals(LinuxExecutionBackend.CHROOT, LinuxEnvironmentPaths.backendOf(null))
    }
}
