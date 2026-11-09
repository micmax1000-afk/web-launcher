package com.weblauncher.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * "Profilo 2": a browser with its own cookies, logins and storage (WebView profiles),
 * e.g. for a second Gmail account.
 */
class IsolatedBrowserActivity : AppCompatActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        AppRepository.applySavedTheme(this)
        setTheme(AppRepository.accentStyle(this))
        super.onCreate(savedInstanceState)
        if (!supported()) {
            Toast.makeText(this, "Aggiorna «Android System WebView» dal Play Store per usare il profilo 2", Toast.LENGTH_LONG).show()
            finish(); return
        }
        web = WebView(this)
        AppRepository.restoreUiMode(this)
        ProfileStore.getInstance().getOrCreateProfile(PROFILE)
        WebViewCompat.setProfile(web, PROFILE)
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val purple = 0xFF8E5CF7.toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.bg) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setBackgroundColor(palette.elevated)
        }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_web_close); setColorFilter(palette.text); background = null
            setPadding(dp(10), dp(10), dp(10), dp(10)); layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            contentDescription = "Chiudi"; setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "Profilo 2"; textSize = 12f; setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * d; setColor(purple) }
        })
        val address = EditText(this).apply {
            setSingleLine(); textSize = 15f; hint = "Cerca o digita l'URL"
            setTextColor(palette.text); setHintTextColor(palette.textMuted)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 20 * d; setColor(palette.iconTile) }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(8), 0, dp(4), 0) }
        }
        bar.addView(address)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; layoutParams = LinearLayout.LayoutParams(-1, dp(3)); visibility = View.INVISIBLE
            progressTintList = android.content.res.ColorStateList.valueOf(purple)
        }
        root.addView(bar); root.addView(progress)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        window.statusBarColor = palette.elevated

        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; loadWithOverviewMode = true; useWideViewPort = true
            builtInZoomControls = true; displayZoomControls = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!address.isFocused) address.setText(url.orEmpty())
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                progress.progress = p; progress.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE
            }
        }
        address.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) {
                val t = v.text.toString().trim()
                if (t.isNotEmpty()) {
                    web.loadUrl(if (t.contains('.') && !t.contains(' ')) (if (t.startsWith("http")) t else "https://$t")
                        else "https://www.google.com/search?q=" + android.net.Uri.encode(t))
                    v.clearFocus()
                }
                true
            } else false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        val start = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("http") } ?: "https://www.google.com"
        web.loadUrl(start)
        address.setText(start)
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val PROFILE = "secondo"
        const val EXTRA_URL = "url"
        fun supported() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

        /** Deletes cookies and site data of profile 2 only. */
        fun clear() {
            if (!supported()) return
            val p = ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            p.cookieManager.removeAllCookies(null)
            p.webStorage.deleteAllData()
        }
    }
}
