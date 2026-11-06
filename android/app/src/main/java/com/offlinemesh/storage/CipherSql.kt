package com.offlinemesh.storage

import android.database.Cursor
import com.offlinemesh.core.db.Row
import com.offlinemesh.core.db.Sql
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** [Sql] over a SQLCipher database (AES-256 page encryption, raw key from [KeyWrap]). */
class CipherSql(private val db: SQLiteDatabase) : Sql {
    private fun norm(args: Array<out Any?>): Array<Any?> = Array(args.size) { i ->
        when (val a = args[i]) { is Boolean -> if (a) 1L else 0L; is Int -> a.toLong(); else -> a }
    }

    override fun exec(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, norm(args))
    }

    override fun query(sql: String, vararg args: Any?): List<Row> {
        db.rawQuery(sql, *norm(args)).use { c ->
            val out = ArrayList<Row>(c.count)
            val names = c.columnNames
            while (c.moveToNext()) {
                val m = HashMap<String, Any?>(names.size)
                for (i in names.indices) m[names[i]] = when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                    else -> c.getString(i)
                }
                out += Row(m)
            }
            return out
        }
    }

    override fun <T> tx(block: () -> T): T {
        db.beginTransaction()
        try { val r = block(); db.setTransactionSuccessful(); return r } finally { db.endTransaction() }
    }

    companion object {
        fun open(path: String, key: ByteArray): CipherSql {
            System.loadLibrary("sqlcipher")
            val db = SQLiteDatabase.openDatabase(path, key, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null, null)
            db.rawQuery("PRAGMA secure_delete = ON").use { it.moveToFirst() }
            return CipherSql(db)
        }
    }
}
