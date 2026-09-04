package io.github.mangi.eta.agent.terminal

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

class RootlessLinuxInstallerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun gzipExtractionPreservesExecutableHardlinksAndGuestAbsoluteLinks() = runBlocking {
        val archive = archive(false) {
            file("bin/tool", "hello", 493)
            link("bin/alias", "/bin/tool", TarConstants.LF_SYMLINK)
            link("bin/chain", "bin/hard", TarConstants.LF_LINK)
            link("bin/hard", "bin/tool", TarConstants.LF_LINK)
        }
        val root = temporary.newFolder("rootfs")
        RootlessLinuxInstaller.extract(archive, root, xz = false)
        assertEquals("hello", File(root, "bin/alias").readText())
        assertEquals("hello", File(root, "bin/hard").readText())
        assertEquals("hello", File(root, "bin/chain").readText())
        assertTrue(File(root, "bin/tool").canExecute())
        assertFalse(Files.readSymbolicLink(File(root, "bin/alias").toPath()).isAbsolute)
        val moved = File(temporary.root, "moved")
        assertTrue(root.renameTo(moved))
        assertEquals("hello", File(moved, "bin/alias").readText())
    }

    @Test fun xzExtractionStripsTheDebianRootfsPrefix() = runBlocking {
        val archive = archive(true) { file("rootfs/etc/issue", "Debian") }
        val root = temporary.newFolder("rootfs")
        RootlessLinuxInstaller.extract(archive, root, xz = true, stripComponents = 1)
        assertEquals("Debian", File(root, "etc/issue").readText())
        assertFalse(File(root, "rootfs").exists())
    }

    @Test fun rejectsTraversalWithoutWritingOutsideDestination() {
        val archive = archive(false) { file("../outside", "bad") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { RootlessLinuxInstaller.extract(archive, temporary.newFolder("rootfs"), false) }
        }
        assertFalse(File(temporary.root, "outside").exists())
    }

    @Test fun rejectsEscapingSymlinks() {
        val archive = archive(false) { link("escape", "../../outside", TarConstants.LF_SYMLINK) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { RootlessLinuxInstaller.extract(archive, temporary.newFolder("rootfs"), false) }
        }
        assertFalse(File(temporary.root, "outside").exists())
    }

    @Test fun archiveLinksCannotRedirectLaterFileWrites() {
        val archive = archive(false) {
            link("directory", "../outside", TarConstants.LF_SYMLINK)
            file("directory/sensitive", "bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { RootlessLinuxInstaller.extract(archive, temporary.newFolder("rootfs"), false) }
        }
        assertFalse(File(temporary.root, "outside/sensitive").exists())
    }

    private fun archive(xz: Boolean, content: TarArchiveOutputStream.() -> Unit): File {
        val file = temporary.newFile(if (xz) "archive.tar.xz" else "archive.tar.gz")
        val output = if (xz) XZOutputStream(file.outputStream(), LZMA2Options()) else GZIPOutputStream(file.outputStream())
        TarArchiveOutputStream(output).use { it.content() }
        return file
    }

    private fun TarArchiveOutputStream.file(name: String, value: String, mode: Int = 420) {
        val bytes = value.toByteArray()
        putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong(); this.mode = mode })
        write(bytes)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.link(name: String, target: String, type: Byte) {
        putArchiveEntry(TarArchiveEntry(name, type).apply { linkName = target })
        closeArchiveEntry()
    }
}
