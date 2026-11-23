package net.kuafuai.andee.text

/**
 * Flatten the brain's markdown into something worth speaking aloud.
 *
 * The brain answers in markdown (`**bold**`, `## heading`, `- list`, links), which
 * TTS either reads out as punctuation or turns into strange pauses, and which
 * clutters the one-line subtitle band.
 *
 * Deliberately leaves `_` alone. Underscore-italic is vanishingly rare in the
 * Chinese answers this thing actually produces, while snake_case is everywhere
 * in the progress subtitles — every body tool is named `tap_screen`,
 * `get_screen_element`, `submit_input`. Treating `_` as emphasis would silently read
 * those as "tapscreen".
 */

// Whole-line patterns also consume the trailing newline (the final `\n?`); otherwise a removed line leaves an empty line,
// adding blank space to the subtitle and one more pause for TTS.
// Rules must be handled before emphasis: a whole line of `***` is a rule but can also be swallowed by the *emphasis* regex.
private val RULE = Regex("""^[ \t]*([-*])([ \t]*\1){2,}[ \t]*$\n?""", RegexOption.MULTILINE)
private val CODE_FENCE = Regex("""^[ \t]*`{3,}.*$\n?""", RegexOption.MULTILINE)
// Images before links: `![alt](url)` is a superset of `[text](url)`; the other order leaves a lone stray `!`.
private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val HEADING = Regex("""^[ \t]{0,3}#{1,6}[ \t]+""", RegexOption.MULTILINE)
private val QUOTE = Regex("""^[ \t]{0,3}>+[ \t]?""", RegexOption.MULTILINE)
private val BULLET = Regex("""^[ \t]*[-*+][ \t]+""", RegexOption.MULTILINE)
private val ORDERED = Regex("""^[ \t]*\d+[.)][ \t]+""", RegexOption.MULTILINE)
// Drop table separator rows (e.g. `|---|:--:|`) entirely; swap the pipes in body rows for spaces so they are not read as a run of pauses.
private val TABLE_RULE = Regex("""^[ \t]*\|?[ \t:|-]*\|[ \t:|-]*$\n?""", RegexOption.MULTILINE)
private val EMPHASIS = Regex("""\*{1,3}([^*\n]+)\*{1,3}""")
private val INLINE_CODE = Regex("""`+([^`\n]+)`+""")
private val BLANK_RUN = Regex("""\n{3,}""")
// Removing markup leaves clusters of spaces (especially where table pipes were); collapse them to one, then trim both ends of each line.
private val SPACE_RUN = Regex("""[ \t]{2,}""")
private val LINE_EDGE = Regex("""^[ \t]+|[ \t]+$""", RegexOption.MULTILINE)

/**
 * Returns [text] with markdown markup removed. Falls back to the original when
 * stripping would leave nothing — an answer made entirely of markup is more
 * likely a bug in this function than an empty answer from the brain.
 */
fun stripMarkdown(text: String): String {
    if (text.isEmpty()) return text
    var s = text
    s = CODE_FENCE.replace(s, "")
    s = RULE.replace(s, "")
    s = TABLE_RULE.replace(s, "")
    s = IMAGE.replace(s, "")
    s = LINK.replace(s, "$1")
    s = HEADING.replace(s, "")
    s = QUOTE.replace(s, "")
    s = BULLET.replace(s, "")
    s = ORDERED.replace(s, "")
    s = EMPHASIS.replace(s, "$1")
    s = INLINE_CODE.replace(s, "$1")
    s = s.replace('|', ' ')
    s = SPACE_RUN.replace(s, " ")
    s = LINE_EDGE.replace(s, "")
    s = BLANK_RUN.replace(s, "\n\n").trim()
    return s.ifEmpty { text }
}
