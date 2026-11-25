package dev.galaxybigbird.apartmentfinder.core

import java.util.Locale

data class RouteImport(
    val stops: List<DeliveryStop>,
    val errors: List<String>,
    val warnings: List<String> = emptyList()
) {
    val canImport: Boolean get() = stops.isNotEmpty() && errors.isEmpty()
}

/**
 * Exact 1:1 Kotlin port of Swift ApartmentCore RouteCSV.
 * Supports both OnTrac 6-column manifests (with sub-stop 12.1 ranking) and standard 4-column CSVs.
 */
object RouteCsvParser {
    private const val MAX_BYTES = 5_000_000
    private val onTracNumberRegex = Regex("""^[0-9]+(?:\.[0-9]+)?$""")

    private data class OnTracNumber(val major: Int, val subStop: Int?) {
        companion object {
            fun parse(label: String): OnTracNumber? {
                if (!onTracNumberRegex.matches(label)) return null
                val parts = label.split('.')
                val major = parts[0].toIntOrNull() ?: return null
                val sub = if (parts.size == 2) parts[1].toIntOrNull() else null
                if (parts.size == 2 && sub == null) return null
                if (major <= 0 && (sub ?: 0) <= 0) return null
                return OnTracNumber(major, sub)
            }
        }
    }

    private data class ParsedRow(val fields: List<String>, val physicalLine: Int)

    fun parse(input: String, knownAddresses: Collection<String> = emptyList()): RouteImport {
        require(input.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Route file is too large (maximum 5 MB)."
        }
        val text = input
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trimStart('\uFEFF')

        val rows = mutableListOf<ParsedRow>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var physicalLine = 1
        var rowStartLine = 1
        var i = 0

        fun finishField() {
            row.add(field.toString())
            field.setLength(0)
            closed = false
        }

        fun finishRow() {
            finishField()
            if (row.any { it.trim().isNotEmpty() }) {
                rows.add(ParsedRow(row.toList(), rowStartLine))
            }
            row.clear()
        }

        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i += 1
                    } else {
                        quoted = false
                        closed = true
                    }
                } else {
                    field.append(ch)
                    if (ch == '\n') physicalLine += 1
                }
            } else {
                when {
                    ch == ',' -> finishField()
                    ch == '\n' -> {
                        finishRow()
                        physicalLine += 1
                        rowStartLine = physicalLine
                    }
                    ch == '"' && field.isEmpty() && !closed -> quoted = true
                    else -> {
                        require(!closed && ch != '"') { "Unexpected character after a quoted CSV field." }
                        field.append(ch)
                    }
                }
            }
            i += 1
        }
        require(!quoted) { "The CSV contains an unclosed quoted field." }
        if (field.isNotEmpty() || row.isNotEmpty() || closed) finishRow()
        val header = rows.firstOrNull() ?: throw IllegalArgumentException("The CSV is empty.")
        if (rows.size > 2001) throw IllegalArgumentException("A route may contain at most 2,000 stops.")

        val columns = header.fields.map { it.trim().lowercase(Locale.US) }
        val standardCols = setOf("sequence", "address", "unit", "notes")
        val onTracCols = setOf("barcode", "last event", "last event time", "last gps location", "seq no", "address")
        val isOnTrac = columns.size == onTracCols.size && columns.toSet() == onTracCols
        val isStandard = columns.size == standardCols.size && columns.toSet() == standardCols
        require(isOnTrac || isStandard) {
            "Choose an OnTrac manifest or a CSV with sequence,address,unit,notes."
        }

        val seqIdx = columns.indexOf(if (isOnTrac) "seq no" else "sequence")
        val addrIdx = columns.indexOf("address")
        val unitIdx = if (isOnTrac) -1 else columns.indexOf("unit")
        val notesIdx = if (isOnTrac) -1 else columns.indexOf("notes")

        val dataRows = rows.drop(1)
        val sourceLabels = dataRows.map { r ->
            if (r.fields.size == columns.size) r.fields[seqIdx].trim() else ""
        }
        val sourceNumbers = sourceLabels.map { OnTracNumber.parse(it) }
        val fileOrder = isOnTrac && sourceLabels.any { it.isEmpty() }
        val uniqueMajors = sourceNumbers.mapNotNull { it?.major }.toSet().size
        val rankedOrder = isOnTrac && !fileOrder &&
            (sourceNumbers.any { it?.subStop != null } || uniqueMajors != sourceNumbers.size)

        val numberedOffsets = sourceNumbers.indices
            .filter { sourceNumbers[it] != null }
            .sortedWith { a, b ->
                val la = sourceNumbers[a]!!
                val lb = sourceNumbers[b]!!
                when {
                    la.major != lb.major -> la.major.compareTo(lb.major)
                    (la.subStop ?: 0) != (lb.subStop ?: 0) -> (la.subStop ?: 0).compareTo(lb.subStop ?: 0)
                    else -> a.compareTo(b)
                }
            }
        val ranks = numberedOffsets.withIndex().associate { (rank, offset) -> offset to (rank + 1) }

        val stops = mutableListOf<DeliveryStop>()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val seenSeq = HashSet<Int>()
        val resolver = DeliveryAddress.Resolver(knownAddresses)

        if (fileOrder && dataRows.isNotEmpty()) {
            warnings.add("Some OnTrac stop numbers are missing. Using the file's order; it may not match your delivery route.")
        }

        for ((offset, parsedRow) in dataRows.withIndex()) {
            val fields = parsedRow.fields
            val label = "Row ${parsedRow.physicalLine}"
            if (fields.size != columns.size) {
                errors.add("$label: wrong number of columns.")
                continue
            }
            val rawSeq = fields[seqIdx].trim()
            if (isOnTrac && rawSeq.isNotEmpty() && sourceNumbers[offset] == null) {
                errors.add("$label: invalid stop number.")
                continue
            }
            val seq = when {
                fileOrder -> offset + 1
                rankedOrder -> ranks[offset]
                else -> rawSeq.toIntOrNull()
            }
            val rawAddress = fields[addrIdx].trim()
            if (seq == null || seq <= 0 || rawAddress.isEmpty()) {
                errors.add("$label: positive sequence and address required.")
                continue
            }
            if (!seenSeq.add(seq)) {
                errors.add("$label: duplicate sequence $seq.")
                continue
            }
            val suppliedUnit = if (unitIdx >= 0) fields[unitIdx].trim() else ""
            val parsedAddr = resolver.resolve(rawAddress, suppliedUnit)
            if (parsedAddr.hasConflictingUnit) {
                errors.add("$label: address and unit columns name different apartments.")
                continue
            }
            val notes = if (notesIdx >= 0) fields[notesIdx].trim() else ""
            stops.add(
                DeliveryStop(
                    sequence = seq,
                    address = parsedAddr.address,
                    unit = parsedAddr.unit,
                    notes = notes,
                    sourceStopLabel = if (isOnTrac && rawSeq.isNotEmpty()) rawSeq else null
                )
            )
        }
        return RouteImport(stops = stops, errors = errors, warnings = warnings)
    }
}
