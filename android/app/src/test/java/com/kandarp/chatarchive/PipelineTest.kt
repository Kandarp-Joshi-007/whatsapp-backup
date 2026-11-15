package com.kandarp.chatarchive

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.time.ZoneId
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class PipelineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = "0123456789abcdef".repeat(4)
    private fun res(p: String) = File(javaClass.classLoader!!.getResource("fixture/$p")!!.toURI())
    private val expectedDb by lazy { res("msgstore.db").readBytes() }

    private fun decrypt(f: File): ByteArray {
        val out = tmp.newFile()
        Crypt15.decryptToFile({ f.inputStream() }, key, out)
        return out.readBytes()
    }

    @Test fun decryptsPythonFixture() = assertArrayEquals(expectedDb, decrypt(res("WhatsApp/Databases/msgstore.db.crypt15")))

    @Test fun decryptsFileMadeByWaCryptTools() = assertArrayEquals(expectedDb, decrypt(res("ref-wacrypttools.crypt15")))

    /** Independent check of our streaming GCM: encrypt with the JDK's own AES-GCM (16-byte IV). */
    @Test fun matchesJdkGcm() {
        repeat(5) {
            val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val aesKey = Crypt15.deriveBackupKey(Crypt15.parseKey(key))
            val z = ByteArrayOutputStream().also { DeflaterOutputStream(it, Deflater()).use { d -> d.write(expectedDb) } }.toByteArray()
            val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv)); doFinal(z)
            }
            val header = byteArrayOf(0x08, 0x01, 0x1a, 18, 0x0a, 16) + iv
            val file = tmp.newFile().apply { writeBytes(byteArrayOf(header.size.toByte(), 1) + header + ct + ByteArray(16)) }
            assertArrayEquals(expectedDb, decrypt(file))
        }
    }

    @Test fun inc32WrapsOnlyLow32Bits() {
        val c = ByteArray(16) { 0x11 }.also { for (i in 12..15) it[i] = 0xFF.toByte() }
        Crypt15.inc32(c)
        assertTrue(c.copyOfRange(0, 12).all { it == 0x11.toByte() })
        assertTrue(c.copyOfRange(12, 16).all { it == 0.toByte() })
    }

    @Test fun wrongKeyIsReportedClearly() {
        try {
            Crypt15.decryptToFile({ res("WhatsApp/Databases/msgstore.db.crypt15").inputStream() }, "f".repeat(64), tmp.newFile())
            fail("should not decrypt")
        } catch (e: Crypt15.DecryptException) { assertTrue(e.message!!.contains("key")) }
    }

    @Test fun keyWithSpaces() = assertArrayEquals(Crypt15.parseKey(key), Crypt15.parseKey(key.chunked(4).joinToString(" ")))

    @Test fun phoneNumbers() {
        assertEquals("919876543210", Contacts.normalize("+91 98765 43210"))
        assertEquals("919876543210", Contacts.normalize("098765 43210"))
        assertEquals("14155550123", Contacts.normalize("+1 (415) 555-0123"))
    }

    @Test fun readsChatsAndBuildsIndex() {
        val db = tmp.newFile().apply { writeBytes(expectedDb) }
        val chats = MsgStore.read(db).associateBy { it.user }
        assertTrue("status" !in chats)
        val papa = chats.getValue("919876543210")
        assertEquals(8, papa.messages.count { it.kind == "image" })
        val fam = chats.getValue("120363025555555555")
        assertTrue(fam.isGroup && fam.subject == "Family ❤️")
        assertTrue(fam.messages.any { it.sender == "919812345678" }) // LID resolved to Mom

        val out = tmp.newFolder("index")
        val names = mapOf("919876543210" to "Papa", "919812345678" to "Mom")
        val stubThumb = Thumbs.Maker { _, _, _ -> byteArrayOf(1, 2, 3) }
        IndexBuilder(res("WhatsApp"), names, mapOf("Media/WhatsApp Video/Sent/VID-20250121-WA0001.mp4" to "DRIVEID"),
            stubThumb, null, ZoneId.of("Asia/Kolkata")).build(MsgStore.read(db), out)

        val man = JSONObject(File(out, "manifest.json").readText())
        val chatsJ = man.getJSONArray("chats")
        val papaJ = (0 until chatsJ.length()).map { chatsJ.getJSONObject(it) }.first { it.getString("name") == "Papa" }
        // same chat id as the Python indexer, so both produce interchangeable archives
        assertEquals(IndexBuilder.chatId("919876543210@s.whatsapp.net"), papaJ.getString("id"))
        assertEquals(8, papaJ.getJSONObject("counts").getInt("image"))
        val jan = JSONObject(File(out, "chat-${papaJ.getString("id")}-2025-01.json").readText()).getJSONArray("messages")
        val msgs = (0 until jan.length()).map { jan.getJSONObject(it) }
        assertEquals(6, msgs.count { it.getString("k") == "image" })
        assertEquals("DRIVEID", msgs.first { it.getString("k") == "video" }.getJSONObject("m").getString("d"))
        val feb = JSONObject(File(out, "chat-${papaJ.getString("id")}-2025-02.json").readText()).getJSONArray("messages")
        val gone = (0 until feb.length()).map { feb.getJSONObject(it) }.first { it.getString("k") == "image" }.getJSONObject("m")
        assertEquals(1, gone.getInt("miss")) // deleted from phone, never uploaded
        assertTrue(gone.has("th"))           // but WhatsApp's own preview survives
    }
}
