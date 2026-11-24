package net.kuafuai.andee.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DropInboxTest {
    private fun dir(): File = File.createTempFile("drop", "").also { it.delete(); it.mkdirs() }

    private fun inbox(dir: File, cap: () -> Long = { DropInbox.DEFAULT_MAX_BYTES }): DropInbox =
        DropInbox(File(dir, "inbox"), File(dir, "drop-index.json"), cap, { 10_000L })

    @Test fun `a settled text file is listed and then marked read`() {
        val d = dir(); val box = inbox(d)
        box.ensureDir()
        val f = File(box.path(), "note.txt")
        f.writeText("hello")
        f.setLastModified(8_000L)
        val item = box.scan().single()
        assertEquals(DropInbox.Status.NEW, item.status)
        assertFalse(f.name.startsWith("."))
        val got = box.read(item.id)
        assertEquals("hello", got.text)
        assertFalse(got.truncated)
        assertTrue(box.scan().single().readAt != null)
    }

    @Test fun `a file written in the last 1500 ms is skipped`() {
        val d = dir(); val box = inbox(d)
        box.ensureDir()
        File(box.path(), "new.txt").apply { writeText("x"); setLastModified(9_000L) }
        assertTrue(box.scan().isEmpty())
    }

    @Test fun `hidden files and directories are ignored`() {
        val d = dir(); val box = inbox(d)
        box.ensureDir()
        File(box.path(), ".hidden.txt").apply { writeText("x"); setLastModified(8_000L) }
        File(box.path(), "folder").mkdirs()
        assertTrue(box.scan().isEmpty())
    }

    @Test fun `at most ten files are picked up in one scan`() {
        val d = dir(); val box = inbox(d)
        box.ensureDir()
        repeat(12) { File(box.path(), "f$it.txt").apply { writeText("x"); setLastModified(8_000L) } }
        assertEquals(10, box.scan().size)
        assertEquals(12, box.scan().size)
    }

    @Test fun `the cap is read from a provider on every scan`() {
        val d = dir(); var cap = 2L * 1024 * 1024
        val box = inbox(d, { cap })
        box.ensureDir()
        val f = File(box.path(), "big.txt")
        f.writeText("x".repeat(3 * 1024 * 1024))
        f.setLastModified(8_000L)
        val id = box.scan().single().id
        assertEquals(DropInbox.Status.TOO_LARGE, box.scan().single().status)
        cap = 10L * 1024 * 1024
        assertEquals(DropInbox.Status.NEW, box.scan().single { it.id == id }.status)
    }

    @Test fun `unsupported extensions are listed but not read`() {
        val d = dir(); val box = inbox(d)
        box.ensureDir()
        File(box.path(), "app.apk").apply { writeText("binary"); setLastModified(8_000L) }
        assertEquals(DropInbox.Status.UNSUPPORTED, box.scan().single().status)
    }

    @Test fun `a corrupt index is rebuilt from the folder`() {
        val d = dir(); val box = inbox(d)
        box.ensureDir()
        File(d, "drop-index.json").writeText("{not json")
        File(box.path(), "note.txt").apply { writeText("ok"); setLastModified(8_000L) }
        assertEquals(1, box.scan().size)
    }

    @Test fun `a file removed from disk is marked missing`() {
        val d = dir(); val box = inbox(d)
        box.ensureDir()
        val f = File(box.path(), "gone.txt")
        f.writeText("x")
        f.setLastModified(8_000L)
        val id = box.scan().single().id
        f.delete()
        assertEquals(DropInbox.Status.MISSING, box.scan().single { it.id == id }.status)
    }

    @Test fun `picker names are flattened`() {
        assertEquals("passwd", DropInbox.safeName("../../etc/passwd"))
        assertEquals("picked", DropInbox.safeName(null))
        assertEquals("a_b.txt", DropInbox.safeName("a\\b.txt"))
    }
}
