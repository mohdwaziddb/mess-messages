package com.offlinemesh

import android.content.Context
import android.util.Log
import com.offlinemesh.core.db.Schema
import com.offlinemesh.core.engine.Engine
import com.offlinemesh.storage.CipherSql
import com.offlinemesh.storage.EncryptedBlobStore
import com.offlinemesh.storage.KeyWrap
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Process-wide owner of the protocol engine. All engine access is serialized on [exec]
 * (the engine is intentionally single-threaded). UI, BLE callbacks and the service all post here.
 */
object MeshCore {
    private const val TAG = "MeshCore"
    val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mesh-engine") }
    private lateinit var appContext: Context
    @Volatile private var engine: Engine? = null
    lateinit var blobs: EncryptedBlobStore
        private set
    var hardwareBackedKeys = false
        private set

    /** (kind, id) change listeners: RN bridge, notifications. Called on the engine thread. */
    val listeners = CopyOnWriteArraySet<(String, String?) -> Unit>()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        exec.execute { open() }
        exec.scheduleWithFixedDelay({ runCatching { engine?.tick() }.onFailure { Log.w(TAG, "tick failed: ${it.javaClass.simpleName}") } },
            10, 10, TimeUnit.SECONDS)
    }

    private fun open() {
        val wrap = KeyWrap(appContext)
        val dbKey = wrap.secret("db")
        val fileKey = wrap.secret("files")
        hardwareBackedKeys = wrap.isHardwareBacked("db")
        val path = File(appContext.noBackupFilesDir, "mesh.db").path
        val sql = CipherSql.open(path, dbKey)
        dbKey.fill(0)
        Schema.migrate(sql)
        blobs = EncryptedBlobStore(File(appContext.noBackupFilesDir, "attachments"), fileKey)
        engine = Engine(sql, blobs, { kind, id -> listeners.forEach { l -> runCatching { l(kind, id) } } })
    }

    /** Run [block] on the engine thread. */
    fun <T> submit(block: (Engine) -> T): Future<T> = exec.submit<T> { block(engine ?: error("engine not ready")) }

    fun post(block: (Engine) -> Unit) { exec.execute { engine?.let { e -> runCatching { block(e) }.onFailure { Log.w(TAG, "engine task failed: ${it.javaClass.simpleName}: ${it.message}") } } } }

    /** Only valid when already running on [exec]. */
    fun engineOnThread(): Engine? = engine

    fun context(): Context = appContext
}
