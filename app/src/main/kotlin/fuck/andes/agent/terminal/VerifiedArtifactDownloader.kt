package fuck.andes.agent.terminal

import fuck.andes.core.AndroidAgentLogger
import fuck.andes.core.AgentLogger
import fuck.andes.core.safeLogType
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class VerifiedArtifact(
    val id: String,
    val version: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val fallbackUrls: List<String> = emptyList(),
)

internal class VerifiedArtifactDownloader(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val logger: AgentLogger = AndroidAgentLogger,
) {
    suspend fun download(
        artifact: VerifiedArtifact,
        target: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Boolean {
        target.parentFile?.mkdirs()
        if (verify(artifact, target)) return true
        val candidates = listOf(artifact.url) + artifact.fallbackUrls
        for ((attempt, url) in candidates.withIndex()) {
            target.delete()
            if (downloadOnce(artifact, target, url, attempt + 1, onProgress)) return true
        }
        target.delete()
        return false
    }

    private suspend fun downloadOnce(
        artifact: VerifiedArtifact,
        target: File,
        url: String,
        attempt: Int,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Boolean {
        val existingBytes = if (target.isFile) target.length() else 0L
        val resumeFrom = if (existingBytes in 1 until artifact.sizeBytes) existingBytes else 0L
        val requestBuilder = Request.Builder().url(url)
        if (resumeFrom > 0L) {
            requestBuilder.header("Range", "bytes=$resumeFrom-")
        }
        val request = requestBuilder.get().build()
        val valid = try {
            httpClient.newCall(request).execute().use responseUse@ { response ->
                if (!response.isSuccessful) {
                    logger.warn(
                        "Verified artifact action=download id=${artifact.id} attempt=$attempt " +
                            "outcome=failed httpCode=${response.code}",
                    )
                    return@responseUse false
                }
                val appendMode = resumeFrom > 0L && response.code == HttpURLConnection.HTTP_PARTIAL
                val declaredLength = response.body.contentLength()
                if (declaredLength > artifact.sizeBytes - resumeFrom) return@responseUse false
                val digest = if (appendMode) {
                    digestOfExisting(target) ?: return@responseUse false
                } else {
                    MessageDigest.getInstance("SHA-256")
                }
                var bytesRead = if (appendMode) resumeFrom else 0L
                var lastReported = bytesRead
                var tooLarge = false
                val output = if (appendMode) FileOutputStream(target, true) else FileOutputStream(target)
                output.buffered().use { buffered ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytesRead += count.toLong()
                            if (bytesRead > artifact.sizeBytes) {
                                tooLarge = true
                                break
                            }
                            digest.update(buffer, 0, count)
                            buffered.write(buffer, 0, count)
                            if (bytesRead - lastReported >= PROGRESS_INTERVAL_BYTES) {
                                lastReported = bytesRead
                                onProgress(bytesRead, artifact.sizeBytes)
                            }
                        }
                    }
                }
                if (tooLarge) return@responseUse false
                val actualSha256 = digest.digest().toHexString()
                val accepted = bytesRead == artifact.sizeBytes && actualSha256 == artifact.sha256
                logger.info(
                    "Verified artifact action=download id=${artifact.id} attempt=$attempt " +
                        "outcome=${if (accepted) "succeeded" else "rejected"} bytes=$bytesRead resume=$resumeFrom",
                )
                accepted
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.warn(
                "Verified artifact action=download id=${artifact.id} attempt=$attempt outcome=failed " +
                    "errorType=${throwable.safeLogType()}",
            )
            false
        }
        return valid
    }

    private fun digestOfExisting(file: File): MessageDigest? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest
    }.getOrNull()

    fun verify(artifact: VerifiedArtifact, file: File): Boolean {
        if (!file.isFile || file.length() != artifact.sizeBytes) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().toHexString() == artifact.sha256
        }.getOrDefault(false)
    }

    companion object {
        private const val PROGRESS_INTERVAL_BYTES = 256L * 1024L

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
