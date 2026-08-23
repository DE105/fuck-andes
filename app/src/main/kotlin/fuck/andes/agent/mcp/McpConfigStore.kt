package fuck.andes.agent.mcp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 服务器配置持久化。
 *
 * 内部以 JSON 数组存储全部服务器配置；对外提供 Claude-Desktop 兼容的
 * `{"mcpServers": {...}}` 格式导入与导出。
 */
internal object McpConfigStore {
    private const val STORE_NAME = "fuck_andes_mcp"
    private val SERVERS_KEY = stringPreferencesKey("mcp_servers_json")
    private const val MCP_SERVERS_FIELD = "mcpServers"

    private val Context.mcpDataStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

    @Volatile
    private lateinit var dataStore: DataStore<Preferences>

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun init(context: Context) {
        if (!::dataStore.isInitialized) {
            dataStore = context.applicationContext.mcpDataStore
        }
    }

    fun serversFlow(): Flow<List<McpServerConfig>> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs -> decode(prefs[SERVERS_KEY]) }
    }

    suspend fun servers(): List<McpServerConfig> = serversFlow().first()

    suspend fun save(servers: List<McpServerConfig>) {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs[SERVERS_KEY] = json.encodeToString(
                ListSerializer(McpServerConfig.serializer()),
                servers,
            )
        }
    }

    /** 按 id 替换或新增。 */
    suspend fun upsert(config: McpServerConfig) {
        val updated = servers().map { if (it.id == config.id) config else it } + listOf(config)
        save(updated.distinctBy(McpServerConfig::id))
    }

    suspend fun remove(id: String) {
        save(servers().filterNot { it.id == id })
    }

    /** 导出为 Claude-Desktop 兼容的 `{"mcpServers": {...}}` 格式。 */
    fun exportServersJson(servers: List<McpServerConfig>): String {
        val serversObject = JSONObject()
        servers.forEach { config ->
            val entry = JSONObject().apply {
                when (config.transport) {
                    McpTransport.HTTP, McpTransport.SSE -> {
                        put("url", config.url)
                        put("connectionType", config.transport.name.lowercase())
                    }
                    McpTransport.STDIO -> {
                        put("command", config.command)
                        if (config.args.isNotEmpty()) {
                            put("args", JSONArray(config.args))
                        }
                    }
                }
                if (config.bearerToken.isNotBlank()) {
                    put("bearerToken", config.bearerToken)
                }
                if (config.headers.isNotEmpty()) {
                    put("headers", JSONObject(config.headers))
                }
                if (config.env.isNotEmpty()) {
                    put("env", JSONObject(config.env))
                }
                put("enabled", config.enabled)
                put("timeoutSeconds", config.timeoutSeconds)
            }
            serversObject.put(config.name, entry)
        }
        return JSONObject().put(MCP_SERVERS_FIELD, serversObject).toString(2)
    }

    /**
     * 解析 Claude-Desktop 兼容的 `{"mcpServers": {...}}` JSON 为配置列表。
     * 传输类型推断：含 `url` 视为 HTTP（可被 UI 改为 SSE）；否则含 `command` 视为 STDIO。
     */
    fun parseImportJson(raw: String): List<McpServerConfig> {
        val root = JSONObject(raw)
        val serversObject = root.optJSONObject(MCP_SERVERS_FIELD)
            ?: throw IllegalArgumentException("缺少 mcpServers 字段")
        val result = mutableListOf<McpServerConfig>()
        serversObject.keys().forEach { name ->
            val entry = serversObject.optJSONObject(name) ?: return@forEach
            val transport = when {
                entry.has("url") -> McpTransport.HTTP
                entry.has("command") -> McpTransport.STDIO
                else -> throw IllegalArgumentException("服务器 $name 缺少 url 或 command")
            }
            val headers = entry.optJSONObject("headers")?.let(::jsonObjectToMap).orEmpty()
            val env = entry.optJSONObject("env")?.let(::jsonObjectToMap).orEmpty()
            val args = entry.optJSONArray("args")?.let(::jsonArrayToStringList).orEmpty()
            result.add(
                McpServerConfig(
                    id = newId(name),
                    name = name,
                    transport = transport,
                    url = entry.optString("url", ""),
                    command = entry.optString("command", ""),
                    args = args,
                    env = env,
                    headers = headers,
                    bearerToken = entry.optString("bearerToken", ""),
                    enabled = entry.optBoolean("enabled", true),
                    timeoutSeconds = entry.optLong("timeoutSeconds", 60L),
                ),
            )
        }
        return result
    }

    /** 由显示名生成稳定且用于工具名编码的 id。 */
    fun newId(name: String): String {
        val slug = name
            .lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .trim('_')
            .takeIf(String::isNotBlank)
            ?: "server"
        val suffix = Integer.toHexString((name.hashCode() and 0x7fffffff) % 0xffff)
        return "${slug}_$suffix"
    }

    private fun decode(raw: String?): List<McpServerConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(McpServerConfig.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, String> =
        buildMap {
            obj.keys().forEach { key -> put(key, obj.optString(key, "")) }
        }

    private fun jsonArrayToStringList(arr: JSONArray): List<String> =
        buildList {
            for (i in 0 until arr.length()) {
                add(arr.optString(i, ""))
            }
        }

    private fun ensureInitialized() {
        check(::dataStore.isInitialized) {
            "McpConfigStore.init(context) must be called in Application.onCreate()"
        }
    }
}
