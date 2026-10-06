package com.ai.limbs.extensions.systemenvironment.ubuntu

import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.provider.type.HiddenExecResult
import org.junit.Assert.*
import org.junit.Test

class UbuntuCapabilityOutcomeTest {
    @Test fun stoppedRuntimeIsNotAnArgumentFailureAndRequiresExplicitStart() {
        val result = UbuntuCapabilityOutcome.failure(UbuntuRuntimeUnavailable("STOPPED", "stopped"))
        assertEquals("UBUNTU_RUNTIME_NOT_RUNNING", result.getString("error_code"))
        assertEquals("NOT_STARTED", result.getString("execution_state"))
        assertEquals("plugin.ubuntu.start", result.getJSONObject("next_action").getJSONObject("capability").getString("name"))
        assertFalse(result.getBoolean("automatic_reexecution"))
    }

    @Test fun crashAndTimeoutPreserveUncertaintyWithoutCommandReplay() {
        for ((state, code) in listOf(HiddenExecResult.State.PROCESS_EXITED to "UBUNTU_PROCESS_EXITED",
            HiddenExecResult.State.TIMEOUT to "UBUNTU_COMMAND_TIMEOUT")) {
            val result = UbuntuCapabilityOutcome.hidden(HiddenExecResult("partial", -1, state, "failed"))
            assertEquals(code, result.getString("error_code"))
            assertEquals("UNKNOWN", result.getString("execution_state"))
            assertEquals("partial", result.getString("output"))
            assertFalse(result.getBoolean("automatic_reexecution"))
            assertEquals("plugin.ubuntu.status", result.getJSONObject("next_action").getJSONObject("capability").getString("name"))
        }
    }

    @Test fun nonzeroExitIsCompletedBusinessFailure() {
        val result = UbuntuCapabilityOutcome.hidden(HiddenExecResult("failed command", 7))
        assertFalse(result.getBoolean("success"))
        assertEquals("UBUNTU_COMMAND_EXIT_NONZERO", result.getString("error_code"))
        assertEquals("COMPLETED", result.getString("execution_state"))
        assertEquals(7, result.getInt("exit_code"))
    }

    @Test fun argumentsAndSuccessAreDistinct() {
        assertEquals("UBUNTU_INVALID_ARGUMENT", UbuntuCapabilityOutcome.failure(IllegalArgumentException("missing input")).getString("error_code"))
        val result = UbuntuCapabilityOutcome.hidden(HiddenExecResult("done", 0))
        assertTrue(result.getBoolean("success"))
        assertEquals("done", result.getString("output"))
        assertFalse(result.has("error_code"))
    }
}
