package org.jellyfin.mobile.player.ui

import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.databinding.FragmentPlayerBinding
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.brightness
import org.jellyfin.mobile.utils.dip
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.abs

class PlayerGestureHelper(
    private val fragment: PlayerFragment,
    private val playerBinding: FragmentPlayerBinding,
    private val playerLockScreenHelper: PlayerLockScreenHelper,
) : KoinComponent {
    private val appPreferences: AppPreferences by inject()
    private val audioManager: AudioManager by lazy { fragment.requireActivity().getSystemService()!! }
    private val playerView: PlayerView by playerBinding::playerView
    private val gestureIndicatorOverlayLayout: LinearLayout by playerBinding::gestureOverlayLayout
    private val gestureIndicatorOverlayImage: ImageView by playerBinding::gestureOverlayImage
    private val gestureIndicatorOverlayProgress: ProgressBar by playerBinding::gestureOverlayProgress
    private val gestureIndicatorOverlayText: TextView by playerBinding::gestureOverlayText
    private var isOnPressingSpeedUp = false

    private enum class GestureMode { NONE, BRIGHTNESS, VOLUME, SEEK }
    private var currentGestureMode = GestureMode.NONE
    private var totalHorizontalDistance = 0f
    private var seekStartPosition = 0L

    init {
        if (appPreferences.exoPlayerRememberBrightness) {
            fragment.requireActivity().window.brightness = appPreferences.exoPlayerBrightness
        }
    }

    /**
     * Tracks whether video content should fill the screen, cutting off unwanted content on the sides.
     * Useful on wide-screen phones to remove black bars from some movies.
     */
    private var isZoomEnabled = false

    /**
     * Tracks a value during a swipe gesture (between multiple onScroll calls).
     * When the gesture starts it's reset to an initial value and gets increased or decreased
     * (depending on the direction) as the gesture progresses.
     */
    private var swipeGestureValueTracker = -1f

    /**
     * Runnable that hides [playerView] controller
     */
    private val hidePlayerViewControllerAction = Runnable {
        playerView.hideController()
    }

    /**
     * Runnable that hides [gestureIndicatorOverlayLayout]
     */
    private val hideGestureIndicatorOverlayAction = Runnable {
        gestureIndicatorOverlayLayout.isVisible = false
        gestureIndicatorOverlayText.isVisible = false
        gestureIndicatorOverlayImage.isVisible = true
    }

    /**
     * Handles taps when controls are locked
     */
    private val unlockDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerLockScreenHelper.peekUnlockButton()
                return true
            }
        },
    )

    /**
     * Handles double tap to seek and brightness/volume gestures
     */
    private val gestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Toggle play/pause on double-tap anywhere
                val player = fragment.viewModel.playerOrNull ?: return false
                if (player.isPlaying) fragment.viewModel.pause() else fragment.viewModel.play()
                // Show controller briefly
                playerView.showController()
                playerView.removeCallbacks(hidePlayerViewControllerAction)
                playerView.postDelayed(hidePlayerViewControllerAction, Constants.DEFAULT_CONTROLS_TIMEOUT_MS.toLong())
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerView.apply {
                    if (!isControllerFullyVisible) showController() else hideController()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!appPreferences.exoPlayerAllowPressSpeedUp) {
                    return
                }

                with(fragment) {
                    isOnPressingSpeedUp = true
                    onPressSpeedUp(true)
                }
            }

            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (!appPreferences.exoPlayerAllowSwipeGestures) {
                    return false
                }

                // Check whether swipe was started in excluded region
                val exclusionSize = playerView.resources.dip(Constants.SWIPE_GESTURE_EXCLUSION_SIZE_VERTICAL)
                if (
                    firstEvent == null ||
                    firstEvent.y < exclusionSize ||
                    firstEvent.y > playerView.height - exclusionSize
                ) {
                    return false
                }

                // Determine gesture mode on first scroll event
                if (currentGestureMode == GestureMode.NONE) {
                    currentGestureMode = if (abs(distanceX) > abs(distanceY)) {
                        // Horizontal swipe: seeking
                        seekStartPosition = fragment.viewModel.playerOrNull?.currentPosition ?: 0L
                        totalHorizontalDistance = 0f
                        GestureMode.SEEK
                    } else {
                        // Vertical swipe: brightness or volume
                        swipeGestureValueTracker = -1f
                        if (firstEvent.x.toInt() > playerView.measuredWidth / 2) {
                            GestureMode.VOLUME
                        } else {
                            GestureMode.BRIGHTNESS
                        }
                    }
                }

                return when (currentGestureMode) {
                    GestureMode.SEEK -> {
                        totalHorizontalDistance += -distanceX

                        val player = fragment.viewModel.playerOrNull
                        val duration = player?.duration?.takeIf { it > 0 } ?: 0L
                        val seekDelta = calculateSeekDelta(totalHorizontalDistance, playerView.measuredWidth.toFloat(), duration)

                        val targetMs = (seekStartPosition + seekDelta).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)

                        showSeekIndicator(seekDelta, targetMs, duration)
                        true
                    }
                    GestureMode.VOLUME, GestureMode.BRIGHTNESS -> {
                        val distanceFull = playerView.measuredHeight * Constants.FULL_SWIPE_RANGE_SCREEN_RATIO
                        val ratioChange = distanceY / distanceFull

                        if (currentGestureMode == GestureMode.VOLUME) {
                            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            if (swipeGestureValueTracker == -1f) swipeGestureValueTracker = currentVolume.toFloat()
                            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val change = ratioChange * maxVolume
                            swipeGestureValueTracker += change
                            val toSet = swipeGestureValueTracker.toInt().coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, toSet, 0)
                            gestureIndicatorOverlayImage.setImageResource(R.drawable.ic_volume_white_24dp)
                            gestureIndicatorOverlayProgress.max = maxVolume
                            gestureIndicatorOverlayProgress.progress = toSet
                        } else {
                            val window = fragment.requireActivity().window
                            val brightnessRange = BRIGHTNESS_OVERRIDE_OFF..BRIGHTNESS_OVERRIDE_FULL
                            if (swipeGestureValueTracker == -1f) {
                                val brightness = window.brightness
                                swipeGestureValueTracker = when (brightness) {
                                    in brightnessRange -> brightness
                                    else -> {
                                        Settings.System.getFloat(
                                            fragment.requireActivity().contentResolver,
                                            Settings.System.SCREEN_BRIGHTNESS,
                                        ) / Constants.SCREEN_BRIGHTNESS_MAX
                                    }
                                }
                            }
                            swipeGestureValueTracker = (swipeGestureValueTracker + ratioChange).coerceIn(brightnessRange)
                            window.brightness = swipeGestureValueTracker
                            if (appPreferences.exoPlayerRememberBrightness) {
                                appPreferences.exoPlayerBrightness = swipeGestureValueTracker
                            }
                            gestureIndicatorOverlayImage.setImageResource(R.drawable.ic_brightness_white_24dp)
                            gestureIndicatorOverlayProgress.max = Constants.PERCENT_MAX
                            gestureIndicatorOverlayProgress.progress = (swipeGestureValueTracker * Constants.PERCENT_MAX).toInt()
                        }

                        gestureIndicatorOverlayLayout.isVisible = true
                        true
                    }
                    GestureMode.NONE -> false
                }
            }
        },
    )

    /**
     * Handles scale/zoom gesture
     */
    private val zoomGestureDetector = ScaleGestureDetector(
        playerView.context,
        object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = fragment.isLandscape()

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                if (abs(scaleFactor - Constants.ZOOM_SCALE_BASE) > Constants.ZOOM_SCALE_THRESHOLD) {
                    isZoomEnabled = scaleFactor > 1
                    updateZoomMode(isZoomEnabled)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) = Unit
        },
    ).apply { isQuickScaleEnabled = false }

    init {
        @Suppress("ClickableViewAccessibility")
        playerView.setOnTouchListener { _, event ->
            if (playerView.useController) {
                when (event.pointerCount) {
                    1 -> gestureDetector.onTouchEvent(event)
                    2 -> zoomGestureDetector.onTouchEvent(event)
                }
            } else {
                unlockDetector.onTouchEvent(event)
            }
            if (event.action == MotionEvent.ACTION_UP) {
                if (isOnPressingSpeedUp) {
                    isOnPressingSpeedUp = false
                    with(fragment) {
                        onPressSpeedUp(false)
                    }
                }
                // Apply seek on horizontal swipe end
                if (currentGestureMode == GestureMode.SEEK) {
                    val duration = fragment.viewModel.playerOrNull?.duration?.takeIf { it > 0 } ?: 0L
                    val seekDelta = calculateSeekDelta(totalHorizontalDistance, playerView.measuredWidth.toFloat(), duration)
                    fragment.onSeekByOffset(seekDelta)
                }
                // Hide gesture indicator after timeout, if shown
                gestureIndicatorOverlayLayout.apply {
                    if (isVisible) {
                        removeCallbacks(hideGestureIndicatorOverlayAction)
                        postDelayed(
                            hideGestureIndicatorOverlayAction,
                            Constants.DEFAULT_CENTER_OVERLAY_TIMEOUT_MS.toLong(),
                        )
                    }
                }
                swipeGestureValueTracker = -1f
                currentGestureMode = GestureMode.NONE
                totalHorizontalDistance = 0f
            }
            true
        }
    }

    fun handleConfiguration(newConfig: Configuration) {
        updateZoomMode(fragment.isLandscape(newConfig) && isZoomEnabled)
    }

    private fun updateZoomMode(enabled: Boolean) {
        playerView.resizeMode = if (enabled) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    /**
     * Calculate seek delta proportional to video duration (like progress bar dragging).
     * Full screen width swipe = skips through the entire video.
     */
    private fun calculateSeekDelta(totalDistance: Float, screenWidth: Float, durationMs: Long): Long {
        if (durationMs <= 0) return 0L
        val ratio = (totalDistance / screenWidth).coerceIn(-1f, 1f)
        return (ratio * durationMs).toLong()
    }

    private fun showSeekIndicator(seekDeltaMs: Long, targetMs: Long, durationMs: Long) {
        gestureIndicatorOverlayImage.isVisible = false
        gestureIndicatorOverlayImage.setImageDrawable(null)
        gestureIndicatorOverlayText.isVisible = true

        // Format time delta
        val totalSeconds = seekDeltaMs / 1000
        val absSeconds = abs(totalSeconds)
        val hours = absSeconds / 3600
        val minutes = (absSeconds % 3600) / 60
        val seconds = absSeconds % 60
        val timeStr = buildString {
            if (totalSeconds < 0) append("-")
            if (hours > 0) append("${hours}:%02d:%02d".format(minutes, seconds))
            else append("${minutes}:%02d".format(seconds))
        }
        gestureIndicatorOverlayText.text = timeStr

        // Update progress bar to show target position
        gestureIndicatorOverlayProgress.max = if (durationMs > 0) Constants.PERCENT_MAX else 0
        gestureIndicatorOverlayProgress.progress = if (durationMs > 0) {
            (targetMs.toDouble() / durationMs * Constants.PERCENT_MAX).toInt().coerceIn(0, Constants.PERCENT_MAX)
        } else {
            0
        }

        gestureIndicatorOverlayLayout.isVisible = true
    }
}
