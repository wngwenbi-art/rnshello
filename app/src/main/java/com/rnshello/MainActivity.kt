package com.rnshello

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Intent
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
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class MainActivity : AppCompatActivity(), RnsCallback {

    private lateinit var addressDisplay: TextView
    private lateinit var messageInput: EditText
    private var destinationAddress: String = ""
    private var btSocket: BluetoothSocket? = null
    private var lastBtError: String = ""

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
            } else if (destinationAddress.isNotEmpty() && input.isNotEmpty()) {
                Thread {
                    try {
                        Python.getInstance().getModule("rns_backend").callAttr("send_text", destinationAddress, input)
                    } catch (e: Exception) { Log.e("RNS_HELLO", "Send Err: ${e.message}") }
                }.start()
            }
        }
    }

    private fun startStack() {
        val savedMac = getSharedPreferences("rns_prefs", MODE_PRIVATE).getString("last_mac", "") ?: ""

        Thread {
            try {
                var bridgeStatus = "false"
                if (savedMac.isNotEmpty()) {
                    if (startBtTcpBridge(savedMac)) {
                        bridgeStatus = "true"
                    }
                }

                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this@MainActivity))
                }
                
                val py = Python.getInstance()
                py.getModule("os").get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)
                val rnsBackend = py.getModule("rns_backend")
                
                val myAddr = rnsBackend.callAttr("start_rns", filesDir.absolutePath, bridgeStatus, this@MainActivity).toString()

                runOnUiThread { 
                    val status = if (bridgeStatus == "true") "CONNECTED" else "ERR: $lastBtError"
                    addressDisplay.text = "Addr: $myAddr\nBT ($savedMac): $status" 
                }

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
            
            btSocket = device.createInsecureRfcommSocketToServiceRecord(uuid)
            btSocket?.connect()

            val server = ServerSocket(4321)
            server.reuseAddress = true
            Thread {
                val client = server.accept()
                val btIn = btSocket?.inputStream
                val btOut = btSocket?.outputStream
                val tcpIn = client?.inputStream
                val tcpOut = client?.outputStream

                Thread { try { tcpIn?.copyTo(btOut!!) } catch (e: Exception) {} }.start()
                Thread { try { btIn?.copyTo(tcpOut!!) } catch (e: Exception) {} }.start()
            }.start()
            true
        } catch (e: Exception) {
            lastBtError = e.message ?: "Unknown BT Error"
            false
        }
    }

    override fun onTextReceived(senderHash: String, text: String) {
        runOnUiThread { Toast.makeText(this, "LXM: $text", Toast.LENGTH_LONG).show() }
    }
    override fun onImageReceived(senderHash: String, imagePath: String) {}
}