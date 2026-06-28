package com.darkbit.bypass

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppInfoItem(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    var isSelected: Boolean
)

class AppsAdapter(
    private var allItems: List<AppInfoItem>,
    private val onSelectionChanged: (AppInfoItem, Boolean) -> Unit
) : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {

    private var filteredItems: List<AppInfoItem> = allItems
    var isEnabled: Boolean = true
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun updateData(newItems: List<AppInfoItem>) {
        allItems = newItems
        filteredItems = newItems
        notifyDataSetChanged()
    }

    fun setFilteredItems(newFilteredItems: List<AppInfoItem>) {
        filteredItems = newFilteredItems
        notifyDataSetChanged()
    }

    fun getItems(): List<AppInfoItem> = allItems

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val item = filteredItems[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = filteredItems.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPackage: TextView = itemView.findViewById(R.id.tvAppPackage)
        private val cbSelected: CheckBox = itemView.findViewById(R.id.cbAppSelected)

        fun bind(item: AppInfoItem) {
            tvName.text = item.name
            tvPackage.text = item.packageName
            if (item.icon != null) {
                ivIcon.setImageDrawable(item.icon)
            } else {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            cbSelected.isChecked = item.isSelected
            cbSelected.isEnabled = isEnabled
            itemView.alpha = if (isEnabled) 1.0f else 0.5f

            itemView.setOnClickListener {
                if (!isEnabled) return@setOnClickListener
                val newChecked = !cbSelected.isChecked
                cbSelected.isChecked = newChecked
                item.isSelected = newChecked
                onSelectionChanged(item, newChecked)
            }
        }
    }
}
