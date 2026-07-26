package fuck.andes.agent.runtime

import android.content.Context
import android.os.Bundle
import fuck.andes.agent.model.AgentConversationCodec
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.data.db.FuckAndesDatabase
import fuck.andes.data.db.RuntimeArchiveEventEntity
import fuck.andes.data.db.RuntimeArchiveRunEntity
import fuck.andes.data.db.RuntimeArchiveRunWithEvents
import fuck.andes.data.db.RuntimeArchiveRunWithEventsSeed
import fuck.andes.data.db.RuntimeRunDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-persistent archive for externally initiated runs that should later be
 * mirrored into the module's own chat history.
 *
 * Unlike [AgentRuntimeResultStore], entries here are not an entry-adapter retry
 * queue. They preserve the event trace so the first-party UI can reconstruct
 * thinking and tool activity that third-party assistant surfaces cannot show.
 */
internal object AgentRunArchiveStore {
    private const val MAX_ARCHIVED = 32
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    data class ArchivedRun(
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val result: AgentRuntimeWire.RunResult,
        val createdAt: Long,
    )

    fun add(context: Context, run: ArchivedRun) {
        val appContext = context.applicationContext
        runBlocking(Dispatchers.IO) {
            val dao = FuckAndesDatabase.get(appContext).runtimeRunDao()
            val compacted = run.copy(events = compactEvents(run.events))
            val archiveRunId = compacted.archiveRunId
            dao.replaceArchivedRun(
                run = compacted.toEntity(archiveRunId),
                events = compacted.toEventEntities(archiveRunId),
            )
            prune(dao)
        }
    }

    fun list(context: Context): List<ArchivedRun> {
        val appContext = context.applicationContext
        return runBlocking(Dispatchers.IO) {
            prune(FuckAndesDatabase.get(appContext).runtimeRunDao())
                .mapNotNull { it.toDomain() }
        }
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        val appContext = context.applicationContext
        runBlocking(Dispatchers.IO) {
            FuckAndesDatabase.get(appContext)
                .runtimeRunDao()
                .deleteArchivedRun(runId)
        }
    }

    private suspend fun prune(dao: RuntimeRunDao): List<RuntimeArchiveRunWithEvents> {
        val now = System.currentTimeMillis()
        val pruned = dao.archivedRuns()
            .filter { now - it.run.createdAt <= MAX_AGE_MS }
            .sortedBy { it.run.createdAt }
            .takeLast(MAX_ARCHIVED)
        dao.replaceArchivedRuns(pruned.map { it.toSeed() })
        return dao.archivedRuns()
    }

    private val ArchivedRun.archiveRunId: String
        get() = result.runId.ifBlank { handoff.id }

    private fun ArchivedRun.toEntity(archiveRunId: String): RuntimeArchiveRunEntity =
        RuntimeArchiveRunEntity(
            archiveRunId = archiveRunId,
            runId = result.runId,
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

    private fun ArchivedRun.toEventEntities(archiveRunId: String): List<RuntimeArchiveEventEntity> =
        events.mapIndexed { index, event ->
            RuntimeArchiveEventEntity(
                archiveRunId = archiveRunId,
                sortIndex = index,
                eventJson = bundleToJson(AgentRuntimeWire.eventToBundle(event)).toString(),
            )
        }

    private fun RuntimeArchiveRunWithEvents.toDomain(): ArchivedRun? =
        runCatching {
            ArchivedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = run.handoffId,
                    source = run.handoffSource,
                    payload = run.handoffPayload,
                    dismissEntrySurfaceOnForegroundOperation = run.dismissEntrySurface,
                ),
                result = AgentRuntimeWire.RunResult(
                    runId = run.runId.ifBlank { run.archiveRunId },
                    ok = run.ok,
                    content = run.content,
                    error = run.error,
                    reasoningContent = run.reasoningContent,
                    transcript = AgentConversationCodec.decodeTranscript(run.transcriptJson).ifEmpty {
                        if (!run.ok || run.content.isBlank()) return@ifEmpty emptyList()
                        listOf(
                            AgentModelClient.ConversationMessage(
                                role = "assistant",
                                content = run.content,
                                reasoningContent = run.reasoningContent,
                            )
                        )
                    },
                    metrics = AgentRunMetrics(
                        inputTokens = run.runInputTokens,
                        cachedInputTokens = run.runCachedInputTokens,
                        outputTokens = run.runOutputTokens,
                        elapsedMs = run.runElapsedMs,
                    ).takeUnless { it.isEmpty },
                ),
                createdAt = run.createdAt,
                events = events
                    .sortedBy { it.sortIndex }
                    .mapNotNull { event ->
                        runCatching {
                            AgentRuntimeWire.eventFromBundle(jsonToBundle(JSONObject(event.eventJson)))
                        }.getOrNull()
                    },
            )
        }.getOrNull()

    private fun RuntimeArchiveRunWithEvents.toSeed(): RuntimeArchiveRunWithEventsSeed =
        RuntimeArchiveRunWithEventsSeed(
            run = run,
            events = events
                .sortedBy { it.sortIndex }
                .map { it.copy(id = 0) },
        )

    @Suppress("DEPRECATION")
    private fun bundleToJson(bundle: Bundle): JSONObject =
        JSONObject().also { json ->
            bundle.keySet().forEach { key ->
                when (val value = bundle.get(key)) {
                    is String -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                    is Int -> json.put(key, value)
                    is Long -> json.put(key, value)
                    is ArrayList<*> -> json.put(key, JSONArray(value))
                    null -> json.put(key, JSONObject.NULL)
                }
            }
        }

    private fun jsonToBundle(json: JSONObject): Bundle =
        Bundle().also { bundle ->
            json.keys().forEach { key ->
                when (val value = json.opt(key)) {
                    is String -> bundle.putString(key, value)
                    is Boolean -> bundle.putBoolean(key, value)
                    is Int -> bundle.putInt(key, value)
                    is Long -> bundle.putLong(key, value)
                    is JSONArray -> bundle.putStringArrayList(
                        key,
                        ArrayList((0 until value.length()).map { index -> value.optString(index) }),
                    )
                }
            }
        }

    private fun compactEvents(events: List<AgentEvent>): List<AgentEvent> {
        val compacted = mutableListOf<AgentEvent>()
        events.forEach { event ->
            val previous = compacted.lastOrNull()
            val merged = when {
                previous is AgentEvent.AssistantBlockDelta &&
                    event is AgentEvent.AssistantBlockDelta &&
                    previous.round == event.round -> {
                    if (previous.kind == event.kind && previous.index == event.index) {
                        previous.copy(
                            delta = previous.delta + event.delta,
                            deltaChars = previous.deltaChars + event.deltaChars,
                        )
                    } else {
                        null
                    }
                }

                else -> null
            }
            if (merged != null) {
                compacted[compacted.lastIndex] = merged
            } else {
                compacted += event
            }
        }
        return compacted
    }
}
