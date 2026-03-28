package com.rnshello

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class MainActivity : AppCompatActivity(), RnsCallback {

    private lateinit var addressDisplay: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addressDisplay = findViewById(R.id.addressDisplay)

        // 1. Initialize Chaquopy
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // 2. Start RNS in a Background Thread (To prevent startup crash)
        Thread {
            try {
                val py = Python.getInstance()
                
                // Set the HOME environment variable for Python/RNS
                val os = py.getModule("os")
                os.get("environ").callAttr("__setitem__", "HOME", filesDir.absolutePath)

                val rnsBackend = py.getModule("rns_backend")
                val storagePath = filesDir.absolutePath
                
                // Start RNS and get our address
                val myAddr = rnsBackend.callAttr("start_rns", storagePath, this).toString()

                // Update UI on the main thread
                runOnUiThread {
                    addressDisplay.text = "My Address: $myAddr"
                    Log.d("RNS_HELLO", "RNS Started: $myAddr")
                }
            } catch (e: Exception) {
                Log.e("RNS_HELLO", "Failed to start RNS: ${e.message}")
            }
        }.start()
    }

    // Callbacks from Python
    override fun onTextReceived(senderHash: String, text: String) {
        runOnUiThread { Log.d("RNS_HELLO", "Text from $senderHash: $text") }
    }

    override fun onImageReceived(senderHash: String, imagePath: String) {
        runOnUiThread { Log.d("RNS_HELLO", "Image from $senderHash at: $imagePath") }
    }
}