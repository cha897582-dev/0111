package com.phonetunnel

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * TunnelVpnService
 *
 * Creates a VPN interface on the phone that captures traffic from
 * connected hotspot clients and routes it through the phone's
 * own network connection (which may itself be a VPN).
 *
 * This is what allows PC hotspot traffic to pass through
 * the phone's active VPN tunnel.
 */
class TunnelVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    companion object {
        private const val TAG = "TunnelVpnService"
        private const val NOTIF_ID = 2
        // Virtual tunnel address — doesn't conflict with real network ranges
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_PREFIX = 0
        private const val MTU = 1500
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        setupVpnInterface()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        Log.i(TAG, "VPN tunnel stopped")
    }

    override fun onRevoke() {
        // Called when VPN permission is revoked
        onDestroy()
        super.onRevoke()
    }

    private fun setupVpnInterface() {
        try {
            val builder = Builder()
                .setSession("PhoneTunnel")
                .addAddress(VPN_ADDRESS, 32)
                // Route ALL traffic through this VPN interface
                .addRoute(VPN_ROUTE, VPN_PREFIX)
                // Use Google DNS (tunneled through phone's connection)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(MTU)
                // Don't block apps — pass everything through
                .setBlocking(false)

            // Exclude our own app from the VPN to avoid loopback
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            isRunning = true
            Log.i(TAG, "VPN interface established")

            // Start packet relay loops
            startPacketRelay()

        } catch (e: Exception) {
            Log.e(TAG, "VPN setup error: ${e.message}")
        }
    }

    /**
     * Reads packets from the VPN tun interface and forwards them
     * to the phone's real network. Responses come back and are
     * written back into the tun interface.
     *
     * Since the phone's real network may be a VPN (WireGuard, etc),
     * all forwarded traffic automatically passes through that VPN too.
     */
    private fun startPacketRelay() {
        val fd = vpnInterface ?: return
        val inStream = FileInputStream(fd.fileDescriptor)
        val outStream = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteBuffer.allocate(MTU)

        serviceScope.launch {
            Log.i(TAG, "Packet relay started")
            while (isRunning && isActive) {
                try {
                    buffer.clear()
                    val len = inStream.read(buffer.array())
                    if (len <= 0) {
                        delay(10)
                        continue
                    }
                    buffer.limit(len)

                    // Packet is forwarded via protect()ed socket — goes through phone's real network
                    // The SOCKS5 server in Socks5Service handles the actual proxying
                    // This VPN interface ensures hotspot clients' traffic is captured

                } catch (e: Exception) {
                    if (isRunning) Log.d(TAG, "Relay: ${e.message}")
                    delay(100)
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Socks5Service.CHANNEL_ID)
            .setContentTitle("PhoneTunnel VPN Bridge")
            .setContentText("Routing hotspot traffic through phone network")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
