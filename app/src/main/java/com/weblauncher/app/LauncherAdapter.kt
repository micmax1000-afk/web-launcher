package com.weblauncher.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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
    data class Folder(val name: String, val items: List<AppItem>) : ListRow()
}

class LauncherAdapter(
    private val onClick: (AppItem) -> Unit,
    private val onMenu: (AppItem) -> Unit,
    private val onHeader: (String) -> Unit,
    private val onReorder: (fromAppId: String, toAppId: String) -> Unit,
    private val onFolder: (String, List<AppItem>) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var spanCount = 4
    var hideNames = false

    /** Apps of a category, with apps sharing a folder collapsed into one folder tile. */
    private fun addGroup(group: List<AppItem>, foldersOn: Boolean) {
        if (!foldersOn) { group.forEach { rows.add(ListRow.App(it)) }; return }
        val done = HashSet<String>()
        group.forEach { a ->
            val f = (a.folder as String?)?.takeIf { it.isNotBlank() }
            if (f == null) rows.add(ListRow.App(a))
            else if (done.add(f)) rows.add(ListRow.Folder(f, group.filter { (it.folder as String?) == f }))
        }
    }

    private val rows = mutableListOf<ListRow>()

    fun submit(apps: List<AppItem>, categories: List<String>, query: String, mostUsed: Boolean = false) {
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

        if (mostUsed && q.isEmpty() && apps.size >= 6) {
            val top = apps.filter { it.openCount > 0 }.sortedByDescending { it.openCount }.take(4)
            if (top.size >= 2) {
                rows.add(ListRow.Header(MOST_USED))
                top.forEach { rows.add(ListRow.App(it)) }
            }
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
            addGroup(group, q.isEmpty())
        }

        val known = catOrder.toSet()
        filtered.filter { it.category !in known && (q.isNotEmpty() || !it.pinned) }
            .groupBy { it.category }
            .forEach { (cat, list) ->
                rows.add(ListRow.Header(cat))
                addGroup(list.sortedBy { it.sortOrder }, q.isEmpty())
            }

        notifyDataSetChanged()
    }

    companion object {
        const val MOST_USED = "Più usate"
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
        is ListRow.Folder -> 2
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
            is ListRow.Folder -> (holder as AppVH).bindFolder(row.name, row.items)
        }
    }

    fun spanSizeLookup() = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int {
            return if (rows.getOrNull(position) is ListRow.Header) spanCount else 1
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

            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.alpha = 0.8f
                    viewHolder.itemView.scaleX = 1.08f
                    viewHolder.itemView.scaleY = 1.08f
                    viewHolder.itemView.elevation = 12f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                viewHolder.itemView.scaleX = 1f
                viewHolder.itemView.scaleY = 1f
                viewHolder.itemView.elevation = 0f
            }
        }
        val helper = ItemTouchHelper(cb)
        touchHelper = helper
        return helper
    }

    private var touchHelper: ItemTouchHelper? = null
    private var attached: RecyclerView? = null

    fun attachTo(recyclerView: RecyclerView) {
        attached = recyclerView
        (touchHelper ?: itemTouchHelper()).attachToRecyclerView(recyclerView)
    }

    fun startDrag(appId: String) {
        val pos = rows.indexOfFirst { (it as? ListRow.App)?.item?.id == appId }
        if (pos < 0) return
        val vh = attached?.findViewHolderForAdapterPosition(pos) ?: return
        touchHelper?.startDrag(vh)
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.sectionTitle)
        fun bind(t: String) {
            title.setTextColor(AppRepository.palette(itemView.context).textMuted)
            title.text = t
            itemView.setOnLongClickListener {
                onHeader(t)
                true
            }
        }
    }

    inner class AppVH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.appName)
        private val initials: TextView = view.findViewById(R.id.appInitials)
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val pin: TextView = view.findViewById(R.id.pinBadge)
        private val menuBtn: ImageButton = view.findViewById(R.id.btnAppMenu)

        fun bindFolder(folderName: String, items: List<AppItem>) {
            val palette = AppRepository.palette(itemView.context)
            (itemView as? com.google.android.material.card.MaterialCardView)?.apply {
                setCardBackgroundColor(palette.card); strokeColor = palette.stroke
            }
            name.setTextColor(palette.text)
            name.text = folderName
            name.visibility = if (hideNames) View.GONE else View.VISIBLE
            icon.visibility = View.GONE
            initials.visibility = View.VISIBLE
            initials.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14f * itemView.resources.displayMetrics.density; setColor(palette.iconTile)
            }
            initials.text = "📁"
            initials.textSize = 20f
            pin.visibility = View.VISIBLE
            pin.text = items.size.toString()
            menuBtn.visibility = View.GONE
            itemView.setOnClickListener { onFolder(folderName, items) }
            itemView.setOnLongClickListener { onFolder(folderName, items); true }
        }

        fun bind(item: AppItem) {
            initials.textSize = 14f
            name.visibility = if (hideNames) View.GONE else View.VISIBLE
            val palette = AppRepository.palette(itemView.context)
            (itemView as? com.google.android.material.card.MaterialCardView)?.apply {
                setCardBackgroundColor(palette.card)
                strokeColor = palette.stroke
            }
            name.setTextColor(palette.text)
            val density = itemView.resources.displayMetrics.density
            fun tile() = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(palette.iconTile)
                setStroke((density).toInt().coerceAtLeast(1), palette.stroke)
            }
            initials.background = tile()
            icon.background = tile()
            name.text = item.name
            initials.text = item.name.take(2).uppercase()
            initials.visibility = View.VISIBLE
            pin.visibility = if (item.pinned) View.VISIBLE else View.GONE
            if (item.locked) { pin.visibility = View.VISIBLE; pin.text = "🔒" } else pin.text = "★"

            val local = item.iconPath?.let { java.io.File(it) }?.takeIf { it.exists() }
            val host = try { Uri.parse(item.url).host } catch (_: Exception) { null }
            val source: Any? = local ?: host?.let { "https://www.google.com/s2/favicons?domain=$it&sz=128" }
            if (source != null) {
                icon.visibility = View.VISIBLE
                icon.load(source) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(16f))
                    listener(
                        onSuccess = { _, _ -> initials.visibility = View.GONE },
                        onError = { _, _ ->
                            icon.visibility = View.GONE
                            initials.visibility = View.VISIBLE
                        }
                    )
                }
            } else {
                icon.visibility = View.GONE
            }

            menuBtn.visibility = View.GONE
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onMenu(item)
                true
            }
        }
    }
}
