package com.musik.app

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** When the phone connects to the car (or another car-audio Bluetooth device), continue the music where you left off. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CarConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val prefs = context.getSharedPreferences("musik", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("car_autoplay", true)) return
        if (prefs.getString("user", null) == null || (prefs.getString("saved_queue", null)?.length ?: 0) < 5) return // nothing to continue
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val isCar = try {
            val cls = device?.bluetoothClass?.deviceClass
            val name = device?.name.orEmpty()
            cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || cls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
                Regex("bmw|mini|car|audi|volvo|vw|mercedes|toyota|ford", RegexOption.IGNORE_CASE).containsMatchIn(name)
        } catch (_: SecurityException) { false }
        if (!isCar) return
        // wait a moment: the car's audio (A2DP) connects a few seconds after Bluetooth itself
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try { ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_CAR_RESUME)) } catch (_: Exception) {}
            pending.finish()
        }, 4_000)
    }
}
