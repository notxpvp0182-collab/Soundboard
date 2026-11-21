package com.aura.soundboard.ui.components

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
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
    private val onVolumeChanged: (SoundItem, Float) -> Unit,
    private val onEditClicked: (SoundItem) -> Unit
) : ListAdapter<SoundItem, SoundGridAdapter.SoundViewHolder>(DiffCallback) {

    private var activeSoundIds: Set<Long> = emptySet()

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
        holder.bind(getItem(position))
    }

    inner class SoundViewHolder(
        private val binding: ItemSoundCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SoundItem) {
            val isPlaying = activeSoundIds.contains(item.id)
            binding.tvSoundName.text = item.name
            binding.tvSoundCategory.text = item.category.uppercase()

            binding.cardRoot.setBackgroundResource(
                if (isPlaying) R.drawable.bg_glass_card_active else R.drawable.bg_glass_card
            )
            binding.viewActiveIndicator.setBackgroundResource(
                if (isPlaying) R.drawable.ic_stop else R.drawable.ic_play
            )

            binding.btnFavorite.alpha = if (item.isFavorite) 1.0f else 0.32f
            binding.btnFavorite.setOnClickListener {
                onFavoriteToggle(item)
            }

            binding.btnMoreOptions.setOnClickListener {
                onEditClicked(item)
            }

            val pct = (item.volume * 100).roundToInt().coerceIn(0, 100)
            binding.seekItemVolume.setOnSeekBarChangeListener(null)
            binding.seekItemVolume.progress = pct
            binding.tvItemVolume.text = "$pct%"

            binding.seekItemVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        binding.tvItemVolume.text = "$progress%"
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val progress = seekBar?.progress ?: pct
                    val newVol = (progress / 100f).coerceIn(0f, 1f)
                    onVolumeChanged(item, newVol)
                }
            })

            binding.btnPlayToggle.text = if (isPlaying) "■ Stop" else "▶ Play"
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
