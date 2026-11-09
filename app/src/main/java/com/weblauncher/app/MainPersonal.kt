package com.weblauncher.app

import android.content.Context.LOCATION_SERVICE
import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.weblauncher.app.MainActivity.CatalogApp
import com.weblauncher.app.MainActivity.Companion.AI_SYSTEM
import com.weblauncher.app.MainActivity.Companion.DESKTOP_UA
import com.weblauncher.app.MainActivity.Companion.EXTRA_OPEN_PLAYER
import com.weblauncher.app.MainActivity.Companion.EXTRA_WIDGET
import com.weblauncher.app.MainActivity.Companion.MENU_AI
import com.weblauncher.app.MainActivity.Companion.NEWS_ALL
import com.weblauncher.app.MainActivity.Companion.STATE_TAB
import com.weblauncher.app.MainActivity.Companion.SUGGEST_TAG
import com.weblauncher.app.MainActivity.Companion.TAB_AUDIO
import com.weblauncher.app.MainActivity.Companion.TAB_BROWSER
import com.weblauncher.app.MainActivity.Companion.TAB_HOME
import com.weblauncher.app.MainActivity.Companion.TAB_NEWS
import com.weblauncher.app.MainActivity.Companion.TAB_SETTINGS
import com.weblauncher.app.MainActivity.Companion.TAB_TOOLS
import com.weblauncher.app.MainActivity.ReaderDoc
import com.weblauncher.app.MainActivity.Song
import com.weblauncher.app.MainActivity.TodayEntry
import com.weblauncher.app.MainActivity.WebTab
import com.weblauncher.app.MainActivity.YtChannel
import java.util.Locale
import java.util.UUID

internal fun MainActivity.addExpenseDialog(amount: Double? = null, desc: String = "", cat: String? = null, time: Long = System.currentTimeMillis(), replaceId: String? = null) {
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val amountEt = TextInputEditText(this).apply {
        hint = "Importo (€)"; setSingleLine()
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        amount?.let { setText(String.format(Locale.ROOT, "%.2f", it)) }
    }
    val descEt = TextInputEditText(this).apply { hint = "Per cosa? (es. pizza, benzina)"; setSingleLine(); setText(desc) }
    val chips = com.google.android.material.chip.ChipGroup(this).apply { isSingleSelection = true; setPadding(0, dp(10), 0, 0) }
    var chosen = cat ?: Expenses.guess(desc)
    val chipViews = Expenses.CATS.map { c ->
        com.google.android.material.chip.Chip(this).apply {
            text = c; isCheckable = true; isChecked = c == chosen
            setOnClickListener { chosen = c }
        }.also { chips.addView(it) }
    }
    descEt.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(e: Editable?) {
            if (cat != null) return
            chosen = Expenses.guess(e?.toString().orEmpty())
            chipViews.forEach { it.isChecked = it.text.toString() == chosen }
        }
    })
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0)
        addView(amountEt); addView(descEt); addView(chips)
    }
    MaterialAlertDialogBuilder(this).setTitle("Nuova spesa").setView(android.widget.ScrollView(this).apply { addView(box) })
        .setPositiveButton("Salva") { _, _ ->
            val v = Calc.num(amountEt.text?.toString().orEmpty())
            if (v == null || v <= 0) { toast("Importo non valido"); return@setPositiveButton }
            Expenses.save(this, Expenses.load(this).filter { it.id != replaceId } +
                Expense(UUID.randomUUID().toString(), v, descEt.text?.toString()?.trim().orEmpty().ifBlank { "Spesa" }, chosen, time))
            toast("💰 ${Expenses.fmt(v)} · ${chosen.substringAfter(' ')}")
            expensesRefresh?.invoke()
        }
        .setNegativeButton("Annulla", null).show()
    if (amount == null) amountEt.requestFocus()
}

internal fun MainActivity.showExpensesPage() {
    openPage("Spese", "Aggiungi", { _ -> addExpenseDialog() }) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun render() {
            body.removeAllViews()
            val m = expenseMonth
            val monthName = java.text.SimpleDateFormat("MMMM yyyy", Locale.ITALIAN).format(m.time).replaceFirstChar { it.uppercase() }
            val nav = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            fun navBtn(t: String, delta: Int) = TextView(this).apply {
                text = t; textSize = 24f; setTextColor(accent); setPadding(dp(16), dp(4), dp(16), dp(4))
                setOnClickListener { expenseMonth = (expenseMonth.clone() as java.util.Calendar).apply { add(java.util.Calendar.MONTH, delta) }; render() }
            }
            nav.addView(navBtn("‹", -1))
            nav.addView(TextView(this).apply {
                text = monthName; textSize = 17f; gravity = android.view.Gravity.CENTER; setTextColor(palette.text)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            })
            nav.addView(navBtn("›", 1))
            body.addView(nav)
            val all = Expenses.load(this)
            val list = all.filter {
                val c = java.util.Calendar.getInstance().apply { timeInMillis = it.time }
                c.get(java.util.Calendar.YEAR) == m.get(java.util.Calendar.YEAR) && c.get(java.util.Calendar.MONTH) == m.get(java.util.Calendar.MONTH)
            }.sortedByDescending { it.time }
            val total = list.sumOf { it.amount }
            body.addView(TextView(this).apply {
                text = Expenses.fmt(total); textSize = 34f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER; setTextColor(palette.text); setPadding(0, dp(6), 0, dp(2))
            })
            body.addView(TextView(this).apply {
                text = "${list.size} spese"; textSize = 13f; gravity = android.view.Gravity.CENTER; setTextColor(palette.textMuted)
            })
            val actions = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER; setPadding(0, dp(12), 0, dp(4))
            }
            fun act(label: String, onClick: () -> Unit) = actions.addView(com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label; isAllCaps = false
                layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) }
                setOnClickListener { onClick() }
            })
            act("🧾 Scontrino") { scanPurpose = "expense"; scanDocument() }
            act("💼 Finanze") { showFinancePage() }
            act("Esporta") {
                val csv = "Data;Importo;Categoria;Descrizione\n" + all.sortedBy { it.time }.joinToString("\n") {
                    java.text.SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(java.util.Date(it.time)) + ";" +
                        String.format(Locale.ITALIAN, "%.2f", it.amount) + ";" + it.cat.substringAfter(' ') + ";" + it.desc.replace(";", ",")
                }
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, csv); putExtra(Intent.EXTRA_SUBJECT, "Spese.csv")
                }, "Esporta spese"))
            }
            body.addView(actions)
            if (list.isEmpty()) { emptyLabel(body, "Nessuna spesa in questo mese.\nA voce: «speso 12 euro pizza»."); return }
            sectionLabel(body, "PER CATEGORIA")
            val byCat = list.groupBy { it.cat }.mapValues { it.value.sumOf { e -> e.amount } }.entries.sortedByDescending { it.value }
            val max = byCat.first().value
            byCat.forEach { (cat, sum) ->
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(6))
                }
                val top = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
                top.addView(TextView(this).apply { text = cat; textSize = 14f; setTextColor(palette.text); layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f) })
                top.addView(TextView(this).apply { text = Expenses.fmt(sum); textSize = 14f; setTextColor(palette.text); setTypeface(typeface, android.graphics.Typeface.BOLD) })
                row.addView(top)
                row.addView(View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 4 * d; setColor(accent) }
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(8)).apply { topMargin = dp(4) }
                    post { layoutParams = (layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                        width = ((row.width - dp(20)) * (sum / max)).toInt().coerceAtLeast(dp(6)) }; requestLayout() }
                })
                body.addView(row)
            }
            sectionLabel(body, "MOVIMENTI")
            val fmt = java.text.SimpleDateFormat("EEE d", Locale.ITALIAN)
            list.forEach { e ->
                listRow(body, "${Expenses.fmt(e.amount)} · ${e.desc}", "${fmt.format(java.util.Date(e.time))} · ${e.cat}",
                    onClick = { addExpenseDialog(e.amount, e.desc, e.cat, e.time, replaceId = e.id) },
                    onRemove = { Expenses.save(this, Expenses.load(this).filter { it.id != e.id }); render() })
            }
        }
        render()
        expensesRefresh = { render() }
        dialog.setOnDismissListener { expensesRefresh = null }
    }
}

/** Scanned receipt or bill: the AI reads amount, date and due date; adds the expense and a payment reminder. */
internal fun MainActivity.readReceipt(images: List<java.io.File>, pdf: java.io.File?) {
    if (!aiReady()) return
    if (images.isEmpty()) { toast("Immagini non disponibili"); return }
    toast("✨ Leggo lo scontrino…")
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date())
    ai.ask(listOf("user" to "Oggi è $today. Leggi il documento nelle immagini (scontrino, ricevuta o bolletta) e rispondi SOLO con JSON: " +
        "{\"tipo\":\"scontrino|bolletta|altro\",\"fornitore\":\"...\",\"importo\":12.34,\"data\":\"yyyy-MM-dd\",\"scadenza\":\"yyyy-MM-dd o vuoto\",\"descrizione\":\"breve\"}"),
        "Rispondi solo con JSON valido.", object : AiBridge.Listener {
            override fun onDone(text: String, model: String) {
                val o = runCatching { org.json.JSONObject(Regex("\\{[\\s\\S]*\\}").find(text)!!.value) }.getOrNull()
                if (o == null) { toast("Non sono riuscito a leggerlo: inseriscilo a mano"); addExpenseDialog(); return }
                val amount = o.optDouble("importo", Double.NaN).takeIf { !it.isNaN() } ?: Calc.num(o.optString("importo"))
                val who = o.optString("fornitore").trim()
                val desc = listOf(who, o.optString("descrizione").trim()).filter { it.isNotEmpty() && it != "null" }.distinct().joinToString(" · ").ifBlank { "Spesa" }
                val p = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                val date = runCatching { p.parse(o.optString("data"))!!.time }.getOrNull() ?: System.currentTimeMillis()
                val due = runCatching { p.parse(o.optString("scadenza"))!!.time }.getOrNull()?.takeIf { it > System.currentTimeMillis() - 86_400_000L }
                val bill = o.optString("tipo").startsWith("boll")
                val msg = buildString {
                    append(if (bill) "🏠 Bolletta" else "🧾 Scontrino"); if (who.isNotEmpty()) append(" · $who")
                    append("\n\nImporto: ${amount?.let { Expenses.fmt(it) } ?: "?"}")
                    append("\nData: ${java.text.SimpleDateFormat("d MMMM yyyy", Locale.ITALIAN).format(java.util.Date(date))}")
                    due?.let { append("\nScadenza: ${java.text.SimpleDateFormat("d MMMM yyyy", Locale.ITALIAN).format(java.util.Date(it))}") }
                }
                val b = MaterialAlertDialogBuilder(this@readReceipt).setTitle("Ho letto").setMessage(msg)
                    .setPositiveButton(if (due != null) "Aggiungi spesa e promemoria" else "Aggiungi spesa") { _, _ ->
                        addExpenseDialog(amount, desc, if (bill) "🏠 Casa e bollette" else null, if (due != null) due else date)
                        if (due != null) {
                            val at = java.util.Calendar.getInstance().apply { timeInMillis = due; add(java.util.Calendar.DAY_OF_YEAR, -1); set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
                                .let { if (it < System.currentTimeMillis()) System.currentTimeMillis() + 3600_000L else it }
                            Reminders.add(this@readReceipt, "Pagare ${if (bill) "la bolletta" else ""} $who ${amount?.let { Expenses.fmt(it) } ?: ""} (scade domani)".replace(Regex("\\s+"), " "), at)
                            toast("⏰ Promemoria il giorno prima della scadenza")
                        }
                    }
                    .setNegativeButton("Annulla", null)
                if (pdf != null) b.setNeutralButton("🎫 Salva il documento") { _, _ -> addToWallet(desc, "pdf", pdf) }
                b.show()
            }
            override fun onError(message: String, noKey: Boolean) { toast(message); addExpenseDialog() }
        }, images.take(4).map { Triple("image", "image/jpeg", "/files/scan/" + it.name) })
}

@SuppressLint("MissingPermission")
internal fun MainActivity.currentLocation(cb: (android.location.Location?) -> Unit) {
    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
        pendingLocation = cb
        placeLocationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        return
    }
    val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
    val provider = when {
        lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) -> android.location.LocationManager.GPS_PROVIDER
        lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) -> android.location.LocationManager.NETWORK_PROVIDER
        else -> null
    }
    if (provider == null) { toast("Attiva la posizione (GPS) del telefono"); cb(null); return }
    toast("📍 Cerco la posizione…")
    var done = false
    val finish = { l: android.location.Location? ->
        if (!done) {
            done = true
            cb(l ?: listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time })
        }
    }
    val listener = object : android.location.LocationListener {
        override fun onLocationChanged(l: android.location.Location) { finish(l) }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }
    try {
        if (android.os.Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(this)) { l -> finish(l) }
        else @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
    } catch (_: Exception) { finish(null) }
    uiHandler.postDelayed({ if (!done) { runCatching { lm.removeUpdates(listener) }; finish(null) } }, 20_000)
}

internal fun MainActivity.navigateTo(lat: Double, lon: Double, walk: Boolean) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=${if (walk) "w" else "d"}")).setPackage("com.google.android.apps.maps")
    try { startActivity(app) } catch (_: Exception) {
        showTab(TAB_BROWSER)
        openInBrowser("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=${if (walk) "walking" else "driving"}")
    }
}

internal fun MainActivity.parkHere() {
    currentLocation { loc ->
        if (loc == null) { toast("Posizione non trovata, riprova all'aperto"); return@currentLocation }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val id = UUID.randomUUID().toString()
        var photo: String? = null
        val note = TextInputEditText(this).apply { hint = "Nota (es. piano -2, colonna B)"; setSingleLine() }
        val photoBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "📷 Foto del posto"; isAllCaps = false
        }
        photoBtn.setOnClickListener {
            capturePhoto(java.io.File(Places.dir(this), "$id.jpg")) { f -> if (f != null) { photo = f.absolutePath; photoBtn.text = "✓ Foto salvata" } }
        }
        val timers = intArrayOf(0, 30, 60, 90, 120, 180)
        var timer = 0
        val chips = com.google.android.material.chip.ChipGroup(this).apply { isSingleSelection = true }
        listOf("Nessuna scadenza", "30 min", "1 ora", "1 h 30", "2 ore", "3 ore").forEachIndexed { i, t ->
            chips.addView(com.google.android.material.chip.Chip(this).apply { text = t; isCheckable = true; isChecked = i == 0; setOnClickListener { timer = timers[i] } })
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0)
            addView(note); addView(photoBtn); addView(TextView(this@parkHere).apply { text = "Il parcheggio scade tra:"; setPadding(0, dp(12), 0, 0) }); addView(chips)
        }
        MaterialAlertDialogBuilder(this).setTitle("🚗 Parcheggio salvato (±${loc.accuracy.toInt()} m)").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Salva") { _, _ ->
                val until = if (timer > 0) System.currentTimeMillis() + timer * 60_000L else 0L
                val list = Places.load(this).filter { !it.parking }.toMutableList()
                list.add(0, Place(id, "Parcheggio", loc.latitude, loc.longitude, System.currentTimeMillis(), note.text?.toString()?.trim().orEmpty(), photo, true, until))
                Places.save(this, list)
                if (until > 0) Reminders.add(this, "🚗 Il parcheggio scade tra 10 minuti", until - 10 * 60_000L)
                toast("🚗 Parcheggio salvato"); placesRefresh?.invoke(); renderParkBar()
            }
            .setNegativeButton("Annulla", null).show()
    }
}

internal fun MainActivity.saveCurrentPlace() {
    currentLocation { loc ->
        if (loc == null) { toast("Posizione non trovata"); return@currentLocation }
        val d = resources.displayMetrics.density
        val et = TextInputEditText(this).apply { hint = "Nome (es. Casa dei nonni, Medico)"; setSingleLine() }
        val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
        MaterialAlertDialogBuilder(this).setTitle("📍 Salva questo posto").setView(box)
            .setPositiveButton("Salva") { _, _ ->
                val n = et.text?.toString()?.trim().orEmpty().ifBlank { "Luogo" }
                Places.save(this, Places.load(this) + Place(UUID.randomUUID().toString(), n, loc.latitude, loc.longitude, System.currentTimeMillis()))
                toast("📍 $n salvato"); placesRefresh?.invoke()
            }.setNegativeButton("Annulla", null).show()
    }
}

internal fun MainActivity.renderParkBar() {
    val bar = findViewById<TextView>(R.id.parkBar)
    val p = Places.parking(this)
    if (p == null) { bar.isVisible = false; return }
    val left = if (p.until > 0) {
        val m = (p.until - System.currentTimeMillis()) / 60_000
        if (m > 0) " · scade tra $m min" else " · scaduto"
    } else ""
    bar.text = "🚗 Parcheggiato ${NewsAdapter.ago(p.time)}$left   ·   Portami lì ›"
    bar.isVisible = true
    bar.setOnClickListener { navigateTo(p.lat, p.lon, true) }
    bar.setOnLongClickListener { showPlacesPage(); true }
}

internal fun MainActivity.showPlacesPage() {
    openPage("Parcheggio e luoghi") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun render() {
            body.removeAllViews()
            val park = Places.parking(this)
            if (park != null) {
                sectionLabel(body, "🚗 IL TUO PARCHEGGIO")
                val card = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 18 * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
                }
                card.addView(TextView(this).apply {
                    text = "Salvato ${NewsAdapter.ago(park.time)}" + (if (park.until > 0) " · scade alle " +
                        java.text.SimpleDateFormat("HH:mm", Locale.ITALIAN).format(java.util.Date(park.until)) else "")
                    textSize = 16f; setTextColor(palette.text); setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                if (park.note.isNotBlank()) card.addView(TextView(this).apply { text = park.note; textSize = 15f; setTextColor(palette.text); setPadding(0, dp(4), 0, 0) })
                park.photo?.let { p -> card.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(160)).apply { topMargin = dp(10) }
                    load(java.io.File(p)) { transformations(coil.transform.RoundedCornersTransformation(24f)) }
                    setOnClickListener { showImageFullscreen(p) }
                }) }
                val btns = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
                btns.addView(com.google.android.material.button.MaterialButton(this).apply {
                    text = "🚶 Portami lì"; isAllCaps = false; layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
                    setOnClickListener { navigateTo(park.lat, park.lon, true) }
                })
                btns.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Ho ripreso l'auto"; isAllCaps = false; layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                    setOnClickListener {
                        park.photo?.let { java.io.File(it).delete() }
                        Places.save(this@showPlacesPage, Places.load(this@showPlacesPage).filter { !it.parking }); render(); renderParkBar()
                    }
                })
                card.addView(btns)
                body.addView(card)
            }
            val actions = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(dp(4), dp(14), dp(4), dp(4)) }
            actions.addView(com.google.android.material.button.MaterialButton(this).apply {
                text = if (park == null) "🚗 Ho parcheggiato qui" else "🚗 Nuovo parcheggio"; isAllCaps = false
                layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(8) }
                setOnClickListener { parkHere() }
            })
            actions.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "📍 Salva questo posto"; isAllCaps = false
                layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(56), 1f)
                setOnClickListener { saveCurrentPlace() }
            })
            body.addView(actions)
            val places = Places.load(this).filter { !it.parking }
            if (places.isNotEmpty()) sectionLabel(body, "📍 LUOGHI SALVATI")
            places.forEach { pl ->
                listRow(body, pl.name, "Salvato ${NewsAdapter.ago(pl.time)} · tocca per andarci",
                    onClick = { navigateTo(pl.lat, pl.lon, false) },
                    onLong = {
                        MaterialAlertDialogBuilder(this).setTitle(pl.name).setItems(arrayOf("🚗 In auto", "🚶 A piedi", "Condividi posizione")) { _, w ->
                            when (w) {
                                0 -> navigateTo(pl.lat, pl.lon, false)
                                1 -> navigateTo(pl.lat, pl.lon, true)
                                2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${pl.name}: https://maps.google.com/?q=${pl.lat},${pl.lon}")
                                }, "Condividi"))
                            }
                        }.show()
                    },
                    onRemove = { Places.save(this, Places.load(this).filter { it.id != pl.id }); render() })
            }
        }
        render()
        placesRefresh = { render() }
        dialog.setOnDismissListener { placesRefresh = null }
    }
}

internal fun MainActivity.askVaultTitle(onOk: (String) -> Unit) {
    val d = resources.displayMetrics.density
    val et = TextInputEditText(this).apply { hint = "Es. Carta d'identità fronte, Tessera sanitaria"; setSingleLine() }
    val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
    MaterialAlertDialogBuilder(this).setTitle("Nome").setView(box)
        .setPositiveButton("Salva") { _, _ -> onOk(et.text?.toString()?.trim().orEmpty().ifBlank { "Documento" }) }
        .setNegativeButton("Annulla", null).show()
}

internal fun MainActivity.saveVault(title: String, kind: String, data: ByteArray) {
    io.execute {
        val ok = runCatching { Vault.put(this, VaultItem(UUID.randomUUID().toString(), title, kind, System.currentTimeMillis()), data) }.isSuccess
        runOnUiThread { toast(if (ok) "🔐 Salvato nella cassaforte" else "Errore di cifratura"); vaultRefresh?.invoke() }
    }
}

internal fun MainActivity.secure(w: android.view.Window?) { w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }

internal fun MainActivity.showVault() {
    authenticate("Apri la cassaforte", strict = true) {
        openPage("Cassaforte", "Aggiungi", { _ ->
            MaterialAlertDialogBuilder(this).setItems(arrayOf("📷 Scatta una foto", "🖼 Dalla galleria", "🔢 Testo (codici, numeri)")) { _, w ->
                when (w) {
                    0 -> {
                        val tmp = java.io.File(cacheDir, "capture/vault_tmp.jpg")
                        capturePhoto(tmp) { f ->
                            if (f != null) {
                                val bytes = f.readBytes(); f.delete()
                                askVaultTitle { t -> saveVault(t, "photo", bytes) }
                            }
                        }
                    }
                    1 -> vaultPicker.launch("image/*")
                    2 -> {
                        val d = resources.displayMetrics.density
                        val title = TextInputEditText(this).apply { hint = "Nome (es. PIN SIM, IBAN)"; setSingleLine() }
                        val body = TextInputEditText(this).apply { hint = "Contenuto"; minLines = 3 }
                        val box = android.widget.LinearLayout(this).apply {
                            orientation = android.widget.LinearLayout.VERTICAL; setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
                            addView(title); addView(body)
                        }
                        val dlg = MaterialAlertDialogBuilder(this).setTitle("Testo protetto").setView(box)
                            .setPositiveButton("Salva") { _, _ ->
                                val t = body.text?.toString().orEmpty()
                                if (t.isNotBlank()) saveVault(title.text?.toString()?.trim().orEmpty().ifBlank { "Nota" }, "text", t.toByteArray(Charsets.UTF_8))
                            }.setNegativeButton("Annulla", null).create()
                        secure(dlg.window); dlg.show()
                    }
                }
            }.show()
        }) { _, body, dialog ->
            secure(dialog.window)
            vaultDialog = dialog
            fun render() {
                body.removeAllViews()
                val list = runCatching { Vault.load(this) }.getOrDefault(mutableListOf())
                if (list.isEmpty()) {
                    emptyLabel(body, "La cassaforte è vuota.\nFoto di documenti e codici, cifrati sul telefono\ne visibili solo con impronta o PIN.\n\nNon finiscono nel backup: restano solo su questo telefono.")
                    return
                }
                list.forEach { v ->
                    listRow(body, (if (v.kind == "photo") "🪪  " else "🔢  ") + v.title, "Aggiunto ${NewsAdapter.ago(v.time)}",
                        onClick = {
                            val data = runCatching { Vault.read(this, v.id) }.getOrNull() ?: run { toast("Impossibile aprire"); return@listRow }
                            if (v.kind == "photo") {
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                                val dlg = androidx.appcompat.app.AppCompatDialog(this, AppRepository.accentStyle(this))
                                dlg.setContentView(ImageView(this).apply {
                                    setImageBitmap(bmp); setBackgroundColor(android.graphics.Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER
                                    setOnClickListener { dlg.dismiss() }
                                })
                                dlg.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                                secure(dlg.window); dlg.show()
                            } else {
                                val t = String(data, Charsets.UTF_8)
                                val dlg = MaterialAlertDialogBuilder(this).setTitle(v.title).setMessage(t)
                                    .setPositiveButton("Copia") { _, _ -> copyText(v.title, t) }.setNegativeButton("Chiudi", null).create()
                                secure(dlg.window); dlg.show()
                            }
                        },
                        onRemove = { confirmClear("Eliminare «${v.title}» dalla cassaforte?") { Vault.delete(this, v.id); render() } })
                }
                body.addView(TextView(this).apply {
                    text = "🔐 Cifrato sul telefono. Non va nel backup: se cambi telefono, rimetti i documenti a mano."
                    textSize = 12f; setTextColor(AppRepository.palette(this@showVault).textMuted); setPadding((10 * resources.displayMetrics.density).toInt(), 24, 0, 0)
                })
            }
            render()
            vaultRefresh = { render() }
            dialog.setOnDismissListener { vaultRefresh = null; vaultDialog = null }
        }
    }
}

internal fun MainActivity.pickDate(title: String, initial: Long, onPick: (Long) -> Unit) {
    val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        val l = java.util.Calendar.getInstance().apply { timeInMillis = initial }
        clear(); set(l.get(java.util.Calendar.YEAR), l.get(java.util.Calendar.MONTH), l.get(java.util.Calendar.DAY_OF_MONTH))
    }.timeInMillis
    val picker = com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
        .setTitleText(title).setSelection(utc).build()
    picker.addOnPositiveButtonClickListener { sel ->
        val u = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
        onPick(java.util.Calendar.getInstance().apply {
            clear(); set(u.get(java.util.Calendar.YEAR), u.get(java.util.Calendar.MONTH), u.get(java.util.Calendar.DAY_OF_MONTH), 9, 0)
        }.timeInMillis)
    }
    picker.show(supportFragmentManager, "date")
}

internal fun MainActivity.daysLeft(t: Long): String {
    val days = ((t - System.currentTimeMillis()) / 86_400_000L).toInt()
    return when {
        days < 0 -> "scaduta"
        days == 0 -> "oggi"
        days == 1 -> "domani"
        days < 60 -> "tra $days giorni"
        else -> "tra ${days / 30} mesi"
    }
}

internal fun MainActivity.saveDeadline(d: Deadline, replaceId: String? = null) {
    val list = Deadlines.load(this)
    list.firstOrNull { it.id == replaceId }?.reminders?.forEach { Reminders.remove(this, it) }
    Deadlines.save(this, list.filter { it.id != replaceId } + Deadlines.schedule(this, d))
    ensureNotificationPermission()
    deadlinesRefresh?.invoke()
}

internal fun MainActivity.editDeadline(kind: String, existing: Deadline? = null, presetTitle: String? = null, presetDate: Long? = null, photo: String? = null) {
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val title = TextInputEditText(this).apply {
        setSingleLine(); setText(existing?.title ?: presetTitle.orEmpty())
        hint = when (kind) { Deadlines.SUBSCRIPTION -> "Nome (es. Netflix, palestra)"; Deadlines.WARRANTY -> "Prodotto (es. lavatrice, telefono)"; else -> "Cosa scade" }
    }
    var date = existing?.date ?: presetDate ?: when (kind) {
        Deadlines.WARRANTY -> java.util.Calendar.getInstance().apply { add(java.util.Calendar.YEAR, 2) }.timeInMillis
        Deadlines.SUBSCRIPTION -> java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, 1) }.timeInMillis
        else -> System.currentTimeMillis() + 30 * 86_400_000L
    }
    var period = existing?.period ?: "mese"
    var photoPath = existing?.photo ?: photo
    val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
    if (kind == Deadlines.DEADLINE && existing == null) {
        val presets = com.google.android.material.chip.ChipGroup(this)
        listOf("Revisione auto", "Bollo auto", "Assicurazione auto", "Carta d'identità", "Passaporto", "Patente", "Tessera sanitaria").forEach { p ->
            presets.addView(com.google.android.material.chip.Chip(this).apply { text = p; setOnClickListener { title.setText(p) } })
        }
        box.addView(presets)
    }
    box.addView(title)
    val amount = TextInputEditText(this).apply {
        hint = "Costo (€)"; setSingleLine()
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        existing?.amount?.takeIf { it > 0 }?.let { setText(String.format(Locale.ROOT, "%.2f", it)) }
    }
    if (kind == Deadlines.SUBSCRIPTION) {
        box.addView(amount)
        val chips = com.google.android.material.chip.ChipGroup(this).apply { isSingleSelection = true }
        listOf("mese" to "Mensile", "anno" to "Annuale", "settimana" to "Settimanale").forEach { (k, l) ->
            chips.addView(com.google.android.material.chip.Chip(this).apply { text = l; isCheckable = true; isChecked = k == period; setOnClickListener { period = k } })
        }
        box.addView(chips)
    }
    val dateBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { isAllCaps = false }
    fun dateLabel() {
        dateBtn.text = when (kind) { Deadlines.SUBSCRIPTION -> "Prossimo rinnovo: "; Deadlines.WARRANTY -> "Garanzia fino al: "; else -> "Scade il: " } + dayFmt.format(java.util.Date(date))
    }
    dateLabel()
    dateBtn.setOnClickListener { pickDate("Data", date) { date = it; dateLabel() } }
    box.addView(dateBtn)
    if (kind == Deadlines.WARRANTY && existing == null) {
        val yrs = com.google.android.material.chip.ChipGroup(this).apply { isSingleSelection = true }
        listOf(1, 2, 3, 5).forEach { y ->
            yrs.addView(com.google.android.material.chip.Chip(this).apply {
                text = "$y ${if (y == 1) "anno" else "anni"} da oggi"; isCheckable = true; isChecked = y == 2
                setOnClickListener { date = java.util.Calendar.getInstance().apply { add(java.util.Calendar.YEAR, y) }.timeInMillis; dateLabel() }
            })
        }
        box.addView(yrs)
    }
    if (kind != Deadlines.SUBSCRIPTION) {
        val photoBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false; text = if (photoPath != null) "✓ Foto allegata" else if (kind == Deadlines.WARRANTY) "📷 Foto dello scontrino" else "📷 Foto del documento"
        }
        photoBtn.setOnClickListener {
            capturePhoto(java.io.File(Deadlines.dir(this), UUID.randomUUID().toString() + ".jpg")) { f -> if (f != null) { photoPath = f.absolutePath; photoBtn.text = "✓ Foto allegata" } }
        }
        box.addView(photoBtn)
    }
    MaterialAlertDialogBuilder(this).setTitle(when (kind) { Deadlines.SUBSCRIPTION -> "Abbonamento"; Deadlines.WARRANTY -> "Garanzia"; else -> "Scadenza" })
        .setView(android.widget.ScrollView(this).apply { addView(box) })
        .setPositiveButton("Salva") { _, _ ->
            val t = title.text?.toString()?.trim().orEmpty()
            if (t.isEmpty()) { toast("Manca il nome"); return@setPositiveButton }
            saveDeadline(Deadline(existing?.id ?: UUID.randomUUID().toString(), kind, t, date,
                Calc.num(amount.text?.toString().orEmpty()) ?: 0.0, if (kind == Deadlines.SUBSCRIPTION) period else null, photoPath), existing?.id)
            toast("Salvato: ti avviso prima della scadenza")
        }
        .setNegativeButton("Annulla", null).show()
}

/** Photo of a document: the AI reads what it is and when it expires. */
internal fun MainActivity.deadlineFromPhoto() {
    if (!aiReady()) return
    val f = java.io.File(scanDir, "doc_${System.currentTimeMillis()}.jpg")
    capturePhoto(f) { shot ->
        if (shot == null) return@capturePhoto
        io.execute {
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(shot.absolutePath)
                val sc = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
                val small = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
                shot.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
            runOnUiThread { deadlineFromImage(shot) }
        }
    }
}

/** An image already in the scan folder: the AI reads what it is and when it expires. */
internal fun MainActivity.deadlineFromImage(shot: java.io.File) {
    if (!aiReady()) return
    toast("✨ Leggo la scadenza…")
    ai.ask(listOf("user" to "Che documento è e quando scade? Rispondi SOLO con JSON {\"documento\":\"nome breve in italiano\",\"scadenza\":\"yyyy-MM-dd\"}."),
        "Rispondi solo con JSON valido.", object : AiBridge.Listener {
            override fun onDone(text: String, model: String) {
                val o = runCatching { org.json.JSONObject(Regex("\\{[\\s\\S]*\\}").find(text)!!.value) }.getOrNull()
                val date = runCatching {
                    java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(o!!.getString("scadenza"))!!.time + 9 * 3600_000L
                }.getOrNull()
                val keep = java.io.File(Deadlines.dir(this@deadlineFromImage), shot.name)
                runCatching { shot.copyTo(keep, true) }
                if (date == null) toast("Data non trovata: inseriscila a mano")
                editDeadline(Deadlines.DEADLINE, null, o?.optString("documento")?.takeIf { it.isNotBlank() && it != "null" }, date, keep.absolutePath)
            }
            override fun onError(message: String, noKey: Boolean) { toast(message) }
        }, listOf(Triple("image", "image/jpeg", "/files/scan/" + shot.name)))
}

internal fun MainActivity.showDeadlinesPage(tab: String = deadlineTab) {
    deadlineTab = tab
    Deadlines.advance(this)
    openPage("Scadenze", "Aggiungi", { _ ->
        if (deadlineTab == Deadlines.DEADLINE) {
            MaterialAlertDialogBuilder(this).setItems(arrayOf("✏️ Inserisci a mano", "📷 Fotografa il documento (l'AI legge la data)")) { _, w ->
                if (w == 0) editDeadline(Deadlines.DEADLINE) else deadlineFromPhoto()
            }.show()
        } else editDeadline(deadlineTab)
    }) { root, body, dialog ->
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val tabs = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(dp(16), 0, dp(16), dp(8)) }
        root.addView(tabs, 1)
        fun render() {
            tabs.removeAllViews()
            listOf(Deadlines.DEADLINE to "📆 Scadenze", Deadlines.SUBSCRIPTION to "🔁 Abbonamenti", Deadlines.WARRANTY to "🧾 Garanzie").forEach { (k, l) ->
                val active = k == deadlineTab
                tabs.addView(TextView(this).apply {
                    text = l; textSize = 13f; setTextColor(if (active) android.graphics.Color.WHITE else palette.text)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 16 * d; setColor(if (active) accent else palette.card); setStroke(d.toInt().coerceAtLeast(1), if (active) accent else palette.stroke)
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
                    setOnClickListener { deadlineTab = k; render() }
                })
            }
            body.removeAllViews()
            val list = Deadlines.load(this).filter { it.kind == deadlineTab }.sortedBy { it.date }
            if (deadlineTab == Deadlines.SUBSCRIPTION && list.isNotEmpty()) {
                val month = list.sumOf { Deadlines.monthly(it) }
                body.addView(TextView(this).apply {
                    text = "${Expenses.fmt(month)} al mese · ${Expenses.fmt(month * 12)} all'anno"
                    textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text)
                    gravity = android.view.Gravity.CENTER; setPadding(0, dp(6), 0, dp(10))
                })
            }
            if (list.isEmpty()) {
                emptyLabel(body, when (deadlineTab) {
                    Deadlines.SUBSCRIPTION -> "Nessun abbonamento.\nTi avviso 3 giorni prima di ogni rinnovo."
                    Deadlines.WARRANTY -> "Nessuna garanzia.\nFotografa lo scontrino: ti avviso un mese prima della scadenza."
                    else -> "Nessuna scadenza.\nRevisione, bollo, documenti: ti avviso 30 e 7 giorni prima."
                })
                return
            }
            list.forEach { x ->
                val left = daysLeft(x.date)
                val sub = when (x.kind) {
                    Deadlines.SUBSCRIPTION -> "${Expenses.fmt(x.amount)} ${when (x.period) { "anno" -> "all'anno"; "settimana" -> "a settimana"; else -> "al mese" }} · rinnovo ${dayFmt.format(java.util.Date(x.date))} ($left)"
                    Deadlines.WARRANTY -> "Garanzia fino al ${dayFmt.format(java.util.Date(x.date))} ($left)"
                    else -> "${dayFmt.format(java.util.Date(x.date))} · $left"
                }
                val urgent = x.date - System.currentTimeMillis() < 7 * 86_400_000L
                listRow(body, (if (urgent) "⚠️ " else "") + x.title, sub,
                    onClick = { editDeadline(x.kind, x) },
                    onLong = { x.photo?.let { showImageFullscreen(it) } ?: toast("Nessuna foto") },
                    onRemove = {
                        confirmClear("Eliminare «${x.title}»?") {
                            x.reminders.forEach { Reminders.remove(this, it) }
                            x.photo?.let { java.io.File(it).delete() }
                            Deadlines.save(this, Deadlines.load(this).filter { it.id != x.id }); render()
                        }
                    })
            }
            body.addView(TextView(this).apply {
                text = "Tocca per modificare" + if (deadlineTab != Deadlines.SUBSCRIPTION) ", tieni premuto per vedere la foto." else "."
                textSize = 12f; setTextColor(palette.textMuted); setPadding(dp(10), dp(12), 0, 0)
            })
        }
        render()
        deadlinesRefresh = { render() }
        dialog.setOnDismissListener { deadlinesRefresh = null }
    }
}
