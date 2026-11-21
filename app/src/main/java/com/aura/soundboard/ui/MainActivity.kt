package com.aura.soundboard.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val container by lazy { (application as SoundboardApp).container }
    private val viewModel: MainViewModel by viewModels { MainViewModel.factory(container) }

    private lateinit var soundGridAdapter: SoundGridAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    private val importAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            viewModel.importAudioUris(uris)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshPermissionStatusLabels()
        if (granted) {
            attemptToggleMicMonitor()
        } else {
            showPermissionDeniedDialog(
                title = "Microphone Permission Denied",
                message = "Microphone access is only used when you enable local voice + soundboard monitoring. You can grant it anytime from Android Settings."
            )
        }
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

        setupRecyclerView()
        setupTopAndMasterBars()
        setupSearchAndCategories()
        setupBottomNavigation()
        setupSettingsControls()
        setupAboutScreen()
        observeState()

        if (!container.settingsRepository.current.onboardingCompleted) {
            showFirstLaunchOnboardingDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatusLabels()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && container.settingsRepository.current.stopOnExit &&
            !container.overlayController.isShowing.value
        ) {
            container.audioEngine.stopAll()
            container.micMonitorMixer.stopMonitoring()
        }
    }

    private fun setupRecyclerView() {
        val cols = if (container.settingsRepository.current.compactGrid) 3 else 2
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
        )

        binding.rvSoundGrid.layoutManager = gridLayoutManager
        binding.rvSoundGrid.adapter = soundGridAdapter
        binding.rvSoundGrid.itemAnimator = null // Keeps 1-2GB RAM UI latency minimal
    }

    private fun setupTopAndMasterBars() {
        binding.btnAudioRoutingGuide.setOnClickListener {
            showAudioRoutingDiagnosticDialog()
        }

        binding.btnToggleOverlay.setOnClickListener { view ->
            HapticHelper.performTap(view, container.settingsRepository.current.hapticFeedback)
            handleOverlayToggleRequest()
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

        binding.btnMicMonitorToggle.setOnClickListener { view ->
            HapticHelper.performTap(view, container.settingsRepository.current.hapticFeedback)
            if (container.micMonitorMixer.isMonitoring.value) {
                container.micMonitorMixer.stopMonitoring()
                Toast.makeText(this, "Microphone monitor stopped.", Toast.LENGTH_SHORT).show()
            } else {
                if (!container.permissionManager.hasRecordAudioPermission()) {
                    showMicrophoneExplanationBeforeRequest()
                } else {
                    attemptToggleMicMonitor()
                }
            }
        }

        binding.btnStopAllMain.setOnClickListener { view ->
            HapticHelper.performTap(view, container.settingsRepository.current.hapticFeedback)
            viewModel.stopAllSounds()
        }

        binding.btnImportFirstSound.setOnClickListener {
            launchAudioImporter()
        }
    }

    private fun setupSearchAndCategories() {
        binding.etSearchSounds.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnCatAll.setOnClickListener { viewModel.setCategoryFilter(MainViewModel.FILTER_ALL) }
        binding.btnCatFavorites.setOnClickListener { viewModel.setCategoryFilter(MainViewModel.FILTER_FAVORITES) }
        binding.btnCatRecent.setOnClickListener { viewModel.setCategoryFilter(MainViewModel.FILTER_RECENT) }
        binding.btnCatSfx.setOnClickListener { viewModel.setCategoryFilter(MainViewModel.FILTER_SFX) }
        binding.btnCatVoice.setOnClickListener { viewModel.setCategoryFilter(MainViewModel.FILTER_VOICE) }
        binding.btnCatMusic.setOnClickListener { viewModel.setCategoryFilter(MainViewModel.FILTER_MUSIC) }
    }

    private fun setupBottomNavigation() {
        binding.btnNavSoundboard.setOnClickListener { switchTab(TAB_BOARD) }
        binding.btnNavImport.setOnClickListener { launchAudioImporter() }
        binding.btnNavSettings.setOnClickListener { switchTab(TAB_SETTINGS) }
        binding.btnNavAbout.setOnClickListener { switchTab(TAB_ABOUT) }
    }

    private fun switchTab(tabIndex: Int) {
        binding.viewSoundboardContainer.visibility = if (tabIndex == TAB_BOARD) View.VISIBLE else View.GONE
        binding.searchAndFilterCard.visibility = if (tabIndex == TAB_BOARD) View.VISIBLE else View.GONE
        binding.viewSettingsContainer.visibility = if (tabIndex == TAB_SETTINGS) View.VISIBLE else View.GONE
        binding.viewAboutContainer.visibility = if (tabIndex == TAB_ABOUT) View.VISIBLE else View.GONE

        val cyan = ContextCompat.getColor(this, R.color.accent_cyan)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)

        binding.btnNavSoundboard.setTextColor(if (tabIndex == TAB_BOARD) cyan else secondary)
        binding.btnNavSettings.setTextColor(if (tabIndex == TAB_SETTINGS) cyan else secondary)
        binding.btnNavAbout.setTextColor(if (tabIndex == TAB_ABOUT) cyan else secondary)

        if (tabIndex == TAB_SETTINGS) {
            refreshPermissionStatusLabels()
        }
    }

    private fun setupSettingsControls() {
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
        binding.tvOverlayOpacityLabel.text = "Overlay Opacity: $opacityPct%"

        binding.switchHapticFeedback.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(hapticFeedback = checked) }
        }
        binding.switchCompactGrid.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(compactGrid = checked) }
            gridLayoutManager.spanCount = if (checked) 3 else 2
        }
        binding.switchAudioFocus.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(requestAudioFocus = checked) }
        }
        binding.switchVoiceCommMode.setOnCheckedChangeListener { _, checked ->
            container.settingsRepository.update { it.copy(voiceCommRouting = checked) }
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
        }

        binding.seekOverlayOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val safePct = progress.coerceIn(35, 100)
                binding.tvOverlayOpacityLabel.text = "Overlay Opacity: $safePct%"
                if (fromUser) {
                    container.settingsRepository.update { it.copy(overlayOpacity = safePct / 100f) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnResetOverlayBounds.setOnClickListener {
            container.overlayController.resetPositionAndSize()
            Toast.makeText(this, "Overlay position and size reset.", Toast.LENGTH_SHORT).show()
        }

        binding.btnGrantOverlay.setOnClickListener {
            startActivity(container.permissionManager.buildOverlaySettingsIntent())
        }

        binding.btnGrantMic.setOnClickListener {
            if (container.permissionManager.hasRecordAudioPermission()) {
                Toast.makeText(this, "Microphone permission is already granted.", Toast.LENGTH_SHORT).show()
            } else {
                showMicrophoneExplanationBeforeRequest()
            }
        }

        binding.btnGrantNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !container.permissionManager.hasNotificationPermission()
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                SoundboardForegroundService.start(this)
                Toast.makeText(this, "Background notification service ready.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAboutScreen() {
        binding.tvAboutVersion.text =
            "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

        binding.btnOpenGithub.setOnClickListener {
            try {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/notxpvp0182-collab/Soundboard")
                )
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "No web browser found.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.filteredSounds.collectLatest { sounds ->
                        soundGridAdapter.submitList(sounds)
                        binding.emptyStateContainer.visibility =
                            if (sounds.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.activeSoundIds.collectLatest { activeIds ->
                        soundGridAdapter.updateActiveSoundIds(activeIds)
                        binding.tvActiveStreamsCount.text =
                            if (activeIds.isEmpty()) "Idle" else "${activeIds.size} Playing"
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
                        binding.btnToggleOverlay.text = if (showing) "Hide Overlay" else "Overlay"
                    }
                }
                launch {
                    viewModel.isMicMonitoring.collectLatest { monitoring ->
                        binding.btnMicMonitorToggle.alpha = if (monitoring) 1.0f else 0.45f
                    }
                }
                launch {
                    viewModel.currentFilter.collectLatest { filter ->
                        updateCategoryChipColors(filter)
                    }
                }
                launch {
                    container.messages.collectLatest { msg ->
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateCategoryChipColors(activeFilter: String) {
        val cyan = ContextCompat.getColor(this, R.color.accent_cyan)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)

        binding.btnCatAll.setTextColor(if (activeFilter == MainViewModel.FILTER_ALL) cyan else secondary)
        binding.btnCatFavorites.setTextColor(if (activeFilter == MainViewModel.FILTER_FAVORITES) cyan else secondary)
        binding.btnCatRecent.setTextColor(if (activeFilter == MainViewModel.FILTER_RECENT) cyan else secondary)
        binding.btnCatSfx.setTextColor(if (activeFilter == MainViewModel.FILTER_SFX) cyan else secondary)
        binding.btnCatVoice.setTextColor(if (activeFilter == MainViewModel.FILTER_VOICE) cyan else secondary)
        binding.btnCatMusic.setTextColor(if (activeFilter == MainViewModel.FILTER_MUSIC) cyan else secondary)
    }

    private fun launchAudioImporter() {
        try {
            importAudioLauncher.launch(arrayOf("audio/*", "application/ogg"))
        } catch (_: Exception) {
            Toast.makeText(this, "System file picker is unavailable on this device.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleOverlayToggleRequest() {
        if (!container.permissionManager.canDrawOverlays()) {
            AlertDialog.Builder(this)
                .setTitle("Enable Floating Overlay Permission")
                .setMessage(
                    "To display the movable glass Soundboard over games or chat apps, Android requires the \"Display over other apps\" permission."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(container.permissionManager.buildOverlaySettingsIntent())
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        SoundboardForegroundService.start(this)
        container.overlayController.toggleOverlay()
    }

    private fun showMicrophoneExplanationBeforeRequest() {
        AlertDialog.Builder(this)
            .setTitle("Microphone Local Monitor Permission")
            .setMessage(
                "Aura requests RECORD_AUDIO only when you activate Live Microphone Monitoring.\n\n" +
                    "• Your microphone is never recorded to storage or sent over any network.\n" +
                    "• Note: Unrooted Android isolates app microphone inputs. Local monitoring mixes your voice and soundboard locally for headphones or hardware AUX loopback."
            )
            .setPositiveButton("Continue") { _, _ ->
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun attemptToggleMicMonitor(allowSpeaker: Boolean = false) {
        val result = container.micMonitorMixer.startMonitoring(allowWithoutHeadset = allowSpeaker)
        result.onSuccess {
            Toast.makeText(this, "Live Microphone Monitor active.", Toast.LENGTH_SHORT).show()
        }.onFailure { err ->
            if (err is IllegalStateException && !allowSpeaker) {
                AlertDialog.Builder(this)
                    .setTitle("Headset Feedback Warning")
                    .setMessage(
                        "${err.message}\n\nDo you still want to enable live microphone monitoring on the phone speaker?"
                    )
                    .setPositiveButton("Enable Anyway") { _, _ ->
                        attemptToggleMicMonitor(allowSpeaker = true)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(this, err.message ?: "Unable to start mic monitor.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSoundEditDialog(item: SoundItem) {
        val dialogBinding = DialogSoundEditBinding.inflate(LayoutInflater.from(this))
        dialogBinding.etEditSoundName.setText(item.name)
        dialogBinding.etEditSoundCategory.setText(item.category)
        val initialPct = (item.volume * 100).roundToInt().coerceIn(0, 100)
        dialogBinding.seekEditVolume.progress = initialPct
        dialogBinding.tvEditVolumeValue.text = "Individual Sound Volume: $initialPct%"
        dialogBinding.cbEditFavorite.isChecked = item.isFavorite

        dialogBinding.seekEditVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                dialogBinding.tvEditVolumeValue.text = "Individual Sound Volume: $progress%"
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
            .setTitle("Delete Sound?")
            .setMessage("Remove \"${item.name}\" from your soundboard? Your original storage file outside the app will not be deleted.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteSound(item)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAudioRoutingDiagnosticDialog() {
        val caps = container.micMonitorMixer.inspectCapabilities()
        AlertDialog.Builder(this)
            .setTitle("Audio Routing & Mic Architecture")
            .setMessage(caps.routingSummary)
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showFirstLaunchOnboardingDialog() {
        AlertDialog.Builder(this)
            .setTitle("Welcome to Aura Soundboard")
            .setMessage(
                "Designed for speed, low RAM usage (1–2 GB devices), and complete privacy.\n\n" +
                    "• Import Audio: Add MP3, WAV, OGG, M4A, or FLAC clips via Android's secure file picker.\n" +
                    "• Floating Glass Overlay: Grant 'Display over other apps' only when you want the movable, resizable overlay.\n" +
                    "• Optional Mic Monitor: Requested only if you enable local voice monitoring.\n" +
                    "• 100% Offline: Zero internet permissions."
            )
            .setPositiveButton("Get Started") { _, _ ->
                container.settingsRepository.update { it.copy(onboardingCompleted = true) }
            }
            .setNeutralButton("Configure Overlay") { _, _ ->
                container.settingsRepository.update { it.copy(onboardingCompleted = true) }
                handleOverlayToggleRequest()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionDeniedDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("App Settings") { _, _ ->
                startActivity(container.permissionManager.buildAppDetailsSettingsIntent())
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun refreshPermissionStatusLabels() {
        val overlayGranted = container.permissionManager.canDrawOverlays()
        val micGranted = container.permissionManager.hasRecordAudioPermission()
        val notifGranted = container.permissionManager.hasNotificationPermission()

        binding.tvPermOverlayStatus.text =
            "Floating Overlay: ${if (overlayGranted) "Allowed ✓" else "Not Allowed"}"
        binding.tvPermMicStatus.text =
            "Microphone Monitor: ${if (micGranted) "Allowed ✓" else "On-Demand"}"
        binding.tvPermNotifStatus.text =
            "Background Service: ${if (notifGranted) "Allowed ✓" else "Not Allowed"}"
    }

    companion object {
        private const val TAB_BOARD = 0
        private const val TAB_SETTINGS = 1
        private const val TAB_ABOUT = 2
    }
}
