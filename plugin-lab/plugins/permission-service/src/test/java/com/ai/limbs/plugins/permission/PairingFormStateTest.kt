package com.ai.limbs.plugins.permission

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PairingFormStateTest {
    @Test fun codeIsConsumedBeforePairingAndSuccessOpensConnectionStep() = runBlocking {
        val form = PairingFormState()
        form.pairPort("37123")
        form.pairCode("123456")
        form.pair { port, code ->
            assertEquals(37123, port)
            assertEquals("123456", code)
            assertEquals("", form.draft.value.pairCode)
            assertTrue(form.draft.value.working)
        }
        assertEquals(PairingStep.CONNECT, form.draft.value.step)
        assertFalse(form.draft.value.working)
        assertFalse(form.draft.value.failed)
    }

    @Test fun pairingFailureKeepsNoCodeAndDoesNotAdvanceTheStep() = runBlocking {
        val form = PairingFormState()
        form.pairPort("37123")
        form.pairCode("123456")
        try {
            form.pair { _, _ -> throw IllegalStateException("Expired code 123456") }
            fail("Pairing failure must be propagated")
        } catch (expected: IllegalStateException) { }
        assertEquals("", form.draft.value.pairCode)
        assertEquals(PairingStep.PAIR, form.draft.value.step)
        assertFalse(form.draft.value.working)
        assertTrue(form.draft.value.failed)
        assertFalse(form.draft.value.feedback.contains("123456"))
    }

    @Test fun closingSurfaceClearsCodeButDoesNotCancelAnInFlightActionOrAllowDuplicates() = runBlocking {
        val form = PairingFormState()
        form.pairPort("37123")
        form.pairCode("123456")
        form.connectPort("39876")
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val task = launch { form.pair { _, _ -> entered.complete(Unit); finish.await() } }
        entered.await()
        form.clearCode()
        assertTrue(form.draft.value.working)
        var calls = 0
        try { form.pair { _, _ -> calls++ }; fail("Concurrent submit must fail") }
        catch (expected: IllegalStateException) { }
        assertEquals(0, calls)
        finish.complete(Unit)
        task.join()
        assertEquals("37123", form.draft.value.pairPort)
        assertEquals("39876", form.draft.value.connectPort)
        assertEquals(PairingStep.CONNECT, form.draft.value.step)
    }

    @Test fun invalidPortsCannotReachPairingOrStartup() = runBlocking {
        val form = PairingFormState()
        form.pairPort("70000")
        form.pairCode("123456")
        form.connectPort("0")
        assertFalse(form.draft.value.canPair)
        assertFalse(form.draft.value.canStart)
        var calls = 0
        try { form.pair { _, _ -> calls++ }; fail("Invalid pairing port must fail") }
        catch (expected: IllegalArgumentException) { }
        try { form.start { calls++ }; fail("Invalid connection port must fail") }
        catch (expected: IllegalArgumentException) { }
        assertEquals(0, calls)
    }
}
