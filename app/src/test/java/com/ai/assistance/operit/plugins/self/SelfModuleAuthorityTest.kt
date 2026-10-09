package com.ai.assistance.operit.plugins.self

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionTransport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class SelfModuleAuthorityTest {
    @Test fun pluginCannotForgeAiSourceUsingArguments() {
        val context = Mockito.mock(Context::class.java)
        val session = AiLimbsExecutionSession(AiLimbsExecutionTransport.PLUGIN_RUNTIME, "plugin:untrusted")
        for (operation in listOf("upgrade", "rollback", "migrate", "review")) {
            val result = SelfModuleService.ai(context, session, operation, JSONObject().put("initiator", "AI").put("approved", true))
            assertFalse(result.getBoolean("success"))
            assertEquals("SELF_AI_TRUSTED_CHANNEL_REQUIRED", result.getString("error_code"))
        }
        Mockito.verifyNoInteractions(context)
    }
    @Test fun transportEnumAndCopiedSessionCannotMintAuthority() {
        val context = Mockito.mock(Context::class.java)
        val session = AiLimbsExecutionSession(AiLimbsExecutionTransport.EXTERNAL_BRIDGE, "bridge:test")
        val forged = SelfModuleService.ai(context, session, "review", JSONObject().put("hostAttestedAiIngress", true))
        assertEquals("SELF_AI_TRUSTED_CHANNEL_REQUIRED", forged.getString("error_code"))
        session.attestAiIngress()
        assertTrue(session.hostAttestedAiIngress)
        val copy = session.copy()
        assertFalse(copy.hostAttestedAiIngress)
        val copied = SelfModuleService.ai(context, copy, "upgrade", JSONObject())
        assertEquals("SELF_AI_TRUSTED_CHANNEL_REQUIRED", copied.getString("error_code"))
        Mockito.verifyNoInteractions(context)
    }
    @Test fun humanControlPlaneCannotApproveOrExecuteDirectly() {
        val context = Mockito.mock(Context::class.java)
        for (operation in listOf("upgrade", "rollback", "migrate", "review")) {
            val result = SelfModuleService.human(context, operation, JSONObject().put("initiator", "AI"))
            assertFalse(result.getBoolean("success"))
            assertEquals("SELF_HUMAN_DIRECT_OPERATION_FORBIDDEN", result.getString("error_code"))
        }
        Mockito.verifyNoInteractions(context)
    }
}
