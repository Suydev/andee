package net.kuafuai.andee

import android.app.Application
import android.os.Build
import android.util.Log
import net.kuafuai.andee.shell.ShizukuDevice
import rikka.shizuku.ShizukuProvider
import rikka.sui.Sui

/**
 * Process name without a Context, because the companion initializer runs before
 * attachBaseContext. Application.getProcessName() covers API 28+; on the minSdk
 * floor the kernel's own cmdline is the only context-free source.
 */
private fun currentProcessName(): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
    return try {
        val bytes = java.io.File("/proc/self/cmdline").readBytes()
        val end = bytes.indexOf(0)
        String(bytes, 0, if (end < 0) bytes.size else end, Charsets.UTF_8)
    } catch (t: Throwable) {
        ""
    }
}

/**
 * Runs before any service, receiver or activity of this app. It exists to
 * point the shared module's logging at logcat before the first line is
 * written. It also performs the one-time Shizuku process setup; the privileged
 * manager itself is started only in the main process.
 */
class AndeeApp : Application() {

    companion object {
        private const val TAG = "AndeeApp"
        private val IS_MAIN_PROCESS = currentProcessName() == BuildConfig.APPLICATION_ID
        private val IS_SUI: Boolean

        init {
            // The official demo does this in every process: Sui is the cheap
            // binder path, and ShizukuProvider needs the multi-process flag in
            // each process that loads it (including :shell).
            IS_SUI = runCatching { Sui.init(BuildConfig.APPLICATION_ID) }.getOrDefault(false)
            if (!IS_SUI) {
                ShizukuProvider.enableMultiProcessSupport(IS_MAIN_PROCESS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        net.kuafuai.andee.util.Log.sink = object : net.kuafuai.andee.util.Log.Sink {
            override fun log(level: Char, tag: String, msg: String, t: Throwable?) {
                when (level) {
                    'V' -> Log.v(tag, msg)
                    'D' -> Log.d(tag, msg)
                    'I' -> Log.i(tag, msg)
                    'W' -> if (t == null) Log.w(tag, msg) else Log.w(tag, msg, t)
                    else -> if (t == null) Log.e(tag, msg) else Log.e(tag, msg, t)
                }
            }
        }
        // The manifest provider is instantiated in :shell too, but nothing else
        // in this process may touch the Shizuku API or the shell manager.
        if (!IS_MAIN_PROCESS) return
        if (!IS_SUI) {
            runCatching { ShizukuProvider.requestBinderForNonProviderProcess(this) }
                .onFailure { Log.w(TAG, "binder request failed: ${it.message}", it) }
        }
        // Sticky binder/dead/permission listeners are installed here, once,
        // on the main process's main thread.
        ShizukuDevice.start(this)
    }
}
