package com.rnshello

import android.Manifest
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

class MainActivity : AppCompatActivity(), RnsCallback {

    private lateinit var addressDisplay: TextView
    private lateinit var messageInput: EditText
    private var destinationAddress: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addressDisplay = findViewById(R.id.addressDisplay)
        messageInput = findViewById(R.id.messageInput)
        val btnSend = findViewById<Button>(R.id.btnSend)

        // Request permissions for Bluetooth (RNode)
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        
        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        } else {
            initRns()
        }

        btnSend.setOnClickListener {
            val input = messageInput.text.toString().trim()
            if (input.startsWith("connect:")) {
                val mac = input.substring(8).trim()
                connectToRNode(mac)
                messageInput.setText("")
            } else if (input.startsWith("dest:")) {
                destinationAddress = input.substring(5).trim()
                Toast.makeText(this, "Dest set", Toast.LENGTH_SHORT).show()
                messageInput.setText("")
            } else if (destinationAddress.isNotEmpty() && input.isNotEmpty()) {
                Thread {
                    try {
                        val py = Python.getInstance()
                        py.getModule("rns_backend").callAttr("send_text", destinationAddress, input)
                    } catch (e: Exception) { Log.e("RNS_HELLO", "Send Error: ${e.message}") }
                }.start()
                messageInput.setText("")
            }
        }
    }

    private fun initRns() {
        Thread {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this))
                }
                val py = Python.getInstance()
                val os = py.getModule("os")
                os.get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)

                val rnsBackend = py.getModule("rns_backend")
                val myAddr = rnsBackend.callAttr("start_rns", filesDir.absolutePath, "", this).toString()

                runOnUiThread { addressDisplay.text = "My Address: $myAddr" }
            } catch (e: Exception) {
                Log.e("RNS_HELLO", "Init Error: ${e.message}")
            }
        }.start()
    }

    private fun connectToRNode(mac: String) {
        Thread {
            try {
                val py = Python.getInstance()
                val rnsBackend = py.getModule("rns_backend")
                val success = rnsBackend.callAttr("connect_rnode_bluetooth", mac).toBoolean()
                runOnUiThread {
                    Toast.makeText(this, if(success) "BT Command Sent" else "BT Error", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("RNS_HELLO", "BT Call Error: ${e.message}")
            }
        }.start()
    }

    override fun onTextReceived(senderHash: String, text: String) {
        runOnUiThread { Toast.makeText(this, "From $senderHash: $text", Toast.LENGTH_LONG).show() }
    }

    override fun onImageReceived(senderHash: String, imagePath: String) {}
}