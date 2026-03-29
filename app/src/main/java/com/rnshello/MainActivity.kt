package com.rnshello

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class MainActivity : AppCompatActivity(), RnsCallback {

    private lateinit var prefs: SharedPreferences
    private var ownHash = ""
    private var targetHash = ""
    private var isBridging = false
    private var btSocket: BluetoothSocket? = null
    private var tcpServer: ServerSocket? = null
    
    private lateinit var chatAdapter: ChatAdapter
    private val discoveredNodes = mutableListOf<String>()
    private lateinit var nodesAdapter: ArrayAdapter<String>
    private var chatRecyclerView: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("rns_database", MODE_PRIVATE)
        
        chatAdapter = ChatAdapter { path -> showBigImage(path) }
        nodesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, discoveredNodes)

        loadSavedNodes()

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            when(item.itemId) {
                R.id.nav_settings -> showSettings()
                R.id.nav_nodes -> showNodes()
                R.id.nav_chat -> showChat()
            }
            true
        }

        if (checkPermissions()) startRns()
        showSettings()
    }

    private fun loadSavedNodes() {
        val savedHashes = prefs.getStringSet("node_list", setOf()) ?: setOf()
        discoveredNodes.clear()
        for (hex in savedHashes) {
            val nick = prefs.getString("nick_$hex", "")
            discoveredNodes.add(if (!nick.isNullOrEmpty()) "$nick ($hex)" else hex)
        }
        nodesAdapter.notifyDataSetChanged()
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.page_settings, null)
        val addressDisplay = view.findViewById<TextView>(R.id.addressDisplay)
        val nickInput = view.findViewById<EditText>(R.id.setNick)
        
        addressDisplay.text = if(ownHash.isEmpty()) "Initializing..." else ownHash
        addressDisplay.setOnClickListener { if(ownHash.isNotEmpty()) showQr(ownHash) }
        nickInput.setText(prefs.getString("my_nickname", "User"))

        val spinRegion = view.findViewById<Spinner>(R.id.spinRegion)
        val spinBw = view.findViewById<Spinner>(R.id.spinBw)
        val spinSf = view.findViewById<Spinner>(R.id.spinSf)
        val spinCr = view.findViewById<Spinner>(R.id.spinCr)

        spinRegion.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Europe (433MHz)", "USA (915MHz)", "Asia (433MHz)"))
        spinBw.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("125 kHz", "62.5 kHz", "31.25 kHz"))
        spinSf.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, (7..12).toList())
        spinCr.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, (5..8).toList())

        view.findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            prefs.edit().apply {
                putString("my_nickname", nickInput.text.toString())
                putInt("sel_region", spinRegion.selectedItemPosition); apply()
            }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        val btSpinner = view.findViewById<Spinner>(R.id.setBtSpinner)
        val updateBT = {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                val names = adapter.bondedDevices.map { "${it.name}\n${it.address}" }
                btSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            }
        }
        updateBT()
        view.findViewById<Button>(R.id.btnRefreshBt).setOnClickListener { updateBT() }
        view.findViewById<Button>(R.id.btnSetConnect).setOnClickListener {
            if (btSpinner.selectedItem != null) hotConnectBt(btSpinner.selectedItem.toString().substringAfterLast("\n"))
        }
        replaceFrame(view)
    }

    private fun showNodes() {
        val view = layoutInflater.inflate(R.layout.page_nodes, null)
        val lv = view.findViewById<ListView>(R.id.nodeListView)
        lv.adapter = nodesAdapter
        lv.setOnItemClickListener { _, _, i, _ ->
            targetHash = discoveredNodes[i].split(" ").last().trim('(', ')')
            findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_chat
        }
        lv.setOnItemLongClickListener { _, _, i, _ ->
            editNodeNickname(discoveredNodes[i].split(" ").last().trim('(', ')')); true
        }
        view.findViewById<Button>(R.id.btnManualAdd).setOnClickListener { manualAddNode() }
        replaceFrame(view)
    }

    private fun showChat() {
        val view = layoutInflater.inflate(R.layout.page_chat, null)
        val header = view.findViewById<TextView>(R.id.targetNodeInfo)
        val nick = prefs.getString("nick_$targetHash", "")
        header.text = if (!nick.isNullOrEmpty()) "Chat: $nick" else "Chat: ${targetHash.take(8)}"

        chatRecyclerView = view.findViewById(R.id.chatRv)
        chatRecyclerView?.layoutManager = LinearLayoutManager(this)
        chatRecyclerView?.adapter = chatAdapter
        
        view.findViewById<Button>(R.id.btnChatSend).setOnClickListener {
            val input = view.findViewById<EditText>(R.id.chatInput)
            val txt = input.text.toString().trim()
            if (targetHash.isNotEmpty() && txt.isNotEmpty()) {
                val id = Python.getInstance().getModule("rns_backend").callAttr("send_text", targetHash, txt).toString()
                onNewMessage(ownHash, txt, System.currentTimeMillis(), false, true, id)
                input.setText("")
            }
        }
        view.findViewById<Button>(R.id.btnChatImg).setOnClickListener {
            if (targetHash.isNotEmpty()) startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 101)
        }
        replaceFrame(view)
    }

    private fun startRns() {
        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                val py = Python.getInstance()
                py.getModule("os").get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)
                val nick = prefs.getString("my_nickname", "User")
                ownHash = py.getModule("rns_backend").callAttr("start_rns", filesDir.absolutePath, this, nick).toString()
            } catch (e: Exception) { Log.e("RNS", e.message ?: "") }
        }.start()
    }

    private fun hotConnectBt(mac: String) {
        Thread {
            try {
                isBridging = false; btSocket?.close(); tcpServer?.close()
                val dev = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac)
                val m = dev.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                btSocket = m.invoke(dev, 1) as BluetoothSocket
                btSocket?.connect()
                tcpServer = ServerSocket(); tcpServer?.reuseAddress = true; tcpServer?.bind(InetSocketAddress("127.0.0.1", 7633))
                isBridging = true
                Thread { bridgeLoop() }.start()
                Python.getInstance().getModule("rns_backend").callAttr("inject_rnode", "433025000", "125000", "17", "8", "6")
                runOnUiThread { Toast.makeText(this, "RNode Connected", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "BT Error: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun bridgeLoop() {
        while(isBridging) {
            try {
                val client = tcpServer?.accept() ?: break
                val btIn = btSocket?.inputStream; val btOut = btSocket?.outputStream
                val tcpIn = client.inputStream; val tcpOut = client.outputStream
                Thread { try { val buf = ByteArray(1024); var r = 0; while(client.isConnected && tcpIn.read(buf).also{r=it}!=-1) btOut?.write(buf,0,r) } catch(e:Exception){} finally {client.close()} }.start()
                Thread { try { val buf = ByteArray(1024); var r = 0; while(client.isConnected && btIn!!.read(buf).also{r=it}!=-1) tcpOut.write(buf,0,r) } catch(e:Exception){} finally {client.close()} }.start()
            } catch (e: Exception) { break }
        }
    }

    override fun onAnnounceReceived(hex: String) {
        val currentSet = prefs.getStringSet("node_list", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (!currentSet.contains(hex)) {
            currentSet.add(hex); prefs.edit().putStringSet("node_list", currentSet).apply()
            runOnUiThread { loadSavedNodes() }
        }
    }

    override fun onNewMessage(s: String, c: String, t: Long, img: Boolean, sent: Boolean, id: String) {
        runOnUiThread { 
            chatAdapter.addMessage(Message(senderHash = s, content = c, timestamp = t, isImage = img, isSent = sent), id) 
            chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    override fun onMessageDelivered(id: String) { runOnUiThread { chatAdapter.markDelivered(id) } }

    private fun manualAddNode() {
        val input = EditText(this); input.hint = "Hex Address"
        AlertDialog.Builder(this).setTitle("Manual Add").setView(input).setPositiveButton("Add") { _,_ -> onAnnounceReceived(input.text.toString().trim()) }.show()
    }

    private fun editNodeNickname(hex: String) {
        val input = EditText(this); input.hint = "Nickname"
        AlertDialog.Builder(this).setTitle("Set Nickname").setView(input).setPositiveButton("Save") { _,_ -> 
            prefs.edit().putString("nick_$hex", input.text.toString()).apply(); loadSavedNodes()
        }.show()
    }

    private fun showBigImage(path: String) {
        val img = ImageView(this); img.setImageBitmap(BitmapFactory.decodeFile(path))
        AlertDialog.Builder(this).setView(img).setPositiveButton("Close", null).show()
    }

    private fun replaceFrame(v: View) {
        val container = findViewById<FrameLayout>(R.id.fragment_container)
        container.removeAllViews(); container.addView(v)
    }

    private fun checkPermissions(): Boolean {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { p.add(Manifest.permission.BLUETOOTH_CONNECT); p.add(Manifest.permission.BLUETOOTH_SCAN) }
        if (p.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) { ActivityCompat.requestPermissions(this, p.toTypedArray(), 1); return false }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startRns()
    }
}