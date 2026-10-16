package com.tarslauncher.app.agent

import com.tarslauncher.app.agent.domain.model.AgentAction
import com.tarslauncher.app.agent.domain.parser.ActionParser
import org.junit.Assert.*
import org.junit.Test

class ActionParserTest {

    @Test
    fun `parse clean tap action JSON successfully`() {
        val raw = """{"thought": "Ayarlar simgesine dokunuyorum", "action": "tap", "args": {"id": 12}}"""
        val action = ActionParser.parse(raw)

        assertNotNull(action)
        assertTrue(action is AgentAction.Tap)
        val tap = action as AgentAction.Tap
        assertEquals("Ayarlar simgesine dokunuyorum", tap.thought)
        assertEquals(12, tap.targetId)
    }

    @Test
    fun `parse action wrapped in markdown code fence`() {
        val raw = """
            Tabii, hedef işlemi başlatıyorum.
            ```json
            {
                "thought": "Arama kutusuna metin giriyorum",
                "action": "type",
                "args": {
                    "id": 4,
                    "text": "Antigravity"
                }
            }
            ```
        """.trimIndent()

        val action = ActionParser.parse(raw)
        assertNotNull(action)
        assertTrue(action is AgentAction.TypeText)
        val typeAction = action as AgentAction.TypeText
        assertEquals(4, typeAction.targetId)
        assertEquals("Antigravity", typeAction.text)
    }

    @Test
    fun `parse open_app action`() {
        val raw = """{"thought": "WhatsApp uygulamasını açıyorum", "action": "open_app", "args": {"name": "WhatsApp"}}"""
        val action = ActionParser.parse(raw)

        assertNotNull(action)
        assertTrue(action is AgentAction.OpenApp)
        val openApp = action as AgentAction.OpenApp
        assertEquals("WhatsApp", openApp.appName)
    }

    @Test
    fun `parse scroll down action`() {
        val raw = """{"thought": "Daha fazla içerik görmek için kaydırıyorum", "action": "scroll", "args": {"direction": "down"}}"""
        val action = ActionParser.parse(raw)

        assertNotNull(action)
        assertTrue(action is AgentAction.Scroll)
        val scroll = action as AgentAction.Scroll
        assertEquals(AgentAction.Scroll.Direction.DOWN, scroll.direction)
    }

    @Test
    fun `parse done action`() {
        val raw = """{"thought": "Hedef tamamlandı", "action": "done", "args": {"message": "Alarm başarıyla kuruldu."}}"""
        val action = ActionParser.parse(raw)

        assertNotNull(action)
        assertTrue(action is AgentAction.Done)
        val done = action as AgentAction.Done
        assertEquals("Alarm başarıyla kuruldu.", done.summary)
    }

    @Test
    fun `malformed JSON returns null safely`() {
        assertNull(ActionParser.parse("Geçersiz metin"))
        assertNull(ActionParser.parse("{ bozuk json "))
        assertNull(ActionParser.parse(""))
    }

    @Test
    fun `operational signature is invariant to thought text`() {
        val tap1 = AgentAction.Tap("Birinci deneme", 15)
        val tap2 = AgentAction.Tap("İkinci deneme aynı butona", 15)

        assertEquals("Tap(15)", tap1.toOperationalSignature())
        assertEquals(tap1.toOperationalSignature(), tap2.toOperationalSignature())
    }

    @Test
    fun `parsePayload extracts reflection, replan, and strips think tags`() {
        val raw = """
            <think>
            Önceki tıklama ekranda bir değişiklik yaratmadı {id: 12}. Yeni bir strateji kurmalıyım.
            </think>
            {
                "reflection": "Önceki adımda arama butonu açılmadı.",
                "thought": "Doğrudan URL açma aracını kullanacağım.",
                "action": "open_url",
                "args": {"url": "github.com"},
                "replan": ["URL'yi aç", "Sayfanın yüklenmesini doğrula"]
            }
        """.trimIndent()

        val payload = ActionParser.parsePayload(raw)
        assertNotNull(payload)
        assertEquals("Önceki adımda arama butonu açılmadı.", payload!!.reflection)
        assertEquals("Doğrudan URL açma aracını kullanacağım.", payload.action.thought)
        assertTrue(payload.action is AgentAction.OpenUrl)
        assertEquals("github.com", (payload.action as AgentAction.OpenUrl).url)
        assertEquals(listOf("URL'yi aç", "Sayfanın yüklenmesini doğrula"), payload.replan)
    }

    @Test
    fun `parsePlan handles both string arrays and object arrays`() {
        val rawStrings = """{"plan": ["Ayarları aç", "Wi-Fi menüsüne gir", "Durumu kontrol et"]}"""
        val steps1 = ActionParser.parsePlan(rawStrings)
        assertNotNull(steps1)
        assertEquals(3, steps1!!.size)
        assertEquals("Ayarları aç", steps1[0])

        val rawObjects = """
            <think>Plan oluşturuluyor...</think>
            {"steps": [{"title": "Uygulamayı başlat"}, {"step": "Mesaj gönder"}]}
        """.trimIndent()
        val steps2 = ActionParser.parsePlan(rawObjects)
        assertEquals(listOf("Uygulamayı başlat", "Mesaj gönder"), steps2)
    }

    @Test
    fun `parse new Codex Hermes tools long_press and clipboard_set`() {
        val longPressRaw = """{"thought": "Mesaja uzun basıyorum", "action": "long_press", "args": {"id": 42}}"""
        val lpAction = ActionParser.parse(longPressRaw)
        assertTrue(lpAction is AgentAction.LongPress)
        assertEquals(42, (lpAction as AgentAction.LongPress).targetId)

        val clipRaw = """{"thought": "Metni panoya kopyalıyorum", "action": "clipboard_set", "args": {"text": "Merhaba Dünya"}}"""
        val clipAction = ActionParser.parse(clipRaw)
        assertTrue(clipAction is AgentAction.ClipboardSet)
        assertEquals("Merhaba Dünya", (clipAction as AgentAction.ClipboardSet).text)
    }
}
