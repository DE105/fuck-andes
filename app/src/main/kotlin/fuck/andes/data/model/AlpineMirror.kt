package fuck.andes.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AlpineMirror(
    val persistedValue: String,
    val baseUrl: String,
) {
    @SerialName("official")
    OFFICIAL("official", "https://dl-cdn.alpinelinux.org/alpine"),

    @SerialName("aliyun")
    ALIYUN("aliyun", "https://mirrors.aliyun.com/alpine"),

    @SerialName("tuna")
    TUNA("tuna", "https://mirrors.tuna.tsinghua.edu.cn/alpine"),

    @SerialName("ustc")
    USTC("ustc", "https://mirrors.ustc.edu.cn/alpine"),

    @SerialName("custom")
    CUSTOM("custom", ""),
    ;

    fun effectiveBaseUrl(customUrl: String?): String {
        if (this != CUSTOM) return baseUrl
        val normalized = customUrl?.trim()?.trimEnd('/')
        return normalized
            ?.takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: OFFICIAL.baseUrl
    }

    companion object {
        fun fromPersistedValue(value: String?): AlpineMirror =
            entries.firstOrNull { it.persistedValue == value } ?: OFFICIAL
    }
}
