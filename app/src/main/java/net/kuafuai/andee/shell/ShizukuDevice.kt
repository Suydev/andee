package net.kuafuai.andee.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import net.kuafuai.andee.BuildConfig
import net.kuafuai.andee.util.Log
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

private const val TAG = "ShizukuDevice"
private const val SERVICE_TAG = "andee-shell"
private const val REQUEST_CODE = 0x5A11
private const val LOCK_WAIT_MS = 2_000L
private const val REBIND_DELAY_MS = 1_000L
private const val MAX_RESULT_CHARS = 70_000
private const val MAX_BIND_ATTEMPTS = 3

/** What the dispatcher needs from the privileged side. Kept small on purpose. */
interface ShellProvider {
    fun start(context: Context)
    fun status(): ShellStatus
    fun exec(cmd: List<String>, timeoutMs: Long = 5_000L): ShellResult
    fun requestPermission(action: (() -> Unit)? = null)
    fun stop()
}

enum class ShellStatus { UNAVAILABLE, PERMISSION_NEEDED, READY }

/**
 * [ShellOutcome.Unknown] is the important value: a timed-out command may already
 * have had an effect. The caller must not retry it through accessibility.
 */
sealed interface ShellOutcome {
    object Success : ShellOutcome
    object Unavailable : ShellOutcome
    object Unknown : ShellOutcome
    data class Failed(val reason: String) : ShellOutcome
}

data class ShellResult(
    val outcome: ShellOutcome = ShellOutcome.Unknown,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val truncated: Boolean = false,
    val detail: String? = null,
)

/**
 * Owns the Shizuku binder and the `:shell` UserService connection.
 *
 * Only the main process ever touches this object. All Shizuku listener
 * callbacks are delivered on the main thread; none of them takes [lock] or
 * blocks, so a hung binder call cannot freeze the UI (finding 4).
 */
object ShizukuDevice : ShellProvider {

    /** Fair: three dispatch pools contend for it and starvation is not acceptable. */
    private val lock = ReentrantLock(true)
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shizuku-device").apply { isDaemon = true }
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var service: IShellService? = null
    @Volatile private var wantBound = false
    @Volatile private var stopped = false
    @Volatile private var bindInFlight = false
    @Volatile private var bindAttempts = 0
    @Volatile private var bindArgs: Shizuku.UserServiceArgs? = null
    @Volatile private var bindConnection: ServiceConnection? = null
    @Volatile private var pendingAction: (() -> Unit)? = null
    @Volatile private var pendingCode = -1
    private val listenersInstalled = AtomicBoolean(false)
    private var bindToken = 0

    /**
     * Every Shizuku call is total: after manager-binder death `requireService()`
     * throws IllegalStateException, and a listener runs on the main thread.
     * None of these helpers may ever propagate that.
     */
    private fun shizukuVersionAtLeast(min: Int): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.getVersion() >= min
    }.getOrDefault(false)

    private fun shizukuBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun shizukuHasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun shizukuDeniedPermanently(): Boolean =
        runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)

    override fun start(context: Context) {
        stopped = false
        appContext = context.applicationContext
        wantBound = true
        bindAttempts = 0
        if (listenersInstalled.compareAndSet(false, true)) {
            val results = listOf(
                runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceived) }
                    .onFailure { Log.w(TAG, "binder listener failed: ${it.message}", it) },
                runCatching { Shizuku.addBinderDeadListener(binderDead) }
                    .onFailure { Log.w(TAG, "dead listener failed: ${it.message}", it) },
                runCatching { Shizuku.addRequestPermissionResultListener(permissionResult) }
                    .onFailure { Log.w(TAG, "permission listener failed: ${it.message}", it) },
            )
            // Allow a later start() to retry any registration that failed.
            if (results.any { it.isFailure }) listenersInstalled.set(false)
        }
        scheduleBind(0)
    }

    override fun status(): ShellStatus {
        if (stopped || !shizukuVersionAtLeast(10) || !shizukuBinderAlive()) {
            return ShellStatus.UNAVAILABLE
        }
        if (!shizukuHasPermission()) return ShellStatus.PERMISSION_NEEDED
        val current = service ?: return ShellStatus.UNAVAILABLE
        return if (runCatching { current.asBinder()?.pingBinder() == true }.getOrDefault(false)) {
            ShellStatus.READY
        } else {
            ShellStatus.UNAVAILABLE
        }
    }

    /**
     * Never waits for permission while holding [lock]: the permission result is
     * delivered on the main thread and would post a worker that needs the same
     * lock. When permission is needed we return [ShellOutcome.Unavailable]
     * immediately; a later grant re-runs the optional [action] on the worker.
     */
    override fun requestPermission(action: (() -> Unit)?) {
        if (stopped || !shizukuVersionAtLeast(11) || !shizukuBinderAlive()) return
        if (shizukuHasPermission()) {
            if (action != null) worker.execute { runCatching(action) }
            return
        }
        if (shizukuDeniedPermanently()) return
        pendingCode = REQUEST_CODE
        pendingAction = action
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure {
                pendingAction = null
                pendingCode = -1
                Log.w(TAG, "permission request failed: ${it.message}", it)
            }
    }

    override fun exec(cmd: List<String>, timeoutMs: Long): ShellResult {
        if (!lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
            return ShellResult(ShellOutcome.Unknown, detail = "shell is busy")
        }
        try {
            val current = service ?: return unavailable("shell service not connected")
            if (!runCatching { current.asBinder()?.pingBinder() == true }.getOrDefault(false)) {
                service = null
                scheduleBind(0)
                return unavailable("shell service binder is dead")
            }
            val raw = try {
                current.exec(cmd.toTypedArray(), timeoutMs.coerceIn(100L, 30_000L))
            } catch (e: RemoteException) {
                service = null
                scheduleBind(0)
                return unavailable("shell service died: ${e.message}")
            } catch (e: Throwable) {
                service = null
                scheduleBind(0)
                return unavailable("shell call failed: ${e.message}")
            }
            return parse(raw)
        } finally {
            lock.unlock()
        }
    }

    override fun stop() {
        // Set first: a sticky listener already queued on the main thread must
        // see this before it can set wantBound back to true (finding 4).
        stopped = true
        wantBound = false
        pendingAction = null
        pendingCode = -1
        bindInFlight = false
        bindAttempts = 0
        synchronized(this) { bindToken++ }
        if (listenersInstalled.compareAndSet(true, false)) {
            runCatching { Shizuku.removeBinderReceivedListener(binderReceived) }
            runCatching { Shizuku.removeBinderDeadListener(binderDead) }
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionResult) }
        }
        val args = bindArgs
        val connection = bindConnection
        bindArgs = null
        bindConnection = null
        service = null
        if (args != null && connection != null) {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
                .onFailure { Log.w(TAG, "unbind failed: ${it.message}", it) }
        }
    }

    private fun unavailable(reason: String): ShellResult =
        ShellResult(ShellOutcome.Unavailable, detail = reason)

    private fun parse(raw: String?): ShellResult {
        if (raw.isNullOrBlank()) {
            return ShellResult(ShellOutcome.Unknown, detail = "empty shell response")
        }
        return try {
            val json = JSONObject(raw)
            val outcome = when (json.optString("outcome")) {
                "success" -> ShellOutcome.Success
                "failed" -> ShellOutcome.Failed(
                    json.optString("reason").ifBlank { "shell command failed" },
                )
                "unknown" -> ShellOutcome.Unknown
                else -> ShellOutcome.Unknown
            }
            ShellResult(
                outcome = outcome,
                stdout = scrub(json.optString("stdout")),
                stderr = scrub(json.optString("stderr")),
                exitCode = if (json.has("exit")) json.optInt("exit") else null,
                truncated = json.optBoolean("truncated"),
                detail = json.optString("reason").ifBlank { null },
            )
        } catch (t: Throwable) {
            ShellResult(ShellOutcome.Unknown, detail = "bad shell response: ${t.message}")
        }
    }

    private fun scrub(value: String): String =
        value.replace("\u0000", "").take(MAX_RESULT_CHARS)

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        // Main thread; the listener is sticky and may fire more than once
        // (Shizuku.java:199). scheduleBind is idempotent while bound.
        if (stopped) return@OnBinderReceivedListener
        wantBound = true
        bindAttempts = 0
        scheduleBind(0)
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        // Main thread: only volatile writes and a scheduled retry, no lock.
        if (stopped) return@OnBinderDeadListener
        service = null
        bindInFlight = false
        bindAttempts = 0
        wantBound = true
        scheduleBind(REBIND_DELAY_MS)
    }

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (code != pendingCode) return@OnRequestPermissionResultListener
        val action = pendingAction
        pendingCode = -1
        pendingAction = null
        if (stopped) return@OnRequestPermissionResultListener
        if (grant != PackageManager.PERMISSION_GRANTED) return@OnRequestPermissionResultListener
        bindAttempts = 0
        worker.execute {
            action?.let { runCatching(it) }
            scheduleBind(0)
        }
    }

    /** Tokenised so a late retry cannot override a newer one. */
    private fun scheduleBind(delayMs: Long) {
        val token = nextBindToken()
        main.postDelayed({
            if (token != bindToken) return@postDelayed
            if (stopped || !wantBound || service != null) return@postDelayed
            // The binder work goes to the worker: after manager-binder death a
            // Shizuku call can throw on the main thread, and bindUserService is
            // a blocking binder transaction.
            worker.execute { doBind() }
        }, delayMs)
    }

    private fun nextBindToken(): Int = synchronized(this) { ++bindToken }

    /** Runs on the worker thread; every Shizuku call below is guarded. */
    private fun doBind() {
        val context = appContext ?: return
        if (stopped || !wantBound || service != null || bindInFlight) return
        if (!shizukuVersionAtLeast(10) || !shizukuBinderAlive()) return
        if (!shizukuHasPermission()) {
            requestPermission(null)
            return
        }
        if (bindAttempts >= MAX_BIND_ATTEMPTS) {
            Log.w(TAG, "bind gave up after $bindAttempts attempts")
            return
        }
        bindAttempts++
        bindInFlight = true
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bindInFlight = false
                if (binder == null || !runCatching { binder.pingBinder() }.getOrDefault(false)) {
                    service = null
                    scheduleBind(REBIND_DELAY_MS)
                    return
                }
                service = runCatching { IShellService.Stub.asInterface(binder) }.getOrNull()
                if (service == null) {
                    scheduleBind(REBIND_DELAY_MS)
                    return
                }
                bindAttempts = 0
                // Shizuku already links to death; this is a second notification
                // path so a lost ServiceConnection callback cannot strand us.
                runCatching { binder.linkToDeath({ service = null; scheduleBind(0) }, 0) }
                    .onFailure { Log.w(TAG, "linkToDeath failed: ${it.message}", it) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bindInFlight = false
                service = null
                // Shizuku removes its cached dead connection in this same
                // main-loop runnable after this callback (died(), lines 69-76).
                // Posting makes our bind run after that cleanup, so we never
                // reuse the dead connection object (finding 7).
                scheduleBind(0)
            }
        }
        bindConnection = connection
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellService::class.java.name),
        )
            .processNameSuffix("shell")
            .daemon(false)
            .tag(SERVICE_TAG)
            .version(BuildConfig.VERSION_CODE)
            .debuggable(BuildConfig.DEBUG)
        bindArgs = args
        try {
            Shizuku.bindUserService(args, connection)
            scheduleWatchdog(connection, REBIND_DELAY_MS * 3)
        } catch (t: Throwable) {
            bindInFlight = false
            Log.w(TAG, "bind failed: ${t.message}", t)
            scheduleBind(REBIND_DELAY_MS)
        }
    }

    /**
     * onServiceConnected is not guaranteed: the manager can drop the request
     * when its binder just died. Clear [bindInFlight] so the retry is not
     * discarded by the guard in [doBind], and keep the retry count bounded.
     */
    private fun scheduleWatchdog(connection: ServiceConnection, delayMs: Long) {
        main.postDelayed({
            if (stopped || !wantBound) return@postDelayed
            if (connection !== bindConnection || service != null) return@postDelayed
            Log.w(TAG, "bind watchdog: no service after ${delayMs}ms, retrying")
            bindInFlight = false
            scheduleBind(0)
        }, delayMs)
    }
}
