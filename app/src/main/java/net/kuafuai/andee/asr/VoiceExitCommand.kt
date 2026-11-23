package net.kuafuai.andee.asr

import java.util.Locale

/**
 * A small, device-side escape hatch. Match the whole utterance: asking how to
 * exit, cancelling a subscription, or quoting a command must remain a turn for
 * the brain rather than unexpectedly shutting the assistant down.
 */
internal object VoiceExitCommand {
    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private val commands = setOf(
        "exit Andee", "close Andee", "quit Andee",
        "Andee exit", "Andee close", "Andee quit",
        "take a break", "Andee take a break", "Hey Andee take a break",
        "Exit Andee", "Shut down Andee", "Andee, exit", "Andee, shut down",
        "Exit Andee", "Shut down Andee", "Andee, exit", "Andee, shut down",
        "Take a break", "Let's take a break first", "You take a break first",
        "Andee, take a break", "Andee, take a break", "Andee, you take a break first",
    ).map(::normalize).toSet()

    fun matches(text: String, userInitiated: Boolean = true): Boolean =
        userInitiated && normalize(text) in commands
}
