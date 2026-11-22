package com.overlayai.assistant.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.overlayai.assistant.data.AppConfigStore
import com.overlayai.assistant.data.CollapsedBallStyle
import com.overlayai.assistant.ipc.DomIpcContract
import com.overlayai.assistant.ipc.DomSnapshotBus
import com.overlayai.assistant.ipc.DomSnapshotReceiver
import com.overlayai.assistant.net.ChatTurn
import com.overlayai.assistant.net.OpenAiClient
import com.overlayai.assistant.net.StreamEvent
import com.overlayai.assistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.math.abs

class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var configStore: AppConfigStore
    private lateinit var screenCapture: ScreenCaptureHelper
    private val openAiClient = OpenAiClient()
    private val domReceiver = DomSnapshotReceiver()

    private var uiState by mutableStateOf(OverlayUiState())
    private val conversationHistory = mutableListOf<ChatTurn>()
    private var nextMsgId = 1L

    private var ballView: ComposeView? = null
    private lateinit var ballParams: WindowManager.LayoutParams
    private var ballOwner: OverlayLifecycleOwner? = null

    private var panelView: ComposeView? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private var panelOwner: OverlayLifecycleOwner? = null

    private var cropView: ComposeView? = null
    private var cropOwner: OverlayLifecycleOwner? = null
    private var activeScreenshot: Bitmap? = null

    private val promptFocusRequester = FocusRequester()
    private val contextFocusRequester = FocusRequester()

    private var activeAttachedImageBase64: String? = null
    private var isExpanded = false
    private var isPanelFocusable = false
    private var isDraggingBall = false
    private var mediaProjectionEnabledInFgs = false
    private var pendingCaptureAfterGrant = false

    private var activeChatJob: Job? = null
    private var idleRetractJob: Job? = null

    private val overlayUriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "缺少悬浮窗权限，服务退出", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        isRunning = true

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        configStore = AppConfigStore(this)
        screenCapture = ScreenCaptureHelper(this) {
            mainHandler.post {
                Toast.makeText(this, "屏幕捕获授权已失效，下次使用需重新授权", Toast.LENGTH_SHORT).show()
            }
        }

        startAsForegroundService(includeMediaProjection = false)

        ContextCompat.registerReceiver(
            this,
            domReceiver,
            IntentFilter(DomIpcContract.ACTION_DOM_SNAPSHOT),
            ContextCompat.RECEIVER_EXPORTED
        )

        syncStateFromConfig()
        initFloatingBall()
        initFloatingPanel()
        showBallOnly()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_CONFIG -> {
                syncStateFromConfig()
                applyPanelWindowFlags()
                applyBallWindowFlags()
                if (!isExpanded && ballView?.parent != null) {
                    snapBallToEdge(uiState.ballDockRight)
                }
            }
            ACTION_MEDIA_PROJECTION_GRANTED -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (code == Activity.RESULT_OK && data != null) {
                    startAsForegroundService(includeMediaProjection = true)
                    runCatching {
                        screenCapture.setProjectionResult(code, data)
                    }.onSuccess {
                        if (pendingCaptureAfterGrant) {
                            pendingCaptureAfterGrant = false
                            performScreenCaptureAndOpenCrop()
                        }
                    }.onFailure { err ->
                        pendingCaptureAfterGrant = false
                        uiState = uiState.copy(warningText = "初始化屏幕捕获失败: ${err.message}")
                    }
                }
            }
            ACTION_MEDIA_PROJECTION_DENIED -> {
                pendingCaptureAfterGrant = false
                if (!isExpanded) expandPanel(autoCaptureDom = false)
                uiState = uiState.copy(warningText = "未授予屏幕捕获权限，仍可使用「读DOM」或「剪贴板」。")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        idleRetractJob?.cancel()
        activeChatJob?.cancel()
        scope.cancel()
        runCatching { unregisterReceiver(domReceiver) }
        screenCapture.release()
        dismissCropOverlay()
        removeWindowSafely(ballView, ballOwner)
        removeWindowSafely(panelView, panelOwner)
        super.onDestroy()
    }

    private fun startAsForegroundService(includeMediaProjection: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI DOM 悬浮助手", NotificationManager.IMPORTANCE_LOW)
        )

        val openMainIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AI 悬浮助手运行中")
            .setContentText("支持 WebView DOM 读取与屏幕框选识别")
            .setContentIntent(openMainIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (includeMediaProjection || mediaProjectionEnabledInFgs) {
                mediaProjectionEnabledInFgs = true
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun syncStateFromConfig() {
        val cfg = configStore.load()
        val active = cfg.activeProfile()
        val prefs = cfg.overlayPrefs
        uiState = uiState.copy(
            profiles = cfg.profiles,
            activeProfileId = active.id,
            activeProfileName = active.name,
            activeModel = active.model,
            panelOpacity = prefs.panelOpacity,
            isTallMode = prefs.panelExpandedTall,
            ballDockRight = prefs.ballDockRight,
            collapsedBallStyle = prefs.collapsedBallStyle
        )
    }

    private fun initFloatingBall() {
        val owner = OverlayLifecycleOwner()
        val dm = resources.displayMetrics
        val prefs = configStore.load().overlayPrefs

        val view = ComposeView(this).apply {
            owner.attachToView(this)
            setContent {
                FloatingBallComposable(state = uiState)
            }
        }

        val approxBallW = defaultBallWidthPx(prefs.collapsedBallStyle)
        ballParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            buildBallWindowFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.ballDockRight) (dm.widthPixels - approxBallW).coerceAtLeast(0) else 0
            y = (dm.heightPixels * prefs.ballYFraction).toInt().coerceIn(dpToPx(64), dm.heightPixels - dpToPx(140))
        }

        view.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            val newW = right - left
            val oldW = oldRight - oldLeft
            if (newW > 0 && newW != oldW && !isDraggingBall && !isExpanded && v.parent != null) {
                v.post { snapBallToEdge(uiState.ballDockRight, newW) }
            }
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var moved = false
        var longPressed = false
        val longPressRunnable = Runnable {
            if (!moved) {
                longPressed = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onBallLongPressed()
            }
        }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingBall = false
                    uiState = uiState.copy(ballIdleRetracted = false)
                    idleRetractJob?.cancel()
                    startX = ballParams.x
                    startY = ballParams.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    longPressed = false
                    mainHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        isDraggingBall = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    ballParams.x = startX + dx
                    ballParams.y = (startY + dy).coerceIn(dpToPx(36), dm.heightPixels - dpToPx(96))
                    runCatching { windowManager.updateViewLayout(v, ballParams) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingBall = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressed && event.actionMasked == MotionEvent.ACTION_UP) {
                        expandPanel(autoCaptureDom = configStore.load().overlayPrefs.autoCaptureOnExpand)
                    } else if (moved) {
                        val midX = ballParams.x + v.width / 2
                        val dockRight = midX >= dm.widthPixels / 2
                        val frac = (ballParams.y.toFloat() / dm.heightPixels.toFloat()).coerceIn(0.08f, 0.85f)
                        configStore.updateOverlayPrefs {
                            it.copy(ballDockRight = dockRight, ballYFraction = frac)
                        }
                        uiState = uiState.copy(ballDockRight = dockRight)
                        snapBallToEdge(dockRight, v.width)
                        scheduleIdleRetract()
                    } else {
                        scheduleIdleRetract()
                    }
                    true
                }
                else -> false
            }
        }

        ballView = view
        ballOwner = owner
    }

    private fun initFloatingPanel() {
        val owner = OverlayLifecycleOwner()
        val dm = resources.displayMetrics
        val panelW = (dm.widthPixels * 0.93f).toInt().coerceAtMost(dpToPx(410))
        val panelH = computePanelHeightPx(uiState.isTallMode)

        val view = ComposeView(this).apply {
            owner.attachToView(this)
            setContent {
                CompositionLocalProvider(LocalUriHandler provides overlayUriHandler) {
                    FloatingPanelComposable(
                        state = uiState,
                        promptFocusRequester = promptFocusRequester,
                        contextFocusRequester = contextFocusRequester,
                        onDragDelta = { dx, dy ->
                            panelParams.x = (panelParams.x + dx).coerceIn(-dm.widthPixels / 3, dm.widthPixels - dpToPx(80))
                            panelParams.y = (panelParams.y + dy).coerceIn(0, dm.heightPixels - dpToPx(120))
                            panelView?.let { pv -> runCatching { windowManager.updateViewLayout(pv, panelParams) } }
                        },
                        onSelectProfile = { profileId ->
                            val cfg = configStore.load()
                            if (cfg.profiles.any { it.id == profileId }) {
                                configStore.save(cfg.copy(activeProfileId = profileId))
                                syncStateFromConfig()
                            }
                        },
                        onCaptureDom = { captureForegroundDom(autoAskAfter = false) },
                        onCaptureScreen = { triggerScreenCaptureFlow() },
                        onPasteClipboard = { pasteFromClipboard() },
                        onToggleTall = { togglePanelHeight() },
                        onToggleOpacityBar = { uiState = uiState.copy(showOpacityBar = !uiState.showOpacityBar) },
                        onSetOpacity = { op ->
                            configStore.updateOverlayPrefs { it.copy(panelOpacity = op) }
                            uiState = uiState.copy(panelOpacity = op)
                        },
                        onCollapse = { collapseToConfiguredEntry() },
                        onCloseService = { stopSelf() },
                        onToggleContextExpanded = { uiState = uiState.copy(contextExpanded = !uiState.contextExpanded) },
                        onContextTextChange = { uiState = uiState.copy(contextText = it) },
                        onRemoveAttachedImage = { clearAttachedImage() },
                        onQuickAction = { instruction -> sendQuickAction(instruction) },
                        onDraftChange = { uiState = uiState.copy(draftPrompt = it) },
                        onSendOrStop = {
                            if (uiState.isGenerating) {
                                stopGeneration()
                            } else {
                                sendManualPrompt()
                            }
                        },
                        onClearChat = { clearAllChat() },
                        onCopyMessage = { text -> copyToClipboard(text) },
                        onRegenerateLast = { regenerateLastTurn() },
                        onRequestImeFocus = { targetRequester -> enablePanelImeFocus(targetRequester) }
                    )
                }
            }
        }

        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && isPanelFocusable) {
                disablePanelImeFocus(hideIme = true)
            }
            false
        }

        panelParams = WindowManager.LayoutParams(
            panelW,
            panelH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            buildPanelWindowFlags(focusable = false),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((dm.widthPixels - panelW) / 2).coerceAtLeast(0)
            y = dpToPx(56)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        panelView = view
        panelOwner = owner
    }

    private fun computePanelHeightPx(tall: Boolean): Int {
        val h = resources.displayMetrics.heightPixels
        val ratio = if (tall) 0.74f else 0.50f
        return (h * ratio).toInt().coerceAtLeast(dpToPx(340))
    }

    private fun togglePanelHeight() {
        val nextTall = !uiState.isTallMode
        configStore.updateOverlayPrefs { it.copy(panelExpandedTall = nextTall) }
        uiState = uiState.copy(isTallMode = nextTall)
        panelParams.height = computePanelHeightPx(nextTall)
        panelView?.let { if (it.parent != null) runCatching { windowManager.updateViewLayout(it, panelParams) } }
    }

    private fun buildBallWindowFlags(): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (configStore.load().overlayPrefs.secureOverlay) {
            flags = flags or WindowManager.LayoutParams.FLAG_SECURE
        }
        return flags
    }

    private fun applyBallWindowFlags() {
        ballParams.flags = buildBallWindowFlags()
        ballView?.let { if (it.parent != null) runCatching { windowManager.updateViewLayout(it, ballParams) } }
    }

    private fun defaultBallWidthPx(style: CollapsedBallStyle): Int =
        dpToPx(if (style == CollapsedBallStyle.MINI_DOT) 34 else 54)

    private fun snapBallToEdge(dockRight: Boolean, measuredWidth: Int? = null) {
        val dm = resources.displayMetrics
        val width = when {
            measuredWidth != null && measuredWidth > 0 -> measuredWidth
            (ballView?.width ?: 0) > 0 -> ballView!!.width
            else -> defaultBallWidthPx(uiState.collapsedBallStyle)
        }
        ballParams.x = if (dockRight) (dm.widthPixels - width).coerceAtLeast(0) else 0
        ballView?.let { bv ->
            if (bv.parent != null) {
                runCatching { windowManager.updateViewLayout(bv, ballParams) }
            }
        }
    }

    private fun buildPanelWindowFlags(focusable: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (configStore.load().overlayPrefs.secureOverlay) {
            flags = flags or WindowManager.LayoutParams.FLAG_SECURE
        }
        return flags
    }

    private fun applyPanelWindowFlags() {
        panelParams.flags = buildPanelWindowFlags(isPanelFocusable)
        panelView?.let { if (it.parent != null) runCatching { windowManager.updateViewLayout(it, panelParams) } }
    }

    private fun enablePanelImeFocus(requester: FocusRequester) {
        if (!isPanelFocusable) {
            isPanelFocusable = true
            applyPanelWindowFlags()
        }
        val pv = panelView ?: return
        pv.post {
            runCatching { requester.requestFocus() }
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(pv, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun disablePanelImeFocus(hideIme: Boolean) {
        if (isPanelFocusable) {
            isPanelFocusable = false
            applyPanelWindowFlags()
        }
        if (hideIme) {
            panelView?.let { pv ->
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(pv.windowToken, 0)
            }
        }
    }

    private fun showBallOnly() {
        idleRetractJob?.cancel()
        detachViewOnly(panelView, panelOwner)
        val latestPrefs = configStore.load().overlayPrefs
        uiState = uiState.copy(
            collapsedBallStyle = latestPrefs.collapsedBallStyle,
            ballDockRight = latestPrefs.ballDockRight,
            ballIdleRetracted = false
        )
        ballParams.flags = buildBallWindowFlags()
        val expectedWidth = defaultBallWidthPx(latestPrefs.collapsedBallStyle)
        ballParams.x = if (latestPrefs.ballDockRight) {
            (resources.displayMetrics.widthPixels - expectedWidth).coerceAtLeast(0)
        } else {
            0
        }
        if (ballView?.parent == null) {
            ballView?.let {
                ballOwner?.onShown()
                windowManager.addView(it, ballParams)
                it.post { snapBallToEdge(latestPrefs.ballDockRight) }
            }
        } else {
            snapBallToEdge(latestPrefs.ballDockRight)
        }
        isExpanded = false
        scheduleIdleRetract()
    }

    private fun expandPanel(autoCaptureDom: Boolean) {
        idleRetractJob?.cancel()
        detachViewOnly(ballView, ballOwner)
        isPanelFocusable = false
        panelParams.flags = buildPanelWindowFlags(focusable = false)
        if (panelView?.parent == null) {
            panelView?.let {
                panelOwner?.onShown()
                windowManager.addView(it, panelParams)
            }
        }
        isExpanded = true
        uiState = uiState.copy(ballHasUnreadReply = false, ballIdleRetracted = false)
        if (autoCaptureDom) {
            captureForegroundDom(autoAskAfter = false)
        }
    }

    private fun collapseToConfiguredEntry() {
        disablePanelImeFocus(hideIme = true)
        showBallOnly()
    }

    private fun scheduleIdleRetract() {
        idleRetractJob?.cancel()
        if (!configStore.load().overlayPrefs.idleEdgeRetract || isExpanded) return
        idleRetractJob = scope.launch {
            delay(3_000L)
            if (!isExpanded && !uiState.isGenerating) {
                uiState = uiState.copy(ballIdleRetracted = true)
            }
        }
    }

    private fun onBallLongPressed() {
        val prefs = configStore.load().overlayPrefs
        if (prefs.longPressAutoAsk) {
            expandPanel(autoCaptureDom = false)
            captureForegroundDom(autoAskAfter = true)
        } else {
            triggerScreenCaptureFlow()
        }
    }

    private fun captureForegroundDom(autoAskAfter: Boolean) {
        disablePanelImeFocus(hideIme = true)
        uiState = uiState.copy(domStatus = "抓取 DOM 中...", warningText = "")
        val requestId = UUID.randomUUID().toString()

        scope.launch {
            val intent = Intent(DomIpcContract.ACTION_CAPTURE_DOM).apply {
                putExtra(DomIpcContract.EXTRA_REQUEST_ID, requestId)
            }
            sendBroadcast(intent)

            val event = withTimeoutOrNull(2_800L) {
                DomSnapshotBus.events.filter { it.requestId == requestId }.first()
            }

            if (event == null) {
                uiState = uiState.copy(
                    domStatus = "未收到网页",
                    warningText = "未检测到网页，请改用截屏框选"
                )
                return@launch
            }

            val snap = event.snapshot
            if (snap.hasError()) {
                uiState = uiState.copy(
                    domStatus = "未检测到",
                    contextMeta = "来源: ${snap.sourcePackage.ifBlank { "未知" }}",
                    warningText = snap.error.orEmpty()
                )
                return@launch
            }

            val modeLabel = if (snap.isSelectionMode()) "选区" else "正文"
            val titlePart = snap.title.ifBlank { snap.url }.ifBlank { "无标题" }
            runCatching { snap.effectiveContextText() }
                .onSuccess { text ->
                    uiState = uiState.copy(
                        domStatus = "已抓取 · $modeLabel",
                        contextMeta = "[$modeLabel] ${snap.sourcePackage} · $titlePart",
                        contextText = text,
                        warningText = snap.warningMessage().orEmpty()
                    )
                    if (autoAskAfter) {
                        val prompt = configStore.load().overlayPrefs.quickAskPrompt
                        sendQuickAction(prompt)
                    }
                }
                .onFailure { err ->
                    uiState = uiState.copy(
                        domStatus = "文本为空",
                        warningText = err.message ?: "页面文本为空，建议使用「截屏框选」"
                    )
                }
        }
    }

    private fun triggerScreenCaptureFlow() {
        disablePanelImeFocus(hideIme = true)
        if (!screenCapture.hasProjection()) {
            pendingCaptureAfterGrant = true
            detachViewOnly(panelView, panelOwner)
            detachViewOnly(ballView, ballOwner)
            startActivity(
                Intent(this, ScreenCapturePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        performScreenCaptureAndOpenCrop()
    }

    private fun performScreenCaptureAndOpenCrop() {
        detachViewOnly(panelView, panelOwner)
        detachViewOnly(ballView, ballOwner)

        scope.launch {
            // Brief settle delay so our own overlay views disappear completely from WindowManager before capture.
            delay(180L)
            val dm = resources.displayMetrics
            runCatching {
                screenCapture.captureSingleFrame(dm.widthPixels, dm.heightPixels, dm.densityDpi)
            }.onSuccess { bmp ->
                showCropOverlay(bmp)
            }.onFailure { err ->
                if (err is SecurityException) {
                    screenCapture.release()
                }
                expandPanel(autoCaptureDom = false)
                uiState = uiState.copy(warningText = "截屏失败：${err.message}，请重试或重新授权。")
            }
        }
    }

    private fun showCropOverlay(screenshot: Bitmap) {
        dismissCropOverlay()
        activeScreenshot = screenshot
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(this).apply {
            owner.attachToView(this)
            setContent {
                CropOverlayComposable(
                    screenshot = screenshot,
                    onConfirmCrop = { rect -> finishCrop(screenshot, rect) },
                    onSendFullScreen = {
                        finishCrop(screenshot, NormalizedCropRect(0f, 0f, 1f, 1f))
                    },
                    onCancel = {
                        dismissCropOverlay()
                        expandPanel(autoCaptureDom = false)
                    }
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        cropView = view
        cropOwner = owner
        owner.onShown()
        windowManager.addView(view, params)
    }

    private fun finishCrop(source: Bitmap, rect: NormalizedCropRect) {
        scope.launch(Dispatchers.Default) {
            val (previewBmp, base64Jpeg) = ImageCropUtils.cropAndEncodeJpeg(source, rect)
            mainHandler.post {
                dismissCropOverlay()
                val oldImg = uiState.attachedImage
                if (oldImg != null && oldImg !== previewBmp && !oldImg.isRecycled) {
                    runCatching { oldImg.recycle() }
                }
                activeAttachedImageBase64 = base64Jpeg
                uiState = uiState.copy(
                    attachedImage = previewBmp,
                    domStatus = "已截取图像",
                    contextMeta = "[截屏框选] ${previewBmp.width}×${previewBmp.height}",
                    warningText = ""
                )
                expandPanel(autoCaptureDom = false)
            }
        }
    }

    private fun dismissCropOverlay() {
        removeWindowSafely(cropView, cropOwner)
        cropView = null
        cropOwner = null
        val old = activeScreenshot
        activeScreenshot = null
        if (old != null && !old.isRecycled) {
            runCatching { old.recycle() }
        }
    }

    private fun clearAttachedImage() {
        val old = uiState.attachedImage
        activeAttachedImageBase64 = null
        uiState = uiState.copy(attachedImage = null)
        if (old != null && !old.isRecycled && uiState.messages.none { it.imagePreview === old }) {
            runCatching { old.recycle() }
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            uiState = uiState.copy(warningText = "剪贴板为空，无法导入文本")
            return
        }
        uiState = uiState.copy(
            contextText = text,
            contextMeta = "[剪贴板] ${text.length} 字符",
            domStatus = "已粘贴",
            warningText = ""
        )
    }

    private fun sendQuickAction(instruction: String) {
        val ctxText = uiState.contextText.trim()
        val imgB64 = activeAttachedImageBase64
        val imgPreview = uiState.attachedImage
        if (ctxText.isEmpty() && imgB64.isNullOrBlank()) {
            uiState = uiState.copy(warningText = "请先点击「读DOM」「截屏框选」或「剪贴板」获取内容")
            return
        }

        val finalPrompt = when {
            instruction.isNotBlank() && ctxText.isNotBlank() -> "$instruction\n\n[页面上下文]\n$ctxText"
            instruction.isNotBlank() -> instruction
            else -> ctxText
        }
        launchChatTurn(finalPrompt, imgB64, imgPreview)
    }

    private fun sendManualPrompt() {
        disablePanelImeFocus(hideIme = true)
        val input = uiState.draftPrompt.trim()
        val ctxText = uiState.contextText.trim()
        val imgB64 = activeAttachedImageBase64
        val imgPreview = uiState.attachedImage

        val finalPrompt = when {
            input.isNotEmpty() && ctxText.isNotEmpty() && conversationHistory.isEmpty() ->
                "$input\n\n[页面上下文]\n$ctxText"
            input.isNotEmpty() -> input
            ctxText.isNotEmpty() && conversationHistory.isEmpty() -> ctxText
            !imgB64.isNullOrBlank() -> configStore.load().overlayPrefs.quickAskPrompt
            else -> {
                uiState = uiState.copy(warningText = "请输入问题，或先读取网页 DOM / 截屏框选")
                return
            }
        }
        uiState = uiState.copy(draftPrompt = "")
        launchChatTurn(finalPrompt, imgB64, imgPreview)
    }

    private fun regenerateLastTurn() {
        if (uiState.isGenerating) return
        val lastUserIdx = conversationHistory.indexOfLast { it.role == "user" }
        if (lastUserIdx < 0) return
        val lastUserTurn = conversationHistory[lastUserIdx]
        while (conversationHistory.size > lastUserIdx) {
            conversationHistory.removeAt(conversationHistory.lastIndex)
        }
        val uiLastUserIdx = uiState.messages.indexOfLast { it.role == "user" }
        val imgPrev = if (uiLastUserIdx >= 0) uiState.messages[uiLastUserIdx].imagePreview else null
        if (uiLastUserIdx >= 0) {
            uiState = uiState.copy(messages = uiState.messages.take(uiLastUserIdx))
        }
        launchChatTurn(lastUserTurn.content, lastUserTurn.imageBase64Jpeg, imgPrev)
    }

    private fun stopGeneration() {
        activeChatJob?.cancel()
        activeChatJob = null
        val updated = uiState.messages.mapIndexed { i, m ->
            if (i == uiState.messages.lastIndex && m.isStreaming) m.copy(isStreaming = false) else m
        }
        uiState = uiState.copy(messages = updated, isGenerating = false)
    }

    private fun clearAllChat() {
        activeChatJob?.cancel()
        activeChatJob = null
        conversationHistory.clear()
        clearAttachedImage()
        uiState = uiState.copy(
            messages = emptyList(),
            isGenerating = false,
            contextText = "",
            draftPrompt = "",
            domStatus = "等待采集",
            contextMeta = "点「读DOM」或「截屏框选」提取题目",
            warningText = ""
        )
    }

    private fun launchChatTurn(
        userPrompt: String,
        imageBase64Jpeg: String?,
        imagePreview: Bitmap?
    ) {
        val cfg = configStore.load()
        val profile = cfg.activeProfile()
        runCatching { profile.activeBearerToken() }.onFailure { err ->
            uiState = uiState.copy(warningText = err.message ?: "请先在主界面配置模型密钥或 OAuth 登录")
            return
        }

        // Consume pending cropped attachment after attaching it to this user turn.
        activeAttachedImageBase64 = null
        val userUiMsg = ChatUiMessage(
            id = nextMsgId++,
            role = "user",
            content = userPrompt,
            imagePreview = imagePreview,
            isStreaming = false
        )
        val assistantMsgId = nextMsgId++
        val assistantPlaceholder = ChatUiMessage(
            id = assistantMsgId,
            role = "assistant",
            content = "",
            reasoning = "",
            isStreaming = true
        )

        conversationHistory.add(ChatTurn(role = "user", content = userPrompt, imageBase64Jpeg = imageBase64Jpeg))
        uiState = uiState.copy(
            attachedImage = null,
            warningText = "",
            messages = uiState.messages + userUiMsg + assistantPlaceholder,
            isGenerating = true
        )

        val snapshotHistory = conversationHistory.toList()
        val textSb = StringBuilder()
        val reasoningSb = StringBuilder()

        activeChatJob?.cancel()
        activeChatJob = scope.launch {
            runCatching {
                openAiClient.streamChat(
                    profile = profile,
                    systemPrompt = cfg.systemPrompt,
                    history = snapshotHistory,
                    onProfileUpdated = { updatedProfile ->
                        configStore.updateProfile(updatedProfile.id) { updatedProfile }
                        mainHandler.post { syncStateFromConfig() }
                    }
                ).collect { ev ->
                    when (ev) {
                        is StreamEvent.TextDelta -> textSb.append(ev.text)
                        is StreamEvent.ReasoningDelta -> reasoningSb.append(ev.text)
                        StreamEvent.Done -> Unit
                    }
                    updateAssistantMessage(
                        msgId = assistantMsgId,
                        content = textSb.toString(),
                        reasoning = reasoningSb.toString(),
                        streaming = true
                    )
                }
            }.onSuccess {
                val finalContent = textSb.toString()
                val finalReasoning = reasoningSb.toString()
                if (finalContent.isEmpty() && finalReasoning.isEmpty()) {
                    updateAssistantMessage(assistantMsgId, "模型返回空响应", "", streaming = false)
                } else {
                    if (finalContent.isNotEmpty()) {
                        conversationHistory.add(ChatTurn("assistant", finalContent))
                    }
                    updateAssistantMessage(assistantMsgId, finalContent, finalReasoning, streaming = false)
                }
                uiState = uiState.copy(
                    isGenerating = false,
                    ballHasUnreadReply = !isExpanded
                )
                scheduleIdleRetract()
            }.onFailure { err ->
                val msg = "请求失败: ${err.message ?: err.javaClass.simpleName}"
                val combined = if (textSb.isEmpty()) msg else "${textSb}\n\n[$msg]"
                updateAssistantMessage(assistantMsgId, combined, reasoningSb.toString(), streaming = false)
                uiState = uiState.copy(
                    isGenerating = false,
                    ballHasUnreadReply = !isExpanded
                )
                scheduleIdleRetract()
            }
        }
    }

    private fun updateAssistantMessage(
        msgId: Long,
        content: String,
        reasoning: String,
        streaming: Boolean
    ) {
        val next = uiState.messages.map { m ->
            if (m.id == msgId) m.copy(content = content, reasoning = reasoning, isStreaming = streaming) else m
        }
        uiState = uiState.copy(messages = next)
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AI Reply", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun detachViewOnly(view: View?, owner: OverlayLifecycleOwner?) {
        if (view != null && view.parent != null) {
            runCatching { windowManager.removeView(view) }
        }
        owner?.onHidden()
    }

    private fun removeWindowSafely(view: View?, owner: OverlayLifecycleOwner?) {
        detachViewOnly(view, owner)
        owner?.destroy()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        var isRunning: Boolean = false

        private const val CHANNEL_ID = "overlay_ai_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_STOP = "com.overlayai.assistant.ACTION_STOP_OVERLAY"
        const val ACTION_REFRESH_CONFIG = "com.overlayai.assistant.ACTION_REFRESH_CONFIG"
        const val ACTION_MEDIA_PROJECTION_GRANTED = "com.overlayai.assistant.ACTION_MP_GRANTED"
        const val ACTION_MEDIA_PROJECTION_DENIED = "com.overlayai.assistant.ACTION_MP_DENIED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}
