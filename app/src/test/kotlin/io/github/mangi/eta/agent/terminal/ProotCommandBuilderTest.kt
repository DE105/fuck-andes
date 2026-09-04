package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotCommandBuilderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun packagedExecutableReceivesQuotedGuestArgumentsAndPrivateLoader() {
        val native = temporary.newFolder("native")
        File(native, "libproot_exec.so").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"\$PROOT_LOADER\" \"\$PROOT_TMP_DIR\" \"\$@\"\n")
            setExecutable(true)
        }
        File(native, "libproot_loader.so").apply { writeText("loader"); setExecutable(true) }
        val workspace = File(temporary.root, "workspace 'with quotes")
        val tmp = File(temporary.root, "tmp")
        val rootfs = File(temporary.root, "rootfs 'quote").path
        val command = "printf '%s' '\$(touch /unexpected)'"
        val script = ProotCommandBuilder.payload(rootfs, command, nativeDirectory = native, workspace = workspace.path, tempDirectory = tmp)
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val args = process.inputStream.bufferedReader().readLines()
        assertEquals(0, process.waitFor())
        assertEquals(File(native, "libproot_loader.so").path, args[0])
        assertEquals(tmp.path, args[1])
        assertTrue(args.contains(rootfs))
        assertTrue(args.contains("${workspace.path}:/workspace"))
        assertTrue(args.contains("--kill-on-exit"))
        assertEquals(command, args.last())
        assertTrue(workspace.isDirectory)
        assertFalse(script.contains("su -c"))
    }

    @Test fun publicSharesRequireExplicitAndroidStorageGrant() {
        assertFalse(ProotCommandBuilder.permittedSharedSource("/storage/emulated/0/Documents", false))
        assertFalse(ProotCommandBuilder.permittedSharedSource("/sdcard/Documents", false))
        assertTrue(ProotCommandBuilder.permittedSharedSource("/storage/emulated/0/Documents", true))
        assertTrue(ProotCommandBuilder.permittedSharedSource(temporary.root.path, false))
    }

    @Test fun missingNativeComponentsFailBeforeGuestLaunch() {
        val process = ProcessBuilder("sh", "-c", ProotCommandBuilder.payload("/rootfs", "echo hello", nativeDirectory = null)).start()
        assertEquals(127, process.waitFor())
        assertEquals("ETA_PROOT_UNAVAILABLE", process.errorStream.bufferedReader().readText().trim())
    }
}
