package dev.csvnav

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

class CsvNavConfigurable(private val project: Project) : BoundConfigurable("CSV Test Navigator") {

    override fun apply() {
        super.apply()
        DaemonCodeAnalyzer.getInstance(project).restart() // re-highlight open CSVs with the new mappings
    }

    override fun createPanel(): DialogPanel {
        val state = CsvNavSettings.getInstance(project).state
        return panel {
            row {
                comment(
                    "One mapping per line: <code>column = path</code> (separate several paths with commas). " +
                        "<code>column*</code> matches every column starting with that name (e.g. Process1, Process2). " +
                        "Paths starting with <code>.</code> are relative to the test case file, others to the project root. " +
                        "<code>{column}</code> is replaced by that column's value in the same row; " +
                        "<code>{process*}</code> in a <code>Data2</code> column means <code>Process2</code> " +
                        "(or the nearest Process column to its left); " +
                        "<code>**</code> matches any number of folders.<br>" +
                        "<b>Path to a CSV file</b>: jumps to the row whose first column equals the cell value.<br>" +
                        "<b>Path to a folder</b>: jumps to a file with that name, or to a matching row (first column) " +
                        "in any CSV file in the folder or its sub-folders.<br>Lines starting with # are ignored."
                )
            }
            row("Column mappings:") {
                textArea()
                    .rows(8)
                    .align(AlignX.FILL)
                    .bindText(state::mappings)
            }
            row("CSV file extensions:") {
                textField().bindText(state::csvExtensions).comment("Comma-separated, e.g. <code>csv, tsv</code>")
            }
            row("Header row:") {
                intTextField(1..1000).bindIntText(state::headerRow)
            }
        }
    }
}
