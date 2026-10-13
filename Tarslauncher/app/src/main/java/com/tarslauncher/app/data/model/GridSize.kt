package com.tarslauncher.app.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class GridSize(
    val columns: Int,
    val rows: Int
) {
    init {
        require(columns > 0) { "Columns must be greater than 0" }
        require(rows > 0) { "Rows must be greater than 0" }
    }

    val totalCells: Int
        get() = columns * rows

    fun serialize(): String = "${columns}x${rows}"

    override fun toString(): String = serialize()

    companion object {
        val DEFAULT = GridSize(columns = 4, rows = 3)

        fun fromString(value: String?): GridSize {
            if (value.isNullOrBlank()) return DEFAULT
            val delim = value.indexOfFirst { it == 'x' || it == 'X' || it == '*' || it == ',' }
            if (delim == -1) return DEFAULT
            return try {
                val cols = value.substring(0, delim).trim().toInt()
                val rows = value.substring(delim + 1).trim().toInt()
                if (cols > 0 && rows > 0) GridSize(cols, rows) else DEFAULT
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}
