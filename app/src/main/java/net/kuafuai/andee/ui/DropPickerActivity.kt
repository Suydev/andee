package net.kuafuai.andee.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import net.kuafuai.andee.config.DropInbox
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Non-launcher Activity: a Service has no onActivityResult to receive a document. */
class DropPickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, REQ_OPEN,
            )
        }.onFailure {
            Toast.makeText(applicationContext, "没有可用的文件应用", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Deprecated("Activity has no other way to get a result without androidx.activity")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != REQ_OPEN) return
        val uri = if (result == RESULT_OK) data?.data else null
        if (uri == null) {
            finish()
            return
        }
        val done = onDone
        val app = applicationContext
        COPY.execute {
            val copied = runCatching { copy(app, uri) }.isSuccess
            Handler(Looper.getMainLooper()).post {
                if (!copied) Toast.makeText(app, "添加文件失败", Toast.LENGTH_SHORT).show()
                done?.invoke()
                finish()
            }
        }
    }

    private fun copy(context: Context, uri: Uri) {
        val box = DropInbox.from(context)
        box.ensureDir()
        var name = DropInbox.safeName(uri.lastPathSegment)
        var dest = File(box.path(), name)
        var n = 1
        val ext = name.substringAfterLast('.', "")
        val stem = if (ext.isEmpty()) name else name.removeSuffix(".$ext")
        while (dest.exists()) {
            name = if (ext.isEmpty()) "$stem-$n" else "$stem-$n.$ext"
            dest = File(box.path(), name)
            n++
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("no stream for the picked document")
    }

    companion object {
        private const val REQ_OPEN = 1

        /** A 1 GB pick must never hold the main thread; one worker, daemon so it cannot keep the process alive. */
        private val COPY = Executors.newSingleThreadExecutor { r ->
            Thread(r, "drop-picker-copy").apply { isDaemon = true }
        }

        /** Set by DropInboxUi.show, cleared by hide; the service stays alive across the picker. */
        var onDone: (() -> Unit)? = null
    }
}
