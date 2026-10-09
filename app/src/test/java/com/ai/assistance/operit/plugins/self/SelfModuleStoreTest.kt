package com.ai.assistance.operit.plugins.self

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SelfModuleStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val id = "4d091ec7-a9db-43e8-b80b-d06b5bd03e30"
    private fun store(name: String) = SelfModuleStore(File(temp.root, name))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun pack(version: String = "0.1.0", identity: String = id, schema: Int = 1, create: Boolean = false): File {
        val payload = JSONObject().put("entry", "blank").put("module_version", version).toString().toByteArray()
        val manifest = JSONObject().put("format", "AIL_SELF_V1").put("module_type", "self")
            .put("package_kind", "module").put("package_schema_version", 1).put("identity_id", identity)
            .put("module_version", version).put("state_schema_version", schema).put("compatible_state_schemas", JSONArray(listOf(schema)))
            .put("integrity", JSONObject().put("algorithm", "sha256").put("entries", JSONObject().put("program/blank.json", hash(payload))))
        if (create) manifest.put("identity_mode", "CREATE").put("identity_id", JSONObject.NULL)
        val file = File(temp.root, "${UUID.randomUUID()}.ails")
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry("self.json")); it.write(manifest.toString().toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry("program/blank.json")); it.write(payload); it.closeEntry()
        }
        return file
    }
    private fun expect(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: IllegalStateException) { assertEquals(code, error.message) }
    }
    private fun module(store: SelfModuleStore) = store.status().getJSONObject("module")
    private fun upgrade(file: File) = JSONObject().put("package_path", file.absolutePath)
    private fun phase(name: String) = JSONObject().put("phase", name)
    @Test fun oneTemplateCreatesIndependentDurableIdentitiesAcrossHosts() {
        val template = pack(create = true)
        val first = store("first"); val second = store("second")
        first.install(template); second.install(template)
        val id = module(first).getString("identity_id")
        assertNotEquals(id, module(second).getString("identity_id"))
        assertEquals(4, UUID.fromString(id).version())
        first.autonomous("upgrade", upgrade(pack("0.1.1", create = true)))
        first.autonomous("rollback", JSONObject().put("target_version", "0.1.0"))
        assertEquals(id, module(store("first")).getString("identity_id"))
    }
    private fun acknowledgment(info: JSONObject) = JSONObject().put("protocol", "AIL_SELF_REGISTRATION_V1").put("received", true)
        .put("identity_id", info.getString("identity_id")).put("migration_id", info.getString("migration_id"))
        .put("package_sha256", info.getString("package_sha256")).put("receipt_id", "cloud-fixture-receipt")
    @Test fun retirementRequiresSavedPackageAndMatchingAcknowledgment() {
        val source = store("source"); val target = store("target"); source.install(pack())
        source.autonomous("migrate", phase("export").put("target_device_id", target.status().getString("device_id")))
        val info = source.migrationRegistration()
        expect("SELF_EXPORT_SAVE_REQUIRED") { source.completeRegistration(acknowledgment(info)) }
        source.markMigrationSaved(info.getString("migration_id"), info.getString("package_sha256"), "content://fixture/chosen.ails")
        expect("SELF_UPLOAD_RECEIPT_MISMATCH") { source.completeRegistration(acknowledgment(info).put("package_sha256", "b".repeat(64))) }
        expect("SELF_UPLOAD_NOT_CONFIRMED") { source.completeRegistration(acknowledgment(info).put("received", false)) }
        assertEquals("SEALED", module(source).getString("lifecycle_state"))
        assertTrue(source.migrationPackage().exists())
    }
    @Test fun confirmedUploadReleasesSlotButRetainsDataAndTransferCanFinish() {
        val source = store("source"); val target = store("target"); source.install(pack(create = true))
        val sourceIdentity = module(source).getString("identity_id")
        val marker = File(temp.root, "source/data/${module(source).getString("data_id")}/marker.txt").apply { writeText("only-copy") }
        val exported = source.autonomous("migrate", phase("export").put("target_device_id", target.status().getString("device_id")))
        val info = source.migrationRegistration()
        source.markMigrationSaved(info.getString("migration_id"), info.getString("package_sha256"), "content://fixture/chosen.ails")
        source.completeRegistration(acknowledgment(info))
        assertTrue(store("source").status().isNull("module")); assertTrue(marker.exists())
        assertEquals(1, source.status().getJSONArray("outgoing_migrations").length())
        val prepared = target.autonomous("migrate", phase("prepare").put("package_path", exported.getString("package_path")))
        val committed = store("source").autonomous("migrate", phase("commit").put("migration_id", info.getString("migration_id")).put("receipt", prepared.getJSONObject("receipt")))
        target.autonomous("migrate", phase("activate").put("release", committed.getJSONObject("release")))
        assertEquals(sourceIdentity, module(target).getString("identity_id"))
        assertEquals("only-copy", File(temp.root, "target/data/${module(target).getString("data_id")}/marker.txt").readText())
    }
    @Test fun departedIdentityCannotBeReinstalledAndSameTemplateCreatesANewIndividual() {
        val source = store("source"); val target = store("target"); val template = pack(create = true)
        source.install(template)
        val previous = module(source).getString("identity_id")
        source.autonomous("migrate", phase("export").put("target_device_id", target.status().getString("device_id")))
        val info = source.migrationRegistration()
        source.markMigrationSaved(info.getString("migration_id"), info.getString("package_sha256"), "content://fixture/chosen.ails")
        source.completeRegistration(acknowledgment(info))
        expect("SELF_IDENTITY_ALREADY_DEPARTED") { source.install(pack(identity = previous)) }
        source.install(template)
        assertNotEquals(previous, module(source).getString("identity_id"))
        expect("SELF_SLOT_OCCUPIED") { source.autonomous("migrate", phase("cancel").put("migration_id", info.getString("migration_id"))) }
    }
    @Test fun uncommittedRetiredTransferCanRestoreOnlyIntoAnEmptySlot() {
        val source = store("source"); val target = store("target"); source.install(pack())
        source.autonomous("migrate", phase("export").put("target_device_id", target.status().getString("device_id")))
        val info = source.migrationRegistration()
        source.markMigrationSaved(info.getString("migration_id"), info.getString("package_sha256"), "content://fixture/chosen.ails")
        source.completeRegistration(acknowledgment(info))
        source.autonomous("migrate", phase("cancel").put("migration_id", info.getString("migration_id")))
        assertEquals(id, module(source).getString("identity_id"))
        assertEquals("ACTIVE", module(source).getString("lifecycle_state"))
        assertEquals(0, source.status().getJSONArray("outgoing_migrations").length())
    }
    @Test fun installAndSecondIdentityAdmission() {
        val host = store("host"); host.install(pack())
        assertEquals(id, module(host).getString("identity_id"))
        assertEquals("ACTIVE", module(host).getString("lifecycle_state"))
        expect("SELF_SLOT_OCCUPIED") { host.install(pack(identity = UUID.randomUUID().toString())) }
    }
    @Test fun renamedOrdinaryArchiveCannotInstall() {
        val file = File(temp.root, "ordinary.ails")
        ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("plugin.json")); it.write("{}".toByteArray()); it.closeEntry() }
        expect("SELF_MANIFEST_MISSING") { store("host").install(file) }
    }
    @Test fun upgradeRollbackPreserveIdentityAndOpaqueData() {
        val host = store("host"); host.install(pack())
        val dataId = module(host).getString("data_id")
        val marker = File(temp.root, "host/data/$dataId/marker.txt"); marker.writeText("opaque模拟数据")
        host.autonomous("upgrade", upgrade(pack("0.1.1")))
        host.autonomous("rollback", JSONObject().put("target_version", "0.1.0"))
        assertEquals(id, module(host).getString("identity_id")); assertEquals(dataId, module(host).getString("data_id"))
        assertEquals("opaque模拟数据", marker.readText())
    }
    @Test fun approvalIsOneUseAndRejectionDoesNotExecute() {
        val host = store("host"); host.install(pack())
        val first = host.request("upgrade", upgrade(pack("0.1.1"))).getString("request_id")
        host.review(first, false)
        assertEquals("0.1.0", module(host).getString("module_version"))
        expect("SELF_REQUEST_TERMINAL") { host.review(first, true) }
        val second = host.request("upgrade", upgrade(pack("0.1.1"))).getString("request_id")
        host.review(second, true)
        assertEquals("0.1.1", module(host).getString("module_version"))
        expect("SELF_REQUEST_TERMINAL") { host.review(second, true) }
    }
    @Test fun attestedAiActsWithoutHumanApplicationAndOrdinaryUninstallIsRejected() {
        val context = org.mockito.Mockito.mock(android.content.Context::class.java)
        org.mockito.Mockito.`when`(context.applicationContext).thenReturn(context)
        org.mockito.Mockito.`when`(context.filesDir).thenReturn(temp.root)
        val session = com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionSession(
            com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionTransport.EXTERNAL_BRIDGE, "bridge:test"
        ).also { it.attestAiIngress() }
        assertTrue(SelfModuleService.ai(context, session, "install", upgrade(pack())).getBoolean("success"))
        assertTrue(SelfModuleService.ai(context, session, "upgrade", upgrade(pack("0.1.1"))).getBoolean("success"))
        val status = SelfModuleService.ai(context, session, "status", JSONObject())
        assertEquals(0, status.getJSONArray("requests").length())
        assertEquals(id, status.getJSONObject("module").getString("identity_id"))
        expect("SELF_ORDINARY_LIFECYCLE_FORBIDDEN") { SelfModuleService.rejectOrdinaryOperation(context, id) }
        SelfModuleService.rejectOrdinaryOperation(context, "plugin.test.ordinary")
        assertEquals("0.1.1", status.getJSONObject("module").getString("module_version"))
    }
    @Test fun disconnectedApprovalRemainsPendingAndCanCancel() {
        val host = store("host"); host.install(pack())
        val request = host.request("rollback", JSONObject().put("target_version", "0.1.0"))
        val pending = host.status().getJSONArray("requests").getJSONObject(0)
        assertEquals("PENDING", pending.getString("status"))
        host.cancelRequest(request.getString("request_id"))
        expect("SELF_REQUEST_TERMINAL") { host.review(request.getString("request_id"), true) }
    }
    @Test fun staleApprovalCannotUpgradeChangedState() {
        val host = store("host"); host.install(pack())
        val request = host.request("upgrade", upgrade(pack("0.1.2")))
        host.autonomous("upgrade", upgrade(pack("0.1.1")))
        expect("SELF_REQUEST_STALE") { host.review(request.getString("request_id"), true) }
        assertEquals("0.1.1", module(host).getString("module_version"))
    }
    @Test fun pendingRequestPinsOriginalPackageBytes() {
        val host = store("host"); host.install(pack()); val file = pack("0.1.1")
        val request = host.request("upgrade", upgrade(file)); file.writeText("changed after request")
        host.review(request.getString("request_id"), true)
        assertEquals("0.1.1", module(host).getString("module_version"))
    }
    @Test fun incompatibleOrForeignVersionPreservesCurrentModule() {
        val host = store("host"); host.install(pack())
        expect("SELF_SCHEMA_INCOMPATIBLE") { host.autonomous("upgrade", upgrade(pack("0.1.1", schema = 2))) }
        expect("SELF_IDENTITY_MISMATCH") { host.autonomous("upgrade", upgrade(pack("0.1.1", UUID.randomUUID().toString()))) }
        expect("SELF_HISTORY_UNAVAILABLE") { host.autonomous("rollback", JSONObject().put("target_version", "0.0.9")) }
        assertEquals("0.1.0", module(host).getString("module_version"))
    }
    @Test fun migrationTransfersOpaqueDataAndKeepsSourceSealed() {
        val source = store("source"); val target = store("target"); source.install(pack())
        val marker = File(temp.root, "source/data/${module(source).getString("data_id")}/marker.txt"); marker.writeText("模拟迁移数据")
        source.autonomous("upgrade", upgrade(pack("0.1.1")))
        val exported = source.autonomous("migrate", phase("export").put("target_device_id", target.status().getString("device_id")))
        assertEquals("SEALED", module(source).getString("lifecycle_state"))
        val prepared = target.autonomous("migrate", phase("prepare").put("package_path", exported.getString("package_path")))
        assertEquals("SEALED", module(target).getString("lifecycle_state"))
        val committed = source.autonomous("migrate", phase("commit").put("receipt", prepared.getJSONObject("receipt")))
        expect("SELF_COMMITTED_MIGRATION_CANNOT_CANCEL") { source.autonomous("migrate", phase("cancel")) }
        target.autonomous("migrate", phase("activate").put("release", committed.getJSONObject("release")))
        assertEquals("ACTIVE", module(target).getString("lifecycle_state")); assertEquals(id, module(target).getString("identity_id"))
        assertEquals("SEALED", module(source).getString("lifecycle_state"))
        assertEquals("模拟迁移数据", File(temp.root, "target/data/${module(target).getString("data_id")}/marker.txt").readText())
        assertTrue(marker.exists())
        target.autonomous("rollback", JSONObject().put("target_version", "0.1.0"))
        assertEquals("0.1.0", module(target).getString("module_version"))
        expect("SELF_MIGRATION_NOT_INBOUND") { target.autonomous("migrate", phase("activate").put("release", committed.getJSONObject("release"))) }
    }
    @Test fun migrationCanAbortWithoutDeletingUniqueData() {
        val source = store("source"); val target = store("target"); source.install(pack())
        val exported = source.autonomous("migrate", phase("export").put("target_device_id", target.status().getString("device_id")))
        target.autonomous("migrate", phase("prepare").put("package_path", exported.getString("package_path")))
        val aborted = source.autonomous("migrate", phase("cancel"))
        target.autonomous("migrate", phase("discard").put("abort", aborted.getJSONObject("abort")))
        assertEquals("ACTIVE", module(source).getString("lifecycle_state")); assertTrue(target.status().isNull("module"))
        assertTrue(File(temp.root, "source/data").walkTopDown().any { it.name == "state.json" })
    }
    @Test fun wrongTargetAndOccupiedTargetCannotPrepare() {
        val source = store("source"); val target = store("target"); val wrong = store("wrong"); source.install(pack())
        val exported = source.autonomous("migrate", phase("export").put("target_device_id", target.status().getString("device_id")))
        expect("SELF_MIGRATION_TARGET_MISMATCH") { wrong.autonomous("migrate", phase("prepare").put("package_path", exported.getString("package_path"))) }
        target.install(pack(identity = UUID.randomUUID().toString()))
        expect("SELF_SLOT_OCCUPIED") { target.autonomous("migrate", phase("prepare").put("package_path", exported.getString("package_path"))) }
        assertEquals("SEALED", module(source).getString("lifecycle_state"))
    }
    @Test fun forgedReceiptCannotTransferRunningRight() {
        val source = store("source"); val target = store("target"); source.install(pack())
        val exported = source.autonomous("migrate", phase("export").put("target_device_id", target.status().getString("device_id")))
        val prepared = target.autonomous("migrate", phase("prepare").put("package_path", exported.getString("package_path")))
        val forged = JSONObject(prepared.getJSONObject("receipt").toString()).put("body", "{}")
        expect("SELF_RECEIPT_SIGNATURE_INVALID") { source.autonomous("migrate", phase("commit").put("receipt", forged)) }
        assertFalse(module(source).getBoolean("transfer_committed"))
    }
    @Test fun concurrentSeparateManagersShareOneSlot() {
        val first = store("host"); val second = store("host"); val a = pack(); val b = pack(identity = UUID.randomUUID().toString())
        val latch = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(first to a, second to b).map { (host, file) -> pool.submit<Boolean> { latch.await(); try { host.install(file); true } catch (_: IllegalStateException) { false } } }
            latch.countDown(); assertEquals(1, tasks.count { it.get() })
        } finally { pool.shutdownNow() }
    }
    @Test fun crashAfterCommitReconcilesRequestWithoutReplay() {
        val host = store("host"); host.install(pack())
        val request = host.request("upgrade", upgrade(pack("0.1.1"))); val requestId = request.getString("request_id")
        host.review(requestId, true)
        val file = File(temp.root, "host/requests/$requestId.json")
        val disk = JSONObject(file.readText()).put("status", "EXECUTING"); file.writeText(disk.toString())
        val restored = store("host").status().getJSONArray("requests").getJSONObject(0)
        assertEquals("COMPLETED_RECOVERED", restored.getString("status"))
        expect("SELF_REQUEST_TERMINAL") { host.review(requestId, true) }
    }
    @Test fun crashBeforeCommitLeavesOriginalDataAndRequiresRecovery() {
        val host = store("host"); host.install(pack())
        val request = host.request("upgrade", upgrade(pack("0.1.1"))); val requestId = request.getString("request_id")
        File(temp.root, "host/requests/$requestId.json").writeText(JSONObject(request.toString()).put("status", "EXECUTING").toString())
        assertEquals("RECOVERY_REQUIRED", store("host").status().getJSONArray("requests").getJSONObject(0).getString("status"))
        assertEquals("0.1.0", module(host).getString("module_version"))
    }
    @Test fun tamperedPayloadAndTraversalAreRejected() {
        val file = File(temp.root, "tampered.ails"); val valid = pack()
        java.util.zip.ZipFile(valid).use { zip -> ZipOutputStream(file.outputStream()).use { output ->
            zip.entries().asSequence().forEach { entry -> output.putNextEntry(ZipEntry(entry.name)); output.write(if (entry.name == "program/blank.json") "{}".toByteArray() else zip.getInputStream(entry).readBytes()); output.closeEntry() }
        } }
        expect("SELF_INTEGRITY_FAILED") { store("host").install(file) }
        val unsafe = File(temp.root, "unsafe.ails")
        ZipOutputStream(unsafe.outputStream()).use { it.putNextEntry(ZipEntry("../escape")); it.write(byteArrayOf(1)); it.closeEntry() }
        expect("SELF_ARCHIVE_ENTRY_INVALID") { store("host").install(unsafe) }
        assertFalse(File(temp.root, "escape").exists())
    }

    private fun bundle(host: SelfModuleStore, mode: String, operations: List<String>, seconds: Long = 7200): JSONArray {
        val items = JSONArray(operations.map { JSONObject().put("operation", it).put("parameters", JSONObject()) })
        return host.requestBundle(JSONObject().put("items", items).put("mode", mode).put("duration_seconds", seconds).put("reason", "生命周期测试申请"))
            .getJSONArray("requests")
    }
    private fun effective(host: SelfModuleStore) = host.status().getJSONObject("authorizations").getJSONArray("effective_grants")
    @Test fun multiSelectRequestsAreIndependentlyApprovedShortenedOrRejected() {
        var now = 1000000L
        val host = SelfModuleStore(File(temp.root, "host")) { now }; host.install(pack())
        val items = bundle(host, "LONG", listOf("upgrade", "migrate", "rollback"))
        assertEquals(3, items.length()); assertEquals(0, effective(host).length())
        now += 5000
        host.review(items.getJSONObject(0).getString("request_id"), true, JSONObject().put("mode", "LONG").put("reason", "允许升级"))
        host.review(items.getJSONObject(1).getString("request_id"), false, JSONObject().put("reason", "本次不迁移"))
        host.review(items.getJSONObject(2).getString("request_id"), true, JSONObject().put("mode", "TIMED").put("duration_seconds", 60))
        val grants = effective(host)
        assertEquals(2, grants.length())
        val timed = (0 until grants.length()).map { grants.getJSONObject(it) }.first { it.getString("operation") == "rollback" }
        assertEquals(now + 60000, timed.getLong("expires_at_ms"))
        assertEquals("0.1.0", module(host).getString("module_version"))
        expect("SELF_REQUEST_TERMINAL") { host.review(items.getJSONObject(0).getString("request_id"), true) }
    }
    @Test fun timedGrantsCannotBeExpandedAndExpireAtExecutionEvenWithoutUiRefresh() {
        var now = 100000L
        val host = SelfModuleStore(File(temp.root, "host")) { now }; host.install(pack())
        val request = bundle(host, "TIMED", listOf("upgrade"), 60).getJSONObject(0).getString("request_id")
        expect("SELF_APPROVAL_SCOPE_EXCEEDED") { host.review(request, true, JSONObject().put("mode", "LONG")) }
        expect("SELF_APPROVAL_SCOPE_EXCEEDED") { host.review(request, true, JSONObject().put("mode", "TIMED").put("duration_seconds", 61)) }
        host.review(request, true, JSONObject().put("mode", "TIMED").put("duration_seconds", 30))
        now += 30000
        expect("SELF_HUMAN_AUTHORIZATION_REQUIRED") { host.humanExecute("upgrade", upgrade(pack("0.1.1"))) }
        assertEquals("0.1.0", module(host).getString("module_version"))
        now -= 60000 // Once expired, a backward wall clock must never resurrect authority.
        assertEquals(0, effective(host).length())
    }
    @Test fun ongoingGrantAllowsLaterHumanOperationsButNeverExecutesOldPendingRequests() {
        val host = store("host"); host.install(pack())
        val old = host.request("upgrade", upgrade(pack("0.1.2"))).getString("request_id")
        val grant = bundle(host, "LONG", listOf("upgrade")).getJSONObject(0).getString("request_id")
        host.review(grant, true)
        assertEquals("0.1.0", module(host).getString("module_version"))
        host.humanExecute("upgrade", upgrade(pack("0.1.1")))
        assertEquals("0.1.1", module(host).getString("module_version"))
        val pending = host.status().getJSONArray("requests")
        assertEquals("PENDING", (0 until pending.length()).map { pending.getJSONObject(it) }.first { it.getString("request_id") == old }.getString("status"))
    }
    @Test fun revokedGrantsSurviveRestartAndProgramRollbackCannotRestoreThem() {
        val host = store("host"); host.install(pack())
        val items = bundle(host, "LONG", listOf("upgrade", "rollback"))
        for (i in 0 until items.length()) host.review(items.getJSONObject(i).getString("request_id"), true)
        host.humanExecute("upgrade", upgrade(pack("0.1.1")))
        host.revoke("upgrade", "结束长期授权")
        host.humanExecute("rollback", JSONObject().put("target_version", "0.1.0"))
        val restarted = store("host")
        assertEquals(1, effective(restarted).length())
        expect("SELF_HUMAN_AUTHORIZATION_REQUIRED") { restarted.humanExecute("upgrade", upgrade(pack("0.1.2"))) }
        assertEquals("0.1.0", module(restarted).getString("module_version"))
    }
    @Test fun migrationKeepsTransactionAuthorityAfterRevocationAndDestinationHasNoOngoingGrant() {
        val source = store("source"); val target = store("target"); source.install(pack())
        val approval = bundle(source, "LONG", listOf("migrate")).getJSONObject(0).getString("request_id")
        source.review(approval, true)
        val exported = source.humanExecute("migrate", phase("export").put("target_device_id", target.status().getString("device_id"))).getJSONObject("result")
        source.revoke("migrate", "禁止后续迁移")
        val incoming = target.request("migrate", phase("prepare").put("package_path", exported.getString("package_path")))
        val prepared = target.review(incoming.getString("request_id"), true).getJSONObject("result")
        val release = source.humanExecute("migrate", phase("commit").put("receipt", prepared.getJSONObject("receipt"))).getJSONObject("result")
        target.humanExecute("migrate", phase("activate").put("release", release.getJSONObject("release")))
        assertEquals("SEALED", module(source).getString("lifecycle_state"))
        assertEquals("ACTIVE", module(target).getString("lifecycle_state"))
        assertEquals(id, module(target).getString("identity_id"))
        assertEquals(0, effective(target).length())
        expect("SELF_HUMAN_AUTHORIZATION_REQUIRED") { target.humanExecute("migrate", phase("export").put("target_device_id", source.status().getString("device_id"))) }
    }
    @Test fun invalidBundleCannotExposePartialApplications() {
        val host = store("host"); host.install(pack())
        expect("SELF_APPLICATION_ITEMS_INVALID") { bundle(host, "LONG", listOf("upgrade", "upgrade")) }
        assertEquals(0, host.status().getJSONArray("requests").length())
        assertEquals(0, effective(host).length())
    }
    @Test fun ongoingAuthorizationNeverSkipsPackageIdentityOrRollbackSchemaChecks() {
        val host = store("host"); host.install(pack())
        val request = bundle(host, "LONG", listOf("upgrade")).getJSONObject(0).getString("request_id")
        host.review(request, true)
        expect("SELF_IDENTITY_MISMATCH") { host.humanExecute("upgrade", upgrade(pack("0.1.1", UUID.randomUUID().toString()))) }
        expect("SELF_SCHEMA_INCOMPATIBLE") { host.humanExecute("upgrade", upgrade(pack("0.1.1", schema = 2))) }
        assertEquals("0.1.0", module(host).getString("module_version"))
    }
    private fun page(host: SelfModuleStore): String = String(java.util.Base64.getDecoder().decode(
        host.resources(JSONObject().put("paths", JSONArray(listOf("resources/index.html")))).getJSONObject("resources").getString("resources/index.html")), Charsets.UTF_8)
    private fun uiPack(version: String, page: String, digestOverride: String? = null): File {
        val program = JSONObject().put("entry", "blank").put("module_version", version).toString().toByteArray()
        val files = linkedMapOf("program/blank.json" to program, "resources/index.html" to page.toByteArray(), "resources/summary.html" to "<html><head></head><body>summary</body></html>".toByteArray())
        files["resources/presentation.json"] = JSONObject().put("api_version", 1).put("runtime", "html").put("entry", "resources/index.html").put("summary_entry", "resources/summary.html").toString().toByteArray()
        val checks = JSONObject(); files.forEach { (name, bytes) -> checks.put(name, if (name == "resources/index.html" && digestOverride != null) digestOverride else hash(bytes)) }
        val manifest = JSONObject().put("format", "AIL_SELF_V1").put("module_type", "self").put("package_kind", "module")
            .put("package_schema_version", 1).put("identity_id", id).put("module_version", version)
            .put("state_schema_version", 1).put("compatible_state_schemas", JSONArray(listOf(1)))
            .put("integrity", JSONObject().put("algorithm", "sha256").put("entries", checks))
        if (create) manifest.put("identity_mode", "CREATE").put("identity_id", JSONObject.NULL)
        val file = File(temp.root, "${UUID.randomUUID()}.ails")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("self.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return file
    }
    @Test fun packagedUiFollowsProgramUpgradeRollbackAndPreservesOpaqueData() {
        val host = store("ui-host"); host.install(pack("0.1.1"))
        assertFalse(host.resources().getBoolean("available"))
        val dataId = module(host).getString("data_id")
        val marker = File(temp.root, "ui-host/data/$dataId/marker.txt"); marker.writeText("keep")
        host.autonomous("upgrade", upgrade(uiPack("0.1.2", "<html><head></head><body>first UI</body></html>")))
        assertTrue(page(host).contains("first UI"))
        host.autonomous("upgrade", upgrade(uiPack("0.1.3", "<html><head></head><body>second UI</body></html>")))
        assertTrue(page(host).contains("second UI"))
        host.autonomous("rollback", JSONObject().put("target_version", "0.1.2"))
        assertTrue(page(host).contains("first UI"))
        host.autonomous("rollback", JSONObject().put("target_version", "0.1.1"))
        assertFalse(host.resources().getBoolean("available"))
        assertEquals(dataId, module(host).getString("data_id")); assertEquals("keep", marker.readText())
    }
    @Test fun kernelReadsResourcesAsOpaqueBytesWithoutUiInterpretation() {
        val host = store("opaque-host")
        val payload = "not HTML\u0000opaque"
        host.install(uiPack("0.1.2", payload))
        assertEquals(payload, page(host))
        expect("SELF_RESOURCE_MISSING") { host.resources(JSONObject().put("paths", JSONArray(listOf("resources/missing")))) }
    }
    @Test fun corruptUiAdmissionDoesNotReplaceTheWorkingProgram() {
        val host = store("host"); host.install(pack("0.1.1"))
        expect("SELF_INTEGRITY_FAILED") { host.autonomous("upgrade", upgrade(uiPack("0.1.2", "changed", "0".repeat(64)))) }
        assertEquals("0.1.1", module(host).getString("module_version"))
    }
    @Test fun staleUiCannotCreateRequestsOrConsumeOngoingGrantsAfterVersionSwitch() {
        val host = store("host"); host.install(uiPack("0.1.2", "<html><head></head><body>UI</body></html>"))
        val binding = host.resources().getJSONObject("binding")
        val approval = bundle(host, "LONG", listOf("upgrade", "rollback"))
        for (i in 0 until approval.length()) host.review(approval.getJSONObject(i).getString("request_id"), true)
        host.humanExecute("upgrade", upgrade(uiPack("0.1.3", "<html><head></head><body>new UI</body></html>")), binding)
        val count = host.status().getJSONArray("requests").length()
        expect("SELF_PROGRAM_BINDING_STALE") { host.humanExecute("rollback", JSONObject().put("target_version", "0.1.2"), binding) }
        expect("SELF_PROGRAM_BINDING_STALE") { host.request("rollback", JSONObject().put("target_version", "0.1.2"), binding) }
        expect("SELF_PROGRAM_BINDING_STALE") { host.requestBundle(JSONObject().put("program_binding", binding).put("mode", "LONG").put("reason", "stale").put("items", JSONArray().put(JSONObject().put("operation", "rollback").put("parameters", JSONObject())))) }
        assertEquals(count, host.status().getJSONArray("requests").length())
        assertEquals("0.1.3", module(host).getString("module_version"))
    }
    @Test fun migratedUiRetainsContentButUsesTargetLocalBindingAndNoOngoingGrants() {
        val source = store("source"); val target = store("target")
        source.install(uiPack("0.1.2", "<html><head></head><body>portable UI</body></html>"))
        val binding = source.resources().getJSONObject("binding")
        val exported = source.autonomous("migrate", phase("export").put("target_device_id", target.status().getString("device_id")))
        val prepared = target.autonomous("migrate", phase("prepare").put("package_path", exported.getString("package_path")))
        assertTrue(page(target).contains("portable UI"))
        val release = source.autonomous("migrate", phase("commit").put("receipt", prepared.getJSONObject("receipt")))
        target.autonomous("migrate", phase("activate").put("release", release.getJSONObject("release")))
        assertNotEquals(binding.getString("data_id"), target.resources().getJSONObject("binding").getString("data_id"))
        expect("SELF_PROGRAM_BINDING_STALE") { target.request("rollback", JSONObject().put("target_version", "0.1.1"), binding) }
        assertEquals(0, effective(target).length())
    }

}
