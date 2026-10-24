package com.so.zapretksu.ui

enum class Route(val title: String, val isTopLevel: Boolean) {
    Home("Home", true),
    Settings("Settings", true),
    Tools("Tools", true),
    Logs("Logs", true),
    Apps("Apps", false),
    Updates("Updates", false),
    Blockcheck("Blockcheck", false),
    Dns("DNS", false),
    Lists("Lists", false),
    Tg("TG WS Proxy", false),
    About("About", false);

    val parent: Route
        get() = when (this) {
            About -> Settings
            else -> Tools
        }

    companion object {
        val TOP_LEVEL = listOf(Home, Settings, Tools, Logs)
    }
}
