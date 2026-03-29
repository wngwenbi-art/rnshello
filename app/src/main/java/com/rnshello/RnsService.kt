package com.rnshello

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.net.InetSocketAddress
import java.net.ServerSocket

class RnsService : Service() {
    private var btSocket: BluetoothSocket? = null
    private var tcpServer: ServerSocket? = null
    private var isBridging = false
    private var currentMac = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "RNS_CHANNEL")
            .setContentTitle("rnshello Mesh Active")
            .setContentText("Maintaining RNode Connection")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1, notification)
        
        val mac = intent?.getStringExtra("mac") ?: getSharedPreferences("rns_database", Context.MODE_PRIVATE).getString("last_mac", "") ?: ""
        if (mac.isNotEmpty() && mac != currentMac) {
            startBridge(mac)
        }
        return START_STICKY
    }

    private fun startBridge(mac: String) {
        Thread {
            try {
                isBridging = false
                btSocket?.close()
                tcpServer?.close()
                Thread.sleep(1000)
                
                val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac)
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                btSocket = m.invoke(device, 1) as BluetoothSocket
                btSocket?.connect()
                
                tcpServer = ServerSocket()
                tcpServer?.reuseAddress = true
                tcpServer?.bind(InetSocketAddress("127.0.0.1", 7633))
                
                isBridging = true
                currentMac = mac
                
                while(isBridging) {
                    val client = tcpServer?.accept() ?: break
                    val btIn = btSocket?.inputStream; val btOut = btSocket?.outputStream
                    val tcpIn = client.inputStream; val tcpOut = client.outputStream
                    Thread { try { val buf = ByteArray(1024); var r=0; while(isBridging && tcpIn.read(buf).also{r=it}!=-1) btOut?.write(buf,0,r) } catch(e:Exception){} }.start()
                    Thread { try { val buf = ByteArray(1024); var r=0; while(isBridging && btIn!!.read(buf).also{r=it}!=-1) tcpOut.write(buf,0,r) } catch(e:Exception){} finally{client.close()} }.start()
                }
            } catch (e: Exception) { currentMac = "" }
        }.start()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel("RNS_CHANNEL", "Mesh Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { isBridging = false; btSocket?.close(); tcpServer?.close(); super.onDestroy() }
}