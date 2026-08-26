package fuck.andes.data.repository

import android.content.Context
import fuck.andes.data.datastore.SettingsDataStore
import fuck.andes.data.db.FuckAndesDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import fuck.andes.data.db.MemoryAtomEntity
import fuck.andes.data.db.MemoryConversationEntity
import fuck.andes.data.db.MemoryProfileEntity
import fuck.andes.data.db.MemoryScenarioEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import fuck.andes.agent.memory.retrieval.VectorSearch

/**
 * 本地四层记忆仓库（参照 TencentDB Agent Memory 的 L0–L3 分层，纯本地实现）。
 *
 * - L0 对话记忆：自动记录每轮消息，支持关键词检索（有界，最多保留最近 400 条 / 30 天）。
 * - L1 原子记忆：跨会话复用的事实 / 偏好条目，支持写入、检索、删除与去重。
 * - L2 场景记忆：按场景名组织的记忆块，同名写入即覆盖。
 * - L3 核心画像：用户长期画像键值，由 Prompt 常驻注入。
 *
 * 所有写入与检索均有上限约束；开关由 SettingsDataStore 的 fourLayerMemoryEnabled 控制，
 * 关闭后模型不可读写，但已保存内容保留。
 */
internal object MemoryLayerRepository {
    private const val MAX_ATOMS = 500
    private const val MAX_SCENARIOS = 200
    private const val MAX_PROFILE_KEYS = 100
    private const val MAX_CONVERSATIONS = 400
    private const val MAX_CONTENT_CHARS = 4_000
    private const val MAX_QUERY_CHARS = 200
    private const val RECENT_ATOMS_FOR_CONTEXT = 20
    private const val RECENT_PROFILE_FOR_CONTEXT = 60
    private const val SCENARIO_NAMES_FOR_CONTEXT = 40
    private const val HOT_ATOMS_FOR_CONTEXT = 10
    private const val RECENT_ATOMS_CANDIDATES = 30
    private const val PROFILE_VALUE_MAX_CHARS = 160
    private const val SCENARIO_SNIPPET_CHARS = 48
    private const val MIN_CONVERSATION_CHARS = 2
    private const val FTS_BATCH_SIZE = 100
    private const val DISTILL_BATCH = 30
    private const val DISTILL_MAX_FACTS = 6
    private const val DISTILL_MIN_TEXT_CHARS = 3
    private const val EXPIRY_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val ATOM_EXPIRY_BATCH = 50
    private const val SCENARIO_EXPIRY_BATCH = 20

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var lastMemoryExpiryCheck = 0L

    fun init(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
        }
    }

    private fun context(): Context = checkNotNull(applicationContext) {
        "MemoryLayerRepository.init(context) must be called in Application.onCreate()"
    }

    private fun dao() = FuckAndesDatabase.get(context()).memoryLayerDao()

    private val semanticIndexer by lazy { SemanticMemoryIndexer(context()) }

    // ── 开关 ───────────────────────────────────────────────────
    fun enabledFlow(): Flow<Boolean> = SettingsDataStore.fourLayerMemoryEnabledFlow()

    suspend fun isEnabled(): Boolean = SettingsDataStore.settings().fourLayerMemoryEnabled

    suspend fun setEnabled(enabled: Boolean) = SettingsDataStore.setFourLayerMemoryEnabled(enabled)


    suspend fun distillMdSyncEnabled(): Boolean = SettingsDataStore.settings().memoryDistillMdSync

    suspend fun setDistillMdSyncEnabled(enabled: Boolean) = SettingsDataStore.setMemoryDistillMdSync(enabled)

    // ── L0 对话记忆 ────────────────────────────────────────────
    suspend fun recordConversation(role: String, content: String) {
        if (content.isBlank()) return
        val db = dao()
        val trimmed = content.trim().take(MAX_CONTENT_CHARS)
        if (trimmed.length < MIN_CONVERSATION_CHARS) return
        db.insertConversation(
            MemoryConversationEntity(
                id = "l0-${UUID.randomUUID()}",
                role = role,
                content = trimmed,
                createdAt = System.currentTimeMillis(),
            )
        )
        // 淘汰超出保留期或条数上限的旧记录（只做计数级清理，删除不阻塞主流程）。
        val now = System.currentTimeMillis()
        db.deleteConversationsBefore(now - CONVERSATION_RETENTION_MS)
        if (db.conversationCount() > MAX_CONVERSATIONS) {
            db.recentConversations(MAX_CONVERSATIONS).lastOrNull()?.let { oldest ->
                // 直接按时间清理：删除 createdAt 早于最旧保留条目的记录
                runCatching { db.deleteConversationsBefore(oldest.createdAt) }
            }
        }
        // P2-1 自动沉淀：用户陈述写入后异步提炼（规则写入 L1/L3），全程静默失败。
        if (role == "user") maybeAutoDistill()
    }

    suspend fun searchConversations(query: String, limit: Int): List<MemoryConversationEntity> =
        dao().searchConversations(
            escapeLike(query.trim().take(MAX_QUERY_CHARS)),
            limit.coerceIn(1, 50),
        )

    suspend fun conversationCount(): Int = dao().conversationCount()

    // ── L1 原子记忆 ────────────────────────────────────────────
    /** 写入原子记忆；相同 content 视为同一原子，仅更新时间与分类。 */
    suspend fun writeAtom(content: String, category: String): MemoryAtomEntity {
        val db = dao()
        val now = System.currentTimeMillis()
        val trimmed = content.trim().take(MAX_CONTENT_CHARS)
        val cat = category.trim().take(MAX_CONTENT_CHARS).ifBlank { "general" }
        if (trimmed.isBlank()) error("原子记忆内容不能为空")
        val existing = db.atomByContent(trimmed)
        val entity = if (existing != null) {
            existing.copy(category = cat, updatedAt = now, searchGrams = toSearchGrams(trimmed))
        } else {
            MemoryAtomEntity(
                id = "l1-${UUID.randomUUID()}",
                content = trimmed,
                category = cat,
                createdAt = now,
                updatedAt = now,
                usageCount = 0,
                searchGrams = toSearchGrams(trimmed),
            )
        }
        // 达到上限时按 LRU+LFU 混合策略淘汰：优先淘汰「最久未更新且使用最少」的条目，活跃条目受保护。
        if (existing == null && db.atomCount() >= MAX_ATOMS) {
            db.rawAtoms(
                SimpleSQLiteQuery(
                    "SELECT * FROM memory_atoms ORDER BY updatedAt ASC, usageCount ASC LIMIT ?",
                    arrayOf(1),
                )
            ).firstOrNull()?.let { victim -> db.deleteAtom(victim.id) }
        }
        db.insertAtom(entity)
        semanticIndexer.index("atom", entity.id, entity.content)
        return entity
    }

    suspend fun searchAtoms(query: String, limit: Int): List<MemoryAtomEntity> {
        val db = dao()
        val q = query.trim().take(MAX_QUERY_CHARS)
        if (q.isBlank()) return emptyList()
        val capped = limit.coerceIn(1, 50)
        ensureGramsBackfilled()
        // FTS5 优先；设备不支持（fts5Supported=false）或 MATCH 出错/未命中时降级 LIKE。
        val results = runCatching {
            val fts = ftsMatchQuery(q)
            if (fts.isBlank() || !FuckAndesDatabase.fts5Supported) {
                emptyList()
            } else {
                db.rawAtoms(
                    SimpleSQLiteQuery(
                        "SELECT * FROM memory_atoms WHERE rowid IN " +
                            "(SELECT rowid FROM memory_atoms_fts WHERE memory_atoms_fts MATCH ?) " +
                            "ORDER BY usageCount DESC, updatedAt DESC LIMIT ?",
                        arrayOf<Any>(fts, capped),
                    )
                )
            }
        }.getOrElse { emptyList() }
        val final = if (results.isEmpty()) db.searchAtoms(escapeLike(q), capped) else results
        val now = System.currentTimeMillis()
        final.forEach { db.bumpAtomUsage(it.id, now) }
        return final
    }

    suspend fun recentAtoms(limit: Int = RECENT_ATOMS_FOR_CONTEXT): List<MemoryAtomEntity> =
        dao().recentAtoms(limit.coerceIn(1, 100))

    suspend fun deleteAtom(id: String) {
        dao().deleteAtom(id)
    }

    suspend fun atomCount(): Int = dao().atomCount()

    // ── 记忆过期淘汰 ────────────────────────────────────────
    /** 按 TTL 清理长期未使用的记忆；6 小时节流，静默失败。 */
    suspend fun expireOldMemoriesIfDue(now: Long = System.currentTimeMillis()) {
        if (now - lastMemoryExpiryCheck < EXPIRY_CHECK_INTERVAL_MS) return
        lastMemoryExpiryCheck = now
        runCatching {
            val db = dao()
            val oldAtoms = db.atomIdsOlderThan(now - MemoryExpiryPolicy.DEFAULT_ATOM_TTL_MS, ATOM_EXPIRY_BATCH)
            if (oldAtoms.isNotEmpty()) {
                db.deleteAtomsByIds(oldAtoms)
                oldAtoms.forEach { semanticIndexer.remove("atom", it) }
            }
            val oldScenarios = db.scenarioIdsOlderThan(now - MemoryExpiryPolicy.DEFAULT_SCENARIO_TTL_MS, SCENARIO_EXPIRY_BATCH)
            if (oldScenarios.isNotEmpty()) {
                db.deleteScenariosByIds(oldScenarios)
                oldScenarios.forEach { semanticIndexer.remove("scenario", it) }
            }
        }
    }

    // ── L2 场景记忆 ────────────────────────────────────────────
    suspend fun saveScenario(name: String, content: String): MemoryScenarioEntity {
        val db = dao()
        val now = System.currentTimeMillis()
        val trimmedName = name.trim().take(MAX_CONTENT_CHARS)
        val trimmedContent = content.trim().take(MAX_CONTENT_CHARS)
        if (trimmedName.isBlank()) error("场景名称不能为空")
        if (trimmedContent.isBlank()) error("场景内容不能为空")
        val existing = db.scenarioByName(trimmedName)
        val entity = if (existing != null) {
            existing.copy(content = trimmedContent, updatedAt = now)
        } else {
            if (db.scenarioCount() >= MAX_SCENARIOS) {
                db.listScenarios(MAX_SCENARIOS).lastOrNull()?.let { oldest ->
                    db.deleteScenario(oldest.id)
                }
            }
            MemoryScenarioEntity(
                id = "l2-${UUID.randomUUID()}",
                name = trimmedName,
                content = trimmedContent,
                createdAt = now,
                updatedAt = now,
            )
        }
        db.insertScenario(entity)
        semanticIndexer.index("scenario", entity.id, entity.content)
        return entity
    }

    suspend fun readScenario(name: String): MemoryScenarioEntity? =
        dao().scenarioByName(name.trim().take(MAX_QUERY_CHARS))

    suspend fun listScenarios(limit: Int = 100): List<MemoryScenarioEntity> =
        dao().listScenarios(limit.coerceIn(1, 200))

    suspend fun deleteScenario(id: String) {
        dao().deleteScenario(id)
    }

    fun scenarioNamesFlow(): Flow<List<MemoryScenarioEntity>> = dao().scenarioNamesFlow()

    // ── L3 核心画像 ────────────────────────────────────────────
    suspend fun updateProfile(key: String, value: String): MemoryProfileEntity {
        val db = dao()
        val now = System.currentTimeMillis()
        val trimmedKey = key.trim().take(MAX_CONTENT_CHARS)
        val trimmedValue = value.trim().take(MAX_CONTENT_CHARS)
        if (trimmedKey.isBlank()) error("画像键不能为空")
        if (trimmedValue.isBlank()) error("画像值不能为空")
        if (db.profileByKey(trimmedKey) == null && db.profileCount() >= MAX_PROFILE_KEYS) {
            db.profileAll().lastOrNull()?.let { oldest -> db.deleteProfile(oldest.key) }
        }
        val entity = MemoryProfileEntity(
            key = trimmedKey,
            value = trimmedValue,
            updatedAt = now,
        )
        db.upsertProfile(entity)
        return entity
    }

    suspend fun deleteProfile(key: String) {
        dao().deleteProfile(key)
    }

    suspend fun profileAll(): List<MemoryProfileEntity> = dao().profileAll()

    fun profileFlow(): Flow<List<MemoryProfileEntity>> = dao().profileFlow()

    // ── Prompt 注入快照 ────────────────────────────────────────
    /**
     * 构建四层记忆的注入摘要：L3 画像全量 + L1 最近原子 + L2 场景名列表。
     * 仅在开关开启且有内容时返回非空字符串，注入失败不影响主流程。
     */
    suspend fun buildContextSummary(): String? {
        if (!isEnabled()) return null
        expireOldMemoriesIfDue()
        return runCatching {
            val db = dao()
            ensureGramsBackfilled()
            val profile = db.profileAll().take(RECENT_PROFILE_FOR_CONTEXT)
            // 高频（usageCount 加权）保底 + 最近写入补齐，去重后截断。
            val atoms = (db.hotAtoms(HOT_ATOMS_FOR_CONTEXT) +
                db.recentAtoms(RECENT_ATOMS_CANDIDATES))
                .distinctBy { it.id }
                .take(RECENT_ATOMS_FOR_CONTEXT)
            val scenarios = db.listScenarios(SCENARIO_NAMES_FOR_CONTEXT)
            if (profile.isEmpty() && atoms.isEmpty() && scenarios.isEmpty()) return null
            buildString {
                if (profile.isNotEmpty()) {
                    appendLine("<memory_profile>")
                    profile.forEach { appendLine("- ${it.key}：${it.value.take(PROFILE_VALUE_MAX_CHARS)}") }
                    appendLine("</memory_profile>")
                }
                if (atoms.isNotEmpty()) {
                    appendLine("<memory_atoms_recent>")
                    atoms.forEach { appendLine("- [${it.category}] ${it.content.take(200)}") }
                    appendLine("</memory_atoms_recent>")
                }
                if (scenarios.isNotEmpty()) {
                    appendLine("<memory_scenarios>")
                    scenarios.forEach {
                        val snippet = it.content.replace('\n', ' ').trim().take(SCENARIO_SNIPPET_CHARS)
                        appendLine("- ${it.name}${if (snippet.isNotEmpty()) "：$snippet" else ""}")
                    }
                    appendLine("</memory_scenarios>")
                }
            }.trim()
        }.getOrNull()
    }

    private const val CONVERSATION_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    // ── 检索工具函数（P0-1 转义 / P1-3 FTS） ──────────────────────
    /** LIKE 通配符转义：\ % _ 均按字面量处理。 */
    private fun escapeLike(query: String): String =
        query.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    /**
     * 把文本转成 2-gram 检索串：仅保留字母/数字/CJK 字符（其余视为边界），
     * 相邻两字符为一 gram，空格连接。unicode61 按空格分词，保证中文 2 字词可精确命中，
     * 且 gram 只含安全字符，不会注入 FTS5 MATCH 语法。
     */
    private fun toSearchGrams(text: String): String {
        val cleaned = text.filter { it.isLetterOrDigit() }
        if (cleaned.isEmpty()) return ""
        if (cleaned.length == 1) return cleaned
        return buildString {
            for (i in 0 until cleaned.length - 1) {
                if (isNotEmpty()) append(' ')
                append(cleaned[i])
                append(cleaned[i + 1])
            }
        }
    }

    /** 构造安全的 FTS5 MATCH 查询串：每个 gram 用双引号包裹。 */
    private fun ftsMatchQuery(query: String): String {
        val grams = toSearchGrams(query)
        if (grams.isEmpty()) return ""
        return grams.split(' ').joinToString(" ") { "\"$it\"" }
    }

    @Volatile
    private var gramsBackfillAttempted = false
    private val backfillMutex = Mutex()

    /**
     * 惰性补算存量原子的 search_grams 并重建 FTS 索引（migration 不做数据变换）。
     * 双检锁保证并发调用只执行一次；全程静默失败，不影响主流程。
     */
    private suspend fun ensureGramsBackfilled() {
        if (gramsBackfillAttempted) return
        backfillMutex.withLock {
            if (gramsBackfillAttempted) return
            gramsBackfillAttempted = true
            runCatching {
                val db = dao()
                while (true) {
                    val pending = db.rawAtoms(
                        SimpleSQLiteQuery(
                            "SELECT * FROM memory_atoms WHERE search_grams = '' AND content != '' LIMIT ?",
                            arrayOf(FTS_BATCH_SIZE),
                        )
                    )
                    if (pending.isEmpty()) break
                    pending.forEach { atom ->
                        db.rawExec(
                            SimpleSQLiteQuery(
                                "UPDATE memory_atoms SET search_grams = ? WHERE id = ?",
                                arrayOf(toSearchGrams(atom.content), atom.id),
                            )
                        )
                    }
                }
                if (FuckAndesDatabase.fts5Supported) {
                    db.rawExec(SimpleSQLiteQuery("DELETE FROM memory_atoms_fts"))
                    db.rawExec(
                        SimpleSQLiteQuery(
                            "INSERT INTO memory_atoms_fts(rowid, content, category, search_grams) " +
                                "SELECT rowid, content, category, search_grams FROM memory_atoms"
                        )
                    )
                }
            }
        }
    }

    // ── P2-1 自动沉淀管线 ───────────────────────────────────────
    @Volatile
    private var distillCursorLoaded = false
    private var distillCursorValue = 0L
    private val distillMutex = Mutex()

    private suspend fun distillCursor(): Long {
        if (!distillCursorLoaded) {
            distillCursorValue = SettingsDataStore.settings().memoryDistillCursor
            distillCursorLoaded = true
        }
        return distillCursorValue
    }

    private suspend fun advanceDistillCursor(value: Long) {
        distillCursorValue = value
        SettingsDataStore.setMemoryDistillCursor(value)
    }

    /**
     * 自动沉淀：增量处理游标之后的用户消息，规则提炼后写入 L1（content 去重）
     * 与 L3（键覆盖）；游标持久化保证重启不重跑。独立开关 memoryAutoDistillEnabled
     * 控制，静默失败不影响主流程。
     */
    private suspend fun maybeAutoDistill() {
        if (!isEnabled()) return
        if (!SettingsDataStore.settings().memoryAutoDistillEnabled) return
        runCatching {
            distillMutex.withLock {
                val db = dao()
                val cursor = distillCursor()
                val batch = db.userConversationsAfter(cursor, DISTILL_BATCH)
                if (batch.isEmpty()) return@withLock
                var lastAt = cursor
                for (conv in batch) {
                    lastAt = conv.createdAt
                    if (conv.content.length < DISTILL_MIN_TEXT_CHARS) continue
                    MemoryDistillRules.extract(conv.content).take(DISTILL_MAX_FACTS).forEach { fact ->
                        writeAtom(fact.content, fact.category)
                        fact.profileKey?.let { key ->
                            updateProfile(key, fact.profileValue ?: fact.content)
                        }
                        promoteFactToMdIfCore(fact)
                    }
                }
                advanceDistillCursor(lastAt)
            }
        }
    }

    /**
     * 方案 B+：把自动蒸馏出的画像级核心事实升格进 MEMORY.md 核心层。
     * 仅当 memoryDistillMdSync 开启时生效；通过 bge 语义关卡（核心度）后写入。
     * 全程静默失败，不影响主蒸馏流程。
     */
    private suspend fun promoteFactToMdIfCore(fact: MemoryDistillRules.DistilledFact) {
        if (!SettingsDataStore.settings().memoryDistillMdSync) return
        runCatching {
            if (!shouldPromoteToMd(fact)) return
            val snapshot = AgentMemoryRepository.snapshot()
            // 文本去重：MEMORY.md 已含该事实则跳过，避免重复堆积
            if (snapshot.content.contains(fact.content)) return
            // 预算护栏：接近上限时不再追加，避免撑爆 1 MiB
            if (snapshot.byteSize >= AgentMemoryStore.MAX_FILE_BYTES * 0.85) return
            val line = memoryMdLineFor(fact)
            when (AgentMemoryRepository.mutate(AgentMemoryMutation.Append(snapshot.revision, line))) {
                is AgentMemoryWriteResult.Success -> Unit
                is AgentMemoryWriteResult.Conflict -> Unit // MD 已变化则放弃本次，防重写
            }
        }
    }

    /** bge 语义关卡：模板化画像核心（profileKey 非空）直接放行；非模板化核心交 bge 判核心度。 */
    private suspend fun shouldPromoteToMd(fact: MemoryDistillRules.DistilledFact): Boolean {
        if (fact.profileKey != null) return true
        val vec = semanticIndexer.embed(fact.content)
        if (vec.isEmpty()) return false // 推理失败降级不入
        return VectorSearch.cosine(vec, coreAnchorVector()) >= CORE_PROMOTE_THRESHOLD
    }

    /** 预计算的核心画像基准向量：用于衡量一条事实是否属于用户长期核心档案。 */
    private suspend fun coreAnchorVector(): FloatArray {
        return coreAnchorVec ?: semanticIndexer.embed(CORE_ANCHOR_TEXT).also {
            coreAnchorVec = if (it.isEmpty()) null else it
        }
    }

    /** 把一条蒸馏事实格式化为追加进 MEMORY.md 的核心行。 */
    private fun memoryMdLineFor(fact: MemoryDistillRules.DistilledFact): String =
        "- [${fact.category}] ${fact.content}"

    private var coreAnchorVec: FloatArray? = null

    private const val CORE_PROMOTE_THRESHOLD = 0.75f
    private const val CORE_ANCHOR_TEXT = "这是关于用户本人的长期稳定核心事实，值得作为核心记忆长期保留。"
}

