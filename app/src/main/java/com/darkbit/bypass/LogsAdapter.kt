package com.darkbit.bypass

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class LogsAdapter : RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

    private var items: List<LogEntry> = emptyList()

    fun update(entries: List<LogEntry>) {
        items = entries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_entry, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dot = itemView.findViewById<View>(R.id.viewLogDot)
        private val tvMessage = itemView.findViewById<TextView>(R.id.tvLogMessage)
        private val tvTime = itemView.findViewById<TextView>(R.id.tvLogTime)

        fun bind(entry: LogEntry) {
            tvMessage.text = entry.message
            tvTime.text = formatDisplayTime(entry.time)

            val colorRes = when (entry.level) {
                LogLevel.SUCCESS -> R.color.log_success
                LogLevel.WARNING -> R.color.log_warning
                LogLevel.ERROR -> R.color.log_error
                LogLevel.INFO -> R.color.log_info
            }
            val color = ContextCompat.getColor(itemView.context, colorRes)
            dot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            tvMessage.setTextColor(
                if (entry.level == LogLevel.ERROR) color
                else ContextCompat.getColor(itemView.context, R.color.text_primary)
            )
        }

        private fun formatDisplayTime(time: String): String {
            if (time == "--:--:--") return ""
            val parts = time.split(' ')
            return if (parts.size >= 2) parts[1] else time
        }
    }
}
