package fuck.andes.agent.runtime

import android.content.Context
import fuck.andes.agent.model.AgentConversationCodec
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.data.db.FuckAndesDatabase
import fuck.andes.data.db.RuntimeResultEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Stores completed runs until an entry adapter confirms that the result was shown.
 *
 * This is a short-lived delivery queue, not user-visible chat history, so it remains
 * deliberately bounded by age and count.
 */
internal object AgentRuntimeResultStore {
    private const val MAX_PENDING = 8
    private const val MAX_AGE_MS = 12L * 60L * 60L * 1000L
    private const val MAX_RECENT_ACKNOWLEDGEMENTS = 32

    private val deliveryLock = Any()
    private val recentlyAcknowledgedRunIds = LinkedHashMap<String, Long>()

    /**
     * 返回 false 表示同一 run 已先收到 ACK，不应在 ACK 之后重新写回待交付队列。
     */
    fun add(context: Context, completedRun: AgentRuntimeWire.CompletedRun): Boolean {
        val appContext = context.applicationContext
        val entity = completedRun.toEntity()
        synchronized(deliveryLock) {
            pruneAcknowledgements(System.currentTimeMillis())
            if (recentlyAcknowledgedRunIds.containsKey(entity.runId)) return false
            runBlocking(Dispatchers.IO) {
                val dao = FuckAndesDatabase.get(appContext).runtimeRunDao()
                dao.upsertRuntimeResult(entity)
                prune(dao)
            }
            return true
        }
    }

    fun list(context: Context): List<AgentRuntimeWire.CompletedRun> {
        val appContext = context.applicationContext
        return synchronized(deliveryLock) {
            runBlocking(Dispatchers.IO) {
                prune(FuckAndesDatabase.get(appContext).runtimeRunDao())
                    .map { it.toDomain() }
            }
        }
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        val appContext = context.applicationContext
        synchronized(deliveryLock) {
            runBlocking(Dispatchers.IO) {
                FuckAndesDatabase.get(appContext)
                    .runtimeRunDao()
                    .deleteRuntimeResult(runId)
            }
            rememberAcknowledgement(runId, System.currentTimeMillis())
        }
    }

    private fun rememberAcknowledgement(runId: String, now: Long) {
        pruneAcknowledgements(now)
        recentlyAcknowledgedRunIds.remove(runId)
        while (recentlyAcknowledgedRunIds.size >= MAX_RECENT_ACKNOWLEDGEMENTS) {
            val oldestRunId = recentlyAcknowledgedRunIds.keys.firstOrNull() ?: break
            recentlyAcknowledgedRunIds.remove(oldestRunId)
        }
        recentlyAcknowledgedRunIds[runId] = now
    }

    private fun pruneAcknowledgements(now: Long) {
        recentlyAcknowledgedRunIds.entries.removeAll { (_, acknowledgedAt) ->
            now - acknowledgedAt > MAX_AGE_MS
        }
    }

    private suspend fun prune(dao: fuck.andes.data.db.RuntimeRunDao): List<RuntimeResultEntity> {
        val now = System.currentTimeMillis()
        val pruned = dao.runtimeResults()
            .filter { now - it.createdAt <= MAX_AGE_MS }
            .sortedBy { it.createdAt }
            .takeLast(MAX_PENDING)
        dao.replaceRuntimeResults(pruned)
        return pruned
    }

    private fun AgentRuntimeWire.CompletedRun.toEntity(): RuntimeResultEntity {
        val stableRunId = result.runId.ifBlank { handoff.id }
        return RuntimeResultEntity(
            runId = stableRunId,
            handoffId = handoff.id,
            handoffSource = handoff.source,
            handoffPayload = handoff.payload,
            dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
            ok = result.ok,
            content = result.content,
            error = result.error,
            reasoningContent = result.reasoningContent,
            transcriptJson = AgentConversationCodec.encodeTranscriptForStorage(result.transcript),
            runInputTokens = result.metrics?.inputTokens,
            runCachedInputTokens = result.metrics?.cachedInputTokens,
            runOutputTokens = result.metrics?.outputTokens,
            runElapsedMs = result.metrics?.elapsedMs,
            createdAt = createdAt,
        )
    }

    private fun RuntimeResultEntity.toDomain(): AgentRuntimeWire.CompletedRun =
        AgentRuntimeWire.CompletedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = handoffId,
                source = handoffSource,
                payload = handoffPayload,
                dismissEntrySurfaceOnForegroundOperation = dismissEntrySurface,
            ),
            result = AgentRuntimeWire.RunResult(
                runId = runId,
                ok = ok,
                content = content,
                error = error,
                reasoningContent = reasoningContent,
                transcript = legacyCompatibleTranscript(
                    raw = transcriptJson,
                    ok = ok,
                    content = content,
                    reasoningContent = reasoningContent,
                ),
                metrics = AgentRunMetrics(
                    inputTokens = runInputTokens,
                    cachedInputTokens = runCachedInputTokens,
                    outputTokens = runOutputTokens,
                    elapsedMs = runElapsedMs,
                ).takeUnless { it.isEmpty },
            ),
            createdAt = createdAt,
        )

    private fun legacyCompatibleTranscript(
        raw: String,
        ok: Boolean,
        content: String,
        reasoningContent: String,
    ): List<AgentModelClient.ConversationMessage> =
        AgentConversationCodec.decodeTranscript(raw).ifEmpty {
            if (!ok || content.isBlank()) return@ifEmpty emptyList()
            listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = content,
                    reasoningContent = reasoningContent,
                )
            )
        }
}
