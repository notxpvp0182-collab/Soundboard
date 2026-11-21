package com.aura.soundboard.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.recyclerview.widget.GridLayoutManager
import com.aura.soundboard.R
import com.aura.soundboard.audio.AudioEngine
import com.aura.soundboard.databinding.LayoutFloatingOverlayBinding
import com.aura.soundboard.permissions.PermissionManager
import com.aura.soundboard.repository.SettingsRepository
import com.aura.soundboard.repository.SoundRepository
import com.aura.soundboard.utils.HapticHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

class OverlayController(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val settingsRepository: SettingsRepository,
    private val soundRepository: SoundRepository,
    private val audioEngine: AudioEngine,
    private val scope: CoroutineScope
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val _isShowing = MutableStateFlow(false)
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    private var binding: LayoutFloatingOverlayBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var adapter: OverlaySoundAdapter? = null
    private var gridLayoutManager: GridLayoutManager? = null

    private val density: Float
        get() = context.resources.displayMetrics.density

    init {
        scope.launch {
            soundRepository.soundsFlow.collectLatest { sounds ->
                adapter?.submitList(sounds)
                binding?.tvOverlayEmpty?.visibility = if (sounds.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        scope.launch {
            audioEngine.activeSoundIds.collectLatest { activeIds ->
                adapter?.updateActiveIds(activeIds)
            }
        }
        scope.launch {
            settingsRepository.settingsFlow.collectLatest { settings ->
                binding?.overlayRoot?.alpha = settings.overlayOpacity
                adapter?.updateShowLabels(settings.overlayShowLabels)
            }
        }
    }

    fun toggleOverlay(): Boolean {
        return if (_isShowing.value) {
            hideOverlay()
            false
        } else {
            showOverlay()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay(): Boolean {
        if (_isShowing.value) return true
        if (!permissionManager.canDrawOverlays()) return false

        val settings = settingsRepository.current
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AuraSoundboard)
        val inflated = LayoutFloatingOverlayBinding.inflate(LayoutInflater.from(themedContext))
        binding = inflated

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val widthPx = dpToPx(settings.overlayWidthDp)
        val heightPx = if (settings.overlayMinimized) dpToPx(58) else dpToPx(settings.overlayHeightDp)

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.overlayX
            y = settings.overlayY
        }
        layoutParams = params

        val spanCount = calculateColumnsForWidthDp(settings.overlayWidthDp)
        val glm = GridLayoutManager(themedContext, spanCount)
        gridLayoutManager = glm

        val soundAdapter = OverlaySoundAdapter { sound, view ->
            HapticHelper.performTap(view, settingsRepository.current.hapticFeedback)
            audioEngine.togglePlay(sound)
        }.apply {
            updateShowLabels(settings.overlayShowLabels)
            updateActiveIds(audioEngine.activeSoundIds.value)
            submitList(soundRepository.soundsFlow.value)
        }
        adapter = soundAdapter

        inflated.rvOverlaySounds.layoutManager = glm
        inflated.rvOverlaySounds.adapter = soundAdapter
        inflated.rvOverlaySounds.itemAnimator = null // Zero animation overhead in floating window
        inflated.overlayRoot.alpha = settings.overlayOpacity
        inflated.tvOverlayEmpty.visibility =
            if (soundRepository.soundsFlow.value.isEmpty()) View.VISIBLE else View.GONE

        applyMinimizedState(inflated, settings.overlayMinimized)

        inflated.seekOverlayMasterVol.progress = (settings.masterVolume * 100).roundToInt()
        inflated.seekOverlayMasterVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val vol = (progress / 100f).coerceIn(0f, 1f)
                    audioEngine.setMasterVolume(vol)
                    settingsRepository.update { it.copy(masterVolume = vol) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        inflated.btnOverlayStopAll.setOnClickListener { view ->
            HapticHelper.performTap(view, settingsRepository.current.hapticFeedback)
            audioEngine.stopAll()
        }

        inflated.btnOverlayMinimize.setOnClickListener {
            val nextMinimized = !settingsRepository.current.overlayMinimized
            settingsRepository.update { it.copy(overlayMinimized = nextMinimized) }
            applyMinimizedState(inflated, nextMinimized)
            val lp = layoutParams ?: return@setOnClickListener
            lp.height = if (nextMinimized) dpToPx(58) else dpToPx(settingsRepository.current.overlayHeightDp)
            safeUpdateViewLayout(inflated.root, lp)
        }

        inflated.btnOverlayClose.setOnClickListener {
            hideOverlay()
        }

        // Drag interaction on header
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        inflated.overlayHeader.setOnTouchListener { _, event ->
            val lp = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = getScreenMetrics()
                    val dx = (event.rawX - initialTouchX).roundToInt()
                    val dy = (event.rawY - initialTouchY).roundToInt()
                    lp.x = (initialX + dx).coerceIn(0, max(0, metrics.widthPixels - lp.width))
                    lp.y = (initialY + dy).coerceIn(0, max(0, metrics.heightPixels - lp.height))
                    safeUpdateViewLayout(inflated.root, lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    settingsRepository.update { it.copy(overlayX = lp.x, overlayY = lp.y) }
                    true
                }
                else -> false
            }
        }

        // Corner Resize Interaction
        var startWidthPx = 0
        var startHeightPx = 0
        var resizeStartX = 0f
        var resizeStartY = 0f

        inflated.btnOverlayResizeHandle.setOnTouchListener { _, event ->
            val lp = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startWidthPx = lp.width
                    startHeightPx = lp.height
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dw = (event.rawX - resizeStartX).roundToInt()
                    val dh = (event.rawY - resizeStartY).roundToInt()
                    val newWidthDp = pxToDp(startWidthPx + dw).coerceIn(190, 480)
                    val newHeightDp = pxToDp(startHeightPx + dh).coerceIn(180, 620)
                    lp.width = dpToPx(newWidthDp)
                    lp.height = dpToPx(newHeightDp)
                    val newCols = calculateColumnsForWidthDp(newWidthDp)
                    if (gridLayoutManager?.spanCount != newCols) {
                        gridLayoutManager?.spanCount = newCols
                    }
                    safeUpdateViewLayout(inflated.root, lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    settingsRepository.update {
                        it.copy(
                            overlayWidthDp = pxToDp(lp.width).coerceIn(190, 480),
                            overlayHeightDp = pxToDp(lp.height).coerceIn(180, 620)
                        )
                    }
                    true
                }
                else -> false
            }
        }

        return try {
            windowManager.addView(inflated.root, params)
            _isShowing.value = true
            true
        } catch (_: Exception) {
            binding = null
            _isShowing.value = false
            false
        }
    }

    fun hideOverlay() {
        val currentBinding = binding ?: return
        try {
            windowManager.removeView(currentBinding.root)
        } catch (_: Exception) {
        } finally {
            binding = null
            layoutParams = null
            adapter = null
            gridLayoutManager = null
            _isShowing.value = false
        }
    }

    fun resetPositionAndSize() {
        settingsRepository.update {
            it.copy(
                overlayX = 36,
                overlayY = 150,
                overlayWidthDp = 270,
                overlayHeightDp = 300,
                overlayMinimized = false
            )
        }
        val b = binding ?: return
        val lp = layoutParams ?: return
        lp.x = 36
        lp.y = 150
        lp.width = dpToPx(270)
        lp.height = dpToPx(300)
        applyMinimizedState(b, false)
        gridLayoutManager?.spanCount = calculateColumnsForWidthDp(270)
        safeUpdateViewLayout(b.root, lp)
    }

    private fun applyMinimizedState(b: LayoutFloatingOverlayBinding, minimized: Boolean) {
        b.overlayBody.visibility = if (minimized) View.GONE else View.VISIBLE
        b.btnOverlayMinimize.text = if (minimized) "▢" else "—"
    }

    private fun safeUpdateViewLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            if (_isShowing.value) {
                windowManager.updateViewLayout(view, params)
            }
        } catch (_: Exception) {
        }
    }

    private fun calculateColumnsForWidthDp(widthDp: Int): Int {
        return when {
            widthDp < 225 -> 1
            widthDp < 330 -> 2
            widthDp < 420 -> 3
            else -> 4
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * density).roundToInt()
    private fun pxToDp(px: Int): Int = (px / density).roundToInt()

    private fun getScreenMetrics(): DisplayMetrics {
        return context.resources.displayMetrics
    }
}
