package com.scluzlep.searchcx.net

import com.scluzlep.searchcx.data.ProviderProfile
import com.scluzlep.searchcx.data.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatTurn(
    val role: String,
    val content: String,
    val imageBase64Jpeg: String? = null
)

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ReasoningDelta(val text: String) : StreamEvent
    data object Done : StreamEvent
}

@Deprecated("Kept for compatibility; prefer StreamEvent", replaceWith = ReplaceWith("StreamEvent"))
sealed interface SseEvent {
    data class Delta(val content: String) : SseEvent
    data object Done : SseEvent
}

/**
 * Stateful splitter that extracts `<think>...</think>` reasoning spans
 * across arbitrary streaming chunk boundaries without dropping prefixes.
 */
class ThinkTagSplitter {
    private val buffer = StringBuilder()
    private var inThink = false

    fun accept(chunk: String): List<StreamEvent> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(chunk)
        val out = mutableListOf<StreamEvent>()

        while (true) {
            val tag = if (inThink) CLOSE_TAG else OPEN_TAG
            val idx = buffer.indexOf(tag)
            if (idx >= 0) {
                if (idx > 0) {
                    val seg = buffer.substring(0, idx)
                    out.add(if (inThink) StreamEvent.ReasoningDelta(seg) else StreamEvent.TextDelta(seg))
                }
                buffer.delete(0, idx + tag.length)
                inThink = !inThink
            } else {
                val safeLen = safeEmitLength(buffer, tag)
                if (safeLen > 0) {
                    val seg = buffer.substring(0, safeLen)
                    out.add(if (inThink) StreamEvent.ReasoningDelta(seg) else StreamEvent.TextDelta(seg))
                    buffer.delete(0, safeLen)
                }
                break
            }
        }
        return out
    }

    fun flush(): List<StreamEvent> {
        if (buffer.isEmpty()) return emptyList()
        val rem = buffer.toString()
        buffer.setLength(0)
        return listOf(if (inThink) StreamEvent.ReasoningDelta(rem) else StreamEvent.TextDelta(rem))
    }

    private fun safeEmitLength(sb: StringBuilder, tag: String): Int {
        val len = sb.length
        val maxPrefix = (tag.length - 1).coerceAtMost(len)
        for (tail in maxPrefix downTo 1) {
            if (tag.regionMatches(0, sb, len - tail, tail)) {
                return len - tail
            }
        }
        return len
    }

    companion object {
        private const val OPEN_TAG = "<think>"
        private const val CLOSE_TAG = "</think>"
    }
}

object OpenAiSseParser {

    fun parseLine(line: String): SseEvent? {
        val events = parseChatCompletionsLine(line, thinkSplitter = null)
        for (ev in events) {
            when (ev) {
                is StreamEvent.TextDelta -> return SseEvent.Delta(ev.text)
                StreamEvent.Done -> return SseEvent.Done
                is StreamEvent.ReasoningDelta -> Unit
            }
        }
        return null
    }

    fun parseChatCompletionsLine(line: String, thinkSplitter: ThinkTagSplitter?): List<StreamEvent> {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return emptyList()
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return emptyList()
        if (payload == "[DONE]") {
            val flushed = thinkSplitter?.flush().orEmpty()
            return flushed + StreamEvent.Done
        }

        val root = JSONObject(payload)
        throwIfUpstreamError(root)

        val choices = root.optJSONArray("choices") ?: return emptyList()
        if (choices.length() == 0) return emptyList()
        val first = choices.optJSONObject(0) ?: return emptyList()
        val delta = first.optJSONObject("delta") ?: first.optJSONObject("message") ?: return emptyList()

        val out = mutableListOf<StreamEvent>()
        val reasoning = extractOptText(delta, "reasoning_content")
            ?: extractOptText(delta, "reasoning")
        if (!reasoning.isNullOrEmpty()) {
            out.add(StreamEvent.ReasoningDelta(reasoning))
        }

        val content = extractOptText(delta, "content")
        if (!content.isNullOrEmpty()) {
            if (thinkSplitter != null) {
                out.addAll(thinkSplitter.accept(content))
            } else {
                out.add(StreamEvent.TextDelta(content))
            }
        }
        return out
    }

    fun parseResponsesSseData(eventName: String?, data: String): List<StreamEvent> {
        val payload = data.trim()
        if (payload.isEmpty()) return emptyList()
        if (payload == "[DONE]") return listOf(StreamEvent.Done)

        val root = JSONObject(payload)
        throwIfUpstreamError(root)
        val type = root.optString("type", eventName.orEmpty())

        return when (type) {
            "response.output_text.delta" -> {
                val delta = root.optString("delta", "")
                if (delta.isNotEmpty()) listOf(StreamEvent.TextDelta(delta)) else emptyList()
            }
            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta" -> {
                val delta = root.optString("delta", "")
                if (delta.isNotEmpty()) listOf(StreamEvent.ReasoningDelta(delta)) else emptyList()
            }
            "response.output_text.done" -> emptyList()
            "response.completed", "response.done" -> listOf(StreamEvent.Done)
            "response.failed" -> {
                val msg = root.optJSONObject("response")
                    ?.optJSONObject("error")
                    ?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Upstream Responses API failed"
                throw IllegalStateException("Upstream API error: $msg")
            }
            "error" -> {
                val msg = root.optString("message").takeIf { it.isNotBlank() } ?: payload
                throw IllegalStateException("Upstream API error: $msg")
            }
            else -> emptyList()
        }
    }

    fun parseNonStreamingContent(rawJson: String): String {
        val root = JSONObject(rawJson)
        throwIfUpstreamError(root)

        if (root.has("output") && root.optJSONArray("output") != null) {
            val outputArr = root.getJSONArray("output")
            val sb = StringBuilder()
            for (i in 0 until outputArr.length()) {
                val item = outputArr.optJSONObject(i) ?: continue
                if (item.optString("type") == "message") {
                    val contentArr = item.optJSONArray("content") ?: continue
                    for (j in 0 until contentArr.length()) {
                        val part = contentArr.optJSONObject(j) ?: continue
                        val t = part.optString("text", "")
                        if (t.isNotEmpty()) sb.append(t)
                    }
                }
            }
            if (sb.isNotEmpty()) return sb.toString()
        }

        val choices = root.optJSONArray("choices")
            ?: throw IllegalStateException("Response missing choices array")
        if (choices.length() == 0) {
            throw IllegalStateException("Response choices array is empty")
        }
        val msg = choices.getJSONObject(0).optJSONObject("message")
            ?: throw IllegalStateException("Response missing message object")
        val content = if (msg.isNull("content")) "" else msg.optString("content", "")
        if (content.isBlank()) {
            throw IllegalStateException("Response message content is empty")
        }
        return content
    }

    fun parseStandardModels(rawJson: String): List<String> {
        val root = JSONObject(rawJson)
        throwIfUpstreamError(root)
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>(data.length())
        for (i in 0 until data.length()) {
            val id = data.optJSONObject(i)?.optString("id", "")?.trim().orEmpty()
            if (id.isNotEmpty()) out.add(id)
        }
        return out.distinct().sorted()
    }

    fun parseCodexModels(rawJson: String): List<String> {
        val root = JSONObject(rawJson)
        throwIfUpstreamError(root)
        val models = root.optJSONArray("models") ?: return emptyList()
        val visible = mutableListOf<String>()
        val all = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val obj = models.optJSONObject(i) ?: continue
            val slug = obj.optString("slug", "").trim()
            if (slug.isEmpty()) continue
            all.add(slug)
            if (obj.optString("visibility", "") == "list") {
                visible.add(slug)
            }
        }
        return (if (visible.isNotEmpty()) visible else all).distinct()
    }

    fun buildChatCompletionsPayload(
        model: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        temperature: Double
    ): JSONObject {
        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt.trim()))
        }
        for (turn in history) {
            if (!turn.imageBase64Jpeg.isNullOrBlank()) {
                val parts = JSONArray().apply {
                    if (turn.content.isNotBlank()) {
                        put(JSONObject().put("type", "text").put("text", turn.content))
                    }
                    put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put("url", "data:image/jpeg;base64,${turn.imageBase64Jpeg}")
                            )
                    )
                }
                messages.put(JSONObject().put("role", turn.role).put("content", parts))
            } else {
                messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
            }
        }
        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", temperature)
            put("stream", true)
        }
    }

    fun buildResponsesPayload(
        model: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        temperature: Double?,
        isCodex: Boolean
    ): JSONObject {
        val input = JSONArray()
        if (systemPrompt.isNotBlank()) {
            input.put(
                JSONObject()
                    .put("role", if (isCodex) "developer" else "system")
                    .put("content", systemPrompt.trim())
            )
        }
        for (turn in history) {
            if (!turn.imageBase64Jpeg.isNullOrBlank()) {
                val parts = JSONArray().apply {
                    if (turn.content.isNotBlank()) {
                        put(JSONObject().put("type", "input_text").put("text", turn.content))
                    }
                    put(
                        JSONObject()
                            .put("type", "input_image")
                            .put("image_url", "data:image/jpeg;base64,${turn.imageBase64Jpeg}")
                    )
                }
                input.put(JSONObject().put("role", turn.role).put("content", parts))
            } else {
                input.put(JSONObject().put("role", turn.role).put("content", turn.content))
            }
        }
        return JSONObject().apply {
            put("model", model)
            put("input", input)
            put("stream", true)
            put("store", false)
            if (!isCodex && temperature != null) {
                put("temperature", temperature)
            }
        }
    }

    fun formatHttpError(code: Int, body: String): String {
        val snippet = body.trim().take(400)
        return if (snippet.isEmpty()) "HTTP $code (empty body)" else "HTTP $code: $snippet"
    }

    private fun extractOptText(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val s = obj.optString(key, "")
        return s.takeIf { it.isNotEmpty() }
    }

    private fun throwIfUpstreamError(root: JSONObject) {
        if (root.has("error") && !root.isNull("error")) {
            val errObj = root.optJSONObject("error")
            val msg = errObj?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.opt("error").toString()
            throw IllegalStateException("Upstream API error: $msg")
        }
    }
}

class OpenAiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val oauthManager: PkceOAuthManager = PkceOAuthManager(http)
) {
    private val refreshMutex = Mutex()

    suspend fun fetchModels(
        profile: ProviderProfile,
        onProfileUpdated: (suspend (ProviderProfile) -> Unit)? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val ready = ensureFreshCredential(profile, forceRefresh = false, onProfileUpdated)
        val base = ready.normalizedBaseUrl()
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Base URL 必须以 http:// 或 https:// 开头"
        }

        suspend fun execute(p: ProviderProfile): Response {
            val url = if (p.type == ProviderType.CODEX_OAUTH) {
                "$base/models?client_version=$CODEX_CLIENT_VERSION"
            } else {
                "$base/models"
            }
            val builder = Request.Builder().url(url).get()
            applyProfileHeaders(builder, p)
            return http.newCall(builder.build()).execute()
        }

        var current = ready
        execute(current).use { first ->
            if (first.code == 401 && current.usesOAuth() && current.oauthRefreshToken.isNotBlank()) {
                first.close()
                current = ensureFreshCredential(current, forceRefresh = true, onProfileUpdated)
                execute(current).use { retryResp ->
                    val raw = retryResp.body?.string().orEmpty()
                    if (!retryResp.isSuccessful) {
                        throw IllegalStateException(OpenAiSseParser.formatHttpError(retryResp.code, raw))
                    }
                    return@withContext if (current.type == ProviderType.CODEX_OAUTH) {
                        OpenAiSseParser.parseCodexModels(raw)
                    } else {
                        OpenAiSseParser.parseStandardModels(raw)
                    }
                }
            }
            val raw = first.body?.string().orEmpty()
            if (!first.isSuccessful) {
                throw IllegalStateException(OpenAiSseParser.formatHttpError(first.code, raw))
            }
            return@withContext if (current.type == ProviderType.CODEX_OAUTH) {
                OpenAiSseParser.parseCodexModels(raw)
            } else {
                OpenAiSseParser.parseStandardModels(raw)
            }
        }
    }

    suspend fun testConnection(
        profile: ProviderProfile,
        onProfileUpdated: (suspend (ProviderProfile) -> Unit)? = null
    ): String {
        val models = fetchModels(profile, onProfileUpdated)
        return if (models.isNotEmpty()) {
            "连接成功（${profile.name}）：获取到 ${models.size} 个可用模型，首个 ${models.first()}"
        } else {
            "连接成功（${profile.name}）：接口响应正常"
        }
    }

    fun streamChat(
        profile: ProviderProfile,
        systemPrompt: String,
        history: List<ChatTurn>,
        onProfileUpdated: (suspend (ProviderProfile) -> Unit)? = null
    ): Flow<StreamEvent> = callbackFlow {
        val initial = runCatching { ensureFreshCredential(profile, forceRefresh = false, onProfileUpdated) }
            .getOrElse {
                close(it)
                return@callbackFlow
            }
        val base = initial.normalizedBaseUrl()
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            close(IllegalArgumentException("Base URL 必须以 http:// 或 https:// 开头"))
            return@callbackFlow
        }
        val model = initial.model.trim()
        if (model.isEmpty()) {
            close(IllegalArgumentException("请先配置 Model 名称"))
            return@callbackFlow
        }

        val isResponses = initial.type == ProviderType.OPENAI_RESPONSES || initial.type == ProviderType.CODEX_OAUTH
        val endpoint = if (isResponses) "$base/responses" else "$base/chat/completions"

        fun buildRequest(p: ProviderProfile): Request {
            val payload = if (isResponses) {
                OpenAiSseParser.buildResponsesPayload(
                    model = model,
                    systemPrompt = systemPrompt,
                    history = history,
                    temperature = if (p.type == ProviderType.CODEX_OAUTH) null else p.temperature,
                    isCodex = p.type == ProviderType.CODEX_OAUTH
                )
            } else {
                OpenAiSseParser.buildChatCompletionsPayload(
                    model = model,
                    systemPrompt = systemPrompt,
                    history = history,
                    temperature = p.temperature
                )
            }
            val builder = Request.Builder()
                .url(endpoint)
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            applyProfileHeaders(builder, p)
            return builder.build()
        }

        var activeCall: Call? = null
        var retried401 = false

        fun launchCall(p: ProviderProfile) {
            val call = http.newCall(buildRequest(p))
            activeCall = call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) close(e) else close()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (resp.code == 401 && !retried401 && p.usesOAuth() && p.oauthRefreshToken.isNotBlank()) {
                            retried401 = true
                            try {
                                val refreshed = kotlinx.coroutines.runBlocking {
                                    ensureFreshCredential(p, forceRefresh = true, onProfileUpdated)
                                }
                                launchCall(refreshed)
                                return
                            } catch (t: Throwable) {
                                close(t)
                                return
                            }
                        }
                        if (!resp.isSuccessful) {
                            val errBody = resp.body?.string().orEmpty()
                            close(IllegalStateException(OpenAiSseParser.formatHttpError(resp.code, errBody)))
                            return
                        }
                        val body = resp.body
                        if (body == null) {
                            close(IllegalStateException("HTTP ${resp.code}: empty response body"))
                            return
                        }
                        val contentType = resp.header("Content-Type").orEmpty()
                        if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                            val raw = body.string()
                            runCatching { OpenAiSseParser.parseNonStreamingContent(raw) }
                                .onSuccess {
                                    trySend(StreamEvent.TextDelta(it))
                                    trySend(StreamEvent.Done)
                                    close()
                                }
                                .onFailure { close(it) }
                            return
                        }

                        try {
                            val source = body.source()
                            val thinkSplitter = ThinkTagSplitter()
                            var currentEventName: String? = null
                            val dataLines = StringBuilder()

                            fun dispatchBufferedEvent(): Boolean {
                                if (dataLines.isEmpty()) {
                                    currentEventName = null
                                    return false
                                }
                                val data = dataLines.toString()
                                dataLines.setLength(0)
                                val events = if (isResponses) {
                                    OpenAiSseParser.parseResponsesSseData(currentEventName, data)
                                } else {
                                    OpenAiSseParser.parseChatCompletionsLine("data: $data", thinkSplitter)
                                }
                                currentEventName = null
                                var done = false
                                for (ev in events) {
                                    trySend(ev)
                                    if (ev == StreamEvent.Done) done = true
                                }
                                return done
                            }

                            while (!call.isCanceled()) {
                                val line = source.readUtf8Line() ?: break
                                when {
                                    line.isEmpty() -> {
                                        if (dispatchBufferedEvent()) break
                                    }
                                    line.startsWith(":") -> Unit
                                    line.startsWith("event:") -> {
                                        currentEventName = line.removePrefix("event:").trim()
                                    }
                                    line.startsWith("data:") -> {
                                        val piece = line.removePrefix("data:").trimStart()
                                        if (isResponses) {
                                            if (dataLines.isNotEmpty()) dataLines.append('\n')
                                            dataLines.append(piece)
                                        } else {
                                            var done = false
                                            for (ev in OpenAiSseParser.parseChatCompletionsLine(line, thinkSplitter)) {
                                                trySend(ev)
                                                if (ev == StreamEvent.Done) done = true
                                            }
                                            if (done) break
                                        }
                                    }
                                }
                            }
                            dispatchBufferedEvent()
                            for (flushed in thinkSplitter.flush()) {
                                trySend(flushed)
                            }
                            close()
                        } catch (t: Throwable) {
                            if (!call.isCanceled()) close(t) else close()
                        }
                    }
                }
            })
        }

        launchCall(initial)
        awaitClose { activeCall?.cancel() }
    }

    private fun applyProfileHeaders(builder: Request.Builder, profile: ProviderProfile) {
        val token = profile.activeBearerToken()
        builder.header("Authorization", "Bearer $token")
        if (profile.type == ProviderType.CODEX_OAUTH) {
            if (profile.oauthAccountId.isNotBlank()) {
                builder.header("ChatGPT-Account-ID", profile.oauthAccountId)
            }
            builder.header("originator", PkceOAuthManager.CODEX_ORIGINATOR)
        }
    }

    private suspend fun ensureFreshCredential(
        profile: ProviderProfile,
        forceRefresh: Boolean,
        onProfileUpdated: (suspend (ProviderProfile) -> Unit)?
    ): ProviderProfile {
        if (!profile.usesOAuth()) return profile
        val shouldRefresh = forceRefresh || profile.isTokenExpiringSoon() ||
            (profile.oauthAccessToken.isBlank() && profile.oauthRefreshToken.isNotBlank())
        if (!shouldRefresh) return profile

        val refresh = profile.oauthRefreshToken.trim()
        if (refresh.isEmpty()) {
            if (forceRefresh) {
                throw IllegalStateException("「${profile.name}」登录凭据已失效且无 Refresh Token，请重新登录。")
            }
            return profile
        }

        return refreshMutex.withLock {
            val updated = when (profile.type) {
                ProviderType.CODEX_OAUTH -> {
                    val res = oauthManager.refreshCodexToken(
                        refreshToken = refresh,
                        previousAccountId = profile.oauthAccountId,
                        previousEmail = profile.oauthEmail,
                        previousPlan = profile.oauthPlan
                    )
                    profile.copy(
                        oauthAccessToken = res.accessToken,
                        oauthRefreshToken = res.refreshToken ?: refresh,
                        oauthExpiresAtMs = res.expiresAtMs,
                        oauthAccountId = res.accountId,
                        oauthEmail = res.email,
                        oauthPlan = res.plan
                    )
                }
                ProviderType.CUSTOM_OAUTH -> {
                    val res = oauthManager.refreshAccessToken(
                        tokenEndpoint = profile.oauthTokenUrl,
                        clientId = profile.oauthClientId,
                        refreshToken = refresh
                    )
                    val expMs = if (res.expiresInSec > 0L) {
                        System.currentTimeMillis() + res.expiresInSec * 1000L
                    } else {
                        profile.oauthExpiresAtMs
                    }
                    profile.copy(
                        oauthAccessToken = res.accessToken,
                        oauthRefreshToken = res.refreshToken ?: refresh,
                        oauthExpiresAtMs = expMs
                    )
                }
                else -> profile
            }
            onProfileUpdated?.invoke(updated)
            updated
        }
    }

    companion object {
        private const val CODEX_CLIENT_VERSION = "0.163.0"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
