package com.offlinemesh.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.offlinemesh.MainActivity
import com.offlinemesh.MeshCore
import com.offlinemesh.R
import com.offlinemesh.ble.BleTransport

/**
 * Foreground service (type connectedDevice) that owns the BLE transport so discovery, delivery
 * and relay keep working while the user has the app in the background. It is only started after
 * the user granted Bluetooth permissions, shows a persistent notification, and can be stopped
 * from the app at any time. Android/OEM power management may still pause it; we never promise
 * uninterrupted background operation.
 */
class MeshService : Service() {
    companion object {
        const val CHANNEL_SERVICE = "mesh_service"
        const val CHANNEL_MESSAGES = "mesh_messages"
        const val NOTIF_ID = 1
        @Volatile var transport: BleTransport? = null; private set
        @Volatile var running = false; private set
        /** Set by the bridge while the UI is visible: no message notifications then. */
        @Volatile var appInForeground = false
        var onNearbyChanged: (() -> Unit)? = null

        fun requiredPermissions(): List<String> = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT)
            } else add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun hasPermissions(ctx: Context) = requiredPermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

        fun start(ctx: Context) {
            if (!hasPermissions(ctx)) throw SecurityException("Bluetooth permissions not granted")
            ContextCompat.startForegroundService(ctx, Intent(ctx, MeshService::class.java))
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, MeshService::class.java)) }
    }

    private val messageListener: (String, String?) -> Unit = { kind, id -> if (kind == "messages" && id != null) maybeNotify(id) }
    private val lastNotified = HashMap<String, Long>()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
            val ok = plugged || pct >= 20
            MeshCore.post { e -> e.relay.batteryOk = ok }
            transport?.lowPower = !plugged && pct < 30
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MeshCore.init(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = serviceNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            // Android 12+ forbids starting a foreground service from the background (e.g. a sticky restart).
            // Respect that: stop, and resume when the user next opens the app.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            if (!hasPermissions(this)) { stopSelf(); return START_NOT_STICKY }
            val t = BleTransport(this) { onNearbyChanged?.invoke() }
            transport = t
            t.start()
            running = true
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            MeshCore.listeners += messageListener
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        MeshCore.listeners -= messageListener
        runCatching { unregisterReceiver(batteryReceiver) }
        transport?.stop()
        transport = null
        onNearbyChanged?.invoke()
        super.onDestroy()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Nearby connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Offline Mesh is discovering and relaying nearby"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openAppIntent() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun serviceNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_mesh)
        .setContentTitle("Offline Mesh is active")
        .setContentText("Finding nearby people and delivering messages without internet")
        .setOngoing(true)
        .setContentIntent(openAppIntent())
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    /** Privacy: notification shows only the sender/group name, never message text. */
    private fun maybeNotify(convId: String) {
        if (appInForeground) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        MeshCore.post { e ->
            val row = e.db.query("SELECT c.title AS title, c.unread AS unread FROM conversations c WHERE c.id=?", convId).firstOrNull() ?: return@post
            if (row.int("unread") == 0) return@post
            val now = System.currentTimeMillis()
            if (now - (lastNotified[convId] ?: 0) < 3000) return@post
            lastNotified[convId] = now
            val n = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(row.str("title"))
                .setContentText(if (row.int("unread") == 1) "New encrypted message" else "${row.int("unread")} new encrypted messages")
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
            runCatching { NotificationManagerCompat.from(this).notify(convId.hashCode(), n) }
        }
    }
}
