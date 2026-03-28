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
import java.io.InputStream
import java.io.OutputStream
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
                saveMacAndRestart(mac)
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

        btnAttach.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, 101)
        }
    }

    private fun startStack() {
        val prefs = getSharedPreferences("rns_prefs", MODE_PRIVATE)
        val savedMac = prefs.getString("last_mac", "") ?: ""

        Thread {
            try {
                var useBridge = "false"
                if (savedMac.isNotEmpty()) {
                    runOnUiThread { addressDisplay.text = "Connecting to RNode BT..." }
                    if (startBtTcpBridge(savedMac)) {
                        useBridge = "true"
                    }
                }

                // 2. Start Python after bridge is established
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this))
                }
                val py = Python.getInstance()
                py.getModule("os").get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)

                val rnsBackend = py.getModule("rns_backend")
                val myAddr = rnsBackend.callAttr("start_rns", filesDir.absolutePath, useBridge, this).toString()

                runOnUiThread { addressDisplay.text = "My Address: $myAddr\nBT: $savedMac" }

            } catch (e: Exception) {
                runOnUiThread { addressDisplay.text = "RNS Error: ${e.message}" }
            }
        }.start()
    }

    private fun startBtTcpBridge(mac: String): Boolean {
        try {
            // 1. Connect Bluetooth (SPP UUID)
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            val device = adapter.getRemoteDevice(mac)
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
            
            btSocket = device.createRfcommSocketToServiceRecord(uuid)
            btSocket?.connect()

            // 2. Open Local TCP Server
            tcpServer = ServerSocket(4321)

            // 3. Start bridging in background
            Thread { bridgeData() }.start()

            return true
        } catch (e: Exception) {
            Log.e("RNS_HELLO", "BT Bridge Failed: ${e.message}")
            return false
        }
    }

    private fun bridgeData() {
        try {
            tcpClient = tcpServer?.accept() // Waits for Python to connect
            val btIn = btSocket?.inputStream
            val btOut = btSocket?.outputStream
            val tcpIn = tcpClient?.inputStream
            val tcpOut = tcpClient?.outputStream

            // TCP to BT
            Thread {
                try {
                    val buffer = ByteArray(1024)
                    var bytes: Int
                    while (tcpIn!!.read(buffer).also { bytes = it } > 0) {
                        btOut?.write(buffer, 0, bytes)
                    }
                } catch (e: Exception) {}
            }.start()

            // BT to TCP
            Thread {
                try {
                    val buffer = ByteArray(1024)
                    var bytes: Int
                    while (btIn!!.read(buffer).also { bytes = it } > 0) {
                        tcpOut?.write(buffer, 0, bytes)
                    }
                } catch (e: Exception) {}
            }.start()

        } catch (e: Exception) {
            Log.e("RNS_HELLO", "Bridge Interrupted")
        }
    }

    private fun saveMacAndRestart(mac: String) {
        getSharedPreferences("rns_prefs", MODE_PRIVATE).edit().putString("last_mac", mac).apply()
        finish()
        startActivity(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Image logic unchanged
    }

    override fun onTextReceived(senderHash: String, text: String) {
        runOnUiThread { Toast.makeText(this, "From $senderHash: $text", Toast.LENGTH_LONG).show() }
    }

    override fun onImageReceived(senderHash: String, imagePath: String) {}
}