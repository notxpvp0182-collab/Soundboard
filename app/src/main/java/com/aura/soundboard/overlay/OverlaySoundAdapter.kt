package com.aura.soundboard.overlay

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aura.soundboard.R
import com.aura.soundboard.databinding.ItemOverlaySoundBinding
import com.aura.soundboard.models.SoundItem
import com.aura.soundboard.utils.UiAnimator

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
        holder.bind(getItem(position), position)
    }

    inner class OverlayViewHolder(
        private val binding: ItemOverlaySoundBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SoundItem, position: Int) {
            val isPlaying = activeIds.contains(item.id)
            val prefix = String.format("#%02d  ", position + 1)
            binding.tvOverlaySoundName.text = if (showLabels) {
                prefix + item.name.uppercase()
            } else {
                prefix + item.name.take(4).uppercase()
            }
            binding.tvOverlaySoundName.setTextColor(
                if (isPlaying) Color.parseColor("#10B981") else Color.WHITE
            )
            binding.btnOverlaySoundRoot.setBackgroundResource(
                if (isPlaying) R.drawable.bg_3d_button_active else R.drawable.bg_3d_button
            )
            binding.viewOverlayActiveDot.setBackgroundResource(
                if (isPlaying) R.drawable.ic_stop else R.drawable.ic_play
            )
            binding.btnOverlaySoundRoot.setOnClickListener { view ->
                UiAnimator.bounceTap(view)
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
