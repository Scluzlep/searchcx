package com.scluzlep.searchcx.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

data class TokenResult(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSec: Long,
    val idToken: String? = null
)

data class CodexCredentialResult(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val accountId: String,
    val email: String,
    val plan: String
)

class PkceOAuthManager(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun generateState(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun computeS256Challenge(codeVerifier: String): String {
        require(codeVerifier.length in 43..128) {
            "RFC 7636 code_verifier length must be 43..128, got ${codeVerifier.length}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun buildAuthorizeUrl(
        authEndpoint: String,
        clientId: String,
        scopes: String,
        state: String,
        codeChallenge: String,
        redirectUri: String = REDIRECT_URI,
        extraParams: Map<String, String> = emptyMap()
    ): String {
        val endpoint = authEndpoint.trim()
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) {
            "OAuth Authorize URL 必须以 https:// 或 http:// 开头"
        }
        val cid = clientId.trim()
        require(cid.isNotEmpty()) { "OAuth Client ID 不能为空" }
        require(state.isNotBlank()) { "OAuth state 不能为空" }
        require(codeChallenge.isNotBlank()) { "OAuth codeChallenge 不能为空" }

        val sep = if (endpoint.contains('?')) '&' else '?'
        val baseParams = mutableListOf(
            "response_type" to "code",
            "client_id" to cid,
            "redirect_uri" to redirectUri,
            "scope" to scopes.trim(),
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256"
        )
        for ((k, v) in extraParams) {
            baseParams.add(k to v)
        }
        val query = baseParams
            .filter { it.second.isNotEmpty() }
            .joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return "$endpoint$sep$query"
    }

    fun buildCodexAuthorizeUrl(
        state: String,
        codeChallenge: String,
        redirectUri: String
    ): String {
        return buildAuthorizeUrl(
            authEndpoint = CODEX_AUTHORIZE_ENDPOINT,
            clientId = CODEX_CLIENT_ID,
            scopes = CODEX_SCOPES,
            state = state,
            codeChallenge = codeChallenge,
            redirectUri = redirectUri,
            extraParams = mapOf(
                "id_token_add_organizations" to "true",
                "codex_cli_simplified_flow" to "true",
                "originator" to CODEX_ORIGINATOR
            )
        )
    }

    suspend fun exchangeCodeForToken(
        tokenEndpoint: String,
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String = REDIRECT_URI
    ): TokenResult = withContext(Dispatchers.IO) {
        val endpoint = tokenEndpoint.trim()
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) {
            "OAuth Token URL 必须以 https:// 或 http:// 开头"
        }
        require(clientId.isNotBlank()) { "OAuth Client ID 不能为空" }
        require(code.isNotBlank()) { "Authorization code 不能为空" }
        require(codeVerifier.isNotBlank()) { "code_verifier 不能为空" }

        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId.trim())
            .add("code", code.trim())
            .add("redirect_uri", redirectUri)
            .add("code_verifier", codeVerifier)
            .build()

        val req = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()
        executeTokenRequest(req)
    }

    suspend fun exchangeCodexCode(
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): CodexCredentialResult {
        val token = exchangeCodeForToken(
            tokenEndpoint = CODEX_TOKEN_ENDPOINT,
            clientId = CODEX_CLIENT_ID,
            code = code,
            codeVerifier = codeVerifier,
            redirectUri = redirectUri
        )
        return toCodexCredential(token)
    }

    suspend fun refreshAccessToken(
        tokenEndpoint: String,
        clientId: String,
        refreshToken: String
    ): TokenResult = withContext(Dispatchers.IO) {
        val endpoint = tokenEndpoint.trim()
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) {
            "OAuth Token URL 必须以 https:// 或 http:// 开头"
        }
        require(clientId.isNotBlank()) { "OAuth Client ID 不能为空" }
        require(refreshToken.isNotBlank()) { "Refresh token 不能为空" }

        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId.trim())
            .add("refresh_token", refreshToken.trim())
            .build()

        val req = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()
        executeTokenRequest(req)
    }

    /**
     * OpenAI auth.openai.com refresh endpoint expects a JSON request body.
     */
    suspend fun refreshCodexToken(
        refreshToken: String,
        previousAccountId: String = "",
        previousEmail: String = "",
        previousPlan: String = ""
    ): CodexCredentialResult = withContext(Dispatchers.IO) {
        require(refreshToken.isNotBlank()) { "Codex refresh_token 不能为空" }
        val payload = JSONObject().apply {
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken.trim())
            put("client_id", CODEX_CLIENT_ID)
        }.toString()

        val req = Request.Builder()
            .url(CODEX_TOKEN_ENDPOINT)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val token = executeTokenRequest(req)
        toCodexCredential(
            token = token,
            previousRefreshToken = refreshToken,
            previousAccountId = previousAccountId,
            previousEmail = previousEmail,
            previousPlan = previousPlan
        )
    }

    private fun executeTokenRequest(req: Request): TokenResult {
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Token exchange failed: ${OpenAiSseParser.formatHttpError(resp.code, raw)}")
            }
            return parseTokenResponse(raw)
        }
    }

    internal fun parseTokenResponse(rawJson: String): TokenResult {
        val obj = JSONObject(rawJson)
        if (obj.has("error") && !obj.isNull("error")) {
            val desc = obj.optString("error_description", obj.optString("error", "unknown"))
            throw IllegalStateException("OAuth token error: $desc")
        }
        val access = obj.optString("access_token", "").trim()
        require(access.isNotEmpty()) { "Token response missing access_token" }
        val refresh = if (obj.has("refresh_token") && !obj.isNull("refresh_token")) {
            obj.optString("refresh_token", "").trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val idToken = if (obj.has("id_token") && !obj.isNull("id_token")) {
            obj.optString("id_token", "").trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val expires = obj.optLong("expires_in", 0L)
        return TokenResult(
            accessToken = access,
            refreshToken = refresh,
            expiresInSec = expires,
            idToken = idToken
        )
    }

    internal fun toCodexCredential(
        token: TokenResult,
        previousRefreshToken: String? = null,
        previousAccountId: String = "",
        previousEmail: String = "",
        previousPlan: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): CodexCredentialResult {
        val idClaims = token.idToken?.let(::decodeJwtClaims)
        val accessClaims = decodeJwtClaims(token.accessToken)
        val authClaims = (idClaims?.optJSONObject(AUTH_CLAIM) ?: accessClaims?.optJSONObject(AUTH_CLAIM))
        val profileClaims = idClaims?.optJSONObject(PROFILE_CLAIM) ?: accessClaims?.optJSONObject(PROFILE_CLAIM)

        val accountId = authClaims?.optString("chatgpt_account_id", "")?.ifBlank { null }
            ?: previousAccountId
        val email = idClaims?.optString("email", "")?.ifBlank { null }
            ?: profileClaims?.optString("email", "")?.ifBlank { null }
            ?: previousEmail
        val planRaw = authClaims?.optString("chatgpt_plan_type", "")?.ifBlank { null }
            ?: previousPlan
        val expiresAt = when {
            token.expiresInSec > 0L -> nowMs + token.expiresInSec * 1000L
            accessClaims?.has("exp") == true -> accessClaims.optLong("exp", 0L) * 1000L
            else -> 0L
        }

        return CodexCredentialResult(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: previousRefreshToken,
            expiresAtMs = expiresAt,
            accountId = accountId,
            email = email,
            plan = formatCodexPlan(planRaw)
        )
    }

    internal fun decodeJwtClaims(jwt: String): JSONObject? {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(parts[1])
            JSONObject(String(bytes, StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val REDIRECT_URI = "searchcx://oauth/callback"
        const val CODEX_ISSUER = "https://auth.openai.com"
        const val CODEX_AUTHORIZE_ENDPOINT = "$CODEX_ISSUER/oauth/authorize"
        const val CODEX_TOKEN_ENDPOINT = "$CODEX_ISSUER/oauth/token"
        const val CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val CODEX_SCOPES = "openid profile email offline_access"
        const val CODEX_CALLBACK_PORT = 1455
        const val CODEX_CALLBACK_PATH = "/auth/callback"
        const val CODEX_CALLBACK_HOST = "127.0.0.1"
        const val CODEX_ORIGINATOR = "codex_cli_rs"

        private const val AUTH_CLAIM = "https://api.openai.com/auth"
        private const val PROFILE_CLAIM = "https://api.openai.com/profile"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun formatCodexPlan(plan: String): String {
            if (plan.isBlank()) return ""
            return when (plan.lowercase()) {
                "free" -> "Free"
                "go" -> "Go"
                "plus" -> "Plus"
                "pro" -> "Pro (More)"
                "prolite" -> "Pro"
                "promax" -> "Pro (Max)"
                "team" -> "Team"
                "business" -> "Business"
                "enterprise", "ent26", "hc" -> "Enterprise"
                "edu", "education" -> "Edu"
                else -> plan.split('_').filter { it.isNotEmpty() }
                    .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
            }
        }
    }
}
