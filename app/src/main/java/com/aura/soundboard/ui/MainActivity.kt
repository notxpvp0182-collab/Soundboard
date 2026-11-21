package com.aura.soundboard.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.aura.soundboard.BuildConfig
import com.aura.soundboard.R
import com.aura.soundboard.SoundboardApp
import com.aura.soundboard.databinding.ActivityMainBinding
import com.aura.soundboard.databinding.DialogSoundEditBinding
import com.aura.soundboard.models.SoundItem
import com.aura.soundboard.service.SoundboardForegroundService
import com.aura.soundboard.ui.components.SoundGridAdapter
import com.aura.soundboard.utils.HapticHelper
import com.aura.soundboard.utils.UiAnimator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val container by lazy { (application as SoundboardApp).container }
    private val viewModel: MainViewModel by viewModels { MainViewModel.factory(container) }

    private lateinit var soundGridAdapter: SoundGridAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private var isSettingsPageVisible = false

    private val importAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            viewModel.importAudioUris(uris)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshPermissionStatusLabels()
        container.micMonitorMixer.refreshRouteState()
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshPermissionStatusLabels()
        SoundboardForegroundService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackNavigation()
        runLoadingScreenSequence()
        setupRecyclerView()
        setupTopAndMasterBars()
        setupSearchAndCategories()
        setupSettingsPageAndSubTabs()
        setupAboutScreen()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatusLabels()
        container.micMonitorMixer.refreshRouteState()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && container.settingsRepository.current.stopOnExit &&
            !container.overlayController.isShowing.value
        ) {
            container.audioEngine.stopAll()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSettingsPageVisible) {
                    closeSettingsPage()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun runLoadingScreenSequence() {
        binding.loadingOverlayContainer.visibility = View.VISIBLE
        binding.ivLoadingLogo.scaleX = 0.82f
        binding.ivLoadingLogo.scaleY = 0.82f
        binding.ivLoadingLogo.animate()
            .scaleX(1.04f)
            .scaleY(1.04f)
            .setDuration(550)
            .withEndAction {
                binding.ivLoadingLogo.animate().scaleX(1.0f).scaleY(1.0f).setDuration(250).start()
            }
            .start()

        ValueAnimator.ofInt(15, 100).apply {
            duration = 820
            addUpdateListener { anim ->
                binding.loadingProgressBar.progress = anim.animatedValue as Int
            }
            start()
        }

        lifecycleScope.launch {
            delay(880)
            binding.loadingOverlayContainer.animate()
                .alpha(0f)
                .setDuration(240)
                .withEndAction {
                    binding.loadingOverlayContainer.visibility = View.GONE
                }
                .start()
        }
    }

    private fun setupRecyclerView() {
        val cols = if (container.settingsRepository.current.compactGrid) 2 else 1
        gridLayoutManager = GridLayoutManager(this, cols)
        soundGridAdapter = SoundGridAdapter(
            onPlayToggle = { item, view ->
                HapticHelper.performTap(view, container.settingsRepository.current.hapticFeedback)
                viewModel.togglePlay(item)
            },
            onFavoriteToggle = { item ->
                viewModel.toggleFavorite(item)
            },
            onVolumeChanged = { item, vol ->
                viewModel.updateItemVolume(item, vol)
            },
            onEditClicked = { item ->
                showSoundEditDialog(item)
            }
        ).apply {
            reducedMotion = container.settingsRepository.current.reducedMotion
        }

        binding.rvSoundGrid.layoutManager = gridLayoutManager
        binding.rvSoundGrid.adapter = soundGridAdapter
        binding.rvSoundGrid.itemAnimator = null
    }

    private fun setupTopAndMasterBars() {
        binding.btnNavImport.setOnClickListener { view ->
            UiAnimator.bounceTap(view, container.settingsRepository.current.reducedMotion)
            HapticHelper.performTap(view, container.settingsRepository.current.hapticFeedback)
            launchAudioImporter()
        }

        binding.btnToggleOverlay.setOnClickListener { view ->
            UiAnimator.bounceTap(view, container.settingsRepository.current.reducedMotion)
            HapticHelper.performTap(view, container.settingsRepository.current.hapticFeedback)
            handleOverlayToggleRequest()
        }

        binding.btnAudioRoutingGuide.setOnClickListener { view ->
            UiAnimator.bounceTap(view, container.settingsRepository.current.reducedMotion)
            showAudioRoutingDiagnosticDialog()
        }

        binding.btnCornerSettings.setOnClickListener { view ->
            UiAnimator.bounceTap(view, container.settingsRepository.current.reducedMotion)
            HapticHelper.performTap(view, container.settingsRepository.current.hapticFeedback)
            openSettingsPage()
        }

        binding.btnBackFromSettings.setOnClickListener { view ->
            UiAnimator.bounceTap(view, container.settingsRepository.current.reducedMotion)
            HapticHelper.performTap(view, container.settingsRepository.current.hapticFeedback)
            closeSettingsPage()
        }

        binding.seekMasterVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvMasterVolumeValue.text = "$progress%"
                if (fromUser) {
                    viewModel.setMasterVolume(progress / 100f)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnStopAllMain.setOnClickListener { view ->
            UiAnimator.bounceTap(view, container.settingsRepository.current.reducedMotion)
            HapticHelper.performTap(view, container.settingsRepository.current.hapticFeedback)
            viewModel.stopAllSounds()
        }

        binding.btnImportFirstSound.setOnClickListener { view ->
            UiAnimator.bounceTap(view, container.settingsRepository.current.reducedMotion)
            launchAudioImporter()
        }
    }

    private fun openSettingsPage() {
        if (isSettingsPageVisible) return
        isSettingsPageVisible = true
        refreshPermissionStatusLabels()
        UiAnimator.switchPage(
            hideView = binding.pageDashboardRoot,
            showView = binding.viewSettingsContainer,
            slideForward = true,
            reducedMotion = container.settingsRepository.current.reducedMotion
        )
    }

    private fun closeSettingsPage() {
        if (!isSettingsPageVisible) return
        isSettingsPageVisible = false
        UiAnimator.switchPage(
            hideView = binding.viewSettingsContainer,
            showView = binding.pageDashboardRoot,
            slideForward = false,
            reducedMotion = container.settingsRepository.current.reducedMotion
        )
    }

    private fun setupSearchAndCategories() {
        binding.etSearchSounds.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnCatAll.setOnClickListener { view ->
            UiAnimator.bounceTap(view)
            viewModel.setCategoryFilter(MainViewModel.FILTER_ALL)
        }
        binding.btnCatFavorites.setOnClickListener { view ->
            UiAnimator.bounceTap(view)
            viewModel.setCategoryFilter(MainViewModel.FILTER_FAVORITES)
        }
        binding.btnCatRecent.setOnClickListener { view ->
            UiAnimator.bounceTap(view)
            viewModel.setCategoryFilter(MainViewModel.FILTER_RECENT)
        }
        binding.btnCatSfx.setOnClickListener { view ->
            UiAnimator.bounceTap(view)
            viewModel.setCategoryFilter(MainViewModel.FILTER_SFX)
        }
        binding.btnCatVoice.setOnClickListener { view ->
            UiAnimator.bounceTap(view)
            viewModel.setCategoryFilter(MainViewModel.FILTER_VOICE)
        }
        binding.btnCatMusic.setOnClickListener { view ->
            UiAnimator.bounceTap(view)
            viewModel.setCategoryFilter(MainViewModel.FILTER_MUSIC)
        }
    }

    private fun setupSettingsPageAndSubTabs() {
        // Sub-page navigation inside Settings
        binding.btnSetTabAudio.setOnClickListener { view ->
            UiAnimator.bounceTap(view)
            selectSettingsSubPage(0)
        }
        binding.btnSetTabOverlay.setOnClickListener { view ->
            UiAnimator.bounceTap(view)
            selectSettingsSubPage(1)
        }
        binding.btnSetTabSystem.setOnClickListener { view ->
            UiAnimator.bounceTap(view)
            selectSettingsSubPage(2)
        }

        val current = container.settingsRepository.current

        binding.switchHapticFeedback.isChecked = current.hapticFeedback
        binding.switchCompactGrid.isChecked = current.compactGrid
        binding.switchAudioFocus.isChecked = current.requestAudioFocus
        binding.switchVoiceCommMode.isChecked = current.voiceCommRouting
        binding.switchOverlapSounds.isChecked = current.allowOverlap
        binding.switchStopOnExit.isChecked = current.stopOnExit
        binding.switchStartOnBoot.isChecked = current.startOnBoot
        binding.switchOverlayShowLabels.isChecked = current.overlayShowLabels
        binding.switchReducedMotion.isChecked = current.reducedMotion

        val opacityPct = (current.overlayOpacity * 100).roundToInt()
        binding.seekOverlayOpacity.progress = opacityPct
        binding.tvOverlayOpacityLabel.text = "PANEL OPACITY: $opacityPct%"

        binding.seekBubbleSize.progress = current.overlayBubbleSizeDp
        binding.tvBubbleSizeLabel.text = "ROUND BUBBLE SIZE: ${current.overlayBubbleSizeDp} DP"

        binding.seekBubbleSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val safeDp = progress.coerceIn(44, 96)
                binding.tvBubbleSizeLabel.text = "ROUND BUBBLE SIZE: $safeDp DP"
                if (fromUser) {
                    container.settingsRepository.update { it.copy(overlayBubbleSizeDp = safeDp) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.switchHapticFeedback.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(hapticFeedback = checked) }
        }
        binding.switchCompactGrid.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(compactGrid = checked) }
            gridLayoutManager.spanCount = if (checked) 2 else 1
        }
        binding.switchAudioFocus.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(requestAudioFocus = checked) }
        }
        binding.switchVoiceCommMode.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(voiceCommRouting = checked) }
            binding.tvRouteMetric.text = if (checked) "MIC OVERRIDE + BOOST" else "STANDARD MEDIA"
        }
        binding.switchOverlapSounds.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(allowOverlap = checked) }
        }
        binding.switchStopOnExit.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(stopOnExit = checked) }
        }
        binding.switchStartOnBoot.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(startOnBoot = checked) }
        }
        binding.switchOverlayShowLabels.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(overlayShowLabels = checked) }
        }
        binding.switchReducedMotion.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(reducedMotion = checked) }
            soundGridAdapter.reducedMotion = checked
        }

        binding.seekOverlayOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val safePct = progress.coerceIn(35, 100)
                binding.tvOverlayOpacityLabel.text = "PANEL OPACITY: $safePct%"
                if (fromUser) {
                    container.settingsRepository.update { it.copy(overlayOpacity = safePct / 100f) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnResetOverlayBounds.setOnClickListener { view ->
            UiAnimator.bounceTap(view)
            container.overlayController.resetPositionAndSize()
            Toast.makeText(this, "BUBBLE RESET", Toast.LENGTH_SHORT).show()
        }

        binding.btnGrantOverlay.setOnClickListener {
            startActivity(container.permissionManager.buildOverlaySettingsIntent())
        }

        binding.btnGrantMic.setOnClickListener {
            if (!container.permissionManager.hasRecordAudioPermission()) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                Toast.makeText(this, "MIC READY", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGrantNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !container.permissionManager.hasNotificationPermission()
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                SoundboardForegroundService.start(this)
                Toast.makeText(this, "ENGINE ACTIVE", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectSettingsSubPage(index: Int) {
        binding.subPageAudio.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.subPageOverlay.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.subPageSystem.visibility = if (index == 2) View.VISIBLE else View.GONE

        val tabs = listOf(binding.btnSetTabAudio, binding.btnSetTabOverlay, binding.btnSetTabSystem)
        tabs.forEachIndexed { idx, btn ->
            val selected = idx == index
            btn.setBackgroundResource(
                if (selected) R.drawable.bg_3d_button_active else R.drawable.bg_3d_button
            )
            btn.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        }
    }

    private fun setupAboutScreen() {
        binding.tvAboutVersion.text =
            "VERSION ${BuildConfig.VERSION_NAME} • BUILD ${BuildConfig.VERSION_CODE}"

        binding.btnOpenGithub.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/notxpvp0182-collab/Soundboard")
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.filteredSounds.collectLatest { sounds ->
                        soundGridAdapter.submitList(sounds)
                        binding.tvTotalSoundsMetric.text = sounds.size.toString()
                        binding.emptyStateContainer.visibility =
                            if (sounds.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.activeSoundIds.collectLatest { activeIds ->
                        soundGridAdapter.updateActiveSoundIds(activeIds)
                        binding.tvActiveStreamsCount.text =
                            if (activeIds.isEmpty()) "IDLE" else "${activeIds.size} LIVE"
                    }
                }
                launch {
                    viewModel.settingsFlow.collectLatest { settings ->
                        val volPct = (settings.masterVolume * 100).roundToInt()
                        binding.seekMasterVolume.progress = volPct
                        binding.tvMasterVolumeValue.text = "$volPct%"
                    }
                }
                launch {
                    viewModel.isOverlayShowing.collectLatest { showing ->
                        binding.btnToggleOverlay.text = if (showing) "● OVERLAY" else "OVERLAY"
                        binding.btnToggleOverlay.setTextColor(if (showing) Color.BLACK else Color.WHITE)
                        binding.btnToggleOverlay.setBackgroundResource(
                            if (showing) R.drawable.bg_3d_button_active else R.drawable.bg_3d_button
                        )
                    }
                }
                launch {
                    container.micMonitorMixer.activeRouteLabel.collectLatest { route ->
                        val overrideOn = container.settingsRepository.current.voiceCommRouting
                        binding.tvRouteMetric.text = if (overrideOn) "MIC BOOST • $route" else route
                    }
                }
                launch {
                    viewModel.currentFilter.collectLatest { filter ->
                        updateCategoryChipColors(filter)
                    }
                }
                launch {
                    container.messages.collectLatest { msg ->
                        Toast.makeText(this@MainActivity, msg.uppercase(), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateCategoryChipColors(activeFilter: String) {
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)

        val pairs = listOf(
            binding.btnCatAll to MainViewModel.FILTER_ALL,
            binding.btnCatFavorites to MainViewModel.FILTER_FAVORITES,
            binding.btnCatRecent to MainViewModel.FILTER_RECENT,
            binding.btnCatSfx to MainViewModel.FILTER_SFX,
            binding.btnCatVoice to MainViewModel.FILTER_VOICE,
            binding.btnCatMusic to MainViewModel.FILTER_MUSIC
        )

        for ((btn, filter) in pairs) {
            val active = activeFilter == filter
            btn.setTextColor(if (active) Color.BLACK else secondary)
            btn.setBackgroundResource(
                if (active) R.drawable.bg_3d_button_active else R.drawable.bg_3d_button
            )
        }
    }

    private fun launchAudioImporter() {
        try {
            importAudioLauncher.launch(arrayOf("audio/*", "application/ogg"))
        } catch (_: Exception) {
            Toast.makeText(this, "FILE PICKER UNAVAILABLE", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleOverlayToggleRequest() {
        if (!container.permissionManager.canDrawOverlays()) {
            AlertDialog.Builder(this)
                .setTitle("OVERLAY PERMISSION")
                .setMessage("ALLOW DISPLAY OVER OTHER APPS FOR FLOATING ROUND ICON.")
                .setPositiveButton("SETTINGS") { _, _ ->
                    startActivity(container.permissionManager.buildOverlaySettingsIntent())
                }
                .setNegativeButton("CANCEL", null)
                .show()
            return
        }

        SoundboardForegroundService.start(this)
        if (!container.overlayController.isShowing.value) {
            container.settingsRepository.update { it.copy(overlayMinimized = true) }
        }
        container.overlayController.toggleOverlay()
    }

    private fun showSoundEditDialog(item: SoundItem) {
        val dialogBinding = DialogSoundEditBinding.inflate(LayoutInflater.from(this))
        dialogBinding.etEditSoundName.setText(item.name)
        dialogBinding.etEditSoundCategory.setText(item.category)
        val initialPct = (item.volume * 100).roundToInt().coerceIn(0, 100)
        dialogBinding.seekEditVolume.progress = initialPct
        dialogBinding.tvEditVolumeValue.text = "SOUND GAIN: $initialPct%"
        dialogBinding.cbEditFavorite.isChecked = item.isFavorite

        dialogBinding.seekEditVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                dialogBinding.tvEditVolumeValue.text = "SOUND GAIN: $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnMoveUp.setOnClickListener {
            viewModel.moveSoundOrder(item.id, moveEarlier = true)
            dialog.dismiss()
        }

        dialogBinding.btnMoveDown.setOnClickListener {
            viewModel.moveSoundOrder(item.id, moveEarlier = false)
            dialog.dismiss()
        }

        dialogBinding.btnDeleteSound.setOnClickListener {
            dialog.dismiss()
            confirmDeleteSound(item)
        }

        dialogBinding.btnCancelEdit.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveEdit.setOnClickListener {
            val newName = dialogBinding.etEditSoundName.text?.toString()?.trim()
                .orEmpty().ifEmpty { item.name }
            val newCat = dialogBinding.etEditSoundCategory.text?.toString()?.trim()
                .orEmpty().ifEmpty { "SFX" }
            val newVol = (dialogBinding.seekEditVolume.progress / 100f).coerceIn(0f, 1f)
            val newFav = dialogBinding.cbEditFavorite.isChecked

            viewModel.saveEditedSound(
                item.copy(
                    name = newName,
                    category = newCat,
                    volume = newVol,
                    isFavorite = newFav
                )
            )
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun confirmDeleteSound(item: SoundItem) {
        AlertDialog.Builder(this)
            .setTitle("DELETE SOUND?")
            .setMessage("REMOVE \"${item.name.uppercase()}\"?")
            .setPositiveButton("DELETE") { _, _ ->
                viewModel.deleteSound(item)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showAudioRoutingDiagnosticDialog() {
        val caps = container.micMonitorMixer.inspectCapabilities()
        AlertDialog.Builder(this)
            .setTitle("MIC OVERRIDE & ROUTING")
            .setMessage(caps.routingSummary)
            .setPositiveButton("CLOSE", null)
            .show()
    }

    private fun refreshPermissionStatusLabels() {
        val overlayGranted = container.permissionManager.canDrawOverlays()
        val micGranted = container.permissionManager.hasRecordAudioPermission()
        val notifGranted = container.permissionManager.hasNotificationPermission()

        binding.tvPermOverlayStatus.text =
            "OVERLAY: ${if (overlayGranted) "ACTIVE ✓" else "REQUIRED"}"
        binding.tvPermMicStatus.text =
            "MIC ACCESS: ${if (micGranted) "ACTIVE ✓" else "AUTO"}"
        binding.tvPermNotifStatus.text =
            "BACKGROUND: ${if (notifGranted) "ACTIVE ✓" else "OPTIONAL"}"
    }
}
