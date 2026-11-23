package net.kuafuai.andee.net

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fast input wrappers are brain-private: they are in `all()` for the
 * on-device model, never in `forHub()`, and the internal `device.shell`
 * method has no schema at all so it cannot be advertised or name-resolved.
 */
class ToolSchemasShellTest {

    private val fast = listOf(
        "fast_tap" to "device.fast_tap",
        "fast_swipe" to "device.fast_swipe",
        "fast_text" to "device.fast_text",
        "fast_key" to "device.fast_key",
        "fast_launch" to "device.fast_launch",
    )

    private fun names(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }

    @Test
    fun `all five fast wrappers are on-device only`() {
        val all = names(ToolSchemas.all())
        val hub = names(ToolSchemas.forHub())
        for ((name, method) in fast) {
            assertTrue("$name must be in all()", name in all)
            assertFalse("$name must not be in forHub()", name in hub)
            assertEquals(method, ToolSchemas.methodOf(name))
            assertTrue("$method must be local-only", ToolSchemas.isLocalOnly(method))
        }
    }

    @Test
    fun `the internal shell method has no schema but is still local only`() {
        assertNull(ToolSchemas.methodOf("device.shell"))
        assertFalse("device.shell" in names(ToolSchemas.all()))
        assertTrue(ToolSchemas.isLocalOnly("device.shell"))
    }

    @Test
    fun `the hub filter cannot drift from the local only predicate`() {
        val hub = ToolSchemas.forHub()
        for (i in 0 until hub.length()) {
            val tool = hub.getJSONObject(i)
            assertFalse(tool.optBoolean("localOnly", false))
            assertFalse(ToolSchemas.isLocalOnly(tool.optString("method")))
        }
    }

    @Test
    fun `existing local only tools stay local only`() {
        assertTrue(ToolSchemas.isLocalOnly("screen.drag"))
        assertFalse(ToolSchemas.isLocalOnly("screen.tap"))
    }
}
