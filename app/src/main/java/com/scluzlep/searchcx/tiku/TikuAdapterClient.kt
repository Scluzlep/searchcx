package com.scluzlep.searchcx.tiku

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Dual-mode question-bank search client supporting both remote tikuAdapter instances
 * and direct concurrent built-in aggregation across upstream HTTP providers.
 */
class TikuAdapterClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    suspend fun searchOne(req: TikuSearchRequest, config: TikuConfig): TikuSearchResult {
        val cleanQuestion = OcsQuestionExtractor.normalizeQuestionText(req.question)
        if (cleanQuestion.isBlank()) {
            return emptyResult(req, "题干为空，无法检索")
        }
        val normalizedReq = req.copy(question = cleanQuestion)

        return when (config.mode) {
            TikuMode.REMOTE_ADAPTER -> searchRemote(normalizedReq, config)
            TikuMode.DIRECT_BUILTIN -> searchDirectBuiltin(normalizedReq, config)
        }
    }

    suspend fun searchBatch(
        requests: List<TikuSearchRequest>,
        config: TikuConfig,
        maxItems: Int = 10
    ): List<TikuSearchResult> {
        if (requests.isEmpty() || maxItems <= 0) return emptyList()
        val bounded = requests.take(maxItems)
        val semaphore = Semaphore(BATCH_CONCURRENCY)
        return coroutineScope {
            bounded.map { req ->
                async {
                    semaphore.withPermit {
                        searchOne(req, config)
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun testConnection(config: TikuConfig): Result<String> {
        val probe = TikuSearchRequest(
            question = "中国的首都是哪里",
            options = emptyList(),
            type = TikuQuestionType.QA.code
        )
        return when (config.mode) {
            TikuMode.REMOTE_ADAPTER -> testRemoteConnection(probe, config)
            TikuMode.DIRECT_BUILTIN -> testDirectConnection(probe, config)
        }
    }

    private suspend fun testRemoteConnection(
        probe: TikuSearchRequest,
        config: TikuConfig
    ): Result<String> {
        val url = runCatching { config.buildRemoteSearchUrl(noRecord = true) }
            .getOrElse { return Result.failure(it) }
        val payload = buildRemoteRequestBody(probe).toString()
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return runCatching {
            val response = executeCall(request)
            if (!response.isSuccessful) {
                throw IllegalStateException("远端服务返回 HTTP ${response.code}")
            }
            val body = response.body.trim()
            if (body.isEmpty()) {
                throw IllegalStateException("远端服务返回空响应")
            }
            JSONObject(body)
            val count = config.effectiveRemoteProviders().size
            "远端服务连接正常，已启用 $count 个题库"
        }
    }

    private suspend fun testDirectConnection(
        probe: TikuSearchRequest,
        config: TikuConfig
    ): Result<String> {
        val providers = config.effectiveDirectProviders()
        if (providers.isEmpty()) {
            return Result.failure(IllegalStateException("无可用直连题库，请检查已启用源或令牌"))
        }

        val results = coroutineScope {
            providers.map { provider ->
                async {
                    provider to runCatching {
                        probeDirectProvider(provider, probe, config.tokens.tokenFor(provider).trim())
                    }.onFailure { err ->
                        if (err is CancellationException) throw err
                    }
                }
            }.awaitAll()
        }

        val reachable = results.filter { it.second.isSuccess }.map { it.first.displayName }
        if (reachable.isNotEmpty()) {
            return Result.success("直连探测通过：${reachable.joinToString("、")}")
        }
        val firstErr = results.firstNotNullOfOrNull { it.second.exceptionOrNull()?.message }
            ?: "所有直连题库均不可达"
        return Result.failure(IllegalStateException(firstErr))
    }

    private suspend fun probeDirectProvider(
        provider: TikuProvider,
        req: TikuSearchRequest,
        token: String
    ) {
        val httpReq = buildDirectProviderRequest(provider, req, token)
            ?: throw IllegalStateException("${provider.displayName} 仅支持远端或缺少令牌")
        val res = executeCall(httpReq)
        if (!res.isSuccessful) {
            throw IllegalStateException("${provider.displayName} HTTP ${res.code}")
        }
        val body = res.body.trim()
        if (body.isEmpty()) {
            throw IllegalStateException("${provider.displayName} 返回空响应")
        }
        JSONObject(body)
    }

    private suspend fun searchRemote(
        req: TikuSearchRequest,
        config: TikuConfig
    ): TikuSearchResult {
        if (config.effectiveRemoteProviders().isEmpty()) {
            return emptyResult(req, "未启用任何题库源")
        }
        val url = runCatching { config.buildRemoteSearchUrl(noRecord = false) }
            .getOrElse { return emptyResult(req, it.message ?: "远端地址无效") }

        val payload = buildRemoteRequestBody(req).toString()
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            val httpResp = executeCall(request)
            if (!httpResp.isSuccessful) {
                return emptyResult(req, "远端服务 HTTP ${httpResp.code}")
            }
            parseRemoteResponse(req, httpResp.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyResult(req, e.message ?: "远端检索失败")
        }
    }

    private fun buildRemoteRequestBody(req: TikuSearchRequest): JSONObject {
        return JSONObject().apply {
            put("qid", "")
            put("plat", req.plat)
            put("question", req.question)
            put("options", JSONArray(req.options))
            put("type", req.type)
            put("courseName", req.courseName)
            put("extra", req.extra)
        }
    }

    private fun parseRemoteResponse(req: TikuSearchRequest, body: String): TikuSearchResult {
        val root = JSONObject(body)
        val ansObj = root.optJSONObject("answer") ?: root

        val rawBest = readStringList(ansObj.optJSONArray("bestAnswer"))
        val rawKeys = readStringList(ansObj.optJSONArray("answerKey"))
        val rawKeyText = ansObj.optString("answerKeyText", "").trim()
        val rawAnswerText = ansObj.optString("answerText", "").trim()
        val rawIndex = readIntList(ansObj.optJSONArray("answerIndex"))
        val rawAllAnswers = readNestedStringLists(ansObj.optJSONArray("allAnswer"))
        val explicitMatched = readStringList(root.optJSONArray("matchedProviders"))

        val resolvedBest = if (rawBest.isNotEmpty()) {
            rawBest
        } else if (rawAnswerText.isNotBlank()) {
            rawAnswerText.split('#').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        if (resolvedBest.isEmpty() && rawKeys.isEmpty() && rawAllAnswers.isNotEmpty()) {
            return TikuAnswerResolver.resolve(
                request = req,
                allAnswers = rawAllAnswers,
                matchedProviders = explicitMatched
            )
        }

        val finalAnswerText = if (rawAnswerText.isNotBlank()) {
            rawAnswerText
        } else {
            resolvedBest.joinToString("#")
        }
        val finalKeyText = if (rawKeyText.isNotBlank()) {
            rawKeyText
        } else {
            rawKeys.joinToString("")
        }
        val hit = resolvedBest.isNotEmpty() || rawKeys.isNotEmpty() || finalAnswerText.isNotBlank()
        val matched = when {
            explicitMatched.isNotEmpty() -> explicitMatched
            hit -> listOf("remote")
            else -> emptyList()
        }

        return TikuSearchResult(
            question = req.question,
            options = req.options,
            type = req.type,
            bestAnswer = resolvedBest,
            answerKey = rawKeys,
            answerKeyText = finalKeyText,
            answerText = finalAnswerText,
            answerIndex = rawIndex,
            allAnswers = rawAllAnswers,
            matchedProviders = matched,
            error = null
        )
    }

    private suspend fun searchDirectBuiltin(
        req: TikuSearchRequest,
        config: TikuConfig
    ): TikuSearchResult {
        val providers = config.effectiveDirectProviders()
        if (providers.isEmpty()) {
            return emptyResult(req, "无可用直连题库，请检查已启用源或令牌")
        }

        val outcomes = coroutineScope {
            providers.map { provider ->
                async {
                    val token = config.tokens.tokenFor(provider).trim()
                    provider to runCatching {
                        queryDirectProvider(provider, req, token)
                    }.onFailure { err ->
                        if (err is CancellationException) throw err
                    }
                }
            }.awaitAll()
        }

        val providerAnswers = ArrayList<TikuAnswerResolver.ProviderAnswer>()
        val errors = ArrayList<String>()
        for ((provider, outcome) in outcomes) {
            outcome.onSuccess { answers ->
                if (answers.isNotEmpty()) {
                    providerAnswers.add(
                        TikuAnswerResolver.ProviderAnswer(provider.id, answers)
                    )
                }
            }.onFailure { err ->
                errors.add("${provider.displayName}: ${err.message ?: "请求失败"}")
            }
        }

        val aggregatedErr = if (providerAnswers.isEmpty() && errors.size == providers.size) {
            errors.joinToString("；")
        } else {
            null
        }
        return TikuAnswerResolver.resolve(req, providerAnswers, aggregatedErr)
    }

    private suspend fun queryDirectProvider(
        provider: TikuProvider,
        req: TikuSearchRequest,
        token: String
    ): List<String> {
        // LOCAL and BUGUAKE are strictly remoteOnly and never executed locally.
        if (provider.remoteOnly) return emptyList()
        val httpReq = buildDirectProviderRequest(provider, req, token) ?: return emptyList()
        val httpResp = executeCall(httpReq)
        if (!httpResp.isSuccessful) {
            throw IllegalStateException("HTTP ${httpResp.code}")
        }
        val body = httpResp.body.trim()
        if (body.isEmpty()) return emptyList()
        return parseDirectProviderResponse(provider, body)
    }

    private fun buildDirectProviderRequest(
        provider: TikuProvider,
        req: TikuSearchRequest,
        token: String
    ): Request? {
        return when (provider) {
            TikuProvider.LOCAL, TikuProvider.BUGUAKE -> null
            TikuProvider.ICODEF -> {
                val form = FormBody.Builder()
                    .add("question", req.question)
                    .build()
                Request.Builder()
                    .url(URL_ICODEF)
                    .apply {
                        if (token.isNotBlank()) {
                            header("Authorization", token)
                        }
                    }
                    .post(form)
                    .build()
            }
            TikuProvider.WANNENG -> {
                val url = if (token.length == 10) {
                    "$URL_WANNENG_TOKEN_PREFIX$token"
                } else {
                    URL_WANNENG_FREE
                }
                val body = JSONObject().apply {
                    put("question", req.question)
                    put("options", JSONArray(req.options))
                    put("type", req.type)
                }.toString()
                Request.Builder()
                    .url(url)
                    .header("plat", req.plat.toString())
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            }
            TikuProvider.TIKUHAI -> {
                val body = JSONObject().apply {
                    put("question", req.question)
                    put("options", JSONArray(req.options))
                    put("type", req.type)
                    put("key", token)
                    put("questionData", "")
                }.toString()
                Request.Builder()
                    .url(URL_TIKUHAI)
                    .header("User-Agent", "tikuhaiAdapter/0.1.0")
                    .header("v", "0.1.0")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            }
            TikuProvider.ENNCY -> {
                if (token.isBlank()) return null
                val url = URL_ENNCY.toHttpUrl().newBuilder()
                    .addQueryParameter("token", token)
                    .addQueryParameter("title", req.question)
                    .build()
                Request.Builder()
                    .url(url)
                    .get()
                    .build()
            }
            TikuProvider.AIDIAN -> {
                val url = if (token.isNotBlank()) URL_AIDIAN_DIRECT else URL_AIDIAN_FREE
                val body = JSONObject().apply {
                    put("question", req.question)
                    put("token", token)
                }.toString()
                Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            }
            TikuProvider.LEMON -> {
                if (token.isBlank()) return null
                val body = JSONObject().apply {
                    put("v", "1.0")
                    put("question", req.question)
                    put("uid", "searchcx")
                }.toString()
                Request.Builder()
                    .url(URL_LEMON)
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            }
        }
    }

    private fun parseDirectProviderResponse(provider: TikuProvider, body: String): List<String> {
        val root = JSONObject(body)
        return when (provider) {
            TikuProvider.LOCAL, TikuProvider.BUGUAKE -> emptyList()
            TikuProvider.ICODEF -> {
                if (root.optInt("code", -1) != 1 || root.isNull("data")) return emptyList()
                splitHashAnswers(root.optString("data", ""))
            }
            TikuProvider.WANNENG -> {
                if (root.optInt("code", -1) != 0) return emptyList()
                val result = root.optJSONObject("result") ?: return emptyList()
                if (!result.optBoolean("success", false)) return emptyList()
                extractWannengAnswers(result)
            }
            TikuProvider.TIKUHAI -> {
                if (root.optInt("code", -1) != 200) return emptyList()
                val data = root.optJSONObject("data")
                    ?: root.optJSONObject("questionData")?.optJSONObject("data")
                    ?: return emptyList()
                extractFlexibleAnswers(data.opt("answer"))
            }
            TikuProvider.ENNCY -> {
                if (root.optInt("code", -1) != 1) return emptyList()
                val data = root.optJSONObject("data") ?: return emptyList()
                if (data.isNull("answer")) return emptyList()
                splitHashAnswers(data.optString("answer", ""))
            }
            TikuProvider.AIDIAN -> {
                val qlist = root.optJSONArray("qlist") ?: return emptyList()
                val out = ArrayList<String>()
                for (i in 0 until qlist.length()) {
                    val item = qlist.optJSONObject(i) ?: continue
                    val raw = item.opt("answer") ?: continue
                    out.addAll(extractFlexibleAnswers(raw))
                }
                out
            }
            TikuProvider.LEMON -> {
                if (root.optInt("code", -1) != 1000) return emptyList()
                val data = root.optJSONObject("data") ?: return emptyList()
                if (data.isNull("answer")) return emptyList()
                splitHashAnswers(data.optString("answer", ""))
            }
        }
    }

    private fun extractWannengAnswers(result: JSONObject): List<String> {
        val answersField = result.opt("answers")
        if (answersField is JSONArray) {
            val out = ArrayList<String>()
            for (i in 0 until answersField.length()) {
                val elem = answersField.opt(i)
                when (elem) {
                    is JSONArray -> out.addAll(readStringList(elem))
                    is String -> out.addAll(splitHashAnswers(elem))
                }
            }
            if (out.isNotEmpty()) return out
        }
        val singleField = result.opt("answer")
        return extractFlexibleAnswers(singleField)
    }

    private fun extractFlexibleAnswers(raw: Any?): List<String> {
        return when (raw) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> readStringList(raw)
            is String -> splitHashAnswers(raw)
            else -> {
                val s = raw.toString().trim()
                if (s.isEmpty() || s.equals("null", ignoreCase = true)) emptyList() else listOf(s)
            }
        }
    }

    private fun splitHashAnswers(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return emptyList()
        return trimmed.split('#').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun readStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            if (arr.isNull(i)) continue
            val text = arr.optString(i, "").trim()
            if (text.isNotEmpty() && !text.equals("null", ignoreCase = true)) {
                out.add(text)
            }
        }
        return out
    }

    private fun readIntList(arr: JSONArray?): List<Int> {
        if (arr == null) return emptyList()
        val out = ArrayList<Int>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(arr.optInt(i))
        }
        return out
    }

    private fun readNestedStringLists(arr: JSONArray?): List<List<String>> {
        if (arr == null) return emptyList()
        val out = ArrayList<List<String>>(arr.length())
        for (i in 0 until arr.length()) {
            val inner = arr.optJSONArray(i) ?: continue
            val list = readStringList(inner)
            if (list.isNotEmpty()) {
                out.add(list)
            }
        }
        return out
    }

    private fun emptyResult(req: TikuSearchRequest, error: String?): TikuSearchResult {
        return TikuSearchResult(
            question = req.question,
            options = req.options,
            type = req.type,
            bestAnswer = emptyList(),
            answerKey = emptyList(),
            answerKeyText = "",
            answerText = "",
            answerIndex = emptyList(),
            allAnswers = emptyList(),
            matchedProviders = emptyList(),
            error = error
        )
    }

    private data class HttpCallResult(
        val isSuccessful: Boolean,
        val code: Int,
        val body: String
    )

    private suspend fun executeCall(request: Request): HttpCallResult =
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { resp ->
                            val bodyStr = resp.body?.string().orEmpty()
                            if (cont.isActive) {
                                cont.resume(
                                    HttpCallResult(
                                        isSuccessful = resp.isSuccessful,
                                        code = resp.code,
                                        body = bodyStr
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) {
                            cont.resumeWithException(e)
                        }
                    }
                }
            })
        }

    companion object {
        private const val BATCH_CONCURRENCY = 3
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val URL_ICODEF = "https://cx.icodef.com/wyn-nb?v=4"
        private const val URL_WANNENG_TOKEN_PREFIX = "http://lyck6.cn/scriptService/api/autoAnswer/"
        private const val URL_WANNENG_FREE = "http://lyck6.cn/scriptService/api/autoFreeAnswer"
        private const val URL_TIKUHAI = "https://api.tikuhai.com/search"
        private const val URL_ENNCY = "https://tk.enncy.cn/query"
        private const val URL_AIDIAN_DIRECT = "http://new.api.51aidian.com/publics/newapi/direct"
        private const val URL_AIDIAN_FREE = "http://new.api.51aidian.com/publics/newapi/freedirect"
        private const val URL_LEMON = "https://api.lemtk.xyz/api/v1/mcx"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
