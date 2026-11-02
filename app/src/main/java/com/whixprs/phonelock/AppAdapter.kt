package com.whixprs.phonelock

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

data class AppEntry(val packageName: String, val label: String, val icon: Drawable)

class AppAdapter(
    private val isEnabled: (String) -> Boolean,
    private val onToggle: (String, Boolean) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var query = ""

    fun submit(apps: List<AppEntry>) {
        all = apps
        applyFilter()
    }

    fun filter(q: String) {
        query = q.trim()
        applyFilter()
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun refresh() = notifyDataSetChanged()

    private fun applyFilter() {
        shown = if (query.isEmpty()) all else all.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        refresh()
    }

    override fun getItemCount() = shown.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = shown[position]
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.pkg.text = app.packageName
        holder.toggle.isChecked = isEnabled(app.packageName)
        holder.itemView.setOnClickListener {
            val newState = !holder.toggle.isChecked
            holder.toggle.isChecked = newState
            onToggle(app.packageName, newState)
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)
        val pkg: TextView = view.findViewById(R.id.pkg)
        val toggle: MaterialSwitch = view.findViewById(R.id.toggle)
    }
}
