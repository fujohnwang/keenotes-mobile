package cn.keevol.keenotes.ui.note

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.keevol.keenotes.data.entity.PendingNote
import cn.keevol.keenotes.databinding.ItemPendingNoteBinding

class PendingNotesAdapter : ListAdapter<PendingNote, PendingNotesAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPendingNoteBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemPendingNoteBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(note: PendingNote) {
            binding.dateText.text = note.createdAt
            binding.contentText.text = note.content
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PendingNote>() {
        override fun areItemsTheSame(oldItem: PendingNote, newItem: PendingNote) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PendingNote, newItem: PendingNote) = oldItem == newItem
    }
}
