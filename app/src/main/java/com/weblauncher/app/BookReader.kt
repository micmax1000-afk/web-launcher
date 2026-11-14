package com.weblauncher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Voice settings of the book reader. */
class ReadPrefs(c: Context) {
    private val p = c.getSharedPreferences("wl_book_voice", Context.MODE_PRIVATE)
    var mode: String get() = p.getString("mode", "system") ?: "system"; set(v) { p.edit().putString("mode", v).apply() }
    var sysVoice: String get() = p.getString("sysVoice", "") ?: ""; set(v) { p.edit().putString("sysVoice", v).apply() }
    var gVoice: String get() = p.getString("gVoice", "Sulafat") ?: "Sulafat"; set(v) { p.edit().putString("gVoice", v).apply() }
    /** Lira voice (Piper): "it_IT-paola-medium" or "it_IT-riccardo-x_low". */
    var lVoice: String get() = p.getString("lVoice", "it_IT-paola-medium") ?: "it_IT-paola-medium"; set(v) { p.edit().putString("lVoice", v).apply() }
    /** Name of the voice in use for the saved audio files (natural voices only). */
    fun naturalVoice() = if (mode == "lira") lVoice else gVoice
    fun chunkChars() = if (mode == "lira") 380 else 1100
    var speed: Float get() = p.getFloat("speed", 1f); set(v) { p.edit().putFloat("speed", v).apply() }
    var pitch: Float get() = p.getFloat("pitch", 1f); set(v) { p.edit().putFloat("pitch", v).apply() }
}

/**
 * Reads a book aloud, sentence by sentence (phone voice) or piece by piece (natural Gemini voice, saved on the phone).
 * Lives as long as the reader screen; [ReadAloudService] keeps the app alive with the screen off and shows the notification.
 */
class BookReader(private val ctx: Context, val book: Book, private val ui: Ui) {
    interface Ui {
        fun stateChanged()
        fun highlight(first: String, last: String?)
        fun showChapter(index: Int)
        fun say(msg: String)
        fun bridge(): AiBridge
    }

    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    val prefs = ReadPrefs(ctx)

    var chapter = 0; private set
    var sentences: List<String> = emptyList(); private set
    var idx = 0; private set
    var playing = false; private set
    var active = false; private set
    var loading = false; private set
    var sleepAt = 0L; private set
    var sleepAtChapterEnd = false; private set
    private var cum = LongArray(1)
    private var gen = 0
    private var fallback = false

    // ----- phone voice -----
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingStart: (() -> Unit)? = null
    private var queuedUpTo = -1

    // ----- natural voice -----
    private var mp: MediaPlayer? = null
    private var mpChunk = -1
    private var chunks: List<IntRange> = emptyList()
    private var chunkIdx = 0
    private val waiters = HashMap<String, MutableList<(File?) -> Unit>>()
    private var nextParts: List<String> = emptyList()   // pieces of the next chapter, to have its first audio ready before the chapter changes
    private var nextFor = -1

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private val audioAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val noisy = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { pause() } }
    private var noisyOn = false
    private val sleepRun = Runnable { sleepAt = 0L; pause() }

    val speed get() = prefs.speed
    fun usingGemini() = (prefs.mode == "gemini" || prefs.mode == "lira") && !fallback   // «natural»: audio made first, then played
    var preparing: String? = null; private set

    // =================== public controls ===================

    fun start(ch: Int, ratio: Float = 0f) {
        active = true; playing = true; fallback = false
        grabFocus(); if (!noisyOn) { ctx.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); noisyOn = true }
        ReadAloudService.start(ctx, book.id, title(), "Preparo la lettura…", true)
        loadChapter(ch) { s -> idx = (ratio * s.size).toInt().coerceIn(0, (s.size - 1).coerceAtLeast(0)); begin() }
    }

    fun toggle() { if (playing) pause() else resume() }

    fun pause() {
        if (!active || !playing) return
        playing = false
        saveProgress()
        tts?.stop()
        runCatching { mp?.pause() }
        gen += if (usingGemini() && mp != null) 0 else 1
        releaseFocus(); ui.stateChanged()
    }

    fun resume() {
        if (!active || playing) return
        playing = true; grabFocus()
        val m = mp
        if (usingGemini() && m != null && mpChunk == chunkIdx) { runCatching { m.start(); applySpeed(m) } ; ui.stateChanged() }
        else begin()
    }

    fun nextSentence() { if (!active) return; if (usingGemini()) playChunk(chunkIdx + 1) else speakFrom((idx + 1).coerceAtMost(sentences.lastIndex)) }
    fun prevSentence() {
        if (!active) return
        if (usingGemini()) playChunk((chunkIdx - 1).coerceAtLeast(0)) else speakFrom((idx - 1).coerceAtLeast(0))
    }
    fun nextChapter() { if (chapter < book.spine.lastIndex) jumpToChapter(chapter + 1) else ui.say("È l'ultimo capitolo") }
    fun prevChapter() { jumpToChapter((chapter - 1).coerceAtLeast(0)) }

    fun jumpToChapter(ch: Int, ratio: Float = 0f) {
        if (!active) return
        playing = true; grabFocus(); stopSound()
        loadChapter(ch) { s -> idx = (ratio * s.size).toInt().coerceIn(0, (s.size - 1).coerceAtLeast(0)); begin() }
    }

    fun setSpeed(v: Float) {
        prefs.speed = v
        mp?.let { applySpeed(it) }
        if (active && playing && !usingGemini()) { tts?.setSpeechRate(v); speakFrom(idx) }
        ui.stateChanged()
    }

    /** Voice, speed or pitch changed in the voice page: apply it from the current sentence. */
    fun settingsChanged() {
        fallback = false; nextFor = -1; nextParts = emptyList()
        if (!active) return
        applyVoice()
        stopSound()
        if (playing) begin() else ui.stateChanged()
    }

    fun setSleepMinutes(min: Int) {
        h.removeCallbacks(sleepRun); sleepAt = 0L; sleepAtChapterEnd = false
        if (min > 0) { sleepAt = System.currentTimeMillis() + min * 60_000L; h.postDelayed(sleepRun, min * 60_000L) }
        ui.stateChanged()
    }
    fun sleepAtEndOfChapter() { h.removeCallbacks(sleepRun); sleepAt = 0L; sleepAtChapterEnd = true; ui.stateChanged() }

    fun command(action: String) {
        h.post {
            when (action) {
                ReadAloudService.A_PLAY -> toggle()
                ReadAloudService.A_NEXT -> nextSentence()
                ReadAloudService.A_PREV -> prevSentence()
                ReadAloudService.A_NEXT_CH -> nextChapter()
                ReadAloudService.A_PREV_CH -> prevChapter()
                ReadAloudService.A_STOP -> stop()
            }
        }
    }

    fun stop() {
        saveProgress()
        active = false; playing = false; gen++
        h.removeCallbacks(sleepRun); sleepAt = 0L; sleepAtChapterEnd = false
        stopSound(); releaseFocus()
        if (noisyOn) { runCatching { ctx.unregisterReceiver(noisy) }; noisyOn = false }
        ReadAloudService.stop(ctx)
        ui.stateChanged()
    }

    fun release() { stop(); tts?.shutdown(); tts = null; ttsReady = false; io.shutdown() }

    // =================== state for the screen ===================

    fun title() = book.titles.getOrElse(chapter) { "Capitolo ${chapter + 1}" }
    fun fraction(): Float = if (sentences.isEmpty()) 0f else (cum[idx.coerceIn(0, sentences.lastIndex)].toFloat() / cum.last().coerceAtLeast(1)).coerceIn(0f, 1f)
    fun minutesLeft(): Int = if (sentences.isEmpty()) 0 else ReadText.minutesLeft(cum.last() - cum[idx.coerceIn(0, sentences.lastIndex)], speed)
    fun ratio(): Float = if (sentences.isEmpty()) 0f else idx.toFloat() / sentences.size

    // =================== internals ===================

    private fun saveProgress() { Ebooks.saveProgress(ctx, book.id, chapter, ratio()) }

    private fun stopSound() {
        tts?.stop()
        mp?.let { runCatching { it.stop() }; runCatching { it.release() } }
        mp = null; mpChunk = -1
        gen++
    }

    private fun loadChapter(ch: Int, then: (List<String>) -> Unit) {
        chapter = ch.coerceIn(0, book.spine.lastIndex); sentences = emptyList(); cum = LongArray(1); idx = 0; loading = true
        ui.showChapter(chapter); ui.stateChanged()
        val my = ++gen
        io.execute {
            val s = runCatching {
                val f = File(Ebooks.dir(ctx), "${book.id}/${book.root}${book.spine[chapter].substringBefore('#')}")
                ReadText.chapterSentences(f.readText())
            }.getOrDefault(emptyList())
            h.post {
                if (my != gen) return@post
                loading = false; sentences = s
                cum = LongArray(s.size + 1).also { a -> s.forEachIndexed { i, x -> a[i + 1] = a[i] + x.length + 1 } }
                then(s)
            }
        }
    }

    private fun begin() {
        if (!active || !playing) return
        if (sentences.isEmpty()) { chapterEnded(); return }
        if (usingGemini()) { chunks = ReadText.chunks(sentences, prefs.chunkChars()); chunkIdx = chunks.indexOfFirst { idx in it }.coerceAtLeast(0); playChunk(chunkIdx) }
        else speakFrom(idx)
    }

    private fun chapterEnded() {
        if (sleepAtChapterEnd) { sleepAtChapterEnd = false; Ebooks.saveProgress(ctx, book.id, (chapter + 1).coerceAtMost(book.spine.lastIndex), 0f); pause(); return }
        if (chapter < book.spine.lastIndex) {
            Ebooks.saveProgress(ctx, book.id, chapter + 1, 0f)
            loadChapter(chapter + 1) { idx = 0; begin() }
        } else { ui.say("Fine del libro 🎉"); Ebooks.saveProgress(ctx, book.id, chapter, 1f); stop() }
    }

    private fun grabFocus() {
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(audioAttrs)
            .setOnAudioFocusChangeListener { c -> if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) h.post { pause() } }.build()
        focus = r; runCatching { am.requestAudioFocus(r) }
    }
    private fun releaseFocus() { focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }; focus = null }

    // ----- phone voice -----

    private fun ensureTts(then: () -> Unit) {
        if (ttsReady) { then(); return }
        pendingStart = then
        if (tts != null) return
        tts = TextToSpeech(ctx) { st ->
            h.post {
                val t = tts
                if (st != TextToSpeech.SUCCESS || t == null) { ui.say("Lettura vocale non disponibile su questo telefono"); stop(); return@post }
                t.language = Locale.ITALIAN
                t.setAudioAttributes(audioAttrs)
                applyVoice()
                t.setOnUtteranceProgressListener(listener)
                ttsReady = true
                pendingStart?.invoke(); pendingStart = null
            }
        }
    }

    private fun applyVoice() {
        val t = tts ?: return
        t.setSpeechRate(prefs.speed); t.setPitch(prefs.pitch)
        val name = prefs.sysVoice
        if (name.isNotBlank()) runCatching { t.voices?.firstOrNull { it.name == name }?.let { t.voice = it } }
    }

    private val listener = object : UtteranceProgressListener() {
        private fun parse(id: String?): Pair<Int, Int>? = id?.split(':')?.takeIf { it.size == 2 }?.let { (it[0].toIntOrNull() ?: return null) to (it[1].toIntOrNull() ?: return null) }
        override fun onStart(id: String?) {
            val (g, i) = parse(id) ?: return
            h.post { if (g == gen && playing && i in sentences.indices) { idx = i; ui.highlight(sentences[i], null); ui.stateChanged(); updateNotification() } }
        }
        override fun onDone(id: String?) { finished(id) }
        @Deprecated("Deprecated in Java") override fun onError(id: String?) { finished(id) }
        private fun finished(id: String?) {
            val (g, i) = parse(id) ?: return
            h.post {
                if (g != gen || !playing) return@post
                val want = i + 4
                if (want < sentences.size && want > queuedUpTo) { tts?.speak(sentences[want], TextToSpeech.QUEUE_ADD, null, "$g:$want"); queuedUpTo = want }
                if (i >= sentences.lastIndex) chapterEnded()
            }
        }
    }

    private fun speakFrom(i: Int) {
        if (sentences.isEmpty()) return
        ensureTts {
            val t = tts ?: return@ensureTts
            t.stop(); gen++
            val g = gen
            idx = i.coerceIn(0, sentences.lastIndex)
            val last = minOf(idx + 3, sentences.lastIndex)
            for (k in idx..last) t.speak(sentences[k], if (k == idx) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$g:$k")
            queuedUpTo = last
            ui.highlight(sentences[idx], null); ui.stateChanged()
        }
    }

    // ----- natural voice (Gemini) -----

    private fun audioDir() = File(Ebooks.dir(ctx), "audio/${book.id}").apply { mkdirs() }
    private fun chunkText(k: Int) = chunks[k].let { sentences.subList(it.first, it.last + 1).joinToString(" ") }
    private fun fileFor(ch: Int, t: String) = File(audioDir(), "c${ch}_${Integer.toHexString(t.hashCode())}_${t.length}_${prefs.naturalVoice()}.wav")
    private fun chunkFile(k: Int) = fileFor(chapter, chunkText(k))

    private fun playChunk(k: Int) {
        if (k >= chunks.size) { chapterEnded(); return }
        stopSound()
        val g = gen
        chunkIdx = k; idx = chunks[k].first
        ui.highlight(sentences[chunks[k].first], sentences[chunks[k].last]); ui.stateChanged(); updateNotification()
        fetchChunk(k) { file ->
            if (g != gen || !playing) return@fetchChunk
            if (file == null) { fellBack(k); return@fetchChunk }
            startFile(file, k, g)
            prefetchAhead(k)
        }
    }

    private fun fellBack(k: Int) {
        fallback = true
        ui.say(if (prefs.mode == "lira") "La voce Lira è troppo lenta o non risponde: uso la voce del telefono. Prova «Prepara il capitolo adesso» prima di ascoltare."
            else "Voce naturale non disponibile (internet o limite di uso): uso la voce del telefono")
        speakFrom(chunks.getOrNull(k)?.first ?: idx)
    }

    private fun fetchChunk(k: Int, cb: (File?) -> Unit) = fetchText(chunkText(k), chunkFile(k), cb)

    /** Keeps the next pieces being made while one plays, also over the end of the chapter (so the chapter change does not stop). */
    private fun prefetchAhead(k: Int) {
        val depth = if (prefs.mode == "lira") 4 else 2
        for (n in 1..depth) {
            val t = k + n
            if (t < chunks.size) { fetchChunk(t) { }; continue }
            if (chapter >= book.spine.lastIndex) return
            val nc = chapter + 1
            if (nextFor != nc) { loadNextParts(nc) { if (active && playing && chapter + 1 == nc) prefetchAhead(chunkIdx) }; return }
            nextParts.getOrNull(t - chunks.size)?.let { part -> fetchText(part, fileFor(nc, part)) { } }
        }
    }

    private fun loadNextParts(nc: Int, then: () -> Unit) {
        val size = prefs.chunkChars()
        io.execute {
            val s = runCatching { ReadText.chapterSentences(File(Ebooks.dir(ctx), "${book.id}/${book.root}${book.spine[nc].substringBefore('#')}").readText()) }.getOrDefault(emptyList())
            val parts = ReadText.chunks(s, size).map { r -> s.subList(r.first, r.last + 1).joinToString(" ") }
            h.post { nextParts = parts; nextFor = nc; then() }
        }
    }

    /** Makes the audio of [text] (or finds it already saved) and hands the file to [cb]; null if it could not be made. */
    private fun fetchText(text: String, file: File, cb: (File?) -> Unit) {
        if (file.exists() && file.length() > 100) { cb(file); return }
        val key = file.name
        val list = waiters[key]
        if (list != null) { list += cb; return }
        waiters[key] = mutableListOf(cb)
        loading = true; ui.stateChanged()
        // if the voice never answers, do not stay silent for ever
        h.postDelayed({ waiters.remove(key)?.let { list -> loading = false; list.forEach { it(null) }; ui.stateChanged() } }, if (prefs.mode == "lira") 150_000L else 60_000L)
        val done = { b64: String, mime: String ->
            io.execute {
                val ok = runCatching {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    file.writeBytes(if (mime.contains("wav", true)) bytes else ReadText.pcmToWav(bytes, ReadText.rateOf(mime)))
                }.isSuccess
                h.post { loading = false; waiters.remove(key)?.forEach { it(if (ok) file else null) }; ui.stateChanged() }
            }
            Unit
        }
        val failed = { _: String, _: String -> h.post { loading = false; waiters.remove(key)?.forEach { it(null) }; ui.stateChanged() }; Unit }
        if (prefs.mode == "lira") ui.bridge().piper(text, prefs.lVoice, done, failed) { msg -> h.post { ui.say(msg) } }
        else ui.bridge().tts(text, prefs.gVoice, done, failed)
    }

    /** Makes the audio of the whole chapter [ch] ahead of time, one piece after the other. */
    fun prepareChapter(ch: Int) {
        if (preparing != null) { ui.say("Sto già preparando un capitolo"); return }
        if (prefs.mode == "system") { ui.say("Scegli prima una voce naturale"); return }
        preparing = "Preparo il capitolo…"; ui.stateChanged()
        io.execute {
            val s = runCatching { ReadText.chapterSentences(File(Ebooks.dir(ctx), "${book.id}/${book.root}${book.spine[ch].substringBefore('#')}").readText()) }.getOrDefault(emptyList())
            val parts = ReadText.chunks(s, prefs.chunkChars()).map { r -> s.subList(r.first, r.last + 1).joinToString(" ") }
            h.post {
                if (parts.isEmpty()) { preparing = null; ui.say("Questo capitolo non ha testo"); ui.stateChanged(); return@post }
                fun step(i: Int) {
                    if (i >= parts.size) { preparing = null; ui.say("Capitolo pronto: ora lo ascolti senza attese"); ui.stateChanged(); return }
                    preparing = "Preparo il capitolo… ${i + 1}/${parts.size}"; ui.stateChanged()
                    fetchText(parts[i], fileFor(ch, parts[i])) { f ->
                        if (f == null) { preparing = null; ui.say("Non riesco a preparare la voce (internet o limite di uso)"); ui.stateChanged() } else step(i + 1)
                    }
                }
                step(0)
            }
        }
    }

    private fun startFile(file: File, k: Int, g: Int) {
        val m = MediaPlayer()
        runCatching {
            m.setAudioAttributes(audioAttrs)
            m.setDataSource(file.path)
            m.setOnPreparedListener { p -> p.start(); applySpeed(p) }
            m.setOnCompletionListener { if (g == gen && playing) playChunk(k + 1) }
            m.setOnErrorListener { _, _, _ -> h.post { if (g == gen) { file.delete(); fellBack(k) } }; true }
            m.prepareAsync()
        }.onFailure { file.delete(); fellBack(k); return }
        mp = m; mpChunk = k
    }

    private fun applySpeed(m: MediaPlayer) {
        runCatching { m.playbackParams = (m.playbackParams ?: PlaybackParams()).setSpeed(prefs.speed.coerceIn(0.5f, 2f)) }
    }

    private fun updateNotification() {
        ReadAloudService.update(ctx, book.id, title(), if (loading) "Preparo la voce…" else "${(fraction() * 100).toInt()}% · ${minutesLeft()} min", playing)
    }

    companion object {
        /** Audio saved by the natural voice, in bytes (all books). */
        fun savedAudioBytes(c: Context): Long = File(Ebooks.dir(c), "audio").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        fun clearSavedAudio(c: Context) { File(Ebooks.dir(c), "audio").deleteRecursively() }
    }
}
