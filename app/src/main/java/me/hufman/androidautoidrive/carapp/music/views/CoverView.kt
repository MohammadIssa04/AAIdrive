package me.hufman.androidautoidrive.carapp.music.views

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import io.bimmergestalt.idriveconnectkit.RHMIDimensions
import io.bimmergestalt.idriveconnectkit.rhmi.*
import me.hufman.androidautoidrive.PhoneAppResources
import me.hufman.androidautoidrive.UnicodeCleaner
import me.hufman.androidautoidrive.carapp.L
import me.hufman.androidautoidrive.carapp.music.MusicAppMode
import me.hufman.androidautoidrive.carapp.music.MusicImageIDs
import me.hufman.androidautoidrive.music.CustomAction
import me.hufman.androidautoidrive.music.MusicController
import me.hufman.androidautoidrive.music.MusicMetadata
import me.hufman.androidautoidrive.music.PlaybackPosition
import me.hufman.androidautoidrive.utils.GraphicsHelpers
import me.hufman.androidautoidrive.utils.TimeUtils.formatTime
import kotlin.math.max
import kotlin.math.min

/**
 * Cover mode: the album cover big on the car's screen with its colours behind it, the song, the artist,
 * the album line (Issa Music puts the line being sung there) and the progress.
 * Previous / play-pause / next (and the app's Like / More like this) are in the toolbar.
 * Opened by clicking the cover or the song in the playback screen.
 */
class CoverView(val state: RHMIState, val controller: MusicController, val phoneAppResources: PhoneAppResources,
                val graphicsHelpers: GraphicsHelpers, val musicImageIDs: MusicImageIDs, val musicAppMode: MusicAppMode) {
	companion object {
		const val TAG = "CoverView"
		val LIKE = Regex("^(i )?like|thumbs ?up|favou?rite|love|heart|gilla", RegexOption.IGNORE_CASE)
		val MORE = Regex("more like|similar|radio|liknande", RegexOption.IGNORE_CASE)

		/** A toolbar window with a separator and a picture, which no other view uses */
		fun fits(state: RHMIState): Boolean {
			return state is RHMIState.ToolbarState &&
					state !is RHMIState.AudioHmiState &&
					state.toolbarComponentsList.count { it.getAction() != null } >= 5 &&
					state.componentsList.filterIsInstance<RHMIComponent.Gauge>().isEmpty() &&
					state.componentsList.filterIsInstance<RHMIComponent.Separator>().isNotEmpty() &&
					state.componentsList.filterIsInstance<RHMIComponent.Image>().any { it.getModel() is RHMIModel.RaImageModel }
		}
	}

	val imageComponent = state.componentsList.filterIsInstance<RHMIComponent.Image>().first { it.getModel() is RHMIModel.RaImageModel }
	val imageModel = imageComponent.getModel()!!.asRaImageModel()!!
	val buttons = (state as RHMIState.ToolbarState).toolbarComponentsList.filter { it.getAction() != null }

	// the area next to the toolbar, as the playback screen's own layout uses it
	private val dimensions = RHMIDimensions.create(musicAppMode.capabilities)
	val width = max(400, dimensions.visibleWidth - dimensions.paddingLeft - 200)
	val height = max(240, dimensions.rhmiHeight - 60)

	var visible = false
		private set
	private var drawnKey: String? = null
	private var likeAction: CustomAction? = null
	private var moreAction: CustomAction? = null
	private var coverKey: Any? = null
	private var coverBitmap: Bitmap? = null

	fun initWidgets() {
		state as RHMIState.ToolbarState
		state.getTextModel()?.asRaDataModel()?.value = L.MUSIC_COVER_TITLE
		state.focusCallback = FocusCallback { focused ->
			visible = focused
			if (focused) {
				drawnKey = null
				redraw()
			}
		}

		state.componentsList.forEach { it.setVisible(false) }
		imageComponent.setVisible(true)
		imageComponent.setProperty(RHMIProperty.PropertyId.POSITION_X.id, 0)
		imageComponent.setProperty(RHMIProperty.PropertyId.POSITION_Y.id, 0)
		imageComponent.setProperty(RHMIProperty.PropertyId.WIDTH.id, width)
		imageComponent.setProperty(RHMIProperty.PropertyId.HEIGHT.id, height)

		setupButton(0, musicImageIDs.SKIP_BACK, L.MUSIC_SKIP_PREVIOUS) { controller.skipToPrevious() }
		setupButton(1, musicImageIDs.SONG, L.MUSIC_PLAY_PAUSE) { controller.togglePlay() }
		setupButton(2, musicImageIDs.SKIP_NEXT, L.MUSIC_SKIP_NEXT) { controller.skipToNext() }
		setupButton(3, musicImageIDs.CHECKMARK, "") { likeAction?.let { controller.customAction(it) } }
		setupButton(4, musicImageIDs.ACTIONS, "") { moreAction?.let { controller.customAction(it) } }
		buttons.drop(5).forEach {
			try {
				it.setVisible(false)
			} catch (e: Exception) {}
			it.setSelectable(false)
		}
		updateActions()
	}

	private fun setupButton(index: Int, imageId: Int, tooltip: String, onClick: () -> Unit) {
		val button = buttons.getOrNull(index) ?: return
		button.getImageModel()?.asImageIdModel()?.imageId = imageId
		button.getTooltipModel()?.asRaDataModel()?.value = tooltip
		button.setVisible(true)
		button.setSelectable(true)
		button.setEnabled(true)
		// stay in this window after a button push
		button.getAction()?.asHMIAction()?.getTargetModel()?.asRaIntModel()?.value = state.id
		button.getAction()?.asRAAction()?.rhmiActionCallback = RHMIActionButtonCallback { onClick() }
	}

	/** The app's own buttons, like Issa Music's "Like" and "More like this" */
	private fun updateActions() {
		val actions = controller.getCustomActions()
		val like = actions.firstOrNull { LIKE.containsMatchIn(it.name) && !MORE.containsMatchIn(it.name) }
		val more = actions.firstOrNull { MORE.containsMatchIn(it.name) }
		if (like?.name != likeAction?.name) {
			buttons.getOrNull(3)?.getTooltipModel()?.asRaDataModel()?.value = like?.name ?: ""
			buttons.getOrNull(3)?.setEnabled(like != null)
		}
		if (more?.name != moreAction?.name) {
			buttons.getOrNull(4)?.getTooltipModel()?.asRaDataModel()?.value = more?.name ?: ""
			buttons.getOrNull(4)?.setEnabled(more != null)
		}
		likeAction = like
		moreAction = more
	}

	fun redraw() {
		if (!visible) return
		val song = controller.getMetadata()
		val position = controller.getPlaybackPosition()
		updateActions()

		// redraw when something shows a change: over Bluetooth the progress moves every 5 seconds, over USB every second
		val step = if (musicAppMode.isBTConnection()) 5000 else 1000
		val key = listOf(song?.mediaId, song?.title, song?.artist, song?.album, song?.coverArt?.hashCode(), song?.coverArtUri,
				position.isPaused, position.getPosition() / step).joinToString("|")
		if (key == drawnKey) return
		drawnKey = key
		try {
			val picture = render(song, position)
			imageModel.value = graphicsHelpers.compress(picture, width, height, quality = if (musicAppMode.isBTConnection()) 60 else 80)
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't draw the cover screen", e)
		}
	}

	private fun cover(song: MusicMetadata?): Bitmap? {
		val key: Any? = song?.coverArt ?: song?.coverArtUri
		if (key == coverKey) return coverBitmap
		coverKey = key
		coverBitmap = song?.coverArt ?: song?.coverArtUri?.let { uri ->
			try {
				toBitmap(phoneAppResources.getUriDrawable(uri))
			} catch (e: Exception) {
				null
			}
		}
		return coverBitmap
	}

	private fun toBitmap(drawable: Drawable): Bitmap {
		if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
		val size = max(1, min(800, max(drawable.intrinsicWidth, drawable.intrinsicHeight)))
		val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		drawable.setBounds(0, 0, size, size)
		drawable.draw(canvas)
		return bitmap
	}

	private fun render(song: MusicMetadata?, position: PlaybackPosition): Bitmap {
		val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(out)
		val art = cover(song)

		// background: the cover blurred over the whole screen (shrunk to a few pixels and stretched back), darkened
		if (art != null) {
			val tiny = Bitmap.createScaledBitmap(art, 10, 10, true)
			val side = max(width, height)
			val blurred = Bitmap.createScaledBitmap(tiny, side, side, true)
			canvas.drawBitmap(blurred, (width - side) / 2f, (height - side) / 2f, null)
			canvas.drawColor(Color.argb(140, 0, 0, 0))
		} else {
			canvas.drawColor(Color.rgb(24, 24, 30))
		}

		// the cover: as big as the screen is high, rounded corners
		val pad = height * 0.06f
		val side = height - 2 * pad
		val coverRect = RectF(pad, pad, pad + side, pad + side)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
		if (art != null) {
			val scaled = Bitmap.createScaledBitmap(art, side.toInt(), side.toInt(), true)
			paint.shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
				setLocalMatrix(Matrix().apply { setTranslate(pad, pad) })
			}
			canvas.drawRoundRect(coverRect, side * 0.04f, side * 0.04f, paint)
			paint.shader = null
		} else {
			paint.color = Color.rgb(60, 60, 70)
			canvas.drawRoundRect(coverRect, side * 0.04f, side * 0.04f, paint)
			paint.color = Color.rgb(160, 160, 170)
			paint.textSize = side * 0.4f
			paint.textAlign = Paint.Align.CENTER
			canvas.drawText("♪", coverRect.centerX(), coverRect.centerY() + side * 0.14f, paint)
			paint.textAlign = Paint.Align.LEFT
		}

		// the text next to it
		val x = coverRect.right + pad * 1.4f
		val textWidth = width - x - pad
		fun line(text: String?, y: Float, size: Float, color: Int, bold: Boolean = false) {
			if (text.isNullOrBlank() || textWidth <= 0) return
			val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
				textSize = size
				this.color = color
				typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
			}
			val shown = TextUtils.ellipsize(UnicodeCleaner.clean(text), p, textWidth, TextUtils.TruncateAt.END)
			canvas.drawText(shown, 0, shown.length, x, y, p)
		}
		line(song?.title, height * 0.27f, height * 0.11f, Color.WHITE, bold = true)
		line(song?.artist, height * 0.40f, height * 0.075f, Color.rgb(225, 225, 225))
		line(song?.album, height * 0.51f, height * 0.058f, Color.rgb(255, 196, 150))

		// progress
		val total = position.maximumPosition
		val now = position.getPosition()
		val barY = height * 0.66f
		paint.color = Color.argb(110, 255, 255, 255)
		canvas.drawRoundRect(RectF(x, barY, x + textWidth, barY + height * 0.016f), 6f, 6f, paint)
		if (total > 0) {
			paint.color = Color.WHITE
			val filled = textWidth * min(1f, max(0f, now.toFloat() / total))
			canvas.drawRoundRect(RectF(x, barY, x + filled, barY + height * 0.016f), 6f, 6f, paint)
		}
		val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = height * 0.05f; color = Color.rgb(200, 200, 200) }
		canvas.drawText(formatTime(now), x, barY + height * 0.09f, timePaint)
		if (total > 0) {
			val totalText = formatTime(total)
			canvas.drawText(totalText, x + textWidth - timePaint.measureText(totalText), barY + height * 0.09f, timePaint)
		}

		// playing or paused
		val cy = height * 0.86f
		val r = height * 0.045f
		val cx = x + textWidth / 2
		paint.color = Color.WHITE
		if (position.isPaused) {
			val path = Path().apply {
				moveTo(cx - r * 0.7f, cy - r)
				lineTo(cx + r, cy)
				lineTo(cx - r * 0.7f, cy + r)
				close()
			}
			canvas.drawPath(path, paint)
		} else {
			canvas.drawRect(cx - r * 0.8f, cy - r, cx - r * 0.25f, cy + r, paint)
			canvas.drawRect(cx + r * 0.25f, cy - r, cx + r * 0.8f, cy + r, paint)
		}
		return out
	}
}
