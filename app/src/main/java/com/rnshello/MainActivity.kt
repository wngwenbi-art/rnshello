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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

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
        loadChatHistory() 

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            when(item.itemId) {
                R.id.nav_nodes -> { currentViewType = 2; showNodes() }
                R.id.nav_chat -> { currentViewType = 3; showChat() }
            }; true
        }
        if (checkPermissions()) startRns()
        showNodes()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_toolbar_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.action_add_node)?.isVisible = (currentViewType == 2)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.action_add_node -> IntentIntegrator(this).setOrientationLocked(false).initiateScan()
            R.id.action_settings -> { currentViewType = 1; showSettings() }
        }; return true
    }

    private fun startRns() {
        val savedMac = prefs.getString("last_mac", "") ?: ""
        val intent = Intent(this, RnsService::class.java).apply { putExtra("mac", savedMac) }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        
        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                val py = Python.getInstance()
                py.getModule("os").get("environ")?.callAttr("__setitem__", "HOME", filesDir.absolutePath)
                val nick = prefs.getString("my_nickname", "User")
                ownHash = py.getModule("rns_backend").callAttr("start_rns", filesDir.absolutePath, this, nick).toString()
                
                if (savedMac.isNotEmpty()) {
                    Thread.sleep(1500) 
                    val f = when(prefs.getInt("sel_region", 0)) { 0 -> "433025000"; 1 -> "915000000"; else -> "433000000" }
                    val bw = when(prefs.getInt("sel_bw", 0)) { 0 -> "125000"; 1 -> "62500"; else -> "31250" }
                    val sf = (prefs.getInt("sel_sf", 1) + 7).toString()
                    val cr = (prefs.getInt("sel_cr", 1) + 5).toString()
                    py.getModule("rns_backend").callAttr("inject_rnode", f, bw, "17", sf, cr)
                }
                runOnUiThread { if (currentViewType == 1) showSettings() }
            } catch (e: Exception) {}
        }.start()
    }

    private fun showNodes() {
        currentViewType = 2
        invalidateOptionsMenu()
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

    private fun showSettings() {
        currentViewType = 1
        invalidateOptionsMenu()
        supportActionBar?.title = "Settings"
        val view = layoutInflater.inflate(R.layout.page_settings, null)
        view.findViewById<TextView>(R.id.addressDisplay).apply {
            text = if(ownHash.isEmpty()) "Loading..." else ownHash
            setOnClickListener { if(ownHash.isNotEmpty()) showQr(ownHash) }
        }
        view.findViewById<EditText>(R.id.setNick).setText(prefs.getString("my_nickname", "User"))
        
        val spinRegion = view.findViewById<Spinner>(R.id.spinRegion)
        spinRegion.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Europe (433MHz)", "USA (915MHz)", "Asia (433MHz)"))
        
        val btSpinner = view.findViewById<Spinner>(R.id.setBtSpinner)
        val updateBT = {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                btSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, adapter.bondedDevices.map { "${it.name}\n${it.address}" })
            }
        }
        updateBT()
        view.findViewById<Button>(R.id.btnRefreshBt).setOnClickListener { showSettings() }
        view.findViewById<Button>(R.id.btnSetConnect).setOnClickListener {
            val mac = btSpinner.selectedItem.toString().substringAfterLast("\n")
            prefs.edit().putString("last_mac", mac).apply()
            Toast.makeText(this, "Connecting to RNode in Background...", Toast.LENGTH_SHORT).show()
            startRns() 
        }
        view.findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            prefs.edit().putString("my_nickname", view.findViewById<EditText>(R.id.setNick).text.toString()).apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        replaceFrame(view)
    }

    private fun showChat() {
        currentViewType = 3
        invalidateOptionsMenu()
        val nick = prefs.getString("nick_$targetHash", "")
        supportActionBar?.title = if (!nick.isNullOrEmpty()) nick else targetHash.take(6)
        val view = layoutInflater.inflate(R.layout.page_chat, null)
        chatRecyclerView = view.findViewById(R.id.chatRv)
        chatRecyclerView?.layoutManager = LinearLayoutManager(this)
        chatRecyclerView?.adapter = chatAdapter
        chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
        
        view.findViewById<ImageButton>(R.id.btnChatSend).setOnClickListener {
            val input = view.findViewById<EditText>(R.id.chatInput)
            val txt = input.text.toString().trim()
            if (targetHash.isNotEmpty() && txt.isNotEmpty()) {
                val id = Python.getInstance().getModule("rns_backend").callAttr("send_text", targetHash, txt).toString()
                onNewMessage(ownHash, txt, System.currentTimeMillis(), false, true, id)
                input.setText("")
            }
        }
        view.findViewById<ImageButton>(R.id.btnChatImg).setOnClickListener {
            if (targetHash.isNotEmpty()) startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 101)
        }
        replaceFrame(view)
    }

    private fun saveChatHistory() {
        val jsonArray = JSONArray()
        for (m in chatAdapter.getMessages()) {
            val obj = JSONObject()
            obj.put("id", m.id)
            obj.put("sender", m.senderHash)
            obj.put("content", m.content)
            obj.put("timestamp", m.timestamp)
            obj.put("isImage", m.isImage)
            obj.put("isSent", m.isSent)
            obj.put("isDelivered", m.isDelivered)
            jsonArray.put(obj)
        }
        prefs.edit().putString("chat_history", jsonArray.toString()).apply()
    }

    private fun loadChatHistory() {
        try {
            val jsonString = prefs.getString("chat_history", "[]")
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val m = Message(
                    obj.getString("id"),
                    obj.getString("sender"),
                    obj.getString("content"),
                    obj.getLong("timestamp"),
                    obj.getBoolean("isImage"),
                    obj.getBoolean("isSent"),
                    obj.getBoolean("isDelivered")
                )
                chatAdapter.addMessage(m, if(!m.isDelivered) m.id else null)
            }
        } catch (e: Exception) { Log.e("RNS", "Load Chat Error", e) }
    }

    override fun onAnnounceReceived(hex: String, nickname: String) {
        if (nickname.isNotEmpty()) prefs.edit().putString("nick_$hex", nickname).apply()
        val list = prefs.getStringSet("node_list", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (!list.contains(hex)) {
            list.add(hex); prefs.edit().putStringSet("node_list", list).apply()
        }
        runOnUiThread { loadSavedNodes() }
    }

    override fun onNewMessage(s: String, c: String, t: Long, img: Boolean, sent: Boolean, id: String) {
        runOnUiThread { 
            chatAdapter.addMessage(Message(senderHash = s, content = c, timestamp = t, isImage = img, isSent = sent), id)
            chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
            if (!sent) showNotification(s, if(img) "Image received" else c)
            saveChatHistory() 
        }
    }

    override fun onMessageDelivered(id: String) { 
        runOnUiThread { 
            chatAdapter.markDelivered(id)
            saveChatHistory() 
        } 
    }

    private fun showNotification(sender: String, text: String) {
        val nick = prefs.getString("nick_$sender", sender.take(6))
        val builder = androidx.core.app.NotificationCompat.Builder(this, "RNS_CHANNEL")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Msg: $nick")
            .setContentText(text)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
        
        // THE FIX: Use System.currentTimeMillis().toInt() instead of Random()
        androidx.core.app.NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun loadSavedNodes() {
        val list = prefs.getStringSet("node_list", setOf()) ?: setOf()
        discoveredNodes.clear()
        for (hex in list) {
            val nick = prefs.getString("nick_$hex", "")
            discoveredNodes.add(if (!nick.isNullOrEmpty()) "$nick ($hex)" else hex)
        }
        nodesAdapter.notifyDataSetChanged()
    }

    private fun showQr(data: String) {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 512, 512)
        for (x in 0..511) for (y in 0..511) bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
        val img = ImageView(this); img.setImageBitmap(bmp)
        AlertDialog.Builder(this).setTitle("My RNS Hash").setView(img).setPositiveButton("Close", null).show()
    }

    private fun showBigImage(path: String) {
        val img = ImageView(this); img.setImageBitmap(BitmapFactory.decodeFile(path))
        AlertDialog.Builder(this).setView(img).setPositiveButton("Close", null).show()
    }

    private fun replaceFrame(v: View) {
        findViewById<FrameLayout>(R.id.fragment_container).apply { removeAllViews(); addView(v) }
    }

    private fun editNodeNickname(hex: String) {
        val input = EditText(this); input.hint = "Nickname"
        AlertDialog.Builder(this).setTitle("Set Nickname").setView(input).setPositiveButton("Save") { _,_ -> 
            prefs.edit().putString("nick_$hex", input.text.toString()).apply(); loadSavedNodes()
        }.show()
    }

    private fun checkPermissions(): Boolean {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) { p.add(Manifest.permission.BLUETOOTH_CONNECT); p.add(Manifest.permission.BLUETOOTH_SCAN) }
        if (p.any { ActivityCompat.checkSelfPermission(this, it) != 0 }) { ActivityCompat.requestPermissions(this, p.toTypedArray(), 1); return false }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val res = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (res != null && res.contents != null) onAnnounceReceived(res.contents.trim(), "")
        else if (requestCode == 101 && resultCode == -1 && data != null) {
            Thread {
                val b = BitmapFactory.decodeStream(contentResolver.openInputStream(data.data!!))
                val scale = 180f / b.width
                val thumb = Bitmap.createScaledBitmap(b, 180, (b.height * scale).toInt(), true)
                val f = File(cacheDir, "out.webp"); val out = FileOutputStream(f)
                thumb.compress(Bitmap.CompressFormat.WEBP, 4, out); out.close()
                val id = Python.getInstance().getModule("rns_backend").callAttr("send_image", targetHash, f.absolutePath).toString()
                onNewMessage(ownHash, f.absolutePath, System.currentTimeMillis(), true, true, id)
            }.start()
        }
    }
}