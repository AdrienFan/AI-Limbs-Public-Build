package com.ai.limbs.plugins.artstudio

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xml.sax.SAXException

class ArtSystemFontCatalogTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    private fun read(xml: String, vararg installed: String): ArtSystemFontCatalog.Inventory {
        val root = temporary.newFolder()
        val fonts = File(root, "fonts").apply { mkdir() }
        installed.forEach { File(fonts, it).writeBytes(byteArrayOf(0)) }
        val configuration = File(root, "fonts.xml").apply { writeText(xml) }
        return ArtSystemFontCatalog.read(configuration, fonts)
    }

    @Test fun missingKhmerDeclarationDoesNotHideReadableChineseAndLatinFaces() {
        val result = read("""
            <familyset>
              <family name="latin"><font>Latin.ttf</font></family>
              <family lang="und-Khmr"><font>NotoSerifKhmer-Regular.otf</font></family>
              <family lang="zh-Hans"><font index="2">SECCJK-Regular.ttc</font></family>
              <family><font>MissingAfter.ttf</font></family>
            </familyset>
        """.trimIndent(), "Latin.ttf", "SECCJK-Regular.ttc")
        assertEquals(listOf("Latin.ttf", "SECCJK-Regular.ttc"), result.available.map { it.file.name })
        assertEquals(listOf("NotoSerifKhmer-Regular.otf", "MissingAfter.ttf"), result.unavailable.map { it.name })
        assertTrue(result.unavailable.all { it.reason == "not_installed" })
        assertEquals("family-3", result.available.last().family)
        assertEquals("zh-Hans", result.available.last().languages)
    }

    @Test fun existingFaceIdentityPreservesTtcStyleAxesAndFamilyMetadata() {
        val result = read("""
            <familyset><family name="sample" lang="zh-Hans,en">
              <font index="2" weight="700" style="italic">
                Existing.ttc
                <axis tag="wdth" stylevalue="100"/>
                <axis tag="wght" stylevalue="700"/>
              </font>
            </family></familyset>
        """.trimIndent(), "Existing.ttc")
        val face = result.available.single()
        assertEquals(2, face.index)
        assertEquals(700, face.weight)
        assertEquals(1, face.slant)
        assertEquals("'wdth' 100.0, 'wght' 700.0", face.settings)
        assertEquals(face.file.absolutePath + "#2#700#1#'wdth' 100.0, 'wght' 700.0", face.id)
        assertEquals("sample", face.family)
        assertEquals("zh-Hans,en", face.languages)
    }

    @Test fun declarationVariantsOfOneCollectionRetainDistinctSelectableIds() {
        val result = read("""
            <familyset><family>
              <font index="2" weight="400">Existing.ttc</font>
              <font index="3" weight="400">Existing.ttc</font>
              <font index="2" weight="700" style="italic">Existing.ttc</font>
            </family></familyset>
        """.trimIndent(), "Existing.ttc")
        assertEquals(3, result.available.map { it.id }.distinct().size)
        assertTrue(result.unavailable.isEmpty())
    }

    @Test fun noInstalledFontsProducesAnExplicitEmptyInventory() {
        val result = read("<familyset><family><font>Missing.ttf</font></family></familyset>")
        assertTrue(result.available.isEmpty())
        assertEquals(ArtSystemFontCatalog.Unavailable("Missing.ttf", "not_installed"),
            result.unavailable.single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun declaredPathCannotEscapeTheFontDirectory() {
        read("<familyset><family><font>../Outside.ttf</font></family></familyset>")
    }

    @Test(expected = IllegalArgumentException::class)
    fun entityDeclarationsRemainErrorsRatherThanBeingSkipped() {
        read("<!DOCTYPE familyset [<!ENTITY font 'Existing.ttf'>]><familyset><family><font>&font;</font></family></familyset>",
            "Existing.ttf")
    }

    @Test(expected = SAXException::class)
    fun malformedConfigurationIsNotReportedAsAnEmptyInstalledCatalog() {
        read("<familyset><family><font>Existing.ttf</font>", "Existing.ttf")
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidAxisMetadataIsNotHiddenByMissingFileStatus() {
        read("<familyset><family><font>Missing.ttf<axis tag='weight' stylevalue='400'/></font></family></familyset>")
    }
}
