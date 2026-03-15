package com.phonetunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class Socks5Service : Service() {

    companion object {
        const val TAG = "Socks5Service"
        const val PORT = 1080
        const val CHANNEL_ID = "phonetunnel_channel"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.phonetunnel.STOP"
        const val BROADCAST_STATUS = "com.phonetunnel.STATUS"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_CONNECTIONS = "connections"
        const val EXTRA_IP = "ip"
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@Socks5Service
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val activeConnections = CopyOnWriteArrayList<Socket>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopProxy(); stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotification("Listening on port $PORT"))
        startProxy()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { stopProxy(); super.onDestroy() }

    private fun startProxy() {
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket().also {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress("0.0.0.0", PORT))
                }
                Log.i(TAG, "SOCKS5 listening on port $PORT")
                broadcastStatus(true)
                while (isActive) {
                    val client = serverSocket!!.accept()
                    activeConnections.add(client)
                    broadcastStatus(true)
                    updateNotification("Connections: ${activeConnections.size}")
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Server error: ${e.message}")
            } finally {
                broadcastStatus(false)
            }
        }
    }

    fun stopProxy() {
        serverJob?.cancel()
        activeConnections.forEach { runCatching { it.close() } }
        activeConnections.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        broadcastStatus(false)
    }

    fun isRunning() = serverSocket?.isBound == true && serverSocket?.isClosed == false
    fun getConnectionCount() = activeConnections.size

    private fun handleClient(client: Socket) {
        client.use {
            client.soTimeout = 30_000
            try {
                val inp = client.getInputStream()
                val out = client.getOutputStream()

                // SOCKS5 auth negotiation — accept no-auth
                val ver = inp.read(); if (ver != 0x05) return
                val nMethods = inp.read()
                inp.read(ByteArray(nMethods))
                out.write(byteArrayOf(0x05, 0x00)); out.flush()

                // Read request
                val req = ByteArray(4); inp.read(req)
                if (req[0].toInt() != 0x05) return
                val cmd = req[1].toInt()
                val atyp = req[3].toInt()

                val destHost: String = when (atyp) {
                    0x01 -> { val a = ByteArray(4); inp.read(a); InetAddress.getByAddress(a).hostAddress!! }
                    0x03 -> { val len = inp.read(); val d = ByteArray(len); inp.read(d); String(d) }
                    0x04 -> { val a = ByteArray(16); inp.read(a); InetAddress.getByAddress(a).hostAddress!! }
                    else -> { sendReply(out, 0x08); return }
                }

                val destPort = (inp.read() shl 8) or inp.read()

                if (cmd != 0x01) { sendReply(out, 0x07); return }

                // Connect — uses phone's network (incl. active VPN tunnel)
                val target = Socket()
                target.connect(InetSocketAddress(destHost, destPort), 15_000)
                sendReply(out, 0x00)

                // Relay bytes bidirectionally
                relay(inp, out, target.getInputStream(), target.getOutputStream())
                target.close()

            } catch (e: Exception) {
                Log.d(TAG, "Client: ${e.message}")
            } finally {
                activeConnections.remove(client)
                broadcastStatus(true)
                updateNotification("Connections: ${activeConnections.size}")
            }
        }
    }

    private fun sendReply(out: OutputStream, status: Int) {
        out.write(byteArrayOf(0x05, status.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun relay(cIn: InputStream, cOut: OutputStream, tIn: InputStream, tOut: OutputStream) {
        val buf = ByteArray(8192)
        var done = false
        val t1 = Thread { try { while (!done) { val n = cIn.read(buf); if (n < 0) break; tOut.write(buf,0,n); tOut.flush() } } catch(_:Exception){} finally { done=true } }
        val t2 = Thread { try { while (!done) { val n = tIn.read(buf); if (n < 0) break; cOut.write(buf,0,n); cOut.flush() } } catch(_:Exception){} finally { done=true } }
        t1.start(); t2.start(); t1.join(60_000); t2.join(60_000); done = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "PhoneTunnel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(this, 0,
            Intent(this, Socks5Service::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhoneTunnel Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true).build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    private fun broadcastStatus(running: Boolean) {
        val ip = try {
            Socket().use { it.connect(InetSocketAddress("8.8.8.8", 80), 1000); it.localAddress.hostAddress ?: "—" }
        } catch (_: Exception) { "—" }
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_RUNNING, running)
            putExtra(EXTRA_CONNECTIONS, activeConnections.size)
            putExtra(EXTRA_IP, ip)
        })
    }
}
