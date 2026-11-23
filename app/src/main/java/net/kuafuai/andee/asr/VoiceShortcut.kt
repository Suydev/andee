package net.kuafuai.andee.asr

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import net.kuafuai.andee.config.Notebook

/**
 * Spoken one-liners handled on the device, without waking the model.
 *
 * "Open WhatsApp" is a launch, not a conversation. Sending it through the
 * brain costs a network round trip and a generation just to produce a tool
 * call this phone can make itself. The dictionary comes from the app
 * playbooks, so an app the user teaches is instantly speakable here.
 */
object VoiceShortcut {
    private const val TAG = "VoiceShortcut"

    private val VERBS = listOf("open", "launch", "start")

    /** True when the phrase was consumed and the brain must not see it. */
    fun tryOpen(context: Context, raw: String): Boolean {
        val text = raw.trim().lowercase()
            .replace(Regex("[!?.,。！？，]+"), "")
            .replace(Regex("\\s+"), " ")
        val verb = VERBS.firstOrNull { text == it || text.startsWith("$it ") } ?: return false
        var target = text.removePrefix(verb).trim()
        if (target.isEmpty()) return false
        if (target == "the" || target == "app") return false
        if (target.startsWith("the ")) target = target.removePrefix("the ").trim()
        if (target.startsWith("app ")) target = target.removePrefix("app ").trim()
        if (target.isEmpty() || target.contains(' ')) return false

        val playbooks = runCatching { Notebook.playbooks(context) }.getOrElse { emptyList() }
        val match = playbooks.firstOrNull { p ->
            target == p.pkg.lowercase() ||
                target == p.label.trim().lowercase() ||
                target == p.label.trim().lowercase().replace(" ", "")
        } ?: return false

        val intent = context.packageManager.getLaunchIntentForPackage(match.pkg) ?: return false
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Opening ${match.label}", Toast.LENGTH_SHORT).show()
            }
        }.onFailure { Log.w(TAG, "launch failed for ${match.pkg}", it) }
        return true
    }
}
