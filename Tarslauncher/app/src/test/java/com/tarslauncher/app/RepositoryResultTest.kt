package com.tarslauncher.app

import com.tarslauncher.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class RepositoryResultTest {

    @Test
    fun testSuccessResult() {
        val result: RepositoryResult<String> = RepositoryResult.Success("hello")
        assertTrue(result.isSuccess)
        assertEquals("hello", result.getOrNull())
        assertEquals("hello", result.getOrDefault("default"))
    }

    @Test
    fun testErrorResult() {
        val exception = RuntimeException("Boom")
        val result: RepositoryResult<String> = RepositoryResult.Error(exception, "Custom message")
        assertFalse(result.isSuccess)
        assertNull(result.getOrNull())
        assertEquals("default", result.getOrDefault("default"))
    }

    @Test
    fun testLoadingResult() {
        val result: RepositoryResult<String> = RepositoryResult.Loading
        assertFalse(result.isSuccess)
        assertNull(result.getOrNull())
    }
}
