package io.github.mangi.eta.agent.device

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.hasUnsupportedControlCharacter
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal class AgentFileReferenceGateway(
    private val resolveDocumentPath: (Uri) -> String? = { null },
    private val importDocument: ((Uri) -> Resolution)? = null,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    private val executeRootCommand: (String) -> BoundedRootCommandExecutor.Result,
) {
    constructor(logger: AgentLogger) : this(
        executeRootCommand = { command ->
            BoundedRootCommandExecutor(logger).use { executor ->
                executor.execute(
                    command = command,
                    timeoutMillis = VALIDATION_TIMEOUT_MS,
                    maxOutputBytes = MAX_VALIDATION_OUTPUT_BYTES,
                )
            }
        }
    )

    constructor(
        context: Context,
        logger: AgentLogger,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) : this(
        resolveDocumentPath = { uri -> queryLocalDocumentPath(context.applicationContext, uri) },
        importDocument = { uri -> importDocumentUri(context.applicationContext, uri) },
        rootAvailable = rootAvailable,
        executeRootCommand = { command ->
            BoundedRootCommandExecutor(logger, rootAvailable).use { executor ->
                executor.execute(
                    command = command,
                    timeoutMillis = VALIDATION_TIMEOUT_MS,
                    maxOutputBytes = MAX_VALIDATION_OUTPUT_BYTES,
                )
            }
        },
    )

    fun resolveDocumentUri(
        uri: Uri,
        expectedKind: AgentFileReferenceKind,
    ): Resolution {
        val documentId = runCatching {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
        }.getOrNull()
        val mappedPath = documentId?.let { mapPrimaryStorageDocument(uri.authority, it) }
            ?: resolveDocumentPath(uri)
        val resolved = mappedPath?.let { resolveAbsolutePath(it, expectedKind) }
        if (resolved is Resolution.Success) return resolved
        if (expectedKind == AgentFileReferenceKind.File && uri.scheme == "content") {
            importDocument?.let { return it(uri) }
        }
        return resolved ?: Resolution.Failure(Error.UnsupportedDocumentProvider)
    }

    fun resolveAbsolutePath(
        rawPath: String,
        expectedKind: AgentFileReferenceKind? = null,
    ): Resolution {
        val path = rawPath
        if (
            path.isEmpty() ||
            !path.startsWith('/') ||
            path.hasUnsupportedControlCharacter()
        ) {
            return Resolution.Failure(Error.InvalidPath)
        }
        val localResult = resolveAsApp(path, expectedKind)
        if (localResult is Resolution.Success || !rootAvailable()) return localResult
        if (localResult is Resolution.Failure && localResult.error == Error.TypeMismatch) return localResult
        val result = executeRootCommand(validationCommand(path))
        if (!result.ok) {
            return Resolution.Failure(
                when {
                    result.errorCode in setOf("ROOT_UNAVAILABLE", "ROOT_REQUIRED") -> Error.RootUnavailable
                    result.timedOut -> Error.ValidationTimedOut
                    result.exitCode == EXIT_ROOT_UNAVAILABLE -> Error.RootUnavailable
                    result.exitCode == EXIT_UNSUPPORTED_TYPE -> Error.UnsupportedFileType
                    result.exitCode == EXIT_PATH_NOT_FOUND -> Error.PathNotFound
                    else -> Error.RootUnavailable
                }
            )
        }
        val outputSeparator = result.stdout.indexOf('\n')
        if (outputSeparator <= 0) return Resolution.Failure(Error.InvalidPath)
        val kind = when (result.stdout.substring(0, outputSeparator)) {
            KIND_FILE -> AgentFileReferenceKind.File
            KIND_DIRECTORY -> AgentFileReferenceKind.Directory
            else -> return Resolution.Failure(Error.UnsupportedFileType)
        }
        val canonicalPath = result.stdout.substring(outputSeparator + 1).trimEnd('\n')
        if (
            canonicalPath.isEmpty() ||
            canonicalPath.hasUnsupportedControlCharacter()
        ) {
            return Resolution.Failure(Error.InvalidPath)
        }
        if (expectedKind != null && kind != expectedKind) {
            return Resolution.Failure(Error.TypeMismatch)
        }
        return Resolution.Success(
            AgentFileReference(
                displayName = canonicalPath.substringAfterLast('/').ifBlank { canonicalPath },
                absolutePath = canonicalPath,
                kind = kind,
            )
        )
    }

    private fun resolveAsApp(path: String, expectedKind: AgentFileReferenceKind?): Resolution = try {
        val file = File(path).canonicalFile
        require(!file.path.hasUnsupportedControlCharacter())
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        val kind = when {
            attributes.isRegularFile -> AgentFileReferenceKind.File
            attributes.isDirectory -> AgentFileReferenceKind.Directory
            else -> null
        }
        when {
            kind == null -> Resolution.Failure(Error.UnsupportedFileType)
            expectedKind != null && expectedKind != kind -> Resolution.Failure(Error.TypeMismatch)
            !Files.isReadable(file.toPath()) ||
                (kind == AgentFileReferenceKind.Directory && !Files.isExecutable(file.toPath())) ->
                Resolution.Failure(Error.AccessDenied)
            else -> Resolution.Success(
                AgentFileReference(
                    displayName = file.name.ifBlank { file.path },
                    absolutePath = file.path,
                    kind = kind,
                ),
            )
        }
    } catch (_: IllegalArgumentException) {
        Resolution.Failure(Error.InvalidPath)
    } catch (_: NoSuchFileException) {
        Resolution.Failure(Error.PathNotFound)
    } catch (_: AccessDeniedException) {
        Resolution.Failure(Error.AccessDenied)
    } catch (_: SecurityException) {
        Resolution.Failure(Error.AccessDenied)
    } catch (_: IOException) {
        Resolution.Failure(Error.AccessDenied)
    }

    internal enum class Error {
        UnsupportedDocumentProvider,
        InvalidPath,
        PathNotFound,
        UnsupportedFileType,
        TypeMismatch,
        RootUnavailable,
        AccessDenied,
        ImportFailed,
        ImportTooLarge,
        ValidationTimedOut,
    }

    internal sealed interface Resolution {
        data class Success(val reference: AgentFileReference) : Resolution
        data class Failure(val error: Error) : Resolution
    }

    internal companion object {
        const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
        const val SHARED_STORAGE_ROOT = "/storage/emulated/0"

        private const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"
        internal const val MAX_IMPORT_BYTES = 32L * 1024 * 1024

        internal fun importDocumentUri(context: Context, uri: Uri): Resolution {
            val importDirectory = File(context.filesDir, "terminal/workspace/imports/${UUID.randomUUID()}")
            var completed = false
            return try {
                val name = documentDisplayName(context, uri)
                val displayName = safeImportName(name)
                if (!importDirectory.mkdirs()) return Resolution.Failure(Error.ImportFailed)
                val importedFile = File(importDirectory, displayName)
                val input = context.contentResolver.openInputStream(uri)
                    ?: return Resolution.Failure(Error.ImportFailed)
                input.use { source ->
                    importedFile.outputStream().use { destination ->
                        BoundedFileCopy.copy(source, destination, MAX_IMPORT_BYTES)
                    }
                }
                completed = true
                Resolution.Success(
                    AgentFileReference(
                        displayName = displayName,
                        absolutePath = importedFile.absolutePath,
                        kind = AgentFileReferenceKind.File,
                    ),
                )
            } catch (_: BoundedFileCopy.TooLargeException) {
                Resolution.Failure(Error.ImportTooLarge)
            } catch (_: SecurityException) {
                Resolution.Failure(Error.AccessDenied)
            } catch (_: IOException) {
                Resolution.Failure(Error.ImportFailed)
            } catch (_: RuntimeException) {
                Resolution.Failure(Error.ImportFailed)
            } finally {
                if (!completed) importDirectory.deleteRecursively()
            }
        }

        private fun documentDisplayName(context: Context, uri: Uri): String? = try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: RuntimeException) {
            // 名称是可选元数据；实际读取授权仍由 openInputStream 校验。
            null
        }

        internal fun safeImportName(name: String?): String = name.orEmpty()
            .map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
            .joinToString("")
            .take(80)
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "imported-file"

        fun mapPrimaryStorageDocument(authority: String?, documentId: String): String? {
            if (authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) return null
            if (documentId.hasUnsupportedControlCharacter()) return null
            val separator = documentId.indexOf(':')
            if (separator < 0 || !documentId.substring(0, separator).equals("primary", ignoreCase = true)) {
                return null
            }
            val relativePath = documentId.substring(separator + 1)
            if (
                relativePath.startsWith('/') ||
                relativePath.split('/').any { it == "." || it == ".." }
            ) {
                return null
            }
            return if (relativePath.isEmpty()) {
                SHARED_STORAGE_ROOT
            } else {
                "$SHARED_STORAGE_ROOT/$relativePath"
            }
        }

        private fun queryLocalDocumentPath(context: Context, uri: Uri): String? {
            queryDataColumn(context, uri)?.let { return it }
            if (
                uri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY &&
                uri.authority != MEDIA_DOCUMENTS_AUTHORITY
            ) {
                return null
            }
            val mediaUri = try {
                MediaStore.getMediaUri(context, uri)
            } catch (_: RuntimeException) {
                null
            } ?: return null
            return queryDataColumn(context, mediaUri)
        }

        @Suppress("DEPRECATION")
        private fun queryDataColumn(context: Context, uri: Uri): String? = try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
            )?.use { cursor ->
                val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (dataIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(dataIndex)) {
                    cursor.getString(dataIndex)
                } else {
                    null
                }
            }
        } catch (_: RuntimeException) {
            // 文档提供方可以拒绝非标准列；这表示它没有可引用的本地绝对路径。
            null
        }

        private fun validationCommand(path: String): String {
            val quotedPath = shellQuote(path)
            return buildString {
                append("[ \"\$(id -u)\" = 0 ] || exit ").append(EXIT_ROOT_UNAVAILABLE).append("; ")
                append("eta_path=\$(readlink -f ").append(quotedPath).append(" 2>/dev/null) || exit ")
                append(EXIT_PATH_NOT_FOUND).append("; ")
                append("[ -n \"\$eta_path\" ] || exit ").append(EXIT_PATH_NOT_FOUND).append("; ")
                append("if [ -f \"\$eta_path\" ]; then eta_kind=").append(KIND_FILE).append("; ")
                append("elif [ -d \"\$eta_path\" ]; then eta_kind=").append(KIND_DIRECTORY).append("; ")
                append("else exit ").append(EXIT_UNSUPPORTED_TYPE).append("; fi; ")
                append("printf '%s\\n%s' \"\$eta_kind\" \"\$eta_path\"")
            }
        }

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        private const val KIND_FILE = "file"
        private const val KIND_DIRECTORY = "directory"
        private const val EXIT_ROOT_UNAVAILABLE = 20
        private const val EXIT_PATH_NOT_FOUND = 21
        private const val EXIT_UNSUPPORTED_TYPE = 23
        private const val VALIDATION_TIMEOUT_MS = 5_000L
        private const val MAX_VALIDATION_OUTPUT_BYTES = 8 * 1024
    }
}
