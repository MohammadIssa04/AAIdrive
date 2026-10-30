package com.musik.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.common.util.UnstableApi

@UnstableApi
class PlayerWidget : AppWidgetProvider() {
    companion object {
        private const val TOGGLE = "com.musik.app.widget.TOGGLE"
        private const val NEXT = "com.musik.app.widget.NEXT"
        private const val PREV = "com.musik.app.widget.PREV"

        fun push(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            try { mgr.updateAppWidget(ids, views(ctx)) } catch (_: Exception) {}
        }

        private fun broadcast(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, PlayerWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun openApp(ctx: Context) = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun views(ctx: Context): RemoteViews {
            val p = PlaybackService.player
            val md = p?.currentMediaItem?.mediaMetadata
            return RemoteViews(ctx.packageName, R.layout.widget_player).apply {
                setTextViewText(R.id.w_title, md?.title ?: "Issa Music")
                setTextViewText(R.id.w_artist, md?.artist ?: "Tap to start listening")
                val cover = PlaybackService.widgetCover
                if (cover != null && md != null) setImageViewBitmap(R.id.w_cover, cover) else setImageViewResource(R.id.w_cover, R.drawable.ic_widget_cover)
                setImageViewResource(R.id.w_toggle, if (p?.isPlaying == true) R.drawable.ic_w_pause else R.drawable.ic_w_play)
                setOnClickPendingIntent(R.id.w_root, openApp(ctx))
                setOnClickPendingIntent(R.id.w_cover, openApp(ctx))
                setOnClickPendingIntent(R.id.w_toggle, broadcast(ctx, TOGGLE, 1))
                setOnClickPendingIntent(R.id.w_next, broadcast(ctx, NEXT, 2))
                setOnClickPendingIntent(R.id.w_prev, broadcast(ctx, PREV, 3))
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) { push(context) }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val p = PlaybackService.player
        when (intent.action) {
            TOGGLE -> if (p == null || p.mediaItemCount == 0) {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else if (p.isPlaying) p.pause() else p.play()
            NEXT -> p?.seekToNextMediaItem()
            PREV -> p?.let { if (it.currentPosition > 4000) it.seekTo(0) else it.seekToPreviousMediaItem() }
            else -> return
        }
        push(context)
    }
}
