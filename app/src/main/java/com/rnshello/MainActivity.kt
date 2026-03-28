package com.rnshello

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class MainActivity : AppCompatActivity(), RnsCallback {

    private lateinit var addressDisplay: TextView
    private lateinit var messageInput: EditText
    private var btSocket: BluetoothSocket? = null
    private var tcpServer: ServerSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addressDisplay = findViewById(R.id.addressDisplay)
        messageInput = findViewById(R.id.messageInput)
        val btnSend = findViewById<Button>(R.id.btnSend)

        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_ADMIN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        } else {
            startStack()
        }

        btnSend.setOnClickListener {
            val input = messageInput.text.toString().trim()
            if (input.startsWith("connect:")) {
                val mac = input.substring(8).trim().uppercase()
                getSharedPreferences("rns_prefs", MODE_PRIVATE).edit().putString("last_mac", mac).commit()
                Toast.makeText(this, "MAC Saved. RESTARTING...", Toast.LENGTH_SHORT).show()
                Thread { Thread.sleep(1000); android.os.Process.killProcess(android.os.Process.myPid()) }.start()
            }
        }
    }

    private fun startStack() {
        val savedMac = getSharedPreferences("rns_prefs", MODE_PRIVATE).getString("last_mac", "") ?: ""

        Thread {
            try {
                var useBridge = "false"
                if (savedMac.isNotEmpty()) {
                    if (startBtTcpBridge(savedMac)) useBridge = "true"
                }

                if (!Python.isStarted()) Python.start(AndroidPlatform(this@MainActivity))
                val py = Python.getInstance()
                py.getModule("os").get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)
                
                val myAddr = py.getModule("rns_backend").callAttr("start_rns", filesDir.absolutePath, useBridge, this@MainActivity).toString()

                runOnUiThread { 
                    addressDisplay.text = "Addr: $myAddr\nBridge: $useBridge ($savedMac)" 
                }
            } catch (e: Exception) {
                runOnUiThread { addressDisplay.text = "Init Error: ${e.message}" }
            }
        }.start()
    }

    private fun startBtTcpBridge(mac: String): Boolean {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val device = adapter.getRemoteDevice(mac)
            
            // Forced Channel 1 Reflection (The Samsung/RNode standard)
            val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            btSocket = m.invoke(device, 1) as BluetoothSocket
            btSocket?.connect()

            // Open Port 7633 as required by RNodeInterface.py
            tcpServer = ServerSocket(7633)
            tcpServer?.reuseAddress = true

            Thread {
                val client = tcpServer?.accept()
                val btIn = btSocket?.inputStream
                val btOut = btSocket?.outputStream
                val tcpIn = client?.inputStream
                val tcpOut = client?.outputStream

                Thread { try { tcpIn?.copyTo(btOut!!) } catch (e: Exception) {} }.start()
                Thread { try { btIn?.copyTo(tcpOut!!) } catch (e: Exception) {} }.start()
            }.start()
            true
        } catch (e: Exception) { false }
    }

    override fun onTextReceived(senderHash: String, text: String) {
        runOnUiThread { Toast.makeText(this, "Msg: $text", Toast.LENGTH_LONG).show() }
    }
    override fun onImageReceived(senderHash: String, imagePath: String) {}
}