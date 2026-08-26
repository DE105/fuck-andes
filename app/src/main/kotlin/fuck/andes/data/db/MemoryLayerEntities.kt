package fuck.andes.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地四层记忆（参照 TencentDB Agent Memory 的 L0–L3 记忆分层，纯本地实现）。
 *
 * - L0 对话记忆：自动记录每轮用户 / 助手消息，作为可检索的原始语料。
 * - L1 原子记忆：可跨会话复用的事实、偏好等原子条目，支持关键词检索。
 * - L2 场景记忆：按场景（工作、学习、项目等）组织的记忆块。
 * - L3 核心画像：用户长期画像键值（名字、身份、偏好、关系等），常驻注入 Prompt。
 */

/** L0 对话记忆。 */
@Entity(
    tableName = "memory_conversations",
    indices = [Index("createdAt")],
)
internal data class MemoryConversationEntity(
    @PrimaryKey val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)

/** L1 原子记忆。 */
@Entity(
    tableName = "memory_atoms",
    indices = [
        Index(value = ["content"]),
        Index(value = ["updatedAt"]),
    ],
)
internal data class MemoryAtomEntity(
    @PrimaryKey val id: String,
    val content: String,
    val category: String,
    val createdAt: Long,
    val updatedAt: Long,
    val usageCount: Int,
    /** 检索用 2-gram 预处理串（FTS5 unicode61 按空格分词）；空串表示待补算。
     * defaultValue 必须与 MIGRATION_16_17 的 `DEFAULT ''` 精确一致：
     * Room 2.7+ 校验 dflt_value（原样 ''），实体缺省 null 会判不匹配导致
     * "Migration didn't properly handle" 启动崩溃（v2.10.7 事故）。 */
    @ColumnInfo(name = "search_grams", defaultValue = "''")
    val searchGrams: String = "",
)

/** L2 场景记忆（name 唯一，同名写入即覆盖）。 */
@Entity(
    tableName = "memory_scenarios",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["updatedAt"]),
    ],
)
internal data class MemoryScenarioEntity(
    @PrimaryKey val id: String,
    val name: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** L3 核心画像键值。 */
@Entity(tableName = "memory_profile")
internal data class MemoryProfileEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)
