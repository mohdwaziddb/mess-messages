package com.offlinemesh.core

import com.offlinemesh.core.db.Row
import com.offlinemesh.core.db.Schema
import com.offlinemesh.core.db.Sql
import java.sql.Connection
import java.sql.DriverManager

/** sqlite-jdbc adapter used only by JVM tests (on device the same code runs on SQLCipher). */
class JdbcSql(url: String = "jdbc:sqlite::memory:") : Sql {
    private val conn: Connection = DriverManager.getConnection(url)
    private var depth = 0

    init { Schema.migrate(this) }

    private fun bind(st: java.sql.PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.setObject(i + 1, null)
                is Boolean -> st.setInt(i + 1, if (a) 1 else 0)
                is Int -> st.setLong(i + 1, a.toLong())
                is Long -> st.setLong(i + 1, a)
                is ByteArray -> st.setBytes(i + 1, a)
                else -> st.setString(i + 1, a.toString())
            }
        }
    }

    override fun exec(sql: String, vararg args: Any?) {
        conn.prepareStatement(sql).use { bind(it, args); it.executeUpdate() }
    }

    override fun query(sql: String, vararg args: Any?): List<Row> = conn.prepareStatement(sql).use { st ->
        bind(st, args)
        st.executeQuery().use { rs ->
            val md = rs.metaData
            val out = ArrayList<Row>()
            while (rs.next()) out += Row((1..md.columnCount).associate { md.getColumnLabel(it) to rs.getObject(it) })
            out
        }
    }

    override fun <T> tx(block: () -> T): T {
        if (depth > 0) { depth++; try { return block() } finally { depth-- } }
        conn.autoCommit = false; depth = 1
        try { val r = block(); conn.commit(); return r } catch (e: Throwable) { conn.rollback(); throw e } finally { depth = 0; conn.autoCommit = true }
    }
}
