package fuck.andes.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import fuck.andes.data.model.ModelSource
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FuckAndesDatabaseMigrationTest {
    @Test
    fun migration6To10PreservesDataAndAddsRunMetrics() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "migration-${UUID.randomUUID()}.db"
        createVersion6Database(context, databaseName)

        val database = Room.databaseBuilder(context, FuckAndesDatabase::class.java, databaseName)
            .addMigrations(
                FuckAndesDatabase.MIGRATION_6_7,
                FuckAndesDatabase.MIGRATION_7_8,
                FuckAndesDatabase.MIGRATION_8_9,
                FuckAndesDatabase.MIGRATION_9_10,
            )
            .build()
        try {
            val result = runBlocking(Dispatchers.IO) {
                database.runtimeRunDao().runtimeResults().single()
            }
            val archive = runBlocking(Dispatchers.IO) {
                database.runtimeRunDao().archivedRuns().single().run
            }
            val conversations = runBlocking(Dispatchers.IO) {
                database.conversationDao().conversations()
            }
            val provider = runBlocking(Dispatchers.IO) {
                database.providerDao().providerById("provider-1")!!.toDomain()
            }

            assertEquals("保留的结果", result.content)
            assertEquals("[]", result.transcriptJson)
            assertEquals(null, result.runInputTokens)
            assertEquals(null, result.runElapsedMs)
            assertEquals("保留的归档", archive.content)
            assertEquals("[]", archive.transcriptJson)
            assertEquals(null, archive.runOutputTokens)
            assertEquals(null, archive.runElapsedMs)
            assertEquals(setOf("conv-1", "conv-custom-empty"), conversations.mapTo(mutableSetOf()) { it.id })
            assertEquals("[]", conversations.first { it.id == "conv-1" }.appliedRuntimeRunIdsJson)
            assertEquals(null, runBlocking(Dispatchers.IO) { database.conversationDao().state() })
            assertEquals(listOf("built-in", "manual"), provider.models.map { it.modelId })
            assertEquals(
                listOf(ModelSource.CATALOG, ModelSource.MANUAL),
                provider.models.map { it.source },
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun createVersion6Database(
        context: Context,
        databaseName: String,
    ) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        VERSION_6_SCHEMA.forEach(db::execSQL)
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-1', '保留的对话', 0, '[]', 1, 1)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-bug-empty', '新对话', 0, '[]', 2, 2)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-custom-empty', '用户命名', 0, '[]', 3, 3)"
                        )
                        db.execSQL(
                            "INSERT INTO conversation_state (id, selected_conversation_id) " +
                                "VALUES ('main', 'conv-bug-empty')"
                        )
                        db.execSQL(
                            "INSERT INTO model_providers " +
                                "(id, type, name, base_url, api_key, is_enabled, is_built_in, sort_order, " +
                                "system_prompt, custom_headers_json, custom_body_json, created_at, endpoint_mode, anthropic_version) " +
                                "VALUES ('provider-1', 'openai_compatible', 'Provider', 'https://example.com/v1', '', 1, 0, 0, " +
                                "NULL, '[]', '[]', 1, 'chat_completions', '2023-06-01')"
                        )
                        db.execSQL(providerModelInsert("built-in-id", "built-in", 1, 0))
                        db.execSQL(providerModelInsert("manual-id", "manual", 0, 1))
                        db.execSQL(providerModelInsert("blank-id", "", 0, 2))
                        db.execSQL(
                            "INSERT INTO runtime_results " +
                                "(run_id, handoff_id, handoff_source, handoff_payload, " +
                                "dismiss_entry_surface, ok, content, error, reasoning_content, created_at) " +
                                "VALUES ('run-1', 'handoff-1', 'test', '{}', 0, 1, '保留的结果', NULL, '', 1)"
                        )
                        db.execSQL(
                            "INSERT INTO runtime_archive_runs " +
                                "(archive_run_id, run_id, handoff_id, handoff_source, handoff_payload, " +
                                "dismiss_entry_surface, ok, content, error, reasoning_content, created_at) " +
                                "VALUES ('archive-1', 'run-1', 'handoff-1', 'test', '{}', 0, 1, " +
                                "'保留的归档', NULL, '', 1)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }
            )
            .build()
        FrameworkSQLiteOpenHelperFactory()
            .create(configuration)
            .also { helper ->
                helper.writableDatabase
                helper.close()
            }
    }

    private companion object {
        fun providerModelInsert(id: String, modelId: String, builtIn: Int, sortOrder: Int): String =
            "INSERT INTO provider_models " +
                "(id, provider_id, model_id, display_name, is_enabled, is_built_in, sort_order, owned_by, " +
                "context_window, input_modalities_json, output_modalities_json, attachment, tool_call, reasoning, " +
                "structured_output, supports_temperature, custom_headers_json, custom_body_json, created_at) " +
                "VALUES ('$id', 'provider-1', '$modelId', 'Model', 1, $builtIn, $sortOrder, NULL, NULL, " +
                "'[\"text\"]', '[\"text\"]', NULL, NULL, NULL, NULL, NULL, '[]', '[]', 1)"

        val VERSION_6_SCHEMA = listOf(
            "CREATE TABLE conversations (id TEXT NOT NULL, title TEXT NOT NULL, thinking_enabled INTEGER NOT NULL, history_json TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE conversation_messages (id TEXT NOT NULL, conversation_id TEXT NOT NULL, sort_index INTEGER NOT NULL, type TEXT NOT NULL, content TEXT NOT NULL, images_json TEXT NOT NULL, render_markdown INTEGER, context_tokens INTEGER, input_tokens INTEGER, output_tokens INTEGER, reasoning_tokens INTEGER, cached_tokens INTEGER, elapsed_seconds INTEGER, tool_name TEXT, tool_status TEXT, arguments_summary TEXT, result_summary TEXT, image_count INTEGER NOT NULL, tools_json TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_conversation_messages_conversation_id ON conversation_messages(conversation_id)",
            "CREATE UNIQUE INDEX index_conversation_messages_conversation_id_sort_index ON conversation_messages(conversation_id, sort_index)",
            "CREATE TABLE conversation_state (id TEXT NOT NULL, selected_conversation_id TEXT NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE model_providers (id TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, base_url TEXT NOT NULL, api_key TEXT NOT NULL, is_enabled INTEGER NOT NULL, is_built_in INTEGER NOT NULL, sort_order INTEGER NOT NULL, system_prompt TEXT, custom_headers_json TEXT NOT NULL, custom_body_json TEXT NOT NULL, created_at INTEGER NOT NULL, endpoint_mode TEXT NOT NULL, anthropic_version TEXT NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE provider_models (id TEXT NOT NULL, provider_id TEXT NOT NULL, model_id TEXT NOT NULL, display_name TEXT NOT NULL, is_enabled INTEGER NOT NULL, is_built_in INTEGER NOT NULL, sort_order INTEGER NOT NULL, owned_by TEXT, context_window INTEGER, input_modalities_json TEXT NOT NULL, output_modalities_json TEXT NOT NULL, attachment INTEGER, tool_call INTEGER, reasoning INTEGER, structured_output INTEGER, supports_temperature INTEGER, custom_headers_json TEXT NOT NULL, custom_body_json TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(provider_id) REFERENCES model_providers(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_provider_models_provider_id ON provider_models(provider_id)",
            "CREATE INDEX index_provider_models_provider_id_sort_order ON provider_models(provider_id, sort_order)",
            "CREATE TABLE runtime_results (run_id TEXT NOT NULL, handoff_id TEXT NOT NULL, handoff_source TEXT NOT NULL, handoff_payload TEXT NOT NULL, dismiss_entry_surface INTEGER NOT NULL, ok INTEGER NOT NULL, content TEXT NOT NULL, error TEXT, reasoning_content TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(run_id))",
            "CREATE TABLE runtime_archive_runs (archive_run_id TEXT NOT NULL, run_id TEXT NOT NULL, handoff_id TEXT NOT NULL, handoff_source TEXT NOT NULL, handoff_payload TEXT NOT NULL, dismiss_entry_surface INTEGER NOT NULL, ok INTEGER NOT NULL, content TEXT NOT NULL, error TEXT, reasoning_content TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(archive_run_id))",
            "CREATE TABLE runtime_archive_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, archive_run_id TEXT NOT NULL, sort_index INTEGER NOT NULL, event_json TEXT NOT NULL, FOREIGN KEY(archive_run_id) REFERENCES runtime_archive_runs(archive_run_id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_runtime_archive_events_archive_run_id ON runtime_archive_events(archive_run_id)",
            "CREATE UNIQUE INDEX index_runtime_archive_events_archive_run_id_sort_index ON runtime_archive_events(archive_run_id, sort_index)",
            "CREATE TABLE skill_registry (skill_id TEXT NOT NULL, enabled INTEGER NOT NULL, source TEXT NOT NULL, install_state TEXT NOT NULL, PRIMARY KEY(skill_id))",
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, 'bd87dd0053b011246cba304c35316f07')",
        )
    }
}
