package com.ai.limbs.plugins.artstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtCapabilityHelpTest {
    @Test fun everyExampleMatchesItsRegisteredParameterContract() {
        assertEquals(207, ArtCapabilityHelp.names().size)
        for (name in ArtCapabilityHelp.names()) {
            val fields = parametersFor(name)
            val example = ArtCapabilityHelp.example(name)
            val keys = fields.map { it.name }.toSet()
            assertEquals("duplicate fields: $name", fields.size, keys.size)
            assertTrue("unknown example keys: $name", example.keys().asSequence().all { it in keys })
            assertTrue("blank capability help: $name", ArtCapabilityHelp.description(name).isNotBlank())
            for (field in fields) {
                assertTrue("missing parameter help: $name.${field.name}",
                    field.description.isNotBlank() && field.description != field.name)
                if (field.required) assertTrue("missing required example argument: $name.${field.name}", example.has(field.name))
                if (!example.has(field.name)) continue
                val value = example.get(field.name)
                val typed = when (field.type) {
                    "string" -> value is String
                    "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
                    "number" -> value is Number && value.toDouble().isFinite()
                    "boolean" -> value is Boolean
                    "array" -> value is JSONArray
                    "object" -> value is JSONObject
                    else -> false
                }
                assertTrue("wrong argument type: $name.${field.name}", typed)
                val choices = ArtCapabilityHelp.property(name, field).optJSONArray("enum")
                if (choices != null) assertTrue("invalid enum example: $name.${field.name}",
                    (0 until choices.length()).any { choices.get(it) == value })
            }
        }
    }

    @Test fun examplesAreIndependentCopiesAndNeverContainLiveArtworkIds() {
        val changed = ArtCapabilityHelp.example("stroke.add")
        changed.put("width", -1)
        assertEquals(6.0, ArtCapabilityHelp.example("stroke.add").getDouble("width"), 0.0)
        for (name in ArtCapabilityHelp.names()) {
            assertFalse("fixed artwork UUID in example: $name",
                Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                    .containsMatchIn(ArtCapabilityHelp.example(name).toString()))
        }
    }

    @Test fun drawingExamplesUseValidColorsDimensionsAndActualEditActions() {
        val create = ArtCapabilityHelp.example("document.create")
        assertTrue(create.getInt("width") in 1..16384)
        assertTrue(create.getInt("height") in 1..16384)
        val color = Regex("#[A-Fa-f0-9]{8}")
        for (name in ArtCapabilityHelp.names()) {
            val args = ArtCapabilityHelp.example(name)
            for (key in listOf("color", "background", "baseColor", "gradientEndColor", "regionColor", "boundaryColor")) {
                if (args.has(key)) assertTrue("invalid color: $name.$key", color.matches(args.getString(key)))
            }
        }
        val stroke = ArtCapabilityHelp.example("stroke.add")
        assertEquals("ink", stroke.getString("tool"))
        assertTrue(stroke.getDouble("width") in 0.1..512.0)
        assertEquals(2, stroke.getJSONArray("points").length())
        for (name in listOf("path.edit", "selection.bezier_edit"))
            assertEquals("move_node", ArtCapabilityHelp.example(name).getJSONArray("edits")
                .getJSONObject(0).getString("action"))
    }

    @Test fun ambiguousUnitsAndTargetsRemainExplicitWithoutRepeatingAllTools() {
        assertTrue(ArtCapabilityHelp.parameterDescription("stroke.add", "points").contains("图层局部"))
        assertTrue(ArtCapabilityHelp.parameterDescription("assistant.stroke", "points").contains("文档坐标"))
        assertTrue(ArtCapabilityHelp.parameterDescription("path.edit", "edits").contains("对象局部"))
        assertTrue(ArtCapabilityHelp.parameterDescription("selection.bezier_edit", "edits").contains("文档像素"))
        assertTrue(ArtCapabilityHelp.description("view.set").contains("手机"))
        assertEquals("assistant", ArtCapabilityHelp.example("view.command").getString("target"))
        assertTrue(ArtCapabilityHelp.description("export.png").contains("不返回原尺寸图片块"))
        assertTrue(ArtCapabilityHelp.parameterDescription("document.create", "background").contains("#AARRGGBB"))
        assertTrue(ArtCapabilityHelp.parameterDescription("stroke.add", "expectedRevision").contains("替换示例0"))
        assertTrue(ArtCapabilityHelp.parameterDescription("layer.select", "id").contains("layer.list.layers[].id"))
        assertTrue(ArtCapabilityHelp.parameterDescription("text.update", "id").contains("kind=text"))
    }
}
