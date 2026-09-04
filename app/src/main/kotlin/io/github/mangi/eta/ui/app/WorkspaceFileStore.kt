package io.github.mangi.eta.ui.app

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class WorkspaceEntry(val path: String, val name: String, val directory: Boolean, val size: Long)

internal class WorkspaceFileStore(context: Context) {
    private val appContext = context.applicationContext
    private val root: File get() = File(TerminalRuntime.userWorkspacePath).canonicalFile

    suspend fun list(path: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        val directory = resolve(path)
        check(directory.isDirectory) { "WORKSPACE_NOT_DIRECTORY" }
        val files = directory.listFiles() ?: throw java.io.IOException("WORKSPACE_UNREADABLE")
        files.mapNotNull { file ->
            val canonical = file.canonicalFile
            if (!canonical.toPath().startsWith(root.toPath()) || (!canonical.isFile && !canonical.isDirectory)) {
                null
            } else {
                WorkspaceEntry(file.relativeTo(root).path, file.name, file.isDirectory, file.length())
            }
        }.sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    suspend fun importFile(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        AgentFileReferenceGateway.importDocumentUri(appContext, uri) is AgentFileReferenceGateway.Resolution.Success
    }

    suspend fun exportFile(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val source = resolve(path)
        check(source.isFile) { "WORKSPACE_NOT_FILE" }
        source.inputStream().use { input ->
            val output = appContext.contentResolver.openOutputStream(destination, "wt")
                ?: throw java.io.IOException("EXPORT_UNAVAILABLE")
            output.use {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun resolve(path: String): File {
        val file = File(root, path).canonicalFile
        require(file.toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
        return file
    }
}
