package dev.csvnav

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * Ctrl+Click / Ctrl+B on a cell in a mapped column jumps to the matching file.
 * Works on the document text, so it behaves the same whether the CSV is opened as plain text
 * or by a CSV language plugin.
 */
class CsvGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val project = editor.project ?: return null
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        if (!CsvNavSettings.getInstance(project).isCsvExtension(file.extension)) return null

        val ref = CsvReferenceResolver.findReference(project, document, offset) ?: return null
        val psiManager = PsiManager.getInstance(project)
        val targets = CsvReferenceResolver.resolve(project, file, ref).mapNotNull { target ->
            val psiFile = psiManager.findFile(target.file) ?: return@mapNotNull null
            if (target.isRow) CsvRowElement(psiFile, ref.value, target.offset, target.line) else psiFile
        }
        return targets.toTypedArray<PsiElement>().ifEmpty { null }
    }

    override fun getActionText(context: DataContext): String? = null
}
