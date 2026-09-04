package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * 单次 Runtime run 的阻塞执行器。
 *
 * 它只拥有模型、工具和终态提交，不持有 Service、Messenger、Compose 或 WindowManager 状态。
 * 所有外部副作用都通过窄回调交回宿主。
 */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val onAcceptedEvent: (AgentEvent, EntrySurfaceGuard?) -> Unit,
    private val persistArtifacts: (
        AgentRuntimeWire.RunRequest,
        AgentRuntimeWire.RunResult,
        List<AgentEvent>,
    ) -> Unit,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val entrySurfaceGuard: EntrySurfaceGuard?,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )

    private val appContext = context.applicationContext

    fun execute(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): Outcome {
        val runController = session.controller
        val archivedEvents = mutableListOf<AgentEvent>()
        var entrySurfaceGuard: EntrySurfaceGuard? = null
        var toolExecutor: AutoCloseable? = null
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        val timing = AgentRunTiming(AndroidAgentLogger)

        val result = try {
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
            entrySurfaceGuard = EntrySurfaceGuard.from(
                handoff = request.handoff,
                logger = AndroidAgentLogger,
                etaVoiceSurfaceDismissal = {
                    EtaAssistantOverlayService.dismissForForegroundOperation(appContext)
                },
            )
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillLoader = SkillRuntime.createLoader(appContext)
            val skillResourceReader = SkillRuntime.createResourceReader(appContext)
            val skillPackageInstaller = SkillRuntime.createPackageInstaller(appContext)
            val githubSkillSource = PublicGitHubSkillSource(
                cacheRoot = appContext.cacheDir,
                baseClient = AgentHttpClient.client,
            )
            val skillContext = SkillContext(
                installedSkills = skillIndexService.listInstalledSkills()
                    .filter { SkillCompatibilityChecker.evaluate(it).available },
            )
            val memoryEnabled = runBlocking { AgentMemoryRepository.isEnabled() }
            val memoryContext = if (memoryEnabled) {
                runCatching {
                    AgentMemoryContextBuilder.build(
                        snapshot = AgentMemoryRepository.snapshot(),
                        contextWindow = request.config.contextWindow,
                    )
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_context_failed") {
                        "Agent memory context unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentMemoryContextBuilder.empty(request.config.contextWindow)
                }
            } else {
                AgentMemoryContext.DISABLED
            }
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = runBlocking {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") {
                        "MCP tool snapshot unavailable: type=${throwable.safeLogType()}"
                    }
                    McpRunSnapshot.EMPTY
                }
            }
            val mcpTools = JSONArray().also(mcpSnapshot::appendModelTools)
            val executor = AgentLocalTools(
                context = appContext,
                logger = AndroidAgentLogger,
                browserRunId = request.runId,
                browserToolsEnabled = {
                    request.config.browserTools && currentPermissions().browserTools
                },
                terminalToolsEnabled = {
                    request.config.terminalTools && currentPermissions().terminalTools
                },
                deviceDirectToolsEnabled = {
                    request.config.deviceDirectTools && currentPermissions().deviceDirectTools
                },
                deviceSensitiveReadToolsEnabled = {
                    request.config.deviceSensitiveReadTools &&
                        currentPermissions().deviceSensitiveReadTools
                },
                deviceSensitiveActionToolsEnabled = {
                    request.config.deviceSensitiveActionTools &&
                        currentPermissions().deviceSensitiveActionTools
                },
                memoryToolsEnabled = {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                },
                screenshotExcludedPackages = {
                    entrySurfaceGuard?.consumeScreenshotExcludedPackages().orEmpty()
                },
                beforeToolExecution = { toolName ->
                    val requiresAccessibility =
                        AgentToolRequirements.requiresAccessibility(toolName)
                    if (
                        !requiresAccessibility &&
                        !AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(toolName)
                    ) {
                        ToolExecutionDecision.Allow
                    } else {
                        val accessibility = if (requiresAccessibility) {
                            AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext)
                        } else {
                            null
                        }
                        when {
                            accessibility != null && !accessibility.available ->
                                ToolExecutionDecision.Reject(
                                    code = accessibility.code,
                                    message = accessibility.message,
                                )
                            entrySurfaceGuard?.dismissOnce() == false ->
                                ToolExecutionDecision.Reject(
                                    code = "ENTRY_SURFACE_NOT_READY",
                                    message = "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用",
                                )
                            else -> ToolExecutionDecision.Allow
                        }
                    }
                },
                skillIndexService = skillIndexService,
                skillLoader = skillLoader,
                skillResourceReader = skillResourceReader,
                githubSkillSource = githubSkillSource,
                skillPackageInstaller = skillPackageInstaller,
                runAvailableSkillIds = skillContext.installedSkills.mapTo(mutableSetOf()) { it.id },
                pendingSkillConflict = pendingSkillConflict,
            )
            val routingExecutor = RoutingToolExecutor(
                local = executor,
                mcp = McpToolExecutor(mcpSnapshot),
            )
            toolExecutor = routingExecutor
            toolsBinding = runController.register(routingExecutor::close)
            timing.preparationFinished(skillContext.installedSkills.size)
            val completedResponse = AgentModelClient.complete(
                config = request.config,
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                prompt = request.prompt,
                toolExecutor = routingExecutor,
                images = request.images,
                history = request.history,
                runController = runController,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = mcpTools,
            ) { event ->
                timing.accept(event)
                acceptEvent(
                    session,
                    event,
                    archivedEvents,
                    entrySurfaceGuard,
                    checkpointRecorder,
                )
            }
            response = completedResponse
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent,
                transcript = completedResponse.transcript,
            )
        } catch (throwable: Throwable) {
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException
            val modelFailure = throwable as? AgentModelExecutionException
            val message = if (cancelled) {
                "已停止"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                AndroidAgentLogger.info("Agent runtime stopped")
            } else {
                AndroidAgentLogger.error(
                    "Agent runtime failed: type=${throwable.safeLogType()}"
                )
                val event = AgentEvent.RunFailed(message)
                runCatching {
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                }.onFailure { checkpointFailure ->
                    AndroidAgentLogger.error(
                        "Agent runtime failure checkpoint failed: " +
                            "type=${checkpointFailure.safeLogType()}"
                    )
                    session.emit(event)
                }
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = message,
                reasoningContent = modelFailure?.reasoningContent.orEmpty(),
                transcript = modelFailure?.transcript.orEmpty(),
            )
        } finally {
            runCatching { toolsBinding?.close() }
            runCatching { toolExecutor?.close() }
        }

        if (cancelled) {
            runCatching { checkpointRecorder?.discard() }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime cancelled checkpoint cleanup failed: " +
                        "type=${throwable.safeLogType()}"
                )
            }
            session.cancel("已停止")
            return Outcome(
                result = result,
                entrySurfaceGuard = entrySurfaceGuard,
                shouldUpdateHost = true,
            )
        }

        val completedRequest = runCatching { snapshotRequest(request) }
            .getOrElse { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime request snapshot failed: type=${throwable.safeLogType()}"
                )
                request
            }
        val committed = session.complete(result) {
            runCatching { checkpointRecorder?.seal() }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}"
                    )
                }
            runCatching { persistArtifacts(completedRequest, result, archivedEvents) }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime artifact persistence failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        return Outcome(
            result = result,
            entrySurfaceGuard = entrySurfaceGuard,
            completedRequest = completedRequest.takeIf { committed },
            response = response.takeIf { committed },
            shouldUpdateHost = committed,
        )
    }

    private fun acceptEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>,
        entrySurfaceGuard: EntrySurfaceGuard?,
        checkpointRecorder: AgentRunCheckpointRecorder?,
    ) {
        checkpointRecorder?.accept(event)
        if (!session.emit(event)) return
        archivedEvents += event
        if (event !is AgentEvent.AssistantBlockDelta) {
            AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        }
        runCatching { onAcceptedEvent(event, entrySurfaceGuard) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                    "Agent runtime event projection failed: type=${throwable.safeLogType()}"
                }
            }
    }
}
