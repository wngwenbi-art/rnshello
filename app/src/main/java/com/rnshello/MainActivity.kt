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
    private lateinit var nodeList: ListView
    private val discoveredNodes = mutableListOf<String>()
    private lateinit var listAdapter: ArrayAdapter<String>
    
    private var destinationAddress: String = ""
    private var btSocket: BluetoothSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addressDisplay = findViewById(R.id.addressDisplay)
        messageInput = findViewById(R.id.messageInput)
        nodeList = findViewById(R.id.chatRecyclerView) as? ListView ?: ListView(this) // Fallback if XML is still RecyclerView
        
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnAttach = findViewById<Button>(R.id.btnAttach)

        // Setup the Node List UI
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, discoveredNodes)
        findViewById<ListView>(R.id.chatRecyclerView).adapter = listAdapter
        
        findViewById<ListView>(R.id.chatRecyclerView).setOnItemClickListener { _, _, position, _ ->
            destinationAddress = discoveredNodes[position]
            Toast.makeText(this, "Chatting with: $destinationAddress", Toast.LENGTH_SHORT).show()
        }

        checkPermissions()

        btnSend.setOnClickListener {
            val input = messageInput.text.toString().trim()
            if (input.startsWith("connect:")) {
                saveMacAndExit(input.substring(8).trim().uppercase())
            } else if (input == "announce") {
                Thread { Python.getInstance().getModule("rns_backend").callAttr("announce_now") }.start()
                Toast.makeText(this, "Announce Sent", Toast.LENGTH_SHORT).show()
            } else if (destinationAddress.isNotEmpty() && input.isNotEmpty()) {
                Thread { Python.getInstance().getModule("rns_backend").callAttr("send_text", destinationAddress, input) }.start()
                messageInput.setText("")
            } else {
                Toast.makeText(this, "Select a node from list or set dest:HEX", Toast.LENGTH_SHORT).show()
            }
        }

        btnAttach.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, 101)
        }
    }

    private fun checkPermissions() {
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
            val server = ServerSocket(7633)
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
        } catch (e: Exception) { false }
    }

    private fun saveMacAndExit(mac: String) {
        getSharedPreferences("rns_prefs", MODE_PRIVATE).edit().putString("last_mac", mac).commit()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onAnnounceReceived(hexAddress: String) {
        runOnUiThread {
            if (!discoveredNodes.contains(hexAddress)) {
                discoveredNodes.add(hexAddress)
                listAdapter.notifyDataSetChanged()
                Toast.makeText(this, "New node discovered!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onTextReceived(senderHash: String, text: String) {
        runOnUiThread { Toast.makeText(this, "MSG from $senderHash: $text", Toast.LENGTH_LONG).show() }
    }

    override fun onImageReceived(senderHash: String, imagePath: String) {
        runOnUiThread { Toast.makeText(this, "IMG from $senderHash: Saved to $imagePath", Toast.LENGTH_LONG).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK && data != null && destinationAddress.isNotEmpty()) {
            Thread {
                try {
                    val stream = contentResolver.openInputStream(data.data!!)
                    val bitmap = BitmapFactory.decodeStream(stream)
                    val tempFile = File(cacheDir, "send.webp")
                    val out = FileOutputStream(tempFile)
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 30, out) // Compressed for LoRa
                    out.close()
                    Python.getInstance().getModule("rns_backend").callAttr("send_image", destinationAddress, tempFile.absolutePath)
                    runOnUiThread { Toast.makeText(this@MainActivity, "Image Sent", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) { Log.e("RNS_HELLO", "Img Err: ${e.message}") }
            }.start()
        }
    }
}