package com.tarslauncher.app

import com.tarslauncher.app.data.model.AppInfo
import com.tarslauncher.app.data.model.PwaInfo
import com.tarslauncher.app.utils.PwaFetcherHelper
import org.junit.Assert.*
import org.junit.Test

class PwaHelperTest {

    @Test
    fun testExtractDomain() {
        assertEquals("chatgpt.com", PwaFetcherHelper.extractDomain("https://chatgpt.com"))
        assertEquals("x.com", PwaFetcherHelper.extractDomain("https://x.com/home"))
        assertEquals("youtube.com", PwaFetcherHelper.extractDomain("https://m.youtube.com"))
        assertEquals("github.com", PwaFetcherHelper.extractDomain("www.github.com"))
        assertEquals("reddit.com", PwaFetcherHelper.extractDomain("reddit.com"))
    }

    @Test
    fun testExtractDefaultTitle() {
        assertEquals("Chatgpt", PwaFetcherHelper.extractDefaultTitle("https://chatgpt.com"))
        assertEquals("X", PwaFetcherHelper.extractDefaultTitle("https://x.com"))
        assertEquals("Github", PwaFetcherHelper.extractDefaultTitle("https://github.com/repository"))
        assertEquals("Notion", PwaFetcherHelper.extractDefaultTitle("notion.so"))
    }

    @Test
    fun testWikipediaDomainAndTitle() {
        assertEquals("wikipedia.org", PwaFetcherHelper.extractDomain("https://www.wikipedia.org"))
        assertEquals("Wikipedia", PwaFetcherHelper.extractDefaultTitle("https://www.wikipedia.org"))
    }

    @Test
    fun testPwaInfoDataModel() {
        val pwa = PwaInfo(
            id = "test_1",
            name = "ChatGPT",
            url = "https://chatgpt.com",
            iconBase64 = null
        )
        assertEquals("test_1", pwa.id)
        assertEquals("ChatGPT", pwa.name)
        assertEquals("https://chatgpt.com", pwa.url)
        assertNull(pwa.iconBase64)
    }

    @Test
    fun testAppInfoPwaMapping() {
        val app = AppInfo(
            name = "ChatGPT",
            packageName = "pwa_test_1",
            icon = null,
            isPwa = true,
            webUrl = "https://chatgpt.com",
            pwaId = "test_1"
        )
        assertTrue(app.isPwa)
        assertEquals("https://chatgpt.com", app.webUrl)
        assertEquals("test_1", app.pwaId)
        assertEquals("pwa_test_1", app.packageName)
    }
}
