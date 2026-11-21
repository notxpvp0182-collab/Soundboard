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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Two-Stage Floating Overlay Controller:
 * 1. Round Floating Icon Bubble (Draggable, scalable in size, single-tap opens full panel).
 * 2. Expanded Floating Soundboard Panel (Minimize button collapses back to Round Icon Bubble).
 */
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
                val b = binding
                val lp = layoutParams
                if (b != null && lp != null && settings.overlayMinimized) {
                    val bubblePx = dpToPx(settings.overlayBubbleSizeDp)
                    if (lp.width != bubblePx || lp.height != bubblePx) {
                        lp.width = bubblePx
                        lp.height = bubblePx
                        safeUpdateViewLayout(b.root, lp)
                    }
                }
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

        val isBubble = settings.overlayMinimized
        val bubblePx = dpToPx(settings.overlayBubbleSizeDp)
        val widthPx = if (isBubble) bubblePx else dpToPx(settings.overlayWidthDp)
        val heightPx = if (isBubble) bubblePx else dpToPx(settings.overlayHeightDp)

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

        val glm = GridLayoutManager(themedContext, 1)
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
        inflated.rvOverlaySounds.itemAnimator = null
        inflated.overlayRoot.alpha = settings.overlayOpacity
        inflated.tvOverlayEmpty.visibility =
            if (soundRepository.soundsFlow.value.isEmpty()) View.VISIBLE else View.GONE

        applyBubbleOrPanelState(inflated, params, isBubble)

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

        // Minimize button collapses panel back into the Round Floating Bubble Icon
        inflated.btnOverlayMinimize.setOnClickListener { view ->
            HapticHelper.performTap(view, settingsRepository.current.hapticFeedback)
            val lp = layoutParams ?: return@setOnClickListener
            settingsRepository.update { it.copy(overlayMinimized = true) }
            applyBubbleOrPanelState(inflated, lp, isBubble = true)
            safeUpdateViewLayout(inflated.root, lp)
        }

        inflated.btnOverlayClose.setOnClickListener {
            hideOverlay()
        }

        // Drag + Tap interaction on the Round Floating Bubble Icon
        var bubbleInitX = 0
        var bubbleInitY = 0
        var bubbleTouchX = 0f
        var bubbleTouchY = 0f
        var isBubbleDragging = false

        inflated.overlayBubbleContainer.setOnTouchListener { view, event ->
            val lp = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    bubbleInitX = lp.x
                    bubbleInitY = lp.y
                    bubbleTouchX = event.rawX
                    bubbleTouchY = event.rawY
                    isBubbleDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - bubbleTouchX).roundToInt()
                    val dy = (event.rawY - bubbleTouchY).roundToInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isBubbleDragging = true
                    }
                    if (isBubbleDragging) {
                        val metrics = getScreenMetrics()
                        lp.x = (bubbleInitX + dx).coerceIn(0, max(0, metrics.widthPixels - lp.width))
                        lp.y = (bubbleInitY + dy).coerceIn(0, max(0, metrics.heightPixels - lp.height))
                        safeUpdateViewLayout(inflated.root, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isBubbleDragging) {
                        // Single tap on Round Floating Icon opens the full Soundboard Panel!
                        HapticHelper.performTap(view, settingsRepository.current.hapticFeedback)
                        settingsRepository.update { it.copy(overlayMinimized = false) }
                        applyBubbleOrPanelState(inflated, lp, isBubble = false)
                        safeUpdateViewLayout(inflated.root, lp)
                    } else {
                        settingsRepository.update { it.copy(overlayX = lp.x, overlayY = lp.y) }
                    }
                    true
                }
                else -> false
            }
        }

        // Drag interaction on Expanded Panel Header
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

        // Corner Resize Handle on Expanded Panel
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
                    val newWidthDp = pxToDp(startWidthPx + dw).coerceIn(210, 480)
                    val newHeightDp = pxToDp(startHeightPx + dh).coerceIn(200, 620)
                    lp.width = dpToPx(newWidthDp)
                    lp.height = dpToPx(newHeightDp)
                    val newCols = if (newWidthDp >= 320) 2 else 1
                    if (gridLayoutManager?.spanCount != newCols) {
                        gridLayoutManager?.spanCount = newCols
                    }
                    safeUpdateViewLayout(inflated.root, lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    settingsRepository.update {
                        it.copy(
                            overlayWidthDp = pxToDp(lp.width).coerceIn(210, 480),
                            overlayHeightDp = pxToDp(lp.height).coerceIn(200, 620)
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
                overlayX = 24,
                overlayY = 180,
                overlayWidthDp = 280,
                overlayHeightDp = 320,
                overlayBubbleSizeDp = 58,
                overlayMinimized = true
            )
        }
        val b = binding ?: return
        val lp = layoutParams ?: return
        lp.x = 24
        lp.y = 180
        applyBubbleOrPanelState(b, lp, isBubble = true)
        safeUpdateViewLayout(b.root, lp)
    }

    private fun applyBubbleOrPanelState(
        b: LayoutFloatingOverlayBinding,
        lp: WindowManager.LayoutParams,
        isBubble: Boolean
    ) {
        val s = settingsRepository.current
        if (isBubble) {
            val sizePx = dpToPx(s.overlayBubbleSizeDp)
            lp.width = sizePx
            lp.height = sizePx
            b.overlayBubbleContainer.visibility = View.VISIBLE
            b.overlayBody.visibility = View.GONE
        } else {
            lp.width = dpToPx(s.overlayWidthDp)
            lp.height = dpToPx(s.overlayHeightDp)
            b.overlayBubbleContainer.visibility = View.GONE
            b.overlayBody.visibility = View.VISIBLE
            gridLayoutManager?.spanCount = if (s.overlayWidthDp >= 320) 2 else 1
        }
    }

    private fun safeUpdateViewLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            if (_isShowing.value) {
                windowManager.updateViewLayout(view, params)
            }
        } catch (_: Exception) {
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * density).roundToInt()
    private fun pxToDp(px: Int): Int = (px / density).roundToInt()

    private fun getScreenMetrics(): DisplayMetrics {
        return context.resources.displayMetrics
    }
}
