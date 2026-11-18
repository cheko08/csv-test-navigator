package dev.csvnav

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/**
 * A mapped cell: its text range in the document, column header (as written), value and target paths.
 * [row] holds every cell of its row as (header, value) in column order, [index] is this cell's column, and
 * [suffix] is the header after the matched rule's prefix (`Data2` -> `2`); both are used for placeholders.
 */
data class CsvReference(
    val range: TextRange,
    val column: String,
    val value: String,
    val paths: List<String>,
    val row: List<Pair<String, String>> = emptyList(),
    val index: Int = 0,
    val suffix: String = "",
)

/** Where a reference points: a whole file, or a row ([offset]/[line]) inside a CSV file. */
data class CsvTarget(val file: VirtualFile, val offset: Int = 0, val line: Int = -1) {
    val isRow get() = line >= 0
}

object CsvReferenceResolver {

    private val PLACEHOLDER = Regex("""\{([^}]+)}""")

    /** Finds the mapped cell at [offset] in [document], or null if the offset is not on one. */
    fun findReference(project: Project, document: Document, offset: Int): CsvReference? {
        if (offset < 0 || offset > document.textLength || document.lineCount == 0) return null
        val line = document.getLineNumber(offset)
        return referencesInLines(project, document, line..line).firstOrNull { it.range.containsOffset(offset) }
    }

    /** All mapped, non-empty cells of [document] below the header row. */
    fun allReferences(project: Project, document: Document): List<CsvReference> =
        referencesInLines(project, document, 0 until document.lineCount)

    private fun referencesInLines(project: Project, document: Document, lines: IntRange): List<CsvReference> {
        val settings = CsvNavSettings.getInstance(project)
        val rules = settings.rules()
        if (rules.isEmpty() || document.lineCount == 0) return emptyList()

        val headerLine = (settings.state.headerRow - 1).coerceIn(0, document.lineCount - 1)
        val headerText = lineText(document, headerLine)
        val delimiter = CsvLine.detectDelimiter(headerText)
        val headers = CsvLine.parse(headerText, delimiter).map { it.value }
        val matches = headers.map { CsvNavSettings.match(rules, it) }
        if (matches.all { it == null }) return emptyList()

        val result = ArrayList<CsvReference>()
        for (line in lines) {
            if (line <= headerLine || line >= document.lineCount) continue
            val lineStart = document.getLineStartOffset(line)
            val cells = CsvLine.parse(lineText(document, line), delimiter)
            val row = headers.indices.map { i -> headers[i] to (cells.getOrNull(i)?.value ?: "") }
            for (cell in cells) {
                if (cell.value.isEmpty()) continue
                val (rule, suffix) = matches.getOrNull(cell.index) ?: continue
                result += CsvReference(
                    TextRange(lineStart + cell.start, lineStart + cell.end),
                    headers[cell.index], cell.value, rule.paths, row, cell.index, suffix,
                )
            }
        }
        return result
    }

    /**
     * Resolves [ref] from the test case file [source]. In each mapped path, `{column}` is replaced by that column's
     * value in the same row, and `**` matches any number of folders. Paths starting with `.` are relative to
     * [source]'s folder, others to the project root.
     * - A path to a file: the rows of that CSV whose first column equals the value.
     * - A path to a folder: files in it (recursively) named after the value, plus rows of the CSV files in it
     *   whose first column equals the value.
     * Exact matches win; case-insensitive matches are used only when there is no exact one.
     */
    fun resolve(
        project: Project,
        source: VirtualFile,
        ref: CsvReference,
        cache: CsvResolveCache = CsvResolveCache(),
    ): List<CsvTarget> {
        val settings = CsvNavSettings.getInstance(project)
        val value = ref.value.replace('\\', '/').trim('/')
        val exact = LinkedHashSet<CsvTarget>()
        val loose = LinkedHashSet<CsvTarget>()

        fun collectRows(file: VirtualFile) {
            if (file == source) return
            for (row in cache.rows(file)) {
                when {
                    row.key == value -> exact += CsvTarget(file, row.offset, row.line)
                    row.key.equals(value, ignoreCase = true) -> loose += CsvTarget(file, row.offset, row.line)
                }
            }
        }

        for (rawPath in ref.paths) {
            val path = substitute(rawPath, ref) ?: continue
            for (target in cache.located(source, path) { locate(project, source, path) }) {
                if (!target.isDirectory) {
                    collectRows(target)
                    continue
                }
                val subPath = value.substringBeforeLast('/', "")
                val name = value.substringAfterLast('/')
                val nameDir = if (subPath.isEmpty()) target else target.findFileByRelativePath(subPath)
                for (file in cache.filesUnder(target)) {
                    if (nameDir != null && VfsUtilCore.isAncestor(nameDir, file, false)) {
                        when {
                            file.nameWithoutExtension == name || file.name == name -> exact += CsvTarget(file)
                            file.nameWithoutExtension.equals(name, true) || file.name.equals(name, true) ->
                                loose += CsvTarget(file)
                        }
                    }
                    if (settings.isCsvExtension(file.extension)) collectRows(file)
                }
            }
        }
        return (exact.ifEmpty { loose }).toList()
    }

    /** The mapped paths of [ref] with placeholders filled in, for messages; a path with a missing value is kept as-is. */
    fun describeTargets(ref: CsvReference): String =
        ref.paths.joinToString(", ") { substitute(it, ref) ?: it }

    /** Columns that placeholders in [ref]'s paths need but are empty or absent in its row, e.g. `Process2`. */
    fun missingPlaceholders(ref: CsvReference): List<String> =
        ref.paths.flatMap { p -> PLACEHOLDER.findAll(p).map { it.groupValues[1].trim() } }
            .filter { placeholderValue(it, ref).isNullOrEmpty() }
            .map { placeholderColumn(it, ref)?.let { i -> ref.row[i].first } ?: expectedColumn(it, ref) }
            .distinct()

    /** Replaces placeholders with values from [ref]'s row; null if one is missing or empty. */
    private fun substitute(path: String, ref: CsvReference): String? {
        var missing = false
        val result = PLACEHOLDER.replace(path) { m ->
            val v = placeholderValue(m.groupValues[1].trim(), ref)
            if (v.isNullOrEmpty()) { missing = true; "" } else v
        }
        return if (missing) null else result
    }

    private fun placeholderValue(placeholder: String, ref: CsvReference): String? =
        placeholderColumn(placeholder, ref)?.let { ref.row[it].second }

    /**
     * Column index a placeholder refers to. `{name}` is the column called `name`. `{name*}` is the column `name`
     * plus this cell's suffix (`{process*}` in `Data2` -> `Process2`), or failing that the nearest column to the
     * left starting with `name`.
     */
    private fun placeholderColumn(placeholder: String, ref: CsvReference): Int? {
        val headers = ref.row.map { it.first.trim().lowercase() }
        if (!placeholder.endsWith("*")) return headers.indexOf(placeholder.lowercase()).takeIf { it >= 0 }
        val prefix = placeholder.removeSuffix("*").trim().lowercase()
        headers.indexOf(prefix + ref.suffix).takeIf { it >= 0 }?.let { return it }
        return (ref.index - 1 downTo 0).firstOrNull { headers[it].startsWith(prefix) }
    }

    private fun expectedColumn(placeholder: String, ref: CsvReference): String =
        if (placeholder.endsWith("*")) placeholder.removeSuffix("*") + ref.suffix else placeholder

    /** Resolves a mapped path (after placeholder substitution) to existing files/folders. */
    private fun locate(project: Project, source: VirtualFile, path: String): List<VirtualFile> {
        val normalized = path.replace('\\', '/')
        val bases = if (normalized.startsWith(".")) {
            listOfNotNull(source.parent)
        } else {
            buildList {
                project.guessProjectDir()?.let { add(it) }
                addAll(ProjectRootManager.getInstance(project).contentRoots)
            }.distinct()
        }
        val relative = if (normalized.startsWith(".")) normalized else normalized.trim('/')

        val starStar = relative.indexOf("**")
        if (starStar < 0) return bases.mapNotNull { it.findFileByRelativePath(relative) }.distinct()

        // "prefix/**/suffix": search below prefix for anything whose relative path ends with suffix.
        val prefix = relative.substring(0, starStar).trimEnd('/')
        val suffix = relative.substring(starStar + 2).trim('/')
        val found = LinkedHashSet<VirtualFile>()
        for (base in bases) {
            val dir = (if (prefix.isEmpty()) base else base.findFileByRelativePath(prefix)) ?: continue
            if (!dir.isDirectory) continue
            if (suffix.isEmpty()) { found += dir; continue }
            VfsUtilCore.visitChildrenRecursively(dir, object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file != dir) {
                        val rel = VfsUtilCore.getRelativePath(file, dir, '/') ?: return true
                        if (rel.equals(suffix, true) || rel.endsWith("/$suffix", true)) found += file
                    }
                    return true
                }
            })
        }
        return found.toList()
    }

    /** First-column keys of every row of [file]. */
    internal fun readRows(file: VirtualFile): List<CsvRowKey> {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return emptyList()
        if (document.lineCount == 0) return emptyList()
        val delimiter = CsvLine.detectDelimiter(lineText(document, 0))
        return (0 until document.lineCount).mapNotNull { line ->
            val first = CsvLine.parse(lineText(document, line), delimiter).firstOrNull()
            if (first == null || first.value.isEmpty()) null
            else CsvRowKey(first.value, document.getLineStartOffset(line) + first.start, line)
        }
    }

    internal fun lineText(document: Document, line: Int): String =
        document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
}
