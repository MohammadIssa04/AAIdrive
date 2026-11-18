package com.musik.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()

    /** At the phone's lowest volume step the buttons keep going quieter (app volume), and come back up the same way. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (vm.opt.fineVolumeKeys && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)) {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val idx = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val o = vm.opt
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && idx <= 1 && o.volApp > 0) {
                o.volApp = (o.volApp - 5).coerceAtLeast(0); vm.toast = "App volume ${o.volApp}%"; return true
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP && idx <= 1 && o.volApp < o.volMax) {
                o.volApp = (o.volApp + 5).coerceAtMost(o.volMax); vm.toast = "App volume ${o.volApp}%"; return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var started = false
    private var stoppedAt = 0L
    override fun onStart() {
        super.onStart(); vm.appVisible = true
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 60_000) vm.clearSearch() // away for a while: Search starts fresh
        if (!started) { started = true; return } // the first start is handled while the app loads
        vm.refreshSocial(); vm.checkResume(); vm.loadMoment()
    }
    override fun onStop() { super.onStop(); vm.appVisible = false; stoppedAt = System.currentTimeMillis() }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handleSearchIntent(intent) }
    /** "Play … on Issa Music" from the Assistant: search for it. */
    private fun handleSearchIntent(i: android.content.Intent?) {
        if (i?.action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val q = i.getStringExtra(android.app.SearchManager.QUERY)?.trim().orEmpty()
        if (q.isNotEmpty()) { vm.query = q; vm.go(Screen.Search) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // notifications, and "Nearby devices" so the music can start by itself when you get in the car
        val ask = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31 && getSharedPreferences("musik", MODE_PRIVATE).getBoolean("car_autoplay", true)) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (ask.isNotEmpty()) registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(ask.toTypedArray())
        val cold = savedInstanceState == null
        handleSearchIntent(intent)
        Th.apply(vm.opt.themeStyle)
        setContent {
            val base = androidx.compose.ui.graphics.Color(vm.accentColor)
            val cur = vm.shown?.toSong()?.coverArt
            val vib = if (vm.opt.dynamicAccent && cur != null) rememberVibrant(vm.api?.coverUrl(cur, 120)) else null
            val target = styleAccent(vib ?: base, Th.style)
            val accent by animateColorAsState(target, tween(if (vm.opt.transitions) 800 else 0), label = "accent")
            MusikTheme(accent) {
                var splash by remember { mutableStateOf(cold) }
                val ctx = LocalContext.current
                LaunchedEffect(Unit) {
                    if (!splash) return@LaunchedEffect
                    val t0 = System.currentTimeMillis()
                    // wait briefly for Home (it's saved on the phone, so usually instant) and the covers at the top
                    withTimeoutOrNull(1_400) {
                        if (vm.api != null) {
                            vm.loadHome()
                            while (vm.home == null && vm.homeError == null) delay(30)
                            withTimeoutOrNull(600) { vm.prefetchCovers(ctx) }
                        }
                    }
                    val left = 1_500 - (System.currentTimeMillis() - t0) // long enough to see the logo animation
                    if (left > 0) delay(left)
                    splash = false
                }
                Box(Modifier.fillMaxSize()) {
                    AppRoot(vm)
                    AnimatedVisibility(splash, enter = fadeIn(tween(0)), exit = fadeOut(tween(450))) { SplashScreen() }
                }
            }
        }
    }
}
