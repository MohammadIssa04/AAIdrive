package com.musik.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

private val JAM_A = Color(0xFF7C3AED)
private val JAM_B = Color(0xFFDB2777)
private val JAM_C = Color(0xFFF97316)

/** The Jam's background: the album cover of the song playing, blurred, with a dark wash so text stays readable. */
@Composable
fun JamBackdrop(url: String?, t: Float, modifier: Modifier = Modifier) {
    val shift = sin(t * 2 * PI.toFloat())
    Box(modifier) {
        Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(JAM_A, JAM_B, JAM_C, JAM_A), Offset(-400f + 400f * shift, 0f), Offset(900f + 400f * shift, 500f))))
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().graphicsLayer { scaleX = 1.35f + 0.05f * shift; scaleY = 1.35f + 0.05f * shift }
                .then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(28.dp) else Modifier.alpha(0.45f)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0x99000000)))))
    }
}

/* ================= Home banner ================= */

@Composable
fun JamBanner(vm: AppVm) {
    val social = vm.social ?: return
    val api = vm.api ?: return
    val inf = rememberInfiniteTransition(label = "jamBanner")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "t")
    if (vm.inJam) {
        val j = vm.jam
        val cur = j?.queue?.getOrNull(j.index)
        val people = (listOfNotNull(j?.host) + (j?.members ?: emptyList())).distinct()
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { vm.showJam = true }) {
            JamBackdrop(api.coverUrl(cur?.coverArt, 300), t, Modifier.matchParentSize())
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (vm.isJamHost) "You're hosting a Jam" else "${j?.hostDisplay ?: "A"}'s Jam", fontWeight = FontWeight.Black, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Queue ›", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xDDFFFFFF))
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(api.coverUrl(cur?.coverArt, 200), 64.dp, RoundedCornerShape(12.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(cur?.title ?: "Nothing playing yet", fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cur?.artist ?: "Add songs to the queue", color = Color(0xFFEDE7F6), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            people.take(5).forEachIndexed { i, n ->
                                Box(Modifier.offset(x = (-8 * i).dp).border(2.dp, Color.White, CircleShape)) {
                                    Avatar(social.avatarUrl(n, if (n == social.me) vm.myProfile?.avatarTs ?: 0 else vm.friends.firstOrNull { it.name == n }?.avatarTs ?: 0), vm.nameOf(n), 24.dp)
                                }
                            }
                            Text("${people.size} listening", fontSize = 12.sp, color = Color(0xFFEDE7F6), modifier = Modifier.offset(x = (-8 * (people.size.coerceAtMost(5) - 1) + 6).dp))
                        }
                    }
                }
                JamSkipBar(vm, compact = true)
            }
        }
    } else vm.jamInvites.forEach { inv ->
        val cur = inv.queue.getOrNull(inv.index)
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
            JamBackdrop(api.coverUrl(cur?.coverArt, 300), t, Modifier.matchParentSize())
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(social.avatarUrl(inv.host, vm.friends.firstOrNull { it.name == inv.host }?.avatarTs ?: 0), inv.hostDisplay, 48.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("${inv.hostDisplay} started a Jam", fontWeight = FontWeight.Black)
                    Text(cur?.let { "♫ ${it.title} – ${it.artist}" } ?: "Listen together in sync", color = Color(0xFFEDE7F6), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button({ vm.joinJam(inv.host) }, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Join", fontWeight = FontWeight.Bold) }
            }
        }
    }
}



/* ================= skip requests ================= */

/** Guests: "Ask to skip". Host: "Salim wants to skip – Skip / Keep". */
@Composable
fun JamSkipBar(vm: AppVm, compact: Boolean = false) {
    if (!vm.inJam) return
    val ask = vm.skipAsk
    if (vm.isJamHost && ask != null) {
        Row(Modifier.padding(top = if (compact) 10.dp else 6.dp).padding(horizontal = if (compact) 0.dp else 20.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color(0x55000000)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⏭", fontSize = 18.sp)
            Text("${ask.names.joinToString(" & ")} want${if (ask.names.size == 1) "s" else ""} to skip", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton({ vm.answerSkip(false) }) { Text("Keep", color = Color(0xDDFFFFFF)) }
            Button({ vm.answerSkip(true) }, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Skip", fontWeight = FontWeight.Bold) }
        }
    } else if (vm.isJamGuest) {
        val can = vm.canAskSkip
        Row(Modifier.padding(top = if (compact) 10.dp else 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x40FFFFFF)).clickable(enabled = can) { vm.askSkip() }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("⏭", fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(if (can) "Ask ${vm.jam?.hostDisplay ?: "the host"} to skip" else "Skip asked ✓", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}
