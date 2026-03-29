package com.rnshello

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import java.util.*

class MainActivity : AppCompatActivity(), RnsCallback {

    private lateinit var prefs: SharedPreferences
    private var ownHash = ""
    private var targetHash = ""
    private lateinit var chatAdapter: ChatAdapter
    private val discoveredNodes = mutableListOf<String>()
    private lateinit var nodesAdapter: ArrayAdapter<String>
    private var chatRecyclerView: RecyclerView? = null
    private var currentViewType: Int = 2 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        prefs = getSharedPreferences("rns_database", MODE_PRIVATE)
        chatAdapter = ChatAdapter(this) { path -> showBigImage(path) }
        nodesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, discoveredNodes)

        loadSavedNodes()

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            when(item.itemId) {
                R.id.nav_nodes -> { currentViewType = 2; showNodes() }
                R.id.nav_chat -> { currentViewType = 3; showChat() }
            }
            true
        }

        if (checkPermissions()) startRns()
        showNodes()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.action_add_node -> IntentIntegrator(this).setOrientationLocked(false).initiateScan()
            R.id.action_settings -> { currentViewType = 1; showSettings() }
        }
        return true
    }

    private fun startRns() {
        // Start Foreground Service to keep BT connection alive
        val savedMac = prefs.getString("last_mac", "") ?: ""
        val serviceIntent = Intent(this, RnsService::class.java).apply {
            putExtra("mac", savedMac)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                val py = Python.getInstance()
                py.getModule("os").get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)
                val nick = prefs.getString("my_nickname", "User")
                ownHash = py.getModule("rns_backend").callAttr("start_rns", filesDir.absolutePath, this, nick).toString()
                runOnUiThread { if (currentViewType == 1) showSettings() }
            } catch (e: Exception) { Log.e("RNS", e.message ?: "") }
        }.start()
    }

    private fun showSettings() {
        currentViewType = 1
        supportActionBar?.title = "Settings"
        val view = layoutInflater.inflate(R.layout.page_settings, null)
        val addressDisplay = view.findViewById<TextView>(R.id.addressDisplay)
        val nickInput = view.findViewById<EditText>(R.id.setNick)
        addressDisplay.text = if(ownHash.isEmpty()) "Connecting..." else ownHash
        addressDisplay.setOnClickListener { if(ownHash.isNotEmpty()) showQr(ownHash) }
        nickInput.setText(prefs.getString("my_nickname", "User"))

        val spinRegion = view.findViewById<Spinner>(R.id.spinRegion)
        spinRegion.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Europe (433MHz)", "USA (915MHz)", "Asia (433MHz)"))
        
        val btSpinner = view.findViewById<Spinner>(R.id.setBtSpinner)
        val updateBT = {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                val names = adapter.bondedDevices.map { "${it.name}\n${it.address}" }
                btSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            }
        }
        updateBT()

        view.findViewById<Button>(R.id.btnSetConnect).setOnClickListener {
            if (btSpinner.selectedItem != null) {
                val mac = btSpinner.selectedItem.toString().substringAfterLast("\n")
                prefs.edit().putString("last_mac", mac).commit()
                startRns() // Restart stack with new MAC
            }
        }
        replaceFrame(view)
    }

    private fun showNodes() {
        currentViewType = 2
        supportActionBar?.title = "Nodes"
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
        view.findViewById<Button>(R.id.btnManualAnnounce).setOnClickListener {
            Thread { Python.getInstance().getModule("rns_backend").callAttr("announce_now") }.start()
        }
        replaceFrame(view)
    }

    private fun showChat() {
        currentViewType = 3
        val nick = prefs.getString("nick_$targetHash", "")
        supportActionBar?.title = if (!nick.isNullOrEmpty()) nick else "Chat: ${targetHash.take(6)}"
        val view = layoutInflater.inflate(R.layout.page_chat, null)
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
        replaceFrame(view)
    }

    override fun onAnnounceReceived(hex: String, nickname: String) {
        if (nickname.isNotEmpty()) prefs.edit().putString("nick_$hex", nickname).apply()
        val currentSet = prefs.getStringSet("node_list", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (!currentSet.contains(hex)) {
            currentSet.add(hex); prefs.edit().putStringSet("node_list", currentSet).apply()
        }
        runOnUiThread { loadSavedNodes() }
    }

    override fun onNewMessage(s: String, c: String, t: Long, img: Boolean, sent: Boolean, id: String) {
        runOnUiThread { 
            chatAdapter.addMessage(Message(senderHash = s, content = c, timestamp = t, isImage = img, isSent = sent), id)
            chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
            if (!sent) showNotification(s, if(img) "Sent an image" else c)
        }
    }

    private fun showNotification(sender: String, text: String) {
        val nick = prefs.getString("nick_$sender", sender.take(6))
        val builder = androidx.core.app.NotificationCompat.Builder(this, "RNS_CHANNEL")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Message from $nick")
            .setContentText(text)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
        androidx.core.app.NotificationManagerCompat.from(this).notify(Random().nextInt(), builder.build())
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

    private fun showQr(data: String) {
        val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 512, 512)
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0..511) for (y in 0..511) bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
        val img = ImageView(this); img.setImageBitmap(bmp)
        AlertDialog.Builder(this).setTitle("My RNS Address").setView(img).setPositiveButton("Close", null).show()
    }

    private fun showBigImage(path: String) {
        val img = ImageView(this); img.setImageBitmap(BitmapFactory.decodeFile(path))
        AlertDialog.Builder(this).setView(img).setPositiveButton("Close", null).show()
    }

    override fun onMessageDelivered(id: String) { runOnUiThread { chatAdapter.markDelivered(id) } }

    private fun replaceFrame(v: View) {
        val container = findViewById<FrameLayout>(R.id.fragment_container)
        container.removeAllViews(); container.addView(v)
    }

    private fun editNodeNickname(hex: String) {
        val input = EditText(this); input.hint = "Nickname"
        AlertDialog.Builder(this).setTitle("Set Nickname").setView(input).setPositiveButton("Save") { _,_ -> 
            prefs.edit().putString("nick_$hex", input.text.toString()).apply(); loadSavedNodes()
        }.show()
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