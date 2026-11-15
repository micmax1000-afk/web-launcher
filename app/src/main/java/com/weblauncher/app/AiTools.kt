package com.weblauncher.app

/** Pure helpers for the AI tools (unit-tested). */
object AiTools {
    val KINDS = listOf("Email", "WhatsApp", "Disdetta", "Reclamo", "Auguri")
    val TONES = listOf("Cortese", "Deciso", "Breve")

    /** The request for «Scrivi per me». */
    fun writePrompt(kind: String, tone: String, what: String): String {
        val form = when (kind) {
            "Email" -> "un'email completa di oggetto (prima riga «Oggetto: …»), saluto iniziale e firma generica «[Il tuo nome]»"
            "WhatsApp" -> "un messaggio WhatsApp naturale, senza oggetto e senza firma, al massimo qualche riga"
            "Disdetta" -> "una lettera di disdetta formale (prima riga «Oggetto: …»), con spazi tra parentesi quadre per i dati che non conosco (numero di contratto, codice cliente, data) e la richiesta di conferma scritta"
            "Reclamo" -> "un reclamo formale (prima riga «Oggetto: …»): fatti, cosa chiedo, entro quando; spazi tra parentesi quadre per i dati che non conosco"
            "Auguri" -> "un messaggio di auguri caloroso e personale, senza frasi fatte"
            else -> "un testo"
        }
        val t = when (tone) { "Deciso" -> "deciso e fermo ma educato"; "Breve" -> "breve, essenziale"; else -> "cortese e cordiale" }
        return "Scrivi in italiano $form. Tono: $t. Ecco cosa devo dire, con parole mie: «${what.trim()}». " +
            "Rispondi solo con il testo pronto da inviare, senza commenti prima o dopo."
    }

    /** «Oggetto: Disdetta» on the first line → subject and body, for the email app. */
    fun splitSubject(text: String): Pair<String?, String> {
        val lines = text.trim().lines()
        val first = lines.firstOrNull()?.trim().orEmpty()
        val m = Regex("^\\**\\s*oggetto\\s*:\\s*\\**\\s*(.+?)\\**$", RegexOption.IGNORE_CASE).find(first) ?: return null to text.trim()
        return m.groupValues[1].trim() to lines.drop(1).joinToString("\n").trim()
    }

    /** The first JSON array of strings in an AI answer (it sometimes adds ```json fences or words around it). */
    fun stringArray(text: String): List<String>? = runCatching {
        val start = text.indexOf('['); val end = text.lastIndexOf(']')
        val a = org.json.JSONArray(text.substring(start, end + 1))
        (0 until a.length()).map { a.optString(it) }
    }.getOrNull()

    /** Groups texts (by index) into batches of at most [maxChars] characters and [maxItems] items. */
    fun batches(texts: List<String>, maxChars: Int = 3500, maxItems: Int = 60): List<IntRange> {
        val out = mutableListOf<IntRange>(); var first = 0; var len = 0
        texts.forEachIndexed { i, s ->
            if (i > first && (len + s.length > maxChars || i - first >= maxItems)) { out += first until i; first = i; len = 0 }
            len += s.length
        }
        if (texts.isNotEmpty()) out += first..texts.lastIndex
        return out
    }

    /** The page says it is written in another language (empty or Italian → no offer). */
    fun isForeign(lang: String?): Boolean {
        val l = lang?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_').orEmpty()
        return l.length in 2..3 && l != "it" && l != "und" && l != "mul" && l != "zxx"
    }

    /** JS that collects the visible texts of the page (kept in window.__wlTr) and returns them as a JSON array. */
    const val COLLECT_JS = """(function(){
  if(window.__wlTr&&window.__wlTr.nodes.length){window.__wlTr.nodes.forEach(function(n,i){n.nodeValue=window.__wlTr.orig[i];});}
  var nodes=[],orig=[],w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{acceptNode:function(n){
    var p=n.parentElement;if(!p)return 2;var t=p.tagName;if(/^(SCRIPT|STYLE|NOSCRIPT|CODE|PRE|TEXTAREA|SVG|MATH)$/.test(t))return 2;
    var s=n.nodeValue.trim();if(s.length<2||!/[A-Za-zÀ-ÿĀ-žА-я一-鿿]/.test(s))return 2;
    if(p.offsetParent===null&&getComputedStyle(p).position!=='fixed')return 2;return 1;}});
  var n,total=0;while((n=w.nextNode())&&nodes.length<700&&total<60000){nodes.push(n);orig.push(n.nodeValue);total+=n.nodeValue.length;}
  window.__wlTr={nodes:nodes,orig:orig};
  return JSON.stringify(orig.map(function(s){return s.replace(/\s+/g,' ').trim();}));
})()"""

    /** JS that puts the translations [json] (a JSON array) into the page starting at text [from]. */
    fun applyJs(from: Int, json: String) = """(function(){var t=window.__wlTr;if(!t)return 0;var a=$json;
  for(var i=0;i<a.length;i++){var n=t.nodes[$from+i];if(!n||typeof a[i]!=='string'||!a[i])continue;
    var o=t.orig[$from+i],lead=o.match(/^\s*/)[0],trail=o.match(/\s*$/)[0];n.nodeValue=lead+a[i]+trail;}
  return a.length;})()"""

    const val RESTORE_JS = """(function(){var t=window.__wlTr;if(!t)return 0;t.nodes.forEach(function(n,i){n.nodeValue=t.orig[i];});return t.nodes.length;})()"""
}
