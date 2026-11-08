package com.tarslauncher.app

import com.tarslauncher.app.data.model.GridSize
import org.junit.Assert.*
import org.junit.Test

class GridSizeTest {

    @Test
    fun testDefaultGridSize() {
        val default = GridSize.DEFAULT
        assertEquals(4, default.columns)
        assertEquals(3, default.rows)
        assertEquals(12, default.totalCells)
        assertEquals("4x3", default.serialize())
        assertEquals("4x3", default.toString())
    }

    @Test
    fun testFromStringValid() {
        val g1 = GridSize.fromString("5x4")
        assertEquals(5, g1.columns)
        assertEquals(4, g1.rows)
        assertEquals(20, g1.totalCells)

        val g2 = GridSize.fromString("3X2")
        assertEquals(3, g2.columns)
        assertEquals(2, g2.rows)

        val g3 = GridSize.fromString("2*4")
        assertEquals(2, g3.columns)
        assertEquals(4, g3.rows)
    }

    @Test
    fun testFromStringInvalidFallsBackToDefault() {
        assertEquals(GridSize.DEFAULT, GridSize.fromString(null))
        assertEquals(GridSize.DEFAULT, GridSize.fromString(""))
        assertEquals(GridSize.DEFAULT, GridSize.fromString("invalid"))
        assertEquals(GridSize.DEFAULT, GridSize.fromString("0x3"))
        assertEquals(GridSize.DEFAULT, GridSize.fromString("-1x4"))
    }

    @Test
    fun oversizedAndOverflowingGridsAreRejected() {
        for (value in listOf("1000000x1000", "65536x65536", "6x1", "1x6", "2147483648x1")) {
            assertNull(GridSize.parse(value))
            assertEquals(GridSize.DEFAULT, GridSize.fromString(value))
        }
        assertTrue(runCatching { GridSize(Int.MAX_VALUE, Int.MAX_VALUE) }.isFailure)
        assertEquals(25, GridSize(5, 5).totalCells)
    }
}
