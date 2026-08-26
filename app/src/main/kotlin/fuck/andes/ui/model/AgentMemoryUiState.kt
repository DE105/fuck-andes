package fuck.andes.ui.model

import androidx.compose.runtime.Immutable
import fuck.andes.data.repository.AgentMemoryStore

@Immutable
data class MemoryAtomUi(
    val id: String,
    val content: String,
    val category: String,
    val updatedAt: Long,
)

@Immutable
data class MemoryScenarioUi(
    val id: String,
    val name: String,
    val content: String,
    val updatedAt: Long,
)

@Immutable
data class MemoryProfileUi(
    val key: String,
    val value: String,
    val updatedAt: Long,
)


@Immutable
data class AgentMemoryUiState(
    val enabled: Boolean = true,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val draft: String = "",
    val savedContent: String = "",
    val draftBytes: Int = 0,
    val maxBytes: Int = AgentMemoryStore.MAX_FILE_BYTES,
    val coreBudgetChars: Int = 8_000,
    // ── 本地四层记忆（参照 TencentDB Agent Memory 的 L0–L3）──
    val fourLayerEnabled: Boolean = true,
    val fourLayerLoading: Boolean = false,
    // MEMORY.md 自动蒸馏同步（蒸馏出的画像级核心事实写入 MEMORY.md）
    val distillMdSync: Boolean = false,
    val atoms: List<MemoryAtomUi> = emptyList(),
    val scenarios: List<MemoryScenarioUi> = emptyList(),
    val profile: List<MemoryProfileUi> = emptyList(),
    val conversationCount: Int = 0,
    // 新增/编辑输入框草稿
    val atomInput: String = "",
    val atomCategory: String = "general",
    val scenarioNameInput: String = "",
    val scenarioContentInput: String = "",
    val profileKeyInput: String = "",
    val profileValueInput: String = "",
    val notice: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = draft != savedContent
    val canSave: Boolean
        get() = !isLoading && !isSaving && hasUnsavedChanges &&
            draftBytes <= maxBytes
}
