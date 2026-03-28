package com.rnshello

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class MainActivity : AppCompatActivity(), RnsCallback {

    private lateinit var addressDisplay: TextView
    private lateinit var messageInput: EditText
    private lateinit var btSpinner: Spinner
    private lateinit var discoveredNodesListView: ListView
    private lateinit var discoveredNodesAdapter: ArrayAdapter<String>
    
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var targetNodeInfo: TextView

    private val discoveredNodes = mutableListOf<String>()
    private var destinationAddress: String = ""
    private var ownAddress: String = "" 

    private var btSocket: BluetoothSocket? = null
    private var tcpServer: ServerSocket? = null
    private var tcpClient: Socket? = null
    private var isBridging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addressDisplay = findViewById(R.id.addressDisplay)
        messageInput = findViewById(R.id.messageInput)
        btSpinner = findViewById(R.id.btSpinner)
        discoveredNodesListView = findViewById(R.id.discoveredNodesList)
        targetNodeInfo = findViewById(R.id.targetNodeInfo)
        
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnAttach = findViewById<Button>(R.id.btnAttach)
        val btnBroadcast = findViewById<Button>(R.id.btnBroadcast)
        val btnConnectBt = findViewById<Button>(R.id.btnConnectBt)
        val btnRefreshBt = findViewById<Button>(R.id.btnRefreshBt)

        discoveredNodesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, discoveredNodes)
        discoveredNodesListView.adapter = discoveredNodesAdapter
        discoveredNodesListView.setOnItemClickListener { _, _, i, _ ->
            destinationAddress = discoveredNodes[i]
            targetNodeInfo.text = "Chatting with: ${destinationAddress.take(8)}..."
            Toast.makeText(this, "Targeting: $destinationAddress", Toast.LENGTH_SHORT).show()
        }

        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatAdapter = ChatAdapter()
        chatRecyclerView.layoutManager = LinearLayoutManager(this)
        chatRecyclerView.adapter = chatAdapter

        btnRefreshBt.setOnClickListener { updateBtSpinner() }
        
        btnConnectBt.setOnClickListener {
            if (btSpinner.selectedItem != null) {
                val selected = btSpinner.selectedItem.toString()
                val mac = selected.substringAfterLast("\n")
                hotConnectBt(mac)
            }
        }

        btnBroadcast.setOnClickListener {
            Thread { 
                try { Python.getInstance().getModule("rns_backend").callAttr("announce_now") 
                } catch (e: Exception) { Log.e("RNS", "Announce Err: ${e.message}") }
            }.start()
            Toast.makeText(this, "Broadcast Sent", Toast.LENGTH_SHORT).show()
        }

        btnSend.setOnClickListener {
            val input = messageInput.text.toString().trim()
            if (destinationAddress.isNotEmpty() && input.isNotEmpty()) {
                val message = Message(ownAddress, input, System.currentTimeMillis(), false, true)
                chatAdapter.addMessage(message)
                chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                Thread { 
                    try { Python.getInstance().getModule("rns_backend").callAttr("send_text", destinationAddress, input) 
                    } catch (e: Exception) { Log.e("RNS", "Send Err: ${e.message}") }
                }.start()
                messageInput.setText("")
            } else { Toast.makeText(this, "Tap a node first!", Toast.LENGTH_SHORT).show() }
        }

        btnAttach.setOnClickListener {
            if (destinationAddress.isEmpty()) {
                Toast.makeText(this, "Select a node first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 101)
        }

        checkPermissions()
    }

    private fun updateBtSpinner() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        val btNames = adapter.bondedDevices.map { "${it.name}\n${it.address}" }
        btSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, btNames)
    }

    private fun hotConnectBt(mac: String) {
        Thread {
            try {
                isBridging = false
                btSocket?.close()
                tcpServer?.close()
                tcpClient?.close()
                val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac)
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                btSocket = m.invoke(device, 1) as BluetoothSocket
                btSocket?.connect()
                tcpServer = ServerSocket()
                tcpServer?.reuseAddress = true
                tcpServer?.bind(InetSocketAddress("127.0.0.1", 7633))
                isBridging = true
                Thread { runBridgeLoop() }.start()
                Python.getInstance().getModule("rns_backend").callAttr("inject_rnode")
                runOnUiThread { Toast.makeText(this, "RNode Active", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "BT Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun runBridgeLoop() {
        while (isBridging) {
            try {
                val client = tcpServer?.accept() ?: break
                tcpClient = client
                val btIn = btSocket?.inputStream
                val btOut = btSocket?.outputStream
                val tcpIn = client.inputStream
                val tcpOut = client.outputStream
                val t1 = Thread { 
                    try {
                        val buffer = ByteArray(1024)
                        var bytesRead = 0
                        while (isBridging && client.isConnected && tcpIn.read(buffer).also { bytesRead = it } != -1) {
                            btOut?.write(buffer, 0, bytesRead)
                            btOut?.flush()
                        }
                    } catch (e: Exception) {} finally { try { client.close() } catch (e: Exception) {} }
                }
                val t2 = Thread { 
                    try {
                        val buffer = ByteArray(1024)
                        var bytesRead = 0
                        while (isBridging && client.isConnected && btIn?.read(buffer).also { bytesRead = it ?: -1 } != -1) {
                            tcpOut.write(buffer, 0, bytesRead)
                            tcpOut.flush()
                        }
                    } catch (e: Exception) {} finally { try { client.close() } catch (e: Exception) {} }
                }
                t1.start(); t2.start()
            } catch (e: Exception) { break }
        }
    }

    private fun startStack() {
        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                val py = Python.getInstance()
                py.getModule("os").get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)
                val addr = py.getModule("rns_backend").callAttr("start_rns", filesDir.absolutePath, this).toString()
                ownAddress = addr
                runOnUiThread { 
                    addressDisplay.text = "My Addr: $addr"
                    updateBtSpinner()
                }
            } catch (e: Exception) { Log.e("RNS", e.message ?: "") }
        }.start()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startStack()
    }

    override fun onAnnounceReceived(hexAddress: String) {
        runOnUiThread {
            if (!discoveredNodes.contains(hexAddress)) {
                discoveredNodes.add(hexAddress)
                discoveredNodesAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun onNewMessage(senderHash: String, content: String, timestamp: Long, isImage: Boolean, isSent: Boolean) {
        val msg = Message(senderHash, content, timestamp, isImage, isSent)
        runOnUiThread {
            chatAdapter.addMessage(msg)
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            Thread {
                try {
                    // --- THUMBNAIL CRUNCHER LOGIC ---
                    val stream = contentResolver.openInputStream(uri)
                    val original = BitmapFactory.decodeStream(stream)
                    
                    // 1. Calculate scaling to max 320px
                    val scale = 320f / Math.max(original.width, original.height)
                    val targetW = (original.width * scale).toInt()
                    val targetH = (original.height * scale).toInt()
                    
                    // 2. Create scaled thumbnail
                    val thumb = Bitmap.createScaledBitmap(original, targetW, targetH, true)
                    
                    // 3. Compress to WebP at 10% quality
                    val tempFile = File(cacheDir, "thumb_out.webp")
                    val out = FileOutputStream(tempFile)
                    thumb.compress(Bitmap.CompressFormat.WEBP, 10, out)
                    out.close()
                    
                    Log.d("RNS_HELLO", "Thumbnail generated: ${tempFile.length() / 1024} KB")

                    val message = Message(ownAddress, tempFile.absolutePath, System.currentTimeMillis(), true, true)
                    runOnUiThread {
                        chatAdapter.addMessage(message)
                        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                    }
                    
                    Python.getInstance().getModule("rns_backend").callAttr("send_image", destinationAddress, tempFile.absolutePath)
                } catch (e: Exception) { Log.e("RNS_HELLO", "Cruncher failed: ${e.message}") }
            }.start()
        }
    }
}