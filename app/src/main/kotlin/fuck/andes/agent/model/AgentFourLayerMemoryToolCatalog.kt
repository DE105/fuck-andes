package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 声明本地四层记忆工具（参照 TencentDB Agent Memory 的 L0–L3 记忆模型）。
 *
 * - memory_atom_write / memory_atom_search / memory_atom_delete：L1 原子记忆
 * - memory_scenario_save / memory_scenario_read：L2 场景记忆
 * - memory_profile_update / memory_profile_delete：L3 核心画像
 * - memory_conversation_search：L0 对话记忆检索
 *
 * 只声明 Schema，执行逻辑在 AgentLocalTools 中；与原有 memory_get / memory_write
 * （MEMORY.md）并存，互不影响。
 */
internal object AgentFourLayerMemoryToolCatalog {
    private const val MAX_CONTENT = 4_000
    private const val MAX_QUERY = 200

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_atom_write",
                    description = "写入一条 L1 原子记忆：跨会话复用的事实、偏好或稳定信息（例如用户名字、喜欢/不喜欢、长期项目背景）。相同内容会去重并更新时间。只保存稳定、有价值的信息，不要保存密钥、验证码、凭据或一次性请求。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "content",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", MAX_CONTENT)
                                        .put("description", "原子记忆内容，一句话陈述一个事实。"),
                                )
                                .put(
                                    "category",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 100)
                                        .put("description", "可选分类，例如 user / preference / project / work / life，默认 general。"),
                                ),
                        )
                        .put("required", JSONArray().put("content")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "memory_atom_search",
                    description = "检索本地 L1 原子记忆，按关键词返回匹配条目。回答用户个性化问题、回忆用户偏好或需要跨会话背景时调用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("query", JSONObject().put("type", "string").put("maxLength", MAX_QUERY))
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 50)
                                        .put("description", "最多返回条数，默认 10。"),
                                ),
                        )
                        .put("required", JSONArray().put("query")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "memory_atom_delete",
                    description = "按 id 删除一条 L1 原子记忆（id 来自 memory_atom_search 或 memory_atom_write 的结果）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("id", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray().put("id")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "memory_scenario_save",
                    description = "保存一条 L2 场景记忆：围绕某个场景（如工作、学习、某个项目）组织的背景知识块。同名场景再次写入会覆盖旧内容。适合保存一段完整但稳定的场景背景，供以后快速恢复上下文。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string").put("maxLength", 200).put("description", "场景名称，例如「工作项目 Alpha」。"))
                                .put("content", JSONObject().put("type", "string").put("maxLength", MAX_CONTENT).put("description", "场景背景内容。")),
                        )
                        .put("required", JSONArray().put("name").put("content")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "memory_scenario_read",
                    description = "按名称读取一条 L2 场景记忆；需要恢复某场景完整背景时调用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("name", JSONObject().put("type", "string").put("maxLength", 200)),
                        )
                        .put("required", JSONArray().put("name")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "memory_profile_update",
                    description = "更新一条 L3 核心画像键值（用户长期画像：名字、身份、偏好、关系、重要背景等）。同名键覆盖。Prompt 中会常驻注入最近画像，因此只放最重要、最稳定的信息。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("key", JSONObject().put("type", "string").put("maxLength", 200).put("description", "画像键，例如 用户名字 / 职业 / 常用语言。"))
                                .put("value", JSONObject().put("type", "string").put("maxLength", MAX_CONTENT).put("description", "画像值。")),
                        )
                        .put("required", JSONArray().put("key").put("value")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "memory_profile_delete",
                    description = "按 key 删除一条 L3 核心画像。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("key", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray().put("key")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "memory_conversation_search",
                    description = "检索本地 L0 对话记忆：查找历史轮次中用户或助手说过的话（本地自动记录，最多保留 30 天 / 400 条）。用于回忆之前的对话细节。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("query", JSONObject().put("type", "string").put("maxLength", MAX_QUERY))
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 50)
                                        .put("description", "最多返回条数，默认 10。"),
                                ),
                        )
                        .put("required", JSONArray().put("query")),
                ),
            )
    }
}
