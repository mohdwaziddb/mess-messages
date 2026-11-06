package com.offlinemesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.offlinemesh.MeshCore
import com.offlinemesh.core.crypto.Crypto
import com.offlinemesh.core.engine.Link
import com.offlinemesh.core.proto.Hex
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bluetooth LE transport. Every device is simultaneously:
 *  - a peripheral: advertises the service UUID + an 8-byte random id that rotates every 15 min
 *    (no name, no key, no phone number) and runs a GATT server;
 *  - a central: scans for that service and connects to nearby installations.
 *
 * GATT layout: RX characteristic (central → peripheral writes), TX characteristic
 * (peripheral → central notifications). Each direction is a serial queue paced by the stack's
 * completion callbacks. Everything carried is already framed + authenticated by the engine.
 */
@SuppressLint("MissingPermission") // Runtime permissions are checked by MeshService before start().
class BleTransport(private val ctx: Context, private val onNearbyChanged: () -> Unit) {
    companion object {
        private const val TAG = "BleTransport"
        val SERVICE: UUID = UUID.fromString("7b1e0001-4f3a-4c2e-9a51-0f5d6e8c1a20")
        val RX: UUID = UUID.fromString("7b1e0002-4f3a-4c2e-9a51-0f5d6e8c1a20")
        val TX: UUID = UUID.fromString("7b1e0003-4f3a-4c2e-9a51-0f5d6e8c1a20")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val MAX_LINKS = 6
        const val ROTATE_MS = 15 * 60_000L
        const val GONE_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val DEFER_CONNECT_MS = 10_000L
    }

    data class Nearby(
        val address: String,
        var ephemeralId: String,
        var rssi: Int,
        var firstSeen: Long,
        var lastSeen: Long,
        var state: String, // discovered | connecting | connected
        var linkId: String? = null,
    )

    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = manager.adapter
    val nearby = ConcurrentHashMap<String, Nearby>()
    private val links = ConcurrentHashMap<String, BleLink>()
    private var server: BluetoothGattServer? = null
    @Volatile var ephemeralId: ByteArray = Crypto.randomBytes(8); private set
    private var rotatedAt = 0L
    @Volatile var running = false; private set
    var discoverable = true
    var autoConnect = true
    var lowPower = false
    /** Set when the user scanned a QR code: connect to this ephemeral id regardless of policy. */
    @Volatile var qrTarget: String? = null
    private var lastInboundAt = 0L
    private var housekeepingTask: java.util.concurrent.ScheduledFuture<*>? = null

    // =========================================================================== lifecycle

    fun isSupported() = ctx.packageManager.hasSystemFeature("android.hardware.bluetooth_le") && adapter != null
    fun isEnabled() = adapter?.isEnabled == true
    fun canAdvertise() = adapter?.bluetoothLeAdvertiser != null && (Build.VERSION.SDK_INT < 26 || adapter.isMultipleAdvertisementSupported)

    fun start() {
        if (running || !isEnabled()) return
        running = true
        openServer()
        rotateId(force = true)
        startScan()
        housekeepingTask = MeshCore.exec.scheduleWithFixedDelay({ runCatching { housekeeping() } }, 5, 5, TimeUnit.SECONDS)
    }

    fun stop() {
        running = false
        housekeepingTask?.cancel(false)
        housekeepingTask = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCb) }
        links.values.forEach { it.close() }
        links.clear()
        runCatching { server?.close() }
        server = null
        nearby.clear()
        onNearbyChanged()
    }

    fun restartScan() { if (running) { runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }; startScan() } }

    fun setVisible(visible: Boolean) {
        discoverable = visible
        if (!running) return
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCb) }
        if (visible) startAdvertising()
    }

    private fun housekeeping() {
        if (!running) return
        val now = System.currentTimeMillis()
        rotateId(force = false)
        var changed = false
        nearby.values.removeIf { n ->
            val gone = n.linkId == null && now - n.lastSeen > GONE_MS
            changed = changed || gone
            gone
        }
        links.values.filter { !it.ready && now - it.createdAt > CONNECT_TIMEOUT_MS }.forEach { it.close() }
        if (autoConnect) nearby.values.filter { it.state == "discovered" }.forEach { maybeConnect(it) }
        if (changed) onNearbyChanged()
    }

    private fun rotateId(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - rotatedAt < ROTATE_MS) return
        ephemeralId = Crypto.randomBytes(8)
        rotatedAt = now
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCb) }
        if (discoverable) startAdvertising()
    }

    // =========================================================================== advertising

    private val advCb = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { Log.w(TAG, "advertise failed $errorCode") }
    }

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (lowPower) AdvertiseSettings.ADVERTISE_MODE_BALANCED else AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)        // never leak the phone's Bluetooth name
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(SERVICE), ephemeralId)
            .build()
        runCatching { adv.startAdvertising(settings, data, advCb) }.onFailure { Log.w(TAG, "advertise: ${it.javaClass.simpleName}") }
    }

    // =========================================================================== scanning

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onResult(it) }
        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed $errorCode") }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE), ByteArray(0)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCb) }.onFailure { Log.w(TAG, "scan: ${it.javaClass.simpleName}") }
    }

    private fun onResult(r: ScanResult) {
        val eph = r.scanRecord?.getServiceData(ParcelUuid(SERVICE)) ?: return
        if (eph.size != 8 || eph.contentEquals(ephemeralId)) return
        val now = System.currentTimeMillis()
        val addr = r.device.address
        val hex = Hex.enc(eph)
        // Same device may rotate its id: entries are keyed by address, duplicates by id are merged.
        nearby.entries.removeIf { it.key != addr && it.value.ephemeralId == hex && it.value.linkId == null }
        val n = nearby.getOrPut(addr) { Nearby(addr, hex, r.rssi, now, now, "discovered") }
        val isNew = n.lastSeen == now && n.firstSeen == now
        n.ephemeralId = hex; n.rssi = r.rssi; n.lastSeen = now
        if (qrTarget == hex) maybeConnect(n, force = true)
        else if (autoConnect && isNew) maybeConnect(n)
        if (isNew) onNearbyChanged()
    }

    fun connectTo(address: String) { nearby[address]?.let { maybeConnect(it, force = true) } }

    @Synchronized
    private fun maybeConnect(n: Nearby, force: Boolean = false) {
        if (!running || n.state != "discovered") return
        if (links.values.any { it.address == n.address }) return
        if (links.size >= MAX_LINKS) return
        if (!force) {
            // Halve duplicate dials: the smaller ephemeral id dials immediately, the other waits.
            val iDialFirst = Hex.enc(ephemeralId) < n.ephemeralId
            val now = System.currentTimeMillis()
            if (!iDialFirst && (now - n.firstSeen < DEFER_CONNECT_MS || now - lastInboundAt < DEFER_CONNECT_MS)) return
        }
        val device = adapter?.getRemoteDevice(n.address) ?: return
        n.state = "connecting"
        val link = CentralLink(device)
        links[link.id] = link
        n.linkId = link.id
        onNearbyChanged()
        link.connect()
    }

    // =========================================================================== links

    abstract inner class BleLink(val device: BluetoothDevice) : Link {
        val address: String = device.address
        val createdAt = System.currentTimeMillis()
        @Volatile var mtu = 23
        @Volatile var ready = false
        private val queue = ArrayDeque<ByteArray>()
        private var busy = false
        @Volatile var closed = false

        override val maxChunk: Int get() = (mtu - 3).coerceIn(20, 512)

        override fun send(chunk: ByteArray) {
            synchronized(this) { if (closed) return; queue.addLast(chunk) }
            pump()
        }

        protected fun pump() {
            val next: ByteArray
            synchronized(this) {
                if (busy || closed || !ready) return
                next = queue.removeFirstOrNull() ?: return
                busy = true
            }
            if (!write(next)) { synchronized(this) { busy = false }; close() }
        }

        protected fun onWriteDone() { synchronized(this) { busy = false }; pump() }

        protected abstract fun write(chunk: ByteArray): Boolean

        protected fun becameReady() {
            if (ready) return
            ready = true
            nearby[address]?.let { it.state = "connected"; it.linkId = id }
            onNearbyChanged()
            MeshCore.post { it.linkUp(this) }
            pump()
        }

        protected fun received(bytes: ByteArray) { MeshCore.post { it.linkChunk(id, bytes) } }

        protected fun closedByRemote() {
            if (links.remove(id) == null) return
            closed = true
            nearby[address]?.let { it.state = "discovered"; it.linkId = null; it.lastSeen = System.currentTimeMillis() }
            onNearbyChanged()
            MeshCore.post { it.linkDown(id) }
        }
    }

    /** We are the GATT client. */
    inner class CentralLink(device: BluetoothDevice) : BleLink(device) {
        override val id = "c:${device.address}"
        override val initiator = true
        private var gatt: BluetoothGatt? = null
        private var rx: BluetoothGattCharacteristic? = null

        fun connect() {
            gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(ctx, false, cb)
        }

        private val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!g.requestMtu(517)) g.discoverServices()
                } else {
                    runCatching { g.close() }
                    closedByRemote()
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = m
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(SERVICE)
                val tx = svc?.getCharacteristic(TX)
                rx = svc?.getCharacteristic(RX)
                if (tx == null || rx == null) { close(); return }
                g.setCharacteristicNotification(tx, true)
                val d = tx.getDescriptor(CCCD) ?: run { close(); return }
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else @Suppress("DEPRECATION") { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) becameReady() else close()
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) onWriteDone() else close()
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                if (c.uuid == TX) received(value)
            }

            @Deprecated("pre-33 callback")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33 && c.uuid == TX) @Suppress("DEPRECATION") received(c.value.copyOf())
            }
        }

        override fun write(chunk: ByteArray): Boolean {
            val g = gatt ?: return false
            val c = rx ?: return false
            return if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else @Suppress("DEPRECATION") {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = chunk
                g.writeCharacteristic(c)
            }
        }

        override fun close() {
            closed = true
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            closedByRemote()
        }
    }

    /** A central connected to our GATT server. */
    inner class PeripheralLink(device: BluetoothDevice) : BleLink(device) {
        override val id = "p:${device.address}"
        override val initiator = false

        override fun write(chunk: ByteArray): Boolean {
            val s = server ?: return false
            val tx = s.getService(SERVICE)?.getCharacteristic(TX) ?: return false
            return if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(device, tx, false, chunk) == BluetoothGatt.GATT_SUCCESS
            } else @Suppress("DEPRECATION") {
                tx.value = chunk
                s.notifyCharacteristicChanged(device, tx, false)
            }
        }

        fun sent() = onWriteDone()
        fun ready() = becameReady()
        fun gotChunk(b: ByteArray) = received(b)
        fun remoteClosed() = closedByRemote()

        override fun close() {
            closed = true
            runCatching { server?.cancelConnection(device) }
            closedByRemote()
        }
    }

    // =========================================================================== GATT server

    private fun openServer() {
        val s = manager.openGattServer(ctx, serverCb) ?: return
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(BluetoothGattCharacteristic(RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        val tx = BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        tx.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        svc.addCharacteristic(tx)
        s.addService(svc)
        server = s
    }

    private fun peripheral(device: BluetoothDevice) = links["p:${device.address}"] as? PeripheralLink

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (links.size >= MAX_LINKS) { server?.cancelConnection(device); return }
                lastInboundAt = System.currentTimeMillis()
                val l = PeripheralLink(device)
                links[l.id] = l
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                peripheral(device)?.remoteClosed()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { peripheral(device)?.mtu = mtu }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (d.uuid == CCCD && value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                val l = peripheral(device) ?: PeripheralLink(device).also { links[it.id] = it }
                l.ready()
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val ok = c.uuid == RX && !preparedWrite && offset == 0 && value != null
            if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            if (ok) peripheral(device)?.gotChunk(value!!.copyOf())
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) { peripheral(device)?.sent() }
    }

    fun linkCount() = links.values.count { it.ready }
}
