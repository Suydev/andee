package net.kuafuai.andee.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DropInboxLabelsTest {
    @Test fun `sizes stay short`() {
        assertEquals("512 B", DropInbox.formatSize(512))
        assertEquals("2 KB", DropInbox.formatSize(2048))
        assertEquals("3 MB", DropInbox.formatSize(3L * 1024 * 1024))
    }

    @Test fun `every status has an honest label`() {
        for (s in DropInbox.Status.values()) assertTrue(DropInbox.label(s).isNotBlank())
    }
}
