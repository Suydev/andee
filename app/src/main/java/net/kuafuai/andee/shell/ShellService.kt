package net.kuafuai.andee.shell

import net.kuafuai.andee.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "ShellService"

/** Wire version reported by [ShellService.version]; independent of the app version code. */
private const val SERVICE_VERSION = 1

/** Hard caps so a command that prints forever cannot exhaust the :shell heap. */
private const val MAX_CAPTURE_BYTES = 64 * 1024
private const val MAX_ARGV = 64
private const val MAX_ARG_CHARS = 4096

/** How long the binder thread waits for the two drain threads after the child exits. */
private const val DRAIN_JOIN_MS = 250L
private const val MAX_TIMEOUT_MS = 30_000L
private const val MIN_TIMEOUT_MS = 100L

/**
 * The Shizuku "user service". Shizuku creates it reflectively in the app's
 * `:shell` process, running as shell (uid 2000) or root (uid 0).
 *
 * There is no Android Context here on purpose: a user-service process is not a
 * valid application process, so receivers and content resolvers would not work.
 * The only thing this class does is spawn a child with an explicit argv and
 * report what happened.
 */
class ShellService : IShellService.Stub() {

    /** Children still owned by this process; destroy() must kill them before exit. */
    private val active: MutableSet<Process> =
        Collections.newSetFromMap(ConcurrentHashMap<Process, Boolean>())

    override fun version(): Int = SERVICE_VERSION

    override fun exec(cmd: Array<String>?, timeoutMs: Long): String {
        val argv = cmd?.toList().orEmpty()
        if (argv.isEmpty() || argv.size > MAX_ARGV ||
            argv.any { it.length > MAX_ARG_CHARS || it.indexOf('\u0000') >= 0 }
        ) {
            return result(Outcome.FAILED, "", "", -1, false, "invalid argv")
        }

        val timeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        var process: Process? = null
        val drains = mutableListOf<Drain>()
        val threads = mutableListOf<Thread>()
        try {
            process = ProcessBuilder(argv).start()
            active.add(process)
            val current = process
            val out = Drain(current.inputStream)
            val err = Drain(current.errorStream)
            drains += out
            drains += err
            for ((index, drain) in drains.withIndex()) {
                Thread(drain, "andee-shell-drain-$index").apply { isDaemon = true }
                    .also { threads += it; it.start() }
            }

            val finished = current.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                // The command was sent; it may already have had an effect.
                // Report UNKNOWN so the caller never blindly retries it.
                current.destroyForcibly()
            }

            // Finding 8: wait for the drainers before reading their buffers.
            joinDrains(threads, DRAIN_JOIN_MS)
            // Closing the pipes unblocks a reader left behind by a child that
            // inherited the descriptor; a second bounded join then finishes it.
            closeQuietly(current.inputStream)
            closeQuietly(current.errorStream)
            joinDrains(threads, DRAIN_JOIN_MS)

            val exit = if (finished) current.exitValue() else -1
            val outcome = when {
                !finished -> Outcome.UNKNOWN
                exit == 0 -> Outcome.SUCCESS
                else -> Outcome.FAILED
            }
            return result(
                outcome,
                out.text(),
                err.text(),
                exit,
                out.truncated || err.truncated,
                null,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "exec failed: ${t.message}", t)
            return result(Outcome.FAILED, "", "", -1, false, t.message ?: t.javaClass.simpleName)
        } finally {
            process?.let { current ->
                if (current.isAlive) current.destroyForcibly()
                active.remove(current)
                closeQuietly(current.inputStream)
                closeQuietly(current.errorStream)
            }
            joinDrains(threads, DRAIN_JOIN_MS)
        }
    }

    /**
     * Reserved Shizuku destroy transaction. It must not wait for an in-flight
     * exec: a caller may hold a lock that this thread would need. Kill the
     * children first (finding 6) — System.exit does not kill them — then exit.
     */
    override fun destroy() {
        for (child in active.toList()) {
            runCatching { child.destroyForcibly() }
        }
        active.clear()
        Log.i(TAG, "destroy: children cancelled")
        System.exit(0)
    }

    private fun closeQuietly(stream: InputStream?) {
        if (stream == null) return
        runCatching { stream.close() }
    }

    private fun joinDrains(threads: List<Thread>, waitMs: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        for (thread in threads) {
            val left = deadline - System.nanoTime()
            if (left <= 0) break
            // NANOSECONDS.toMillis truncates a positive sub-millisecond
            // remainder to 0, and Thread.join(0) waits forever. Always bound
            // the wait to at least one millisecond.
            try {
                thread.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(left)))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun result(
        outcome: String,
        stdout: String,
        stderr: String,
        exit: Int,
        truncated: Boolean,
        reason: String?,
    ): String {
        val json = JSONObject()
            .put("outcome", outcome)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("exit", exit)
            .put("truncated", truncated)
        if (reason != null) json.put("reason", reason)
        return json.toString()
    }

    /** Bounded reader. Each instance owns one stream, so no lock is needed. */
    private class Drain(private val input: InputStream?) : Runnable {
        private val bytes = ByteArrayOutputStream()
        private var overCap = false

        val truncated: Boolean get() = overCap

        fun text(): String = bytes.toString("UTF-8")

        override fun run() {
            if (input == null) return
            val chunk = ByteArray(4096)
            try {
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    if (read == 0) continue
                    val room = MAX_CAPTURE_BYTES - bytes.size()
                    if (room > 0) bytes.write(chunk, 0, minOf(read, room))
                    if (read > room) overCap = true
                }
            } catch (ignored: IOException) {
                // Expected when a timed-out child's pipe is closed in finally.
            }
        }
    }

    private object Outcome {
        const val SUCCESS = "success"
        const val FAILED = "failed"
        const val UNKNOWN = "unknown"
    }
}
