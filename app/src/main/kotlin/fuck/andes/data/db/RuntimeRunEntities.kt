package fuck.andes.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "runtime_results")
internal data class RuntimeResultEntity(
    @PrimaryKey @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "handoff_id") val handoffId: String,
    @ColumnInfo(name = "handoff_source") val handoffSource: String,
    @ColumnInfo(name = "handoff_payload") val handoffPayload: String,
    @ColumnInfo(name = "dismiss_entry_surface") val dismissEntrySurface: Boolean,
    val ok: Boolean,
    val content: String,
    val error: String?,
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String,
    @ColumnInfo(name = "transcript_json") val transcriptJson: String,
    @ColumnInfo(name = "run_input_tokens") val runInputTokens: Int? = null,
    @ColumnInfo(name = "run_cached_input_tokens") val runCachedInputTokens: Int? = null,
    @ColumnInfo(name = "run_output_tokens") val runOutputTokens: Int? = null,
    @ColumnInfo(name = "run_elapsed_ms") val runElapsedMs: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "runtime_archive_runs")
internal data class RuntimeArchiveRunEntity(
    @PrimaryKey @ColumnInfo(name = "archive_run_id") val archiveRunId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "handoff_id") val handoffId: String,
    @ColumnInfo(name = "handoff_source") val handoffSource: String,
    @ColumnInfo(name = "handoff_payload") val handoffPayload: String,
    @ColumnInfo(name = "dismiss_entry_surface") val dismissEntrySurface: Boolean,
    val ok: Boolean,
    val content: String,
    val error: String?,
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String,
    @ColumnInfo(name = "transcript_json") val transcriptJson: String,
    @ColumnInfo(name = "run_input_tokens") val runInputTokens: Int? = null,
    @ColumnInfo(name = "run_cached_input_tokens") val runCachedInputTokens: Int? = null,
    @ColumnInfo(name = "run_output_tokens") val runOutputTokens: Int? = null,
    @ColumnInfo(name = "run_elapsed_ms") val runElapsedMs: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "runtime_archive_events",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeArchiveRunEntity::class,
            parentColumns = ["archive_run_id"],
            childColumns = ["archive_run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("archive_run_id"),
        Index(value = ["archive_run_id", "sort_index"], unique = true),
    ],
)
internal data class RuntimeArchiveEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "archive_run_id") val archiveRunId: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "event_json") val eventJson: String,
)

internal data class RuntimeArchiveRunWithEvents(
    @Embedded val run: RuntimeArchiveRunEntity,
    @Relation(
        parentColumn = "archive_run_id",
        entityColumn = "archive_run_id",
    )
    val events: List<RuntimeArchiveEventEntity>,
)
