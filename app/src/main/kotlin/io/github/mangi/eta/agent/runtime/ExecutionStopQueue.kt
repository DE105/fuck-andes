package io.github.mangi.eta.agent.runtime

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 服务销毁只封闭新提交；已经接纳的停止回调必须执行完，不能随服务协程一起取消。 */
internal class ExecutionStopQueue(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-execution-stop").apply { isDaemon = true }
    },
    private val onFailure: (Exception) -> Unit,
) {
    fun submit(callbacks: List<() -> Unit>, afterStop: () -> Unit = {}) {
        executor.execute {
            callbacks.forEach { callback ->
                try {
                    callback()
                } catch (failure: Exception) {
                    onFailure(failure)
                }
            }
            afterStop()
        }
    }

    fun close(callbacks: List<() -> Unit>) {
        submit(callbacks)
        executor.shutdown()
    }
}
