package fuck.andes.data.db

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        ConversationContextCheckpointEntity::class,
        ConversationMessageEntity::class,
        ConversationStateEntity::class,
        ProviderEntity::class,
        ProviderModelEntity::class,
        RuntimeResultEntity::class,
        RuntimeArchiveRunEntity::class,
        RuntimeArchiveEventEntity::class,
        RuntimeInFlightRunEntity::class,
        RuntimeInFlightEventEntity::class,
        SkillRegistryEntity::class,
        McpServerEntity::class,
        MemoryConversationEntity::class,
        MemoryAtomEntity::class,
        MemoryScenarioEntity::class,
        MemoryProfileEntity::class,
        MemoryEmbeddingEntity::class,
    ],
    version = 19,
    exportSchema = false,
)
internal abstract class FuckAndesDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun providerDao(): ProviderDao
    abstract fun runtimeRunDao(): RuntimeRunDao
    abstract fun skillDao(): SkillDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun memoryLayerDao(): MemoryLayerDao
    abstract fun memoryEmbeddingDao(): MemoryEmbeddingDao

    companion object {
        @Volatile
        private var instance: FuckAndesDatabase? = null

        fun get(context: Context): FuckAndesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FuckAndesDatabase::class.java,
                    "fuck_andes.db",
                )
                    .addMigrations(
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                    )
                    .addCallback(
                        object : RoomDatabase.Callback() {
                            override fun onOpen(db: SupportSQLiteDatabase) {
                                super.onOpen(db)
                                ensureFtsSchema(db)
                            }
                        }
                    )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }


        /** 本地四层记忆（L0–L3）+ 语义向量表：兼容性扩展，只新增表，不动现有表。 */
        internal val MIGRATION_18_19 = Migration(18, 19) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_conversations` (" +
                    "`id` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_conversations_createdAt` ON `memory_conversations` (`createdAt`)")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_atoms` (" +
                    "`id` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, " +
                    "`category` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`usageCount` INTEGER NOT NULL, " +
                    "`search_grams` TEXT NOT NULL DEFAULT '', " +
                    "PRIMARY KEY(`id`))"
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_atoms_content` ON `memory_atoms` (`content`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_atoms_updatedAt` ON `memory_atoms` (`updatedAt`)")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_scenarios` (" +
                    "`id` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_scenarios_name` ON `memory_scenarios` (`name`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_scenarios_updatedAt` ON `memory_scenarios` (`updatedAt`)")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_profile` (" +
                    "`key` TEXT NOT NULL, " +
                    "`value` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`key`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_embeddings` (" +
                    "`id` TEXT NOT NULL, " +
                    "`refType` TEXT NOT NULL, " +
                    "`refId` TEXT NOT NULL, " +
                    "`vector` BLOB NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_embeddings_refType` ON `memory_embeddings` (`refType`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_embeddings_refId` ON `memory_embeddings` (`refId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_embeddings_updatedAt` ON `memory_embeddings` (`updatedAt`)")
        }

        internal val MIGRATION_6_7 = Migration(6, 7) { database ->
            database.execSQL(
                "ALTER TABLE runtime_results ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'"
            )
            database.execSQL(
                "ALTER TABLE runtime_archive_runs ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'"
            )
        }

        internal val MIGRATION_16_17 = Migration(16, 17) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS mcp_servers (" +
                    "id TEXT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "url TEXT NOT NULL, " +
                    "enabled INTEGER NOT NULL, " +
                    "protocol_mode TEXT NOT NULL, " +
                    "authorization_type TEXT NOT NULL, " +
                    "tools_json TEXT NOT NULL, " +
                    "enabled_tool_names_json TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "sort_order INTEGER NOT NULL, " +
                    "last_refreshed_at INTEGER, " +
                    "last_protocol_version TEXT, " +
                    "PRIMARY KEY(id))"
            )
        }

        internal val MIGRATION_17_18 = Migration(17, 18) { database ->
            database.execSQL("ALTER TABLE mcp_servers ADD COLUMN tools_expire_at INTEGER")
        }

        internal val MIGRATION_7_8 = Migration(7, 8) { database ->
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN " +
                    "applied_runtime_run_ids_json TEXT NOT NULL DEFAULT '[]'"
            )
        }

        internal val MIGRATION_8_9 = Migration(8, 9) { database ->
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'"
            )
            database.execSQL(
                "UPDATE provider_models SET source = 'catalog' WHERE is_built_in = 1"
            )
            // 旧版“添加自定义模型”会在打开编辑框时提前落下一条空记录。
            database.execSQL("DELETE FROM provider_models WHERE TRIM(model_id) = ''")
            // 只清理由旧版“新建对话”产生、且用户从未真正使用或命名过的占位记录。
            database.execSQL(
                "DELETE FROM conversations " +
                    "WHERE title = '新对话' " +
                    "AND TRIM(history_json) = '[]' " +
                    "AND TRIM(applied_runtime_run_ids_json) = '[]' " +
                    "AND NOT EXISTS (" +
                    "SELECT 1 FROM conversation_messages " +
                    "WHERE conversation_messages.conversation_id = conversations.id)"
            )
            database.execSQL(
                "DELETE FROM conversation_state WHERE selected_conversation_id NOT IN " +
                    "(SELECT id FROM conversations)"
            )
        }

        internal val MIGRATION_9_10 = Migration(9, 10) { database ->
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN " +
                    "reasoning_effort TEXT NOT NULL DEFAULT 'default'"
            )
            database.execSQL(
                "UPDATE conversations SET reasoning_effort = " +
                    "CASE WHEN thinking_enabled = 1 THEN 'default' ELSE 'off' END"
            )
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN " +
                    "reasoning_capabilities_json TEXT NOT NULL DEFAULT 'null'"
            )
        }

        internal val MIGRATION_10_11 = Migration(10, 11) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS conversation_context_checkpoints (" +
                    "conversation_id TEXT NOT NULL, " +
                    "history_json TEXT NOT NULL, " +
                    "PRIMARY KEY(conversation_id), " +
                    "FOREIGN KEY(conversation_id) REFERENCES conversations(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL(
                "INSERT INTO conversation_context_checkpoints (conversation_id, history_json) " +
                    "SELECT id, CASE " +
                    "WHEN length(CAST(history_json AS BLOB)) <= 131072 THEN history_json " +
                    "ELSE '[]' END FROM conversations"
            )
            // 会话列表不再使用旧字段；及时清空可保证旧版留下的超大行不会继续占用数据库。
            database.execSQL("UPDATE conversations SET history_json = '[]'")
        }

        internal val MIGRATION_11_12 = Migration(11, 12) { database ->
            database.execSQL(
                "ALTER TABLE conversation_messages ADD COLUMN " +
                    "is_edited INTEGER NOT NULL DEFAULT 0"
            )
        }

        internal val MIGRATION_12_13 = Migration(12, 13) { database ->
            database.execSQL(
                "ALTER TABLE model_providers ADD COLUMN " +
                    "hosted_web_search_enabled INTEGER NOT NULL DEFAULT 0"
            )
        }

        internal val MIGRATION_13_14 = Migration(13, 14) { database ->
            database.execSQL(
                "ALTER TABLE runtime_archive_runs ADD COLUMN " +
                    "user_image_previews_json TEXT NOT NULL DEFAULT '[]'"
            )
        }

        internal val MIGRATION_14_15 = Migration(14, 15) { database ->
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN context_window_override INTEGER"
            )
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN reasoning_override INTEGER"
            )
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN " +
                    "reasoning_capabilities_override_json TEXT NOT NULL DEFAULT 'null'"
            )
        }

        internal val MIGRATION_15_16 = Migration(15, 16) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS runtime_inflight_runs (" +
                    "run_id TEXT NOT NULL, " +
                    "owner_instance_id TEXT NOT NULL, " +
                    "handoff_id TEXT NOT NULL, " +
                    "handoff_source TEXT NOT NULL, " +
                    "handoff_payload TEXT NOT NULL, " +
                    "dismiss_entry_surface INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "PRIMARY KEY(run_id))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS runtime_inflight_events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "run_id TEXT NOT NULL, " +
                    "sort_index INTEGER NOT NULL, " +
                    "event_json TEXT NOT NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runtime_inflight_runs(run_id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_runtime_inflight_events_run_id " +
                    "ON runtime_inflight_events(run_id)"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_runtime_inflight_events_run_id_sort_index " +
                    "ON runtime_inflight_events(run_id, sort_index)"
            )

        }

        /**
         * FTS5 在当前设备是否可用。系统 SQLite 未编译 FTS5 时（部分厂商 ROM），
         * 检索自动降级 LIKE；本标志由 onOpen 检测后设置，供仓库层短路。
         */
        @Volatile
        var fts5Supported: Boolean = false
            private set

        private fun ensureFtsSchema(db: SupportSQLiteDatabase) {
            val supported = runCatching {
                db.query("SELECT sqlite_compileoption_used('ENABLE_FTS5')").use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) == 1
                }
            }.getOrDefault(false)
            if (!supported) return
            val created = runCatching {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS memory_atoms_fts USING fts5(" +
                        "content, category, search_grams, " +
                        "content='memory_atoms', content_rowid='rowid', " +
                        "tokenize='unicode61')"
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS memory_atoms_fts_ai AFTER INSERT ON memory_atoms BEGIN " +
                        "INSERT INTO memory_atoms_fts(rowid, content, category, search_grams) " +
                        "VALUES (new.rowid, new.content, new.category, new.search_grams); END"
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS memory_atoms_fts_ad AFTER DELETE ON memory_atoms BEGIN " +
                        "INSERT INTO memory_atoms_fts(memory_atoms_fts, rowid, content, category, search_grams) " +
                        "VALUES('delete', old.rowid, old.content, old.category, old.search_grams); END"
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS memory_atoms_fts_au AFTER UPDATE ON memory_atoms BEGIN " +
                        "INSERT INTO memory_atoms_fts(memory_atoms_fts, rowid, content, category, search_grams) " +
                        "VALUES('delete', old.rowid, old.content, old.category, old.search_grams); " +
                        "INSERT INTO memory_atoms_fts(rowid, content, category, search_grams) " +
                        "VALUES (new.rowid, new.content, new.category, new.search_grams); END"
                )
            }.isSuccess
            fts5Supported = created
        }
    }
}
