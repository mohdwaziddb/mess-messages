package com.offlinemesh.bridge

import android.app.Activity
import android.app.ActivityManager
import com.facebook.react.bridge.BaseActivityEventListener
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.offlinemesh.MeshCore
import com.offlinemesh.core.engine.Engine
import com.offlinemesh.core.engine.EngineException
import com.offlinemesh.core.engine.clearRelayQueue
import com.offlinemesh.core.engine.contacts
import com.offlinemesh.core.engine.conversations
import com.offlinemesh.core.engine.groupDetails
import com.offlinemesh.core.engine.groups
import com.offlinemesh.core.engine.invites
import com.offlinemesh.core.engine.messages
import com.offlinemesh.core.engine.profile
import com.offlinemesh.core.engine.securityEvents
import com.offlinemesh.core.engine.storageInfo
import com.offlinemesh.core.proto.Hex
import com.offlinemesh.service.MeshService
import java.io.File

/**
 * React Native bridge. Exposes commands and read-only views; never returns key material.
 * Every engine call is executed on the single engine thread and resolved asynchronously.
 */
class MeshModule(private val rc: ReactApplicationContext) : ReactContextBaseJavaModule(rc) {
    override fun getName() = "OfflineMesh"

    private var pendingScan: Promise? = null
    private var pendingPick: Promise? = null

    private val activityListener = object : BaseActivityEventListener() {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            when (requestCode) {
                REQ_SCAN -> pendingScan?.let { p ->
                    pendingScan = null
                    val text = data?.getStringExtra("text")
                    if (resultCode == Activity.RESULT_OK && text != null) p.resolve(text) else p.resolve(null)
                }
                REQ_PICK -> pendingPick?.let { p ->
                    pendingPick = null
                    p.resolve(if (resultCode == Activity.RESULT_OK) data?.data?.toString() else null)
                }
            }
        }
    }

    companion object {
        private const val REQ_SCAN = 4101
        private const val REQ_PICK = 4102
    }

    private val changeListener: (String, String?) -> Unit = { kind, id ->
        emit("mesh:change", Arguments.createMap().apply { putString("kind", kind); putString("id", id) })
    }

    init {
        MeshCore.init(rc)
        MeshCore.listeners += changeListener
        rc.addActivityEventListener(activityListener)
        MeshService.onNearbyChanged = { emit("mesh:change", Arguments.createMap().apply { putString("kind", "nearby") }) }
    }

    override fun invalidate() {
        MeshCore.listeners -= changeListener
        super.invalidate()
    }

    private fun emit(event: String, data: WritableMap) {
        if (rc.hasActiveReactInstance()) runCatching { rc.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(event, data) }
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Double) {}

    // ------------------------------------------------------------------ helpers

    private fun call(p: Promise, block: (Engine) -> Any?) {
        MeshCore.exec.execute {
            try {
                // Already on the engine thread.
                val e = MeshCore.engineOnThread() ?: return@execute p.reject("E_NOT_READY", "Secure storage is still opening")
                p.resolve(toJs(block(e)))
            } catch (ex: EngineException) {
                p.reject("E_MESH", ex.message)
            } catch (ex: Exception) {
                p.reject("E_INTERNAL", ex.message ?: ex.javaClass.simpleName)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toJs(v: Any?): Any? = when (v) {
        null, is String, is Boolean -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Double -> v
        is Float -> v.toDouble()
        is Map<*, *> -> Arguments.createMap().also { m -> v.forEach { (k, x) -> putAny(m, k.toString(), toJs(x)) } }
        is List<*> -> Arguments.createArray().also { a -> v.forEach { x -> pushAny(a, toJs(x)) } }
        Unit -> null
        else -> v.toString()
    }

    private fun putAny(m: WritableMap, k: String, v: Any?) = when (v) {
        null -> m.putNull(k); is String -> m.putString(k, v); is Boolean -> m.putBoolean(k, v); is Double -> m.putDouble(k, v)
        is WritableMap -> m.putMap(k, v); is WritableArray -> m.putArray(k, v); else -> m.putString(k, v.toString())
    }

    private fun pushAny(a: WritableArray, v: Any?) = when (v) {
        null -> a.pushNull(); is String -> a.pushString(v); is Boolean -> a.pushBoolean(v); is Double -> a.pushDouble(v)
        is WritableMap -> a.pushMap(v); is WritableArray -> a.pushArray(v); else -> a.pushString(v.toString())
    }

    private fun strings(a: ReadableArray): List<String> = (0 until a.size()).mapNotNull { a.getString(it) }

    // ------------------------------------------------------------------ state & identity

    @ReactMethod fun getState(p: Promise) = call(p) { e ->
        val pm = rc.packageManager
        val t = MeshService.transport
        val ble = t ?: com.offlinemesh.ble.BleTransport(rc) {}
        mapOf(
            "hasIdentity" to e.hasIdentity(),
            "profile" to if (e.hasIdentity()) e.profile() else null,
            "permissionsGranted" to MeshService.hasPermissions(rc),
            "serviceRunning" to MeshService.running,
            "bleSupported" to ble.isSupported(),
            "bleEnabled" to ble.isEnabled(),
            "canAdvertise" to ble.canAdvertise(),
            "visible" to (e.setting("visible", "1") == "1"),
            "autoConnect" to (e.setting("auto_connect", "1") == "1"),
            "readReceipts" to (e.setting("read_receipts", "1") == "1"),
            "relayEnabled" to e.relayEnabled,
            "wifiDirectSupported" to pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
            "wifiAwareSupported" to (Build.VERSION.SDK_INT >= 26 && pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)),
            "hardwareBackedKeys" to MeshCore.hardwareBackedKeys,
            "batteryOptimized" to !(rc.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(rc.packageName),
            "androidVersion" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER,
            "stats" to e.stats(),
        )
    }

    @ReactMethod fun createIdentity(name: String, p: Promise) = call(p) { e -> e.createIdentity(name); e.profile() }
    @ReactMethod fun setMyName(name: String, p: Promise) = call(p) { e -> e.setMyName(name); e.profile() }

    // ------------------------------------------------------------------ service / transport

    @ReactMethod fun requiredPermissions(p: Promise) = p.resolve(Arguments.fromList(MeshService.requiredPermissions()))

    @ReactMethod fun startService(p: Promise) {
        try {
            MeshService.start(rc)
            MeshCore.post { e ->
                MeshService.transport?.let { t ->
                    t.autoConnect = e.setting("auto_connect", "1") == "1"
                    t.setVisible(e.setting("visible", "1") == "1")
                }
            }
            p.resolve(true)
        } catch (e: Exception) { p.reject("E_SERVICE", e.message) }
    }

    @ReactMethod fun stopService(p: Promise) { MeshService.stop(rc); p.resolve(true) }

    @ReactMethod fun setAppForeground(fg: Boolean) { MeshService.appInForeground = fg }

    @ReactMethod fun nearby(p: Promise) = call(p) { e ->
        val t = MeshService.transport ?: return@call emptyList<Any>()
        val connected = e.connectedPeerIds().toSet()
        val contacts = e.contacts().associateBy { it["id"] as String }
        // Authenticated links are shown as people; raw discoveries as "nearby device" until the handshake completes.
        val people = connected.mapNotNull { id -> contacts[id]?.let { c -> c + mapOf("type" to "person") } }
        val linkedAddresses = t.nearby.values.filter { it.state == "connected" }.size
        val devices = t.nearby.values.filter { it.state != "connected" }.sortedByDescending { it.rssi }.map {
            mapOf("type" to "device", "address" to it.address, "ephemeralId" to it.ephemeralId, "rssi" to it.rssi,
                "state" to it.state, "lastSeen" to it.lastSeen)
        }
        mapOf("people" to people, "devices" to devices, "links" to t.linkCount(), "linkedAddresses" to linkedAddresses,
            "scanning" to t.running, "visible" to t.discoverable)
    }

    @ReactMethod fun connectDevice(address: String, p: Promise) {
        val t = MeshService.transport ?: return p.reject("E_SERVICE", "Nearby service is not running")
        MeshCore.exec.execute { t.connectTo(address); p.resolve(true) }
    }

    // ------------------------------------------------------------------ views

    @ReactMethod fun contacts(p: Promise) = call(p) { it.contacts() }
    @ReactMethod fun conversations(p: Promise) = call(p) { it.conversations() }
    @ReactMethod fun messages(convId: String, limit: Double, p: Promise) = call(p) { it.messages(convId, limit.toInt()) }
    @ReactMethod fun groups(p: Promise) = call(p) { it.groups() }
    @ReactMethod fun groupDetails(groupId: String, p: Promise) = call(p) { it.groupDetails(groupId) }
    @ReactMethod fun invites(p: Promise) = call(p) { it.invites() }
    @ReactMethod fun securityEvents(p: Promise) = call(p) { it.securityEvents() }
    @ReactMethod fun stats(p: Promise) = call(p) { it.stats() }
    @ReactMethod fun storageInfo(p: Promise) = call(p) { e -> e.storageInfo() + mapOf("encryptedFileBytes" to MeshCore.blobs.totalBytes()) }

    // ------------------------------------------------------------------ messaging

    @ReactMethod fun openDirect(peerId: String, p: Promise) = call(p) { it.directConversation(peerId) }
    @ReactMethod fun sendText(convId: String, text: String, p: Promise) = call(p) { it.sendText(convId, text) }
    @ReactMethod fun retryMessage(msgId: String, p: Promise) = call(p) { it.retryMessage(msgId); true }
    @ReactMethod fun deleteMessage(msgId: String, p: Promise) = call(p) { it.deleteMessage(msgId); true }
    @ReactMethod fun clearConversation(convId: String, p: Promise) = call(p) { it.clearConversation(convId); true }
    @ReactMethod fun markRead(convId: String, p: Promise) = call(p) { it.markRead(convId); true }

    @ReactMethod fun sendFile(convId: String, uri: String, p: Promise) {
        try {
            val u = Uri.parse(uri)
            val cr = rc.contentResolver
            var name = "file"; var size = -1L
            cr.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                }
            }
            if (size > com.offlinemesh.core.proto.Content.MAX_ATTACHMENT) return p.reject("E_MESH", "File too large (max 8 MB over Bluetooth)")
            val mime = cr.getType(u) ?: "application/octet-stream"
            val data = cr.openInputStream(u)?.use { it.readBytes() } ?: return p.reject("E_MESH", "Could not read file")
            call(p) { it.sendFile(convId, name, mime, data) }
        } catch (e: Exception) { p.reject("E_MESH", e.message) }
    }

    /** Images are returned as a data URI for in-app display; decrypted bytes are never written to shared storage implicitly. */
    @ReactMethod fun attachmentDataUri(msgId: String, p: Promise) = call(p) { e ->
        val data = e.readAttachment(msgId) ?: return@call null
        val mime = e.db.query("SELECT mime FROM attachments WHERE id=?", msgId).firstOrNull()?.str("mime") ?: "application/octet-stream"
        "data:$mime;base64," + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /** Explicit user action: export a decrypted copy to Downloads. */
    @ReactMethod fun saveAttachment(msgId: String, p: Promise) = call(p) { e ->
        val data = e.readAttachment(msgId) ?: throw EngineException("File not available yet")
        val row = e.db.query("SELECT name, mime FROM attachments WHERE id=?", msgId).first()
        val name = row.str("name").replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, row.str("mime"))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OfflineMesh")
            }
            val uri = rc.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw EngineException("Could not save")
            rc.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            "Downloads/OfflineMesh/$name"
        } else {
            val dir = File(rc.getExternalFilesDir(null), "Downloads").apply { mkdirs() }
            File(dir, name).writeBytes(data); "${dir.path}/$name"
        }
    }

    // ------------------------------------------------------------------ groups

    @ReactMethod fun createGroup(name: String, members: ReadableArray, p: Promise) = call(p) { it.createGroup(name, strings(members)) }
    @ReactMethod fun addMembers(groupId: String, members: ReadableArray, p: Promise) = call(p) { it.addMembers(groupId, strings(members)); true }
    @ReactMethod fun removeMember(groupId: String, memberId: String, p: Promise) = call(p) { it.removeMember(groupId, memberId); true }
    @ReactMethod fun setAdmin(groupId: String, memberId: String, admin: Boolean, p: Promise) = call(p) { it.setAdmin(groupId, memberId, admin); true }
    @ReactMethod fun renameGroup(groupId: String, name: String, p: Promise) = call(p) { it.renameGroup(groupId, name); true }
    @ReactMethod fun leaveGroup(groupId: String, p: Promise) = call(p) { it.leaveGroup(groupId); true }
    @ReactMethod fun acceptInvite(groupId: String, p: Promise) = call(p) { it.acceptInvite(groupId); "g:$groupId" }
    @ReactMethod fun rejectInvite(groupId: String, p: Promise) = call(p) { it.rejectInvite(groupId); true }

    // ------------------------------------------------------------------ verification & security

    @ReactMethod fun myQr(p: Promise) {
        val t = MeshService.transport ?: return p.reject("E_SERVICE", "Turn on nearby discovery first so others can find you")
        call(p) { it.createQr(t.ephemeralId) }
    }

    @ReactMethod fun scanQr(text: String, p: Promise) = call(p) { e ->
        val eph = e.scanQr(text)
        if (eph == null) mapOf("status" to "verified")
        else {
            val t = MeshService.transport ?: throw EngineException("Turn on nearby discovery to connect")
            t.qrTarget = eph
            t.nearby.values.firstOrNull { it.ephemeralId == eph }?.let { t.connectTo(it.address) }
            mapOf("status" to "connecting", "ephemeralId" to eph)
        }
    }

    @ReactMethod fun safetyNumber(peerId: String, p: Promise) = call(p) { it.safetyNumber(peerId) }
    @ReactMethod fun setVerified(peerId: String, verified: Boolean, p: Promise) = call(p) { it.setVerified(peerId, verified); true }
    @ReactMethod fun block(peerId: String, blocked: Boolean, p: Promise) = call(p) { it.block(peerId, blocked); true }
    @ReactMethod fun revoke(peerId: String, p: Promise) = call(p) { it.revoke(peerId); true }

    // ------------------------------------------------------------------ settings

    @ReactMethod fun setSetting(key: String, value: Boolean, p: Promise) = call(p) { e ->
        when (key) {
            "relayEnabled" -> e.relayEnabled = value
            "visible" -> { e.putSetting("visible", if (value) "1" else "0"); MeshService.transport?.setVisible(value) }
            "autoConnect" -> { e.putSetting("auto_connect", if (value) "1" else "0"); MeshService.transport?.autoConnect = value }
            "readReceipts" -> e.putSetting("read_receipts", if (value) "1" else "0")
            else -> throw EngineException("Unknown setting")
        }
        true
    }

    @ReactMethod fun clearRelayQueue(p: Promise) = call(p) { it.clearRelayQueue(); true }

    @ReactMethod fun openBatterySettings(p: Promise) {
        runCatching { rc.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { openAppSettings(p); return }
        p.resolve(true)
    }

    @ReactMethod fun openAppSettings(p: Promise) {
        rc.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${rc.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        p.resolve(true)
    }

    @ReactMethod fun openBluetoothSettings(p: Promise) {
        rc.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); p.resolve(true)
    }

    /** Irreversible: wipes database, keys and files (Android clears all app data and kills the process). */
    @ReactMethod fun wipeAllData(p: Promise) {
        MeshService.stop(rc)
        p.resolve(true)
        (rc.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
    }

    // ------------------------------------------------------------------ QR + files (native, offline)

    @ReactMethod fun scanQrCode(p: Promise) {
        val act = rc.currentActivity ?: return p.reject("E_UI", "No activity")
        pendingScan?.resolve(null)
        pendingScan = p
        act.startActivityForResult(Intent(act, QrScanActivity::class.java), REQ_SCAN)
    }

    /** QR matrix for rendering in JS (generated on-device by ZXing). */
    @ReactMethod fun qrMatrix(text: String, p: Promise) {
        try {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
            val sb = StringBuilder(m.width * m.height)
            for (y in 0 until m.height) for (x in 0 until m.width) sb.append(if (m.get(x, y)) '1' else '0')
            p.resolve(Arguments.createMap().apply { putInt("size", m.width); putString("bits", sb.toString()) })
        } catch (e: Exception) { p.reject("E_QR", e.message) }
    }

    @ReactMethod fun pickFile(p: Promise) {
        val act = rc.currentActivity ?: return p.reject("E_UI", "No activity")
        pendingPick?.resolve(null)
        pendingPick = p
        act.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PICK)
    }

    @ReactMethod fun ephemeralIdHex(p: Promise) = p.resolve(MeshService.transport?.ephemeralId?.let { Hex.enc(it) })
}
