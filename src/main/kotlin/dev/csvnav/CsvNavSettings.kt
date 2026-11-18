package dev.csvnav

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/** `name = paths`, or `name* = paths` ([prefix]) to match every column whose name starts with [name]. */
data class MappingRule(val name: String, val prefix: Boolean, val paths: List<String>)

/**
 * Project-level settings, stored in `.idea/csvTestNavigator.xml` so they can be committed and shared with the team.
 */
@Service(Service.Level.PROJECT)
@State(name = "CsvTestNavigator", storages = [Storage("csvTestNavigator.xml")])
class CsvNavSettings : PersistentStateComponent<CsvNavSettings.State> {

    class State {
        /**
         * One mapping per line: `column = path[, path2...]`; `column*` matches every column starting with `column`.
         * Paths starting with `.` are relative to the test case file's folder, others to the project root.
         * See [CsvReferenceResolver.resolve].
         */
        var mappings: String = DEFAULT_MAPPINGS
        /** Comma-separated extensions of files treated as test-case CSVs. */
        var csvExtensions: String = "csv"
        /** Row number (1-based) holding the column names. */
        var headerRow: Int = 1
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    fun rules(): List<MappingRule> = parseMappings(state.mappings)

    fun isCsvExtension(ext: String?): Boolean =
        ext != null && state.csvExtensions.split(',').any { it.trim().equals(ext, ignoreCase = true) }

    companion object {
        const val DEFAULT_MAPPINGS = "process* = ../config/ProcessDetails.csv\ndata* = ../testData/**/{process*}.csv"

        /** Columns starting with this get the process colour; other mapped columns get the data colour. */
        const val PROCESS_COLUMN = "process"

        fun getInstance(project: Project): CsvNavSettings = project.getService(CsvNavSettings::class.java)

        fun parseMappings(text: String): List<MappingRule> =
            text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .mapNotNull { line ->
                    val (col, folders) = line.split('=', limit = 2)
                    val name = col.trim().lowercase()
                    val paths = folders.split(',').map { it.trim().trimEnd('/', '\\') }.filter { it.isNotEmpty() }
                    if (name.isEmpty() || name == "*" || paths.isEmpty()) null
                    else MappingRule(name.removeSuffix("*"), name.endsWith("*"), paths)
                }

        /**
         * The rule for [column] plus the rest of the name after the rule's prefix (e.g. `Data2` -> `2`).
         * Exact rules beat prefix rules; among prefix rules the longest prefix wins.
         */
        fun match(rules: List<MappingRule>, column: String): Pair<MappingRule, String>? {
            val c = column.trim().lowercase()
            rules.firstOrNull { !it.prefix && it.name == c }?.let { return it to "" }
            return rules.filter { it.prefix && c.startsWith(it.name) }
                .maxByOrNull { it.name.length }
                ?.let { it to c.removePrefix(it.name) }
        }
    }
}
