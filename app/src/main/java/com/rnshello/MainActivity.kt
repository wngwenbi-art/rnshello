package com.rnshello

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class MainActivity : AppCompatActivity(), RnsCallback {

    private lateinit var addressDisplay: TextView
    private lateinit var messageInput: EditText
    private var destinationAddress: String = ""

    private var btSocket: BluetoothSocket? = null
    private var tcpServer: ServerSocket? = null
    private var tcpClient: Socket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addressDisplay = findViewById(R.id.addressDisplay)
        messageInput = findViewById(R.id.messageInput)
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnAttach = findViewById<Button>(R.id.btnAttach)

        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
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
                val mac = input.substring(8).trim()
                saveMacAndExit(mac)
            } else if (input.startsWith("dest:")) {
                destinationAddress = input.substring(5).trim()
                Toast.makeText(this, "Dest: $destinationAddress", Toast.LENGTH_SHORT).show()
                messageInput.setText("")
            } else if (destinationAddress.isNotEmpty() && input.isNotEmpty()) {
                Thread {
                    try {
                        val py = Python.getInstance()
                        py.getModule("rns_backend").callAttr("send_text", destinationAddress, input)
                    } catch (e: Exception) { Log.e("RNS_HELLO", "Send Err: ${e.message}") }
                }.start()
                messageInput.setText("")
            }
        }
    }

    private fun startStack() {
        val prefs = getSharedPreferences("rns_prefs", MODE_PRIVATE)
        val savedMac = prefs.getString("last_mac", "") ?: ""

        Thread {
            try {
                var bridgeStatus = "false"
                
                if (savedMac.isNotEmpty()) {
                    runOnUiThread { Toast.makeText(this, "Attempting BT Bridge...", Toast.LENGTH_SHORT).show() }
                    
                    if (startBtTcpBridge(savedMac)) {
                        bridgeStatus = "true"
                        runOnUiThread { Toast.makeText(this, "BT Bridge Active!", Toast.LENGTH_SHORT).show() }
                    } else {
                        runOnUiThread { Toast.makeText(this, "BT Bridge Failed!", Toast.LENGTH_LONG).show() }
                    }
                }

                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this@MainActivity))
                }
                
                val py = Python.getInstance()
                py.getModule("os").get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)
                val rnsBackend = py.getModule("rns_backend")
                
                // CRITICAL: We pass the bridgeStatus as a clean string
                val myAddr = rnsBackend.callAttr("start_rns", filesDir.absolutePath, bridgeStatus, this@MainActivity).toString()

                runOnUiThread { addressDisplay.text = "My Address: $myAddr\nBridge: $bridgeStatus" }

            } catch (e: Exception) {
                runOnUiThread { addressDisplay.text = "Error: ${e.message}" }
            }
        }.start()
    }

    private fun startBtTcpBridge(mac: String): Boolean {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            val device = adapter.getRemoteDevice(mac)
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            
            // Try Insecure for RNodes
            btSocket = device.createInsecureRfcommSocketToServiceRecord(uuid)
            btSocket?.connect()

            if (tcpServer != null && !tcpServer!!.isClosed) tcpServer!!.close()
            tcpServer = ServerSocket(4321)
            tcpServer?.reuseAddress = true

            Thread { bridgeData() }.start()
            true
        } catch (e: Exception) {
            Log.e("RNS_HELLO", "Bridge Error: ${e.message}")
            false
        }
    }

    private fun bridgeData() {
        try {
            tcpClient = tcpServer?.accept()
            val btIn = btSocket?.inputStream
            val btOut = btSocket?.outputStream
            val tcpIn = tcpClient?.inputStream
            val tcpOut = tcpClient?.outputStream

            Thread {
                try {
                    val buffer = ByteArray(1024)
                    var bytes: Int
                    while (tcpIn!!.read(buffer).also { bytes = it } > 0) {
                        btOut?.write(buffer, 0, bytes)
                    }
                } catch (e: Exception) {}
            }.start()

            Thread {
                try {
                    val buffer = ByteArray(1024)
                    var bytes: Int
                    while (btIn!!.read(buffer).also { bytes = it } > 0) {
                        tcpOut?.write(buffer, 0, bytes)
                    }
                } catch (e: Exception) {}
            }.start()
        } catch (e: Exception) {}
    }

    private fun saveMacAndExit(mac: String) {
        getSharedPreferences("rns_prefs", MODE_PRIVATE).edit().putString("last_mac", mac).commit()
        runOnUiThread { Toast.makeText(this, "MAC Saved. RESTART APP!", Toast.LENGTH_LONG).show() }
        Thread { Thread.sleep(1500); android.os.Process.killProcess(android.os.Process.myPid()) }.start()
    }

    override fun onTextReceived(senderHash: String, text: String) {
        runOnUiThread { Toast.makeText(this, "LXM: $text", Toast.LENGTH_LONG).show() }
    }
    override fun onImageReceived(senderHash: String, imagePath: String) {}
}