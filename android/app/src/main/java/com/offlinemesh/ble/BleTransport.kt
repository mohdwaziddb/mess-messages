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
        stopScans()
        stopAdvertising()
        advState = "off"
        links.values.forEach { it.close() }
        links.clear()
        runCatching { server?.close() }
        server = null
        nearby.clear()
        onNearbyChanged()
    }

    fun restartScan() { if (running) { stopScans(); startScan() } }

    fun setVisible(visible: Boolean) {
        // Already advertising (or about to) as asked: don't stop/restart, which races on some stacks.
        if (visible && discoverable && running && (advState == "on" || advState == "starting")) return
        discoverable = visible
        if (!running) return
        stopAdvertising()
        if (visible) startAdvertising() else advState = "hidden"
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
        links.values.filter { !it.ready && now - it.createdAt > CONNECT_TIMEOUT_MS }.forEach { l ->
            // Never tear down a physical connection that our own client link to the same phone is using.
            if (l is PeripheralLink && links.containsKey("c:${l.address}")) { links.remove(l.id); l.closed = true } else l.close()
        }
        if (autoConnect) nearby.values.filter { it.state == "discovered" }.forEach { maybeConnect(it) }
        if (changed) onNearbyChanged()
    }

    private fun rotateId(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - rotatedAt < ROTATE_MS) return
        ephemeralId = Crypto.randomBytes(8)
        rotatedAt = now
        stopAdvertising()
        if (discoverable) startAdvertising()
    }

    // =========================================================================== diagnostics

    /** Human-checkable radio state, shown on the Troubleshooting screen. */
    @Volatile var advState = "off"; private set      // off | starting | on | hidden | failed:<code>
    @Volatile var scanState = "off"; private set     // off | on | failed:<code>
    private val rawSeen = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var meshSeenTotal = 0

    fun diagnostics(): Map<String, Any?> = mapOf(
        "advertising" to advState,
        "scanning" to scanState,
        "bluetoothSignals" to rawSeen.size,
        "meshPhonesSeen" to meshSeenTotal,
    )

    // =========================================================================== advertising

    // A fresh callback per start: reusing one callback across a quick stop/start makes some stacks
    // reject the second start (ALREADY_STARTED) after the first was stopped, leaving us invisible.
    @Volatile private var advCb: AdvertiseCallback? = null
    @Volatile private var advRetried = false

    private fun stopAdvertising() {
        val cb = advCb ?: return
        advCb = null
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
    }

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: run { advState = "failed:no-advertiser"; return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (lowPower) AdvertiseSettings.ADVERTISE_MODE_BALANCED else AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(if (advRetried) AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM else AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)        // never leak the phone's Bluetooth name
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(SERVICE), ephemeralId)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (advCb === this) { advState = "on"; advRetried = false; MeshCore.log("ble advertising on") }
            }
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertise failed $errorCode")
                MeshCore.log("ble advertising failed code=$errorCode")
                if (advCb !== this) return
                advState = "failed:$errorCode"
                advCb = null
                // One delayed retry with gentler settings (busy controller / too many advertisers).
                if (!advRetried && running && discoverable) {
                    advRetried = true
                    MeshCore.exec.schedule({ if (running && discoverable && advCb == null) startAdvertising() }, 2, TimeUnit.SECONDS)
                }
            }
        }
        advCb = cb
        advState = "starting"
        runCatching { adv.startAdvertising(settings, data, cb) }.onFailure {
            Log.w(TAG, "advertise: ${it.javaClass.simpleName}")
            advCb = null
            advState = "failed:${it.javaClass.simpleName}"
        }
    }

    // =========================================================================== scanning

    /** Hardware-filtered scan: keeps working with the screen off, but some chipsets drop 128-bit service-data filters. */
    private val filteredCb = scanCallback("filtered")
    /** Unfiltered scan, matched in software by [onResult]: works on every chipset (Android pauses it with the screen off). */
    private val openCb = scanCallback("open")

    private fun scanCallback(name: String) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onResult(it) }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "$name scan failed $errorCode")
            MeshCore.log("ble $name scan failed code=$errorCode")
            // ALREADY_STARTED (1) means it is in fact running.
            if (errorCode != SCAN_FAILED_ALREADY_STARTED) scanState = "failed:$errorCode"
        }
    }

    private fun stopScans() {
        val scanner = adapter?.bluetoothLeScanner
        runCatching { scanner?.stopScan(filteredCb) }
        runCatching { scanner?.stopScan(openCb) }
        scanState = "off"
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { scanState = "failed:no-scanner"; return }
        val mode = if (lowPower) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY
        val settings = ScanSettings.Builder().setScanMode(mode).build()
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE), ByteArray(0)).build()
        scanState = "on"
        runCatching { scanner.startScan(listOf(filter), settings, filteredCb) }
            .onFailure { Log.w(TAG, "filtered scan: ${it.javaClass.simpleName}"); scanState = "failed:${it.javaClass.simpleName}" }
        runCatching { scanner.startScan(null, settings, openCb) }
            .onFailure { Log.w(TAG, "open scan: ${it.javaClass.simpleName}") }
    }

    private fun onResult(r: ScanResult) {
        if (rawSeen.size < 1000) rawSeen.add(r.device.address)
        val eph = r.scanRecord?.getServiceData(ParcelUuid(SERVICE)) ?: return
        if (eph.size != 8 || eph.contentEquals(ephemeralId)) return
        val now = System.currentTimeMillis()
        val addr = r.device.address
        val hex = Hex.enc(eph)
        // Same device may rotate its id: entries are keyed by address, duplicates by id are merged.
        nearby.entries.removeIf { it.key != addr && it.value.ephemeralId == hex && it.value.linkId == null }
        val n = nearby.getOrPut(addr) { Nearby(addr, hex, r.rssi, now, now, "discovered") }
        val isNew = n.lastSeen == now && n.firstSeen == now
        if (isNew) meshSeenTotal++
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
        // Already linked to this phone (e.g. it dialed us): a second link only adds a duplicate to resolve.
        if (links.values.any { it.ready && it.address == n.address }) return
        if (!force) {
            // Halve duplicate dials: the smaller ephemeral id dials immediately, the other waits.
            val iDialFirst = Hex.enc(ephemeralId) < n.ephemeralId
            val now = System.currentTimeMillis()
            if (!iDialFirst && (now - n.firstSeen < DEFER_CONNECT_MS || now - lastInboundAt < DEFER_CONNECT_MS)) return
        }
        val device = adapter?.getRemoteDevice(n.address) ?: return
        n.state = "connecting"
        MeshCore.log("ble dial ${n.address.takeLast(5)} force=$force rssi=${n.rssi}")
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
            // A throwing radio call must never escape into the engine (it would stall retries); treat it as a failed write.
            val ok = try { write(next) } catch (e: Exception) { MeshCore.log("ble $id write threw ${e.javaClass.simpleName}: ${e.message}"); false }
            if (!ok) { MeshCore.log("ble $id write failed (${next.size} B), closing"); synchronized(this) { busy = false }; close() }
        }

        protected fun onWriteDone() { synchronized(this) { busy = false }; pump() }

        protected abstract fun write(chunk: ByteArray): Boolean

        protected fun becameReady() {
            if (ready) return
            ready = true
            MeshCore.log("ble $id ready mtu=$mtu")
            nearby[address]?.let { it.state = "connected"; it.linkId = id }
            onNearbyChanged()
            MeshCore.post { it.linkUp(this) }
            pump()
        }

        protected fun received(bytes: ByteArray) { MeshCore.post { it.linkChunk(id, bytes) } }

        protected fun closedByRemote() {
            if (links.remove(id) == null) return
            closed = true
            MeshCore.log("ble $id closed")
            nearby[address]?.let {
                // Another link to the same phone may still be up (two links can share one connection).
                val other = links.values.firstOrNull { l -> l.address == address && l.ready }
                if (other != null) { it.state = "connected"; it.linkId = other.id }
                else { it.state = "discovered"; it.linkId = null; it.lastSeen = System.currentTimeMillis() }
            }
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
                MeshCore.log("ble $id client state=$newState status=$status")
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
                MeshCore.log("ble $id mtu=$m status=$status")
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(SERVICE)
                val tx = svc?.getCharacteristic(TX)
                rx = svc?.getCharacteristic(RX)
                if (tx == null || rx == null) { MeshCore.log("ble $id service missing (status=$status)"); close(); return }
                g.setCharacteristicNotification(tx, true)
                val d = tx.getDescriptor(CCCD) ?: run { close(); return }
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else @Suppress("DEPRECATION") { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) becameReady() else { MeshCore.log("ble $id notify-enable failed status=$status"); close() }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) onWriteDone() else { MeshCore.log("ble $id write status=$status"); close() }
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
            // Our own client link to the same phone rides on the same physical connection. Cancelling it here
            // would also kill that link (this is what broke one direction after duplicate-link resolution).
            val shared = links.values.any { it !== this && it.address == address }
            if (!shared) runCatching { server?.cancelConnection(device) }
            MeshCore.log("ble $id close (shared connection kept=$shared)")
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

    /** Server-side MTU per phone: it can be reported before the peripheral link exists (created on notify-enable). */
    private val serverMtu = ConcurrentHashMap<String, Int>()

    private fun newPeripheral(device: BluetoothDevice) = PeripheralLink(device).also { l ->
        serverMtu[device.address]?.let { l.mtu = it }
        links[l.id] = l
    }

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            MeshCore.log("ble server ${device.address.takeLast(5)} state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Android reports every LE connection to the GATT server, including the ones *we* dialed as a
                // client. Treating those as inbound created a phantom link that timed out after 15 s and then
                // cancelled the shared connection, killing our real client link. Ignore them.
                if (links.containsKey("c:${device.address}")) return
                if (links.size >= MAX_LINKS) { server?.cancelConnection(device); return }
                lastInboundAt = System.currentTimeMillis()
                if (peripheral(device) == null) newPeripheral(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverMtu.remove(device.address)
                peripheral(device)?.remoteClosed()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            serverMtu[device.address] = mtu
            peripheral(device)?.mtu = mtu
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (d.uuid == CCCD && value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                val l = peripheral(device) ?: newPeripheral(device)
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
