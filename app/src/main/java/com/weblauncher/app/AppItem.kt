package com.weblauncher.app

data class AppItem(
    val id: String,
    var name: String,
    var url: String,
    var category: String,
    var pinned: Boolean = false
)
