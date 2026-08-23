package fuck.andes.agent.device

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import fuck.andes.agent.model.AgentFileReference
import fuck.andes.agent.model.AgentFileReferenceKind
import fuck.andes.agent.model.hasUnsupportedControlCharacter
import fuck.andes.core.AgentLogger
import java.io.File

internal class AgentFileReferenceGateway(
    private val resolveDocumentPath: (Uri) -> String? = { null },
    private val executeRootCommand: (String) -> BoundedRootCommandExecutor.Result,
    private val copyDocumentContent: (Uri, AgentFileReferenceKind) -> String? = { _, _ -> null },
    private val logger: AgentLogger = NoOpAgentLogger,
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
        },
        logger = logger,
    )

    constructor(
        context: Context,
        logger: AgentLogger,
    ) : this(
        resolveDocumentPath = { uri -> queryLocalDocumentPath(context.applicationContext, uri) },
        executeRootCommand = { command ->
            BoundedRootCommandExecutor(logger).use { executor ->
                executor.execute(
                    command = command,
                    timeoutMillis = VALIDATION_TIMEOUT_MS,
                    maxOutputBytes = MAX_VALIDATION_OUTPUT_BYTES,
                )
            }
        },
        copyDocumentContent = { uri, kind ->
            copySafDocumentToTemporaryRoot(
                context.applicationContext,
                uri,
                kind,
                logger = logger,
            ) { command ->
                BoundedRootCommandExecutor(logger).use { executor ->
                    executor.execute(
                        command = command,
                        timeoutMillis = SAF_COPY_TIMEOUT_MS,
                        maxOutputBytes = MAX_VALIDATION_OUTPUT_BYTES,
                    )
                }
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
        if (documentId == null) {
            logger.debug {
                "resolveDocumentUri: 无法解析 document id uri=$uri authority=${uri.authority} kind=$expectedKind"
            }
            return Resolution.Failure(Error.UnsupportedDocumentProvider)
        }
        mapPrimaryStorageDocument(uri.authority, documentId)?.let { mapped ->
            logger.debug {
                "resolveDocumentUri: 主存储卷映射成功 uri=$uri documentId=$documentId path=$mapped"
            }
            return resolveAbsolutePath(mapped, expectedKind)
        }
        resolveDocumentPath(uri)?.let { path ->
            logger.debug {
                "resolveDocumentUri: 本地路径查询成功 uri=$uri path=$path"
            }
            return resolveAbsolutePath(path, expectedKind)
        }
        copyDocumentContent(uri, expectedKind)?.let { path ->
            logger.debug {
                "resolveDocumentUri: SAF 拷贝兜底成功 uri=$uri kind=$expectedKind path=$path"
            }
            return resolveAbsolutePath(path, expectedKind)
        }
        logger.debug {
            "resolveDocumentUri: 所有解析器均失败 uri=$uri authority=${uri.authority} documentId=$documentId kind=$expectedKind"
        }
        return Resolution.Failure(Error.UnsupportedDocumentProvider)
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
        val result = executeRootCommand(validationCommand(path))
        if (!result.ok) {
            return Resolution.Failure(
                when {
                    result.errorCode == "ROOT_UNAVAILABLE" -> Error.RootUnavailable
                    result.timedOut -> Error.ValidationTimedOut
                    result.exitCode == EXIT_ROOT_UNAVAILABLE -> Error.RootUnavailable
                    result.exitCode == EXIT_OUTSIDE_ALLOWED_ROOTS -> Error.OutsideAllowedRoots
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
            canonicalPath.hasUnsupportedControlCharacter() ||
            !isWithinAllowedRoots(canonicalPath)
        ) {
            return Resolution.Failure(Error.OutsideAllowedRoots)
        }
        if (expectedKind != null && kind != expectedKind) {
            return Resolution.Failure(Error.TypeMismatch)
        }
        return Resolution.Success(
            AgentFileReference(
                displayName = canonicalPath.substringAfterLast('/').ifBlank {
                    if (canonicalPath == SHARED_STORAGE_ROOT) "内部存储" else "临时目录"
                },
                absolutePath = canonicalPath,
                kind = kind,
            )
        )
    }

    internal enum class Error {
        UnsupportedDocumentProvider,
        InvalidPath,
        OutsideAllowedRoots,
        PathNotFound,
        UnsupportedFileType,
        TypeMismatch,
        RootUnavailable,
        ValidationTimedOut,
    }

    internal sealed interface Resolution {
        data class Success(val reference: AgentFileReference) : Resolution
        data class Failure(val error: Error) : Resolution
    }

    internal companion object {
        const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
        const val SHARED_STORAGE_ROOT = "/storage/emulated/0"
        const val TEMPORARY_ROOT = "/data/local/tmp"

        private const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"
        private const val DOWNLOADS_DOCUMENTS_AUTHORITY = "com.android.providers.downloads.documents"
        private const val SAF_COPY_DIRECTORY = "eta-saf-cache"
        private const val SAF_COPY_TIMEOUT_MS = 60_000L

        fun mapPrimaryStorageDocument(authority: String?, documentId: String): String? {
            if (authority == DOWNLOADS_DOCUMENTS_AUTHORITY) {
                if (documentId.hasUnsupportedControlCharacter()) return null
                if (!documentId.startsWith("raw:")) return null
                val rawPath = documentId.substringAfter("raw:")
                if (
                    rawPath.isEmpty() ||
                    !rawPath.startsWith('/') ||
                    rawPath.split('/').any { it == "." || it == ".." }
                ) {
                    return null
                }
                return rawPath
            }
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

        private fun copySafDocumentToTemporaryRoot(
            context: Context,
            uri: Uri,
            expectedKind: AgentFileReferenceKind,
            logger: AgentLogger,
            executeRootCommand: (String) -> BoundedRootCommandExecutor.Result,
        ): String? {
            if (expectedKind != AgentFileReferenceKind.File) {
                logger.debug {
                    "copySafDocumentToTemporaryRoot: 目录不支持拷贝 kind=$expectedKind uri=$uri"
                }
                return null
            }
            val displayName = queryDisplayName(context.contentResolver, uri)
                ?.let { sanitizeFileName(it) }
                ?: "saf-file"
            val cacheFile = File(context.cacheDir, "saf-copy-${System.currentTimeMillis()}")
            val copied = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
            } catch (_: RuntimeException) {
                false
            }
            if (!copied) {
                logger.debug {
                    "copySafDocumentToTemporaryRoot: 无法读取内容 uri=$uri displayName=$displayName"
                }
                return null
            }
            val targetDir = "$TEMPORARY_ROOT/$SAF_COPY_DIRECTORY"
            val targetPath = "$targetDir/${System.currentTimeMillis()}-$displayName"
            val result = executeRootCommand(
                "mkdir -p ${shellQuote(targetDir)} && cp ${shellQuote(cacheFile.absolutePath)} ${shellQuote(targetPath)}"
            )
            if (!result.ok) {
                logger.debug {
                    "copySafDocumentToTemporaryRoot: root 拷贝失败 exit=${result.exitCode} " +
                        "timedOut=${result.timedOut} stderr=${result.stderr} target=$targetPath"
                }
                return null
            }
            return targetPath
        }

        private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        } catch (_: RuntimeException) {
            null
        }

        private fun sanitizeFileName(name: String): String =
            name.replace(Regex("""[/\\\u0000-\u001F\u007F]"""), "_").trim().take(180)

        internal fun isWithinAllowedRoots(path: String): Boolean =
            path == SHARED_STORAGE_ROOT ||
                path.startsWith("$SHARED_STORAGE_ROOT/") ||
                path == TEMPORARY_ROOT ||
                path.startsWith("$TEMPORARY_ROOT/")

        private fun validationCommand(path: String): String {
            val quotedPath = shellQuote(path)
            return buildString {
                append("[ \"\$(id -u)\" = 0 ] || exit ").append(EXIT_ROOT_UNAVAILABLE).append("; ")
                append("eta_path=\$(readlink -f ").append(quotedPath).append(" 2>/dev/null) || exit ")
                append(EXIT_PATH_NOT_FOUND).append("; ")
                append("[ -n \"\$eta_path\" ] || exit ").append(EXIT_PATH_NOT_FOUND).append("; ")
                append("case \"\$eta_path\" in ")
                append(SHARED_STORAGE_ROOT).append('|').append(SHARED_STORAGE_ROOT).append("/*|")
                append(TEMPORARY_ROOT).append('|').append(TEMPORARY_ROOT).append("/*) ;; ")
                append("*) exit ").append(EXIT_OUTSIDE_ALLOWED_ROOTS).append(" ;; esac; ")
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
        private const val EXIT_OUTSIDE_ALLOWED_ROOTS = 22
        private const val EXIT_UNSUPPORTED_TYPE = 23
        private const val VALIDATION_TIMEOUT_MS = 5_000L
        private const val MAX_VALIDATION_OUTPUT_BYTES = 8 * 1024
    }
}

private object NoOpAgentLogger : AgentLogger {
    override fun debug(message: () -> String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, throwable: Throwable?) = Unit
}
