package com.rnshello

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class MainActivity : AppCompatActivity(), RnsCallback {

    private lateinit var addressDisplay: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        addressDisplay = findViewById(R.id.addressDisplay)

        // 1. Request Bluetooth Permissions (Critical for Android 12+)
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions, 1)
        } else {
            initRns()
        }
    }

    private fun initRns() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        Thread {
            try {
                val py = Python.getInstance()
                val rnsBackend = py.getModule("rns_backend")
                
                // IMPORTANT: If you know your RNode's BT MAC, put it here, 
                // e.g., "AA:BB:CC:DD:EE:FF". If empty, it will search paired devices.
                val btMac = "" 
                
                val myAddr = rnsBackend.callAttr("start_rns", filesDir.absolutePath, btMac, this).toString()

                runOnUiThread {
                    addressDisplay.text = "My Address: $myAddr\nConnected to RNode: BT"
                }
            } catch (e: Exception) {
                runOnUiThread { addressDisplay.text = "RNS Error: ${e.message}" }
            }
        }.start()
    }

    override fun onTextReceived(senderHash: String, text: String) {
        // UI code to show message
    }

    override fun onImageReceived(senderHash: String, imagePath: String) {
        // UI code to show image
    }
}