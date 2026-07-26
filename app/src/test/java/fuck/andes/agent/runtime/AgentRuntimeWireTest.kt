package fuck.andes.agent.runtime

import android.graphics.Bitmap
import android.os.Parcel
import android.util.Base64
import fuck.andes.agent.media.AgentImageCodec
import fuck.andes.agent.model.AgentModelClient
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRuntimeWireTest {
    @Test
    fun oversizedLegacyInlineImageRequestIsRejectedBeforeMessengerSend() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-large-image",
            prompt = "分析图片",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            images = listOf(
                AgentModelClient.ModelImage(
                    reference = "data:image/png;base64,${"A".repeat(500_000)}",
                    mimeType = "image/png",
                    bytes = 375_000,
                )
            ),
        )

        assertThrows(AgentRuntimeWire.PayloadTooLargeException::class.java) {
            AgentRuntimeWire.toLegacyBundle(request)
        }
    }

    @Test
    fun largeImageBodyUsesFileDescriptorAndStaysOutOfBinderBundle() {
        val imageBytes = ByteArray(600_000) { index -> (index % 251).toByte() }
        val dataUrl = "data:image/png;base64,${Base64.encodeToString(imageBytes, Base64.NO_WRAP)}"
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-large-image",
            prompt = "分析图片",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            images = listOf(
                AgentModelClient.ModelImage(
                    reference = dataUrl,
                    mimeType = "image/png",
                    bytes = imageBytes.size,
                    source = "test",
                )
            ),
        )

        AgentRuntimeImageTransfer.prepare(
            RuntimeEnvironment.getApplication(),
            request.images,
        ).use { prepared ->
            val bundle = AgentRuntimeWire.toBundle(request, prepared.images)
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(bundle)
                assertTrue(parcel.dataSize() < 64_000)
            } finally {
                parcel.recycle()
            }

            val materialized = AgentRuntimeImageTransfer.materialize(
                AgentRuntimeWire.incomingRunRequestFromBundle(bundle)
            )
            assertEquals(request.copy(images = emptyList()), materialized.copy(images = emptyList()))
            assertEquals(request.images.single().reference, materialized.images.single().reference)
            assertEquals(request.images.single().mimeType, materialized.images.single().mimeType)
            assertEquals(request.images.single().bytes, materialized.images.single().bytes)
            assertEquals(request.images.single().source, materialized.images.single().source)
        }
    }

    @Test
    fun localImageReferenceIsNotBase64EncodedUntilRuntimeIngestsIt() {
        val context = RuntimeEnvironment.getApplication()
        val sourceFile = File(context.cacheDir, "runtime-wire-source-${System.nanoTime()}.png")
        FileOutputStream(sourceFile).use { output ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(
                Bitmap.CompressFormat.PNG,
                100,
                output,
            )
        }
        try {
            val image = AgentImageCodec.fromTransferReference(
                context = context,
                value = sourceFile.absolutePath,
                source = "test_local",
            ) ?: error("测试图片解析失败")
            assertEquals(sourceFile.absolutePath, image.reference)
            val request = AgentRuntimeWire.RunRequest(
                runId = "run-local-image",
                prompt = "分析本地图片",
                config = AgentModelClient.ModelConfig(
                    baseUrl = "https://example.invalid/v1",
                    apiKey = "test-key",
                    model = "test-model",
                    systemPrompt = "",
                ),
                images = listOf(image),
            )

            AgentRuntimeImageTransfer.prepare(context, request.images).use { prepared ->
                assertNull(prepared.images.single().remoteUrl)
                assertTrue(prepared.images.single().fileDescriptor != null)
                val materialized = AgentRuntimeImageTransfer.materialize(
                    AgentRuntimeWire.incomingRunRequestFromBundle(
                        AgentRuntimeWire.toBundle(request, prepared.images)
                    )
                )
                assertTrue(materialized.images.single().reference.startsWith("data:image/"))
                assertTrue(materialized.images.single().reference.contains(";base64,"))
                assertEquals(sourceFile.length().toInt(), materialized.images.single().bytes)
            }
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun completedRunDrainStaysUnderBinderTransactionBudget() {
        val runs = List(8) { index ->
            AgentRuntimeWire.CompletedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = "run-$index",
                    source = "agent_ui",
                    payload = "conversation-$index",
                ),
                result = AgentRuntimeWire.RunResult(
                    runId = "run-$index",
                    ok = true,
                    content = "c".repeat(80_000),
                    reasoningContent = "r".repeat(40_000),
                    transcript = listOf(
                        AgentModelClient.ConversationMessage(
                            role = "assistant",
                            content = "t".repeat(80_000),
                        )
                    ),
                ),
                createdAt = index.toLong(),
            )
        }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(AgentRuntimeWire.completedRunsToBundle(runs))
            assertTrue(parcel.dataSize() < 900_000)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun legacyModelConfigJsonDefaultsBrowserToolsToEnabled() {
        val config = Json.decodeFromString<AgentModelClient.ModelConfig>(
            """{"baseUrl":"https://api.openai.com/v1","apiKey":"test-key","model":"gpt-test","systemPrompt":"你是手机 Agent"}"""
        )

        assertEquals(true, config.browserTools)
        assertEquals(true, config.deviceDirectTools)
        assertEquals(false, config.deviceSensitiveReadTools)
        assertEquals(false, config.deviceSensitiveActionTools)
    }

    @Test
    fun runRequestBundleRoundTripPreservesConfigHistoryAndImages() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-1",
            prompt = "继续分析",
            config = AgentModelClient.ModelConfig(
                providerSourceType = "bailian",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKey = "test-key",
                model = "qwen3-max",
                systemPrompt = "你是手机 Agent",
                terminalTools = true,
                browserTools = true,
                deviceDirectTools = true,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
                thinkingEnabled = true,
                extraBodyJson = """{"thinking_budget":256}""",
            ),
            images = listOf(
                AgentModelClient.ModelImage(
                    reference = "data:image/png;base64,abc",
                    mimeType = "image/png",
                    bytes = 123,
                    width = 1080,
                    height = 2400,
                    source = "screenshot",
                )
            ),
            history = listOf(
                AgentModelClient.ConversationMessage(
                    role = "user",
                    content = "上一轮问题",
                ),
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "上一轮回答",
                ),
            ),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "handoff-1",
                source = "overlay",
                payload = """{"package":"com.tencent.mm"}""",
            ),
        )

        val bundle = AgentRuntimeWire.toLegacyBundle(request)
        assertEquals(true, bundle.containsKey("browser_tools"))
        assertEquals(true, bundle.getBoolean("browser_tools"))
        val roundTripped = AgentRuntimeWire.runRequestFromBundle(bundle)

        assertEquals(request, roundTripped)
    }

    @Test
    fun legacyRunRequestBundleDefaultsBrowserToolsToEnabled() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-legacy",
            prompt = "读取网页",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "test-key",
                model = "gpt-test",
                systemPrompt = "你是手机 Agent",
                browserTools = false,
            ),
            images = emptyList(),
        )
        val legacyBundle = AgentRuntimeWire.toLegacyBundle(request).apply {
            remove("browser_tools")
        }

        val roundTripped = AgentRuntimeWire.runRequestFromBundle(legacyBundle)

        assertEquals(true, roundTripped.config.browserTools)
    }

    @Test
    fun legacyRunRequestUsesSafeDeviceToolDefaults() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-legacy-device-tools",
            prompt = "查看设备状态",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "test-key",
                model = "gpt-test",
                systemPrompt = "你是手机 Agent",
                deviceDirectTools = false,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
            ),
            images = emptyList(),
        )
        val legacyBundle = AgentRuntimeWire.toLegacyBundle(request).apply {
            remove("device_direct_tools")
            remove("device_sensitive_read_tools")
            remove("device_sensitive_action_tools")
        }

        val config = AgentRuntimeWire.runRequestFromBundle(legacyBundle).config

        assertEquals(true, config.deviceDirectTools)
        assertEquals(false, config.deviceSensitiveReadTools)
        assertEquals(false, config.deviceSensitiveActionTools)
    }

    @Test
    fun browserToolArgumentsSummaryOmitsSensitiveInputAndUrlParts() {
        val summary = AgentModelClient.summarizeBrowserToolArguments(
            """{"action":"type","url":"https://user:password@example.com/private?q=token#fragment","text":"secret input"}"""
        )

        assertEquals("输入内容 · example.com", summary)
    }

    @Test
    fun browserToolResultSummaryKeepsSafeMetadataAndFailureState() {
        val summary = AgentModelClient.summarizeToolResult(
            toolName = "browser_use",
            result = AgentModelClient.ToolResult(
                content = """{"ok":false,"action":"get_readable","url":"https://user:password@example.com/private?q=token#fragment","title":"Example","text":"secret body","elements":[{},{}],"truncated":true}"""
            ),
        )

        assertEquals(
            "ok=false, action=get_readable, host=example.com, title=Example, text_chars=11, elements=2, truncated=true",
            summary,
        )
    }

    @Test
    fun openUriArgumentsSummaryOmitsPathCredentialsAndQuery() {
        val summary = AgentModelClient.summarizeOpenUriArguments(
            """{"uri":"https://user:password@example.com/private/access_token/value?q=secret#fragment"}"""
        )

        assertEquals("交给外部应用 · https · example.com", summary)
    }

    @Test
    fun eventBundleRoundTripPreservesReasoningAndUsage() {
        val events = listOf(
            AgentEvent.AssistantBlockStart(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
            ),
            AgentEvent.AssistantBlockDelta(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                deltaChars = 4,
                delta = "思考",
            ),
            AgentEvent.AssistantBlockEnd(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                contentChars = 4,
            ),
            AgentEvent.AssistantReceived(
                round = 2,
                contentChars = 12,
                reasoningContent = "完整思考内容",
                toolNames = listOf("observe_screen", "input_text"),
            ),
            AgentEvent.UsageReceived(
                round = 2,
                usage = AgentTokenUsage(
                    contextTokens = 4096,
                    inputTokens = 1200,
                    outputTokens = 320,
                    reasoningTokens = 80,
                    cachedTokens = 900,
                ),
            ),
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "call_abc",
                name = "observe_screen",
                argsPreview = """{"include_screenshot":true}""",
            ),
            AgentEvent.ToolFinished(
                round = 2,
                toolCallId = "call_abc",
                name = "observe_screen",
                resultSummary = "ok=true, chars=120",
                imageCount = 1,
                imageBytes = 2048,
            ),
        )

        events.forEach { event ->
            assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
        }
    }

    @Test
    fun runResultBundleRoundTripPreservesReasoningContent() {
        val result = AgentRuntimeWire.RunResult(
            runId = "run-1",
            ok = true,
            content = "最终回答",
            reasoningContent = "先分析问题，再调用工具，最后总结。",
            transcript = listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "最终回答",
                    reasoningContent = "先分析问题，再调用工具，最后总结。",
                )
            ),
            metrics = AgentRunMetrics(
                inputTokens = 1_200,
                cachedInputTokens = 900,
                outputTokens = 320,
                elapsedMs = 12_345,
            ),
        )

        val roundTripped = AgentRuntimeWire.runResultFromBundle(AgentRuntimeWire.toBundle(result))

        assertEquals(result, roundTripped)
    }

    @Test
    fun entryHandoffBundleRoundTripPreservesEntrySurfacePolicy() {
        val handoff = AgentRuntimeWire.EntryHandoff(
            id = "handoff-1",
            source = "breeno",
            payload = """{"userText":"打开微信"}""",
            dismissEntrySurfaceOnForegroundOperation = true,
        )

        val roundTripped = AgentRuntimeWire.entryHandoffFromBundle(AgentRuntimeWire.toBundle(handoff))

        assertEquals(handoff, roundTripped)
    }

    @Test
    fun entryHandoffDefaultsToKeepingEntrySurfaceVisible() {
        val handoff = AgentRuntimeWire.entryHandoffFromBundle(
            AgentRuntimeWire.toBundle(
                AgentRuntimeWire.EntryHandoff(
                    id = "handoff-1",
                    source = "app",
                    payload = "{}",
                )
            )
        )

        assertEquals(false, handoff.dismissEntrySurfaceOnForegroundOperation)
    }

    @Test
    fun legacyBreenoHandoffDefaultsToDismissingEntrySurface() {
        val bundle = AgentRuntimeWire.toBundle(
            AgentRuntimeWire.EntryHandoff(
                id = "handoff-1",
                source = "breeno",
                payload = "{}",
            )
        ).apply {
            remove("handoff_dismiss_entry_surface_on_foreground_operation")
        }

        val handoff = AgentRuntimeWire.entryHandoffFromBundle(bundle)

        assertEquals(true, handoff.dismissEntrySurfaceOnForegroundOperation)
    }

    @Test
    fun legacyNonBreenoHandoffDefaultsToKeepingEntrySurfaceVisible() {
        val bundle = AgentRuntimeWire.toBundle(
            AgentRuntimeWire.EntryHandoff(
                id = "handoff-1",
                source = "app",
                payload = "{}",
            )
        ).apply {
            remove("handoff_dismiss_entry_surface_on_foreground_operation")
        }

        val handoff = AgentRuntimeWire.entryHandoffFromBundle(bundle)

        assertEquals(false, handoff.dismissEntrySurfaceOnForegroundOperation)
    }
}
