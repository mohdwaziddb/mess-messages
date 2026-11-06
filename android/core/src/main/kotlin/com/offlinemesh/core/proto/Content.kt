package com.offlinemesh.core.proto

/**
 * Application content. Only ever exists as plaintext inside the end-to-end layer
 * (Double Ratchet session or Sender Key group session). Never put on the wire unencrypted.
 */
sealed class Content {
    data class Text(val msgId: String, val sentAt: Long, val text: String) : Content()

    /** kind: [RECEIPT_DELIVERED] or [RECEIPT_READ]. */
    data class Receipt(val kind: Int, val msgIds: List<String>) : Content()

    data class Profile(val name: String) : Content()

    /** A signed group state change (see GroupOp in engine). */
    data class GroupOp(val signedOp: ByteArray) : Content()

    /** Sender-key distribution for a group epoch, sent over the pairwise session. */
    data class SenderKey(val groupId: String, val epoch: Int, val distribution: ByteArray) : Content()

    data class GroupText(val groupId: String, val epoch: Int, val msgId: String, val sentAt: Long, val text: String) : Content()

    /** File header. groupId empty = private chat. Chunks follow as [AttachmentChunk]. */
    data class Attachment(
        val groupId: String, val epoch: Int, val msgId: String, val sentAt: Long,
        val name: String, val mime: String, val size: Long, val sha256: ByteArray, val chunkCount: Int,
    ) : Content()

    data class AttachmentChunk(val groupId: String, val epoch: Int, val msgId: String, val index: Int, val data: ByteArray) : Content()

    /** Sent by a QR scanner to prove it read the one-time token shown on the other screen. */
    data class QrClaim(val token: ByteArray) : Content()

    companion object {
        const val RECEIPT_DELIVERED = 1
        const val RECEIPT_READ = 2
        const val MAX_TEXT = 8_000
        const val MAX_RECEIPT_IDS = 200
        const val ATTACHMENT_CHUNK = 32 * 1024
        const val MAX_ATTACHMENT = 8L * 1024 * 1024
        const val MAX_ATTACHMENT_CHUNKS = (MAX_ATTACHMENT / ATTACHMENT_CHUNK).toInt()

        private const val T_TEXT = 1
        private const val T_RECEIPT = 2
        private const val T_PROFILE = 3
        private const val T_GROUP_OP = 4
        private const val T_SENDER_KEY = 5
        private const val T_GROUP_TEXT = 6
        private const val T_ATTACHMENT = 7
        private const val T_ATTACHMENT_CHUNK = 8
        private const val T_QR_CLAIM = 9

        fun encode(c: Content): ByteArray {
            val w = WireWriter(256)
            when (c) {
                is Text -> w.u8(T_TEXT).str(c.msgId).u64(c.sentAt).str(c.text)
                is Receipt -> { w.u8(T_RECEIPT).u8(c.kind).u16(c.msgIds.size); c.msgIds.forEach { w.str(it) } }
                is Profile -> w.u8(T_PROFILE).str(c.name)
                is GroupOp -> w.u8(T_GROUP_OP).bytes32(c.signedOp)
                is SenderKey -> w.u8(T_SENDER_KEY).str(c.groupId).u32(c.epoch.toLong()).bytes32(c.distribution)
                is GroupText -> w.u8(T_GROUP_TEXT).str(c.groupId).u32(c.epoch.toLong()).str(c.msgId).u64(c.sentAt).str(c.text)
                is Attachment -> w.u8(T_ATTACHMENT).str(c.groupId).u32(c.epoch.toLong()).str(c.msgId).u64(c.sentAt)
                    .str(c.name).str(c.mime).u64(c.size).bytes16(c.sha256).u32(c.chunkCount.toLong())
                is AttachmentChunk -> w.u8(T_ATTACHMENT_CHUNK).str(c.groupId).u32(c.epoch.toLong()).str(c.msgId)
                    .u32(c.index.toLong()).bytes32(c.data)
                is QrClaim -> w.u8(T_QR_CLAIM).bytes16(c.token)
            }
            return w.toByteArray()
        }

        fun decode(b: ByteArray): Content {
            val r = WireReader(b)
            val c = when (r.u8()) {
                T_TEXT -> Text(r.str(64), r.u64(), r.str(MAX_TEXT))
                T_RECEIPT -> {
                    val kind = r.u8(); val n = r.u16()
                    if (n > MAX_RECEIPT_IDS) throw MalformedException("too many receipts")
                    Receipt(kind, List(n) { r.str(64) })
                }
                T_PROFILE -> Profile(r.str(64))
                T_GROUP_OP -> GroupOp(r.bytes32(32 * 1024))
                T_SENDER_KEY -> SenderKey(r.str(64), r.u32().toInt(), r.bytes32(4096))
                T_GROUP_TEXT -> GroupText(r.str(64), r.u32().toInt(), r.str(64), r.u64(), r.str(MAX_TEXT))
                T_ATTACHMENT -> Attachment(
                    r.str(64), r.u32().toInt(), r.str(64), r.u64(), r.str(256), r.str(128), r.u64(), r.bytes16(),
                    r.u32().toInt(),
                ).also {
                    if (it.size > MAX_ATTACHMENT || it.chunkCount > MAX_ATTACHMENT_CHUNKS || it.sha256.size != 32)
                        throw MalformedException("attachment bounds")
                }
                T_ATTACHMENT_CHUNK -> AttachmentChunk(r.str(64), r.u32().toInt(), r.str(64), r.u32().toInt(), r.bytes32(ATTACHMENT_CHUNK))
                T_QR_CLAIM -> QrClaim(r.bytes16()).also { if (it.token.size != 16) throw MalformedException("qr token") }
                else -> throw MalformedException("unknown content type")
            }
            r.expectEnd()
            return c
        }
    }
}
