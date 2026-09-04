package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import java.io.File

internal enum class LinuxExecutionBackend(val wireName: String) {
    CHROOT("chroot"),
    PROOT("proot"),
}

/** App 进程的终端运行条件；读取能力不会触发 su 授权。 */
internal object TerminalRuntime {
    @Volatile private var appContext: Context? = null
    fun acquireUserTask(id: String, onStop: () -> Unit): Boolean =
        appContext?.let { AgentExecutionService.acquire(it, id, onStop = onStop) } ?: true

    fun releaseUserTask(id: String) {
        if (appContext != null) AgentExecutionService.release(id)
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        File(userWorkspacePath).mkdirs()
    }

    val rootAvailable: Boolean get() = RootAccess.isGranted
    val publicStorageGranted: Boolean get() = appContext != null && android.os.Environment.isExternalStorageManager()
    val nativeLibraryDir: File? get() = appContext?.applicationInfo?.nativeLibraryDir?.let(::File)
    val userWorkspacePath: String get() = appContext?.let {
        File(it.filesDir, "terminal/workspace").absolutePath
    } ?: File(System.getProperty("java.io.tmpdir"), "eta-terminal-workspace").absolutePath
    val temporaryDirectory: File get() = appContext?.let { File(it.cacheDir, "terminal/proot") }
        ?: File(System.getProperty("java.io.tmpdir"), "eta-proot")

    fun defaultIdentity(environment: TerminalEnvironment, rootfsPath: String? = null): String = when {
        environment.isLinux -> if (LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT) "user" else "root"
        rootAvailable -> "root"
        else -> "user"
    }

    fun workspace(identity: String): String =
        if (identity == "root") "/data/local/tmp/eta" else userWorkspacePath

    fun nativeExecutable(name: String): File? = nativeLibraryDir?.let { File(it, name) }
        ?.takeIf { it.isFile && it.canExecute() }
}
