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

    @Test
    fun `detects Turkish uppercase SIL and long press on delete`() {
        val screenDump = """
            PAKET: com.example.files
            [8] "SİL" <tık,uzun-tık>
        """.trimIndent()

        val tapAction = AgentAction.Tap("Dosyayı siliyorum", 8)
        assertTrue(safetyGate.evaluate(tapAction, screenDump).first)

        val longPressAction = AgentAction.LongPress("Dosyaya uzun basıyorum", 8)
        assertTrue(safetyGate.evaluate(longPressAction, screenDump).first)
    }

    @Test
    fun `harmless Tamam or Onayla dialog button does not trigger false positive without risky context`() {
        val screenDump = """
            PAKET: com.android.deskclock
            [10] "Alarm Kuruldu"
            [11] "Tamam" <tık>
        """.trimIndent()

        val action = AgentAction.Tap("Tamam'a basıyorum", 11)
        val (needsConfirm, _) = safetyGate.evaluate(action, screenDump)
        assertFalse("Normal 'Tamam' butonu gereksiz güvenlik onayı istememeli", needsConfirm)
    }

    @Test
    fun `isSensitiveAppPackage blocks Turkish banking, fintech and password managers`() {
        val sensitivePkgs = listOf(
            "com.garanti.cepsubesi",
            "com.ykb.android",
            "com.isisortaklik.iscep",
            "com.ziraat.ziraatmobil",
            "com.mobile.papara",
            "com.getmidas.app",
            "tr.gov.turkiye.edevlet.kapisi",
            "com.x8bit.bitwarden",
            "com.onepassword.android"
        )
        sensitivePkgs.forEach { pkg ->
            assertTrue("Paket engellenmeliydi: $pkg", safetyGate.isSensitiveAppPackage(pkg))
        }

        val harmlessPkgs = listOf(
            "com.android.calculator2",
            "com.example.notes",
            "com.spotify.music"
        )
        harmlessPkgs.forEach { pkg ->
            assertFalse("Zararsız paket engellenmemeliydi: $pkg", safetyGate.isSensitiveAppPackage(pkg))
        }
    }

    @Test
    fun `NoteAction clear and delete require explicit user confirmation`() {
        val clearAction = AgentAction.NoteAction("Tüm notları temizliyorum", action = "clear")
        val (clearNeedsConfirm, clearPrompt) = safetyGate.evaluate(clearAction, "")
        assertTrue(clearNeedsConfirm)
        assertTrue(clearPrompt.contains("TÜM notlar"))

        val deleteAction = AgentAction.NoteAction("Toplantı notunu siliyorum", action = "delete", title = "Toplantı")
        val (deleteNeedsConfirm, deletePrompt) = safetyGate.evaluate(deleteAction, "")
        assertTrue(deleteNeedsConfirm)
        assertTrue(deletePrompt.contains("Toplantı"))

        val addAction = AgentAction.NoteAction("Yeni not ekliyorum", action = "add", title = "Alışveriş", text = "Süt, ekmek")
        val (addNeedsConfirm, _) = safetyGate.evaluate(addAction, "")
        assertFalse("Not ekleme işlemi kullanıcı onayı istememeli", addNeedsConfirm)
    }
}
