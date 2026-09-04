package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 终端会话属于运行任务；Activity 重建或退出只断开界面，不终止用户启动的进程。 */
internal class TerminalSessionHost private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val terminal = UserTerminalStore(context.applicationContext, scope)
    val console = ConsoleStore(context.applicationContext, scope)

    companion object {
        @Volatile private var instance: TerminalSessionHost? = null
        fun get(context: Context): TerminalSessionHost = instance ?: synchronized(this) {
            instance ?: TerminalSessionHost(context).also { instance = it }
        }
    }
}

/** 先取得前台执行资格，进程建立后再绑定 session，覆盖两者之间用户按停止的竞态。 */
internal class TerminalSessionLease private constructor(
    private val id: String,
    private val onStop: (String) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    private val session = AtomicReference<String?>(null)

    fun attach(sessionId: String): Boolean {
        session.set(sessionId)
        return !stopped.get()
    }

    fun release() { AgentExecutionService.release(id) }

    companion object {
        fun acquire(context: Context, onStop: (String) -> Unit): TerminalSessionLease? {
            val lease = TerminalSessionLease("terminal-ui:${UUID.randomUUID()}", onStop)
            return lease.takeIf {
                AgentExecutionService.acquire(context, lease.id) {
                    lease.stopped.set(true)
                    lease.session.get()?.let(lease.onStop)
                }
            }
        }
    }
}
