package com.offlinemesh.core

import com.offlinemesh.core.db.Schema
import com.offlinemesh.core.db.one
import com.offlinemesh.core.engine.EngineException
import com.offlinemesh.core.engine.contacts
import com.offlinemesh.core.engine.conversations
import com.offlinemesh.core.engine.profile
import com.offlinemesh.core.proto.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

    @Test fun profilePhotoTravelsEncryptedUpdatesAndCanBeRemoved() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        // Set before meeting: delivered on first connect.
        a.setMyAvatar(jpeg)
        assertTrue((a.profile()["avatar"] as String).startsWith("data:image/jpeg;base64,"))
        net.connect("A", "B")
        assertTrue((b.contacts().single()["avatar"] as String).startsWith("data:image/jpeg;base64,"))
        assertTrue((b.conversations().firstOrNull()?.get("avatar") ?: b.contacts().single()["avatar"]) != null)
        // Never on the wire in the clear.
        val wire = net.captured.joinToString("") { String(it.third, Charsets.ISO_8859_1) }
        assertTrue(!wire.contains(String(jpeg, Charsets.ISO_8859_1)))

        // Removed while connected: pushed immediately.
        a.setMyAvatar(null); net.pump()
        assertNull(b.contacts().single()["avatar"])

        // Changed while apart: delivered on the next connection, and only once.
        net.disconnect("A", "B")
        a.setMyAvatar(jpeg + byteArrayOf(9))
        net.connect("A", "B")
        assertTrue(b.contacts().single()["avatar"] != null)
        val before = net.captured.size
        net.disconnect("A", "B"); net.connect("A", "B")
        val reconnectBytes = net.captured.drop(before).sumOf { it.third.size }
        assertTrue("photo must not be resent when unchanged ($reconnectBytes B)", reconnectBytes < 8_000)
    }

    @Test fun invalidPhotosRejected() {
        val net = Net(); val a = net.node("A")
        assertThrows(EngineException::class.java) { a.setMyAvatar(byteArrayOf(1, 2, 3, 4, 5)) }
        assertThrows(EngineException::class.java) { a.setMyAvatar(jpeg + ByteArray(Content.MAX_AVATAR)) }
        // A peer can't push something that isn't an image either.
        assertThrows(Exception::class.java) { Content.decode(Content.encode(Content.Avatar(byteArrayOf(1, 2, 3, 4)))) }
    }

    @Test fun pinnedChatsComeFirst() {
        val net = Net(); val a = net.node("A"); val b = net.node("B"); val c = net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        val ab = a.directConversation(b.myId); val ac = a.directConversation(c.myId)
        a.sendText(ab, "older"); net.now += 1000; a.sendText(ac, "newer"); net.pump()
        assertEquals(listOf(ac, ab), a.conversations().map { it["id"] })
        a.setPinned(ab, true)
        assertEquals(listOf(ab, ac), a.conversations().map { it["id"] })
        assertEquals(true, a.conversations().first()["pinned"])
        a.setPinned(ab, false)
        assertEquals(listOf(ac, ab), a.conversations().map { it["id"] })
        assertThrows(EngineException::class.java) { a.setPinned("d:nobody", true) }
    }

    @Test fun blockedShowsOnConversationAndStopsSending() {
        val net = Net(); val a = net.node("A"); val b = net.node("B")
        net.connect("A", "B")
        val ab = a.directConversation(b.myId)
        a.block(b.myId, true)
        assertEquals(true, a.conversations().single { it["id"] == ab }["blocked"])
        assertThrows(EngineException::class.java) { a.sendText(ab, "x") }
        a.block(b.myId, false)
        assertEquals(false, a.conversations().single { it["id"] == ab }["blocked"])
    }

    @Test fun upgradeFromV1KeepsDataAndAddsColumns() {
        // A database exactly as version 0.1.4 left it (schema V1, with data), then opened by the new app.
        val file = java.io.File.createTempFile("mesh-v1", ".db").apply { deleteOnExit() }
        val url = "jdbc:sqlite:${file.path}"
        java.sql.DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE schema_version (v INTEGER NOT NULL)")
                st.execute("INSERT INTO schema_version (v) VALUES (1)")
                Schema.migrations[0].forEach { st.execute(it) }
                st.execute("INSERT INTO conversations (id, kind, title) VALUES ('d:x', 'direct', 'X')")
            }
        }
        val sql = JdbcSql(url)   // runs the pending migrations
        assertEquals("X", sql.one("SELECT title FROM conversations WHERE id='d:x'")!!.str("title"))
        assertEquals(Schema.version, sql.one("SELECT v FROM schema_version")!!.int("v"))
        assertEquals(0, sql.one("SELECT pinned FROM conversations WHERE id='d:x'")!!.int("pinned"))
        assertNull(sql.one("SELECT avatar FROM contacts LIMIT 1"))
    }
}
