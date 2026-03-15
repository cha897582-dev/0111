package com.phonetunnel

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.phonetunnel.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: Socks5Service? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as Socks5Service.LocalBinder).getService()
            bound = true
            updateUi()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; bound = false }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val running = intent.getBooleanExtra(Socks5Service.EXTRA_RUNNING, false)
            val count   = intent.getIntExtra(Socks5Service.EXTRA_CONNECTIONS, 0)
            val ip      = intent.getStringExtra(Socks5Service.EXTRA_IP) ?: "—"
            updateUi(running, count, ip)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupClicks()
        updateUi(false, 0, "—")
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, Socks5Service::class.java), connection, 0)
        val filter = IntentFilter(Socks5Service.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(statusReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        if (bound) { unbindService(connection); bound = false }
        unregisterReceiver(statusReceiver)
    }

    private fun setupClicks() {
        binding.btnToggle.setOnClickListener {
            if (service?.isRunning() == true) stopProxy() else startProxy()
        }
        binding.btnCopyIp.setOnClickListener {
            val ip = binding.tvIpValue.text.toString()
            if (ip != "—") {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("IP", "$ip:1080"))
                Toast.makeText(this, "Copied: $ip:1080", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startProxy() {
        val i = Intent(this, Socks5Service::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        bindService(i, connection, Context.BIND_AUTO_CREATE)
    }

    private fun stopProxy() {
        service?.stopProxy()
        stopService(Intent(this, Socks5Service::class.java))
        updateUi(false, 0, "—")
    }

    private fun updateUi(
        running: Boolean = service?.isRunning() ?: false,
        connections: Int  = service?.getConnectionCount() ?: 0,
        ip: String        = "—"
    ) {
        if (running) {
            binding.statusDot.setBackgroundResource(R.drawable.dot_green)
            binding.tvStatus.text = "Running"
            binding.tvStatus.setTextColor(getColor(R.color.accent_green))
            binding.btnToggle.text = "Stop"
            binding.btnToggle.setBackgroundColor(getColor(R.color.btn_stop))
            binding.cardInfo.visibility = View.VISIBLE
            binding.tvConnectionCount.text = connections.toString()
            if (ip != "—") binding.tvIpValue.text = ip
            binding.tvPort.text = "1080"
        } else {
            binding.statusDot.setBackgroundResource(R.drawable.dot_gray)
            binding.tvStatus.text = "Stopped"
            binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
            binding.btnToggle.text = "Start"
            binding.btnToggle.setBackgroundColor(getColor(R.color.btn_start))
            binding.cardInfo.visibility = View.GONE
        }
    }
}
