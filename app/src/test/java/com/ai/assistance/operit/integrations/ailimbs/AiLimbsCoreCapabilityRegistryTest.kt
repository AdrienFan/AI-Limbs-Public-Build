package com.ai.assistance.operit.integrations.ailimbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsCoreCapabilityRegistryTest {
    @Test
    fun canonicalAndAliasesResolveToSameRegistration() {
        val registrations = AiLimbsCoreCapabilityRegistry.registrationSnapshot()
        assertTrue(registrations.isNotEmpty())

        registrations.forEach { registration ->
            val canonical = registration.catalogEntry.targetToolName
            assertSame(
                registration,
                AiLimbsCoreCapabilityRegistry.registrationForInvokeName(canonical)
            )
            registration.invokeAliases.forEach { alias ->
                assertSame(
                    registration,
                    AiLimbsCoreCapabilityRegistry.registrationForInvokeName(alias)
                )
            }
        }
    }

    @Test
    fun everyManagedDocumentHasOneReadAndWriteRegistration() {
        val registrations = AiLimbsCoreCapabilityRegistry.registrationSnapshot()

        AiLimbsDocumentId.entries.forEach { documentId ->
            val reads = registrations.filter {
                (it.route as? AiLimbsCoreRoute.ManagedDocumentRead)?.documentId == documentId
            }
            val writes = registrations.filter {
                (it.route as? AiLimbsCoreRoute.ManagedDocumentWrite)?.documentId == documentId
            }
            assertEquals(1, reads.size)
            val expectedWriteCount =
                if (documentId == AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT) 0 else 1
            assertEquals(expectedWriteCount, writes.size)

            val read = reads.single()
            assertEquals(
                read.catalogEntry.targetToolName,
                AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(documentId, write = false)
            )
            assertEquals(
                buildSet {
                    add(read.catalogEntry.targetToolName)
                    addAll(read.invokeAliases)
                },
                AiLimbsCoreCapabilityRegistry.managedDocumentInvokeNames(documentId, write = false)
            )

            writes.singleOrNull()?.let { write ->
                assertEquals(
                    write.catalogEntry.targetToolName,
                    AiLimbsCoreCapabilityRegistry.managedDocumentInvokeName(documentId, write = true)
                )
                assertEquals(
                    buildSet {
                        add(write.catalogEntry.targetToolName)
                        addAll(write.invokeAliases)
                    },
                    AiLimbsCoreCapabilityRegistry.managedDocumentInvokeNames(documentId, write = true)
                )
            }
        }
    }

    @Test
    fun availabilityPoliciesStayWithMatchingProvider() {
        AiLimbsCoreCapabilityRegistry.registrationSnapshot().forEach { registration ->
            when (registration.availabilityPolicy) {
                AiLimbsCoreAvailabilityPolicy.BRIDGE_RECONNECT ->
                    assertEquals(AiLimbsCoreProvider.BRIDGE, registration.provider)
                AiLimbsCoreAvailabilityPolicy.UBUNTU_STATUS,
                AiLimbsCoreAvailabilityPolicy.UBUNTU_START,
                AiLimbsCoreAvailabilityPolicy.UBUNTU_STOP,
                AiLimbsCoreAvailabilityPolicy.UBUNTU_IDLE_POLICY ->
                    assertEquals(AiLimbsCoreProvider.UBUNTU, registration.provider)
                AiLimbsCoreAvailabilityPolicy.DEFAULT -> Unit
            }
        }
    }

    @Test
    fun sourceLocatorsFollowProviderPolicy() {
        AiLimbsCoreCapabilityRegistry.registrationSnapshot().forEach { registration ->
            val locator = registration.catalogEntry.sourceLocator.orEmpty()
            when (registration.provider) {
                AiLimbsCoreProvider.BRIDGE -> {
                    assertEquals(
                        AiLimbsCoreCapabilityRegistry.BRIDGE_PROVIDER,
                        registration.catalogEntry.sourceName
                    )
                    assertTrue(locator.startsWith("ai-limbs://bridge/"))
                }
                AiLimbsCoreProvider.UBUNTU -> {
                    assertEquals(
                        AiLimbsCoreCapabilityRegistry.UBUNTU_PROVIDER,
                        registration.catalogEntry.sourceName
                    )
                    if (
                        registration.availabilityPolicy ==
                            AiLimbsCoreAvailabilityPolicy.UBUNTU_IDLE_POLICY
                    ) {
                        assertTrue(locator.startsWith("ubuntu://idle/"))
                    } else {
                        assertTrue(locator.startsWith("ubuntu://lifecycle/"))
                    }
                }
                AiLimbsCoreProvider.CORE -> Unit
            }
        }
    }

    @Test
    fun lanerChatNoReplyResolveIsRegistered() {
        val registration =
            AiLimbsCoreCapabilityRegistry.registrationForInvokeName("ai_limbs.chat.turn.resolve")
        assertTrue(registration != null)
        assertEquals(
            AiLimbsCoreRoute.LanerChat(AiLimbsLanerChatOperation.TURN_RESOLVE),
            registration?.route
        )
    }

    @Test
    fun immutableSystemPromptAndPolicyCapabilitiesAreRegisteredCorrectly() {
        assertEquals(
            null,
            AiLimbsCoreCapabilityRegistry.registrationForInvokeName(
                "ai_limbs.system_access_prompt.write"
            )
        )
        listOf(
            "ai_limbs.policy.describe",
            "ai_limbs.policy.session.reset",
            "ai_limbs.storage.search",
            "ai_limbs.storage.describe",
            "ai_limbs.storage.project.files"
        ).forEach { name ->
            assertTrue(
                "Expected registered capability: $name",
                AiLimbsCoreCapabilityRegistry.registrationForInvokeName(name) != null
            )
        }
    }

    @Test
    fun forwardedRoutesUseCatalogCanonicalNameAsSingleNameSource() {
        val forwarded =
            AiLimbsCoreCapabilityRegistry.registrationSnapshot()
                .filter { it.route == AiLimbsCoreRoute.ForwardHostTool }
        assertTrue(forwarded.isNotEmpty())

        forwarded.forEach { registration ->
            assertFalse(registration.catalogEntry.targetToolName.isBlank())
            assertSame(
                registration,
                AiLimbsCoreCapabilityRegistry.registrationForInvokeName(
                    registration.catalogEntry.targetToolName
                )
            )
        }
    }
}
