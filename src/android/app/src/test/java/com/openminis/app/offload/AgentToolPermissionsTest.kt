package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-agent-perm-rules] Pure-logic contract for the core-tool permission
 * registry (audit P0 #5).
 *
 * No Robolectric in this module, so gate() itself (Context + prefs +
 * dialog suspension) is on-device-verified. The pure part that carries
 * real risk is the NAME REGISTRY: every gateableTools entry must match a
 * ChatViewModel.executeTool when-block key, or the gate is a no-op for a
 * tool that the model can call freely (silent failure — exactly the
 * class of bug the vc43 dispatch-gap taught us: surface ≠ enforcement).
 * These tests pin the names and the lookup/defaults so a rename on either
 * side of that contract fails here instead of shipping dead.
 */
class AgentToolPermissionsTest {

    @Test
    fun `gateable tool names match the executeTool dispatch keys`() {
        // Keep in sync with ChatViewModel.executeTool when-block + the
        // py_meta_tools dispatch arm. Sorted for stable diffs.
        val expected = listOf(
            "browser_use",
            "event_rule_set",
            "job_start",
            "memory_write",
            "plugin_install",
            "py_meta_tools",
            "shell_execute",
            "spawn_agent",
        )
        assertEquals(expected, AgentToolPermissions.gateableTools.map { it.toolName }.sorted())
    }

    @Test
    fun `no gateable name collides with an offload CLI registry entry`() {
        // Both registries store levels under the same level_<name> prefs
        // key and read through the merged getLevel — a collision would let
        // the offload settings row silently retune an agent tool (or vice
        // versa).
        val offloadNames = OffloadPermissionManager.toolRegistry.map { it.toolName }.toSet()
        AgentToolPermissions.gateableTools.forEach { tool ->
            assertTrue(
                "agent tool '${tool.toolName}' collides with offload registry",
                tool.toolName !in offloadNames,
            )
        }
    }

    @Test
    fun `every gateable tool has display name and description`() {
        AgentToolPermissions.gateableTools.forEach { tool ->
            assertTrue("name ${tool.toolName}", tool.displayName.isNotBlank())
            assertTrue("desc ${tool.toolName}", tool.description.isNotBlank())
        }
    }

    @Test
    fun `default level is BYPASS for every gateable tool`() {
        // House philosophy: installing an agent app IS the consent. The
        // registry defines what CAN be constrained, not that it must be.
        AgentToolPermissions.gateableTools.forEach { tool ->
            assertEquals(
                "default for ${tool.toolName}",
                OffloadPermissionManager.PermissionLevel.BYPASS,
                AgentToolPermissions.defaultLevelFor(tool.toolName),
            )
        }
    }

    @Test
    fun `defaultLevelFor is null for non-gateable names`() {
        assertNull(AgentToolPermissions.defaultLevelFor("file_read"))
        assertNull(AgentToolPermissions.defaultLevelFor("totally_unknown"))
        assertNull(AgentToolPermissions.defaultLevelFor("calendar"))
    }

    @Test
    fun `info lookup covers the registry and rejects the rest`() {
        AgentToolPermissions.gateableTools.forEach { tool ->
            val found = AgentToolPermissions.info(tool.toolName)
            assertNotNull(found)
            assertEquals(tool.toolName, found!!.toolName)
        }
        assertNull(AgentToolPermissions.info("file_read"))
    }
}
