package com.weblauncher.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.android.material.card.MaterialCardView

class NewsAdapter(
    private val onClick: (NewsItem) -> Unit,
    private val onLong: (NewsItem) -> Unit
) : RecyclerView.Adapter<NewsAdapter.VH>() {

    private var items: List<NewsItem> = emptyList()

    fun submit(list: List<NewsItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_news, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        try { holder.bind(items[position], position) }
        catch (e: Exception) { CrashReporter.log(holder.itemView.context, "notizia", e) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view)

    private fun VH.bind(item: NewsItem, position: Int) {
        val ctx = itemView.context
        val palette = AppRepository.palette(ctx)
        val accent = com.google.android.material.color.MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        (itemView as MaterialCardView).apply {
            setCardBackgroundColor(palette.card)
            strokeColor = palette.stroke
        }
        val meta = itemView.findViewById<TextView>(R.id.newsMeta)
        val title = itemView.findViewById<TextView>(R.id.newsTitle)
        val also = itemView.findViewById<TextView>(R.id.newsAlso)
        val hero = itemView.findViewById<ImageView>(R.id.newsHero)
        val thumb = itemView.findViewById<ImageView>(R.id.newsThumb)
        meta.text = "${item.source} · ${ago(item.time)}"
        meta.setTextColor(accent)
        title.text = item.title
        title.setTextColor(palette.text)
        val others = item.also ?: emptyList()
        also.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        also.text = "Anche su: " + others.take(4).joinToString(", ")
        also.setTextColor(palette.textMuted)
        // big picture for the first story and for stories covered by several outlets
        val big = item.image != null && (position == 0 || others.size >= 2)
        hero.visibility = if (big) View.VISIBLE else View.GONE
        thumb.visibility = if (!big && item.image != null) View.VISIBLE else View.GONE
        val target = if (big) hero else thumb
        if (item.image != null) target.load(item.image) {
            crossfade(true)
            if (!big) transformations(RoundedCornersTransformation(24f))
            listener(onError = { _, _ -> target.visibility = View.GONE })
        }
        itemView.setOnClickListener { onClick(item) }
        itemView.setOnLongClickListener { onLong(item); true }
    }

    companion object {
        fun ago(time: Long): String {
            val m = (System.currentTimeMillis() - time) / 60_000
            return when {
                m < 1 -> "ora"
                m < 60 -> "$m min fa"
                m < 24 * 60 -> "${m / 60} h fa"
                m < 48 * 60 -> "ieri"
                else -> "${m / (24 * 60)} giorni fa"
            }
        }
    }
}
