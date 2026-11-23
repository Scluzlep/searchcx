package com.scluzlep.searchcx.xposed

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.scluzlep.searchcx.data.DomSnapshot
import com.scluzlep.searchcx.ipc.DomIpcContract
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Modern Xposed / libxposed API 102 module for read-only WebView DOM & selection extraction.
 * Strictly avoids legacy `de.robv.android.xposed` APIs and never mutates target web pages.
 */
class WebViewDomModule : XposedModule() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackedWebViews = WeakHashMap<Any, Long>()
    private val activeHandles = linkedMapOf<String, XposedInterface.HookHandle>()
    private val hookedClassLoaders = WeakHashMap<ClassLoader, Boolean>()

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

    private fun handleCaptureRequest(appCtx: Context, requestId: String) {
        mainHandler.post {
            val visibleOrForeground = isProcessVisibleOrForeground(appCtx)
            resumedActivityRef?.get()?.window?.decorView?.let { trackWebViewsInTree(it) }

            val bestWebView = pickBestWebView()
            if (bestWebView == null) {
                // Only main foreground process emits a missing-WebView snapshot so background/child processes stay quiet.
                if (visibleOrForeground && !currentProcessName.contains(':')) {
                    sendSnapshot(
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
        val completed = AtomicBoolean(false)

        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                sendSnapshot(
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
                val snapshot = parseJsResult(
                    sourcePackage = appCtx.packageName,
                    fallbackUrl = fallbackUrl,
                    fallbackTitle = fallbackTitle,
                    rawResult = rawResult
                )
                sendSnapshot(appCtx, requestId, snapshot)
            }
        }

        if (webView is WebView) {
            runCatching {
                webView.evaluateJavascript(EXTRACT_DOM_JS) { raw -> onResult(raw) }
            }.onFailure { err ->
                mainHandler.removeCallbacks(timeoutRunnable)
                if (completed.compareAndSet(false, true)) {
                    sendSnapshot(
                        appCtx,
                        requestId,
                        DomSnapshot.errorSnapshot(appCtx.packageName, "读取 WebView DOM 失败：${err.message}")
                            .copy(url = fallbackUrl, title = fallbackTitle)
                    )
                }
            }
        } else {
            evaluateX5WebView(webView, EXTRACT_DOM_JS) { raw ->
                onResult(raw)
            }.onFailure { err ->
                mainHandler.removeCallbacks(timeoutRunnable)
                if (completed.compareAndSet(false, true)) {
                    sendSnapshot(
                        appCtx,
                        requestId,
                        DomSnapshot.errorSnapshot(appCtx.packageName, "读取 X5 WebView DOM 失败：${err.message}")
                            .copy(url = fallbackUrl, title = fallbackTitle)
                    )
                }
            }
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

    private fun parseJsResult(
        sourcePackage: String,
        fallbackUrl: String,
        fallbackTitle: String,
        rawResult: String?
    ): DomSnapshot {
        if (rawResult.isNullOrBlank() || rawResult == "null") {
            return DomSnapshot.errorSnapshot(sourcePackage, "WebView 返回空结果")
                .copy(url = fallbackUrl, title = fallbackTitle)
        }
        return runCatching {
            val decoded = when (val parsed = JSONTokener(rawResult).nextValue()) {
                is JSONObject -> parsed
                is String -> JSONObject(parsed)
                else -> JSONObject(rawResult)
            }
            val url = decoded.optString("url").ifBlank { fallbackUrl }
            val title = decoded.optString("title").ifBlank { fallbackTitle }
            val selected = decoded.optString("selectedText").take(DomIpcContract.MAX_BODY_CHARS)
            val body = decoded.optString("bodyText").take(DomIpcContract.MAX_BODY_CHARS)
            val iframeCount = decoded.optInt("crossOriginIframeCount", 0).coerceAtLeast(0)
            val extractedQuestions = DomSnapshot.parseQuizItems(decoded.optJSONArray("extractedQuestions"))
            val images = DomSnapshot.parseImages(decoded.optJSONArray("images"))
            DomSnapshot(
                sourcePackage = sourcePackage,
                url = url,
                title = title,
                selectedText = selected,
                bodyText = body,
                crossOriginIframeCount = iframeCount,
                capturedAtMs = System.currentTimeMillis(),
                error = null,
                extractedQuestions = extractedQuestions,
                images = images
            )
        }.getOrElse { err ->
            DomSnapshot.errorSnapshot(sourcePackage, "解析 DOM JSON 失败：${err.message}")
                .copy(url = fallbackUrl, title = fallbackTitle)
        }
    }

    private fun sendSnapshot(appCtx: Context, requestId: String, snapshot: DomSnapshot) {
        runCatching {
            val reply = Intent(DomIpcContract.ACTION_DOM_SNAPSHOT).apply {
                setPackage(DomIpcContract.ASSISTANT_PACKAGE)
                putExtra(DomIpcContract.EXTRA_REQUEST_ID, requestId)
                putExtra(DomIpcContract.EXTRA_PAYLOAD_JSON, snapshot.toJson())
            }
            appCtx.sendBroadcast(reply)
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
        private const val EVALUATE_TIMEOUT_MS = 3500L

        private val EXTRACT_DOM_JS = """
            (function() {
              try {
                var maxLen = ${DomIpcContract.MAX_BODY_CHARS};
                var maxQuestions = ${DomSnapshot.MAX_EXTRACTED_QUESTIONS};
                var maxQuestionChars = ${DomSnapshot.MAX_QUESTION_CHARS};
                var maxOptions = ${DomSnapshot.MAX_OPTIONS_PER_QUESTION};
                var maxImages = ${DomSnapshot.MAX_CAPTURED_IMAGES};
                var maxInlinePerImg = 85000;
                var maxTotalInline = 180000;
                var crossCount = 0;
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
                  var cls = typeof el.className === 'string' ? el.className : '';

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
                  var cls = typeof img.className === 'string' ? img.className : '';
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

                function isElementHidden(el) {
                  if (!el || el.nodeType !== 1) return false;
                  if (el.hidden) return true;
                  var st = el.style;
                  if (st && (st.display === 'none' || st.visibility === 'hidden')) return true;
                  try {
                    var view = el.ownerDocument && el.ownerDocument.defaultView;
                    if (view && el.isConnected) {
                      var cs = view.getComputedStyle(el);
                      if (cs && (cs.display === 'none' || cs.visibility === 'hidden')) return true;
                    }
                  } catch (eCs) {}
                  return false;
                }

                function isNoisyUiImage(img, url, w, h) {
                  var cls = typeof img.className === 'string' ? img.className : '';
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

                  var cls = typeof node.className === 'string' ? node.className : '';
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
                    rawQuestions.push({
                      question: stem,
                      options: opts,
                      type: inferType(el, stem, opts),
                      inViewport: isElementInViewport(el, doc, parentVisible)
                    });
                  }
                }

                function extractSelectionRich(doc) {
                  var win = doc && doc.defaultView ? doc.defaultView : null;
                  var selObj = (win && win.getSelection) ? win.getSelection() : (doc && doc.getSelection ? doc.getSelection() : null);
                  if (!selObj || selObj.rangeCount <= 0 || selObj.isCollapsed) return '';
                  var range = selObj.getRangeAt(0);
                  var holder = doc.createElement('div');
                  holder.appendChild(range.cloneContents());
                  return extractRichText(holder, doc, { isOption: false });
                }

                function extractDocTree(doc, parentVisible) {
                  if (!doc) return;
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
                    if (maxSide > 900) {
                      var shrink = 900 / maxSide;
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

                if (finalSelected.length > maxLen) finalSelected = finalSelected.slice(0, maxLen);
                if (finalBody.length > maxLen) finalBody = finalBody.slice(0, maxLen);
                return JSON.stringify({
                  url: String(location.href || ''),
                  title: String(document.title || ''),
                  selectedText: finalSelected,
                  bodyText: finalBody,
                  crossOriginIframeCount: crossCount,
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
                  extractedQuestions: [],
                  images: []
                });
              }
            })();
        """.trimIndent()
    }
}
