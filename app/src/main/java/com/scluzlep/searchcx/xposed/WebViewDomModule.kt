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
            DomSnapshot(
                sourcePackage = sourcePackage,
                url = url,
                title = title,
                selectedText = selected,
                bodyText = body,
                crossOriginIframeCount = iframeCount,
                capturedAtMs = System.currentTimeMillis(),
                error = null,
                extractedQuestions = extractedQuestions
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
        private const val EVALUATE_TIMEOUT_MS = 2500L

        private val EXTRACT_DOM_JS = """
            (function() {
              try {
                var maxLen = ${DomIpcContract.MAX_BODY_CHARS};
                var maxQuestions = ${DomSnapshot.MAX_EXTRACTED_QUESTIONS};
                var maxQuestionChars = ${DomSnapshot.MAX_QUESTION_CHARS};
                var maxOptions = ${DomSnapshot.MAX_OPTIONS_PER_QUESTION};
                var crossCount = 0;
                var selObj = window.getSelection ? window.getSelection() : null;
                var selected = selObj ? String(selObj.toString() || '').trim() : '';
                var chunks = [];
                var extractedQuestions = [];
                var seenQuestions = {};

                var containerSelector = '.TiMu, .questionLi, .singleQuesId, .examPaper_subject, .subject_describe, .topic-item, .question-item, [class*="question-card"], [class*="ques-item"]';
                var stemSelectors = ['.clearfix > .fl', '.mark_name', '.qtContent', '.subject_stem', '.question-stem', '.topic-title', 'h3', 'h4'];
                var optionSelectors = ['.answerBg .answer_p', '.mark_answer li', '.option-item', '[class*="option"]', 'li', 'label'];

                function cleanText(s) {
                  return String(s || '').replace(/\r/g, '').replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
                }

                function stripOptionPrefix(s) {
                  return cleanText(s).replace(/^[A-Ha-h][.．:：、)）]\s*/, '').trim();
                }

                function isJudgementPair(opts) {
                  if (!opts || opts.length !== 2) return false;
                  var a = opts[0].toLowerCase().trim();
                  var b = opts[1].toLowerCase().trim();
                  var pairs = [
                    ['正确', '错误'],
                    ['对', '错'],
                    ['true', 'false'],
                    ['t', 'f'],
                    ['√', '×'],
                    ['是', '否'],
                    ['yes', 'no']
                  ];
                  for (var p = 0; p < pairs.length; p++) {
                    var x = pairs[p][0];
                    var y = pairs[p][1];
                    if ((a === x && b === y) || (a === y && b === x)) return true;
                  }
                  return false;
                }

                function extractOptions(el) {
                  var opts = [];
                  for (var s = 0; s < optionSelectors.length; s++) {
                    var sel = optionSelectors[s];
                    var nodes = el.querySelectorAll(sel);
                    if (!nodes || nodes.length === 0) continue;
                    var candidateOpts = [];
                    var seenOpt = {};
                    for (var j = 0; j < nodes.length; j++) {
                      var node = nodes[j];
                      // Skip wrapper containers that themselves contain nested option elements.
                      if (sel === '[class*="option"]' && node.querySelector('[class*="option"], li, label')) {
                        continue;
                      }
                      var txt = stripOptionPrefix(node.innerText || node.textContent || '');
                      if (txt && !seenOpt[txt]) {
                        seenOpt[txt] = true;
                        candidateOpts.push(txt);
                        if (candidateOpts.length >= maxOptions) break;
                      }
                    }
                    var isGeneric = (sel === 'li' || sel === 'label');
                    if (candidateOpts.length >= (isGeneric ? 2 : 1)) {
                      opts = candidateOpts;
                      break;
                    }
                  }
                  return opts;
                }

                function extractStem(el, opts) {
                  for (var i = 0; i < stemSelectors.length; i++) {
                    var found = el.querySelector(stemSelectors[i]);
                    if (found) {
                      var t = cleanText(found.innerText || found.textContent || '');
                      if (t) return t;
                    }
                  }
                  var full = cleanText(el.innerText || el.textContent || '');
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
                    if (stemLines.length >= 6) break;
                  }
                  return stemLines.join('\n').trim() || lines[0].trim();
                }

                function inferType(el, stem, opts) {
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

                function extractQuestionsFromDoc(doc) {
                  if (!doc || extractedQuestions.length >= maxQuestions) return;
                  var containers = doc.querySelectorAll(containerSelector);
                  if (!containers || containers.length === 0) return;
                  var capturedEls = [];
                  for (var i = 0; i < containers.length; i++) {
                    if (extractedQuestions.length >= maxQuestions) break;
                    var el = containers[i];
                    var nested = false;
                    for (var c = 0; c < capturedEls.length; c++) {
                      if (capturedEls[c] === el || capturedEls[c].contains(el)) {
                        nested = true;
                        break;
                      }
                    }
                    if (nested) continue;
                    var opts = extractOptions(el);
                    var stem = extractStem(el, opts);
                    if (!stem) continue;
                    if (stem.length > maxQuestionChars) stem = stem.slice(0, maxQuestionChars);
                    if (seenQuestions[stem]) continue;
                    seenQuestions[stem] = true;
                    capturedEls.push(el);
                    extractedQuestions.push({
                      question: stem,
                      options: opts,
                      type: inferType(el, stem, opts)
                    });
                  }
                }

                function extractDocText(doc) {
                  if (!doc) return;
                  try {
                    if (!selected && doc.defaultView && doc.defaultView.getSelection) {
                      var frameSel = String(doc.defaultView.getSelection().toString() || '').trim();
                      if (frameSel) selected = frameSel;
                    }
                  } catch (eSel) {}
                  try {
                    var body = doc.body || doc.documentElement;
                    if (body) {
                      var t = String(body.innerText || body.textContent || '').trim();
                      if (t) chunks.push(t);
                    }
                  } catch (eBody) {}
                  try {
                    extractQuestionsFromDoc(doc);
                  } catch (eQuiz) {}
                  try {
                    var frames = doc.querySelectorAll('iframe, frame');
                    for (var i = 0; i < frames.length; i++) {
                      try {
                        var childDoc = frames[i].contentDocument || (frames[i].contentWindow && frames[i].contentWindow.document);
                        if (childDoc) {
                          extractDocText(childDoc);
                        } else {
                          crossCount++;
                        }
                      } catch (eFrame) {
                        crossCount++;
                      }
                    }
                  } catch (eList) {}
                }
                extractDocText(document);
                var bodyText = chunks.join('\n\n').replace(/\r/g, '').trim();
                if (selected.length > maxLen) selected = selected.slice(0, maxLen);
                if (bodyText.length > maxLen) bodyText = bodyText.slice(0, maxLen);
                return JSON.stringify({
                  url: String(location.href || ''),
                  title: String(document.title || ''),
                  selectedText: selected,
                  bodyText: bodyText,
                  crossOriginIframeCount: crossCount,
                  extractedQuestions: extractedQuestions
                });
              } catch (e) {
                return JSON.stringify({
                  url: String(location.href || ''),
                  title: String(document.title || ''),
                  selectedText: '',
                  bodyText: '',
                  crossOriginIframeCount: 0,
                  extractedQuestions: []
                });
              }
            })();
        """.trimIndent()
    }
}
