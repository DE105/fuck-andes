package fuck.andes.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val alpineMirror: AlpineMirror = AlpineMirror.OFFICIAL,
)
