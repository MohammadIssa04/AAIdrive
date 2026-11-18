package com.musik.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SplashScreen() {
    // clean: same dark colour as Android's own start screen, the logo fades in once, the name follows. Nothing moves around.
    val logo = remember { Animatable(0f) }
    val name = remember { Animatable(0f) }
    LaunchedEffect(Unit) { logo.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); name.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxSize().background(Color(0xFF0E0E12)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(84.dp).graphicsLayer { alpha = logo.value }.clip(RoundedCornerShape(22.dp))) { drawLogo() }
            Spacer(Modifier.height(18.dp))
            Text("Mohammad Issa", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 3.sp,
                modifier = Modifier.graphicsLayer { alpha = name.value })
        }
    }
}

/** The Issa Music logo (same as the app icon), drawn in a 108×108 grid. */
private fun DrawScope.drawLogo(phase: Float = 0f) {
    val k = size.width / 108f * 1.25f
    val o = size.width / 2 - 54f * k
    fun p(x: Float, y: Float) = Offset(o + x * k, o + y * k)
    drawRect(Brush.linearGradient(listOf(Color(0xFF6A4CFF), Color(0xFF9B4DFF), Color(0xFFFF4FA3)), Offset(0f, 0f), Offset(size.width, size.height)))
    val w = 3.2f * k
    // sound waves
    listOf(Triple(20f, 14f, 0.55f), Triple(28f, 20f, 0.3f)).forEachIndexed { wi, (r, hy, base) ->
        val alpha = if (wi == 0) 0.9f else 0.55f
        val sx = if (r == 20f) 74f else 80f
        val cx = sx - kotlin.math.sqrt(r * r - hy * hy)
        val ang = Math.toDegrees(kotlin.math.asin((hy / r).toDouble())).toFloat()
        val c = p(cx, 54f); val rr = r * k
        drawArc(Color.White.copy(alpha = alpha), -ang, 2 * ang, false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(w, cap = StrokeCap.Round))
    }
    drawCircle(Color.White, 23f * k, p(54f, 54f))
    drawPath(Path().apply { val a = p(48f, 43f); moveTo(a.x, a.y); val b = p(66f, 54f); lineTo(b.x, b.y); val c = p(48f, 65f); lineTo(c.x, c.y); close() }, Color(0xFF7A4DFF))
}
