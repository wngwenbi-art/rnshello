package com.rnshello

import android.Manifest
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
        val btnAttach = findViewById<Button>(R.id.btnAttach)

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
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
                Toast.makeText(this, "Dest set to: " + destinationAddress, Toast.LENGTH_SHORT).show()
                messageInput.setText("")
            } else if (destinationAddress.isNotEmpty() && input.isNotEmpty()) {
                sendText(input)
                messageInput.setText("")
            } else {
                Toast.makeText(this, "Need Dest or Message", Toast.LENGTH_SHORT).show()
            }
        }

        btnAttach.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, 101)
        }
    }

    private fun initRns() {
        // Retrieve the saved MAC address
        val prefs = getSharedPreferences("rns_prefs", MODE_PRIVATE)
        val savedMac = prefs.getString("last_mac", "") ?: ""

        Thread {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this))
                }
                val py = Python.getInstance()
                val os = py.getModule("os")
                os.get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)

                val rnsBackend = py.getModule("rns_backend")
                val myAddr = rnsBackend.callAttr("start_rns", filesDir.absolutePath, savedMac, this).toString()

                runOnUiThread { 
                    val macText = if (savedMac.isNotEmpty()) savedMac else "None"
                    addressDisplay.text = "My Address: " + myAddr + "\nBT MAC: " + macText
                }
            } catch (e: Exception) {
                Log.e("RNS_HELLO", "Init Error: " + e.message)
                runOnUiThread { addressDisplay.text = "RNS Error: " + e.message }
            }
        }.start()
    }

    private fun connectToRNode(mac: String) {
        Toast.makeText(this, "Saving MAC and Restarting App...", Toast.LENGTH_LONG).show()
        
        // Save the MAC address
        val prefs = getSharedPreferences("rns_prefs", MODE_PRIVATE)
        prefs.edit().putString("last_mac", mac).apply()

        // Restart the Activity to reload Reticulum
        val intent = intent
        finish()
        startActivity(intent)
    }

    private fun sendText(text: String) {
        Thread {
            try {
                val py = Python.getInstance()
                py.getModule("rns_backend").callAttr("send_text", destinationAddress, text)
            } catch (e: Exception) {
                Log.e("RNS_HELLO", "Send Error: " + e.message)
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            if (destinationAddress.isEmpty()) {
                Toast.makeText(this, "Set destination first!", Toast.LENGTH_SHORT).show()
                return
            }
            Thread {
                try {
                    val stream = contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(stream)
                    val tempFile = File(cacheDir, "outbound.webp")
                    val out = FileOutputStream(tempFile)
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 40, out)
                    out.close()

                    val py = Python.getInstance()
                    py.getModule("rns_backend").callAttr("send_image", destinationAddress, tempFile.absolutePath)
                    runOnUiThread { Toast.makeText(this, "Image Sent", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    Log.e("RNS_HELLO", "Img Send Error: " + e.message)
                }
            }.start()
        }
    }

    override fun onTextReceived(senderHash: String, text: String) {
        runOnUiThread {
            Toast.makeText(this, "From " + senderHash + ": " + text, Toast.LENGTH_LONG).show()
        }
    }

    override fun onImageReceived(senderHash: String, imagePath: String) {
        runOnUiThread {
            Toast.makeText(this, "Image received from " + senderHash, Toast.LENGTH_LONG).show()
        }
    }
}