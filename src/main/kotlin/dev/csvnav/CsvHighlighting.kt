package dev.csvnav

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import java.awt.Color
import java.awt.Font
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

object CsvNavColors {
    /** Defaults come from the colorSchemes XML files (green / blue, bold); editable in Settings | Editor | Color Scheme. */
    val PROCESS: TextAttributesKey = TextAttributesKey.createTextAttributesKey("CSV_TEST_NAVIGATOR_PROCESS")
    val DATA: TextAttributesKey = TextAttributesKey.createTextAttributesKey("CSV_TEST_NAVIGATOR_DATA")
    /** Red bold text by default, replacing the process/data colour. */
    val UNRESOLVED: TextAttributesKey = TextAttributesKey.createTextAttributesKey("CSV_TEST_NAVIGATOR_UNRESOLVED")

    fun forColumn(column: String): TextAttributesKey =
        if (column.trim().startsWith(CsvNavSettings.PROCESS_COLUMN, ignoreCase = true)) PROCESS else DATA

    /**
     * The scheme's attributes for [key], or built-in bold green/blue/red if the scheme has none (a scheme the
     * colorSchemes XML files don't cover, or the plugin was installed without restarting the IDE).
     */
    fun attributes(scheme: EditorColorsScheme, key: TextAttributesKey): TextAttributes? {
        val fromScheme = scheme.getAttributes(key)
        if (fromScheme != null && (fromScheme.foregroundColor != null || fromScheme.backgroundColor != null)) return null
        return fallback(key, ColorUtil.isDark(scheme.defaultBackground))
    }

    fun fallback(key: TextAttributesKey, dark: Boolean): TextAttributes {
        val color = when (key) {
            PROCESS -> if (dark) Color(0x6AAB73) else Color(0x067D17)
            UNRESOLVED -> if (dark) Color(0xF75464) else Color(0xD50000)
            else -> if (dark) Color(0x56A8F5) else Color(0x0033B3)
        }
        return TextAttributes(color, null, null, null, Font.BOLD)
    }
}

/** Applies [key] to the builder, or explicit fallback attributes when the colour scheme doesn't define it. */
private fun HighlightInfo.Builder.colours(scheme: EditorColorsScheme, key: TextAttributesKey): HighlightInfo.Builder {
    val fallback = CsvNavColors.attributes(scheme, key)
    return if (fallback == null) textAttributes(key) else textAttributes(fallback)
}

/** Runs [CsvHighlightingPass] for every editor showing a CSV file, whatever language the IDE assigns to it. */
class CsvHighlightingPassFactory : TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar {

    override fun registerHighlightingPassFactory(registrar: TextEditorHighlightingPassRegistrar, project: Project) {
        registrar.registerTextEditorHighlightingPass(this, null, null, false, -1)
    }

    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
        val vFile = file.virtualFile ?: return null
        if (!CsvNavSettings.getInstance(file.project).isCsvExtension(vFile.extension)) return null
        return CsvHighlightingPass(file.project, editor, vFile)
    }
}

/** Colours mapped cells (processes green, data blue) and underlines in red those whose target can't be found. */
class CsvHighlightingPass(project: Project, editor: Editor, private val file: VirtualFile) :
    TextEditorHighlightingPass(project, editor.document, false) {

    private var infos: List<HighlightInfo> = emptyList()

    override fun doCollectInformation(progress: ProgressIndicator) {
        val cache = CsvResolveCache()
        val result = ArrayList<HighlightInfo>()
        val scheme = colorsScheme ?: EditorColorsManager.getInstance().globalScheme
        for (ref in CsvReferenceResolver.allReferences(myProject, myDocument)) {
            ProgressManager.checkCanceled()
            val info = if (CsvReferenceResolver.resolve(myProject, file, ref, cache).isEmpty()) {
                // Error severity keeps the tooltip, error-stripe mark and F2 navigation; the colour is just red text.
                HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
                    .range(ref.range)
                    .colours(scheme, CsvNavColors.UNRESOLVED)
                    .descriptionAndTooltip(unresolvedMessage(ref))
            } else {
                HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
                    .range(ref.range)
                    .colours(scheme, CsvNavColors.forColumn(ref.column))
            }
            info.create()?.let { result += it }
        }
        infos = result
    }

    override fun doApplyInformationToEditor() {
        UpdateHighlightersUtil.setHighlightersToEditor(
            myProject, myDocument, 0, myDocument.textLength, infos, colorsScheme, id,
        )
    }

    companion object {
        fun unresolvedMessage(ref: CsvReference): String {
            val missing = CsvReferenceResolver.missingPlaceholders(ref)
            if (missing.isNotEmpty()) {
                return "Cannot resolve ${ref.column} '${ref.value}': this row has no ${missing.joinToString(", ")}"
            }
            return "Cannot resolve ${ref.column} '${ref.value}' in ${CsvReferenceResolver.describeTargets(ref)}"
        }
    }
}

/** Settings | Editor | Color Scheme | CSV Test Navigator. */
class CsvNavColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "CSV Test Navigator"
    override fun getIcon() = null
    override fun getHighlighter() = PlainSyntaxHighlighter()
    override fun getDemoText() =
        "id,Process1,Data1,Process2,Data2\n" +
            "TC1,<process>Login</process>,<data>user1</data>,<process>Logout</process>,<data>user1</data>\n" +
            "TC2,<unresolved>Missing</unresolved>,<unresolved>user1</unresolved>,<process>Login</process>,<unresolved>user9</unresolved>\n"
    override fun getAdditionalHighlightingTagToDescriptorMap() =
        mapOf("process" to CsvNavColors.PROCESS, "data" to CsvNavColors.DATA, "unresolved" to CsvNavColors.UNRESOLVED)
    override fun getAttributeDescriptors() = arrayOf(
        AttributesDescriptor("Process name", CsvNavColors.PROCESS),
        AttributesDescriptor("Data name", CsvNavColors.DATA),
        AttributesDescriptor("Missing reference", CsvNavColors.UNRESOLVED),
    )
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
}
