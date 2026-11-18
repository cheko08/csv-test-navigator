package dev.csvnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsvLineTest {

    @Test
    fun detectsDelimiters() {
        assertEquals(',', CsvLine.detectDelimiter("id,process,data"))
        assertEquals(';', CsvLine.detectDelimiter("id;process;data"))
        assertEquals('\t', CsvLine.detectDelimiter("id\tprocess\tdata"))
        assertEquals(',', CsvLine.detectDelimiter("\"a;b;c\",process"))
        assertEquals(',', CsvLine.detectDelimiter("single"))
    }

    @Test
    fun parsesPlainCellsWithOffsets() {
        val line = "TC1, Login ,user1"
        val cells = CsvLine.parse(line, ',')
        assertEquals(listOf("TC1", "Login", "user1"), cells.map { it.value })
        assertEquals("Login", line.substring(cells[1].start, cells[1].end))
    }

    @Test
    fun parsesQuotedCells() {
        val line = "TC1,\"Log, in\",\"say \"\"hi\"\"\""
        val cells = CsvLine.parse(line, ',')
        assertEquals(listOf("TC1", "Log, in", "say \"hi\""), cells.map { it.value })
        assertEquals("Log, in", line.substring(cells[1].start, cells[1].end))
    }

    @Test
    fun keepsEmptyCells() {
        assertEquals(listOf("a", "", "c", ""), CsvLine.parse("a,,c,", ',').map { it.value })
    }

    @Test
    fun mappingRulesMatchPrefixesAndPreferExactNames() {
        val rules = CsvNavSettings.parseMappings("process* = a\ndata* = b\ndatasource = c\n# comment\nbroken")
        assertEquals("process" to "1", CsvNavSettings.match(rules, "Process1")?.let { it.first.name to it.second })
        assertEquals("data" to "", CsvNavSettings.match(rules, " DATA ")?.let { it.first.name to it.second })
        assertEquals(listOf("c"), CsvNavSettings.match(rules, "DataSource")?.first?.paths)
        assertNull(CsvNavSettings.match(rules, "Id"))
    }

    @Test
    fun cellAtFindsCellUnderCaret() {
        val cells = CsvLine.parse("TC1,Login,user1", ',')
        assertEquals("Login", CsvLine.cellAt(cells, 4)?.value)
        assertEquals("Login", CsvLine.cellAt(cells, 9)?.value) // caret right after the word
        assertNull(CsvLine.cellAt(CsvLine.parse("a,,c", ','), 2))
    }
}
