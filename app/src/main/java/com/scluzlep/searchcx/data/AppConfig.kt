package com.scluzlep.searchcx.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.scluzlep.searchcx.tiku.TikuConfig
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProviderType(val displayName: String) {
    OPENAI_CHAT("OpenAI Chat (/chat/completions)"),
    OPENAI_RESPONSES("OpenAI Responses (/responses)"),
    CODEX_OAUTH("ChatGPT / Codex OAuth"),
    CUSTOM_OAUTH("自定义 OAuth PKCE")
}

data class ProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "OpenAI",
    val type: ProviderType = ProviderType.OPENAI_CHAT,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = "",
    val oauthAuthUrl: String = "",
    val oauthTokenUrl: String = "",
    val oauthClientId: String = "",
    val oauthScopes: String = "openid profile offline_access",
    val oauthAccessToken: String = "",
    val oauthRefreshToken: String = "",
    val oauthExpiresAtMs: Long = 0L,
    val oauthAccountId: String = "",
    val oauthEmail: String = "",
    val oauthPlan: String = "",
    val temperature: Double = 0.3
) {
    fun normalizedBaseUrl(): String = when (type) {
        ProviderType.CODEX_OAUTH -> CODEX_BASE_URL
        else -> baseUrl.trim().trimEnd('/')
    }

    fun usesOAuth(): Boolean = type == ProviderType.CODEX_OAUTH || type == ProviderType.CUSTOM_OAUTH

    fun activeBearerToken(): String {
        val token = if (usesOAuth()) oauthAccessToken.trim() else apiKey.trim()
        if (token.isEmpty()) {
            val label = when (type) {
                ProviderType.CODEX_OAUTH -> "ChatGPT Codex OAuth 登录"
                ProviderType.CUSTOM_OAUTH -> "OAuth Access Token"
                else -> "API Key"
            }
            throw IllegalStateException("「$name」未完成 $label 配置，请先在主界面配置或授权。")
        }
        return token
    }

    fun isTokenExpiringSoon(nowMs: Long = System.currentTimeMillis(), leewayMs: Long = 5 * 60_000L): Boolean {
        return usesOAuth() && oauthExpiresAtMs > 0L && nowMs >= (oauthExpiresAtMs - leewayMs)
    }

    companion object {
        const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex"
    }
}

enum class CollapsedBallStyle(val displayName: String) {
    BUTTON("悬浮按钮"),
    MINI_DOT("白色小白点")
}

enum class OverlayThemeMode(val displayName: String, val shortLabel: String) {
    FOLLOW_SYSTEM("自动", "自"),
    LIGHT("浅色", "浅"),
    DARK("深色", "深");

    fun next(): OverlayThemeMode = when (this) {
        FOLLOW_SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> FOLLOW_SYSTEM
    }
}

enum class ResizeEdge {
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    BOTTOM
}

data class WindowBoundsPx(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class OverlayPrefs(
    val autoCaptureOnExpand: Boolean = true,
    val longPressAutoAsk: Boolean = true,
    val idleEdgeRetract: Boolean = true,
    val panelOpacity: Float = 0.96f,
    val panelExpandedTall: Boolean = false,
    val secureOverlay: Boolean = false,
    val ballDockRight: Boolean = true,
    val ballYFraction: Float = 0.36f,
    val quickAskPrompt: String = DEFAULT_QUICK_ASK_PROMPT,
    val collapsedBallStyle: CollapsedBallStyle = CollapsedBallStyle.BUTTON,
    val themeMode: OverlayThemeMode = OverlayThemeMode.FOLLOW_SYSTEM,
    val panelXFraction: Float = UNSET_FRACTION,
    val panelYFraction: Float = UNSET_FRACTION,
    val panelWidthDp: Int = 0,
    val panelHeightDp: Int = 0,
    val ballDocked: Boolean = true,
    val ballXFraction: Float = UNSET_FRACTION
) {
    fun withResetWindowBounds(): OverlayPrefs = copy(
        panelXFraction = UNSET_FRACTION,
        panelYFraction = UNSET_FRACTION,
        panelWidthDp = 0,
        panelHeightDp = 0,
        panelExpandedTall = false
    )

    fun toJson(): String {
        return JSONObject().apply {
            put("autoCaptureOnExpand", autoCaptureOnExpand)
            put("longPressAutoAsk", longPressAutoAsk)
            put("idleEdgeRetract", idleEdgeRetract)
            put("panelOpacity", panelOpacity.toDouble())
            put("panelExpandedTall", panelExpandedTall)
            put("secureOverlay", secureOverlay)
            put("ballDockRight", ballDockRight)
            put("ballYFraction", ballYFraction.toDouble())
            put("quickAskPrompt", quickAskPrompt)
            put("collapsedBallStyle", collapsedBallStyle.name)
            put("themeMode", themeMode.name)
            put("panelXFraction", panelXFraction.toDouble())
            put("panelYFraction", panelYFraction.toDouble())
            put("panelWidthDp", panelWidthDp)
            put("panelHeightDp", panelHeightDp)
            put("ballDocked", ballDocked)
            put("ballXFraction", ballXFraction.toDouble())
        }.toString()
    }

    companion object {
        const val LEGACY_DEFAULT_QUICK_ASK_PROMPT = "请直接解析以下内容，给出清晰步骤与最终结论："
        const val DEFAULT_QUICK_ASK_PROMPT = "请给出速查答案和简要解析："
        const val UNSET_FRACTION = -10f

        fun migrateDefaultQuickAskPrompt(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            return if (trimmed.isEmpty() || trimmed == LEGACY_DEFAULT_QUICK_ASK_PROMPT) {
                DEFAULT_QUICK_ASK_PROMPT
            } else {
                raw!!
            }
        }

        fun fromJson(rawJson: String): OverlayPrefs {
            val obj = JSONObject(rawJson)
            val defaults = OverlayPrefs()
            val styleName = obj.optString("collapsedBallStyle", defaults.collapsedBallStyle.name)
            val style = CollapsedBallStyle.entries.firstOrNull { it.name == styleName }
                ?: CollapsedBallStyle.BUTTON
            val themeName = obj.optString("themeMode", defaults.themeMode.name)
            val themeMode = OverlayThemeMode.entries.firstOrNull { it.name == themeName }
                ?: OverlayThemeMode.FOLLOW_SYSTEM
            val rawQuickAsk = if (obj.has("quickAskPrompt") && !obj.isNull("quickAskPrompt")) {
                obj.optString("quickAskPrompt", "")
            } else {
                null
            }
            val rawWidthDp = obj.optInt("panelWidthDp", defaults.panelWidthDp)
            val panelWidthDp = if (rawWidthDp <= 0) 0 else rawWidthDp.coerceIn(220, 1400)
            val rawHeightDp = obj.optInt("panelHeightDp", defaults.panelHeightDp)
            val panelHeightDp = if (rawHeightDp <= 0) 0 else rawHeightDp.coerceIn(220, 1800)
            val rawPanelX = obj.optDouble("panelXFraction", UNSET_FRACTION.toDouble()).toFloat()
            val panelXFraction = if (!rawPanelX.isFinite() || rawPanelX <= -5f) {
                UNSET_FRACTION
            } else {
                rawPanelX.coerceIn(-0.95f, 0.98f)
            }
            val rawPanelY = obj.optDouble("panelYFraction", UNSET_FRACTION.toDouble()).toFloat()
            val panelYFraction = if (!rawPanelY.isFinite() || rawPanelY <= -5f) {
                UNSET_FRACTION
            } else {
                rawPanelY.coerceIn(0f, 0.96f)
            }
            val rawBallX = obj.optDouble("ballXFraction", UNSET_FRACTION.toDouble()).toFloat()
            val ballXFraction = if (!rawBallX.isFinite() || rawBallX <= -5f) {
                UNSET_FRACTION
            } else {
                rawBallX.coerceIn(0f, 0.98f)
            }
            return OverlayPrefs(
                autoCaptureOnExpand = obj.optBoolean("autoCaptureOnExpand", defaults.autoCaptureOnExpand),
                longPressAutoAsk = obj.optBoolean("longPressAutoAsk", defaults.longPressAutoAsk),
                idleEdgeRetract = obj.optBoolean("idleEdgeRetract", defaults.idleEdgeRetract),
                panelOpacity = obj.optDouble("panelOpacity", defaults.panelOpacity.toDouble())
                    .toFloat()
                    .coerceIn(0.45f, 1.0f),
                panelExpandedTall = obj.optBoolean("panelExpandedTall", defaults.panelExpandedTall),
                secureOverlay = obj.optBoolean("secureOverlay", defaults.secureOverlay),
                ballDockRight = obj.optBoolean("ballDockRight", defaults.ballDockRight),
                ballYFraction = obj.optDouble("ballYFraction", defaults.ballYFraction.toDouble())
                    .toFloat()
                    .coerceIn(0.08f, 0.85f),
                quickAskPrompt = migrateDefaultQuickAskPrompt(rawQuickAsk),
                collapsedBallStyle = style,
                themeMode = themeMode,
                panelXFraction = panelXFraction,
                panelYFraction = panelYFraction,
                panelWidthDp = panelWidthDp,
                panelHeightDp = panelHeightDp,
                ballDocked = obj.optBoolean("ballDocked", defaults.ballDocked),
                ballXFraction = ballXFraction
            )
        }
    }
}

object OverlayWindowGeometry {
    fun minPanelWidthPx(screenW: Int, density: Float): Int {
        return (248f * density).toInt()
            .coerceAtMost((screenW - (16f * density).toInt()).coerceAtLeast(120))
    }

    fun maxPanelWidthPx(screenW: Int, density: Float): Int {
        return (screenW - (12f * density).toInt())
            .coerceAtLeast(minPanelWidthPx(screenW, density))
    }

    fun minPanelHeightPx(screenH: Int, density: Float): Int {
        return (260f * density).toInt()
            .coerceAtMost((screenH - (48f * density).toInt()).coerceAtLeast(140))
    }

    fun maxPanelHeightPx(screenH: Int, density: Float): Int {
        return (screenH - (36f * density).toInt())
            .coerceAtLeast(minPanelHeightPx(screenH, density))
    }

    fun defaultPanelWidthPx(screenW: Int, density: Float): Int {
        return (screenW * 0.93f).toInt()
            .coerceAtMost((410f * density).toInt())
            .coerceIn(minPanelWidthPx(screenW, density), maxPanelWidthPx(screenW, density))
    }

    fun defaultPanelHeightPx(screenH: Int, density: Float, tall: Boolean): Int {
        return (screenH * (if (tall) 0.74f else 0.50f)).toInt()
            .coerceIn(minPanelHeightPx(screenH, density), maxPanelHeightPx(screenH, density))
    }

    fun clampPanelX(x: Int, panelW: Int, screenW: Int, density: Float): Int {
        val minVisible = (64f * density).toInt().coerceAtLeast(32)
        return x.coerceIn(minVisible - panelW, (screenW - minVisible).coerceAtLeast(minVisible - panelW))
    }

    fun clampPanelY(y: Int, panelH: Int, screenH: Int, density: Float): Int {
        val minVisible = (64f * density).toInt().coerceAtLeast(32)
        return y.coerceIn(0, (screenH - minVisible).coerceAtLeast(0))
    }

    fun resolvePanelBounds(
        prefs: OverlayPrefs,
        screenW: Int,
        screenH: Int,
        density: Float
    ): WindowBoundsPx {
        val minW = minPanelWidthPx(screenW, density)
        val maxW = maxPanelWidthPx(screenW, density)
        val w = if (prefs.panelWidthDp > 0) {
            (prefs.panelWidthDp * density).toInt().coerceIn(minW, maxW)
        } else {
            defaultPanelWidthPx(screenW, density)
        }

        val minH = minPanelHeightPx(screenH, density)
        val maxH = maxPanelHeightPx(screenH, density)
        val h = if (prefs.panelHeightDp > 0) {
            (prefs.panelHeightDp * density).toInt().coerceIn(minH, maxH)
        } else {
            defaultPanelHeightPx(screenH, density, prefs.panelExpandedTall)
        }

        val rawX = if (prefs.panelXFraction.isFinite() && prefs.panelXFraction > -5f) {
            (prefs.panelXFraction * screenW).toInt()
        } else {
            (screenW - w) / 2
        }
        val rawY = if (prefs.panelYFraction.isFinite() && prefs.panelYFraction > -5f) {
            (prefs.panelYFraction * screenH).toInt()
        } else {
            (56f * density).toInt()
        }

        return WindowBoundsPx(
            x = clampPanelX(rawX, w, screenW, density),
            y = clampPanelY(rawY, h, screenH, density),
            width = w,
            height = h
        )
    }

    fun applyPanelDrag(
        bounds: WindowBoundsPx,
        dx: Int,
        dy: Int,
        screenW: Int,
        screenH: Int,
        density: Float
    ): WindowBoundsPx {
        return bounds.copy(
            x = clampPanelX(bounds.x + dx, bounds.width, screenW, density),
            y = clampPanelY(bounds.y + dy, bounds.height, screenH, density)
        )
    }

    fun applyPanelResize(
        bounds: WindowBoundsPx,
        edge: ResizeEdge,
        dx: Int,
        dy: Int,
        screenW: Int,
        screenH: Int,
        density: Float
    ): WindowBoundsPx {
        val minW = minPanelWidthPx(screenW, density)
        val maxW = maxPanelWidthPx(screenW, density)
        val minH = minPanelHeightPx(screenH, density)
        val maxH = maxPanelHeightPx(screenH, density)
        return when (edge) {
            ResizeEdge.BOTTOM_RIGHT -> {
                val newW = (bounds.width + dx).coerceIn(minW, maxW)
                val newH = (bounds.height + dy).coerceIn(minH, maxH)
                WindowBoundsPx(
                    x = clampPanelX(bounds.x, newW, screenW, density),
                    y = clampPanelY(bounds.y, newH, screenH, density),
                    width = newW,
                    height = newH
                )
            }
            ResizeEdge.BOTTOM_LEFT -> {
                val rightEdge = bounds.x + bounds.width
                val newW = (bounds.width - dx).coerceIn(minW, maxW)
                val newX = clampPanelX(rightEdge - newW, newW, screenW, density)
                val newH = (bounds.height + dy).coerceIn(minH, maxH)
                WindowBoundsPx(
                    x = newX,
                    y = clampPanelY(bounds.y, newH, screenH, density),
                    width = newW,
                    height = newH
                )
            }
            ResizeEdge.BOTTOM -> {
                val newH = (bounds.height + dy).coerceIn(minH, maxH)
                WindowBoundsPx(
                    x = clampPanelX(bounds.x, bounds.width, screenW, density),
                    y = clampPanelY(bounds.y, newH, screenH, density),
                    width = bounds.width,
                    height = newH
                )
            }
        }
    }

    fun shouldSnapBallToEdge(ballX: Int, ballW: Int, screenW: Int, density: Float): Boolean {
        val snapMargin = (28f * density).toInt()
        return ballX <= snapMargin || (ballX + ballW) >= (screenW - snapMargin)
    }
}

data class AppConfig(
    val profiles: List<ProviderProfile> = defaultProfiles(),
    val activeProfileId: String = DEFAULT_OPENAI_ID,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val overlayPrefs: OverlayPrefs = OverlayPrefs(),
    val hookTargetPackages: String = "",
    val tikuConfig: TikuConfig = TikuConfig()
) {
    fun activeProfile(): ProviderProfile {
        return profiles.firstOrNull { it.id == activeProfileId }
            ?: profiles.firstOrNull()
            ?: defaultOpenAiProfile()
    }

    fun normalizedBaseUrl(): String = activeProfile().normalizedBaseUrl()

    fun activeBearerToken(): String = activeProfile().activeBearerToken()

    fun withUpdatedProfile(updated: ProviderProfile): AppConfig {
        val exists = profiles.any { it.id == updated.id }
        val nextList = if (exists) {
            profiles.map { if (it.id == updated.id) updated else it }
        } else {
            profiles + updated
        }
        return copy(profiles = nextList)
    }

    fun withAddedProfile(profile: ProviderProfile, activate: Boolean = true): AppConfig {
        return copy(
            profiles = profiles + profile,
            activeProfileId = if (activate) profile.id else activeProfileId
        )
    }

    fun withRemovedProfile(profileId: String): AppConfig {
        if (profiles.size <= 1) return this
        val next = profiles.filterNot { it.id == profileId }
        val nextActive = if (activeProfileId == profileId) next.first().id else activeProfileId
        return copy(profiles = next, activeProfileId = nextActive)
    }

    companion object {
        const val DEFAULT_OPENAI_ID = "default_openai"
        const val DEFAULT_CODEX_ID = "default_codex"
        const val LEGACY_DEFAULT_SYSTEM_PROMPT =
            "你是简洁高效的个人 AI 助手。基于提供的网页 DOM 上下文、截图或用户问题，给出结构清晰、重点突出、推导严谨的解答。"
        const val DEFAULT_SYSTEM_PROMPT =
            "你是简洁精准的解题助手。基于提供的题目文本、公式、附图或截图作答，按「速查」与「解析」两部分输出：\n1. 速查：最先直接给出最终答案（选择题标明选项字母与内容，多题按题号逐一列出），方便一眼核对。\n2. 解析：随后用精炼步骤说明核心思路、公式推导或排除依据；若附带题库参考，请独立验算后再下结论。"

        fun migrateDefaultSystemPrompt(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            return if (trimmed.isEmpty() || trimmed == LEGACY_DEFAULT_SYSTEM_PROMPT) {
                DEFAULT_SYSTEM_PROMPT
            } else {
                raw!!
            }
        }

        fun defaultOpenAiProfile() = ProviderProfile(
            id = DEFAULT_OPENAI_ID,
            name = "OpenAI",
            type = ProviderType.OPENAI_CHAT,
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini"
        )

        fun defaultCodexProfile() = ProviderProfile(
            id = DEFAULT_CODEX_ID,
            name = "ChatGPT Codex",
            type = ProviderType.CODEX_OAUTH,
            baseUrl = ProviderProfile.CODEX_BASE_URL,
            model = "gpt-5.1-codex"
        )

        fun defaultProfiles(): List<ProviderProfile> = listOf(
            defaultOpenAiProfile(),
            defaultCodexProfile()
        )
    }
}

/**
 * Persists multi-provider config & floating preferences ONLY in assistant's private storage.
 * All API keys and OAuth tokens are encrypted per-profile with AndroidKeyStore AES-256-GCM.
 * Never invoke from hooked third-party app processes.
 */
class AppConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val storeLock = Any()

    fun load(): AppConfig = synchronized(storeLock) {
        val rawProfiles = prefs.getString(KEY_PROFILES_JSON, null)
        val profiles = if (!rawProfiles.isNullOrBlank()) {
            decodeProfiles(rawProfiles)
        } else {
            migrateLegacyV1Profiles()
        }.ifEmpty { AppConfig.defaultProfiles() }

        val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
            ?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id

        val systemPrompt = AppConfig.migrateDefaultSystemPrompt(prefs.getString(KEY_SYSTEM_PROMPT, null))

        val rawOverlay = prefs.getString(KEY_OVERLAY_PREFS_JSON, null)
        val overlayPrefs = if (!rawOverlay.isNullOrBlank()) {
            decodeOverlayPrefs(rawOverlay)
        } else {
            OverlayPrefs()
        }

        val rawTiku = prefs.getString(KEY_TIKU_CONFIG_JSON, null)
        val tikuConfig = if (!rawTiku.isNullOrBlank()) {
            runCatching { TikuConfig.fromJson(rawTiku) { decryptString(it) } }
                .getOrDefault(TikuConfig())
        } else {
            TikuConfig()
        }

        return AppConfig(
            profiles = profiles,
            activeProfileId = activeId,
            systemPrompt = systemPrompt,
            overlayPrefs = overlayPrefs,
            hookTargetPackages = prefs.getString(KEY_HOOK_PACKAGES, "") ?: "",
            tikuConfig = tikuConfig
        )
    }

    fun save(config: AppConfig) = synchronized(storeLock) {
        val sanitizedProfiles = config.profiles.ifEmpty { AppConfig.defaultProfiles() }
        val activeId = config.activeProfileId
            .takeIf { id -> sanitizedProfiles.any { it.id == id } }
            ?: sanitizedProfiles.first().id

        val ok = prefs.edit()
            .putString(KEY_PROFILES_JSON, encodeProfiles(sanitizedProfiles))
            .putString(KEY_ACTIVE_PROFILE_ID, activeId)
            .putString(KEY_SYSTEM_PROMPT, config.systemPrompt.trim())
            .putString(KEY_OVERLAY_PREFS_JSON, encodeOverlayPrefs(config.overlayPrefs))
            .putString(KEY_HOOK_PACKAGES, config.hookTargetPackages.trim())
            .putString(KEY_TIKU_CONFIG_JSON, config.tikuConfig.toJson { encryptString(it) })
            .commit()
        if (!ok) {
            throw IllegalStateException("Failed to persist configuration to SharedPreferences")
        }
    }

    fun updateProfile(profileId: String, transform: (ProviderProfile) -> ProviderProfile): ProviderProfile =
        synchronized(storeLock) {
            val current = load()
            val target = current.profiles.firstOrNull { it.id == profileId }
                ?: throw IllegalStateException("未找到 ID 为 $profileId 的服务商配置")
            val updated = transform(target)
            save(current.withUpdatedProfile(updated))
            updated
        }

    fun updateOverlayPrefs(transform: (OverlayPrefs) -> OverlayPrefs): OverlayPrefs =
        synchronized(storeLock) {
            val current = load()
            val next = transform(current.overlayPrefs)
            save(current.copy(overlayPrefs = next))
            next
        }

    fun updateTikuConfig(transform: (TikuConfig) -> TikuConfig): TikuConfig =
        synchronized(storeLock) {
            val current = load()
            val next = transform(current.tikuConfig)
            save(current.copy(tikuConfig = next))
            next
        }

    fun saveOAuthState(state: String, codeVerifier: String, profileId: String) = synchronized(storeLock) {
        val ok = prefs.edit()
            .putString(KEY_PENDING_OAUTH_STATE, state)
            .putString(KEY_PENDING_OAUTH_PROFILE_ID, profileId)
            .putString(KEY_PENDING_OAUTH_VERIFIER_ENC, encryptString(codeVerifier))
            .commit()
        if (!ok) {
            throw IllegalStateException("Failed to persist pending OAuth PKCE state")
        }
    }

    fun consumeOAuthState(): Triple<String, String, String> = synchronized(storeLock) {
        val state = prefs.getString(KEY_PENDING_OAUTH_STATE, null)
            ?: throw IllegalStateException("No pending OAuth state found")
        val profileId = prefs.getString(KEY_PENDING_OAUTH_PROFILE_ID, null).orEmpty()
        val verifier = decryptString(prefs.getString(KEY_PENDING_OAUTH_VERIFIER_ENC, null))
        if (verifier.isEmpty()) {
            throw IllegalStateException("No pending OAuth PKCE code_verifier found")
        }
        prefs.edit()
            .remove(KEY_PENDING_OAUTH_STATE)
            .remove(KEY_PENDING_OAUTH_PROFILE_ID)
            .remove(KEY_PENDING_OAUTH_VERIFIER_ENC)
            .apply()
        Triple(state, verifier, profileId)
    }

    private fun migrateLegacyV1Profiles(): List<ProviderProfile> {
        val legacyBase = prefs.getString(LEGACY_BASE_URL, null)
        val legacyModel = prefs.getString(LEGACY_MODEL, null)
        val legacyKeyEnc = prefs.getString(LEGACY_API_KEY_ENC, null)
        val legacyMode = prefs.getString(LEGACY_AUTH_MODE, null)
        val legacyAuthUrl = prefs.getString(LEGACY_OAUTH_AUTH_URL, "") ?: ""
        val legacyTokenUrl = prefs.getString(LEGACY_OAUTH_TOKEN_URL, "") ?: ""
        val legacyClientId = prefs.getString(LEGACY_OAUTH_CLIENT_ID, "") ?: ""
        val legacyScopes = prefs.getString(LEGACY_OAUTH_SCOPES, "openid profile offline_access")
            ?: "openid profile offline_access"
        val legacyAccessEnc = prefs.getString(LEGACY_OAUTH_ACCESS_ENC, null)
        val legacyRefreshEnc = prefs.getString(LEGACY_OAUTH_REFRESH_ENC, null)
        val legacyTemp = prefs.getFloat(LEGACY_TEMPERATURE, 0.3f).toDouble()

        if (legacyBase == null && legacyKeyEnc == null && legacyAccessEnc == null) {
            return AppConfig.defaultProfiles()
        }

        val migratedType = if (legacyMode == "OAUTH_PKCE") {
            ProviderType.CUSTOM_OAUTH
        } else {
            ProviderType.OPENAI_CHAT
        }
        val first = ProviderProfile(
            id = AppConfig.DEFAULT_OPENAI_ID,
            name = if (migratedType == ProviderType.CUSTOM_OAUTH) "迁移 OAuth 配置" else "OpenAI",
            type = migratedType,
            baseUrl = legacyBase ?: "https://api.openai.com/v1",
            model = legacyModel ?: "gpt-4o-mini",
            apiKey = runCatching { decryptString(legacyKeyEnc) }.getOrDefault(""),
            oauthAuthUrl = legacyAuthUrl,
            oauthTokenUrl = legacyTokenUrl,
            oauthClientId = legacyClientId,
            oauthScopes = legacyScopes,
            oauthAccessToken = runCatching { decryptString(legacyAccessEnc) }.getOrDefault(""),
            oauthRefreshToken = runCatching { decryptString(legacyRefreshEnc) }.getOrDefault(""),
            temperature = legacyTemp
        )
        return listOf(first, AppConfig.defaultCodexProfile())
    }

    private fun encodeProfiles(profiles: List<ProviderProfile>): String {
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("type", p.type.name)
                    put("baseUrl", p.baseUrl)
                    put("model", p.model)
                    put("apiKeyEnc", encryptString(p.apiKey))
                    put("oauthAuthUrl", p.oauthAuthUrl)
                    put("oauthTokenUrl", p.oauthTokenUrl)
                    put("oauthClientId", p.oauthClientId)
                    put("oauthScopes", p.oauthScopes)
                    put("oauthAccessEnc", encryptString(p.oauthAccessToken))
                    put("oauthRefreshEnc", encryptString(p.oauthRefreshToken))
                    put("oauthExpiresAtMs", p.oauthExpiresAtMs)
                    put("oauthAccountId", p.oauthAccountId)
                    put("oauthEmail", p.oauthEmail)
                    put("oauthPlan", p.oauthPlan)
                    put("temperature", p.temperature)
                }
            )
        }
        return arr.toString()
    }

    private fun decodeProfiles(rawJson: String): List<ProviderProfile> {
        val arr = JSONArray(rawJson)
        val out = ArrayList<ProviderProfile>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val typeName = obj.optString("type", ProviderType.OPENAI_CHAT.name)
            val type = ProviderType.entries.firstOrNull { it.name == typeName } ?: ProviderType.OPENAI_CHAT
            out.add(
                ProviderProfile(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    name = obj.optString("name", "OpenAI"),
                    type = type,
                    baseUrl = obj.optString("baseUrl", "https://api.openai.com/v1"),
                    model = obj.optString("model", "gpt-4o-mini"),
                    apiKey = decryptString(obj.optString("apiKeyEnc", "")),
                    oauthAuthUrl = obj.optString("oauthAuthUrl", ""),
                    oauthTokenUrl = obj.optString("oauthTokenUrl", ""),
                    oauthClientId = obj.optString("oauthClientId", ""),
                    oauthScopes = obj.optString("oauthScopes", "openid profile offline_access"),
                    oauthAccessToken = decryptString(obj.optString("oauthAccessEnc", "")),
                    oauthRefreshToken = decryptString(obj.optString("oauthRefreshEnc", "")),
                    oauthExpiresAtMs = obj.optLong("oauthExpiresAtMs", 0L),
                    oauthAccountId = obj.optString("oauthAccountId", ""),
                    oauthEmail = obj.optString("oauthEmail", ""),
                    oauthPlan = obj.optString("oauthPlan", ""),
                    temperature = obj.optDouble("temperature", 0.3)
                )
            )
        }
        return out
    }

    private fun encodeOverlayPrefs(p: OverlayPrefs): String = p.toJson()

    private fun decodeOverlayPrefs(rawJson: String): OverlayPrefs = OverlayPrefs.fromJson(rawJson)

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    private fun encryptString(plain: String): String {
        val trimmed = plain.trim()
        if (trimmed.isEmpty()) return ""
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decryptString(encoded: String?): String {
        if (encoded.isNullOrBlank()) return ""
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val buf = ByteBuffer.wrap(packed)
        val ivLen = buf.int
        require(ivLen in 12..32) { "Corrupted encrypted payload IV length: $ivLen" }
        val iv = ByteArray(ivLen)
        buf.get(iv)
        val ciphertext = ByteArray(buf.remaining())
        buf.get(ciphertext)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        private const val PREFS_NAME = "overlay_ai_config"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "overlay_ai_aes_gcm_v1"
        private const val AES_GCM = "AES/GCM/NoPadding"

        private const val KEY_PROFILES_JSON = "profiles_v2_json"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_OVERLAY_PREFS_JSON = "overlay_prefs_v2_json"
        private const val KEY_HOOK_PACKAGES = "hook_packages"
        private const val KEY_TIKU_CONFIG_JSON = "tiku_config_v1_json"
        private const val KEY_PENDING_OAUTH_STATE = "pending_oauth_state"
        private const val KEY_PENDING_OAUTH_PROFILE_ID = "pending_oauth_profile_id"
        private const val KEY_PENDING_OAUTH_VERIFIER_ENC = "pending_oauth_verifier_enc"

        private const val LEGACY_BASE_URL = "base_url"
        private const val LEGACY_MODEL = "model"
        private const val LEGACY_API_KEY_ENC = "api_key_enc"
        private const val LEGACY_AUTH_MODE = "auth_mode"
        private const val LEGACY_OAUTH_AUTH_URL = "oauth_auth_url"
        private const val LEGACY_OAUTH_TOKEN_URL = "oauth_token_url"
        private const val LEGACY_OAUTH_CLIENT_ID = "oauth_client_id"
        private const val LEGACY_OAUTH_SCOPES = "oauth_scopes"
        private const val LEGACY_OAUTH_ACCESS_ENC = "oauth_access_enc"
        private const val LEGACY_OAUTH_REFRESH_ENC = "oauth_refresh_enc"
        private const val LEGACY_TEMPERATURE = "temperature"
    }
}
