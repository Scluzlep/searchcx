package com.scluzlep.searchcx.tiku

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

enum class TikuMode(val displayName: String) {
    DIRECT_BUILTIN("内置聚合"),
    REMOTE_ADAPTER("远端服务")
}

enum class TikuQuestionType(val code: Int, val displayName: String) {
    SINGLE(0, "单选题"),
    MULTIPLE(1, "多选题"),
    COMPLETION(2, "填空题"),
    JUDGEMENT(3, "判断题"),
    QA(4, "问答题");

    companion object {
        fun byCode(code: Int): TikuQuestionType =
            entries.firstOrNull { it.code == code } ?: SINGLE
    }
}

enum class TikuProvider(
    val id: String,
    val displayName: String,
    val tokenParamName: String?,
    val isFree: Boolean,
    val remoteOnly: Boolean,
    val requiresTokenInDirectMode: Boolean
) {
    ICODEF("icodef", "icodef", "icodefToken", true, false, false),
    LOCAL("local", "本地库", null, true, true, false),
    BUGUAKE("buguake", "不挂科", null, true, true, false),
    ENNCY("enncy", "言溪", "enncyToken", false, false, true),
    TIKUHAI("tikuhai", "题库海", "tikuhaiToken", false, false, false),
    WANNENG("wanneng", "万能", "wannengToken", false, false, false),
    AIDIAN("aidian", "爱点", "aidianYToken", false, false, false),
    LEMON("lemon", "柠檬", "lemonToken", false, false, true);

    companion object {
        fun freeProviders(): List<TikuProvider> = entries.filter { it.isFree }

        fun paidProviders(): List<TikuProvider> = entries.filter { !it.isFree }

        fun defaultFreeIds(): Set<String> = setOf("icodef", "local", "buguake")

        fun byId(id: String): TikuProvider? {
            val normalized = id.trim()
            return entries.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
        }
    }
}

data class TikuProviderTokens(
    val wannengToken: String = "",
    val icodefToken: String = "",
    val enncyToken: String = "",
    val aidianYToken: String = "",
    val lemonToken: String = "",
    val tikuhaiToken: String = ""
) {
    fun tokenFor(provider: TikuProvider): String = when (provider) {
        TikuProvider.WANNENG -> wannengToken
        TikuProvider.ICODEF -> icodefToken
        TikuProvider.ENNCY -> enncyToken
        TikuProvider.AIDIAN -> aidianYToken
        TikuProvider.LEMON -> lemonToken
        TikuProvider.TIKUHAI -> tikuhaiToken
        TikuProvider.LOCAL, TikuProvider.BUGUAKE -> ""
    }

    fun withToken(provider: TikuProvider, value: String): TikuProviderTokens {
        val trimmed = value.trim()
        return when (provider) {
            TikuProvider.WANNENG -> copy(wannengToken = trimmed)
            TikuProvider.ICODEF -> copy(icodefToken = trimmed)
            TikuProvider.ENNCY -> copy(enncyToken = trimmed)
            TikuProvider.AIDIAN -> copy(aidianYToken = trimmed)
            TikuProvider.LEMON -> copy(lemonToken = trimmed)
            TikuProvider.TIKUHAI -> copy(tikuhaiToken = trimmed)
            TikuProvider.LOCAL, TikuProvider.BUGUAKE -> this
        }
    }
}

data class TikuConfig(
    val enabled: Boolean = true,
    val mode: TikuMode = TikuMode.DIRECT_BUILTIN,
    val remoteBaseUrl: String = DEFAULT_REMOTE_URL,
    val autoQueryBeforeAi: Boolean = true,
    val skipAiOnHit: Boolean = false,
    val enabledProviders: Set<String> = TikuProvider.defaultFreeIds(),
    val tokens: TikuProviderTokens = TikuProviderTokens()
) {
    fun withProviderToggled(id: String, enabled: Boolean): TikuConfig {
        val provider = TikuProvider.byId(id) ?: return this
        val normalizedSet = normalizedEnabledIds()
        val updated = if (enabled) {
            normalizedSet + provider.id
        } else {
            normalizedSet - provider.id
        }
        return copy(enabledProviders = updated)
    }

    fun effectiveRemoteProviders(): List<TikuProvider> {
        val normalized = normalizedEnabledIds()
        return TikuProvider.entries.filter { it.id in normalized }
    }

    fun effectiveRemoteUseParam(): String =
        effectiveRemoteProviders().joinToString(",") { it.id }

    fun effectiveDirectProviders(): List<TikuProvider> {
        val normalized = normalizedEnabledIds()
        return TikuProvider.entries.filter { provider ->
            provider.id in normalized &&
                !provider.remoteOnly &&
                (!provider.requiresTokenInDirectMode || tokens.tokenFor(provider).isNotBlank())
        }
    }

    fun buildRemoteSearchUrl(noRecord: Boolean = false): String {
        val raw = remoteBaseUrl.trim().ifEmpty { DEFAULT_REMOTE_URL }
        val withScheme = if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            raw
        } else {
            "http://$raw"
        }
        val parsed = withScheme.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("远端题库服务地址无效: $remoteBaseUrl")

        val builder = parsed.newBuilder()
        val segments = parsed.pathSegments
        val isRootOnly = segments.isEmpty() || (segments.size == 1 && segments[0].isEmpty())
        if (isRootOnly) {
            builder.addPathSegments("adapter-service/search")
        } else if (segments.last().isEmpty()) {
            builder.removePathSegment(segments.lastIndex)
        }

        val remoteProviders = effectiveRemoteProviders()
        val useParam = remoteProviders.joinToString(",") { it.id }
        if (useParam.isNotEmpty()) {
            builder.setEncodedQueryParameter("use", useParam)
        } else {
            builder.removeAllQueryParameters("use")
        }

        for (provider in TikuProvider.entries) {
            val paramName = provider.tokenParamName ?: continue
            builder.removeAllQueryParameters(paramName)
        }
        for (provider in remoteProviders) {
            val paramName = provider.tokenParamName ?: continue
            val token = tokens.tokenFor(provider).trim()
            if (token.isNotEmpty()) {
                builder.setQueryParameter(paramName, token)
            }
        }

        if (noRecord) {
            builder.setQueryParameter("noRecord", "true")
        } else {
            builder.removeAllQueryParameters("noRecord")
        }
        return builder.build().toString()
    }

    fun toJson(encrypt: (String) -> String = { it }): String {
        val normalized = normalizedEnabledIds()
        val orderedEnabled = TikuProvider.entries.filter { it.id in normalized }.map { it.id }
        return JSONObject().apply {
            put("enabled", enabled)
            put("mode", mode.name)
            put("remoteBaseUrl", remoteBaseUrl.trim().ifEmpty { DEFAULT_REMOTE_URL })
            put("autoQueryBeforeAi", autoQueryBeforeAi)
            put("skipAiOnHit", skipAiOnHit)
            put("enabledProviders", JSONArray().apply {
                for (id in orderedEnabled) {
                    put(id)
                }
            })
            put("tokens", JSONObject().apply {
                put("wannengToken", encrypt(tokens.wannengToken.trim()))
                put("icodefToken", encrypt(tokens.icodefToken.trim()))
                put("enncyToken", encrypt(tokens.enncyToken.trim()))
                put("aidianYToken", encrypt(tokens.aidianYToken.trim()))
                put("lemonToken", encrypt(tokens.lemonToken.trim()))
                put("tikuhaiToken", encrypt(tokens.tikuhaiToken.trim()))
            })
        }.toString()
    }

    private fun normalizedEnabledIds(): Set<String> =
        enabledProviders.mapNotNull { TikuProvider.byId(it)?.id }.toSet()

    companion object {
        const val DEFAULT_REMOTE_URL = "http://127.0.0.1:8060/adapter-service/search"

        fun fromJson(raw: String, decrypt: (String) -> String = { it }): TikuConfig {
            if (raw.isBlank()) return TikuConfig()
            val obj = JSONObject(raw)
            val defaults = TikuConfig()
            val modeName = obj.optString("mode", defaults.mode.name)
            val mode = TikuMode.entries.firstOrNull { it.name == modeName } ?: defaults.mode

            val enabledProviders = if (obj.has("enabledProviders") && !obj.isNull("enabledProviders")) {
                val arr = obj.optJSONArray("enabledProviders") ?: JSONArray()
                val set = LinkedHashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val provider = TikuProvider.byId(arr.optString(i, ""))
                    if (provider != null) {
                        set.add(provider.id)
                    }
                }
                set
            } else {
                TikuProvider.defaultFreeIds()
            }

            val tokObj = obj.optJSONObject("tokens")
            fun readDecryptedToken(key: String): String {
                if (tokObj == null || !tokObj.has(key) || tokObj.isNull(key)) return ""
                val encrypted = tokObj.optString(key, "")
                return if (encrypted.isEmpty()) "" else decrypt(encrypted).trim()
            }

            val tokens = TikuProviderTokens(
                wannengToken = readDecryptedToken("wannengToken"),
                icodefToken = readDecryptedToken("icodefToken"),
                enncyToken = readDecryptedToken("enncyToken"),
                aidianYToken = readDecryptedToken("aidianYToken"),
                lemonToken = readDecryptedToken("lemonToken"),
                tikuhaiToken = readDecryptedToken("tikuhaiToken")
            )

            return TikuConfig(
                enabled = obj.optBoolean("enabled", defaults.enabled),
                mode = mode,
                remoteBaseUrl = obj.optString("remoteBaseUrl", defaults.remoteBaseUrl)
                    .trim()
                    .ifEmpty { DEFAULT_REMOTE_URL },
                autoQueryBeforeAi = obj.optBoolean("autoQueryBeforeAi", defaults.autoQueryBeforeAi),
                skipAiOnHit = obj.optBoolean("skipAiOnHit", defaults.skipAiOnHit),
                enabledProviders = enabledProviders,
                tokens = tokens
            )
        }
    }
}

data class TikuSearchRequest(
    val question: String,
    val options: List<String> = emptyList(),
    val type: Int = 0,
    val courseName: String = "",
    val plat: Int = 0,
    val extra: String = ""
)

data class TikuSearchResult(
    val question: String,
    val options: List<String>,
    val type: Int,
    val bestAnswer: List<String>,
    val answerKey: List<String>,
    val answerKeyText: String,
    val answerText: String,
    val answerIndex: List<Int>,
    val allAnswers: List<List<String>>,
    val matchedProviders: List<String>,
    val error: String? = null
) {
    fun isHit(): Boolean =
        bestAnswer.any { it.isNotBlank() } ||
            answerKey.any { it.isNotBlank() } ||
            answerText.isNotBlank()
}
