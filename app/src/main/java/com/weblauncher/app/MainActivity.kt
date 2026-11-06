package com.weblauncher.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var repo: AppRepository
    private lateinit var adapter: LauncherAdapter
    private lateinit var searchInput: EditText
    private lateinit var webSearchHint: TextView
    private lateinit var recycler: RecyclerView

    private var apps: MutableList<AppItem> = mutableListOf()
    private var categories: MutableList<String> = mutableListOf()

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotEmpty()) handleVoiceText(spoken)
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecognition()
        else toast(getString(R.string.mic_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = AppRepository(this)
        apps = repo.loadApps()
        categories = repo.loadCategories()

        searchInput = findViewById(R.id.searchInput)
        webSearchHint = findViewById(R.id.webSearchHint)
        recycler = findViewById(R.id.recycler)

        adapter = LauncherAdapter(
            onClick = { openApp(it) },
            onLongClick = { item, _ -> showAppMenu(item) }
        )

        val glm = GridLayoutManager(this, 3)
        glm.spanSizeLookup = adapter.spanSizeLookup()
        recycler.layoutManager = glm
        recycler.adapter = adapter

        refreshList()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty()
                updateWebHint(q)
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearchFromBox()
                true
            } else false
        }

        webSearchHint.setOnClickListener {
            val q = searchInput.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) openWebSearch(q)
        }

        findViewById<View>(R.id.btnMic).setOnClickListener { requestMicAndListen() }
        findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener { showAddEditDialog(null) }
        findViewById<View>(R.id.navHome).setOnClickListener {
            searchInput.setText("")
            refreshList()
        }
        findViewById<View>(R.id.navVoice).setOnClickListener { requestMicAndListen() }
        findViewById<View>(R.id.navSettings).setOnClickListener { showSettings() }
    }

    private fun refreshList() {
        val q = searchInput.text?.toString().orEmpty()
        adapter.submit(apps, categories, q)
    }

    private fun updateWebHint(q: String) {
        val t = q.trim()
        if (t.isEmpty()) {
            webSearchHint.visibility = View.GONE
        } else {
            webSearchHint.visibility = View.VISIBLE
            webSearchHint.text = "\ud83d\udd0d  Cerca sul web: $t"
        }
    }

    private fun runSearchFromBox() {
        val q = searchInput.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) return
        if (q.contains('.') && !q.contains(' ')) {
            openUrl(normalizeUrl(q))
        } else {
            openWebSearch(q)
        }
        searchInput.setText("")
        refreshList()
    }

    private fun handleVoiceText(text: String) {
        toast("Hai detto: $text")
        val match = apps.firstOrNull {
            it.name.contains(text, ignoreCase = true) ||
                text.contains(it.name, ignoreCase = true)
        }
        when {
            match != null -> openApp(match)
            text.contains('.') && !text.contains(' ') -> openUrl(normalizeUrl(text))
            else -> openWebSearch(text)
        }
    }

    private fun requestMicAndListen() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED -> startVoiceRecognition()
            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecognition() {
        if (!isSpeechAvailable()) {
            toast(getString(R.string.no_speech))
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ITALIAN.toString())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.no_speech))
        }
    }

    private fun isSpeechAvailable(): Boolean {
        return packageManager
            .queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
            .isNotEmpty()
    }

    private fun openApp(item: AppItem) = openUrl(normalizeUrl(item.url))

    private fun openWebSearch(query: String) {
        openUrl("https://www.google.com/search?q=" + Uri.encode(query))
    }

    private fun normalizeUrl(raw: String): String {
        val u = raw.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    private fun openUrl(url: String) {
        try {
            val custom = CustomTabsIntent.Builder().setShowTitle(true).build()
            custom.launchUrl(this, Uri.parse(url))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                toast("Impossibile aprire: $url")
            }
        }
    }

    private fun showAppMenu(item: AppItem) {
        val options = arrayOf(
            if (item.pinned) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
            "Modifica / Sposta",
            "Elimina"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        item.pinned = !item.pinned
                        repo.saveApps(apps)
                        refreshList()
                        toast(if (item.pinned) "Aggiunta ai preferiti" else "Rimossa dai preferiti")
                    }
                    1 -> showAddEditDialog(item)
                    2 -> {
                        MaterialAlertDialogBuilder(this)
                            .setMessage("Eliminare \"${item.name}\"?")
                            .setPositiveButton("Elimina") { _, _ ->
                                apps.removeAll { it.id == item.id }
                                repo.saveApps(apps)
                                refreshList()
                                toast("Eliminata")
                            }
                            .setNegativeButton("Annulla", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showAddEditDialog(existing: AppItem?) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }

        fun field(hint: String, value: String): TextInputEditText {
            val til = TextInputLayout(this).apply {
                this.hint = hint
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            }
            val et = TextInputEditText(til.context).apply {
                setText(value)
                setSingleLine()
            }
            til.addView(et)
            container.addView(til)
            return et
        }

        val nameEt = field("Nome", existing?.name.orEmpty())
        val urlEt = field("URL", existing?.url.orEmpty())
        val catEt = field("Categoria", existing?.category ?: categories.firstOrNull().orEmpty())

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Aggiungi Web App" else "Modifica / Sposta")
            .setView(container)
            .setPositiveButton("Salva") { _, _ ->
                val name = nameEt.text?.toString()?.trim().orEmpty()
                val url = urlEt.text?.toString()?.trim().orEmpty()
                val cat = catEt.text?.toString()?.trim().orEmpty().ifBlank { "Utility" }
                if (name.isBlank() || url.isBlank()) {
                    toast("Nome e URL obbligatori")
                    return@setPositiveButton
                }
                if (cat !in categories) {
                    categories.add(cat)
                    repo.saveCategories(categories)
                }
                if (existing == null) {
                    apps.add(AppItem(UUID.randomUUID().toString(), name, normalizeUrl(url), cat))
                    toast("App aggiunta")
                } else {
                    existing.name = name
                    existing.url = normalizeUrl(url)
                    existing.category = cat
                    toast("App aggiornata")
                }
                repo.saveApps(apps)
                refreshList()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showSettings() {
        val items = arrayOf(
            "Esporta backup (JSON)",
            "Reset app predefinite",
            "Info voce / microfono"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Impostazioni")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val json = com.google.gson.Gson().toJson(
                            mapOf("apps" to apps, "categories" to categories)
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_TEXT, json)
                            putExtra(Intent.EXTRA_SUBJECT, "web-launcher-backup.json")
                        }
                        startActivity(Intent.createChooser(send, "Esporta backup"))
                    }
                    1 -> {
                        MaterialAlertDialogBuilder(this)
                            .setMessage("Ripristinare le app predefinite?")
                            .setPositiveButton("Reset") { _, _ ->
                                apps = repo.resetDefaults()
                                categories = repo.loadCategories()
                                refreshList()
                                toast("App ripristinate")
                            }
                            .setNegativeButton("Annulla", null)
                            .show()
                    }
                    2 -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Microfono")
                            .setMessage(
                                "Questa app usa il riconoscimento vocale nativo di Android.\n\n" +
                                    "Se non funziona:\n" +
                                    "1. Impostazioni \u2192 App \u2192 Web Launcher \u2192 Autorizzazioni \u2192 Microfono \u2192 Consentire\n" +
                                    "2. Assicurati di avere Google app / riconoscimento vocale installato\n" +
                                    "3. Serve connessione internet"
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
