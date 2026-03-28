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

        btnAttach.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startStack()
        }
    }

    private fun startStack() {
        val prefs = getSharedPreferences("rns_prefs", MODE_PRIVATE)
        val savedMac = prefs.getString("last_mac", "") ?: ""

        Thread {
            try {
                var useBridge = "false"
                if (savedMac.isNotEmpty()) {
                    runOnUiThread { addressDisplay.text = "1. Connecting BT to $savedMac..." }
                    if (startBtTcpBridge(savedMac)) {
                        runOnUiThread { addressDisplay.text = "2. BT Connected! Starting Python..." }
                        useBridge = "true"
                    } else {
                        runOnUiThread { addressDisplay.text = "BT FAILED! Check pairing or power." }
                        return@Thread 
                    }
                }

                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this@MainActivity))
                }
                val py = Python.getInstance()
                py.getModule("os").get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)

                val rnsBackend = py.getModule("rns_backend")
                val myAddr = rnsBackend.callAttr("start_rns", filesDir.absolutePath, useBridge, this@MainActivity).toString()

                runOnUiThread { addressDisplay.text = "My Address: $myAddr\nBT: $savedMac" }

            } catch (e: Exception) {
                runOnUiThread { addressDisplay.text = "RNS Error: ${e.message}" }
            }
        }.start()
    }

    private fun startBtTcpBridge(mac: String): Boolean {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw Exception("No BT Adapter")
            val device = adapter.getRemoteDevice(mac)
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                throw Exception("Missing Permission")
            }
            
            try {
                btSocket = device.createRfcommSocketToServiceRecord(uuid)
                btSocket?.connect()
            } catch (e: Exception) {
                btSocket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                btSocket?.connect()
            }

            if (tcpServer != null && !tcpServer!!.isClosed) tcpServer!!.close()
            tcpServer = ServerSocket(4321)
            tcpServer?.reuseAddress = true

            Thread { bridgeData() }.start()
            return true
        } catch (e: Exception) {
            Log.e("RNS_HELLO", "BT Bridge Failed: ${e.message}")
            return false
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
        runOnUiThread { Toast.makeText(this, "MAC Saved! App closing...", Toast.LENGTH_LONG).show() }
        Thread {
            Thread.sleep(2000)
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onTextReceived(senderHash: String, text: String) {
        runOnUiThread { Toast.makeText(this, "From $senderHash: $text", Toast.LENGTH_LONG).show() }
    }

    override fun onImageReceived(senderHash: String, imagePath: String) {}
}