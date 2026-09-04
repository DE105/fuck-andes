package io.github.mangi.eta.agent.terminal

import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

/** 普通身份只访问终端工作区、免 Root 环境和 Android 已授权的共享存储。 */
internal object UserFileAccess {
    fun resolve(path: String): File {
        val workspace = File(TerminalRuntime.userWorkspacePath)
        val raw = path.trim().ifBlank { workspace.absolutePath }
        val file = when {
            raw == "~" -> workspace
            raw.startsWith("~/") -> File(workspace, raw.removePrefix("~/"))
            raw.startsWith('/') -> File(raw)
            else -> File(workspace, raw)
        }.canonicalFile
        val roots = listOf(workspace, File(workspace.parentFile, "proot"), File("/storage/emulated/0"))
        require(roots.any { root -> file.toPath().startsWith(root.canonicalFile.toPath()) }) { "路径不在普通终端可访问范围内" }
        return file
    }

    fun read(path: String, offsetBytes: Int, maxBytes: Int): String = operation {
        val file = resolve(path)
        require(file.isFile && file.canRead()) { "文件不可读取" }
        val offset = offsetBytes.coerceAtLeast(0)
        val limit = maxBytes.coerceIn(1, 16_000)
        val bytes = RandomAccessFile(file, "r").use { input ->
            input.seek(offset.toLong())
            ByteArray(minOf(limit.toLong(), (input.length() - offset).coerceAtLeast(0)).toInt()).also { input.readFully(it) }
        }
        val content = bytes.decodeToString()
        JSONObject().put("ok", true).put("tool", "read_file").put("path", file.absolutePath)
            .put("offset_bytes", offset).put("bytes_read", bytes.size).put("truncated", file.length() > offset.toLong() + bytes.size || content.length > 16_000)
            .put("content", content.take(16_000))
    }

    fun write(path: String, content: String, append: Boolean): String = operation {
        val file = resolve(path)
        val bytes = content.toByteArray()
        require(bytes.size <= 512 * 1024) { "写入内容过大" }
        require(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) { "目录不可创建" }
        require(!file.exists() || file.isFile) { "目标不是普通文件" }
        java.io.FileOutputStream(file, append).use { it.write(bytes) }
        JSONObject().put("ok", true).put("tool", "write_file").put("path", file.absolutePath)
            .put("mode", if (append) "append" else "overwrite").put("bytes_written", bytes.size)
    }

    fun list(path: String, showHidden: Boolean, limit: Int): String = operation {
        val directory = resolve(path)
        val entries = requireNotNull(directory.listFiles()) { "目录不可读取" }
            .filter { showHidden || !it.name.startsWith('.') }.sortedBy { it.name }
        val selected = entries.take(limit.coerceIn(1, 200))
        val text = selected.joinToString("\n") { (if (it.isDirectory) "d " else "- ") + it.name }
        JSONObject().put("ok", true).put("tool", "list_directory").put("path", directory.absolutePath)
            .put("exit_code", 0).put("stderr", "").put("truncated", selected.size < entries.size || text.length > 16_000)
            .put("entries_text", text.take(16_000))
    }

    private inline fun operation(block: () -> JSONObject): String = try {
        block().toString()
    } catch (_: java.io.IOException) {
        error("FILE_ACCESS_DENIED", "文件不可访问，请检查路径和文件授权")
    } catch (_: SecurityException) {
        error("FILE_ACCESS_DENIED", "文件访问未授权")
    } catch (_: IllegalArgumentException) {
        error("INVALID_PATH", "路径或文件参数不在允许范围内")
    }

    private fun error(code: String, message: String): String = JSONObject().put("ok", false).put("code", code).put("message", message).toString()
}
