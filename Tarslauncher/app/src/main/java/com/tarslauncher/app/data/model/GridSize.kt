package com.tarslauncher.app.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class GridSize(
    val columns: Int,
    val rows: Int
) {
    init {
        require(columns in 1..MAX_COLUMNS) { "Columns must be between 1 and $MAX_COLUMNS" }
        require(rows in 1..MAX_ROWS) { "Rows must be between 1 and $MAX_ROWS" }
    }

    val totalCells: Int
        get() = columns * rows

    fun serialize(): String = "${columns}x${rows}"

    override fun toString(): String = serialize()

    companion object {
        const val MAX_COLUMNS = 5
        const val MAX_ROWS = 5
        val DEFAULT = GridSize(columns = 4, rows = 3)

        fun fromString(value: String?): GridSize = parse(value) ?: DEFAULT

        fun parse(value: String?): GridSize? {
            if (value.isNullOrBlank()) return null
            val delim = value.indexOfFirst { it == 'x' || it == 'X' || it == '*' || it == ',' }
            if (delim == -1) return null
            return try {
                val cols = value.substring(0, delim).trim().toInt()
                val rows = value.substring(delim + 1).trim().toInt()
                GridSize(cols, rows)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
