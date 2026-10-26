package me.hufman.androidautoidrive.carapp.music.views

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import de.bmw.idrive.BMWRemoting
import io.bimmergestalt.idriveconnectkit.RHMIDimensions
import io.bimmergestalt.idriveconnectkit.rhmi.*
import me.hufman.androidautoidrive.PhoneAppResources
import me.hufman.androidautoidrive.UnicodeCleaner
import me.hufman.androidautoidrive.carapp.L
import me.hufman.androidautoidrive.carapp.music.MusicAppMode
import me.hufman.androidautoidrive.carapp.music.MusicImageIDs
import me.hufman.androidautoidrive.music.MusicController
import me.hufman.androidautoidrive.music.MusicMetadata
import me.hufman.androidautoidrive.utils.GraphicsHelpers
import me.hufman.androidautoidrive.utils.TimeUtils.formatTime
import kotlin.math.max
import kotlin.math.min

/**
 * Cover mode: the album cover big on the car's screen, and next to it the song, the artist, the album line
 * (Issa Music puts the line being sung there), the time, and previous / play-pause / next right there.
 * The cover is a picture, sent once per song; everything else is the car's own text and list, so it updates cheaply.
 * Opened by clicking the cover or the song in the playback screen, or with Cover in its side menu.
 */
class CoverView(val state: RHMIState, val controller: MusicController, val phoneAppResources: PhoneAppResources,
                val graphicsHelpers: GraphicsHelpers, val musicImageIDs: MusicImageIDs, val musicAppMode: MusicAppMode) {
	companion object {
		const val TAG = "CoverView"

		/** A toolbar window with a separator, a picture, a label and lists, which no other view uses */
		fun fits(state: RHMIState): Boolean {
			return state is RHMIState.ToolbarState &&
					state !is RHMIState.AudioHmiState &&
					state.componentsList.filterIsInstance<RHMIComponent.Gauge>().isEmpty() &&
					state.componentsList.filterIsInstance<RHMIComponent.Separator>().isNotEmpty() &&
					state.componentsList.filterIsInstance<RHMIComponent.Label>().isNotEmpty() &&
					state.componentsList.filterIsInstance<RHMIComponent.List>().size >= 2 &&
					state.componentsList.filterIsInstance<RHMIComponent.Image>().any { it.getModel() is RHMIModel.RaImageModel }
		}
	}

	val imageComponent = state.componentsList.filterIsInstance<RHMIComponent.Image>().first { it.getModel() is RHMIModel.RaImageModel }
	val titleLabel = state.componentsList.filterIsInstance<RHMIComponent.Label>().first()
	val buttonList = state.componentsList.filterIsInstance<RHMIComponent.List>()[0]     // previous / play-pause / next
	val infoList = state.componentsList.filterIsInstance<RHMIComponent.List>()[1]       // artist, album line, time

	// the area next to the (empty) side menu, as the playback screen's own layout uses it
	private val dimensions = RHMIDimensions.create(musicAppMode.capabilities)
	val width = max(600, dimensions.visibleWidth - dimensions.paddingLeft - 200)
	val height = max(300, dimensions.rhmiHeight - 60)
	val coverSize = height - 20
	val textX = coverSize + 30
	val textWidth = max(260, width - textX - 10)

	var visible = false
		private set
	private var drawnCover: Any? = null
	private var drawnTitle: String? = null
	private var drawnInfo: List<String>? = null
	private var drawnPaused: Boolean? = null
	private val pauseIcon by lazy { icon(paused = false) }
	private val playIcon by lazy { icon(paused = true) }

	fun initWidgets() {
		state as RHMIState.ToolbarState
		state.getTextModel()?.asRaDataModel()?.value = L.MUSIC_COVER_TITLE
		state.focusCallback = FocusCallback { focused ->
			visible = focused
			if (focused) {
				forget()
				redraw()
				focusPlayPause()
			}
		}

		// an empty side menu: the buttons are next to the cover
		// (the side menu has to stay visible, or the car squeezes the whole window into a narrow box)
		state.toolbarComponentsList.forEach {
			try {
				it.getImageModel()?.asImageIdModel()?.imageId = 0
			} catch (e: Exception) {}
			it.getTooltipModel()?.asRaDataModel()?.value = ""
			it.setVisible(true)
			it.setSelectable(false)
			it.setEnabled(false)
		}
		state.componentsList.forEach { it.setVisible(false) }

		// the cover: as big as the screen is high, no background
		imageComponent.apply {
			setVisible(true)
			setProperty(RHMIProperty.PropertyId.POSITION_X.id, 0)
			setProperty(RHMIProperty.PropertyId.POSITION_Y.id, 10)
			setProperty(RHMIProperty.PropertyId.WIDTH.id, coverSize)
			setProperty(RHMIProperty.PropertyId.HEIGHT.id, coverSize)
		}
		titleLabel.apply {
			setVisible(true)
			setProperty(RHMIProperty.PropertyId.POSITION_X.id, textX)
			setProperty(RHMIProperty.PropertyId.POSITION_Y.id, 15)
			setProperty(RHMIProperty.PropertyId.WIDTH.id, textWidth)
			setProperty(RHMIProperty.PropertyId.HEIGHT.id, 50)
		}
		infoList.apply {
			setVisible(true)
			setEnabled(true)
			setSelectable(false)
			setProperty(RHMIProperty.PropertyId.POSITION_X.id, textX)
			setProperty(RHMIProperty.PropertyId.POSITION_Y.id, 70)
			setProperty(RHMIProperty.PropertyId.WIDTH.id, textWidth)
		}
		buttonList.apply {
			setVisible(true)
			setEnabled(true)
			setSelectable(true)
			setProperty(RHMIProperty.PropertyId.POSITION_X.id, textX)
			setProperty(RHMIProperty.PropertyId.POSITION_Y.id, min(height - 160, 235))
			setProperty(RHMIProperty.PropertyId.WIDTH.id, min(textWidth, 420))
			setProperty(RHMIProperty.PropertyId.LIST_COLUMNWIDTH.id, "0,60,*")
			// pushing a button stays in this window
			getAction()?.asHMIAction()?.getTargetModel()?.asRaIntModel()?.value = state.id
			getAction()?.asRAAction()?.rhmiActionCallback = RHMIActionListCallback { index ->
				when (index) {
					0 -> controller.skipToPrevious()
					1 -> controller.togglePlay()
					2 -> controller.skipToNext()
				}
			}
		}
	}

	private fun forget() {
		drawnCover = null
		drawnTitle = null
		drawnInfo = null
		drawnPaused = null
	}

	private fun focusPlayPause() {
		try {
			state.app.events.values.filterIsInstance<RHMIEvent.FocusEvent>().firstOrNull()
					?.triggerEvent(mapOf(0.toByte() to buttonList.id, 41.toByte() to 1))
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't focus the play button", e)
		}
	}

	fun redraw() {
		if (!visible) return
		val song = controller.getMetadata()
		val position = controller.getPlaybackPosition()

		// the cover: only when it changes
		val coverKey: Any? = song?.coverArt ?: song?.coverArtUri
		if (coverKey != drawnCover) {
			drawnCover = coverKey
			try {
				val art = cover(song)
				imageModel().value = if (art != null) {
					graphicsHelpers.compress(art, coverSize, coverSize, quality = 85)
				} else {
					graphicsHelpers.compress(placeholder(), coverSize, coverSize, quality = 85)
				}
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't show the cover", e)
			}
		}

		val title = UnicodeCleaner.clean(song?.title ?: "")
		if (title != drawnTitle) {
			drawnTitle = title
			titleLabel.getModel()?.asRaDataModel()?.value = title
		}

		val total = position.maximumPosition
		val time = formatTime(position.getPosition()).trim() + if (total > 0) "  /  " + formatTime(total).trim() else ""
		val info = listOf(UnicodeCleaner.clean(song?.artist ?: ""), UnicodeCleaner.clean(song?.album ?: ""), time)
		if (info != drawnInfo) {
			drawnInfo = info
			val rows = RHMIModel.RaListModel.RHMIListConcrete(1)
			info.forEach { rows.addRow(arrayOf(it)) }
			infoList.getModel()?.value = rows
		}

		if (position.isPaused != drawnPaused) {
			val first = drawnPaused == null
			drawnPaused = position.isPaused
			val rows = RHMIModel.RaListModel.RHMIListConcrete(3)
			rows.addRow(arrayOf("", BMWRemoting.RHMIResourceIdentifier(BMWRemoting.RHMIResourceType.IMAGEID, musicImageIDs.SKIP_BACK), L.MUSIC_SKIP_PREVIOUS))
			rows.addRow(arrayOf("", BMWRemoting.RHMIResourceData(BMWRemoting.RHMIResourceType.IMAGEDATA, if (position.isPaused) playIcon else pauseIcon),
					if (position.isPaused) L.MUSIC_PLAY else L.MUSIC_PAUSE))
			rows.addRow(arrayOf("", BMWRemoting.RHMIResourceIdentifier(BMWRemoting.RHMIResourceType.IMAGEID, musicImageIDs.SKIP_NEXT), L.MUSIC_SKIP_NEXT))
			buttonList.getModel()?.value = rows
			if (!first) focusPlayPause()    // a new list puts the cursor back at the top
		}
	}

	private fun imageModel() = imageComponent.getModel()!!.asRaImageModel()!!

	private fun cover(song: MusicMetadata?): Bitmap? {
		return song?.coverArt ?: song?.coverArtUri?.let { uri ->
			try {
				toBitmap(phoneAppResources.getUriDrawable(uri))
			} catch (e: Exception) {
				null
			}
		}
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

	/** No cover: a note on dark grey */
	private fun placeholder(): Bitmap {
		val bitmap = Bitmap.createBitmap(coverSize, coverSize, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		canvas.drawColor(Color.rgb(45, 45, 52))
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.rgb(160, 160, 170)
			textSize = coverSize * 0.4f
			textAlign = Paint.Align.CENTER
		}
		canvas.drawText("♪", coverSize / 2f, coverSize * 0.64f, paint)
		return bitmap
	}

	/** The play / pause symbol for the button list, white on transparent */
	private fun icon(paused: Boolean): ByteArray {
		val size = 48
		val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
		if (paused) {   // show Play
			canvas.drawPath(Path().apply {
				moveTo(15f, 9f)
				lineTo(39f, 24f)
				lineTo(15f, 39f)
				close()
			}, paint)
		} else {        // show Pause
			canvas.drawRect(13f, 10f, 21f, 38f, paint)
			canvas.drawRect(27f, 10f, 35f, 38f, paint)
		}
		return graphicsHelpers.compress(bitmap, size, size)
	}
}
