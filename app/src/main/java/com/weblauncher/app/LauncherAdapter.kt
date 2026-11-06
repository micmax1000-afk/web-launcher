package com.weblauncher.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

sealed class ListRow {
    data class Header(val title: String) : ListRow()
    data class App(val item: AppItem) : ListRow()
}

class LauncherAdapter(
    private val onClick: (AppItem) -> Unit,
    private val onLongClick: (AppItem, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<ListRow>()

    fun submit(apps: List<AppItem>, categories: List<String>, query: String) {
        rows.clear()
        val q = query.trim().lowercase()
        var filtered = if (q.isEmpty()) apps else apps.filter {
            it.name.lowercase().contains(q) ||
                it.url.lowercase().contains(q) ||
                it.category.lowercase().contains(q)
        }

        if (filtered.isEmpty()) {
            notifyDataSetChanged()
            return
        }

        val pinned = filtered.filter { it.pinned }
        if (pinned.isNotEmpty() && q.isEmpty()) {
            rows.add(ListRow.Header("Preferiti"))
            pinned.forEach { rows.add(ListRow.App(it)) }
        }

        val catOrder = categories.ifEmpty { filtered.map { it.category }.distinct() }
        for (cat in catOrder) {
            val group = filtered.filter { it.category == cat && (q.isNotEmpty() || !it.pinned) }
            if (group.isEmpty()) continue
            rows.add(ListRow.Header(cat))
            group.forEach { rows.add(ListRow.App(it)) }
        }

        val known = catOrder.toSet()
        filtered.filter { it.category !in known && (q.isNotEmpty() || !it.pinned) }
            .groupBy { it.category }
            .forEach { (cat, list) ->
                rows.add(ListRow.Header(cat))
                list.forEach { rows.add(ListRow.App(it)) }
            }

        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ListRow.Header -> 0
        is ListRow.App -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderVH(inf.inflate(R.layout.item_section_header, parent, false))
        } else {
            AppVH(inf.inflate(R.layout.item_app, parent, false))
        }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ListRow.Header -> (holder as HeaderVH).bind(row.title)
            is ListRow.App -> (holder as AppVH).bind(row.item)
        }
    }

    fun spanSizeLookup() = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int {
            return if (rows.getOrNull(position) is ListRow.Header) 3 else 1
        }
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.sectionTitle)
        fun bind(t: String) { title.text = t }
    }

    inner class AppVH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.appName)
        private val initials: TextView = view.findViewById(R.id.appInitials)
        private val pin: TextView = view.findViewById(R.id.pinBadge)

        fun bind(item: AppItem) {
            name.text = item.name
            initials.text = item.name.take(2).uppercase()
            pin.visibility = if (item.pinned) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onLongClick(item, itemView)
                true
            }
        }
    }
}
