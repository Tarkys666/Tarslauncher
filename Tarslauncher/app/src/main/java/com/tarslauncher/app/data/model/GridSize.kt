package com.tarslauncher.app.data.model

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
            return try {
                val parts = value.split("x", "X", "*", ",")
                if (parts.size == 2) {
                    val cols = parts[0].trim().toInt()
                    val rows = parts[1].trim().toInt()
                    if (cols > 0 && rows > 0) GridSize(cols, rows) else DEFAULT
                } else {
                    DEFAULT
                }
            } catch (e: Exception) {
                DEFAULT
            }
        }
    }
}
