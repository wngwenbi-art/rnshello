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
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

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

    // GLOBAL REFERENCES TO PREVENT UNRESOLVED REFERENCE ERRORS
    private var chatRecyclerView: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("rns_settings", MODE_PRIVATE)

        chatAdapter = ChatAdapter()
        nodesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, discoveredNodes)

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

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.page_settings, null)
        val hashTxt = view.findViewById<TextView>(R.id.setHash)
        val nickInput = view.findViewById<EditText>(R.id.setNick)
        val freqInput = view.findViewById<EditText>(R.id.setFreq)
        
        hashTxt.text = if(ownHash.isEmpty()) "Generating..." else ownHash
        hashTxt.setOnClickListener { if(ownHash.isNotEmpty()) showQr(ownHash) }
        
        nickInput.setText(prefs.getString("nickname", "User"))
        freqInput.setText(prefs.getString("freq", "433025000"))

        view.findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            prefs.edit().putString("nickname", nickInput.text.toString()).apply()
            prefs.edit().putString("freq", freqInput.text.toString()).apply()
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
        }

        val btSpinner = view.findViewById<Spinner>(R.id.setBtSpinner)
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            val names = adapter.bondedDevices.map { "${it.name}\n${it.address}" }
            btSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        }

        view.findViewById<Button>(R.id.btnSetConnect).setOnClickListener {
            if (btSpinner.selectedItem != null) {
                val mac = btSpinner.selectedItem.toString().substringAfterLast("\n")
                hotConnect(mac)
            }
        }
        replaceFrame(view)
    }

    private fun showNodes() {
        val view = layoutInflater.inflate(R.layout.page_nodes, null)
        val lv = view.findViewById<ListView>(R.id.nodeListView)
        lv.adapter = nodesAdapter
        lv.setOnItemClickListener { _, _, i, _ ->
            targetHash = discoveredNodes[i].split(" ").last().replace("(", "").replace(")", "")
            findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_chat
        }
        lv.setOnItemLongClickListener { _, _, i, _ ->
            val rawHex = discoveredNodes[i].split(" ").last().replace("(", "").replace(")", "")
            editNodeNickname(rawHex)
            true
        }
        view.findViewById<Button>(R.id.btnManualAdd).setOnClickListener { manualAddNode() }
        replaceFrame(view)
    }

    private fun showChat() {
        val view = layoutInflater.inflate(R.layout.page_chat, null)
        chatRecyclerView = view.findViewById(R.id.chatRv)
        chatRecyclerView?.layoutManager = LinearLayoutManager(this)
        chatRecyclerView?.adapter = chatAdapter
        
        val input = view.findViewById<EditText>(R.id.chatInput)
        view.findViewById<Button>(R.id.btnChatSend).setOnClickListener {
            val txt = input.text.toString().trim()
            if (targetHash.isNotEmpty() && txt.isNotEmpty()) {
                onNewMessage(ownHash, txt, System.currentTimeMillis(), false, true)
                Thread { Python.getInstance().getModule("rns_backend").callAttr("send_text", targetHash, txt) }.start()
                input.setText("")
            }
        }
        view.findViewById<Button>(R.id.btnChatImg).setOnClickListener {
            if (targetHash.isNotEmpty()) startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 101)
        }
        replaceFrame(view)
    }

    private fun replaceFrame(v: View) {
        val container = findViewById<FrameLayout>(R.id.fragment_container)
        container.removeAllViews()
        container.addView(v)
    }

    private fun startRns() {
        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                val py = Python.getInstance()
                py.getModule("os").get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)
                val nickname = prefs.getString("nickname", "User")
                ownHash = py.getModule("rns_backend").callAttr("start_rns", filesDir.absolutePath, this, nickname).toString()
                runOnUiThread { Toast.makeText(this, "RNS Node Online", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { Log.e("RNS", e.message ?: "") }
        }.start()
    }

    private fun hotConnect(mac: String) {
        Thread {
            try {
                isBridging = false; btSocket?.close(); tcpServer?.close()
                val dev = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac)
                val m = dev.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                btSocket = m.invoke(dev, 1) as BluetoothSocket
                btSocket?.connect()
                tcpServer = ServerSocket(); tcpServer?.reuseAddress = true
                tcpServer?.bind(InetSocketAddress("127.0.0.1", 7633))
                isBridging = true
                Thread { bridgeLoop() }.start()
                val f = prefs.getString("freq", "433025000") ?: "433025000"
                val pyStatus = Python.getInstance().getModule("rns_backend").callAttr("inject_rnode", f, "125000", "17", "8", "6").toString()
                runOnUiThread { Toast.makeText(this, "RNode: $pyStatus", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "BT Error: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun bridgeLoop() {
        while(isBridging) {
            try {
                val client = tcpServer?.accept() ?: break
                val btIn = btSocket?.inputStream; val btOut = btSocket?.outputStream
                val tcpIn = client.inputStream; val tcpOut = client.outputStream
                Thread { try { tcpIn.copyTo(btOut!!) } catch(e:Exception){} finally { client.close() } }.start()
                Thread { try { btIn?.copyTo(tcpOut!!) } catch(e:Exception){} finally { client.close() } }.start()
            } catch (e: Exception) { break }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            Thread {
                try {
                    val stream = contentResolver.openInputStream(data.data!!)
                    val b = BitmapFactory.decodeStream(stream)
                    val scale = 240f / b.width
                    val thumb = Bitmap.createScaledBitmap(b, 240, (b.height * scale).toInt(), true)
                    val f = File(cacheDir, "out.webp")
                    val out = FileOutputStream(f)
                    thumb.compress(Bitmap.CompressFormat.WEBP, 5, out)
                    out.close()
                    onNewMessage(ownHash, f.absolutePath, System.currentTimeMillis(), true, true)
                    Python.getInstance().getModule("rns_backend").callAttr("send_image", targetHash, f.absolutePath)
                } catch (e: Exception) { Log.e("RNS", "Img Err: ${e.message}") }
            }.start()
        }
    }

    private fun showQr(data: String) {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, 512, 512)
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0..511) for (y in 0..511) bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
        val img = ImageView(this); img.setImageBitmap(bmp)
        AlertDialog.Builder(this).setTitle("Scan My Address").setView(img).show()
    }

    override fun onAnnounceReceived(hex: String) {
        runOnUiThread {
            val nick = prefs.getString("nick_$hex", "")
            val displayStr = if (nick!!.isNotEmpty()) "$nick ($hex)" else hex
            if(!discoveredNodes.contains(displayStr) && !discoveredNodes.any { it.contains(hex) }) { 
                discoveredNodes.add(displayStr)
                nodesAdapter.notifyDataSetChanged() 
            } 
        }
    }

    override fun onNewMessage(s: String, c: String, t: Long, img: Boolean, sent: Boolean) {
        runOnUiThread { 
            chatAdapter.addMessage(Message(s, c, t, img, sent)) 
            chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    private fun manualAddNode() {
        val input = EditText(this); input.hint = "32-char Hex Address"
        AlertDialog.Builder(this).setTitle("Manual Add").setView(input)
            .setPositiveButton("Add") { _,_ -> onAnnounceReceived(input.text.toString().trim()) }.show()
    }

    private fun editNodeNickname(hex: String) {
        val input = EditText(this); input.hint = "Nickname"
        AlertDialog.Builder(this).setTitle("Nickname for ${hex.take(6)}").setView(input)
            .setPositiveButton("Save") { _,_ -> 
                prefs.edit().putString("nick_$hex", input.text.toString()).apply()
                discoveredNodes.removeAll { it.contains(hex) }
                onAnnounceReceived(hex)
            }.show()
    }

    private fun checkPermissions(): Boolean {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_ADMIN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_CONNECT); p.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (p.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, p.toTypedArray(), 1); return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startRns()
    }
}