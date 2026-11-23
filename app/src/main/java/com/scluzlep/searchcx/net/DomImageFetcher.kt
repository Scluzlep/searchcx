package com.scluzlep.searchcx.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.scluzlep.searchcx.data.DomImageItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class ParsedDataUrl(
    val mimeType: String,
    val bytes: ByteArray
)

data class ResolvedDomImage(
    val index: Int,
    val chatImage: ChatImage,
    val isFormula: Boolean,
    val alt: String
)

/**
 * Resolves captured DOM image/formula references into normalized ChatImage payloads
 * with white-background compositing and tiny-formula upscaling for multimodal models.
 */
class DomImageFetcher(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    suspend fun resolveDomImages(
        images: List<DomImageItem>,
        pageUrl: String,
        maxImages: Int = DEFAULT_MAX_IMAGES
    ): List<ResolvedDomImage> = withContext(Dispatchers.IO) {
        if (images.isEmpty() || maxImages <= 0) return@withContext emptyList()
        val bounded = images.take(maxImages)
        val semaphore = Semaphore(FETCH_CONCURRENCY)

        val resolvedList = coroutineScope {
            bounded.map { item ->
                async {
                    semaphore.withPermit {
                        resolveSingleImage(item, pageUrl)
                    }
                }
            }.awaitAll()
        }
        resolvedList.filterNotNull().sortedBy { it.index }
    }

    private suspend fun resolveSingleImage(
        item: DomImageItem,
        pageUrl: String
    ): ResolvedDomImage? {
        if (item.dataUrl.isNotBlank()) {
            val parsed = parseDataUrl(item.dataUrl)
            if (parsed != null) {
                val normalized = normalizeImageBytes(parsed.bytes)
                if (normalized != null) {
                    return ResolvedDomImage(
                        index = item.index,
                        chatImage = normalized,
                        isFormula = item.isFormula,
                        alt = item.alt
                    )
                }
            }
        }

        val url = item.url.trim()
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }

        val downloaded = downloadBytes(url, buildReferer(url, pageUrl)) ?: return null
        val normalized = normalizeImageBytes(downloaded) ?: return null
        return ResolvedDomImage(
            index = item.index,
            chatImage = normalized,
            isFormula = item.isFormula,
            alt = item.alt
        )
    }

    private suspend fun downloadBytes(imageUrl: String, referer: String?): ByteArray? {
        val builder = Request.Builder()
            .url(imageUrl)
            .header("User-Agent", MOBILE_USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/png,image/*,*/*;q=0.8")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        return executeDownload(builder.build())
    }

    private suspend fun executeDownload(request: Request): ByteArray? {
        return try {
            suspendCancellableCoroutine { cont ->
                val call = httpClient.newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!cont.isCancelled) {
                            cont.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { resp ->
                            if (!resp.isSuccessful) {
                                cont.resume(null)
                                return
                            }
                            val contentLength = resp.body?.contentLength() ?: -1L
                            if (contentLength > MAX_DOWNLOAD_BYTES) {
                                cont.resume(null)
                                return
                            }
                            val bodyBytes = runCatching {
                                resp.body?.byteStream()?.use { input ->
                                    val buffer = ByteArrayOutputStream()
                                    val chunk = ByteArray(8192)
                                    var total = 0
                                    while (true) {
                                        val read = input.read(chunk)
                                        if (read < 0) break
                                        total += read
                                        if (total > MAX_DOWNLOAD_BYTES) {
                                            return@use null
                                        }
                                        buffer.write(chunk, 0, read)
                                    }
                                    buffer.toByteArray()
                                }
                            }.getOrNull()
                            cont.resume(bodyBytes)
                        }
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizeImageBytes(rawBytes: ByteArray): ChatImage? {
        if (rawBytes.isEmpty() || rawBytes.size > MAX_DOWNLOAD_BYTES) return null
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        var scaled: Bitmap? = null
        var composited: Bitmap? = null
        return try {
            val (targetW, targetH) = computeTargetSize(decoded.width, decoded.height)
            if (targetW <= 0 || targetH <= 0) return null

            val sourceBmp = if (targetW != decoded.width || targetH != decoded.height) {
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also { scaled = it }
            } else {
                decoded
            }

            // Composite onto opaque white canvas so transparent formula PNGs stay legible.
            val canvasBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                .also { composited = it }
            val canvas = Canvas(canvasBmp)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(sourceBmp, 0f, 0f, null)

            val out = ByteArrayOutputStream()
            val mime = if (maxOf(targetW, targetH) <= PNG_THRESHOLD_PX) {
                canvasBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                "image/png"
            } else {
                canvasBmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                "image/jpeg"
            }
            val encoded = Base64.getEncoder().encodeToString(out.toByteArray())
            if (encoded.isBlank()) null else ChatImage(mimeType = mime, base64 = encoded)
        } finally {
            composited?.let { if (!it.isRecycled) it.recycle() }
            scaled?.let { if (it !== decoded && !it.isRecycled) it.recycle() }
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    companion object {
        const val DEFAULT_MAX_IMAGES = 8
        const val MIN_FORMULA_SIDE_PX = 36
        const val MIN_UPSCALED_SIDE_PX = 40
        const val MAX_IMAGE_SIDE_PX = 1280
        const val MAX_UPSCALE_FACTOR = 3.0
        const val PNG_THRESHOLD_PX = 320
        const val JPEG_QUALITY = 85
        const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024
        private const val FETCH_CONCURRENCY = 4
        private const val TIMEOUT_SEC = 5L
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        private val DATA_URL_REGEX = Regex("""^data:([^;,]+)?(?:;[^,]*)*;base64,(.+)$""", RegexOption.DOT_MATCHES_ALL)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

        fun parseDataUrl(raw: String): ParsedDataUrl? {
            val trimmed = raw.trim()
            val match = DATA_URL_REGEX.matchEntire(trimmed) ?: return null
            val mime = match.groupValues[1].trim().ifEmpty { "image/png" }
            val b64 = match.groupValues[2].replace(Regex("""\s+"""), "")
            if (b64.isEmpty()) return null
            val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
            if (bytes.isEmpty()) return null
            return ParsedDataUrl(mimeType = mime, bytes = bytes)
        }

        fun computeTargetSize(width: Int, height: Int): Pair<Int, Int> {
            if (width <= 0 || height <= 0) return width to height
            val minSide = minOf(width, height)
            val maxSide = maxOf(width, height)

            var scale = 1.0
            if (minSide < MIN_FORMULA_SIDE_PX) {
                scale = minOf(MAX_UPSCALE_FACTOR, MIN_UPSCALED_SIDE_PX.toDouble() / minSide.toDouble())
            }
            if (maxSide * scale > MAX_IMAGE_SIDE_PX) {
                scale = MAX_IMAGE_SIDE_PX.toDouble() / maxSide.toDouble()
            }

            val dstW = (width * scale).roundToInt().coerceAtLeast(1)
            val dstH = (height * scale).roundToInt().coerceAtLeast(1)
            return dstW to dstH
        }

        fun buildReferer(imageUrl: String, pageUrl: String): String? {
            val parsedPage = pageUrl.trim().toHttpUrlOrNull()
            if (parsedPage != null) {
                return formatOriginReferer(parsedPage.scheme, parsedPage.host, parsedPage.port)
            }
            val parsedImg = imageUrl.trim().toHttpUrlOrNull()
            if (parsedImg != null) {
                return formatOriginReferer(parsedImg.scheme, parsedImg.host, parsedImg.port)
            }
            return null
        }

        private fun formatOriginReferer(scheme: String, host: String, port: Int): String {
            val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
            val portPart = if (defaultPort) "" else ":$port"
            return "$scheme://$host$portPart/"
        }

        fun buildImageMappingHint(resolved: List<ResolvedDomImage>): String {
            if (resolved.isEmpty()) return ""
            val sb = StringBuilder()
            sb.append("[图片索引对应关系]\n")
            resolved.forEachIndexed { attachIdx, item ->
                val kind = if (item.isFormula) "公式图" else "题图"
                sb.append("- [图片").append(item.index).append("]：第 ")
                    .append(attachIdx + 1).append(" 张附图 · ").append(kind)
                if (item.alt.isNotBlank()) {
                    sb.append(" · alt: ").append(item.alt)
                }
                sb.append('\n')
            }
            return sb.toString().trim()
        }
    }
}
