package com.weblauncher.app

enum class SearchEngine(val label: String, val queryUrl: String) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BING("Bing", "https://www.bing.com/search?q="),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query="),
    YAHOO("Yahoo", "https://search.yahoo.com/search?p=");

    fun buildUrl(query: String): String = queryUrl + android.net.Uri.encode(query)

    companion object {
        fun fromName(name: String?): SearchEngine =
            entries.find { it.name == name } ?: GOOGLE
    }
}
