package com.overlayai.assistant.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class PixelCropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

object ImageCropUtils {
    fun clampRect(rect: NormalizedCropRect, minSpan: Float = 0.06f): NormalizedCropRect {
        val rawLeft = rect.left.coerceIn(0f, 1f - minSpan)
        val rawTop = rect.top.coerceIn(0f, 1f - minSpan)
        val rawRight = rect.right.coerceIn(rawLeft + minSpan, 1f)
        val rawBottom = rect.bottom.coerceIn(rawTop + minSpan, 1f)
        return NormalizedCropRect(rawLeft, rawTop, rawRight, rawBottom)
    }

    fun toPixelBounds(rect: NormalizedCropRect, imgW: Int, imgH: Int): PixelCropRect {
        require(imgW > 0 && imgH > 0) { "Invalid image size: ${imgW}x${imgH}" }
        val clamped = clampRect(rect)
        val l = (clamped.left * imgW).roundToInt().coerceIn(0, imgW - 1)
        val t = (clamped.top * imgH).roundToInt().coerceIn(0, imgH - 1)
        val r = (clamped.right * imgW).roundToInt().coerceIn(l + 1, imgW)
        val b = (clamped.bottom * imgH).roundToInt().coerceIn(t + 1, imgH)
        return PixelCropRect(left = l, top = t, width = r - l, height = b - t)
    }

    fun cropAndEncodeJpeg(
        source: Bitmap,
        rect: NormalizedCropRect,
        maxEdgePx: Int = 1600,
        quality: Int = 85
    ): Pair<Bitmap, String> {
        val bounds = toPixelBounds(rect, source.width, source.height)
        val cropped = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width, bounds.height)
        val longest = maxOf(cropped.width, cropped.height)
        var finalBmp = if (longest > maxEdgePx) {
            val scale = maxEdgePx.toFloat() / longest.toFloat()
            Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * scale).roundToInt().coerceAtLeast(1),
                (cropped.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            cropped
        }
        if (finalBmp === source) {
            finalBmp = source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val baos = ByteArrayOutputStream()
        finalBmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 95), baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return finalBmp to b64
    }
}

class ScreenCaptureHelper(
    context: Context,
    private val onProjectionRevoked: () -> Unit
) {
    private val appContext = context.applicationContext
    private val projectionManager =
        appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    @Volatile
    private var mediaProjection: MediaProjection? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mediaProjection = null
            onProjectionRevoked()
        }
    }

    fun hasProjection(): Boolean = mediaProjection != null

    fun setProjectionResult(resultCode: Int, data: Intent) {
        release()
        val projection = projectionManager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("系统未返回有效的 MediaProjection 对象")
        projection.registerCallback(projectionCallback, Handler(appContext.mainLooper))
        mediaProjection = projection
    }

    fun release() {
        val mp = mediaProjection
        mediaProjection = null
        if (mp != null) {
            runCatching { mp.unregisterCallback(projectionCallback) }
            runCatching { mp.stop() }
        }
    }

    suspend fun captureSingleFrame(width: Int, height: Int, densityDpi: Int): Bitmap =
        withContext(Dispatchers.IO) {
            val projection = mediaProjection
                ?: throw IllegalStateException("尚未授予屏幕捕获权限，请先在弹窗中允许录屏。")
            require(width > 0 && height > 0) { "无效屏幕尺寸: ${width}x${height}" }

            val thread = HandlerThread("overlay-screen-cap").apply { start() }
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val frameDeferred = CompletableDeferred<Bitmap>()
            var virtualDisplay: VirtualDisplay? = null

            reader.setOnImageAvailableListener({ r ->
                if (frameDeferred.isCompleted) return@setOnImageAvailableListener
                val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val rawBmp = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    rawBmp.copyPixelsFromBuffer(plane.buffer)
                    val clean = Bitmap.createBitmap(rawBmp, 0, 0, width, height)
                    if (clean !== rawBmp) rawBmp.recycle()
                    frameDeferred.complete(clean)
                } catch (t: Throwable) {
                    if (!frameDeferred.isCompleted) frameDeferred.completeExceptionally(t)
                } finally {
                    image.close()
                }
            }, handler)

            try {
                virtualDisplay = projection.createVirtualDisplay(
                    "OverlayAiCapture",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    handler
                )
                withTimeoutOrNull(3_000L) { frameDeferred.await() }
                    ?: throw IllegalStateException("屏幕捕获超时（3s 内未收到帧），请重试或重新授权。")
            } finally {
                runCatching { virtualDisplay?.release() }
                runCatching { reader.close() }
                runCatching { thread.quitSafely() }
            }
        }
}
