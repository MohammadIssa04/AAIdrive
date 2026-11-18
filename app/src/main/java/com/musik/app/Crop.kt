package com.musik.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads an image, scaled down to at most [max] px and turned the right way up. */
private fun loadOriented(ctx: android.content.Context, uri: Uri, max: Int = 2048): Bitmap? {
    val cr = ctx.contentResolver
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // Note: decoding with inJustDecodeBounds always returns null, so only the stream itself is checked here.
    val input = cr.openInputStream(uri) ?: return null
    input.use { BitmapFactory.decodeStream(it, null, b) }
    if (b.outWidth <= 0 || b.outHeight <= 0) return null
    var sample = 1
    while (b.outWidth / (sample * 2) >= max || b.outHeight / (sample * 2) >= max) sample *= 2
    val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    val deg = try {
        cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
        } ?: 0f
    } catch (_: Exception) { 0f }
    return if (deg == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
}

/** Renders exactly what's inside the crop frame into an [outW]x[outH] bitmap. */
private fun renderCrop(src: Bitmap, boxW: Float, boxH: Float, scale: Float, offset: Offset, rotation: Float, outW: Int, outH: Int): Bitmap {
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(android.graphics.Color.BLACK)
    val k = outW / boxW
    val turned = ((rotation / 90f).toInt() % 2 + 2) % 2 == 1 && rotation % 90f == 0f
    val base = if (turned) maxOf(boxW / src.height, boxH / src.width) else maxOf(boxW / src.width, boxH / src.height)
    c.translate(outW / 2f + offset.x * k, outH / 2f + offset.y * k)
    c.rotate(rotation)
    c.scale(base * scale * k, base * scale * k)
    c.drawBitmap(src, -src.width / 2f, -src.height / 2f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return out
}

@Composable
fun AvatarCropper(uri: Uri, onCancel: () -> Unit, onSave: (Bitmap) -> Unit) = ImageCropper(uri, 1f, true, 512, 512, onCancel, onSave)

/** Move, zoom and turn a picture inside a frame ([ratio] = width / height; round = avatar circle).
 *  The picture always fills the frame – it can't be dragged off or made too small, so it never "lands wrong". */
@Composable
fun ImageCropper(uri: Uri, ratio: Float, round: Boolean, outW: Int, outH: Int, onCancel: () -> Unit, onSave: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var quarter by remember { mutableIntStateOf(0) } // 0..3 × 90°
    var boxW by remember { mutableFloatStateOf(1f) }
    var boxH by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(uri) {
        val b = withContext(Dispatchers.IO) { try { loadOriented(ctx, uri) } catch (_: Exception) { null } }
        if (b == null) failed = true else { src = b; img = b.asImageBitmap() }
    }
    // keep the picture covering the whole frame
    fun clamp(o: Offset, sc: Float): Offset {
        val b = img ?: return o
        val (w, h) = if (quarter % 2 == 1) b.height.toFloat() to b.width.toFloat() else b.width.toFloat() to b.height.toFloat()
        val base = maxOf(boxW / w, boxH / h)
        val mx = ((w * base * sc - boxW) / 2f).coerceAtLeast(0f); val my = ((h * base * sc - boxH) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onCancel) { Text("Cancel", color = Dim) }
                Text(if (round) "Profile picture" else "Choose the part to show", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton({ src?.let { onSave(renderCrop(it, boxW, boxH, scale, offset, quarter * 90f, outW, outH)) } }, enabled = src != null) { Text("Done", fontWeight = FontWeight.Bold, color = Tint.light) }
            }
            Text("Drag to move • pinch to zoom • double-tap to reset", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 16.dp))
            Box(
                Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(if (round) 0.dp else 14.dp))
                    .onSizeChanged { boxW = it.width.toFloat(); boxH = it.height.toFloat() }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f); offset = clamp(offset + pan, scale)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val bmp = img
                if (bmp == null) { if (failed) Text("Couldn't open this picture", color = Bad) else CircularProgressIndicator(color = Tint.accent) }
                else Canvas(Modifier.fillMaxSize()) {
                    val (w, h) = if (quarter % 2 == 1) bmp.height.toFloat() to bmp.width.toFloat() else bmp.width.toFloat() to bmp.height.toFloat()
                    val base = maxOf(size.width / w, size.height / h)
                    translate(size.width / 2 + offset.x, size.height / 2 + offset.y) {
                        rotate(quarter * 90f, pivot = Offset.Zero) {
                            scale(base * scale, base * scale, pivot = Offset.Zero) {
                                drawImage(bmp, topLeft = Offset(-bmp.width / 2f, -bmp.height / 2f))
                            }
                        }
                    }
                    if (round) {
                        val r = size.minDimension / 2 - 8.dp.toPx()
                        val mask = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(0f, 0f, size.width, size.height))
                            addOval(Rect(center.x - r, center.y - r, center.x + r, center.y + r))
                        }
                        drawPath(mask, Color(0xB0000000))
                        drawCircle(Color.White.copy(alpha = 0.8f), r, center, style = Stroke(2.dp.toPx()))
                    } else {
                        // thirds grid, like a camera, to help placing things
                        val g = Color.White.copy(alpha = 0.25f)
                        for (k in 1..2) {
                            drawLine(g, Offset(size.width * k / 3f, 0f), Offset(size.width * k / 3f, size.height), 1.dp.toPx())
                            drawLine(g, Offset(0f, size.height * k / 3f), Offset(size.width, size.height * k / 3f), 1.dp.toPx())
                        }
                        drawRect(Color.White.copy(alpha = 0.8f), style = Stroke(2.dp.toPx()))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Zoom", color = Dim, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                Slider(scale, { scale = it; offset = clamp(offset, it) }, valueRange = 1f..6f, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Tint.accent, inactiveTrackColor = Card2))
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ quarter = (quarter + 1) % 4; offset = Offset.Zero }, shape = RoundedCornerShape(50)) { Text("↻ Turn", color = Color.White) }
                OutlinedButton({ scale = 1f; offset = Offset.Zero; quarter = 0 }, shape = RoundedCornerShape(50)) { Text("Reset", color = Color.White) }
            }
        }
    }
}
