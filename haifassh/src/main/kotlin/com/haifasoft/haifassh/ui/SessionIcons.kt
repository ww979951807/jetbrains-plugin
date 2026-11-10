package com.haifasoft.haifassh.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * The built-in set of monochrome icons a user can pick for an SSH session.
 * The shapes are flat single-color silhouettes so they tint cleanly via
 * [com.intellij.util.IconUtil.colorize] with the session's chosen color.
 */
object SessionIcons {

    data class Choice(val key: String, val label: String, val icon: Icon)

    const val DEFAULT_KEY = "server"

    private fun load(name: String): Icon =
        IconLoader.getIcon("/icons/sessions/$name.svg", SessionIcons::class.java)

    val choices: List<Choice> = listOf(
        Choice("server", "Server", load("server")),
        Choice("database", "Database", load("database")),
        Choice("cloud", "Cloud", load("cloud")),
        Choice("terminal", "Terminal", load("terminal")),
        Choice("globe", "Globe / Web", load("globe")),
        Choice("container", "Container", load("container")),
        Choice("laptop", "Laptop", load("laptop")),
        Choice("lock", "Secure", load("lock")),
    )

    private val byKey: Map<String, Choice> = choices.associateBy { it.key }

    fun choice(key: String): Choice =
        byKey[key.ifBlank { DEFAULT_KEY }] ?: byKey.getValue(DEFAULT_KEY)

    fun icon(key: String): Icon = choice(key).icon
}
