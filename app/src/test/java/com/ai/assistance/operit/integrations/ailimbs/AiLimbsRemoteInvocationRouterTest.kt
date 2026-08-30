package com.ai.assistance.operit.integrations.ailimbs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AiLimbsRemoteInvocationRouterTest {
    @Test
    fun coreCapabilityEntersDispatcherDirectly() {
        val args = JSONObject().put("query", "Ubuntu")
        val invocation =
            routeRemoteInvocation(
                requestedTool = "capability.search",
                args = args,
                isCoreCapability = true,
                isResolvedHostCapability = false,
                hostToolExecutor = "ai_limbs.host_tool.execute"
            )

        assertEquals("capability.search", invocation.tool)
        assertSame(args, invocation.args)
    }

    @Test
    fun resolvedToolPkgCapabilityUsesSharedHostExecutor() {
        val args = JSONObject().put("path", "/tmp/example.txt")
        val invocation =
            routeRemoteInvocation(
                requestedTool = "example_pkg:read",
                args = args,
                isCoreCapability = false,
                isResolvedHostCapability = true,
                hostToolExecutor = "ai_limbs.host_tool.execute"
            )

        assertEquals("ai_limbs.host_tool.execute", invocation.tool)
        assertEquals("example_pkg:read", invocation.args.getString("name"))
        assertSame(args, invocation.args.getJSONObject("parameters"))
    }

    @Test
    fun unknownToolStillEntersDispatcherForCanonicalError() {
        val args = JSONObject()
        val invocation =
            routeRemoteInvocation(
                requestedTool = "unknown.tool",
                args = args,
                isCoreCapability = false,
                isResolvedHostCapability = false,
                hostToolExecutor = "ai_limbs.host_tool.execute"
            )

        assertEquals("unknown.tool", invocation.tool)
        assertSame(args, invocation.args)
    }
}
