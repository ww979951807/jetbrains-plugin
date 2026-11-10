package com.haifasoft.haifassh.model

/**
 * A saved command the user can type into a live shell with one click. The
 * [description] is what the user searches by; the [command] is the text typed
 * into the terminal (never auto-executed — no trailing newline is sent).
 *
 * Mutable with defaults so the IntelliJ XML serializer can persist it.
 */
class CommandSnippet {
    var id: String = ""
    var command: String = ""
    var description: String = ""

    fun copyOf(): CommandSnippet {
        val c = CommandSnippet()
        c.id = id
        c.command = command
        c.description = description
        return c
    }
}
