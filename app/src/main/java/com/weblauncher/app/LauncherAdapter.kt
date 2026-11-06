package com.weblauncher.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation

sealed class ListRow {
    data class Header(val title: String) : ListRow()
    data class App(val item: AppItem) : ListRow()
}

class LauncherAdapter(
    private val onClick: (AppItem) -> Unit,
    private val onLongClick: (AppItem, View) -> Unit,
    private val onReorder: (fromAppId: String, toAppId: String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<ListRow>()

    fun submit(apps: List<AppItem>, categories: List<String>, query: String) {
        rows.clear()
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) apps else apps.filter {
            it.name.lowercase().contains(q) ||
                it.url.lowercase().contains(q) ||
                it.category.lowercase().contains(q)
        }

        if (filtered.isEmpty()) {
            notifyDataSetChanged()
            return
        }

        val pinned = filtered.filter { it.pinned }.sortedBy { it.sortOrder }
        if (pinned.isNotEmpty() && q.isEmpty()) {
            rows.add(ListRow.Header("Preferiti"))
            pinned.forEach { rows.add(ListRow.App(it)) }
        }

        val catOrder = categories.ifEmpty { filtered.map { it.category }.distinct() }
        for (cat in catOrder) {
            val group = filtered
                .filter { it.category == cat && (q.isNotEmpty() || !it.pinned) }
                .sortedBy { it.sortOrder }
            if (group.isEmpty()) continue
            rows.add(ListRow.Header(cat))
            group.forEach { rows.add(ListRow.App(it)) }
        }

        val known = catOrder.toSet()
        filtered.filter { it.category !in known && (q.isNotEmpty() || !it.pinned) }
            .groupBy { it.category }
            .forEach { (cat, list) ->
                rows.add(ListRow.Header(cat))
                list.sortedBy { it.sortOrder }.forEach { rows.add(ListRow.App(it)) }
            }

        notifyDataSetChanged()
    }

    fun isAppRow(position: Int): Boolean =
        rows.getOrNull(position) is ListRow.App

    fun onItemMove(from: Int, to: Int): Boolean {
        if (from == to) return false
        val fromRow = rows.getOrNull(from) as? ListRow.App ?: return false
        val toRow = rows.getOrNull(to) as? ListRow.App ?: return false
        rows.removeAt(from)
        rows.add(to, fromRow)
        notifyItemMoved(from, to)
        onReorder(fromRow.item.id, toRow.item.id)
        return true
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

    fun itemTouchHelper(): ItemTouchHelper {
        val cb = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (!isAppRow(from) || !isAppRow(to)) return false
                return onItemMove(from, to)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.alpha = 0.75f
                    viewHolder.itemView.scaleX = 1.05f
                    viewHolder.itemView.scaleY = 1.05f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                viewHolder.itemView.scaleX = 1f
                viewHolder.itemView.scaleY = 1f
            }
        }
        return ItemTouchHelper(cb)
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.sectionTitle)
        fun bind(t: String) { title.text = t }
    }

    inner class AppVH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.appName)
        private val initials: TextView = view.findViewById(R.id.appInitials)
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val pin: TextView = view.findViewById(R.id.pinBadge)

        fun bind(item: AppItem) {
            name.text = item.name
            initials.text = item.name.take(2).uppercase()
            initials.visibility = View.VISIBLE
            pin.visibility = if (item.pinned) View.VISIBLE else View.GONE

            val host = try { Uri.parse(item.url).host } catch (_: Exception) { null }
            if (!host.isNullOrBlank()) {
                val fav = "https://www.google.com/s2/favicons?domain=$host&sz=128"
                icon.visibility = View.VISIBLE
                icon.load(fav) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(16f))
                    listener(
                        onSuccess = { _, _ -> initials.visibility = View.GONE },
                        onError = { _, _ -> icon.visibility = View.GONE; initials.visibility = View.VISIBLE }
                    )
                }
            } else {
                icon.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onLongClick(item, itemView)
                true
            }
        }
    }
}
