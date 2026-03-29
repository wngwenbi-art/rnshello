package com.rnshello

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.net.InetSocketAddress
import java.net.ServerSocket

class RnsService : Service() {
    private var btSocket: BluetoothSocket? = null
    private var tcpServer: ServerSocket? = null
    private var isRunning = true

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "RNS_CHANNEL")
            .setContentTitle("rnshello Mesh Active")
            .setContentText("Connected to RNode")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1, notification)
        val mac = intent?.getStringExtra("mac") ?: ""
        if (mac.isNotEmpty()) startBridge(mac)
        return START_STICKY
    }

    private fun startBridge(mac: String) {
        Thread {
            try {
                val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac)
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                btSocket = m.invoke(device, 1) as BluetoothSocket
                btSocket?.connect()
                tcpServer = ServerSocket()
                tcpServer?.reuseAddress = true
                tcpServer?.bind(InetSocketAddress("127.0.0.1", 7633))
                while(isRunning) {
                    val client = tcpServer?.accept() ?: break
                    val btIn = btSocket?.inputStream; val btOut = btSocket?.outputStream
                    val tcpIn = client.inputStream; val tcpOut = client.outputStream
                    Thread { try { tcpIn.copyTo(btOut!!) } catch(e:Exception){} finally {client.close()} }.start()
                    Thread { try { btIn!!.copyTo(tcpOut!!) } catch(e:Exception){} finally {client.close()} }.start()
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel("RNS_CHANNEL", "Mesh Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { isRunning = false; btSocket?.close(); tcpServer?.close(); super.onDestroy() }
}