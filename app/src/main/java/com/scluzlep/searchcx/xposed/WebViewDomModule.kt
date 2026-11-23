package com.scluzlep.searchcx.xposed

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import com.scluzlep.searchcx.data.DomSnapshot
import com.scluzlep.searchcx.ipc.DomChunkAssembler
import com.scluzlep.searchcx.ipc.DomIpcContract
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Modern Xposed / libxposed API 102 module for read-only WebView DOM & selection extraction.
 * Strictly avoids legacy `de.robv.android.xposed` APIs and never mutates target web pages.
 */
class WebViewDomModule : XposedModule() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackedWebViews = WeakHashMap<Any, Long>()
    private val activeHandles = linkedMapOf<String, XposedInterface.HookHandle>()
    private val hookedClassLoaders = WeakHashMap<ClassLoader, Boolean>()
    private val ioExecutorLock = Any()

    @Volatile
    private var ioExecutor: ExecutorService? = null

    @Volatile
    private var isSystemServerProcess: Boolean = false

    @Volatile
    private var currentProcessName: String = ""

    @Volatile
    private var currentPackageName: String = ""

    @Volatile
    private var hostAppContext: Context? = null

    @Volatile
    private var captureReceiver: BroadcastReceiver? = null

    @Volatile
    private var resumedActivityRef: WeakReference<Activity>? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        isSystemServerProcess = param.isSystemServer
        currentProcessName = param.processName
        if (param.isSystemServer) {
            return
        }
        log(Log.INFO, TAG, "Loaded API 102 module in process=${param.processName}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (isSystemServerProcess) {
            return
        }
        if (param.isFirstPackage) {
            currentPackageName = param.packageName
        }

        if (param.packageName == DomIpcContract.ASSISTANT_PACKAGE && param.isFirstPackage) {
            installSelfStatusHook(param.classLoader, oldById = null)
            return
        }

        setupTargetProcessHooks(
            packageName = param.packageName,
            classLoader = param.classLoader,
            isFirstPackage = param.isFirstPackage,
            oldById = null
        )
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        unregisterCaptureReceiver()
        mainHandler.removeCallbacksAndMessages(null)
        shutdownIoExecutor()
        resumedActivityRef = null
        hostAppContext = null
        synchronized(trackedWebViews) {
            trackedWebViews.clear()
        }
        synchronized(hookedClassLoaders) {
            hookedClassLoaders.clear()
        }
        param.setSavedInstanceState(currentPackageName.ifBlank { null })
        log(Log.INFO, TAG, "Hot reloading initiated for pkg=$currentPackageName")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        currentProcessName = param.processName
        if (param.isSystemServer) {
            return
        }

        val app = currentApplicationByReflection()
        val pkgName = (param.savedInstanceState as? String)?.takeIf { it.isNotBlank() }
            ?: app?.packageName
            ?: param.processName.substringBefore(':')
        currentPackageName = pkgName

        val appClassLoader = app?.classLoader ?: javaClass.classLoader ?: ClassLoader.getSystemClassLoader()
        val oldById = linkedMapOf<String, XposedInterface.HookHandle>()
        param.oldHookHandles.forEachIndexed { index, handle ->
            val key = handle.id ?: "legacy#$index:${handle.executable.toGenericString()}"
            oldById[key] = handle
        }

        if (pkgName == DomIpcContract.ASSISTANT_PACKAGE) {
            installSelfStatusHook(appClassLoader, oldById)
            unhookRemaining(oldById)
            return
        }

        setupTargetProcessHooks(
            packageName = pkgName,
            classLoader = appClassLoader,
            isFirstPackage = true,
            oldById = oldById
        )
        unhookRemaining(oldById)
        app?.applicationContext?.let { ensureReceiverRegistered(it) }
        log(Log.INFO, TAG, "Hot reloaded completed for pkg=$pkgName process=$currentProcessName")
    }

    private fun installSelfStatusHook(
        classLoader: ClassLoader,
        oldById: MutableMap<String, XposedInterface.HookHandle>?
    ) {
        runCatching {
            val clazz = Class.forName("com.scluzlep.searchcx.xposed.ModuleStatus", false, classLoader)
            val method = clazz.getDeclaredMethod("isSelfHooked")
            installOrReplaceHook(
                id = "self:ModuleStatus#isSelfHooked",
                executable = method,
                oldById = oldById
            ) { true }
        }.onFailure {
            log(Log.WARN, TAG, "Failed to install self status hook", it)
        }
    }

    private fun setupTargetProcessHooks(
        packageName: String,
        classLoader: ClassLoader,
        isFirstPackage: Boolean,
        oldById: MutableMap<String, XposedInterface.HookHandle>?
    ) {
        if (packageName == DomIpcContract.ASSISTANT_PACKAGE) {
            return
        }

        if (isFirstPackage) {
            currentApplicationByReflection()?.applicationContext?.let { ctx ->
                ensureReceiverRegistered(ctx)
            }
            installLifecycleHooks(oldById)
            installStandardWebViewHooks(oldById)
        }

        val shouldHookX5 = synchronized(hookedClassLoaders) {
            if (hookedClassLoaders.containsKey(classLoader)) {
                false
            } else {
                hookedClassLoaders[classLoader] = true
                true
            }
        }
        if (shouldHookX5) {
            installX5WebViewHooks(classLoader, oldById)
        }
    }

    private fun installLifecycleHooks(oldById: MutableMap<String, XposedInterface.HookHandle>?) {
        runCatching {
            val onCreateMethod = Application::class.java.getDeclaredMethod("onCreate")
            installOrReplaceHook(
                id = "app:Application#onCreate",
                executable = onCreateMethod,
                oldById = oldById
            ) { chain ->
                val result = chain.proceed()
                val app = chain.thisObject as? Application
                app?.applicationContext?.let { ensureReceiverRegistered(it) }
                result
            }
        }.onFailure {
            log(Log.WARN, TAG, "Hook Application.onCreate failed", it)
        }

        runCatching {
            val onResumeMethod = Activity::class.java.getDeclaredMethod("onResume")
            installOrReplaceHook(
                id = "app:Activity#onResume",
                executable = onResumeMethod,
                oldById = oldById
            ) { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null) {
                    resumedActivityRef = WeakReference(activity)
                    ensureReceiverRegistered(activity.applicationContext)
                    trackWebViewsInTree(activity.window?.decorView)
                }
                result
            }
        }.onFailure {
            log(Log.WARN, TAG, "Hook Activity.onResume failed", it)
        }
    }

    private fun installStandardWebViewHooks(oldById: MutableMap<String, XposedInterface.HookHandle>?) {
        val webViewClass = WebView::class.java

        val trackAfterHooker = XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            (chain.thisObject as? WebView)?.let { trackWebView(it) }
            result
        }

        hookMethodsByName(
            clazz = webViewClass,
            methodName = "onAttachedToWindow",
            idPrefix = "wv:onAttachedToWindow",
            oldById = oldById,
            hooker = trackAfterHooker
        )
        hookMethodsByName(
            clazz = webViewClass,
            methodName = "onWindowFocusChanged",
            idPrefix = "wv:onWindowFocusChanged",
            oldById = oldById,
            hooker = trackAfterHooker
        )
        hookMethodsByName(
            clazz = webViewClass,
            methodName = "loadUrl",
            idPrefix = "wv:loadUrl",
            oldById = oldById,
            hooker = trackAfterHooker
        )
        hookMethodsByName(
            clazz = webViewClass,
            methodName = "loadData",
            idPrefix = "wv:loadData",
            oldById = oldById,
            hooker = trackAfterHooker
        )
        hookMethodsByName(
            clazz = webViewClass,
            methodName = "loadDataWithBaseURL",
            idPrefix = "wv:loadDataWithBaseURL",
            oldById = oldById,
            hooker = trackAfterHooker
        )
        hookMethodsByName(
            clazz = webViewClass,
            methodName = "setWebViewClient",
            idPrefix = "wv:setWebViewClient",
            oldById = oldById,
            hooker = trackAfterHooker
        )
    }

    private fun installX5WebViewHooks(
        classLoader: ClassLoader,
        oldById: MutableMap<String, XposedInterface.HookHandle>?
    ) {
        val x5Class = runCatching {
            Class.forName("com.tencent.smtt.sdk.WebView", false, classLoader)
        }.getOrNull() ?: return

        val trackAfterHooker = XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            chain.thisObject?.takeIf { x5Class.isInstance(it) }?.let { trackWebView(it) }
            result
        }

        val methods = listOf(
            "onAttachedToWindow",
            "onWindowFocusChanged",
            "loadUrl",
            "loadData",
            "loadDataWithBaseURL",
            "setWebViewClient"
        )
        for (name in methods) {
            hookMethodsByName(
                clazz = x5Class,
                methodName = name,
                idPrefix = "x5:$name",
                oldById = oldById,
                hooker = trackAfterHooker
            )
        }
        log(Log.INFO, TAG, "Installed TBS X5 WebView hooks in pkg=$currentPackageName")
    }

    private fun hookMethodsByName(
        clazz: Class<*>,
        methodName: String,
        idPrefix: String,
        oldById: MutableMap<String, XposedInterface.HookHandle>?,
        hooker: XposedInterface.Hooker
    ) {
        val matches = collectMethodsNamed(clazz, methodName)
        if (matches.isEmpty()) {
            return
        }
        for (method in matches) {
            val sig = method.parameterTypes.joinToString(",") { it.name }
            val hookId = "$idPrefix($sig)"
            installOrReplaceHook(hookId, method, oldById, hooker)
        }
    }

    private fun collectMethodsNamed(startClass: Class<*>, methodName: String): List<Method> {
        val found = mutableListOf<Method>()
        val seenSignatures = hashSetOf<String>()
        var cursor: Class<*>? = startClass
        while (cursor != null && cursor != Any::class.java) {
            for (method in cursor.declaredMethods) {
                if (method.name != methodName) continue
                val sig = method.parameterTypes.joinToString(",") { it.name }
                if (seenSignatures.add(sig)) {
                    found.add(method)
                }
            }
            // Stop once found on the most specific class if subclass overrides it; continue up only if none found yet.
            if (found.isNotEmpty() && cursor == startClass) {
                // Keep superclass overloads too (e.g. WebView.loadUrl overloads vs View.onAttachedToWindow)
            }
            cursor = cursor.superclass
        }
        return found
    }

    private fun installOrReplaceHook(
        id: String,
        executable: Executable,
        oldById: MutableMap<String, XposedInterface.HookHandle>?,
        hooker: XposedInterface.Hooker
    ) {
        if (activeHandles.containsKey(id)) {
            return
        }
        val oldHandle = oldById?.remove(id)
        val handle = if (oldHandle != null) {
            runCatching {
                oldHandle.replaceHook(hooker)
            }.getOrElse {
                runCatching { oldHandle.unhook() }
                hook(executable)
                    .setId(id)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker)
            }
        } else {
            hook(executable)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker)
        }
        activeHandles[id] = handle
    }

    private fun unhookRemaining(oldById: MutableMap<String, XposedInterface.HookHandle>) {
        for (handle in oldById.values) {
            runCatching { handle.unhook() }
        }
        oldById.clear()
    }

    private fun ensureReceiverRegistered(context: Context) {
        val appCtx = context.applicationContext ?: context
        if (appCtx.packageName == DomIpcContract.ASSISTANT_PACKAGE) {
            return
        }
        if (captureReceiver != null) {
            return
        }
        synchronized(this) {
            if (captureReceiver != null) {
                return
            }
            hostAppContext = appCtx
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != DomIpcContract.ACTION_CAPTURE_DOM) return
                    val requestId = intent.getStringExtra(DomIpcContract.EXTRA_REQUEST_ID) ?: return
                    handleCaptureRequest(appCtx, requestId)
                }
            }
            val filter = IntentFilter(DomIpcContract.ACTION_CAPTURE_DOM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appCtx.registerReceiver(receiver, filter)
            }
            captureReceiver = receiver
        }
    }

    private fun unregisterCaptureReceiver() {
        val receiver = captureReceiver ?: return
        val ctx = hostAppContext
        captureReceiver = null
        if (ctx != null) {
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }

    private fun trackWebView(webView: Any) {
        synchronized(trackedWebViews) {
            trackedWebViews[webView] = SystemClock.uptimeMillis()
        }
    }

    private fun trackWebViewsInTree(root: View?) {
        if (root == null) return
        if (isSupportedWebView(root)) {
            trackWebView(root)
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                trackWebViewsInTree(root.getChildAt(i))
            }
        }
    }

    private fun isSupportedWebView(candidate: Any): Boolean {
        if (candidate is WebView) return true
        var cursor: Class<*>? = candidate.javaClass
        while (cursor != null && cursor != Any::class.java) {
            if (cursor.name == "com.tencent.smtt.sdk.WebView") {
                return true
            }
            cursor = cursor.superclass
        }
        return false
    }

    private fun getOrCreateIoExecutor(): ExecutorService {
        ioExecutor?.takeIf { !it.isShutdown && !it.isTerminated }?.let { return it }
        synchronized(ioExecutorLock) {
            ioExecutor?.takeIf { !it.isShutdown && !it.isTerminated }?.let { return it }
            return Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "scx-dom-io").apply { isDaemon = true }
            }.also { ioExecutor = it }
        }
    }

    private fun shutdownIoExecutor() {
        synchronized(ioExecutorLock) {
            ioExecutor?.shutdownNow()
            ioExecutor = null
        }
    }

    private fun handleCaptureRequest(appCtx: Context, requestId: String) {
        mainHandler.post {
            val visibleOrForeground = isProcessVisibleOrForeground(appCtx)
            resumedActivityRef?.get()?.window?.decorView?.let { trackWebViewsInTree(it) }

            val bestWebView = pickBestWebView()
            if (bestWebView == null) {
                // Only main foreground process emits a missing-WebView snapshot so background/child processes stay quiet.
                if (visibleOrForeground && !currentProcessName.contains(':')) {
                    sendSnapshotBroadcast(
                        appCtx,
                        requestId,
                        DomSnapshot.errorSnapshot(
                            appCtx.packageName,
                            "未检测到活跃 WebView，请改用截屏框选"
                        )
                    )
                }
                return@post
            }

            if (!visibleOrForeground && !isViewVisibleOnScreen(bestWebView)) {
                return@post
            }

            extractFromWebView(appCtx, requestId, bestWebView)
        }
    }

    private fun pickBestWebView(): Any? {
        val entries: List<Pair<Any, Long>> = synchronized(trackedWebViews) {
            trackedWebViews.entries.map { it.key to it.value }
        }
        if (entries.isEmpty()) return null

        return entries.maxWithOrNull { a, b ->
            val visA = if (isViewVisibleOnScreen(a.first)) 1 else 0
            val visB = if (isViewVisibleOnScreen(b.first)) 1 else 0
            if (visA != visB) {
                visA.compareTo(visB)
            } else {
                val areaA = viewArea(a.first)
                val areaB = viewArea(b.first)
                if (areaA != areaB) areaA.compareTo(areaB) else a.second.compareTo(b.second)
            }
        }?.first?.takeIf { isViewVisibleOnScreen(it) || viewArea(it) > 0 }
    }

    private fun isViewVisibleOnScreen(target: Any): Boolean {
        val view = target as? View ?: return false
        return view.isAttachedToWindow && view.windowVisibility == View.VISIBLE && view.isShown
    }

    private fun viewArea(target: Any): Long {
        val view = target as? View ?: return 0L
        if (view.width <= 0 || view.height <= 0) return 0L
        return view.width.toLong() * view.height.toLong()
    }

    private fun isProcessVisibleOrForeground(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val myPid = android.os.Process.myPid()
        val processes = am.runningAppProcesses ?: return true
        val selfInfo = processes.firstOrNull { it.pid == myPid } ?: return true
        return selfInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun extractFromWebView(appCtx: Context, requestId: String, webView: Any) {
        val fallbackUrl = reflectStringGetter(webView, "getUrl")
        val fallbackTitle = reflectStringGetter(webView, "getTitle")
        val userAgent = resolveWebViewUserAgent(webView)
        val x5ClassLoader = if (webView is WebView) null else webView.javaClass.classLoader
        val completed = AtomicBoolean(false)

        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                sendSnapshotBroadcast(
                    appCtx,
                    requestId,
                    DomSnapshot.errorSnapshot(
                        appCtx.packageName,
                        "页面读取超时，请重试或改用截屏框选"
                    ).copy(url = fallbackUrl, title = fallbackTitle)
                )
            }
        }
        mainHandler.postDelayed(timeoutRunnable, EVALUATE_TIMEOUT_MS)

        val onResult: (String?) -> Unit = { rawResult ->
            mainHandler.removeCallbacks(timeoutRunnable)
            if (completed.compareAndSet(false, true)) {
                val decodedResult = decodeJsObject(rawResult)
                decodedResult.onFailure { err ->
                    val msg = if (err.message == EMPTY_JS_RESULT_MARKER) {
                        "WebView 返回空结果"
                    } else {
                        "解析 DOM JSON 失败：${err.message}"
                    }
                    sendSnapshotBroadcast(
                        appCtx,
                        requestId,
                        DomSnapshot.errorSnapshot(appCtx.packageName, msg)
                            .copy(url = fallbackUrl, title = fallbackTitle)
                    )
                }.onSuccess { decodedJson ->
                    val svgJobId = decodedJson.optString("svgJobId", "").trim()
                    if (svgJobId.isNotBlank()) {
                        pollSvgJob(
                            appCtx = appCtx,
                            requestId = requestId,
                            webView = webView,
                            decodedJson = decodedJson,
                            svgJobId = svgJobId,
                            userAgent = userAgent,
                            fallbackUrl = fallbackUrl,
                            fallbackTitle = fallbackTitle,
                            x5ClassLoader = x5ClassLoader,
                            attempt = 1
                        )
                    } else {
                        finalizeAndBroadcast(
                            appCtx = appCtx,
                            requestId = requestId,
                            decodedJson = decodedJson,
                            userAgent = userAgent,
                            fallbackUrl = fallbackUrl,
                            fallbackTitle = fallbackTitle,
                            x5ClassLoader = x5ClassLoader
                        )
                    }
                }
            }
        }

        evaluateAnyWebView(
            webView = webView,
            script = EXTRACT_DOM_JS,
            onResult = onResult,
            onError = { err ->
                mainHandler.removeCallbacks(timeoutRunnable)
                if (completed.compareAndSet(false, true)) {
                    val prefix = if (webView is WebView) "读取 WebView DOM 失败" else "读取 X5 WebView DOM 失败"
                    sendSnapshotBroadcast(
                        appCtx,
                        requestId,
                        DomSnapshot.errorSnapshot(appCtx.packageName, "$prefix：${err.message}")
                            .copy(url = fallbackUrl, title = fallbackTitle)
                    )
                }
            }
        )
    }

    private fun pollSvgJob(
        appCtx: Context,
        requestId: String,
        webView: Any,
        decodedJson: JSONObject,
        svgJobId: String,
        userAgent: String,
        fallbackUrl: String,
        fallbackTitle: String,
        x5ClassLoader: ClassLoader?,
        attempt: Int
    ) {
        val quotedId = JSONObject.quote(svgJobId)
        val forceCleanup = attempt >= SVG_MAX_POLL_ATTEMPTS
        val pollScript = """
            (function() {
              try {
                var jobs = window.__scxSvgJobs;
                var jid = $quotedId;
                if (!jobs || !jobs[jid]) return JSON.stringify({ done: true, results: {} });
                var j = jobs[jid];
                if (j.done || $forceCleanup) {
                  var res = j.results || {};
                  delete jobs[jid];
                  return JSON.stringify({ done: true, results: res });
                }
                return JSON.stringify({ done: false, results: j.results || {} });
              } catch (e) {
                return JSON.stringify({ done: true, results: {} });
              }
            })();
        """.trimIndent()

        mainHandler.postDelayed({
            evaluateAnyWebView(
                webView = webView,
                script = pollScript,
                onResult = { pollRaw ->
                    val pollObj = decodeJsObject(pollRaw).getOrNull()
                    val done = pollObj?.optBoolean("done", true) ?: true
                    val resultsObj = pollObj?.optJSONObject("results")
                    if (done || attempt >= SVG_MAX_POLL_ATTEMPTS) {
                        mergeSvgJobResults(decodedJson, resultsObj)
                        finalizeAndBroadcast(
                            appCtx = appCtx,
                            requestId = requestId,
                            decodedJson = decodedJson,
                            userAgent = userAgent,
                            fallbackUrl = fallbackUrl,
                            fallbackTitle = fallbackTitle,
                            x5ClassLoader = x5ClassLoader
                        )
                    } else {
                        pollSvgJob(
                            appCtx = appCtx,
                            requestId = requestId,
                            webView = webView,
                            decodedJson = decodedJson,
                            svgJobId = svgJobId,
                            userAgent = userAgent,
                            fallbackUrl = fallbackUrl,
                            fallbackTitle = fallbackTitle,
                            x5ClassLoader = x5ClassLoader,
                            attempt = attempt + 1
                        )
                    }
                },
                onError = {
                    finalizeAndBroadcast(
                        appCtx = appCtx,
                        requestId = requestId,
                        decodedJson = decodedJson,
                        userAgent = userAgent,
                        fallbackUrl = fallbackUrl,
                        fallbackTitle = fallbackTitle,
                        x5ClassLoader = x5ClassLoader
                    )
                }
            )
        }, SVG_POLL_INTERVAL_MS)
    }

    private fun mergeSvgJobResults(decodedJson: JSONObject, resultsObj: JSONObject?) {
        if (resultsObj == null) return
        val images = decodedJson.optJSONArray("images") ?: return
        var usedInlineChars = totalInlineChars(images)
        for (i in 0 until images.length()) {
            val imgObj = images.optJSONObject(i) ?: continue
            if (imgObj.optString("dataUrl", "").isNotBlank()) continue
            val idxKey = imgObj.optInt("index", -1).toString()
            val pngDataUrl = resultsObj.optString(idxKey, "").trim()
            if (!pngDataUrl.startsWith("data:image/")) continue
            if (pngDataUrl.length <= MAX_INLINE_PER_IMG_CHARS &&
                usedInlineChars + pngDataUrl.length <= MAX_TOTAL_INLINE_CHARS
            ) {
                imgObj.put("dataUrl", pngDataUrl)
                usedInlineChars += pngDataUrl.length
            }
        }
    }

    private fun finalizeAndBroadcast(
        appCtx: Context,
        requestId: String,
        decodedJson: JSONObject,
        userAgent: String,
        fallbackUrl: String,
        fallbackTitle: String,
        x5ClassLoader: ClassLoader?
    ) {
        if (!needsHttpPrefetch(decodedJson)) {
            val snapshot = buildSnapshotFromJsonObject(
                sourcePackage = appCtx.packageName,
                fallbackUrl = fallbackUrl,
                fallbackTitle = fallbackTitle,
                decoded = decodedJson
            )
            sendSnapshotBroadcast(appCtx, requestId, snapshot)
            return
        }

        getOrCreateIoExecutor().execute {
            runCatching {
                prefetchHttpImages(
                    decodedJson = decodedJson,
                    userAgent = userAgent,
                    fallbackUrl = fallbackUrl,
                    x5ClassLoader = x5ClassLoader
                )
            }.onFailure { err ->
                log(Log.WARN, TAG, "Host image prefetch failed; falling back to URLs", err)
            }
            val snapshot = runCatching {
                buildSnapshotFromJsonObject(
                    sourcePackage = appCtx.packageName,
                    fallbackUrl = fallbackUrl,
                    fallbackTitle = fallbackTitle,
                    decoded = decodedJson
                )
            }.getOrElse { err ->
                DomSnapshot.errorSnapshot(appCtx.packageName, "构建 DOM 快照失败：${err.message}")
                    .copy(url = fallbackUrl, title = fallbackTitle)
            }
            sendSnapshotBroadcast(appCtx, requestId, snapshot)
        }
    }

    private fun needsHttpPrefetch(decodedJson: JSONObject): Boolean {
        val images = decodedJson.optJSONArray("images") ?: return false
        for (i in 0 until images.length()) {
            val obj = images.optJSONObject(i) ?: continue
            if (obj.optString("dataUrl", "").isBlank() && isHttpUrl(obj.optString("url", "").trim())) {
                return true
            }
        }
        return false
    }

    private fun totalInlineChars(images: JSONArray): Int {
        var total = 0
        for (i in 0 until images.length()) {
            val obj = images.optJSONObject(i) ?: continue
            total += obj.optString("dataUrl", "").length
        }
        return total
    }

    private fun prefetchHttpImages(
        decodedJson: JSONObject,
        userAgent: String,
        fallbackUrl: String,
        x5ClassLoader: ClassLoader?
    ) {
        val images = decodedJson.optJSONArray("images") ?: return
        val pageUrl = decodedJson.optString("url").ifBlank { fallbackUrl }
        var totalInlineChars = totalInlineChars(images)
        var fetchedCount = 0

        for (i in 0 until images.length()) {
            if (Thread.currentThread().isInterrupted) break
            if (fetchedCount >= MAX_PREFETCH_IMAGES) break
            if (totalInlineChars >= MAX_TOTAL_INLINE_CHARS - 2048) break

            val obj = images.optJSONObject(i) ?: continue
            if (obj.optString("dataUrl", "").isNotBlank()) continue
            val imgUrl = obj.optString("url", "").trim()
            if (!isHttpUrl(imgUrl)) continue

            fetchedCount++
            val rawBytes = runCatching {
                downloadImageBytes(imgUrl, pageUrl, userAgent, x5ClassLoader)
            }.getOrNull() ?: continue
            val remainingBudget = MAX_TOTAL_INLINE_CHARS - totalInlineChars
            val dataUrl = runCatching {
                normalizeAndEncodeImage(rawBytes, remainingBudget)
            }.getOrNull() ?: continue

            obj.put("dataUrl", dataUrl)
            totalInlineChars += dataUrl.length
        }
    }

    private fun downloadImageBytes(
        imageUrl: String,
        pageUrl: String,
        userAgent: String,
        x5ClassLoader: ClassLoader?
    ): ByteArray? {
        var currentUrl = imageUrl
        var redirects = 0
        while (redirects <= MAX_PREFETCH_REDIRECTS) {
            if (Thread.currentThread().isInterrupted || !isHttpUrl(currentUrl)) return null
            val conn = (URL(currentUrl).openConnection() as? HttpURLConnection) ?: return null
            try {
                conn.connectTimeout = PREFETCH_TIMEOUT_MS
                conn.readTimeout = PREFETCH_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", userAgent.ifBlank { DEFAULT_USER_AGENT })
                conn.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/*,*/*;q=0.8")
                buildOriginReferer(pageUrl, currentUrl)?.let { conn.setRequestProperty("Referer", it) }
                getCookieSafely(currentUrl, x5ClassLoader)?.takeIf { it.isNotBlank() }?.let {
                    conn.setRequestProperty("Cookie", it)
                }

                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank() || redirects >= MAX_PREFETCH_REDIRECTS) {
                        return null
                    }
                    val nextUrl = runCatching { URI(currentUrl).resolve(location).toString() }.getOrNull()
                        ?: return null
                    currentUrl = nextUrl
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    return null
                }
                val contentLength = conn.contentLengthLong
                if (contentLength > MAX_PREFETCH_RAW_BYTES) {
                    return null
                }
                return conn.inputStream.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var total = 0
                    while (true) {
                        if (Thread.currentThread().isInterrupted) return@use null
                        val read = input.read(chunk)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PREFETCH_RAW_BYTES) return@use null
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun getCookieSafely(url: String, x5ClassLoader: ClassLoader?): String? {
        val systemCookie = runCatching {
            CookieManager.getInstance().getCookie(url)
        }.getOrNull()
        if (!systemCookie.isNullOrBlank()) {
            return systemCookie
        }
        if (x5ClassLoader != null) {
            val x5Cookie = runCatching {
                val cmClass = Class.forName("com.tencent.smtt.sdk.CookieManager", false, x5ClassLoader)
                val cm = cmClass.getMethod("getInstance").invoke(null)
                cmClass.getMethod("getCookie", String::class.java).invoke(cm, url) as? String
            }.getOrNull()
            if (!x5Cookie.isNullOrBlank()) {
                return x5Cookie
            }
        }
        return null
    }

    private fun buildOriginReferer(pageUrl: String, imgUrl: String): String? {
        return extractOrigin(pageUrl) ?: extractOrigin(imgUrl)
    }

    private fun extractOrigin(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (!isHttpUrl(trimmed)) return null
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return@runCatching null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val port = uri.port
            val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
            val portPart = if (port <= 0 || defaultPort) "" else ":$port"
            "$scheme://$host$portPart/"
        }.getOrNull()
    }

    private fun normalizeAndEncodeImage(rawBytes: ByteArray, remainingTotalBudget: Int): String? {
        if (rawBytes.isEmpty() || rawBytes.size > MAX_PREFETCH_RAW_BYTES) return null
        val maxAllowed = minOf(MAX_INLINE_PER_IMG_CHARS, remainingTotalBudget)
        if (maxAllowed < 64) return null

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        var sampleSize = 1
        val rawMaxSide = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
        while (rawMaxSide / (sampleSize * 2) >= PREFETCH_MAX_SIDE_PX) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return null
        var scaled: Bitmap? = null
        var composited: Bitmap? = null
        return try {
            val srcW = decoded.width
            val srcH = decoded.height
            if (srcW <= 0 || srcH <= 0) return null

            val minSide = minOf(srcW, srcH)
            val maxSide = maxOf(srcW, srcH)
            var scale = 1.0
            if (minSide < PREFETCH_MIN_FORMULA_SIDE_PX) {
                scale = minOf(PREFETCH_MAX_UPSCALE_FACTOR, PREFETCH_MIN_UPSCALED_SIDE_PX.toDouble() / minSide.toDouble())
            }
            if (maxSide * scale > PREFETCH_MAX_SIDE_PX) {
                scale = PREFETCH_MAX_SIDE_PX.toDouble() / maxSide.toDouble()
            }
            val dstW = (srcW * scale).roundToInt().coerceAtLeast(1)
            val dstH = (srcH * scale).roundToInt().coerceAtLeast(1)

            val sourceBmp = if (dstW != srcW || dstH != srcH) {
                Bitmap.createScaledBitmap(decoded, dstW, dstH, true).also { scaled = it }
            } else {
                decoded
            }

            val canvasBmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
                .also { composited = it }
            val canvas = Canvas(canvasBmp)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(sourceBmp, 0f, 0f, null)

            fun encode(format: Bitmap.CompressFormat, quality: Int, mime: String): String? {
                val out = ByteArrayOutputStream()
                if (!canvasBmp.compress(format, quality, out)) return null
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                if (b64.isEmpty()) return null
                return "data:$mime;base64,$b64"
            }

            var dataUrl = if (dstW <= PREFETCH_PNG_THRESHOLD_PX && dstH <= PREFETCH_PNG_THRESHOLD_PX) {
                encode(Bitmap.CompressFormat.PNG, 100, "image/png")
            } else {
                encode(Bitmap.CompressFormat.JPEG, PREFETCH_JPEG_QUALITY_HIGH, "image/jpeg")
            }
            if (dataUrl == null || dataUrl.length > maxAllowed) {
                dataUrl = encode(Bitmap.CompressFormat.JPEG, PREFETCH_JPEG_QUALITY_FALLBACK, "image/jpeg")
                    ?.takeIf { it.length <= maxAllowed }
            }
            dataUrl
        } finally {
            composited?.let { if (!it.isRecycled) it.recycle() }
            scaled?.let { if (it !== decoded && !it.isRecycled) it.recycle() }
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun isHttpUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    private fun evaluateAnyWebView(
        webView: Any,
        script: String,
        onResult: (String?) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (webView is WebView) {
            runCatching {
                webView.evaluateJavascript(script) { raw -> onResult(raw) }
            }.onFailure(onError)
        } else {
            evaluateX5WebView(webView, script, onResult).onFailure(onError)
        }
    }

    private fun evaluateX5WebView(
        x5WebView: Any,
        script: String,
        callback: (String?) -> Unit
    ): Result<Unit> = runCatching {
        val loader = x5WebView.javaClass.classLoader
        val vcClass = Class.forName("com.tencent.smtt.sdk.ValueCallback", false, loader)
        val proxy = Proxy.newProxyInstance(loader, arrayOf(vcClass)) { _, method, args ->
            if (method.name == "onReceiveValue") {
                val value = args?.firstOrNull()?.toString()
                callback(value)
            }
            null
        }
        val evalMethod = x5WebView.javaClass.getMethod("evaluateJavascript", String::class.java, vcClass)
        evalMethod.invoke(x5WebView, script, proxy)
    }

    private fun resolveWebViewUserAgent(webView: Any): String {
        return runCatching {
            if (webView is WebView) {
                webView.settings.userAgentString.orEmpty()
            } else {
                val settings = webView.javaClass.getMethod("getSettings").invoke(webView)
                settings?.javaClass?.getMethod("getUserAgentString")?.invoke(settings)?.toString().orEmpty()
            }
        }.getOrDefault("").ifBlank { DEFAULT_USER_AGENT }
    }

    private fun reflectStringGetter(target: Any, methodName: String): String {
        return runCatching {
            (target as? WebView)?.let { wv ->
                return when (methodName) {
                    "getUrl" -> wv.url.orEmpty()
                    "getTitle" -> wv.title.orEmpty()
                    else -> ""
                }
            }
            target.javaClass.getMethod(methodName).invoke(target)?.toString().orEmpty()
        }.getOrDefault("")
    }

    private fun decodeJsObject(rawResult: String?): Result<JSONObject> {
        if (rawResult.isNullOrBlank() || rawResult == "null") {
            return Result.failure(IllegalStateException(EMPTY_JS_RESULT_MARKER))
        }
        return runCatching {
            when (val parsed = JSONTokener(rawResult).nextValue()) {
                is JSONObject -> parsed
                is String -> JSONObject(parsed)
                else -> JSONObject(rawResult)
            }
        }
    }

    private fun buildSnapshotFromJsonObject(
        sourcePackage: String,
        fallbackUrl: String,
        fallbackTitle: String,
        decoded: JSONObject
    ): DomSnapshot {
        val url = decoded.optString("url").ifBlank { fallbackUrl }
        val title = decoded.optString("title").ifBlank { fallbackTitle }
        val selected = decoded.optString("selectedText").take(DomIpcContract.MAX_BODY_CHARS)
        val body = decoded.optString("bodyText").take(DomIpcContract.MAX_BODY_CHARS)
        val iframeCount = decoded.optInt("crossOriginIframeCount", 0).coerceAtLeast(0)
        val hasObfuscatedFont = decoded.optBoolean("hasObfuscatedFont", false)
        val extractedQuestions = DomSnapshot.parseQuizItems(decoded.optJSONArray("extractedQuestions"))
        val images = DomSnapshot.parseImages(decoded.optJSONArray("images"))
        return DomSnapshot(
            sourcePackage = sourcePackage,
            url = url,
            title = title,
            selectedText = selected,
            bodyText = body,
            crossOriginIframeCount = iframeCount,
            capturedAtMs = System.currentTimeMillis(),
            error = null,
            extractedQuestions = extractedQuestions,
            images = images,
            hasObfuscatedFont = hasObfuscatedFont
        )
    }

    private fun sendSnapshotBroadcast(appCtx: Context, requestId: String, snapshot: DomSnapshot) {
        runCatching {
            var payloadJson = snapshot.toJson()
            if (payloadJson.length > DomIpcContract.MAX_ASSEMBLED_PAYLOAD_CHARS) {
                val strippedImages = snapshot.images.map { img ->
                    if (img.url.isNotBlank()) img.copy(dataUrl = "") else img
                }
                payloadJson = snapshot.copy(images = strippedImages).toJson()
                if (payloadJson.length > DomIpcContract.MAX_ASSEMBLED_PAYLOAD_CHARS) {
                    payloadJson = DomSnapshot.errorSnapshot(
                        snapshot.sourcePackage,
                        "页面 DOM 数据过大，请改用选中题目或截屏框选",
                        url = snapshot.url,
                        title = snapshot.title
                    ).toJson()
                }
            }
            val chunks = DomChunkAssembler.splitPayload(payloadJson, DomIpcContract.MAX_CHUNK_CHARS)
            val total = chunks.size
            for ((index, chunk) in chunks.withIndex()) {
                val reply = Intent(DomIpcContract.ACTION_DOM_SNAPSHOT).apply {
                    setPackage(DomIpcContract.ASSISTANT_PACKAGE)
                    putExtra(DomIpcContract.EXTRA_REQUEST_ID, requestId)
                    putExtra(DomIpcContract.EXTRA_CHUNK_INDEX, index)
                    putExtra(DomIpcContract.EXTRA_CHUNK_TOTAL, total)
                    putExtra(DomIpcContract.EXTRA_PAYLOAD_JSON, chunk)
                }
                appCtx.sendBroadcast(reply)
            }
        }.onFailure { err ->
            log(Log.WARN, TAG, "Failed to send snapshot broadcast", err)
        }
    }

    private fun currentApplicationByReflection(): Application? {
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            atClass.getMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()
    }

    companion object {
        private const val TAG = "WebViewDomModule"
        private const val EMPTY_JS_RESULT_MARKER = "__SCX_EMPTY_JS_RESULT__"
        private const val EVALUATE_TIMEOUT_MS = 3500L
        private const val SVG_POLL_INTERVAL_MS = 250L
        private const val SVG_MAX_POLL_ATTEMPTS = 4
        private const val MAX_PREFETCH_IMAGES = 8
        private const val MAX_PREFETCH_REDIRECTS = 3
        private const val PREFETCH_TIMEOUT_MS = 3000
        private const val MAX_PREFETCH_RAW_BYTES = 4 * 1024 * 1024
        private const val MAX_INLINE_PER_IMG_CHARS = 450_000
        private const val MAX_TOTAL_INLINE_CHARS = 1_800_000
        private const val PREFETCH_MIN_FORMULA_SIDE_PX = 36
        private const val PREFETCH_MIN_UPSCALED_SIDE_PX = 40
        private const val PREFETCH_MAX_UPSCALE_FACTOR = 3.0
        private const val PREFETCH_MAX_SIDE_PX = 1280
        private const val PREFETCH_PNG_THRESHOLD_PX = 320
        private const val PREFETCH_JPEG_QUALITY_HIGH = 85
        private const val PREFETCH_JPEG_QUALITY_FALLBACK = 72
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        private val EXTRACT_DOM_JS = """
            (function() {
              try {
                var maxLen = ${DomIpcContract.MAX_BODY_CHARS};
                var maxQuestions = ${DomSnapshot.MAX_EXTRACTED_QUESTIONS};
                var maxQuestionChars = ${DomSnapshot.MAX_QUESTION_CHARS};
                var maxOptions = ${DomSnapshot.MAX_OPTIONS_PER_QUESTION};
                var maxImages = ${DomSnapshot.MAX_CAPTURED_IMAGES};
                var maxInlinePerImg = 450000;
                var maxTotalInline = 1800000;
                var crossCount = 0;
                var hasObfuscatedFont = false;
                var selectedRaw = '';
                var chunks = [];
                var rawQuestions = [];
                var seenQuestions = {};

                var rawImages = [];
                var rawImageByKey = {};
                var tokenPrefix = '__SCX_IMG_';
                var tokenSuffix = '__';

                var containerSelector = '.TiMu, .questionLi, .singleQuesId, .examPaper_subject, .subject_describe, .topic-item, .question-item, .exam-item, .q_main, [class*="question-card"], [class*="ques-item"], [class*="question-wrap"]';
                var stemSelectors = ['.clearfix > .fl', '.mark_name', '.qtContent', '.subject_stem', '.question-stem', '.question-content', '.qeustion-content .markdown-latex-container', '.qeustion-content', '.combination-content', '.question-topic', '.topic-title', '.Zy_TItle .clearfix', 'h3', 'h4'];
                var optionSelectors = ['.answerBg .answer_p', '.mark_answer li', '.option-item .markdown-latex-container', '.option-item .vditor-content', '.option-item', 'ul li .after', '.textDIV', '.eidtDiv', '[class*="option"]', 'li', 'label:not(.before)'];

                var SKIP_TAGS = {
                  SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, HEAD: 1,
                  META: 1, LINK: 1, IFRAME: 1, FRAME: 1, OBJECT: 1, EMBED: 1,
                  VIDEO: 1, AUDIO: 1
                };
                var NOISE_LAYOUT_TAGS = { NAV: 1, HEADER: 1, FOOTER: 1, ASIDE: 1 };
                var BLOCK_TAGS = {
                  P: 1, DIV: 1, LI: 1, TR: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1,
                  SECTION: 1, ARTICLE: 1, BLOCKQUOTE: 1, PRE: 1, DT: 1, DD: 1
                };
                var MATH_SYM = {
                  '\u2032': "'", '\u2033': "''", '\u2061': '', '\u2212': '-',
                  '\u22C5': '\\cdot ', '\u00D7': '\\times ', '\u00F7': '\\div ',
                  '\u2264': '\\le ', '\u2265': '\\ge ', '\u2260': '\\ne ',
                  '\u2248': '\\approx ', '\u00B1': '\\pm ', '\u221E': '\\infty ',
                  '\u2211': '\\sum ', '\u222B': '\\int ', '\u220F': '\\prod ',
                  '\u2202': '\\partial ', '\u2207': '\\nabla ', '\u2208': '\\in ',
                  '\u2209': '\\notin ', '\u2282': '\\subset ', '\u2286': '\\subseteq ',
                  '\u222A': '\\cup ', '\u2229': '\\cap ', '\u2200': '\\forall ',
                  '\u2203': '\\exists ', '\u2192': '\\to ', '\u21D2': '\\Rightarrow ',
                  '\u21D4': '\\Leftrightarrow '
                };

                function cleanText(s) {
                  return String(s || '')
                    .replace(/\r/g, '')
                    .replace(/[ \t\u00A0\u3000]+/g, ' ')
                    .replace(/ *\n */g, '\n')
                    .replace(/\n{3,}/g, '\n\n')
                    .trim();
                }

                function getClassName(el) {
                  if (!el) return '';
                  if (typeof el.className === 'string') return el.className;
                  if (el.className && typeof el.className.baseVal === 'string') return el.className.baseVal;
                  return (el.getAttribute && el.getAttribute('class')) || '';
                }

                function stripOptionPrefix(s) {
                  return cleanText(s).replace(/^[A-Ha-h][.．:：、)）]\s*/, '').trim();
                }

                function unwrapTexDelimiters(raw) {
                  var t = String(raw || '').trim();
                  if (!t) return '';
                  t = t.replace(/^\$\$([\s\S]*)\$\$$/, '$1').replace(/^\$([\s\S]*)\$$/, '$1');
                  t = t.replace(/^\\\(([\s\S]*)\\\)$/, '$1').replace(/^\\\[([\s\S]*)\\\]$/, '$1');
                  return t.trim();
                }

                function formatMathDelimiter(raw, isBlock) {
                  var inner = unwrapTexDelimiters(raw);
                  if (!inner) return '';
                  if (isBlock) return '\n$$' + inner + '$$\n';
                  return ' $' + inner + '$ ';
                }

                function normalizeMathUnicode(str) {
                  var out = String(str || '');
                  try { out = out.normalize('NFKC'); } catch (eN) {}
                  var mapped = '';
                  for (var i = 0; i < out.length; i++) {
                    var ch = out.charAt(i);
                    mapped += (MATH_SYM.hasOwnProperty(ch) ? MATH_SYM[ch] : ch);
                  }
                  return mapped;
                }

                function mmlElementChildren(node) {
                  var kids = [];
                  if (!node || !node.children) return kids;
                  for (var i = 0; i < node.children.length; i++) {
                    var c = node.children[i];
                    if (c && c.tagName) kids.push(c);
                  }
                  return kids;
                }

                function mathmlToLatex(node) {
                  if (!node) return '';
                  if (node.nodeType === 3) return normalizeMathUnicode(node.textContent || '');
                  if (node.nodeType !== 1) return '';
                  var tag = (node.localName || node.tagName || '').toLowerCase();
                  if (tag.indexOf(':') >= 0) tag = tag.split(':').pop();
                  var kids = mmlElementChildren(node);
                  function at(idx) { return kids[idx] ? mathmlToLatex(kids[idx]) : ''; }
                  function allKids() {
                    var s = '';
                    for (var i = 0; i < kids.length; i++) s += mathmlToLatex(kids[i]);
                    return s;
                  }
                  if (tag === 'mi' || tag === 'mn' || tag === 'mo' || tag === 'mtext' || tag === 'ms') {
                    return normalizeMathUnicode(node.textContent || '');
                  }
                  if (tag === 'mspace') return ' ';
                  if (tag === 'mfrac') return '\\frac{' + at(0) + '}{' + at(1) + '}';
                  if (tag === 'msqrt') return '\\sqrt{' + allKids() + '}';
                  if (tag === 'mroot') return '\\sqrt[' + at(1) + ']{' + at(0) + '}';
                  if (tag === 'msup') return '{' + at(0) + '}^{' + at(1) + '}';
                  if (tag === 'msub') return '{' + at(0) + '}_{' + at(1) + '}';
                  if (tag === 'msubsup') return '{' + at(0) + '}_{' + at(1) + '}^{' + at(2) + '}';
                  if (tag === 'munder') return '{' + at(0) + '}_{' + at(1) + '}';
                  if (tag === 'mover') {
                    var over = at(1).trim();
                    if (over === '\u2192' || over === '\\to ' || over === '⃗') return '\\vec{' + at(0) + '}';
                    if (over === '\u00AF' || over === '-' || over === 'ˉ') return '\\overline{' + at(0) + '}';
                    if (over === '^' || over === '\u02C6') return '\\hat{' + at(0) + '}';
                    return '{' + at(0) + '}^{' + over + '}';
                  }
                  if (tag === 'munderover') return '{' + at(0) + '}_{' + at(1) + '}^{' + at(2) + '}';
                  if (tag === 'mfenced') {
                    var open = node.getAttribute('open') || '(';
                    var close = node.getAttribute('close') || ')';
                    var sep = node.getAttribute('separators');
                    if (sep === null || sep === undefined) sep = ',';
                    var parts = [];
                    for (var f = 0; f < kids.length; f++) parts.push(mathmlToLatex(kids[f]));
                    return open + parts.join(sep) + close;
                  }
                  if (tag === 'mtable') {
                    var rows = [];
                    for (var r = 0; r < kids.length; r++) {
                      rows.push(mathmlToLatex(kids[r]));
                    }
                    return '\\begin{matrix} ' + rows.join(' \\\\ ') + ' \\end{matrix}';
                  }
                  if (tag === 'mtr' || tag === 'mlabeledtr') {
                    var cells = [];
                    for (var td = 0; td < kids.length; td++) {
                      cells.push(mathmlToLatex(kids[td]));
                    }
                    return cells.join(' & ');
                  }
                  if (tag === 'mtd') return allKids();
                  if (tag === 'semantics') {
                    var ann = node.querySelector('annotation[encoding="application/x-tex"], annotation');
                    if (ann && ann.textContent && ann.textContent.trim()) {
                      return unwrapTexDelimiters(ann.textContent);
                    }
                    return at(0);
                  }
                  return kids.length > 0 ? allKids() : normalizeMathUnicode(node.textContent || '');
                }

                function mjxSvgNodeToLatex(node) {
                  if (!node || !node.getAttribute) return '';
                  var type = node.getAttribute('data-mml-node');
                  var kids = [];
                  if (node.children) {
                    for (var i = 0; i < node.children.length; i++) {
                      if (node.children[i].hasAttribute && node.children[i].hasAttribute('data-mml-node')) {
                        kids.push(node.children[i]);
                      }
                    }
                  }
                  function at(idx) { return kids[idx] ? mjxSvgNodeToLatex(kids[idx]) : ''; }
                  function allKids() {
                    var s = '';
                    for (var k = 0; k < kids.length; k++) s += mjxSvgNodeToLatex(kids[k]);
                    return s;
                  }
                  if (type === 'mi' || type === 'mn' || type === 'mo' || type === 'mtext' || type === 'ms') {
                    var glyphs = node.querySelectorAll('path[data-c], use[data-c]');
                    if (glyphs && glyphs.length > 0) {
                      var str = '';
                      for (var g = 0; g < glyphs.length; g++) {
                        var hex = glyphs[g].getAttribute('data-c');
                        if (hex) {
                          var cp = parseInt(hex, 16);
                          if (!isNaN(cp) && cp > 0) {
                            try { str += String.fromCodePoint(cp); } catch (eCp) {}
                          }
                        }
                      }
                      return normalizeMathUnicode(str);
                    }
                    return normalizeMathUnicode(node.textContent || '');
                  }
                  if (type === 'mspace') return ' ';
                  if (type === 'mfrac') return '\\frac{' + at(0) + '}{' + at(1) + '}';
                  if (type === 'msqrt') return '\\sqrt{' + allKids() + '}';
                  if (type === 'mroot') return '\\sqrt[' + at(1) + ']{' + at(0) + '}';
                  if (type === 'msup') return '{' + at(0) + '}^{' + at(1) + '}';
                  if (type === 'msub') return '{' + at(0) + '}_{' + at(1) + '}';
                  if (type === 'msubsup') return '{' + at(0) + '}_{' + at(1) + '}^{' + at(2) + '}';
                  if (type === 'munder') return '{' + at(0) + '}_{' + at(1) + '}';
                  if (type === 'mover') return '{' + at(0) + '}^{' + at(1) + '}';
                  if (type === 'munderover') return '{' + at(0) + '}_{' + at(1) + '}^{' + at(2) + '}';
                  if (type === 'mtable') {
                    var rows = [];
                    for (var r = 0; r < kids.length; r++) rows.push(mjxSvgNodeToLatex(kids[r]));
                    return '\\begin{matrix} ' + rows.join(' \\\\ ') + ' \\end{matrix}';
                  }
                  if (type === 'mtr') {
                    var cells = [];
                    for (var cIdx = 0; cIdx < kids.length; cIdx++) cells.push(mjxSvgNodeToLatex(kids[cIdx]));
                    return cells.join(' & ');
                  }
                  return allKids();
                }

                function tryExtractMathFromElement(el) {
                  var tag = (el.tagName || '').toUpperCase();
                  var cls = getClassName(el);

                  var attrTex = el.getAttribute('data-latex') || el.getAttribute('data-tex') || el.getAttribute('data-formula') || el.getAttribute('data-math') || '';
                  if (attrTex && attrTex.trim() && tag !== 'IMG') {
                    return formatMathDelimiter(attrTex, false);
                  }

                  if (cls && (/\bkatex\b/.test(cls) || /\bkatex-display\b/.test(cls))) {
                    var ann = el.querySelector('annotation[encoding="application/x-tex"], annotation');
                    if (ann && ann.textContent && ann.textContent.trim()) {
                      return formatMathDelimiter(ann.textContent, /\bkatex-display\b/.test(cls));
                    }
                    var kMath = el.querySelector('math');
                    if (kMath) {
                      return formatMathDelimiter(mathmlToLatex(kMath), /\bkatex-display\b/.test(cls));
                    }
                  }

                  if (tag === 'MJX-CONTAINER') {
                    var isDisplay = el.getAttribute('display') === 'true' || /\bmjx-block\b/.test(cls);
                    var assistiveMath = el.querySelector('mjx-assistive-mml math, math');
                    if (assistiveMath) {
                      var mmlTex = mathmlToLatex(assistiveMath);
                      if (mmlTex && mmlTex.trim()) return formatMathDelimiter(mmlTex, isDisplay);
                    }
                    var mmlSvg = el.querySelector('g[data-mml-node="math"]');
                    if (mmlSvg) {
                      var svgTex = mjxSvgNodeToLatex(mmlSvg);
                      if (svgTex && svgTex.trim()) return formatMathDelimiter(svgTex, isDisplay);
                    }
                    var aria = el.getAttribute('aria-label') || '';
                    if (aria && aria.trim()) return formatMathDelimiter(aria, isDisplay);
                  }

                  if (cls && (/\bMathJax\b/.test(cls) || /\bMathJax_Display\b/.test(cls) || /\bMathJax_SVG\b/.test(cls))) {
                    var isMjBlock = /\bMathJax_Display\b/.test(cls) || /\bMathJax_SVG_Display\b/.test(cls);
                    var next = el.nextElementSibling;
                    if (next && next.tagName && next.tagName.toUpperCase() === 'SCRIPT' && (next.type || '').indexOf('math/tex') >= 0) {
                      return formatMathDelimiter(next.textContent || '', isMjBlock || (next.type || '').indexOf('mode=display') >= 0);
                    }
                    var innerScript = el.querySelector('script[type*="math/tex"]');
                    if (innerScript && innerScript.textContent) {
                      return formatMathDelimiter(innerScript.textContent, isMjBlock);
                    }
                    var dataMathml = el.getAttribute('data-mathml') || '';
                    if (dataMathml && el.ownerDocument && el.ownerDocument.createElement) {
                      var tmp = el.ownerDocument.createElement('div');
                      tmp.innerHTML = dataMathml;
                      var parsedMath = tmp.querySelector('math');
                      if (parsedMath) return formatMathDelimiter(mathmlToLatex(parsedMath), isMjBlock);
                    }
                  }

                  if (tag === 'MATH') {
                    var mathTex = mathmlToLatex(el);
                    if (mathTex && mathTex.trim()) {
                      return formatMathDelimiter(mathTex, el.getAttribute('display') === 'block');
                    }
                  }

                  return null;
                }

                function tryExtractLatexFromImg(img, resolvedUrl) {
                  var directTex = img.getAttribute('data-latex') || img.getAttribute('data-tex') || img.getAttribute('data-formula') || '';
                  if (directTex && directTex.trim()) {
                    return formatMathDelimiter(directTex, false);
                  }
                  var alt = (img.getAttribute('alt') || '').trim();
                  var cls = getClassName(img);
                  if (alt && alt.length <= 300) {
                    var looksExplicitTex = (/^\$[\s\S]+\$$/.test(alt) || /\\[a-zA-Z]+/.test(alt) || /[\^_{}]/.test(alt));
                    var looksMathClass = /formula|math|latex|equation|kfformula/i.test(cls + ' ' + resolvedUrl);
                    var isGenericWord = /^(公式|图片|image|picture|formula|equation)$/i.test(alt);
                    if (!isGenericWord && (looksExplicitTex || (looksMathClass && /[=+\-*/<>0-9∫∑∏√α-ωΑ-Ω]/.test(alt)))) {
                      return formatMathDelimiter(alt, false);
                    }
                  }
                  if (resolvedUrl && /equation|codecogs|latex|tex=|formula=|mimetex|mathtype/i.test(resolvedUrl)) {
                    var qIdx = resolvedUrl.indexOf('?');
                    if (qIdx >= 0) {
                      var qStr = resolvedUrl.slice(qIdx + 1);
                      var pairs = qStr.split('&');
                      for (var p = 0; p < pairs.length; p++) {
                        var kv = pairs[p].split('=');
                        var k = (kv[0] || '').toLowerCase();
                        if ((k === 'tex' || k === 'latex' || k === 'formula') && kv.length > 1) {
                          try {
                            var val = decodeURIComponent(kv.slice(1).join('=').replace(/\+/g, ' ')).trim();
                            if (val && val.length <= 400) return formatMathDelimiter(val, false);
                          } catch (eUrl) {}
                        }
                      }
                    }
                    if (/latex\.codecogs\.com\/[^?]+\?(.*)/i.test(resolvedUrl)) {
                      try {
                        var rawCog = decodeURIComponent(RegExp.$1).trim();
                        if (rawCog && rawCog.length <= 400 && rawCog.indexOf('=') < 0) {
                          return formatMathDelimiter(rawCog, false);
                        }
                      } catch (eCog) {}
                    }
                  }
                  return null;
                }

                function resolveUrl(rawUrl, doc) {
                  var u = String(rawUrl || '').trim();
                  if (!u || u === 'about:blank') return '';
                  if (/^(data:|blob:|https?:)/i.test(u)) return u;
                  try {
                    return new URL(u, (doc && doc.baseURI) || location.href).href;
                  } catch (eU) {
                    return u;
                  }
                }

                function registerImage(el, url, alt, w, h, isFormula, reuseKey) {
                  if (reuseKey && rawImageByKey.hasOwnProperty(reuseKey)) {
                    var existingIdx = rawImageByKey[reuseKey];
                    return ' ' + tokenPrefix + existingIdx + tokenSuffix + ' ';
                  }
                  var idx = rawImages.length;
                  rawImages.push({
                    el: el,
                    url: url || '',
                    dataUrl: (url && url.indexOf('data:') === 0) ? url : '',
                    alt: String(alt || '').trim().slice(0, 200),
                    width: w || 0,
                    height: h || 0,
                    isFormula: !!isFormula
                  });
                  if (reuseKey) rawImageByKey[reuseKey] = idx;
                  return ' ' + tokenPrefix + idx + tokenSuffix + ' ';
                }

                function getComputedStyleSafe(el) {
                  try {
                    var view = el && el.ownerDocument && el.ownerDocument.defaultView;
                    if (view && el.isConnected) return view.getComputedStyle(el);
                  } catch (eCs) {}
                  return null;
                }

                function hasCxSecretFontFamily(el, cs) {
                  try {
                    var style = cs || getComputedStyleSafe(el);
                    return !!(style && style.fontFamily && /cxsecret/i.test(style.fontFamily));
                  } catch (eFont) {
                    return false;
                  }
                }

                function isElementHidden(el) {
                  if (!el || el.nodeType !== 1) return false;
                  if (el.hidden) return true;
                  var st = el.style;
                  if (st && (st.display === 'none' || st.visibility === 'hidden')) return true;
                  var cs = getComputedStyleSafe(el);
                  if (cs && (cs.display === 'none' || cs.visibility === 'hidden')) return true;
                  var cls = getClassName(el);
                  if ((cls && /\bfont-cxsecret\b/.test(cls)) || hasCxSecretFontFamily(el, cs)) {
                    hasObfuscatedFont = true;
                  }
                  return false;
                }

                function serializeStandaloneSvg(svgNode, w, h) {
                  try {
                    if (!svgNode || typeof XMLSerializer === 'undefined') return '';
                    var clone = svgNode.cloneNode(true);
                    if (!clone.getAttribute('xmlns')) {
                      clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
                    }
                    if (!clone.getAttribute('xmlns:xlink') && /xlink:href/i.test(svgNode.outerHTML || '')) {
                      clone.setAttribute('xmlns:xlink', 'http://www.w3.org/1999/xlink');
                    }
                    var origW = parseFloat(svgNode.getAttribute('width') || '0');
                    var origH = parseFloat(svgNode.getAttribute('height') || '0');
                    if (!clone.getAttribute('viewBox') && origW > 0 && origH > 0 && (origW !== w || origH !== h)) {
                      clone.setAttribute('viewBox', '0 0 ' + origW + ' ' + origH);
                    }
                    clone.setAttribute('width', String(w));
                    clone.setAttribute('height', String(h));
                    var xml = new XMLSerializer().serializeToString(clone);
                    if (xml && xml.indexOf('xmlns="http://www.w3.org/2000/svg"') < 0) {
                      xml = xml.replace(/^<svg\b/i, '<svg xmlns="http://www.w3.org/2000/svg"');
                    }
                    return xml || '';
                  } catch (eSvgSer) {
                    return '';
                  }
                }

                function isNoisyUiImage(img, url, w, h) {
                  var cls = getClassName(img);
                  var isFormulaHint = /formula|latex|math|katex|equation|kfformula|jyeoo|codecogs/i.test(url + ' ' + cls);
                  if (!isFormulaHint) {
                    if ((w > 0 && w <= 4) || (h > 0 && h <= 4)) return true;
                    if (/\/assets\/icon|\/images\/icon|\/static\/icon|avatar|headimg|spacer\.gif|blank\.gif|loading\.gif|spinner/i.test(url)) return true;
                    if (/\b(avatar|user-head|btn-icon|close-icon|nav-logo)\b/i.test(cls)) return true;
                  }
                  return false;
                }

                function walkRich(node, doc, opts, parts) {
                  if (!node) return;
                  if (node.nodeType === 3) {
                    parts.push(node.textContent || '');
                    return;
                  }
                  if (node.nodeType !== 1) return;
                  var tag = (node.tagName || '').toUpperCase();
                  if (SKIP_TAGS[tag]) return;
                  if (opts && opts.skipNavChrome && NOISE_LAYOUT_TAGS[tag]) return;
                  if (isElementHidden(node)) return;

                  var cls = getClassName(node);
                  if (cls && (/\bkatex-html\b/.test(cls) || /\bMathJax_Preview\b/.test(cls) || /\bMJX_Assistive_MathML\b/.test(cls))) return;
                  if (tag === 'MJX-ASSISTIVE-MML') return;

                  var mathLatex = tryExtractMathFromElement(node);
                  if (mathLatex !== null) {
                    if (mathLatex) parts.push(mathLatex);
                    return;
                  }

                  if (tag === 'IMG') {
                    if (opts && opts.isOption) {
                      var parentCls = typeof cls === 'string' ? cls : '';
                      if (/\b(radio|checkbox|icon-check)\b/i.test(parentCls)) return;
                    }
                    var rawUrl = node.currentSrc || node.getAttribute('data-original') || node.getAttribute('data-src') || node.getAttribute('data-lazy-src') || node.getAttribute('original-src') || node.src || '';
                    var url = resolveUrl(rawUrl, doc);
                    var rect = node.getBoundingClientRect ? node.getBoundingClientRect() : { width: 0, height: 0 };
                    var w = node.naturalWidth || Math.round(rect.width) || parseInt(node.getAttribute('width') || '0', 10) || 0;
                    var h = node.naturalHeight || Math.round(rect.height) || parseInt(node.getAttribute('height') || '0', 10) || 0;
                    var imgLatex = tryExtractLatexFromImg(node, url);
                    if (imgLatex) {
                      parts.push(imgLatex);
                      return;
                    }
                    if (!url || isNoisyUiImage(node, url, w, h)) return;
                    var isFormulaImg = /formula|latex|math|katex|equation|kfformula|jyeoo|codecogs/i.test(url + ' ' + cls) || (w > 0 && h > 0 && h <= 56 && w <= 600);
                    var alt = node.getAttribute('alt') || '';
                    parts.push(registerImage(node, url, alt, w, h, isFormulaImg, url));
                    return;
                  }

                  if (tag === 'CANVAS') {
                    var cRect = node.getBoundingClientRect ? node.getBoundingClientRect() : { width: 0, height: 0 };
                    var cw = node.width || Math.round(cRect.width) || 0;
                    var ch = node.height || Math.round(cRect.height) || 0;
                    if (cw >= 24 && ch >= 16) {
                      parts.push(registerImage(node, '', '', cw, ch, ch <= 80, null));
                    }
                    return;
                  }

                  if (tag === 'SVG') {
                    if (cls && /\b(katex|MathJax|mjx-)/i.test(cls)) return;
                    if (opts && opts.isOption && /\b(radio|checkbox|icon-check)\b/i.test(cls)) return;
                    var sRect = node.getBoundingClientRect ? node.getBoundingClientRect() : { width: 0, height: 0 };
                    var attrW = parseFloat(node.getAttribute('width') || '0') || 0;
                    var attrH = parseFloat(node.getAttribute('height') || '0') || 0;
                    var sw = Math.round(sRect.width) || Math.round(attrW) || 0;
                    var sh = Math.round(sRect.height) || Math.round(attrH) || 0;
                    if ((sw <= 0 || sh <= 0) && node.getAttribute('viewBox')) {
                      var vbParts = String(node.getAttribute('viewBox')).trim().split(/[\s,]+/);
                      if (vbParts.length === 4) {
                        if (sw <= 0) sw = Math.round(parseFloat(vbParts[2]) || 0);
                        if (sh <= 0) sh = Math.round(parseFloat(vbParts[3]) || 0);
                      }
                    }
                    if (sw < 24 || sh < 20) return;
                    if (sw <= 28 && sh <= 28 && /\b(icon|btn-icon|close-icon|arrow|caret|spinner|loading)\b/i.test(cls)) return;
                    var hasShape = false;
                    try {
                      hasShape = !!node.querySelector('path, circle, rect, line, polygon, polyline, ellipse, text');
                    } catch (eSvgQ) {}
                    if (!hasShape) return;
                    var svgXml = serializeStandaloneSvg(node, sw, sh);
                    if (!svgXml) return;
                    var svgAlt = node.getAttribute('aria-label') || node.getAttribute('title') || '';
                    if (!svgAlt) {
                      try {
                        var titleEl = node.querySelector('title');
                        if (titleEl && titleEl.textContent) svgAlt = titleEl.textContent;
                      } catch (eTitle) {}
                    }
                    var svgToken = registerImage(node, '', svgAlt, sw, sh, sh <= 80, null);
                    var svgIdxMatch = /__SCX_IMG_(\d+)__/.exec(svgToken);
                    if (svgIdxMatch) {
                      var svgRawIdx = parseInt(svgIdxMatch[1], 10);
                      if (rawImages[svgRawIdx]) {
                        rawImages[svgRawIdx].isSvg = true;
                        rawImages[svgRawIdx].svgXml = svgXml;
                      }
                    }
                    parts.push(svgToken);
                    return;
                  }

                  if (tag === 'BR') {
                    parts.push('\n');
                    return;
                  }

                  if ((tag === 'SUP' || tag === 'SUB') && !node.querySelector('img, canvas, math, mjx-container, .katex')) {
                    var scriptInner = cleanText(node.textContent || '');
                    if (scriptInner && scriptInner.length <= 24) {
                      parts.push((tag === 'SUP' ? '^{' : '_{') + scriptInner + '}');
                      return;
                    }
                  }

                  var isBlock = !!BLOCK_TAGS[tag];
                  if (isBlock) parts.push('\n');
                  var children = node.childNodes;
                  if (children) {
                    for (var i = 0; i < children.length; i++) {
                      walkRich(children[i], doc, opts, parts);
                    }
                  }
                  if (isBlock) parts.push('\n');
                }

                function extractRichText(root, doc, opts) {
                  var parts = [];
                  walkRich(root, doc, opts || {}, parts);
                  return cleanText(parts.join(''));
                }

                function isJudgementPair(opts) {
                  if (!opts || opts.length !== 2) return false;
                  var a = opts[0].toLowerCase().trim();
                  var b = opts[1].toLowerCase().trim();
                  var pairs = [
                    ['正确', '错误'], ['对', '错'], ['true', 'false'],
                    ['t', 'f'], ['√', '×'], ['是', '否'], ['yes', 'no']
                  ];
                  for (var p = 0; p < pairs.length; p++) {
                    var x = pairs[p][0];
                    var y = pairs[p][1];
                    if ((a === x && b === y) || (a === y && b === x)) return true;
                  }
                  return false;
                }

                function extractOptions(el, doc) {
                  var opts = [];
                  for (var s = 0; s < optionSelectors.length; s++) {
                    var sel = optionSelectors[s];
                    var nodes;
                    try { nodes = el.querySelectorAll(sel); } catch (eSel) { continue; }
                    if (!nodes || nodes.length === 0) continue;
                    var candidateOpts = [];
                    var seenOpt = {};
                    for (var j = 0; j < nodes.length; j++) {
                      var node = nodes[j];
                      if (sel === '[class*="option"]' && node.querySelector('[class*="option"], li, label')) {
                        continue;
                      }
                      var txt = stripOptionPrefix(extractRichText(node, doc, { isOption: true }));
                      if (txt && !seenOpt[txt]) {
                        seenOpt[txt] = true;
                        candidateOpts.push(txt);
                        if (candidateOpts.length >= maxOptions) break;
                      }
                    }
                    var isGeneric = (sel === 'li' || sel === 'label:not(.before)');
                    if (candidateOpts.length >= (isGeneric ? 2 : 1)) {
                      opts = candidateOpts;
                      break;
                    }
                  }
                  return opts;
                }

                function extractStem(el, opts, doc) {
                  for (var i = 0; i < stemSelectors.length; i++) {
                    var found = null;
                    try { found = el.querySelector(stemSelectors[i]); } catch (eQ) {}
                    if (found) {
                      var t = extractRichText(found, doc, { isOption: false });
                      if (t) return t;
                    }
                  }
                  var full = extractRichText(el, doc, { isOption: false });
                  if (!full) return '';
                  var lines = full.split('\n');
                  var stemLines = [];
                  var optSet = {};
                  for (var k = 0; k < opts.length; k++) {
                    optSet[opts[k]] = true;
                  }
                  for (var idx = 0; idx < lines.length; idx++) {
                    var line = lines[idx].trim();
                    if (!line) continue;
                    var stripped = stripOptionPrefix(line);
                    if (optSet[stripped]) break;
                    stemLines.push(line);
                    if (stemLines.length >= 8) break;
                  }
                  return stemLines.join('\n').trim() || lines[0].trim();
                }

                function inferType(el, stem, opts) {
                  var hiddenType = el.querySelector('input[id^="answertype"], input[name^="type"]');
                  if (hiddenType && hiddenType.value !== undefined && hiddenType.value !== '') {
                    var parsed = parseInt(hiddenType.value, 10);
                    if (!isNaN(parsed) && parsed >= 0 && parsed <= 4) return parsed;
                  }
                  if (isJudgementPair(opts)) return 3;
                  if (el.querySelector('input[type="checkbox"]')) return 1;
                  if (el.querySelector('input[type="radio"]')) return 0;
                  if (el.querySelector('input[type="text"]')) return 2;
                  if (el.querySelector('textarea, [contenteditable="true"]')) {
                    if (stem.indexOf('填空') >= 0) return 2;
                    return 4;
                  }
                  if (opts.length >= 2) {
                    if (stem.indexOf('多选') >= 0) return 1;
                    return 0;
                  }
                  if (stem.indexOf('填空') >= 0) return 2;
                  return 4;
                }

                function isElementInViewport(el, doc, parentVisible) {
                  if (!parentVisible || !el || !el.getBoundingClientRect) return false;
                  var r = el.getBoundingClientRect();
                  if (r.width <= 0 || r.height <= 0) return false;
                  var win = doc.defaultView || window;
                  var vh = win.innerHeight || (doc.documentElement && doc.documentElement.clientHeight) || 0;
                  var vw = win.innerWidth || (doc.documentElement && doc.documentElement.clientWidth) || 0;
                  if (vh <= 0 || vw <= 0) return true;
                  return r.bottom > 0 && r.top < vh && r.right > 0 && r.left < vw;
                }

                function findObfuscatedFontElement(root) {
                  if (!root || root.nodeType !== 1) return null;
                  var cls = getClassName(root);
                  if (/\bfont-cxsecret\b/.test(cls) || hasCxSecretFontFamily(root, null)) {
                    return root;
                  }
                  try {
                    var byCls = root.querySelector('.font-cxsecret');
                    if (byCls && !isElementHidden(byCls)) return byCls;
                  } catch (eQ) {}
                  try {
                    var all = root.getElementsByTagName('*');
                    var limit = Math.min(all.length, 80);
                    for (var i = 0; i < limit; i++) {
                      var child = all[i];
                      if (!child || isElementHidden(child)) continue;
                      if (hasCxSecretFontFamily(child, null)) return child;
                    }
                  } catch (eAll) {}
                  return null;
                }

                function wrapCanvasLines(ctx, text, maxWidth) {
                  var outLines = [];
                  var paragraphs = String(text || '').split('\n');
                  for (var p = 0; p < paragraphs.length; p++) {
                    var para = paragraphs[p];
                    if (!para) {
                      outLines.push('');
                      continue;
                    }
                    var line = '';
                    for (var i = 0; i < para.length; i++) {
                      var ch = para.charAt(i);
                      var testLine = line + ch;
                      if (line && ctx.measureText(testLine).width > maxWidth) {
                        var lastSpace = line.lastIndexOf(' ');
                        if (lastSpace > Math.floor(line.length * 0.5) && /[A-Za-z0-9]/.test(ch)) {
                          outLines.push(line.slice(0, lastSpace));
                          line = line.slice(lastSpace + 1) + ch;
                        } else {
                          outLines.push(line);
                          line = ch;
                        }
                      } else {
                        line = testLine;
                      }
                    }
                    if (line) outLines.push(line);
                  }
                  return outLines;
                }

                function renderObfuscatedQuestionCanvas(doc, fontEl, stem, opts) {
                  try {
                    var targetDoc = (doc && doc.createElement) ? doc : document;
                    var canvas = targetDoc.createElement('canvas');
                    if (!canvas || !canvas.getContext) return null;
                    var ctx = canvas.getContext('2d');
                    if (!ctx) return null;

                    var cs = getComputedStyleSafe(fontEl);
                    var rawSize = cs ? parseFloat(cs.fontSize) : 16;
                    if (!rawSize || isNaN(rawSize)) rawSize = 16;
                    var fontPx = Math.max(18, Math.min(28, Math.round(rawSize * 1.25)));
                    var fStyle = (cs && cs.fontStyle && cs.fontStyle !== 'normal') ? (cs.fontStyle + ' ') : '';
                    var fWeight = (cs && cs.fontWeight && cs.fontWeight !== '400' && cs.fontWeight !== 'normal') ? (cs.fontWeight + ' ') : '';
                    var fFamily = (cs && cs.fontFamily) ? cs.fontFamily : 'sans-serif';
                    if (!/sans-serif|serif/i.test(fFamily)) {
                      fFamily = fFamily + ', sans-serif';
                    }
                    var fontSpec = fStyle + fWeight + fontPx + 'px ' + fFamily;

                    var cleanStem = String(stem || '').replace(/__SCX_IMG_\d+__/g, '[图]').trim();
                    var linesInput = [];
                    if (cleanStem) linesInput.push(cleanStem);
                    if (opts && opts.length) {
                      for (var i = 0; i < opts.length; i++) {
                        var letter = String.fromCharCode(65 + i);
                        var cleanOpt = String(opts[i] || '').replace(/__SCX_IMG_\d+__/g, '[图]').trim();
                        if (cleanOpt) linesInput.push(letter + '. ' + cleanOpt);
                      }
                    }
                    var fullText = linesInput.join('\n');
                    if (!fullText || !fullText.trim()) return null;

                    var canvasW = 680;
                    var padding = 20;
                    var lineHeight = Math.round(fontPx * 1.45);
                    canvas.width = canvasW;
                    canvas.height = 64;
                    ctx.font = fontSpec;

                    var wrapped = wrapCanvasLines(ctx, fullText, canvasW - padding * 2);
                    if (!wrapped || wrapped.length === 0) return null;
                    if (wrapped.length > 48) wrapped = wrapped.slice(0, 48);

                    var canvasH = Math.min(1800, padding * 2 + wrapped.length * lineHeight);
                    if (canvasH < 40) canvasH = 40;

                    canvas.width = canvasW;
                    canvas.height = canvasH;
                    ctx.fillStyle = '#FFFFFF';
                    ctx.fillRect(0, 0, canvasW, canvasH);
                    ctx.font = fontSpec;
                    ctx.fillStyle = '#111111';
                    ctx.textBaseline = 'top';

                    for (var li = 0; li < wrapped.length; li++) {
                      var y = padding + li * lineHeight;
                      if (y + lineHeight > canvasH) break;
                      ctx.fillText(wrapped[li], padding, y);
                    }

                    var hasInk = false;
                    try {
                      var imgData = ctx.getImageData(0, 0, canvasW, canvasH).data;
                      var stride = 4 * 7;
                      for (var px = 0; px < imgData.length; px += stride) {
                        if (imgData[px] < 245 || imgData[px + 1] < 245 || imgData[px + 2] < 245) {
                          hasInk = true;
                          break;
                        }
                      }
                    } catch (eImgData) {
                      hasInk = true;
                    }
                    if (!hasInk) return null;

                    var out = canvas.toDataURL('image/png');
                    if (out && out.length > maxInlinePerImg) {
                      out = canvas.toDataURL('image/jpeg', 0.85);
                    }
                    if (out && out.length > maxInlinePerImg) {
                      out = canvas.toDataURL('image/jpeg', 0.72);
                    }
                    if (!out || out.length > maxInlinePerImg) return null;
                    return { dataUrl: out, width: canvasW, height: canvasH };
                  } catch (eRender) {
                    return null;
                  }
                }

                function extractQuestionsFromDoc(doc, parentVisible) {
                  if (!doc || rawQuestions.length >= maxQuestions) return;
                  var containers = doc.querySelectorAll(containerSelector);
                  if (!containers || containers.length === 0) return;
                  var capturedEls = [];
                  for (var i = 0; i < containers.length; i++) {
                    if (rawQuestions.length >= maxQuestions) break;
                    var el = containers[i];
                    if (isElementHidden(el)) continue;
                    var nested = false;
                    for (var c = 0; c < capturedEls.length; c++) {
                      if (capturedEls[c] === el || capturedEls[c].contains(el)) {
                        nested = true;
                        break;
                      }
                    }
                    if (nested) continue;
                    var opts = extractOptions(el, doc);
                    var stem = extractStem(el, opts, doc);
                    if (!stem) continue;
                    if (stem.length > maxQuestionChars) stem = stem.slice(0, maxQuestionChars);
                    if (seenQuestions[stem]) continue;
                    seenQuestions[stem] = true;
                    capturedEls.push(el);
                    var qType = inferType(el, stem, opts);
                    var qVisible = isElementInViewport(el, doc, parentVisible);
                    var obfEl = findObfuscatedFontElement(el);
                    if (obfEl) {
                      hasObfuscatedFont = true;
                      var rendered = renderObfuscatedQuestionCanvas(doc, obfEl, stem, opts);
                      if (rendered && rendered.dataUrl) {
                        var visualToken = registerImage(null, rendered.dataUrl, '混淆字体题目视觉图', rendered.width, rendered.height, false, null);
                        var trimmedToken = visualToken.trim();
                        if (stem.length + trimmedToken.length + 1 > maxQuestionChars) {
                          stem = stem.slice(0, Math.max(0, maxQuestionChars - trimmedToken.length - 1));
                        }
                        stem = stem + '\n' + trimmedToken;
                      }
                    }
                    rawQuestions.push({
                      question: stem,
                      options: opts,
                      type: qType,
                      inViewport: qVisible
                    });
                  }
                }

                function extractSelectionRich(doc) {
                  var win = doc && doc.defaultView ? doc.defaultView : null;
                  var selObj = (win && win.getSelection) ? win.getSelection() : (doc && doc.getSelection ? doc.getSelection() : null);
                  if (!selObj || selObj.rangeCount <= 0 || selObj.isCollapsed) return '';
                  var range = selObj.getRangeAt(0);
                  var anc = range.commonAncestorContainer;
                  if (anc && anc.nodeType === 3) anc = anc.parentElement;
                  if (anc && !hasObfuscatedFont && findObfuscatedFontElement(anc)) {
                    hasObfuscatedFont = true;
                  }
                  var holder = doc.createElement('div');
                  holder.appendChild(range.cloneContents());
                  return extractRichText(holder, doc, { isOption: false });
                }

                function extractDocTree(doc, parentVisible) {
                  if (!doc) return;
                  try {
                    if (!hasObfuscatedFont) {
                      var obfNodes = doc.querySelectorAll('.font-cxsecret');
                      for (var obfIdx = 0; obfIdx < obfNodes.length; obfIdx++) {
                        if (!isElementHidden(obfNodes[obfIdx])) {
                          hasObfuscatedFont = true;
                          break;
                        }
                      }
                    }
                  } catch (eObfDoc) {}
                  try {
                    if (!selectedRaw) {
                      var sel = extractSelectionRich(doc);
                      if (sel) selectedRaw = sel;
                    }
                  } catch (eSel) {}
                  try {
                    extractQuestionsFromDoc(doc, parentVisible);
                  } catch (eQuiz) {}
                  try {
                    var body = doc.body || doc.documentElement;
                    if (body && !selectedRaw) {
                      var t = extractRichText(body, doc, { skipNavChrome: true });
                      if (t) chunks.push(t);
                    }
                  } catch (eBody) {}
                  try {
                    var frames = doc.querySelectorAll('iframe, frame');
                    for (var i = 0; i < frames.length; i++) {
                      var fr = frames[i];
                      var frVisible = isElementInViewport(fr, doc, parentVisible);
                      try {
                        var childDoc = fr.contentDocument || (fr.contentWindow && fr.contentWindow.document);
                        if (childDoc) {
                          extractDocTree(childDoc, frVisible);
                        } else {
                          crossCount++;
                        }
                      } catch (eFrame) {
                        crossCount++;
                      }
                    }
                  } catch (eList) {}
                }

                extractDocTree(document, true);
                var bodyRaw = chunks.join('\n\n').replace(/\r/g, '').trim();

                // Determine active scope so images are pruned strictly to selected/question content (full DOM fallback only).
                var activeTexts = [];
                if (selectedRaw) {
                  activeTexts.push(selectedRaw);
                } else if (rawQuestions.length > 0) {
                  var hasVisibleQ = rawQuestions.some(function(q) { return q.inViewport; });
                  var preferredRawQs = hasVisibleQ ? rawQuestions.filter(function(q) { return q.inViewport; }) : rawQuestions;
                  for (var pq = 0; pq < preferredRawQs.length; pq++) {
                    activeTexts.push(preferredRawQs[pq].question);
                    for (var po = 0; po < preferredRawQs[pq].options.length; po++) {
                      activeTexts.push(preferredRawQs[pq].options[po]);
                    }
                  }
                } else if (bodyRaw) {
                  activeTexts.push(bodyRaw);
                }

                var rawToDisplayIdx = {};
                var activeImageEntries = [];
                var tokenRegex = /__SCX_IMG_(\d+)__/g;
                for (var at = 0; at < activeTexts.length; at++) {
                  var str = activeTexts[at] || '';
                  var m;
                  tokenRegex.lastIndex = 0;
                  while ((m = tokenRegex.exec(str)) !== null) {
                    var rIdx = parseInt(m[1], 10);
                    if (!rawToDisplayIdx.hasOwnProperty(rIdx) && rawImages[rIdx] && activeImageEntries.length < maxImages) {
                      var displayIdx = activeImageEntries.length + 1;
                      rawToDisplayIdx[rIdx] = displayIdx;
                      activeImageEntries.push({
                        displayIdx: displayIdx,
                        item: rawImages[rIdx]
                      });
                    }
                  }
                }

                function rewritePlaceholders(text, collectIndices) {
                  if (!text) return '';
                  return String(text).replace(/__SCX_IMG_(\d+)__/g, function(_, numStr) {
                    var rIdx = parseInt(numStr, 10);
                    if (rawToDisplayIdx.hasOwnProperty(rIdx)) {
                      var dIdx = rawToDisplayIdx[rIdx];
                      if (collectIndices && collectIndices.indexOf(dIdx) < 0) {
                        collectIndices.push(dIdx);
                      }
                      return '[图片' + dIdx + ']';
                    }
                    return '[图片]';
                  });
                }

                var finalSelected = rewritePlaceholders(selectedRaw, null);
                var finalBody = rewritePlaceholders(bodyRaw, null);
                var finalQuestions = [];
                for (var q = 0; q < rawQuestions.length; q++) {
                  var rq = rawQuestions[q];
                  var qIndices = [];
                  var fqText = rewritePlaceholders(rq.question, qIndices);
                  var fqOpts = [];
                  for (var o = 0; o < rq.options.length; o++) {
                    fqOpts.push(rewritePlaceholders(rq.options[o], qIndices));
                  }
                  if (fqText.length > maxQuestionChars) fqText = fqText.slice(0, maxQuestionChars);
                  finalQuestions.push({
                    question: fqText,
                    options: fqOpts,
                    type: rq.type,
                    inViewport: rq.inViewport,
                    imageIndices: qIndices
                  });
                }

                function captureElementDataUrl(item, budgetRemaining) {
                  if (item.dataUrl) {
                    if (item.dataUrl.length <= maxInlinePerImg && item.dataUrl.length <= budgetRemaining) {
                      return item.dataUrl;
                    }
                    return '';
                  }
                  var el = item.el;
                  if (!el || !document.createElement) return '';
                  var isCanvas = (el.tagName || '').toUpperCase() === 'CANVAS';
                  var isLocalOrBlob = /^(blob:|data:)/i.test(item.url || '');
                  var isSameOrigin = false;
                  try {
                    if (item.url && /^https?:/i.test(item.url)) {
                      isSameOrigin = new URL(item.url, location.href).origin === location.origin;
                    }
                  } catch (eOr) {}
                  if (!isCanvas && !isLocalOrBlob && !isSameOrigin) return '';
                  if (budgetRemaining < 4096) return '';

                  try {
                    var srcW = isCanvas ? (el.width || item.width) : (el.naturalWidth || item.width);
                    var srcH = isCanvas ? (el.height || item.height) : (el.naturalHeight || item.height);
                    if (!srcW || !srcH || srcW <= 0 || srcH <= 0) return '';

                    var scale = 1;
                    var minSide = Math.min(srcW, srcH);
                    if (minSide < 36) {
                      scale = Math.min(3, Math.ceil(40 / minSide));
                    }
                    var dstW = Math.round(srcW * scale);
                    var dstH = Math.round(srcH * scale);
                    var maxSide = Math.max(dstW, dstH);
                    if (maxSide > 1280) {
                      var shrink = 1280 / maxSide;
                      dstW = Math.max(1, Math.round(dstW * shrink));
                      dstH = Math.max(1, Math.round(dstH * shrink));
                    }

                    var canvas = document.createElement('canvas');
                    canvas.width = dstW;
                    canvas.height = dstH;
                    var ctx = canvas.getContext('2d');
                    if (!ctx) return '';
                    ctx.fillStyle = '#FFFFFF';
                    ctx.fillRect(0, 0, dstW, dstH);
                    ctx.drawImage(el, 0, 0, dstW, dstH);

                    var out = (dstW <= 320 && dstH <= 320)
                      ? canvas.toDataURL('image/png')
                      : canvas.toDataURL('image/jpeg', 0.85);
                    if (out && out.length > maxInlinePerImg) {
                      out = canvas.toDataURL('image/jpeg', 0.72);
                    }
                    if (out && out.length <= maxInlinePerImg && out.length <= budgetRemaining) {
                      return out;
                    }
                  } catch (eCap) {
                    // Tainted cross-origin canvas falls back to Kotlin OkHttp fetcher using item.url.
                  }
                  return '';
                }

                var remainingBudget = maxTotalInline;
                var finalImages = [];
                for (var fi = 0; fi < activeImageEntries.length; fi++) {
                  var entry = activeImageEntries[fi];
                  var item = entry.item;
                  var inlineUrl = captureElementDataUrl(item, remainingBudget);
                  if (inlineUrl) {
                    remainingBudget -= inlineUrl.length;
                  }
                  finalImages.push({
                    index: entry.displayIdx,
                    url: (item.url && item.url.indexOf('data:') !== 0) ? item.url : '',
                    dataUrl: inlineUrl,
                    alt: item.alt,
                    width: item.width,
                    height: item.height,
                    isFormula: item.isFormula
                  });
                }

                var svgTasks = [];
                for (var si = 0; si < activeImageEntries.length; si++) {
                  var sEntry = activeImageEntries[si];
                  if (sEntry.item && sEntry.item.isSvg && sEntry.item.svgXml) {
                    svgTasks.push({
                      displayIdx: sEntry.displayIdx,
                      svgXml: sEntry.item.svgXml,
                      width: sEntry.item.width,
                      height: sEntry.item.height
                    });
                    if (svgTasks.length >= 4) break;
                  }
                }

                var svgJobId = '';
                if (svgTasks.length > 0 && typeof Image !== 'undefined' && document.createElement) {
                  svgJobId = 'scx_svg_' + Date.now() + '_' + Math.floor(Math.random() * 100000);
                  window.__scxSvgJobs = window.__scxSvgJobs || {};
                  var svgJob = { done: false, results: {}, pending: svgTasks.length };
                  window.__scxSvgJobs[svgJobId] = svgJob;

                  setTimeout(function() {
                    try {
                      svgJob.done = true;
                    } catch (eT1) {}
                  }, 900);
                  setTimeout(function() {
                    try {
                      if (window.__scxSvgJobs) delete window.__scxSvgJobs[svgJobId];
                    } catch (eT2) {}
                  }, 15000);

                  for (var ti = 0; ti < svgTasks.length; ti++) {
                    (function(task) {
                      function finishTask() {
                        svgJob.pending--;
                        if (svgJob.pending <= 0) {
                          svgJob.done = true;
                        }
                      }
                      try {
                        var img = new Image();
                        img.onload = function() {
                          try {
                            var srcW = task.width || img.naturalWidth || img.width || 120;
                            var srcH = task.height || img.naturalHeight || img.height || 80;
                            var scale = 1;
                            var minSide = Math.min(srcW, srcH);
                            if (minSide > 0 && minSide < 36) {
                              scale = Math.min(3, Math.ceil(40 / minSide));
                            } else if (minSide > 0 && Math.max(srcW, srcH) <= 400) {
                              scale = 2;
                            }
                            var dstW = Math.max(1, Math.round(srcW * scale));
                            var dstH = Math.max(1, Math.round(srcH * scale));
                            var maxSide = Math.max(dstW, dstH);
                            if (maxSide > 1280) {
                              var shrink = 1280 / maxSide;
                              dstW = Math.max(1, Math.round(dstW * shrink));
                              dstH = Math.max(1, Math.round(dstH * shrink));
                            }
                            var canvas = document.createElement('canvas');
                            canvas.width = dstW;
                            canvas.height = dstH;
                            var ctx = canvas.getContext('2d');
                            if (ctx) {
                              ctx.fillStyle = '#FFFFFF';
                              ctx.fillRect(0, 0, dstW, dstH);
                              ctx.drawImage(img, 0, 0, dstW, dstH);
                              var pngUrl = canvas.toDataURL('image/png');
                              if (pngUrl && pngUrl.length > maxInlinePerImg) {
                                pngUrl = canvas.toDataURL('image/jpeg', 0.85);
                              }
                              if (pngUrl && pngUrl.length > maxInlinePerImg) {
                                pngUrl = canvas.toDataURL('image/jpeg', 0.72);
                              }
                              if (pngUrl && pngUrl.length <= maxInlinePerImg) {
                                svgJob.results[String(task.displayIdx)] = pngUrl;
                              }
                            }
                          } catch (eDraw) {}
                          finishTask();
                        };
                        img.onerror = function() {
                          finishTask();
                        };
                        img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(task.svgXml);
                      } catch (eInit) {
                        finishTask();
                      }
                    })(svgTasks[ti]);
                  }
                }

                if (finalSelected.length > maxLen) finalSelected = finalSelected.slice(0, maxLen);
                if (finalBody.length > maxLen) finalBody = finalBody.slice(0, maxLen);
                return JSON.stringify({
                  url: String(location.href || ''),
                  title: String(document.title || ''),
                  selectedText: finalSelected,
                  bodyText: finalBody,
                  crossOriginIframeCount: crossCount,
                  hasObfuscatedFont: !!hasObfuscatedFont,
                  svgJobId: svgJobId,
                  extractedQuestions: finalQuestions,
                  images: finalImages
                });
              } catch (e) {
                return JSON.stringify({
                  url: String(location.href || ''),
                  title: String(document.title || ''),
                  selectedText: '',
                  bodyText: '',
                  crossOriginIframeCount: 0,
                  hasObfuscatedFont: false,
                  svgJobId: '',
                  extractedQuestions: [],
                  images: []
                });
              }
            })();
        """.trimIndent()
    }
}
