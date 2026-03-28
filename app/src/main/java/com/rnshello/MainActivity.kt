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
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.util.UUID

class MainActivity : AppCompatActivity(), RnsCallback {

    private lateinit var addressDisplay: TextView
    private lateinit var messageInput: EditText
    private lateinit var listAdapter: ArrayAdapter<String>
    private val discoveredNodes = mutableListOf<String>()
    
    private var destinationAddress: String = ""
    private var btSocket: BluetoothSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addressDisplay = findViewById(R.id.addressDisplay)
        messageInput = findViewById(R.id.messageInput)
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnAttach = findViewById<Button>(R.id.btnAttach)
        val listView = findViewById<ListView>(R.id.chatRecyclerView)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, discoveredNodes)
        listView.adapter = listAdapter
        listView.setOnItemClickListener { _, _, i, _ ->
            destinationAddress = discoveredNodes[i]
            Toast.makeText(this, "Target: $destinationAddress", Toast.LENGTH_SHORT).show()
        }

        checkPermissions()

        btnSend.setOnClickListener {
            val input = messageInput.text.toString().trim()
            if (input.startsWith("connect:")) {
                saveMacAndExit(input.substring(8).trim().uppercase())
            } else if (input == "announce") {
                Thread { Python.getInstance().getModule("rns_backend").callAttr("announce_now") }.start()
            } else if (destinationAddress.isNotEmpty() && input.isNotEmpty()) {
                Thread { Python.getInstance().getModule("rns_backend").callAttr("send_text", destinationAddress, input) }.start()
                messageInput.setText("")
            }
        }

        btnAttach.setOnClickListener {
            if (destinationAddress.isEmpty()) {
                Toast.makeText(this, "Select a node first!", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                startActivityForResult(intent, 101)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            Thread {
                try {
                    // --- THE COLUMBA IMAGE CRUNCHER ---
                    // 1. Decode with scaling to reduce memory footprint
                    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val stream = contentResolver.openInputStream(uri)
                    val rawBitmap = BitmapFactory.decodeStream(stream, null, options)
                    
                    // 2. Target mesh size: WebP at 20% quality
                    val tempFile = File(cacheDir, "outbound.webp")
                    val out = FileOutputStream(tempFile)
                    rawBitmap?.compress(Bitmap.CompressFormat.WEBP, 20, out)
                    out.close()
                    
                    Log.d("RNS_HELLO", "Compressed image size: ${tempFile.length() / 1024} KB")

                    // 3. Hand path to Python
                    val py = Python.getInstance()
                    py.getModule("rns_backend").callAttr("send_image", destinationAddress, tempFile.absolutePath)
                    
                    runOnUiThread { Toast.makeText(this, "WebP Sent (${tempFile.length()/1024}KB)", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    Log.e("RNS_HELLO", "Image compression failed: ${e.message}")
                }
            }.start()
        }
    }

    // --- STANDARD RNS CALLBACKS ---
    override fun onAnnounceReceived(hexAddress: String) {
        runOnUiThread {
            if (!discoveredNodes.contains(hexAddress)) {
                discoveredNodes.add(hexAddress)
                listAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun onTextReceived(senderHash: String, text: String) {
        runOnUiThread { Toast.makeText(this, "$senderHash: $text", Toast.LENGTH_LONG).show() }
    }

    override fun onImageReceived(senderHash: String, imagePath: String) {
        runOnUiThread {
            // Display the received WebP in a popup
            val dialog = android.app.Dialog(this)
            val imgView = ImageView(this)
            imgView.setImageBitmap(BitmapFactory.decodeFile(imagePath))
            dialog.setContentView(imgView)
            dialog.show()
            Toast.makeText(this, "Image from $senderHash", Toast.LENGTH_SHORT).show()
        }
    }

    // (Permissions and Bridge code omitted for brevity but preserved in local file)
    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_ADMIN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        } else { startStack() }
    }

    private fun startStack() {
        val savedMac = getSharedPreferences("rns_prefs", MODE_PRIVATE).getString("last_mac", "") ?: ""
        Thread {
            try {
                var useBridge = "false"
                if (savedMac.isNotEmpty()) if (startBtTcpBridge(savedMac)) useBridge = "true"
                if (!Python.isStarted()) Python.start(AndroidPlatform(this@MainActivity))
                val py = Python.getInstance()
                py.getModule("os").get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)
                val addr = py.getModule("rns_backend").callAttr("start_rns", filesDir.absolutePath, useBridge, this@MainActivity).toString()
                runOnUiThread { addressDisplay.text = "Addr: $addr\nBT Bridge: $useBridge" }
            } catch (e: Exception) { runOnUiThread { addressDisplay.text = "Error: ${e.message}" } }
        }.start()
    }

    private fun startBtTcpBridge(mac: String): Boolean {
        return try {
            val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac)
            val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            btSocket = m.invoke(device, 1) as BluetoothSocket
            btSocket?.connect()
            tcpServer = ServerSocket(7633)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startStack()
    }

    private fun saveMacAndExit(mac: String) {
        getSharedPreferences("rns_prefs", MODE_PRIVATE).edit().putString("last_mac", mac).commit()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}