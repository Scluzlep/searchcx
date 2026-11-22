package com.scluzlep.searchcx.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import kotlin.math.abs

private enum class DragMode {
    NONE,
    MOVE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    NEW_RECT
}

@Composable
fun CropOverlayComposable(
    screenshot: Bitmap,
    onConfirmCrop: (NormalizedCropRect) -> Unit,
    onSendFullScreen: () -> Unit,
    onCancel: () -> Unit
) {
    val imageBitmap = remember(screenshot) { screenshot.asImageBitmap() }
    var crop by remember {
        mutableStateOf(NormalizedCropRect(left = 0.06f, top = 0.22f, right = 0.94f, bottom = 0.56f))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val areaW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val areaH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
            val handleRadiusPx = with(density) { 28.dp.toPx() }

            Image(
                bitmap = imageBitmap,
                contentDescription = "Captured screen",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )

            var dragMode by remember { mutableStateOf(DragMode.NONE) }
            var dragAnchor by remember { mutableStateOf(Offset.Zero) }
            var startCrop by remember { mutableStateOf(crop) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(areaW, areaH) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                startCrop = crop
                                dragAnchor = pos
                                val l = crop.left * areaW
                                val t = crop.top * areaH
                                val r = crop.right * areaW
                                val b = crop.bottom * areaH

                                dragMode = when {
                                    abs(pos.x - l) <= handleRadiusPx && abs(pos.y - t) <= handleRadiusPx ->
                                        DragMode.TOP_LEFT
                                    abs(pos.x - r) <= handleRadiusPx && abs(pos.y - t) <= handleRadiusPx ->
                                        DragMode.TOP_RIGHT
                                    abs(pos.x - l) <= handleRadiusPx && abs(pos.y - b) <= handleRadiusPx ->
                                        DragMode.BOTTOM_LEFT
                                    abs(pos.x - r) <= handleRadiusPx && abs(pos.y - b) <= handleRadiusPx ->
                                        DragMode.BOTTOM_RIGHT
                                    pos.x in l..r && pos.y in t..b -> DragMode.MOVE
                                    else -> DragMode.NEW_RECT
                                }
                            },
                            onDragEnd = { dragMode = DragMode.NONE },
                            onDragCancel = { dragMode = DragMode.NONE },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x / areaW
                                val dy = dragAmount.y / areaH
                                val minSpan = 0.08f
                                crop = when (dragMode) {
                                    DragMode.MOVE -> {
                                        val w = crop.right - crop.left
                                        val h = crop.bottom - crop.top
                                        val nl = (crop.left + dx).coerceIn(0f, 1f - w)
                                        val nt = (crop.top + dy).coerceIn(0f, 1f - h)
                                        NormalizedCropRect(nl, nt, nl + w, nt + h)
                                    }
                                    DragMode.TOP_LEFT -> NormalizedCropRect(
                                        left = (crop.left + dx).coerceIn(0f, crop.right - minSpan),
                                        top = (crop.top + dy).coerceIn(0f, crop.bottom - minSpan),
                                        right = crop.right,
                                        bottom = crop.bottom
                                    )
                                    DragMode.TOP_RIGHT -> NormalizedCropRect(
                                        left = crop.left,
                                        top = (crop.top + dy).coerceIn(0f, crop.bottom - minSpan),
                                        right = (crop.right + dx).coerceIn(crop.left + minSpan, 1f),
                                        bottom = crop.bottom
                                    )
                                    DragMode.BOTTOM_LEFT -> NormalizedCropRect(
                                        left = (crop.left + dx).coerceIn(0f, crop.right - minSpan),
                                        top = crop.top,
                                        right = crop.right,
                                        bottom = (crop.bottom + dy).coerceIn(crop.top + minSpan, 1f)
                                    )
                                    DragMode.BOTTOM_RIGHT -> NormalizedCropRect(
                                        left = crop.left,
                                        top = crop.top,
                                        right = (crop.right + dx).coerceIn(crop.left + minSpan, 1f),
                                        bottom = (crop.bottom + dy).coerceIn(crop.top + minSpan, 1f)
                                    )
                                    DragMode.NEW_RECT -> {
                                        val curX = (change.position.x / areaW).coerceIn(0f, 1f)
                                        val curY = (change.position.y / areaH).coerceIn(0f, 1f)
                                        val ancX = (dragAnchor.x / areaW).coerceIn(0f, 1f)
                                        val ancY = (dragAnchor.y / areaH).coerceIn(0f, 1f)
                                        ImageCropUtils.clampRect(
                                            NormalizedCropRect(
                                                left = minOf(ancX, curX),
                                                top = minOf(ancY, curY),
                                                right = maxOf(ancX, curX),
                                                bottom = maxOf(ancY, curY)
                                            ),
                                            minSpan = minSpan
                                        )
                                    }
                                    DragMode.NONE -> crop
                                }
                            }
                        )
                    }
            ) {
                val rectPx = Rect(
                    left = crop.left * size.width,
                    top = crop.top * size.height,
                    right = crop.right * size.width,
                    bottom = crop.bottom * size.height
                )
                val holePath = Path().apply { addRect(rectPx) }
                clipPath(holePath, clipOp = ClipOp.Difference) {
                    drawRect(Color.Black.copy(alpha = 0.56f))
                }

                // Crisp border + subtle rule-of-thirds grid
                drawRect(
                    color = Color.White.copy(alpha = 0.92f),
                    topLeft = rectPx.topLeft,
                    size = rectPx.size,
                    style = Stroke(width = 2.dp.toPx())
                )
                val thirdW = rectPx.width / 3f
                val thirdH = rectPx.height / 3f
                for (i in 1..2) {
                    drawLine(
                        Color.White.copy(alpha = 0.25f),
                        start = Offset(rectPx.left + thirdW * i, rectPx.top),
                        end = Offset(rectPx.left + thirdW * i, rectPx.bottom),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.25f),
                        start = Offset(rectPx.left, rectPx.top + thirdH * i),
                        end = Offset(rectPx.right, rectPx.top + thirdH * i),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Blue corner L-handles
                val handleLen = 18.dp.toPx()
                val handleStroke = 4.dp.toPx()
                val accent = Color(0xFF3482FF)
                val corners = listOf(
                    Offset(rectPx.left, rectPx.top) to Pair(1f, 1f),
                    Offset(rectPx.right, rectPx.top) to Pair(-1f, 1f),
                    Offset(rectPx.left, rectPx.bottom) to Pair(1f, -1f),
                    Offset(rectPx.right, rectPx.bottom) to Pair(-1f, -1f)
                )
                for ((corner, dir) in corners) {
                    drawLine(
                        color = accent,
                        start = corner,
                        end = Offset(corner.x + handleLen * dir.first, corner.y),
                        strokeWidth = handleStroke
                    )
                    drawLine(
                        color = accent,
                        start = corner,
                        end = Offset(corner.x, corner.y + handleLen * dir.second),
                        strokeWidth = handleStroke
                    )
                }
            }
        }

        // Top instruction banner
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC111827))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                BasicText(
                    text = "拖动框选识别区域",
                    style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                )
            }
        }

        // Bottom university-style action bar
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xECFFFFFF))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CropActionButton(
                label = "取消",
                bg = Color(0xFFF2F2F7),
                fg = Color(0xFF1F2937),
                modifier = Modifier.weight(0.8f),
                onClick = onCancel
            )
            CropActionButton(
                label = "整屏发送",
                bg = Color(0xFFEAF2FF),
                fg = Color(0xFF1D4ED8),
                modifier = Modifier.weight(1f),
                onClick = onSendFullScreen
            )
            CropActionButton(
                label = "框选问 AI",
                bg = Color(0xFF3482FF),
                fg = Color.White,
                modifier = Modifier.weight(1.2f),
                onClick = { onConfirmCrop(crop) }
            )
        }
    }
}

@Composable
private fun CropActionButton(
    label: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        )
    }
}
