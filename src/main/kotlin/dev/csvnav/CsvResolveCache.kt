package dev.csvnav

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/** First cell of a row in a target CSV. */
data class CsvRowKey(val key: String, val offset: Int, val line: Int)

/**
 * Short-lived cache for resolving many references at once (one highlighting pass), so each folder is scanned and
 * each target CSV is read only once. Create a new one per pass; it does not track file changes.
 */
class CsvResolveCache {
    private val located = HashMap<String, List<VirtualFile>>()
    private val filesUnder = HashMap<VirtualFile, List<VirtualFile>>()
    private val rows = HashMap<VirtualFile, List<CsvRowKey>>()

    fun located(source: VirtualFile, path: String, compute: () -> List<VirtualFile>): List<VirtualFile> {
        val key = if (path.startsWith(".")) "${source.parent?.path}|$path" else "|$path"
        return located.getOrPut(key, compute)
    }

    fun filesUnder(dir: VirtualFile): List<VirtualFile> = filesUnder.getOrPut(dir) {
        val result = ArrayList<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(dir, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                ProgressManager.checkCanceled()
                if (!file.isDirectory) result += file
                return true
            }
        })
        result
    }

    fun rows(file: VirtualFile): List<CsvRowKey> = rows.getOrPut(file) { CsvReferenceResolver.readRows(file) }
}
