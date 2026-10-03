package com.ai.limbs.plugins.artstudio

import org.json.JSONObject

/** Native animation uses held cels, so only changes in the union of track keys need rendering. */
internal object ArtGifSchedule {
    data class Run(val frame: Int, val frames: Int, val delay: Int)
    fun runs(snapshot: JSONObject): List<Run> {
        val state = snapshot.getJSONObject("state")
        val settings = ArtAnimation.settings(state)
        val start = settings.getInt("start"); val end = settings.getInt("end"); val fps = settings.getInt("fps")
        require(start in 0..ArtAnimation.MAX_TIME && end in start..ArtAnimation.MAX_TIME && end - start < 600)
        val changes = sortedSetOf(start, end + 1)
        for (layer in ArtAnimation.layers(state)) ArtAnimation.keys(layer)?.let { keys ->
            for (index in 0 until keys.length()) {
                val frame = keys.getJSONObject(index).getInt("time")
                if (frame in (start + 1)..end) changes.add(frame)
            }
        }
        return changes.toList().zipWithNext().map { (frame, next) ->
            Run(frame, next - frame, ArtGifWriter.spanDelay(frame - start, next - frame, fps))
        }
    }
}
