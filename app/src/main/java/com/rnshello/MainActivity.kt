
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
    val prefs = getSharedPreferences("rns_prefs", MODE_PRIVATE)
    val savedMac = prefs.getString("last_mac", "") ?: ""

    Thread {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            val py = Python.getInstance()
            val rnsBackend = py.getModule("rns_backend")
            
            // Pass the savedMac into start_rns
            val myAddr = rnsBackend.callAttr("start_rns", filesDir.absolutePath, savedMac, this).toString()

            runOnUiThread { addressDisplay.text = "My Address: $myAddr\nBT: $savedMac" }
        } catch (e: Exception) {
            Log.e("RNS_HELLO", "Init Error: ${e.message}")
        }
    }.start()
}
private fun connectToRNode(mac: String) {
    Toast.makeText(this, "Saving MAC and Restarting RNS...", Toast.LENGTH_LONG).show()
    
    // Save the MAC address to a local file so it persists
    val prefs = getSharedPreferences("rns_prefs", MODE_PRIVATE)
    prefs.edit().putString("last_mac", mac).apply()

    // Restart the activity to reload Python with the new config
    val intent = intent
    finish()
    startActivity(intent)
}
    