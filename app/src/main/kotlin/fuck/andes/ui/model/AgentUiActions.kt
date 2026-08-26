package fuck.andes.ui.model

import fuck.andes.data.model.ReasoningEffort

sealed interface AgentHomeAction {
    data class ReasoningEffortChanged(val effort: ReasoningEffort) : AgentHomeAction
    data class ModelSelected(val modelId: String) : AgentHomeAction
    data class SubmitMessage(val text: String) : AgentHomeAction
    data object StopRun : AgentHomeAction
    data class ImageAttached(val uri: String) : AgentHomeAction
    data class RemoveImage(val id: String) : AgentHomeAction
    data class FilesAttached(val uris: List<String>) : AgentHomeAction
    data class FolderAttached(val uri: String) : AgentHomeAction
    data class FilePathAttached(val path: String) : AgentHomeAction
    data class RemoveFileReference(val id: String) : AgentHomeAction
    data class EditMessage(val id: String) : AgentHomeAction
    data object CancelMessageEdit : AgentHomeAction
    data class DeleteMessage(val id: String) : AgentHomeAction
    data class RegenerateMessage(val id: String) : AgentHomeAction
    data object OpenTools : AgentHomeAction
    data object OpenSkills : AgentHomeAction
    data object OpenPermissions : AgentHomeAction
    data object OpenSystemEnhance : AgentHomeAction
    data object OpenSettings : AgentHomeAction
    data object OpenBrowser : AgentHomeAction
    data object ExpandRunTrace : AgentHomeAction
}

sealed interface PermissionHealthAction {
    data class OpenItemAction(val itemId: String) : PermissionHealthAction
    data object NavigateBack : PermissionHealthAction
}

sealed interface AgentChatAction {
    data object NavigateBack : AgentChatAction
    data class ReasoningEffortChanged(val effort: ReasoningEffort) : AgentChatAction
    data class ModelSelected(val modelId: String) : AgentChatAction
    data class SubmitMessage(val text: String) : AgentChatAction
    data object StopRun : AgentChatAction
    data object OpenBrowser : AgentChatAction
    data class ImageAttached(val uri: String) : AgentChatAction
    data class RemoveImage(val id: String) : AgentChatAction
    data class FilesAttached(val uris: List<String>) : AgentChatAction
    data class FolderAttached(val uri: String) : AgentChatAction
    data class FilePathAttached(val path: String) : AgentChatAction
    data class RemoveFileReference(val id: String) : AgentChatAction
    data class EditMessage(val id: String) : AgentChatAction
    data object CancelMessageEdit : AgentChatAction
    data class DeleteMessage(val id: String) : AgentChatAction
    data class RegenerateMessage(val id: String) : AgentChatAction
}

sealed interface AgentToolsAction {
    data object NavigateBack : AgentToolsAction
    data object OpenBrowser : AgentToolsAction
}

sealed interface AgentSkillsAction {
    data object NavigateBack : AgentSkillsAction
    data class ImportZip(val uri: String) : AgentSkillsAction
    data object ConfirmZipReplacement : AgentSkillsAction
    data object CancelZipReplacement : AgentSkillsAction
    data object DismissNotice : AgentSkillsAction
    data class ToggleSkill(val skillId: String, val enabled: Boolean) : AgentSkillsAction
    data class DeleteSkill(val skillId: String) : AgentSkillsAction
    data class ReinstallBuiltin(val skillId: String) : AgentSkillsAction
}

sealed interface AgentSystemEnhanceAction {
    data object NavigateBack : AgentSystemEnhanceAction
    data class ToggleItem(val itemId: String) : AgentSystemEnhanceAction
}

sealed interface AgentMemoryAction {
    data object NavigateBack : AgentMemoryAction
    data class ToggleEnabled(val enabled: Boolean) : AgentMemoryAction
    data class DraftChanged(val content: String) : AgentMemoryAction
    data object Save : AgentMemoryAction
    data object Clear : AgentMemoryAction
    data object DismissNotice : AgentMemoryAction
    // ── 本地四层记忆 ──
    data class ToggleFourLayerEnabled(val enabled: Boolean) : AgentMemoryAction
    data class ToggleDistillMdSync(val enabled: Boolean) : AgentMemoryAction
    data class AtomInputChanged(val content: String) : AgentMemoryAction
    data class AtomCategoryChanged(val category: String) : AgentMemoryAction
    data object AddAtom : AgentMemoryAction
    data class DeleteAtom(val id: String) : AgentMemoryAction
    data class ScenarioNameChanged(val name: String) : AgentMemoryAction
    data class ScenarioContentChanged(val content: String) : AgentMemoryAction
    data object SaveScenario : AgentMemoryAction
    data class DeleteScenario(val id: String) : AgentMemoryAction
    data class ProfileKeyChanged(val key: String) : AgentMemoryAction
    data class ProfileValueChanged(val value: String) : AgentMemoryAction
    data object AddProfile : AgentMemoryAction
    data class DeleteProfile(val key: String) : AgentMemoryAction
    data object RefreshLayers : AgentMemoryAction
}
