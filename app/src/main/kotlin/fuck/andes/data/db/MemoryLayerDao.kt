package fuck.andes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** 本地四层记忆的数据访问层。 */
@Dao
internal interface MemoryLayerDao {

    // ── L0 对话记忆 ────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(entity: MemoryConversationEntity)

    @Query("SELECT * FROM memory_conversations ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentConversations(limit: Int): List<MemoryConversationEntity>

    @Query(
        "SELECT * FROM memory_conversations WHERE content LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun searchConversations(query: String, limit: Int): List<MemoryConversationEntity>

    @Query("SELECT COUNT(*) FROM memory_conversations")
    suspend fun conversationCount(): Int

    @Query(
        "SELECT * FROM memory_conversations WHERE role = 'user' AND createdAt > :cursor " +
            "ORDER BY createdAt ASC LIMIT :limit"
    )
    suspend fun userConversationsAfter(cursor: Long, limit: Int): List<MemoryConversationEntity>

    @Query("DELETE FROM memory_conversations WHERE createdAt < :before")
    suspend fun deleteConversationsBefore(before: Long)

    // ── L1 原子记忆 ────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAtom(entity: MemoryAtomEntity)

    @Query(
        "SELECT * FROM memory_atoms WHERE content LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY usageCount DESC, updatedAt DESC LIMIT :limit"
    )
    suspend fun searchAtoms(query: String, limit: Int): List<MemoryAtomEntity>

    @Query("SELECT * FROM memory_atoms ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recentAtoms(limit: Int): List<MemoryAtomEntity>

    @Query("SELECT * FROM memory_atoms ORDER BY usageCount DESC, updatedAt DESC LIMIT :limit")
    suspend fun hotAtoms(limit: Int): List<MemoryAtomEntity>

    // FTS 虚拟表（memory_atoms_fts）非 Room @Entity，KSP 校验不识别，
    // 因此涉及 FTS 表或补算列的查询一律走 @RawQuery（运行时真实执行）。
    @RawQuery
    suspend fun rawAtoms(query: SupportSQLiteQuery): List<MemoryAtomEntity>

    @RawQuery
    suspend fun rawExec(query: SupportSQLiteQuery): Int

    @Query("SELECT * FROM memory_atoms WHERE content = :content LIMIT 1")
    suspend fun atomByContent(content: String): MemoryAtomEntity?

    @Query("SELECT * FROM memory_atoms WHERE id = :id LIMIT 1")
    suspend fun atomById(id: String): MemoryAtomEntity?

    @Query("DELETE FROM memory_atoms WHERE id = :id")
    suspend fun deleteAtom(id: String)

    @Query("UPDATE memory_atoms SET usageCount = usageCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun bumpAtomUsage(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM memory_atoms")
    suspend fun atomCount(): Int

    @Query("SELECT id FROM memory_atoms WHERE updatedAt < :before LIMIT :limit")
    suspend fun atomIdsOlderThan(before: Long, limit: Int): List<String>

    @Query("DELETE FROM memory_atoms WHERE id IN (:ids)")
    suspend fun deleteAtomsByIds(ids: List<String>): Int

    // ── L2 场景记忆 ────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScenario(entity: MemoryScenarioEntity)

    @Query("SELECT * FROM memory_scenarios WHERE name = :name LIMIT 1")
    suspend fun scenarioByName(name: String): MemoryScenarioEntity?

    @Query("SELECT * FROM memory_scenarios WHERE id = :id LIMIT 1")
    suspend fun scenarioById(id: String): MemoryScenarioEntity?

    @Query("SELECT * FROM memory_scenarios ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun listScenarios(limit: Int): List<MemoryScenarioEntity>

    @Query("SELECT * FROM memory_scenarios ORDER BY updatedAt DESC")
    fun scenarioNamesFlow(): Flow<List<MemoryScenarioEntity>>

    @Query("DELETE FROM memory_scenarios WHERE id = :id")
    suspend fun deleteScenario(id: String)

    @Query("SELECT COUNT(*) FROM memory_scenarios")
    suspend fun scenarioCount(): Int

    @Query("SELECT id FROM memory_scenarios WHERE updatedAt < :before LIMIT :limit")
    suspend fun scenarioIdsOlderThan(before: Long, limit: Int): List<String>

    @Query("DELETE FROM memory_scenarios WHERE id IN (:ids)")
    suspend fun deleteScenariosByIds(ids: List<String>): Int

    // ── L3 核心画像 ────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(entity: MemoryProfileEntity)

    @Query("SELECT * FROM memory_profile ORDER BY updatedAt DESC")
    suspend fun profileAll(): List<MemoryProfileEntity>

    @Query("SELECT * FROM memory_profile ORDER BY updatedAt DESC")
    fun profileFlow(): Flow<List<MemoryProfileEntity>>

    @Query("SELECT * FROM memory_profile WHERE key = :key LIMIT 1")
    suspend fun profileByKey(key: String): MemoryProfileEntity?

    @Query("DELETE FROM memory_profile WHERE key = :key")
    suspend fun deleteProfile(key: String)

    @Query("SELECT COUNT(*) FROM memory_profile")
    suspend fun profileCount(): Int
}
