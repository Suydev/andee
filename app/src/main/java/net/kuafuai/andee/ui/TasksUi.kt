package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.kuafuai.andee.compat.WindowMetricsCompat
import net.kuafuai.andee.config.Notebook
import net.kuafuai.andee.config.Scheduler
import net.kuafuai.andee.config.Tasks
import net.kuafuai.andee.config.VoiceConfig

/**
 * 在办 on the phone: the 「事」 Andee has in hand, as its own card from the top bar — the same split as the Mac's
 * sidebar (`desktop/TasksUi.kt`): 动态 says what happened, 在办 what is in hand, 小本本 who Andee is (its last tab is
 * now 定时, the alarms).
 *
 * The list puts first what wants the user — delivered (Andee says it is done; only the user closes it) and answered
 * — then the rest, newest-touched first. A row opens the 事 in the same card: its state, its record, and what to do —
 * 接着办 (a turn now, the task named by the device), 说点要求再办 (the next words are about it), 办完了, 放下; for a
 * delivered one ✓ 办完了 / 还没完. Finished ones of the last seven days are at the foot, and can be reopened.
 *
 * Plain [t] strings rather than resources, like [SettingsUi]: one card, both languages, nothing to look up.
 */
class TasksUi(
    private val context: Context,
    /** 接着办: a turn about [Tasks.Task], started now. */
    private val onContinue: (Tasks.Task) -> Unit,
    /** 说点要求再办: the next words the user types or says are about this task. */
    private val onTalk: (Tasks.Task) -> Unit,
    /** 工作文件 ›: what worker [id] made, on the computer, as a drive. Null hides the entry. */
    private val onFiles: ((worker: String, title: String) -> Unit)? = null,
    /**
     * Stop worker [id] on the computer (the protocol's only way to abandon a job is to end its process). Blocks on the link, so
     * it is called off the main thread; the result is whether the computer stopped it. Null hides the button.
     */
    private val onStopWorker: ((worker: String) -> Boolean)? = null,
    /** Hand the service a callback to run when a worker's progress changes (or `{}` to stop). */
    private val watchProgress: (() -> Unit) -> Unit = {},
    private val onDismiss: () -> Unit = {},
    /** Open straight on this task's page. */
    openTask: String? = null,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var frosted = false

    /** The 事 open in the card; null shows the list. */
    private var open: String? = openTask

    private fun zh() = VoiceConfig.uiLanguage(context) == "zh"
    private fun t(z: String, e: String) = if (zh()) z else e
    private val tasks: Tasks get() = Notebook.tasks(context)

    fun show() {
    if (root != null) return
    // A worker made progress, or ended: draw again. Only while this card is up, and only on the main thread.
    watchProgress { root?.post { if (root != null) refreshLive() } }
        val p = WindowManager.LayoutParams(
            cardWidth(), cardHeight(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        frosted = Glass.frost(context, wm, p)
        val v = build()
        runCatching {
            v.hideFromAccessibility()
            wm.addView(v, p)
            root = v
            OwnCard.shown()
            Glass.enter(v)
        }
    }

    fun hide() {
    val v = root ?: return
    root = null
    watchProgress {}
        Glass.exit(v) {
            runCatching { wm.removeView(v) }
            OwnCard.hidden()
            onDismiss()
        }
    }

    private fun rebuild() {
        val old = root ?: return
        liveLines.clear()
        runDots.clear()
        liveCard = null
        val v = build()
        runCatching {
            v.hideFromAccessibility()
            val p = old.layoutParams as WindowManager.LayoutParams
            wm.removeView(old)
            wm.addView(v, p)
            root = v
            Glass.fadeIn(v)
        }
    }

    private fun cardWidth(): Int = minOf(dp(560), (WindowMetricsCompat.bounds(wm).width() * 0.96f).toInt())
    private fun cardHeight(): Int = minOf(dp(720), (WindowMetricsCompat.bounds(wm).height() * 0.90f).toInt())

    @SuppressLint("SetTextI18n")
    private fun build(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(context, dp(28), frosted)
            clipToOutline = true
        }
        val task = open?.let { tasks.get(it) }
        card.addView(header(task), matchWrap())
        card.addView(View(context).apply { setBackgroundColor(Color.parseColor(Glass.PANEL_EDGE)) }, LinearLayout.LayoutParams(MATCH, dp(1)))
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        if (task != null) buildTask(body, task) else buildList(body)
        val scroll = ScrollView(context)
        scroll.addView(body, matchWrap())
        card.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return card
    }

    private fun header(task: Tasks.Task?): LinearLayout {
        val h = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(14), dp(12))
        }
        if (task != null) h.addView(chip("‹ " + t("In progress", "In hand")) { open = null; rebuild() })
        h.addView(
            label(if (task != null) "" else t("In progress", "In hand"), Glass.Type.HEADLINE, Glass.TITLE),
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        h.addView(chip("✕") { hide() })
        return h
    }

    // ---- the list ----

    private fun buildList(body: LinearLayout) {
        val now = System.currentTimeMillis()
        val active = TaskRows.ordered(tasks.active())
        if (active.isEmpty()) {
            body.addView(label(t("Nothing in progress right now. When Andee picks up something that can't be finished in one go (needs to ask someone, wait for a reply, or be done later), it shows up here until it's done.",
            "Nothing in hand. When Andee takes on something it cannot finish at once, it shows here until it is done."),
            Glass.Type.CAPTION, Glass.SECONDARY), matchWrap())
        }
        for (task in active) {
            val row = panel()
            row.addView(oneLine(label(task.what, Glass.Type.BODY, Glass.TITLE)), matchWrap())
            val state = label("${task.id} · " + TaskRows.state(task, now, zh()), Glass.Type.CAPTION,
            if (task.status == "ready" || task.status == Tasks.DELIVERED) Glass.ACCENT else Glass.SECONDARY, top = 4).apply {
                // Up to three lines, each cut at its end: where it is, its plan, what it is doing (the Mac's rows too).
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val dot = runningDot()
            dot.visibility = if (TaskRows.runningWorker(task) != null) View.VISIBLE else View.GONE
            line.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(7); topMargin = dp(4) })
            line.addView(state, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(line, matchWrap())
            runDots[task.id] = dot
            // A running worker's line is rewritten in place as its progress arrives; the card is not rebuilt for it.
            liveLines[task.id] = state
            row.isClickable = true
            Glass.pressable(row)
            row.setOnClickListener { open = task.id; rebuild() }
            body.addView(row, matchWrap().apply { bottomMargin = dp(10) })
        }
        val week = 7L * 24 * 3_600_000
        val closed = tasks.all().filter { !it.active && now - it.closedAt < week }.sortedByDescending { it.closedAt }
        if (closed.isNotEmpty()) {
            body.addView(label(t("Recently finished", "Recently finished"), Glass.Type.CAPTION, Glass.MUTED, top = 8), matchWrap().apply { bottomMargin = dp(6) })
            for (task in closed) {
                val row = panel()
                row.addView(label((if (task.status == "done") "✓ " else "— ") + task.what, Glass.Type.CAPTION, Glass.SECONDARY), matchWrap())
                row.isClickable = true
                Glass.pressable(row)
                row.setOnClickListener { open = task.id; rebuild() }
                body.addView(row, matchWrap().apply { bottomMargin = dp(8) })
            }
        }
    }

    // ---- one 事 ----

    private fun buildTask(body: LinearLayout, task: Tasks.Task) {
    val now = System.currentTimeMillis()
    buildLiveCard(body, task)
        body.addView(label(task.what, Glass.Type.HEADLINE, Glass.TITLE), matchWrap())
        body.addView(label("${task.id} · " + TaskRows.state(task, now, zh()), Glass.Type.CAPTION, Glass.SECONDARY, top = 4), matchWrap().apply { bottomMargin = dp(12) })
        buildFilesEntry(body, task)
        when {
            task.status == Tasks.DELIVERED -> {
                val p = panel()
                p.addView(label(t("Andee says it delivered: ", "Andee says it delivered: ") + task.result.ifBlank { t("(didn't say what was delivered)", "(it did not say what)") },
                Glass.Type.BODY, Glass.TITLE), matchWrap())
                p.addView(label(t("Whether it's done is up to you.", "Whether it is done is yours to say."), Glass.Type.CAPTION, Glass.SECONDARY, top = 4), matchWrap())
                body.addView(p, matchWrap().apply { bottomMargin = dp(10) })
                body.addView(buttons(
                    t("✓ Done", "✓ Done") to { confirm(task) },
                    t("Not done yet", "Not yet") to {
                        tasks.reopen(task.id, t("User says it's not done", "the user said it is not done"))
                        hide(); onTalk(task)
                    },
                ), matchWrap().apply { bottomMargin = dp(12) })
            }
            task.active -> {
                val running = TaskRows.runningWorker(task)
                if (running != null && onStopWorker != null) buildRunningActions(body, task, running)
                else body.addView(buttons(
                    t("Keep going", "Carry on") to { hide(); onContinue(task) },
                    t("Add some requirements", "Say how") to { hide(); onTalk(task) },
                    t("Done", "Done") to { confirm(task) },
                    t("Let go", "Let go") to { letGo(task) },
                ), matchWrap().apply { bottomMargin = dp(12) })
            }
            else -> {
                body.addView(label((if (task.status == "done") t("Done: ", "Done: ") else t("Let go of: ", "Let go: ")) + task.result,
                Glass.Type.CAPTION, Glass.SECONDARY), matchWrap().apply { bottomMargin = dp(8) })
                body.addView(buttons(t("Reopen", "Reopen") to { tasks.reopen(task.id, t("User reopened", "reopened by the user")); rebuild() }),
                matchWrap().apply { bottomMargin = dp(12) })
            }
        }
        val from = panel()
        from.addView(label(t("Came from: ", "From: ") + TaskRows.whose(task.from, zh()) +
        task.from.quote.takeIf { it.isNotBlank() }?.let { " — 「${it.take(120)}」" }.orEmpty(), Glass.Type.CAPTION, Glass.TITLE), matchWrap())
        if (task.deliverTo.kind != "user") from.addView(label(t("Handed off when done: ", "Deliver to: ") + TaskRows.whose(task.deliverTo, zh()), Glass.Type.CAPTION, Glass.TITLE, top = 4), matchWrap())
        if (task.next.isNotBlank()) from.addView(label(t("Next step: ", "Next: ") + task.next, Glass.Type.CAPTION, Glass.TITLE, top = 4), matchWrap())
        body.addView(from, matchWrap().apply { bottomMargin = dp(10) })
        if (task.waits.isNotEmpty()) {
            body.addView(label(t("Waited / waiting", "Waits"), Glass.Type.CAPTION, Glass.MUTED), matchWrap().apply { bottomMargin = dp(6) })
            for (w in task.waits) {
                val p = panel()
                p.addView(label((if (w.open) "○ " else "● ") + TaskRows.who(w, zh()) + (if (w.asked.isNotBlank()) " · " + w.asked else ""), Glass.Type.CAPTION, Glass.TITLE), matchWrap())
                if (!w.open) p.addView(label("「" + w.answer + "」", Glass.Type.CAPTION, Glass.SECONDARY, top = 4), matchWrap())
                body.addView(p, matchWrap().apply { bottomMargin = dp(8) })
            }
        }
        if (task.steps.isNotEmpty()) {
            body.addView(label(t("Elapsed", "Steps"), Glass.Type.CAPTION, Glass.MUTED, top = 4), matchWrap().apply { bottomMargin = dp(6) })
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            for (s in task.steps) body.addView(label(fmt.format(java.util.Date(s.at)) + "  " + s.text, Glass.Type.CAPTION, Glass.TITLE), matchWrap().apply { bottomMargin = dp(4) })
        }
    }

    /**
     * 工作文件 ›: the way in to what the worker made, on the computer. Shown for every worker this task has handed work to, open
     * or answered, whether it is running or done — a running job already has files in its folder. Only while a computer
     * offers its agent to this phone, since that is what the listing is asked of.
     */
    private fun buildFilesEntry(body: LinearLayout, task: Tasks.Task) {
        val open = onFiles ?: return
        val workers = task.waits.filter { it.kind == "worker" }.map { it.name }.distinct()
        for (w in workers) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Glass.panel(context, dp(14))
                isClickable = true
                Glass.pressable(this)
                setOnClickListener { open(w, task.what) }
            }
            row.addView(label("📁", Glass.Type.BODY, Glass.TITLE).apply { setPadding(0, 0, dp(10), 0) })
            row.addView(label(t("Work files", "Work files") + " · dsh #$w", Glass.Type.BODY, Glass.TITLE), LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(label(t("On the computer — tap to download ›", "on the computer, fetched when opened ›"), Glass.Type.CAPTION, Glass.SECONDARY))
            body.addView(row, matchWrap().apply { bottomMargin = dp(10) })
        }
    }

    /**
     * The worker this 事 waits on, while it runs: what it is on, its own checklist and its newest actions — the phone's twin of
     * the Mac's live card. Nothing here when it is not running (the report has not come, or it ended).
     */
    private fun buildLiveCard(body: LinearLayout, task: Tasks.Task) {
        liveCard = null
        liveCardTask = task.id
        val w = task.waits.lastOrNull { it.open && it.kind == "worker" } ?: return
        if (net.kuafuai.andee.peer.RemoteProgress.of(w.name) == null) return
        val p = panel()
        liveCard = p
        fillLiveCard(p)
        body.addView(p, matchWrap().apply { bottomMargin = dp(10) })
    }

    /** The card showing the running worker of the open task, if one is: refilled in place as progress arrives. */
    private var liveCard: LinearLayout? = null
    private var liveCardTask: String? = null

    private fun fillLiveCard(p: LinearLayout) {
        val task = liveCardTask?.let { tasks.get(it) } ?: return
        val w = task.waits.lastOrNull { it.open && it.kind == "worker" } ?: return
        val s = net.kuafuai.andee.peer.RemoteProgress.of(w.name) ?: return
        p.removeAllViews()
        p.addView(oneLine(label("dsh #${w.name} · " + net.kuafuai.andee.peer.RemoteProgress.line(s, System.currentTimeMillis(), zh()),
            Glass.Type.CAPTION, Glass.ACCENT)), matchWrap())
        if (s.todos.isNotEmpty()) {
            p.addView(label(t("Its checklist ${s.done}/${s.todos.size}", "Its checklist ${s.done}/${s.todos.size}"), Glass.Type.CAPTION, Glass.MUTED, top = 6), matchWrap())
            for (td in s.todos.take(8)) p.addView(oneLine(label((when (td.status) { "completed" -> "✓ "; "in_progress" -> "▸ "; else -> "○ " }) + td.text,
                Glass.Type.CAPTION, if (td.status == "in_progress") Glass.TITLE else Glass.SECONDARY, top = 2)), matchWrap())
        }
        if (s.recent.isNotEmpty()) {
            p.addView(label(t("Recently worked on", "Lately"), Glass.Type.CAPTION, Glass.MUTED, top = 6), matchWrap())
            for (a in s.recent.takeLast(4)) p.addView(oneLine(label("· $a", Glass.Type.CAPTION, Glass.SECONDARY, top = 2)), matchWrap())
        }
        if (s.files > 0) p.addView(label(t("Produced ${s.files} files (on the computer)", "${s.files} files so far (on the computer)"), Glass.Type.CAPTION, Glass.SECONDARY, top = 6), matchWrap())
    }

    /** 放下: close the 事 as dropped and cancel the alarms it had armed. */
    private fun letGo(task: Tasks.Task) {
        tasks.close(task.id, "dropped", t("User let go of it in In progress", "let go by the user in In progress"))
            .onSuccess { c -> c.cancel.forEach { Scheduler.cancelTodo(context, it) } }
        open = null; rebuild()
    }

    /** The user is asked about a running worker before the 事 is closed: [asking] is "done" or "dropped", null for the buttons. */
    private var asking: String? = null

    /**
     * A worker is running on this 事. Carrying on or adding a requirement would only spend a turn (dsh takes no word mid-run, it
     * can only be stopped), so the one action is 停止 dsh. Closing the 事 is still allowed but asks about the worker first, so it
     * is not left running on the user's quota with nothing to receive its report.
     */
    private fun buildRunningActions(body: LinearLayout, task: Tasks.Task, worker: String) {
        val mode = asking
        if (mode == null) {
            body.addView(buttons(
                t("Stop dsh", "Stop dsh") to { stopWorker(worker) },
                t("Done", "Done") to { asking = "done"; rebuild() },
                t("Let go", "Let go") to { asking = "dropped"; rebuild() },
            ), matchWrap().apply { bottomMargin = dp(12) })
            return
        }
        val done = mode == "done"
        val p = panel()
        p.addView(label(t("dsh #$worker is still running", "dsh #$worker is still running"), Glass.Type.BODY, Glass.DANGER), matchWrap())
        p.addView(label(
            t("Stop it first? If not, it'll run to completion using your quota, and by the time the report comes back the matter will already be closed with nobody to receive it.",
                "Stop it first? If not it runs to the end on your quota, and its report arrives after this is closed, with nobody to take it."),
            Glass.Type.CAPTION, Glass.TITLE, top = 4,
        ), matchWrap())
        body.addView(p, matchWrap().apply { bottomMargin = dp(8) })
        val finish = { if (done) confirm(task) else letGo(task); asking = null }
        body.addView(buttons(
            t("Stop and " + if (done) "done" else "Let go", "Stop it and " + if (done) "finish" else "let go") to { stopWorker(worker); finish() },
            t("Let it keep running", "Leave it running") to { finish() },
            t("Cancel", "Cancel") to { asking = null; rebuild() },
        ), matchWrap().apply { bottomMargin = dp(12) })
    }

    /** 停止 dsh: asked of the computer off the main thread; a row says what happened and the card redraws when it answers. */
    private fun stopWorker(worker: String) {
        val stop = onStopWorker ?: return
        Thread {
            val ok = runCatching { stop(worker) }.getOrDefault(false)
            Handler(Looper.getMainLooper()).post {
                ChatHistory.addEvent(
                    net.kuafuai.andee.brain.BrainEvent.Kind.STOPPED.name,
                    if (ok) t("You stopped dsh #$worker on the computer", "You stopped dsh #$worker on the computer")
                    else t("Couldn't stop dsh #$worker on the computer (can't reach the computer, or it has already finished)", "Could not stop dsh #$worker on the computer (out of reach, or it already ended)"),
                    "",
                )
                if (root != null) rebuild()
            }
        }.apply { isDaemon = true; name = "stop-worker" }.start()
    }

    private fun confirm(task: Tasks.Task) {
        tasks.confirm(task.id).onSuccess { c -> c.cancel.forEach { Scheduler.cancelTodo(context, it) } }
        ChatHistory.addEvent(net.kuafuai.andee.brain.BrainEvent.Kind.TASK_DONE.name, t("You confirmed it's done: ", "You confirmed done: ") + task.what, "")
        open = null
        rebuild()
    }

    // ---- small parts ----

    private fun buttons(vararg actions: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.forEachIndexed { i, (text, act) ->
            if (i > 0) row.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
            row.addView(TextView(context).apply {
                this.text = text
                textSize = Glass.Type.CAPTION
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(if (i == 0) "#FFFFFFFF" else Glass.TITLE))
                background = if (i == 0) Glass.tinted(context, dp(14)) else Glass.panel(context, dp(14))
                setPadding(dp(6), dp(11), dp(6), dp(11))
                isClickable = true
                Glass.pressable(this)
                setOnClickListener { act() }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return row
    }

    private fun chip(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.SECONDARY))
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        background = Glass.panel(context, dp(16))
        isClickable = true
        Glass.pressable(this)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, dp(32))
    }

    private fun panel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = Glass.panel(context, dp(14))
    }

    /** One line, cut with an ellipsis: how the Mac's rows read, so a long title or status never makes a row taller. */
    private fun oneLine(v: TextView): TextView = v.apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** The status line of each row on screen, by task id: what [refreshLive] rewrites. */
    private val liveLines = HashMap<String, TextView>()

    /** The breathing dot beside each row, shown while that task's worker is running. */
    private val runDots = HashMap<String, View>()

    /** An accent dot that fades in and out, for as long as it is attached: the mark that something is still working. */
    private fun runningDot(): View = View(context).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(Glass.ACCENT))
        }
        isClickable = false
        val pulse = object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) return
                // Deep and fairly quick, so it reads as alive at a glance (owner's call, 11-13: it was too faint to notice).
                animate().alpha(if (alpha > 0.5f) 0.12f else 1f).setDuration(600).withEndAction(this).start()
            }
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { post(pulse) }
            override fun onViewDetachedFromWindow(v: View) { animate().cancel() }
        })
    }

    /**
     * Progress arrived: rewrite the lines of the running rows, and nothing else. Rebuilding the card for it made it blink
     * every two seconds. A change in *which* tasks there are (one opened, one ended) still rebuilds, once.
     */
    private fun refreshLive() {
        val now = System.currentTimeMillis()
        // Rows are all the open tasks; if one appeared or went, the list itself changes and is built again, once.
        val seen = TaskRows.ordered(tasks.active()).map { it.id }.toSet()
        if (open == null && seen != liveLines.keys) { rebuild(); return }
        // A task that has just stopped being "running" (its report came) changes the open page too.
        if (open != null && (tasks.get(open!!)?.waits?.any { it.open && it.kind == "worker" } != (liveCard != null))) { rebuild(); return }
        for ((id, view) in liveLines) {
            val task = tasks.get(id) ?: continue
            view.text = "${task.id} · " + TaskRows.state(task, now, zh())
            runDots[id]?.visibility = if (TaskRows.runningWorker(task) != null) View.VISIBLE else View.GONE
        }
        liveCard?.let { fillLiveCard(it) }
    }

    private fun label(text: String, size: Float, color: String, top: Int = 0): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(Color.parseColor(color))
        setPadding(0, dp(top), 0, 0)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}

/** The words of a 事's row, shared by the card and the top bar's badge: order, state, who. */
object TaskRows {
    /** What wants the user first (delivered, answered), then the rest, newest-touched first. */
    fun ordered(list: List<Tasks.Task>): List<Tasks.Task> =
        list.sortedWith(compareBy<Tasks.Task> { if (it.status == Tasks.DELIVERED || it.status == "ready") 0 else 1 }.thenByDescending { it.touchedAt })

    /** The worker on the computer this task waits on, while it is running there; null otherwise. */
    fun runningWorker(t: Tasks.Task): String? =
        t.waits.lastOrNull { it.open && it.kind == "worker" }?.name?.takeIf { net.kuafuai.andee.peer.RemoteProgress.of(it) != null }

    /** How many tasks have a worker running now: the top bar key's quiet dot. */
    fun running(list: List<Tasks.Task>): Int = list.count { runningWorker(it) != null }

    /** How many want the user now: the top bar key's dot. */
    fun wanting(list: List<Tasks.Task>): Int = list.count { it.status == Tasks.DELIVERED || it.status == "ready" }

    fun state(t: Tasks.Task, now: Long, zh: Boolean): String {
        fun tr(a: String, b: String) = if (zh) a else b
        if (t.status == Tasks.DELIVERED) return tr("Delivered, waiting for your confirmation", "Delivered — confirm?")
        if (!t.active) return if (t.status == "done") tr("Done", "done") else tr("Let go of it", "let go")
        val answered = t.waits.lastOrNull { !it.open && !it.seen }
        if (t.status == "ready" && answered != null) return tr("Reply arrived: ", "Answered: ") + answered.answer.lineSequence().first().take(50)
        val open = t.waits.filter { it.open }
        // A worker on the computer that is running now: what it is on, like the Mac's row.
        open.firstOrNull { it.kind == "worker" }?.let { w ->
            net.kuafuai.andee.peer.RemoteProgress.of(w.name)?.let { s ->
                return "dsh #${w.name} · " + net.kuafuai.andee.peer.RemoteProgressText.of(s, now, zh).asList().joinToString("\n")
            }
        }
        if (open.isNotEmpty()) return tr("Waiting for ", "Waiting on ") + open.joinToString(", ") { who(it, zh) } + " · " + ago(now - open.maxOf { it.at }, zh)
        if (t.next.isNotBlank()) return tr("Next step: ", "Next: ") + t.next
        return tr("In progress", "Under way")
    }

    fun whose(s: net.kuafuai.andee.brain.Source, zh: Boolean): String = when (s.kind) {
        "user" -> if (zh) "You" else "you"
        "self" -> if (zh) "Andee itself" else "Andee itself"
        else -> s.short()
    }

    fun who(w: Tasks.Wait, zh: Boolean): String = when (w.kind) {
        "user" -> if (zh) "You" else "you"
        "worker" -> if (zh) "dsh worker on the computer #${w.name}" else "dsh worker #${w.name} on the computer"
        else -> w.short()
    }

    fun ago(ms: Long, zh: Boolean): String {
        val m = ms / 60_000
        return when {
            m < 1 -> if (zh) "just now" else "just now"
            m < 60 -> if (zh) "$m min" else "${m}m"
            m < 48 * 60 -> if (zh) "${m / 60} hr" else "${m / 60}h"
            else -> if (zh) "${m / 1440} d" else "${m / 1440}d"
        }
    }
}
