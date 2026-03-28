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
import java.util.*

class MainActivity : AppCompatActivity(), RnsCallback {

    private lateinit var addressDisplay: TextView
    private lateinit var messageInput: EditText
    private lateinit var listAdapter: ArrayAdapter<String>
    private val discoveredNodes = mutableListOf<String>()
    private var destinationAddress: String = ""
    
    private var btSocket: BluetoothSocket? = null
    private var tcpServer: ServerSocket? = null
    private var currentBridgeMac: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addressDisplay = findViewById(R.id.addressDisplay)
        messageInput = findViewById(R.id.messageInput)
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnAttach = findViewById<Button>(R.id.btnAttach)
        val btnBroadcast = findViewById<Button>(R.id.btnBroadcast)
        val btnConnectBt = findViewById<Button>(R.id.btnConnectBt)
        val btSpinner = findViewById<Spinner>(R.id.btSpinner)
        val listView = findViewById<ListView>(R.id.chatRecyclerView)

        // Setup Discovered Nodes List
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, discoveredNodes)
        listView.adapter = listAdapter
        listView.setOnItemClickListener { _, _, i, _ ->
            destinationAddress = discoveredNodes[i]
            Toast.makeText(this, "Targeting: $destinationAddress", Toast.LENGTH_SHORT).show()
        }

        // Bluetooth Selection Spinner
        val pairedDevices = getPairedDevices()
        val btNames = pairedDevices.map { "${it.name}\n${it.address}" }
        btSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, btNames)

        checkPermissions()

        btnConnectBt.setOnClickListener {
            val selected = btSpinner.selectedItem.toString()
            val mac = selected.substringAfterLast("\n")
            HotConnectBt(mac)
        }

        btnBroadcast.setOnClickListener {
            Thread { Python.getInstance().getModule("rns_backend").callAttr("announce_now") }.start()
            Toast.makeText(this, "Broadcast Sent", Toast.LENGTH_SHORT).show()
        }

        btnSend.setOnClickListener {
            val input = messageInput.text.toString().trim()
            if (destinationAddress.isNotEmpty() && input.isNotEmpty()) {
                Thread { Python.getInstance().getModule("rns_backend").callAttr("send_text", destinationAddress, input) }.start()
                messageInput.setText("")
            } else { Toast.makeText(this, "Tap a node first!", Toast.LENGTH_SHORT).show() }
        }

        btnAttach.setOnClickListener {
            if (destinationAddress.isEmpty()) return@setOnClickListener
            startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 101)
        }
    }

    private fun getPairedDevices(): List<android.bluetooth.BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            adapter.bondedDevices.toList()
        } else listOf()
    }

    private fun HotConnectBt(mac: String) {
        Thread {
            try {
                // 1. Close existing bridge
                btSocket?.close()
                tcpServer?.close()
                
                // 2. Open new Bluetooth socket
                val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac)
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                btSocket = m.invoke(device, 1) as BluetoothSocket
                btSocket?.connect()

                // 3. Start TCP Bridge
                tcpServer = ServerSocket(7633)
                Thread {
                    try {
                        val client = tcpServer?.accept()
                        val btIn = btSocket?.inputStream
                        val btOut = btSocket?.outputStream
                        val tcpIn = client?.inputStream
                        val tcpOut = client?.outputStream
                        Thread { try { tcpIn?.copyTo(btOut!!) } catch (e: Exception) {} }.start()
                        Thread { try { btIn?.copyTo(tcpOut!!) } catch (e: Exception) {} }.start()
                    } catch (e: Exception) {}
                }.start()

                // 4. Inject into Python
                Python.getInstance().getModule("rns_backend").callAttr("inject_rnode")
                currentBridgeMac = mac
                runOnUiThread { Toast.makeText(this, "RNode Active: $mac", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "BT Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun startStack() {
        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                val py = Python.getInstance()
                py.getModule("os").get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)
                val addr = py.getModule("rns_backend").callAttr("start_rns", filesDir.absolutePath, this).toString()
                runOnUiThread { addressDisplay.text = "My Addr: $addr" }
            } catch (e: Exception) { Log.e("RNS", e.message ?: "") }
        }.start()
    }

    override fun onAnnounceReceived(hexAddress: String) {
        runOnUiThread {
            if (!discoveredNodes.contains(hexAddress)) {
                discoveredNodes.add(hexAddress)
                listAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun onTextReceived(senderHash: String, text: String) {
        runOnUiThread { Toast.makeText(this, "From $senderHash: $text", Toast.LENGTH_LONG).show() }
    }

    override fun onImageReceived(senderHash: String, imagePath: String) {
        runOnUiThread {
            val dialog = android.app.Dialog(this)
            val imgView = ImageView(this)
            imgView.setImageBitmap(BitmapFactory.decodeFile(imagePath))
            dialog.setContentView(imgView)
            dialog.show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            Thread {
                val stream = contentResolver.openInputStream(data.data!!)
                val tempFile = File(cacheDir, "out.webp")
                val out = FileOutputStream(tempFile)
                BitmapFactory.decodeStream(stream)?.compress(Bitmap.CompressFormat.WEBP, 20, out)
                out.close()
                Python.getInstance().getModule("rns_backend").callAttr("send_image", destinationAddress, tempFile.absolutePath)
                runOnUiThread { Toast.makeText(this, "Sent WebP", Toast.LENGTH_SHORT).show() }
            }.start()
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
        } else { startStack() }
    }
}