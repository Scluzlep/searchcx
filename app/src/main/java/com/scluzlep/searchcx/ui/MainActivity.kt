package com.scluzlep.searchcx.ui

import androidx.compose.runtime.collectAsState
import top.yukonga.miuix.kmp.basic.Button

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.scluzlep.searchcx.data.AppConfig
import com.scluzlep.searchcx.data.AppConfigStore
import com.scluzlep.searchcx.data.CollapsedBallStyle
import com.scluzlep.searchcx.data.OverlayPrefs
import com.scluzlep.searchcx.data.ProviderProfile
import com.scluzlep.searchcx.data.ProviderType
import com.scluzlep.searchcx.net.LoopbackOAuthServer
import com.scluzlep.searchcx.net.OAuthCallbackForegroundService
import com.scluzlep.searchcx.net.OpenAiClient
import com.scluzlep.searchcx.net.PkceOAuthManager
import com.scluzlep.searchcx.overlay.OverlayService
import com.scluzlep.searchcx.tiku.TikuAdapterClient
import com.scluzlep.searchcx.tiku.TikuConfig
import com.scluzlep.searchcx.tiku.TikuMode
import com.scluzlep.searchcx.tiku.TikuProvider
import com.scluzlep.searchcx.tiku.TikuProviderTokens
import com.scluzlep.searchcx.xposed.ModuleStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var configStore: AppConfigStore
    private val openAiClient = OpenAiClient()
    private val tikuClient = TikuAdapterClient()
    private val oauthManager = PkceOAuthManager()
    private var codexLoginJob: Job? = null

    private var lsposedActive by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)

    private var config by mutableStateOf(AppConfig())
    private var fetchedModels by mutableStateOf<List<String>>(emptyList())
    private var statusMessage by mutableStateOf("")
    private var statusColor by mutableStateOf(COLOR_INFO)

    // Editable draft fields synced with config.activeProfile()
    private var editProfileId by mutableStateOf("")
    private var editName by mutableStateOf("")
    private var editType by mutableStateOf(ProviderType.OPENAI_CHAT)
    private var editBaseUrl by mutableStateOf("")
    private var editModel by mutableStateOf("")
    private var editApiKey by mutableStateOf("")
    private var editOAuthAuthUrl by mutableStateOf("")
    private var editOAuthTokenUrl by mutableStateOf("")
    private var editOAuthClientId by mutableStateOf("")
    private var editOAuthScopes by mutableStateOf("openid profile offline_access")
    private var editSystemPrompt by mutableStateOf("")
    private var editQuickPrompt by mutableStateOf("")

    // Editable draft fields synced with config.tikuConfig
    private var editTikuEnabled by mutableStateOf(true)
    private var editTikuMode by mutableStateOf(TikuMode.DIRECT_BUILTIN)
    private var editTikuRemoteUrl by mutableStateOf(TikuConfig.DEFAULT_REMOTE_URL)
    private var editTikuAutoQuery by mutableStateOf(true)
    private var editTikuSkipAiOnHit by mutableStateOf(false)
    private var editTikuProviders by mutableStateOf(TikuProvider.defaultFreeIds())
    private var editTikuTokens by mutableStateOf(TikuProviderTokens())
    private var showIcodefTokenField by mutableStateOf(false)
    private var tikuStatusMessage by mutableStateOf("")
    private var tikuStatusColor by mutableStateOf(COLOR_INFO)

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startOverlayService() else setStatus("未授予通知权限，无法启动前台悬浮服务", COLOR_ERROR)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = AppConfigStore(this)
        refreshRuntimeFlags()
        loadAllFromStore()
        handleOAuthCallback(intent)

        setContent {
            MiuixTheme(colors = lightColorScheme()) {
                Scaffold(
                    topBar = { TopAppBar(title = "SearchCX") }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SmallTitle(
                            text = "悬浮窗",
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        )
                        val xposedStatus by ModuleStatus.status.collectAsState()
                        val effectiveActive = xposedStatus.isActive || lsposedActive
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(16.dp)
                        ) {
                            Text(
                                text = if (effectiveActive) "LSPosed 已激活" else "LSPosed 未激活",
                                color = if (effectiveActive) COLOR_SUCCESS else COLOR_WARN,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (xposedStatus.serviceBound) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "${xposedStatus.frameworkName} ${xposedStatus.frameworkVersion} · API ${xposedStatus.apiVersion}",
                                    color = Color(0xFF475569),
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "作用域 ${xposedStatus.scope.size} · 运行中 ${xposedStatus.runningTargets.size}",
                                    color = Color(0xFF475569),
                                    fontSize = 12.sp
                                )
                                if (xposedStatus.runningTargets.isNotEmpty()) {
                                    val targetLine = xposedStatus.runningTargets.take(3).joinToString(" | ") {
                                        "${it.processName}[${it.state}]"
                                    } + if (xposedStatus.runningTargets.size > 3) " +${xposedStatus.runningTargets.size - 3}" else ""
                                    Text(
                                        text = targetLine,
                                        color = if (xposedStatus.staleTargetCount > 0) COLOR_WARN else Color(0xFF64748B),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (xposedStatus.lastActionMessage.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = xposedStatus.lastActionMessage,
                                    color = Color(0xFF2563EB),
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { refreshRuntimeFlags() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("刷新状态", fontSize = 13.sp)
                                }
                                Button(
                                    onClick = { ModuleStatus.hotReloadTargets(reloadAllIfNoneStale = true) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("热重载宿主", fontSize = 13.sp)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (overlayGranted) "悬浮窗权限：已开启" else "悬浮窗权限：未开启",
                                color = if (overlayGranted) COLOR_SUCCESS else COLOR_WARN,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    text = "权限",
                                    onClick = { openOverlayPermissionSettings() },
                                    modifier = Modifier.weight(0.8f)
                                )
                                TextButton(
                                    text = "启动悬浮窗",
                                    onClick = { ensurePermissionsAndStartOverlay() },
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                    modifier = Modifier.weight(1.4f)
                                )
                                TextButton(
                                    text = "停止",
                                    onClick = {
                                        stopService(Intent(this@MainActivity, OverlayService::class.java))
                                        setStatus("悬浮服务已停止", COLOR_INFO)
                                    },
                                    modifier = Modifier.weight(0.8f)
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            val prefs = config.overlayPrefs
                            PrefToggleRow(
                                label = "展开时自动读取网页",
                                checked = prefs.autoCaptureOnExpand,
                                onToggle = { updateOverlayPrefs { copy(autoCaptureOnExpand = !it.autoCaptureOnExpand) } }
                            )
                            PrefToggleRow(
                                label = "长按直接抓取并提问",
                                checked = prefs.longPressAutoAsk,
                                onToggle = { updateOverlayPrefs { copy(longPressAutoAsk = !it.longPressAutoAsk) } }
                            )
                            PrefToggleRow(
                                label = "闲置自动贴边半隐",
                                checked = prefs.idleEdgeRetract,
                                onToggle = { updateOverlayPrefs { copy(idleEdgeRetract = !it.idleEdgeRetract) } }
                            )
                            PrefToggleRow(
                                label = "悬浮窗防截屏",
                                checked = prefs.secureOverlay,
                                onToggle = { updateOverlayPrefs { copy(secureOverlay = !it.secureOverlay) } }
                            )

                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "收起样式",
                                fontSize = 12.sp,
                                color = Color(0xFF6B7280)
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    CollapsedBallStyle.BUTTON to "圆形按钮",
                                    CollapsedBallStyle.MINI_DOT to "白色小点"
                                ).forEach { (style, lbl) ->
                                    SelectableChip(
                                        label = lbl,
                                        selected = prefs.collapsedBallStyle == style,
                                        modifier = Modifier.weight(1f),
                                        onClick = { updateOverlayPrefs { copy(collapsedBallStyle = style) } }
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "悬浮窗透明度",
                                fontSize = 12.sp,
                                color = Color(0xFF6B7280)
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(0.75f to "75%", 0.90f to "90%", 0.96f to "96%", 1.0f to "100%").forEach { (op, lbl) ->
                                    val selected = kotlin.math.abs(prefs.panelOpacity - op) < 0.03f
                                    SelectableChip(
                                        label = lbl,
                                        selected = selected,
                                        modifier = Modifier.weight(1f),
                                        onClick = { updateOverlayPrefs { copy(panelOpacity = op) } }
                                    )
                                }
                            }
                        }

                        SmallTitle(
                            text = "AI 配置",
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(16.dp)
                        ) {
                            // Profile selector strip
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                config.profiles.forEach { profile ->
                                    SelectableChip(
                                        label = profile.name.ifBlank { profile.type.name },
                                        selected = profile.id == config.activeProfileId,
                                        onClick = { switchActiveProfile(profile.id) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    text = "+ OAI",
                                    onClick = { addNewProfile(ProviderType.OPENAI_CHAT) },
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    text = "+ Codex",
                                    onClick = { addNewProfile(ProviderType.CODEX_OAUTH) },
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    text = "删除当前",
                                    onClick = { deleteActiveProfile() },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            TextField(
                                value = editName,
                                onValueChange = { editName = it },
                                label = "配置名称",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))

                            Text(
                                text = "接口协议类型",
                                fontSize = 12.sp,
                                color = Color(0xFF6B7280)
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    ProviderType.OPENAI_CHAT to "OAI Chat",
                                    ProviderType.OPENAI_RESPONSES to "OAI Responses",
                                    ProviderType.CODEX_OAUTH to "Codex OAuth",
                                    ProviderType.CUSTOM_OAUTH to "自定义 OAuth"
                                ).forEach { (t, lbl) ->
                                    SelectableChip(
                                        label = lbl,
                                        selected = editType == t,
                                        onClick = {
                                            editType = t
                                            if (t == ProviderType.CODEX_OAUTH) {
                                                editBaseUrl = ProviderProfile.CODEX_BASE_URL
                                                if (editModel == "gpt-4o-mini" || editModel.isBlank()) {
                                                    editModel = "gpt-5.1-codex"
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))
                            if (editType != ProviderType.CODEX_OAUTH) {
                                TextField(
                                    value = editBaseUrl,
                                    onValueChange = { editBaseUrl = it },
                                    label = "Base URL",
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(10.dp))
                            } else {
                                Text(
                                    text = "Codex 端点：${ProviderProfile.CODEX_BASE_URL}/responses",
                                    fontSize = 12.sp,
                                    color = Color(0xFF4B5563)
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextField(
                                    value = editModel,
                                    onValueChange = { editModel = it },
                                    label = "Model",
                                    singleLine = true,
                                    modifier = Modifier.weight(1.4f)
                                )
                                TextButton(
                                    text = "拉取模型",
                                    onClick = { fetchModelsForActiveProfile() },
                                    modifier = Modifier.weight(0.9f)
                                )
                            }

                            if (fetchedModels.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    fetchedModels.take(18).forEach { m ->
                                        SelectableChip(
                                            label = m,
                                            selected = editModel.trim() == m,
                                            onClick = { editModel = m }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))
                            when (editType) {
                                ProviderType.OPENAI_CHAT, ProviderType.OPENAI_RESPONSES -> {
                                    TextField(
                                        value = editApiKey,
                                        onValueChange = { editApiKey = it },
                                        label = "API Key",
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                ProviderType.CODEX_OAUTH -> {
                                    val active = config.activeProfile()
                                    val signedIn = active.oauthAccessToken.isNotBlank()
                                    val accountLine = if (signedIn) {
                                        val who = active.oauthEmail.ifBlank { active.oauthAccountId.ifBlank { "已登录" } }
                                        val plan = active.oauthPlan.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                                        "Codex：已登录 · $who$plan"
                                    } else {
                                        "Codex：未登录"
                                    }
                                    Text(
                                        text = accountLine,
                                        color = if (signedIn) COLOR_SUCCESS else Color(0xFF4B5563),
                                        fontSize = 12.sp
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        TextButton(
                                            text = if (signedIn) "重新登录" else "登录 Codex",
                                            onClick = { startCodexOAuthLogin() },
                                            colors = ButtonDefaults.textButtonColorsPrimary(),
                                            modifier = Modifier.weight(1.4f)
                                        )
                                        TextButton(
                                            text = "刷新",
                                            onClick = { refreshActiveOAuthToken() },
                                            modifier = Modifier.weight(0.7f)
                                        )
                                        TextButton(
                                            text = "退出",
                                            onClick = { logoutActiveOAuth() },
                                            modifier = Modifier.weight(0.7f)
                                        )
                                    }
                                }
                                ProviderType.CUSTOM_OAUTH -> {
                                    val active = config.activeProfile()
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        TextField(
                                            value = editOAuthAuthUrl,
                                            onValueChange = { editOAuthAuthUrl = it },
                                            label = "Authorize Endpoint",
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        TextField(
                                            value = editOAuthTokenUrl,
                                            onValueChange = { editOAuthTokenUrl = it },
                                            label = "Token Endpoint",
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        TextField(
                                            value = editOAuthClientId,
                                            onValueChange = { editOAuthClientId = it },
                                            label = "OAuth Client ID",
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        TextField(
                                            value = editOAuthScopes,
                                            onValueChange = { editOAuthScopes = it },
                                            label = "Scopes",
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            text = if (active.oauthAccessToken.isNotBlank()) {
                                                "OAuth：已登录"
                                            } else {
                                                "OAuth：未登录"
                                            },
                                            color = if (active.oauthAccessToken.isNotBlank()) COLOR_SUCCESS else Color(0xFF4B5563),
                                            fontSize = 12.sp
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            TextButton(
                                                text = "浏览器授权",
                                                onClick = { launchCustomOAuthLogin() },
                                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(
                                                text = "刷新 Token",
                                                onClick = { refreshActiveOAuthToken() },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        SmallTitle(
                            text = "题库配置",
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(16.dp)
                        ) {
                            PrefToggleRow(
                                label = "启用 tikuAdapter 题库",
                                checked = editTikuEnabled,
                                onToggle = { editTikuEnabled = !editTikuEnabled }
                            )

                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "接入方式",
                                fontSize = 12.sp,
                                color = Color(0xFF6B7280)
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    TikuMode.DIRECT_BUILTIN to "内置聚合",
                                    TikuMode.REMOTE_ADAPTER to "远端服务"
                                ).forEach { (mode, label) ->
                                    SelectableChip(
                                        label = label,
                                        selected = editTikuMode == mode,
                                        modifier = Modifier.weight(1f),
                                        onClick = { editTikuMode = mode }
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (editTikuMode == TikuMode.DIRECT_BUILTIN) {
                                    "直连题库接口完成规范化与投票，无需部署服务端"
                                } else {
                                    "连接自建 tikuAdapter 服务，支持本地库与不挂科"
                                },
                                fontSize = 11.sp,
                                color = Color(0xFF6B7280)
                            )

                            if (editTikuMode == TikuMode.REMOTE_ADAPTER) {
                                Spacer(Modifier.height(10.dp))
                                TextField(
                                    value = editTikuRemoteUrl,
                                    onValueChange = { editTikuRemoteUrl = it },
                                    label = "远端服务地址",
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(Modifier.height(6.dp))
                            PrefToggleRow(
                                label = "提问时先查题库",
                                checked = editTikuAutoQuery,
                                onToggle = { editTikuAutoQuery = !editTikuAutoQuery }
                            )
                            PrefToggleRow(
                                label = "全命中时跳过 AI",
                                checked = editTikuSkipAiOnHit,
                                onToggle = { editTikuSkipAiOnHit = !editTikuSkipAiOnHit }
                            )

                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "免费题库源",
                                fontSize = 12.sp,
                                color = Color(0xFF4B5563),
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(6.dp))
                            val visibleFreeProviders = TikuProvider.freeProviders().filter {
                                editTikuMode == TikuMode.REMOTE_ADAPTER || !it.remoteOnly
                            }
                            TikuProviderChipRow(
                                providers = visibleFreeProviders,
                                selectedIds = editTikuProviders,
                                onToggle = { id -> toggleTikuProvider(id) }
                            )

                            if (TikuProvider.ICODEF.id in editTikuProviders) {
                                val icodefExpanded = showIcodefTokenField || editTikuTokens.icodefToken.isNotBlank()
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = if (icodefExpanded) "icodef Token · 选填 收起" else "icodef Token · 选填",
                                    fontSize = 11.sp,
                                    color = COLOR_INFO,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clickable {
                                        showIcodefTokenField = !icodefExpanded
                                    }
                                )
                                if (icodefExpanded) {
                                    Spacer(Modifier.height(6.dp))
                                    TextField(
                                        value = editTikuTokens.icodefToken,
                                        onValueChange = {
                                            editTikuTokens = editTikuTokens.withToken(TikuProvider.ICODEF, it)
                                        },
                                        label = "icodef Token",
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "收费题库源",
                                fontSize = 12.sp,
                                color = Color(0xFF4B5563),
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "默认关闭，按需开启并填写对应 Token",
                                fontSize = 11.sp,
                                color = Color(0xFF6B7280)
                            )
                            Spacer(Modifier.height(6.dp))
                            TikuProviderChipRow(
                                providers = TikuProvider.paidProviders(),
                                selectedIds = editTikuProviders,
                                onToggle = { id -> toggleTikuProvider(id) }
                            )

                            val enabledPaidProviders = TikuProvider.paidProviders().filter {
                                it.id in editTikuProviders
                            }
                            if (enabledPaidProviders.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    enabledPaidProviders.forEach { provider ->
                                        TextField(
                                            value = editTikuTokens.tokenFor(provider),
                                            onValueChange = {
                                                editTikuTokens = editTikuTokens.withToken(provider, it)
                                            },
                                            label = "${provider.displayName} Token",
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    text = "保存题库",
                                    onClick = { saveTikuConfigToStore() },
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    text = "测试题库",
                                    onClick = { testTikuConnection() },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (tikuStatusMessage.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = tikuStatusMessage,
                                    color = tikuStatusColor,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        SmallTitle(
                            text = "系统提示词与保存",
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(16.dp)
                        ) {
                            TextField(
                                value = editSystemPrompt,
                                onValueChange = { editSystemPrompt = it },
                                label = "系统提示词",
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            TextField(
                                value = editQuickPrompt,
                                onValueChange = { editQuickPrompt = it },
                                label = "默认提问指令",
                                minLines = 2,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    text = "保存全部配置",
                                    onClick = { saveDraftToStore() },
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    text = "测试连接",
                                    onClick = { testApiConnection() },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (statusMessage.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = statusMessage,
                                    color = statusColor,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        config = config.copy(overlayPrefs = configStore.load().overlayPrefs)
        refreshRuntimeFlags()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
    }

    override fun onDestroy() {
        codexLoginJob?.cancel()
        OAuthCallbackForegroundService.stop(this)
        super.onDestroy()
    }

    private fun refreshRuntimeFlags() {
        ModuleStatus.refresh()
        lsposedActive = ModuleStatus.isActive()
        overlayGranted = Settings.canDrawOverlays(this)
    }

    private fun loadAllFromStore() {
        val loaded = configStore.load()
        config = loaded
        loadProfileIntoDraft(loaded.activeProfile(), loaded)
        loadTikuIntoDraft(loaded.tikuConfig)
    }

    private fun loadTikuIntoDraft(tiku: TikuConfig) {
        editTikuEnabled = tiku.enabled
        editTikuMode = tiku.mode
        editTikuRemoteUrl = tiku.remoteBaseUrl
        editTikuAutoQuery = tiku.autoQueryBeforeAi
        editTikuSkipAiOnHit = tiku.skipAiOnHit
        editTikuProviders = tiku.enabledProviders
        editTikuTokens = tiku.tokens
        showIcodefTokenField = tiku.tokens.icodefToken.isNotBlank()
    }

    private fun toggleTikuProvider(providerId: String) {
        editTikuProviders = if (providerId in editTikuProviders) {
            editTikuProviders - providerId
        } else {
            editTikuProviders + providerId
        }
    }

    private fun buildTikuConfigFromDraft(): TikuConfig {
        return TikuConfig(
            enabled = editTikuEnabled,
            mode = editTikuMode,
            remoteBaseUrl = editTikuRemoteUrl.trim().ifEmpty { TikuConfig.DEFAULT_REMOTE_URL },
            autoQueryBeforeAi = editTikuAutoQuery,
            skipAiOnHit = editTikuSkipAiOnHit,
            enabledProviders = editTikuProviders,
            tokens = editTikuTokens
        )
    }

    private fun loadProfileIntoDraft(p: ProviderProfile, cfg: AppConfig) {
        editProfileId = p.id
        editName = p.name
        editType = p.type
        editBaseUrl = p.baseUrl
        editModel = p.model
        editApiKey = p.apiKey
        editOAuthAuthUrl = p.oauthAuthUrl
        editOAuthTokenUrl = p.oauthTokenUrl
        editOAuthClientId = p.oauthClientId
        editOAuthScopes = p.oauthScopes
        editSystemPrompt = cfg.systemPrompt
        editQuickPrompt = cfg.overlayPrefs.quickAskPrompt
        fetchedModels = emptyList()
    }

    private fun buildConfigWithDraft(): AppConfig {
        val fresh = configStore.load()
        val existingProfile = fresh.profiles.firstOrNull { it.id == editProfileId } ?: fresh.activeProfile()
        val updatedProfile = existingProfile.copy(
            name = editName.trim().ifBlank { existingProfile.name },
            type = editType,
            baseUrl = if (editType == ProviderType.CODEX_OAUTH) {
                ProviderProfile.CODEX_BASE_URL
            } else {
                editBaseUrl.trim()
            },
            model = editModel.trim(),
            apiKey = editApiKey.trim(),
            oauthAuthUrl = editOAuthAuthUrl.trim(),
            oauthTokenUrl = editOAuthTokenUrl.trim(),
            oauthClientId = editOAuthClientId.trim(),
            oauthScopes = editOAuthScopes.trim()
        )
        return fresh.copy(
            activeProfileId = updatedProfile.id,
            systemPrompt = editSystemPrompt.trim(),
            overlayPrefs = config.overlayPrefs.copy(quickAskPrompt = editQuickPrompt.trim()),
            tikuConfig = buildTikuConfigFromDraft()
        ).withUpdatedProfile(updatedProfile)
    }

    private fun saveDraftToStore(): AppConfig {
        val updated = buildConfigWithDraft()
        configStore.save(updated)
        config = updated
        notifyOverlayRefresh()
        setStatus("配置已保存", COLOR_SUCCESS)
        return updated
    }

    private fun saveTikuConfigToStore(): AppConfig {
        val updated = buildConfigWithDraft()
        configStore.save(updated)
        config = updated
        notifyOverlayRefresh()
        setTikuStatus("题库配置已保存", COLOR_SUCCESS)
        return updated
    }

    private fun testTikuConnection() {
        val cfg = saveTikuConfigToStore()
        val tikuCfg = cfg.tikuConfig
        setTikuStatus("正在测试题库 (${tikuCfg.mode.displayName})...", COLOR_INFO)
        lifecycleScope.launch {
            tikuClient.testConnection(tikuCfg)
                .onSuccess { msg -> setTikuStatus(msg, COLOR_SUCCESS) }
                .onFailure { err -> setTikuStatus("题库测试失败: ${err.message}", COLOR_ERROR) }
        }
    }

    private fun updateOverlayPrefs(transform: OverlayPrefs.(OverlayPrefs) -> OverlayPrefs) {
        val current = buildConfigWithDraft()
        val nextPrefs = current.overlayPrefs.transform(current.overlayPrefs)
        val updated = current.copy(overlayPrefs = nextPrefs)
        configStore.save(updated)
        config = updated
        notifyOverlayRefresh()
    }

    private fun switchActiveProfile(profileId: String) {
        val savedDraft = buildConfigWithDraft()
        val switched = savedDraft.copy(activeProfileId = profileId)
        configStore.save(switched)
        config = switched
        loadProfileIntoDraft(switched.activeProfile(), switched)
        notifyOverlayRefresh()
    }

    private fun addNewProfile(type: ProviderType) {
        val baseCfg = buildConfigWithDraft()
        val newProfile = when (type) {
            ProviderType.CODEX_OAUTH -> ProviderProfile(
                id = UUID.randomUUID().toString(),
                name = "Codex ${baseCfg.profiles.size + 1}",
                type = ProviderType.CODEX_OAUTH,
                baseUrl = ProviderProfile.CODEX_BASE_URL,
                model = "gpt-5.1-codex"
            )
            else -> ProviderProfile(
                id = UUID.randomUUID().toString(),
                name = "OpenAI ${baseCfg.profiles.size + 1}",
                type = ProviderType.OPENAI_CHAT,
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4o-mini"
            )
        }
        val next = baseCfg.withAddedProfile(newProfile, activate = true)
        configStore.save(next)
        config = next
        loadProfileIntoDraft(newProfile, next)
        notifyOverlayRefresh()
    }

    private fun deleteActiveProfile() {
        val cur = configStore.load()
        if (cur.profiles.size <= 1) {
            setStatus("至少保留一个服务商配置", COLOR_WARN)
            return
        }
        val next = cur.withRemovedProfile(cur.activeProfileId)
        configStore.save(next)
        config = next
        loadProfileIntoDraft(next.activeProfile(), next)
        notifyOverlayRefresh()
        setStatus("已删除选中配置", COLOR_INFO)
    }

    private fun notifyOverlayRefresh() {
        if (!OverlayService.isRunning) return
        runCatching {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_REFRESH_CONFIG
            }
            startService(intent)
        }
    }

    private fun openOverlayPermissionSettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun ensurePermissionsAndStartOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            setStatus("请先授予「显示在其他应用上层」悬浮窗权限", COLOR_WARN)
            openOverlayPermissionSettings()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startOverlayService()
    }

    private fun startOverlayService() {
        saveDraftToStore()
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        setStatus("悬浮窗已启动", COLOR_SUCCESS)
    }

    private fun fetchModelsForActiveProfile() {
        val cfg = saveDraftToStore()
        val profile = cfg.activeProfile()
        setStatus("正在从 ${profile.normalizedBaseUrl()} 拉取模型列表...", COLOR_INFO)
        lifecycleScope.launch {
            runCatching {
                openAiClient.fetchModels(profile) { updated ->
                    configStore.updateProfile(updated.id) { updated }
                    loadAllFromStore()
                }
            }.onSuccess { models ->
                fetchedModels = models
                setStatus(
                    if (models.isEmpty()) "接口返回 0 个模型，可手动输入 Model 名称"
                    else "拉取成功：共 ${models.size} 个模型，点击上方标签快速选用",
                    COLOR_SUCCESS
                )
            }.onFailure { err ->
                setStatus("拉取模型失败: ${err.message}", COLOR_ERROR)
            }
        }
    }

    private fun testApiConnection() {
        val cfg = saveDraftToStore()
        val profile = cfg.activeProfile()
        setStatus("正在测试连接 ${profile.normalizedBaseUrl()} ...", COLOR_INFO)
        lifecycleScope.launch {
            runCatching {
                openAiClient.testConnection(profile) { updated ->
                    configStore.updateProfile(updated.id) { updated }
                    loadAllFromStore()
                }
            }.onSuccess {
                setStatus(it, COLOR_SUCCESS)
            }.onFailure {
                setStatus("连接测试失败: ${it.message}", COLOR_ERROR)
            }
        }
    }

    private fun startCodexOAuthLogin() {
        val cfg = saveDraftToStore()
        val targetId = cfg.activeProfileId
        codexLoginJob?.cancel()
        setStatus("正在启动本地 127.0.0.1:1455 环回服务并打开 ChatGPT 授权页...", COLOR_INFO)

        codexLoginJob = lifecycleScope.launch {
            var server: LoopbackOAuthServer? = null
            try {
                OAuthCallbackForegroundService.start(this@MainActivity)
                server = LoopbackOAuthServer(
                    port = PkceOAuthManager.CODEX_CALLBACK_PORT,
                    callbackPath = PkceOAuthManager.CODEX_CALLBACK_PATH,
                    redirectHost = PkceOAuthManager.CODEX_CALLBACK_HOST
                )
                val verifier = oauthManager.generateCodeVerifier()
                val challenge = oauthManager.computeS256Challenge(verifier)
                val state = oauthManager.generateState()
                val authUrl = oauthManager.buildCodexAuthorizeUrl(
                    state = state,
                    codeChallenge = challenge,
                    redirectUri = server.redirectUri
                )
                server.startListening(expectedState = state)
                launchBrowserTab(authUrl)
                val callback = server.awaitCallback(expectedState = state)
                if (!callback.error.isNullOrBlank()) {
                    error("授权失败：${callback.error} ${callback.errorDescription.orEmpty()}")
                }
                val code = callback.code ?: error("未收到授权码 code")
                setStatus("正在交换 Codex Token ...", COLOR_INFO)
                val cred = oauthManager.exchangeCodexCode(code, verifier, server.redirectUri)
                configStore.updateProfile(targetId) { p ->
                    p.copy(
                        type = ProviderType.CODEX_OAUTH,
                        baseUrl = ProviderProfile.CODEX_BASE_URL,
                        oauthAccessToken = cred.accessToken,
                        oauthRefreshToken = cred.refreshToken ?: p.oauthRefreshToken,
                        oauthExpiresAtMs = cred.expiresAtMs,
                        oauthAccountId = cred.accountId,
                        oauthEmail = cred.email,
                        oauthPlan = cred.plan
                    )
                }
                loadAllFromStore()
                notifyOverlayRefresh()
                setStatus("Codex 登录成功：${cred.email.ifBlank { cred.accountId }}", COLOR_SUCCESS)
            } catch (ex: Throwable) {
                setStatus("Codex 登录失败: ${ex.message ?: ex.javaClass.simpleName}", COLOR_ERROR)
            } finally {
                server?.close()
                OAuthCallbackForegroundService.stop(this@MainActivity)
            }
        }
    }

    private fun launchCustomOAuthLogin() {
        runCatching {
            val cfg = saveDraftToStore()
            val p = cfg.activeProfile()
            val verifier = oauthManager.generateCodeVerifier()
            val challenge = oauthManager.computeS256Challenge(verifier)
            val state = oauthManager.generateState()
            val authUrl = oauthManager.buildAuthorizeUrl(
                authEndpoint = p.oauthAuthUrl,
                clientId = p.oauthClientId,
                scopes = p.oauthScopes,
                state = state,
                codeChallenge = challenge
            )
            configStore.saveOAuthState(state, verifier, p.id)
            launchBrowserTab(authUrl)
            setStatus("已拉起浏览器进行 OAuth PKCE 授权...", COLOR_INFO)
        }.onFailure {
            setStatus("OAuth 发起失败: ${it.message}", COLOR_ERROR)
        }
    }

    private fun launchBrowserTab(url: String) {
        val customTabs = CustomTabsIntent.Builder().setShowTitle(true).build()
        runCatching {
            customTabs.launchUrl(this, Uri.parse(url))
        }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "searchcx" || data.host != "oauth") return

        val err = data.getQueryParameter("error")
        if (!err.isNullOrBlank()) {
            val desc = data.getQueryParameter("error_description").orEmpty()
            setStatus("OAuth 授权被拒绝: $err $desc", COLOR_ERROR)
            return
        }

        val code = data.getQueryParameter("code")
        val returnedState = data.getQueryParameter("state")
        if (code.isNullOrBlank() || returnedState.isNullOrBlank()) {
            setStatus("OAuth 回调缺少 code 或 state 参数", COLOR_ERROR)
            return
        }

        val (expectedState, verifier, profileId) = runCatching { configStore.consumeOAuthState() }.getOrElse {
            setStatus("OAuth 状态校验失败: ${it.message}", COLOR_ERROR)
            return
        }
        if (returnedState != expectedState) {
            setStatus("OAuth 校验失败", COLOR_ERROR)
            return
        }

        val cfg = configStore.load()
        val target = cfg.profiles.firstOrNull { it.id == profileId } ?: cfg.activeProfile()
        setStatus("正在用授权码交换 Access Token ...", COLOR_INFO)
        lifecycleScope.launch {
            runCatching {
                oauthManager.exchangeCodeForToken(
                    tokenEndpoint = target.oauthTokenUrl,
                    clientId = target.oauthClientId,
                    code = code,
                    codeVerifier = verifier
                )
            }.onSuccess { token ->
                val exp = if (token.expiresInSec > 0L) {
                    System.currentTimeMillis() + token.expiresInSec * 1000L
                } else {
                    0L
                }
                configStore.updateProfile(target.id) { p ->
                    p.copy(
                        type = ProviderType.CUSTOM_OAUTH,
                        oauthAccessToken = token.accessToken,
                        oauthRefreshToken = token.refreshToken ?: p.oauthRefreshToken,
                        oauthExpiresAtMs = exp
                    )
                }
                loadAllFromStore()
                notifyOverlayRefresh()
                setStatus("OAuth 登录成功", COLOR_SUCCESS)
            }.onFailure { ex ->
                setStatus("交换 Token 失败: ${ex.message}", COLOR_ERROR)
            }
        }
    }

    private fun refreshActiveOAuthToken() {
        val cfg = saveDraftToStore()
        val p = cfg.activeProfile()
        val refresh = p.oauthRefreshToken.trim()
        if (refresh.isEmpty()) {
            setStatus("当前配置无 Refresh Token，请先登录", COLOR_ERROR)
            return
        }
        setStatus("正在刷新 OAuth Token ...", COLOR_INFO)
        lifecycleScope.launch {
            runCatching {
                when (p.type) {
                    ProviderType.CODEX_OAUTH -> {
                        val cred = oauthManager.refreshCodexToken(
                            refreshToken = refresh,
                            previousAccountId = p.oauthAccountId,
                            previousEmail = p.oauthEmail,
                            previousPlan = p.oauthPlan
                        )
                        configStore.updateProfile(p.id) { cur ->
                            cur.copy(
                                oauthAccessToken = cred.accessToken,
                                oauthRefreshToken = cred.refreshToken ?: refresh,
                                oauthExpiresAtMs = cred.expiresAtMs,
                                oauthAccountId = cred.accountId,
                                oauthEmail = cred.email,
                                oauthPlan = cred.plan
                            )
                        }
                    }
                    else -> {
                        val tok = oauthManager.refreshAccessToken(
                            tokenEndpoint = p.oauthTokenUrl,
                            clientId = p.oauthClientId,
                            refreshToken = refresh
                        )
                        val exp = if (tok.expiresInSec > 0L) {
                            System.currentTimeMillis() + tok.expiresInSec * 1000L
                        } else {
                            p.oauthExpiresAtMs
                        }
                        configStore.updateProfile(p.id) { cur ->
                            cur.copy(
                                oauthAccessToken = tok.accessToken,
                                oauthRefreshToken = tok.refreshToken ?: refresh,
                                oauthExpiresAtMs = exp
                            )
                        }
                    }
                }
            }.onSuccess {
                loadAllFromStore()
                notifyOverlayRefresh()
                setStatus("Token 刷新成功", COLOR_SUCCESS)
            }.onFailure { ex ->
                setStatus("Token 刷新失败: ${ex.message}", COLOR_ERROR)
            }
        }
    }

    private fun logoutActiveOAuth() {
        val p = config.activeProfile()
        configStore.updateProfile(p.id) { cur ->
            cur.copy(
                oauthAccessToken = "",
                oauthRefreshToken = "",
                oauthExpiresAtMs = 0L,
                oauthAccountId = "",
                oauthEmail = "",
                oauthPlan = ""
            )
        }
        loadAllFromStore()
        notifyOverlayRefresh()
        setStatus("已清除该账号的 OAuth 凭据", COLOR_INFO)
    }

    private fun setStatus(message: String, color: Color) {
        statusMessage = message
        statusColor = color
    }

    private fun setTikuStatus(message: String, color: Color) {
        tikuStatusMessage = message
        tikuStatusColor = color
    }

    companion object {
        private val COLOR_INFO = Color(0xFF3482FF)
        private val COLOR_SUCCESS = Color(0xFF15803D)
        private val COLOR_WARN = Color(0xFFB45309)
        private val COLOR_ERROR = Color(0xFFCF1322)
    }
}

@Composable
private fun PrefToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color(0xFF1F2937),
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) Color(0xFF3482FF) else Color(0xFFE5E7EB))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (checked) "开" else "关",
                color = if (checked) Color.White else Color(0xFF4B5563),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun TikuProviderChipRow(
    providers: List<TikuProvider>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        providers.forEach { provider ->
            SelectableChip(
                label = provider.displayName,
                selected = provider.id in selectedIds,
                onClick = { onToggle(provider.id) }
            )
        }
    }
}

@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFF3482FF) else Color(0xFFF2F2F7))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFF1F2937),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
