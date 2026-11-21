package com.aura.soundboard.ui.components

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aura.soundboard.R
import com.aura.soundboard.databinding.ItemSoundCardBinding
import com.aura.soundboard.models.SoundItem
import kotlin.math.roundToInt

class SoundGridAdapter(
    private val onPlayToggle: (SoundItem, View) -> Unit,
    private val onFavoriteToggle: (SoundItem) -> Unit,
    @Suppress("UNUSED_PARAMETER") private val onVolumeChanged: (SoundItem, Float) -> Unit,
    private val onEditClicked: (SoundItem) -> Unit
) : ListAdapter<SoundItem, SoundGridAdapter.SoundViewHolder>(DiffCallback) {

    private var activeSoundIds: Set<Long> = emptySet()

    // Soft NexuSec badge tint palette (Blue, Purple, Emerald, Amber, Indigo)
    private val badgeFillColors = intArrayOf(
        Color.parseColor("#183B82F6"),
        Color.parseColor("#18A855F7"),
        Color.parseColor("#1810B981"),
        Color.parseColor("#18F59E0B"),
        Color.parseColor("#186366F1")
    )
    private val badgeStrokeColors = intArrayOf(
        Color.parseColor("#403B82F6"),
        Color.parseColor("#40A855F7"),
        Color.parseColor("#4010B981"),
        Color.parseColor("#40F59E0B"),
        Color.parseColor("#406366F1")
    )
    private val badgeTextColors = intArrayOf(
        Color.parseColor("#60A5FA"),
        Color.parseColor("#C084FC"),
        Color.parseColor("#34D399"),
        Color.parseColor("#FBBF24"),
        Color.parseColor("#818CF8")
    )

    fun updateActiveSoundIds(newIds: Set<Long>) {
        if (activeSoundIds == newIds) return
        activeSoundIds = newIds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SoundViewHolder {
        val binding = ItemSoundCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SoundViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SoundViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class SoundViewHolder(
        private val binding: ItemSoundCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SoundItem, position: Int) {
            val isPlaying = activeSoundIds.contains(item.id)
            val paletteIdx = (position % badgeFillColors.size).coerceAtLeast(0)

            val serialPrefix = String.format("#%02d  ", position + 1)
            binding.tvSoundName.text = serialPrefix + item.name.uppercase()
            binding.tvSoundCategory.text = item.category.uppercase()
            binding.tvSoundCategory.setTextColor(badgeTextColors[paletteIdx])

            val density = binding.root.resources.displayMetrics.density
            val badgeBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(badgeFillColors[paletteIdx])
                setStroke((1f * density).toInt().coerceAtLeast(1), badgeStrokeColors[paletteIdx])
            }
            binding.badgeContainer.background = badgeBg

            binding.cardRoot.setBackgroundResource(
                if (isPlaying) R.drawable.bg_glass_card_active else R.drawable.bg_glass_card
            )
            binding.viewActiveIndicator.setBackgroundResource(
                if (isPlaying) R.drawable.ic_stop else R.drawable.ic_sound_wave
            )

            binding.btnFavorite.alpha = if (item.isFavorite) 1.0f else 0.25f
            binding.btnFavorite.setOnClickListener {
                onFavoriteToggle(item)
            }

            binding.btnMoreOptions.setOnClickListener {
                onEditClicked(item)
            }

            val pct = (item.volume * 100).roundToInt().coerceIn(0, 100)
            binding.tvItemVolume.text = "VOL $pct%"

            binding.btnPlayToggle.text = if (isPlaying) "■ STOP" else "▶ PLAY"
            binding.btnPlayToggle.setBackgroundResource(
                if (isPlaying) R.drawable.bg_3d_button_active else R.drawable.bg_3d_button
            )

            binding.btnPlayToggle.setOnClickListener { view ->
                onPlayToggle(item, view)
            }
            binding.cardRoot.setOnClickListener { view ->
                onPlayToggle(item, view)
            }
            binding.cardRoot.setOnLongClickListener {
                onEditClicked(item)
                true
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SoundItem>() {
        override fun areItemsTheSame(oldItem: SoundItem, newItem: SoundItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SoundItem, newItem: SoundItem): Boolean =
            oldItem == newItem
    }
}
