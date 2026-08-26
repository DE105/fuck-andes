package fuck.andes.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    val fourLayerMemoryEnabled: Boolean = true,
    val memoryAutoDistillEnabled: Boolean = true,
    val memoryDistillCursor: Long = 0L,
    val memoryDistillMdSync: Boolean = false,
    val appearance: AppearanceSettings = AppearanceSettings(),
)
