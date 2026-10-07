package com.ai.limbs.plugins.visualmanager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FrameCoordinatesTest {
    private fun frame(width: Int, height: Int, previewWidth: Int, previewHeight: Int, available: Boolean = true) =
        JSONObject().put("width", previewWidth).put("height", previewHeight).put("geometry", JSONObject()
            .put("touch_width", width).put("touch_height", height).put("touch_mapping_available", available))

    @Test fun landscapeEndpointsAndCentreStayInsideTouchBounds() {
        val f = frame(2176, 1812, 1024, 852)
        assertEquals(0 to 0, FrameCoordinates.normalized(f, 0.0, 0.0))
        assertEquals(2175 to 1811, FrameCoordinates.normalized(f, 1.0, 1.0))
        assertEquals(1088 to 906, FrameCoordinates.normalized(f, 0.5, 0.5))
        val matrix = FrameCoordinates.mapping(f) as org.json.JSONArray
        assertEquals(2175.0, matrix.getDouble(0) * 1023, 0.0001)
        assertEquals(1811.0, matrix.getDouble(4) * 851, 0.0001)
    }

    @Test fun normalizedPointDoesNotDependOnPreviewResolution() {
        assertEquals(FrameCoordinates.normalized(frame(1812, 2176, 799, 960), 0.51, 0.73),
            FrameCoordinates.normalized(frame(1812, 2176, 1705, 2048), 0.51, 0.73))
    }

    @Test fun unknownWindowOffsetIsNotGuessed() {
        val f = frame(2176, 1812, 1024, 852, false)
        assertEquals(JSONObject.NULL, FrameCoordinates.mapping(f))
        assertThrows(IllegalStateException::class.java) { FrameCoordinates.normalized(f, 0.5, 0.5) }
    }

    @Test fun invalidCoordinatesDoNotBecomeOffscreenTaps() {
        for (x in listOf(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                FrameCoordinates.normalized(frame(2176, 1812, 1024, 852), x, 0.5)
            }
        }
    }
}
