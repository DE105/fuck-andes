package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 入口进程侧的 Runtime 客户端。
 *
 * 它只负责把一次 Agent 请求交给模块进程，并把事件/结果带回入口适配层；
 * 不执行模型、不执行工具、不渲染 UI。
 */
internal class AgentRuntimeClient(
    private val context: Context,
    private val logger: AgentLogger
) {
    sealed interface AttachOutcome {
        data class Completed(val result: AgentRuntimeWire.RunResult) : AttachOutcome
        data object NotActive : AttachOutcome
        data object Unavailable : AttachOutcome
    }

    sealed interface ActiveRunQuery {
        data class Known(val runId: String?) : ActiveRunQuery
        data object Unavailable : ActiveRunQuery
    }

    sealed interface CompletedRunsQuery {
        data class Known(val runs: List<AgentRuntimeWire.CompletedRun>) : CompletedRunsQuery
        data object Unavailable : CompletedRunsQuery
    }

    fun run(
        request: AgentRuntimeWire.RunRequest,
        onEvent: (AgentEvent) -> Unit
    ): AgentRuntimeWire.RunResult {
        val resultLatch = CountDownLatch(1)
        val resultRef = AtomicReference<AgentRuntimeWire.RunResult?>()
        val preparedImagesRef = AtomicReference<AgentRuntimeImageTransfer.PreparedImages?>()
        val clientMessenger = Messenger(
            ClientHandler(
                onEvent = onEvent,
                onResult = { result ->
                    resultRef.set(result)
                    resultLatch.countDown()
                },
                onRequestIngested = {
                    preparedImagesRef.getAndSet(null)?.close()
                },
            )
        )

        val lease = AgentRuntimeConnection.acquire(context, logger)
            ?: return AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 服务绑定失败")
        val serviceMessenger = lease.messenger
        val deathRecipient = IBinder.DeathRecipient {
            if (resultRef.get() == null) {
                resultRef.set(
                    AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 服务连接已断开")
                )
                resultLatch.countDown()
            }
        }

        try {
            lease.binder.linkToDeath(deathRecipient, 0)
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_START_RUN)
            msg.replyTo = clientMessenger
            val preparedImages = AgentRuntimeImageTransfer.prepare(context, request.images)
            preparedImagesRef.set(preparedImages)
            msg.data = AgentRuntimeWire.toBundle(request, preparedImages.images)
            serviceMessenger.send(msg)
            // 最终结果或 Binder 断连负责唤醒；正常长任务不因客户端等待时长被取消。
            resultLatch.await()
            return resultRef.get() ?: AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 未返回结果")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching {
                val cancelMessage = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
                cancelMessage.data = AgentRuntimeWire.ackBundle(request.runId)
                serviceMessenger.send(cancelMessage)
            }
            return AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 等待被中断")
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime start request failed: type=${throwable.safeLogType()}")
            return AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = when (throwable) {
                    is AgentRuntimeWire.PayloadTooLargeException -> throwable.message
                    is AgentRuntimeImageTransfer.ImageTransferException -> throwable.message
                    else -> "Agent Runtime 请求发送失败（${throwable.safeLogType()}）"
                },
            )
        } finally {
            preparedImagesRef.getAndSet(null)?.close()
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
        }
    }

    fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        withRuntimeMessenger(Unit) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
        }
    }

    fun ackResult(runId: String): Boolean {
        if (runId.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ACK_RESULT)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
            true
        }
    }

    fun drainCompletedRuns(): List<AgentRuntimeWire.CompletedRun> {
        return when (val query = queryCompletedRuns()) {
            is CompletedRunsQuery.Known -> query.runs
            CompletedRunsQuery.Unavailable -> emptyList()
        }
    }

    fun queryCompletedRuns(): CompletedRunsQuery {
        val resultLatch = CountDownLatch(1)
        val resultRef = AtomicReference<List<AgentRuntimeWire.CompletedRun>>(emptyList())
        val clientMessenger = Messenger(
            DrainHandler { results ->
                resultRef.set(results)
                resultLatch.countDown()
            }
        )

        return withRuntimeMessenger<CompletedRunsQuery>(CompletedRunsQuery.Unavailable) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS)
            msg.replyTo = clientMessenger
            serviceMessenger.send(msg)
            if (resultLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                CompletedRunsQuery.Known(resultRef.get())
            } else {
                CompletedRunsQuery.Unavailable
            }
        }
    }

    fun queryActiveRun(): ActiveRunQuery {
        val responseLatch = CountDownLatch(1)
        val runIdRef = AtomicReference("")
        val clientMessenger = Messenger(
            ActiveRunHandler { runId ->
                runIdRef.set(runId)
                responseLatch.countDown()
            }
        )

        return withRuntimeMessenger<ActiveRunQuery>(ActiveRunQuery.Unavailable) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN)
            msg.replyTo = clientMessenger
            serviceMessenger.send(msg)
            if (!responseLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ActiveRunQuery.Unavailable
            } else {
                ActiveRunQuery.Known(runIdRef.get().takeIf(String::isNotBlank))
            }
        }
    }

    /** 历史一次性交给 onReplay；未指定时沿用 onEvent。后续新增事件始终交给 onEvent。 */
    fun attachRun(
        runId: String,
        onReplay: ((List<AgentEvent>) -> Unit)? = null,
        onEvent: (AgentEvent) -> Unit,
    ): AttachOutcome {
        if (runId.isBlank()) return AttachOutcome.NotActive
        val terminalLatch = CountDownLatch(1)
        val attachLatch = CountDownLatch(1)
        val attachedRef = AtomicReference<Boolean?>(null)
        val resultRef = AtomicReference<AgentRuntimeWire.RunResult?>()
        val clientMessenger = Messenger(
            AttachHandler(
                onReplay = onReplay,
                onEvent = onEvent,
                onAttachResponse = { attached ->
                    attachedRef.set(attached)
                    attachLatch.countDown()
                    if (!attached) terminalLatch.countDown()
                },
                onResult = { result ->
                    resultRef.set(result)
                    attachLatch.countDown()
                    terminalLatch.countDown()
                },
            )
        )
        val lease = AgentRuntimeConnection.acquire(context, logger)
            ?: return AttachOutcome.Unavailable
        val deathRecipient = IBinder.DeathRecipient {
            attachLatch.countDown()
            terminalLatch.countDown()
        }

        try {
            lease.binder.linkToDeath(deathRecipient, 0)
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN)
            msg.replyTo = clientMessenger
            msg.data = AgentRuntimeWire.ackBundle(runId)
            lease.messenger.send(msg)
            if (!attachLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return AttachOutcome.Unavailable
            }
            terminalLatch.await()
            resultRef.get()?.let { return AttachOutcome.Completed(it) }
            return if (attachedRef.get() == false) {
                AttachOutcome.NotActive
            } else {
                AttachOutcome.Unavailable
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return AttachOutcome.Unavailable
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime attach failed: type=${throwable.safeLogType()}")
            return AttachOutcome.Unavailable
        } finally {
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
        }
    }

    private fun <T> withRuntimeMessenger(defaultValue: T, block: (Messenger) -> T): T {
        val lease = AgentRuntimeConnection.acquire(context, logger) ?: return defaultValue
        try {
            return block(lease.messenger)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return defaultValue
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime service call failed: type=${throwable.safeLogType()}")
            return defaultValue
        } finally {
            lease.close()
        }
    }

    private class ClientHandler(
        private val onEvent: (AgentEvent) -> Unit,
        private val onResult: (AgentRuntimeWire.RunResult) -> Unit,
        private val onRequestIngested: () -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AgentRuntimeWire.MSG_EVENT -> {
                    AgentRuntimeWire.eventFromBundle(msg.data ?: return)?.let(onEvent)
                }

                AgentRuntimeWire.MSG_RESULT -> {
                    onResult(AgentRuntimeWire.runResultFromBundle(msg.data ?: return))
                }

                AgentRuntimeWire.MSG_REQUEST_INGESTED -> onRequestIngested()
            }
        }
    }

    private class DrainHandler(
        private val onResults: (List<AgentRuntimeWire.CompletedRun>) -> Unit
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE) {
                onResults(AgentRuntimeWire.completedRunsFromBundle(msg.data ?: return))
            }
        }
    }

    private class ActiveRunHandler(
        private val onResponse: (String) -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE) {
                onResponse(AgentRuntimeWire.runIdFromBundle(msg.data ?: return))
            }
        }
    }

    private class AttachHandler(
        onReplay: ((List<AgentEvent>) -> Unit)?,
        onEvent: (AgentEvent) -> Unit,
        onAttachResponse: (Boolean) -> Unit,
        onResult: (AgentRuntimeWire.RunResult) -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        private val delivery = AgentRuntimeAttachDelivery(
            onReplay = onReplay,
            onEvent = onEvent,
            onAttachResponse = onAttachResponse,
            onResult = onResult,
        )

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AgentRuntimeWire.MSG_EVENT ->
                    AgentRuntimeWire.eventFromBundle(msg.data ?: return)?.let(delivery::event)
                AgentRuntimeWire.MSG_RESULT ->
                    delivery.result(AgentRuntimeWire.runResultFromBundle(msg.data ?: return))
                AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE ->
                    delivery.attachResponse(AgentRuntimeWire.attachRunSucceeded(msg.data ?: return))
            }
        }
    }

    private companion object {
        const val RESPONSE_TIMEOUT_SECONDS = 8L
    }
}
