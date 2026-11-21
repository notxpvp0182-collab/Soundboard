package com.aura.soundboard.overlay

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aura.soundboard.R
import com.aura.soundboard.databinding.ItemOverlaySoundBinding
import com.aura.soundboard.models.SoundItem

class OverlaySoundAdapter(
    private val onSoundClicked: (SoundItem, View) -> Unit
) : ListAdapter<SoundItem, OverlaySoundAdapter.OverlayViewHolder>(DiffCallback) {

    private var activeIds: Set<Long> = emptySet()
    private var showLabels: Boolean = true

    fun updateActiveIds(newActiveIds: Set<Long>) {
        if (activeIds == newActiveIds) return
        activeIds = newActiveIds
        notifyDataSetChanged()
    }

    fun updateShowLabels(show: Boolean) {
        if (showLabels == show) return
        showLabels = show
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OverlayViewHolder {
        val binding = ItemOverlaySoundBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OverlayViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OverlayViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class OverlayViewHolder(
        private val binding: ItemOverlaySoundBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SoundItem) {
            val isPlaying = activeIds.contains(item.id)
            binding.tvOverlaySoundName.text = if (showLabels) {
                item.name
            } else {
                item.name.take(3).uppercase()
            }
            binding.btnOverlaySoundRoot.setBackgroundResource(
                if (isPlaying) R.drawable.bg_glass_card_active else R.drawable.bg_glass_card
            )
            binding.viewOverlayActiveDot.setBackgroundResource(
                if (isPlaying) R.drawable.ic_stop else R.drawable.ic_play
            )
            binding.btnOverlaySoundRoot.setOnClickListener { view ->
                onSoundClicked(item, view)
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
