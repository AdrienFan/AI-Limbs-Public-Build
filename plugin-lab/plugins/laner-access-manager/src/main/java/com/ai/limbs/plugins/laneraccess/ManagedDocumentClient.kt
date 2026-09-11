package com.ai.limbs.plugins.laneraccess

import com.ai.limbs.plugin.runtime.InProcessPluginHost
import org.json.JSONObject

internal enum class ManagedDocumentKind(
    val primitiveId: String,
    val title: String,
    val minLines: Int,
    val editableField: String
) {
    CUSTOM_ACCESS_PROMPT(
        primitiveId = "host.custom_access_prompt@1",
        title = "自定义接入提示",
        minLines = 5,
        editableField = "content"
    ),
    WORK_MANUAL(
        primitiveId = "host.work_manual@1",
        title = "工作手册",
        minLines = 14,
        editableField = "editable_content"
    )
}

internal data class ManagedDocumentSnapshot(
    val id: String,
    val createdAtEpochMillis: Long,
    val sha256: String
)

internal data class ManagedDocumentState(
    val content: String,
    val snapshots: List<ManagedDocumentSnapshot>
)

internal class ManagedDocumentClient(
    private val host: InProcessPluginHost
) {
    suspend fun load(kind: ManagedDocumentKind): ManagedDocumentState {
        val readState = invoke(kind, "read")
        val historyState = invoke(kind, "snapshots")
        val snapshotsJson = historyState.optJSONArray("snapshots")
        val snapshots = buildList {
            if (snapshotsJson != null) {
                for (index in 0 until snapshotsJson.length()) {
                    val item = snapshotsJson.getJSONObject(index)
                    add(
                        ManagedDocumentSnapshot(
                            id = item.getString("id"),
                            createdAtEpochMillis = item.getLong("created_at_epoch_ms"),
                            sha256 = item.getString("sha256")
                        )
                    )
                }
            }
        }
        return ManagedDocumentState(
            content = readState.optString(kind.editableField),
            snapshots = snapshots
        )
    }

    suspend fun write(kind: ManagedDocumentKind, content: String): Boolean =
        invoke(
            kind,
            "write",
            JSONObject().put("content", content)
        ).optBoolean("changed", false)

    suspend fun restore(kind: ManagedDocumentKind, snapshotId: String): Boolean =
        invoke(
            kind,
            "restore",
            JSONObject().put("snapshot_id", snapshotId)
        ).optBoolean("changed", false)

    private suspend fun invoke(
        kind: ManagedDocumentKind,
        operation: String,
        payload: JSONObject = JSONObject()
    ): JSONObject {
        val request = JSONObject(payload.toString()).put("operation", operation)
        return JSONObject(
            host.invokeHostCapability(
                kind.primitiveId,
                request.toString()
            )
        )
    }
}
