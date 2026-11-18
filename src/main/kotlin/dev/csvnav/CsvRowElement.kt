package dev.csvnav

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import javax.swing.Icon

/** Navigation target for one row of a CSV file; opens the file with the caret on the row's first cell. */
class CsvRowElement(
    private val psiFile: PsiFile,
    private val name: String,
    val offset: Int,
    val line: Int,
) : FakePsiElement() {

    override fun getParent(): PsiElement = psiFile
    override fun getContainingFile(): PsiFile = psiFile
    override fun getName(): String = name
    override fun getTextOffset(): Int = offset
    override fun getTextRange(): TextRange = TextRange(offset, offset + name.length)
    override fun getNavigationElement(): PsiElement = this
    override fun isValid(): Boolean = psiFile.isValid
    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.DataTables

    override fun getPresentableText(): String = name
    override fun getLocationString(): String = "${psiFile.name}:${line + 1}"

    override fun canNavigate(): Boolean = psiFile.virtualFile != null
    override fun canNavigateToSource(): Boolean = canNavigate()
    override fun navigate(requestFocus: Boolean) {
        OpenFileDescriptor(project, psiFile.virtualFile, offset).navigate(requestFocus)
    }

    override fun equals(other: Any?): Boolean =
        other is CsvRowElement && other.psiFile == psiFile && other.offset == offset
    override fun hashCode(): Int = psiFile.hashCode() * 31 + offset
}
