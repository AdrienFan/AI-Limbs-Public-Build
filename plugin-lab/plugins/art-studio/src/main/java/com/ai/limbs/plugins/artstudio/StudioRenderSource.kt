package com.ai.limbs.plugins.artstudio

import org.json.JSONObject

/** Snapshot and revision marker are captured together. Even an empty document has a marker. */
internal class StudioRenderSource(val snapshot:JSONObject?,val revisionMarker:String,
    val assets:StudioAssetLease) : AutoCloseable {
    override fun close()=assets.close()
}
