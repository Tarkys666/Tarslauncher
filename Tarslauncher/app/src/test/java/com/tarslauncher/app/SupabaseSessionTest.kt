package com.tarslauncher.app

import com.tarslauncher.app.data.sync.SupabaseSession
import org.junit.Assert.*
import org.junit.Test

class SupabaseSessionTest {
    private val response = """{
        "access_token":"user-access-token", "refresh_token":"rotating-refresh-token",
        "expires_in":3600,
        "user":{"id":"11111111-1111-4111-8111-111111111111", "email":"test@example.com",
        "is_anonymous":false,"user_metadata":{"full_name":"Test User"}}
    }"""

    @Test fun sessionSurvivesRestartWithoutExtendingItsExpiry() {
        val session = SupabaseSession.fromJson(response, nowSeconds = 1000)
        assertEquals(4600L, session.expiresAt)
        assertEquals(session, SupabaseSession.fromJson(session.toJson(), nowSeconds = 4000))
        assertFalse(session.user.isAnonymous)
        assertEquals("Test User", session.user.displayName)
        assertFalse(session.needsRefresh(4539))
        assertTrue(session.needsRefresh(4540))
        assertTrue(session.needsRefresh(5000))
    }

    @Test fun rejectsLegacyEmailIdentityAndIncompleteSessions() {
        val invalid = listOf(
            response.replace("11111111-1111-4111-8111-111111111111", "google_test@example.com"),
            response.replace("user-access-token", ""),
            response.replace("rotating-refresh-token", ""),
            response.replace("\"is_anonymous\":false", "\"is_anonymous\":true"),
            """{"google_uid":"google_test@example.com"}"""
        )
        invalid.forEach { assertTrue(runCatching { SupabaseSession.fromJson(it) }.isFailure) }
    }
}
