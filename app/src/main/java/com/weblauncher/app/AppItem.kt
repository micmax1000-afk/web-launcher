package com.weblauncher.app

data class AppItem(
    val id: String,
    var name: String,
    var url: String,
    var category: String,
    var pinned: Boolean = false,
    var sortOrder: Int = 0,
    var iconPath: String? = null,
    var openCount: Int = 0,
    var locked: Boolean = false
)
