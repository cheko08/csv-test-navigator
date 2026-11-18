package dev.csvnav

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CsvColorsTest : BasePlatformTestCase() {

    fun testEveryBundledSchemeHasColours() {
        val manager = EditorColorsManager.getInstance()
        val report = manager.allSchemes.map { scheme ->
            val attrs = listOf(CsvNavColors.PROCESS, CsvNavColors.DATA, CsvNavColors.UNRESOLVED)
                .map { scheme.getAttributes(it)?.foregroundColor }
            "${scheme.name} (parent=${(scheme as? com.intellij.openapi.editor.colors.impl.AbstractColorsScheme)?.parentScheme?.name}): $attrs"
        }
        println("SCHEMES:\n" + report.joinToString("\n"))
        for (scheme in manager.allSchemes.filter { it.name.removePrefix("_@user_") in setOf("Default", "Darcula") }) {
            for (key in listOf(CsvNavColors.PROCESS, CsvNavColors.DATA, CsvNavColors.UNRESOLVED)) {
                assertNotNull("${scheme.name} has no foreground for ${key.externalName}", scheme.getAttributes(key)?.foregroundColor)
            }
        }
    }

    fun testFallbackWhenSchemeHasNoColours() {
        val scheme = EditorColorsManager.getInstance().globalScheme.clone() as EditorColorsScheme
        scheme.setAttributes(CsvNavColors.PROCESS, TextAttributes())
        val attrs = CsvNavColors.attributes(scheme, CsvNavColors.PROCESS)
        assertNotNull("expected built-in fallback", attrs?.foregroundColor)
        assertNull("scheme colours win when present", CsvNavColors.attributes(scheme, CsvNavColors.DATA))
    }
}
