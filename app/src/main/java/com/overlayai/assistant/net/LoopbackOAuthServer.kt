package com.overlayai.assistant.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

data class LoopbackOAuthCallback(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?
)

/**
 * Lightweight temporary HTTP server bound strictly to 127.0.0.1 to receive
 * browser OAuth authorization_code callbacks (used by ChatGPT/Codex OAuth PKCE).
 */
class LoopbackOAuthServer(
    port: Int = 1455,
    private val callbackPath: String = "/auth/callback",
    private val redirectHost: String = "127.0.0.1"
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    private val deferred = CompletableDeferred<LoopbackOAuthCallback>()
    @Volatile
    private var acceptThread: Thread? = null

    private val socket: ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
    }

    val boundPort: Int
        get() = socket.localPort

    val redirectUri: String
        get() = "http://$redirectHost:$boundPort$callbackPath"

    /**
     * Begins accepting loopback connections immediately so no redirect races with launchBrowser().
     */
    fun startListening(expectedState: String) {
        if (!listening.compareAndSet(false, true)) return
        acceptThread = Thread({
            acceptLoop(expectedState)
        }, "oauth-loopback-$boundPort").apply {
            isDaemon = true
            start()
        }
    }

    suspend fun awaitCallback(
        expectedState: String,
        timeoutMs: Long = 5 * 60_000L
    ): LoopbackOAuthCallback = withContext(Dispatchers.IO) {
        startListening(expectedState)
        try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: throw IllegalStateException("OAuth 浏览器登录等待回调超时（${timeoutMs / 1000}s）")
        } finally {
            close()
        }
    }

    private fun acceptLoop(expectedState: String) {
        while (!closed.get() && !deferred.isCompleted) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (t: Throwable) {
                if (!closed.get()) deferred.completeExceptionally(t)
                break
            }

            client.use { conn ->
                conn.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return@use
                while (true) {
                    val hdr = reader.readLine() ?: break
                    if (hdr.isEmpty()) break
                }

                val target = requestLine.split(" ").getOrNull(1).orEmpty()
                val pathOnly = target.substringBefore('?')
                val query = target.substringAfter('?', "")

                if (pathOnly != callbackPath) {
                    writeHtmlResponse(
                        conn.getOutputStream(),
                        statusCode = 404,
                        statusText = "Not Found",
                        title = "Invalid Callback Path",
                        bodyText = "Expected callback at $callbackPath."
                    )
                    return@use
                }

                val params = parseQueryParams(query)
                val state = params["state"]
                val code = params["code"]
                val error = params["error"]
                val errorDesc = params["error_description"]

                if (state != expectedState) {
                    writeHtmlResponse(
                        conn.getOutputStream(),
                        statusCode = 400,
                        statusText = "Bad Request",
                        title = "State Mismatch",
                        bodyText = "Authorization state did not match. Please restart login inside the app."
                    )
                    return@use
                }

                if (code.isNullOrBlank() && error.isNullOrBlank()) {
                    writeHtmlResponse(
                        conn.getOutputStream(),
                        statusCode = 400,
                        statusText = "Bad Request",
                        title = "Missing Code",
                        bodyText = "Missing authorization code or error parameter."
                    )
                    return@use
                }

                val ok = error.isNullOrBlank() && !code.isNullOrBlank()
                writeHtmlResponse(
                    conn.getOutputStream(),
                    statusCode = 200,
                    statusText = "OK",
                    title = if (ok) "授权成功 · 可返回助手" else "授权未完成",
                    bodyText = if (ok) {
                        "登录凭据已安全传回 AI DOM 悬浮助手，你可以关闭此页面并切回应用。"
                    } else {
                        "授权失败：$error ${errorDesc.orEmpty()}".trim()
                    }
                )
                deferred.complete(
                    LoopbackOAuthCallback(
                        code = code,
                        state = state,
                        error = error,
                        errorDescription = errorDesc
                    )
                )
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            acceptThread?.interrupt()
        }
    }

    companion object {
        internal fun parseQueryParams(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
                val rawVal = if (eq >= 0) pair.substring(eq + 1) else ""
                val key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name())
                out[key] = value
            }
            return out
        }

        private fun writeHtmlResponse(
            out: java.io.OutputStream,
            statusCode: Int,
            statusText: String,
            title: String,
            bodyText: String
        ) {
            val safeTitle = escapeHtml(title)
            val safeBody = escapeHtml(bodyText)
            val html = """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>$safeTitle</title>
                  <style>
                    body{font-family:system-ui,-apple-system,sans-serif;background:#F7F7F7;color:#111827;display:grid;place-items:center;min-height:92vh;margin:0;padding:24px;}
                    .card{background:#fff;border-radius:18px;padding:28px;max-width:440px;box-shadow:0 8px 30px rgba(0,0,0,.08);}
                    h1{font-size:20px;margin:0 0 12px;}
                    p{font-size:15px;line-height:1.6;color:#4B5563;margin:0;}
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>$safeTitle</h1>
                    <p>$safeBody</p>
                  </div>
                </body>
                </html>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)

            val header = (
                "HTTP/1.1 $statusCode $statusText\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${html.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)

            out.write(header)
            out.write(html)
            out.flush()
        }

        private fun escapeHtml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
