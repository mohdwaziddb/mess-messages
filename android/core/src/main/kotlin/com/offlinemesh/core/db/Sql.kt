package com.offlinemesh.core.db

/**
 * Tiny SQL abstraction so the same repository code runs on Android (SQLCipher) and in JVM tests
 * (sqlite-jdbc). Arguments: String, Long, Int, Boolean, ByteArray or null.
 */
interface Sql {
    fun exec(sql: String, vararg args: Any?)
    fun query(sql: String, vararg args: Any?): List<Row>
    fun <T> tx(block: () -> T): T
}

class Row(private val v: Map<String, Any?>) {
    fun str(c: String): String = v[c]?.toString() ?: error("null column $c")
    fun strOrNull(c: String): String? = v[c]?.toString()
    fun long(c: String): Long = (v[c] as? Number)?.toLong() ?: 0L
    fun longOrNull(c: String): Long? = (v[c] as? Number)?.toLong()
    fun int(c: String): Int = long(c).toInt()
    fun bool(c: String): Boolean = long(c) != 0L
    fun blob(c: String): ByteArray = v[c] as? ByteArray ?: error("null blob $c")
    fun blobOrNull(c: String): ByteArray? = v[c] as? ByteArray
}

fun Sql.one(sql: String, vararg args: Any?): Row? = query(sql, *args).firstOrNull()
