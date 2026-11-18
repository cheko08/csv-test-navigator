package dev.csvnav

/** One cell of a CSV line. [start]/[end] are offsets of the cell's text within the line, quotes excluded. */
data class CsvCell(val index: Int, val start: Int, val end: Int, val value: String)

object CsvLine {

    /** Picks the most frequent of `,` `;` `\t` `|` (outside quotes) in the header line; defaults to comma. */
    fun detectDelimiter(header: String): Char {
        val candidates = charArrayOf(',', ';', '\t', '|')
        val counts = IntArray(candidates.size)
        var inQuotes = false
        for (c in header) {
            if (c == '"') inQuotes = !inQuotes
            else if (!inQuotes) {
                val i = candidates.indexOf(c)
                if (i >= 0) counts[i]++
            }
        }
        val best = counts.indices.maxByOrNull { counts[it] } ?: 0
        return if (counts[best] == 0) ',' else candidates[best]
    }

    /** Splits a single line into cells, honouring double-quoted cells and `""` escapes. */
    fun parse(line: String, delimiter: Char): List<CsvCell> {
        val cells = ArrayList<CsvCell>()
        var pos = 0
        var index = 0
        while (true) {
            var p = pos
            while (p < line.length && line[p] != delimiter && line[p].isWhitespace()) p++
            val start: Int
            val end: Int
            val value: String
            if (p < line.length && line[p] == '"') {
                // Quoted cell: content runs to the next unescaped quote.
                start = p + 1
                val sb = StringBuilder()
                p = start
                while (p < line.length) {
                    if (line[p] == '"') {
                        if (p + 1 < line.length && line[p + 1] == '"') { sb.append('"'); p += 2; continue }
                        break
                    }
                    sb.append(line[p]); p++
                }
                end = p
                value = sb.toString().trim()
                while (p < line.length && line[p] != delimiter) p++
            } else {
                start = p
                while (p < line.length && line[p] != delimiter) p++
                var e = p
                while (e > start && line[e - 1].isWhitespace()) e--
                end = e
                value = line.substring(start, end)
            }
            cells += CsvCell(index++, start, end, value)
            if (p >= line.length) break
            pos = p + 1 // skip delimiter
        }
        return cells
    }

    /** Returns the cell containing [column] (an offset within the line), or null if it sits on a delimiter. */
    fun cellAt(cells: List<CsvCell>, column: Int): CsvCell? =
        cells.firstOrNull { column >= it.start && column <= it.end && it.value.isNotEmpty() }
}
