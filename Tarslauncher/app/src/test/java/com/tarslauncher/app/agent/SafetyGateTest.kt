package com.tarslauncher.app.agent

import com.tarslauncher.app.agent.domain.model.AgentAction
import com.tarslauncher.app.agent.domain.safety.SafetyGate
import org.junit.Assert.*
import org.junit.Test

class SafetyGateTest {

    private val safetyGate = SafetyGate()

    @Test
    fun `detects dangerous delete button and requires confirmation`() {
        val screenDump = """
            PAKET: com.example.app
            [1] "Geri" <tık>
            [2] "Profili Düzenle" <tık>
            [3] "Hesabı Sil" <tık>
        """.trimIndent()

        val action = AgentAction.Tap("Hesap silme butonuna tıklanıyor", 3)
        val (needsConfirm, prompt) = safetyGate.evaluate(action, screenDump)

        assertTrue(needsConfirm)
        assertTrue(prompt.contains("Hesabı Sil"))
    }

    @Test
    fun `detects dangerous payment action and requires confirmation`() {
        val screenDump = """
            PAKET: com.example.shop
            [5] "Sepeti Onayla" <tık>
            [6] "Hemen Öde" <tık>
        """.trimIndent()

        val action = AgentAction.Tap("Ödeme yapılıyor", 6)
        val (needsConfirm, _) = safetyGate.evaluate(action, screenDump)

        assertTrue(needsConfirm)
    }

    @Test
    fun `harmless search button does not trigger false positive`() {
        val screenDump = """
            PAKET: com.google.android.youtube
            [1] "Arama Çubuğu" <tık>
            [2] "Karar Ver" <tık>
            [3] "Garantili Ürünler" <tık>
        """.trimIndent()

        val action = AgentAction.Tap("Arama çubuğuna tıklanıyor", 1)
        val (needsConfirm, prompt) = safetyGate.evaluate(action, screenDump)

        assertFalse("Arama kelimesi 'ara' ile yanlış eşleşmemeli", needsConfirm)
        assertTrue(prompt.isEmpty())
    }

    @Test
    fun `opening banking app requires confirmation`() {
        val action = AgentAction.OpenApp("Banka uygulamasını açıyorum", "Ziraat Mobil Bankacılık")
        val (needsConfirm, prompt) = safetyGate.evaluate(action, "")

        assertTrue(needsConfirm)
        assertTrue(prompt.contains("Ziraat Mobil Bankacılık"))
    }

    @Test
    fun `harmless navigation passes without confirmation`() {
        val screenDump = """
            PAKET: com.android.settings
            [1] "Ağ ve İnternet" <tık>
            [2] "Bağlı Cihazlar" <tık>
            [3] "Uygulamalar" <tık>
        """.trimIndent()

        val action = AgentAction.Tap("Ağ ayarlarına gidiliyor", 1)
        val (needsConfirm, prompt) = safetyGate.evaluate(action, screenDump)

        assertFalse(needsConfirm)
        assertTrue(prompt.isEmpty())
    }
}
