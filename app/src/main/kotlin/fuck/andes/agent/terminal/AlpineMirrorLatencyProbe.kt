package fuck.andes.agent.terminal

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 对候选 Alpine 镜像源做延迟测速：HEAD 请求各镜像的包索引文件，返回毫秒延迟。
 * 请求失败或超时的镜像返回 null。
 */
internal object AlpineMirrorLatencyProbe {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun probe(baseUrls: List<String>): Map<String, Long?> {
        val deduplicated = baseUrls.distinct().filter { it.isNotBlank() }
        return coroutineScope {
            val results = deduplicated.map { baseUrl ->
                baseUrl to async { measureLatencyMillis(baseUrl) }
            }
            results.associate { (baseUrl, deferred) ->
                baseUrl to deferred.await()
            }
        }
    }

    private suspend fun measureLatencyMillis(baseUrl: String): Long? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/v3.24/main/x86_64/APKINDEX.tar.gz")
            .head()
            .build()
        try {
            val start = System.nanoTime()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    (System.nanoTime() - start) / 1_000_000L
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
