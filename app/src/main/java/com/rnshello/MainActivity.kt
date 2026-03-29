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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.*

class MainActivity : AppCompatActivity(), RnsCallback {
    private lateinit var prefs: SharedPreferences
    private var ownHash = ""
    private var targetHash = ""
    private lateinit var chatAdapter: ChatAdapter
    private val allMessages = mutableListOf<Message>()
    private val discoveredNodes = mutableListOf<String>()
    private lateinit var nodesAdapter: ArrayAdapter<String>
    private var chatRecyclerView: RecyclerView? = null
    private var currentViewType: Int = 2 

    private var btSocket: BluetoothSocket? = null
    private var tcpServer: ServerSocket? = null
    private var tcpClient: Socket? = null
    private var isBridging = false

    // Parameter Mappings
    val freqLabels = arrayOf("433.0 MHz", "858.0 MHz", "915.0 MHz")
    val freqVals = arrayOf("433000000", "858000000", "915000000")
    val bwLabels = arrayOf("125 kHz", "62.5 kHz", "31.25 kHz")
    val bwVals = arrayOf("125000", "62500", "31250")
    val txLabels = arrayOf("10 dBm", "12 dBm", "17 dBm", "21 dBm")
    val txVals = arrayOf("10", "12", "17", "21")
    val sfLabels = arrayOf("SF 5", "SF 6", "SF 7", "SF 8")
    val sfVals = arrayOf("5", "6", "7", "8")
    val crLabels = arrayOf("CR 4/5", "CR 4/6", "CR 4/7", "CR 4/8")
    val crVals = arrayOf("5", "6", "7", "8")

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
                    Thread.sleep(2500) 
                    val f = freqVals[prefs.getInt("sel_freq", 0)]
                    val bw = bwVals[prefs.getInt("sel_bw", 0)]
                    val tx = txVals[prefs.getInt("sel_tx", 2)]
                    val sf = sfVals[prefs.getInt("sel_sf", 3)]
                    val cr = crVals[prefs.getInt("sel_cr", 1)]
                    py.getModule("rns_backend").callAttr("inject_rnode", f, bw, tx, sf, cr)
                }
                runOnUiThread { if (currentViewType == 1) showSettings() }
            } catch (e: Exception) {}
        }.start()
    }

    private fun setupSlider(view: View, seekId: Int, lblId: Int, prefix: String, labels: Array<String>, prefKey: String, defVal: Int): SeekBar {
        val seekBar = view.findViewById<SeekBar>(seekId)
        val label = view.findViewById<TextView>(lblId)
        seekBar.max = labels.size - 1
        val savedIndex = prefs.getInt(prefKey, defVal)
        seekBar.progress = savedIndex
        label.text = "$prefix: ${labels[savedIndex]}"
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) { label.text = "$prefix: ${labels[prog]}" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        return seekBar
    }

    private fun showSettings() {
        currentViewType = 1
        invalidateOptionsMenu()
        supportActionBar?.title = "Settings"
        val view = layoutInflater.inflate(R.layout.page_settings, null)
        
        // Corrected view IDs to match Slider XML
        val hashView = view.findViewById<TextView>(R.id.setHash)
        hashView.text = if(ownHash.isEmpty()) "Loading..." else ownHash
        hashView.setOnClickListener { if(ownHash.isNotEmpty()) showQr(ownHash) }
        
        val nickInput = view.findViewById<EditText>(R.id.setNick)
        nickInput.setText(prefs.getString("my_nickname", "User"))

        val seekFreq = setupSlider(view, R.id.seekFreq, R.id.lblFreq, "Frequency", freqLabels, "sel_freq", 0)
        val seekBw = setupSlider(view, R.id.seekBw, R.id.lblBw, "Bandwidth", bwLabels, "sel_bw", 0)
        val seekTx = setupSlider(view, R.id.seekTx, R.id.lblTx, "TX Power", txLabels, "sel_tx", 2)
        val seekSf = setupSlider(view, R.id.seekSf, R.id.lblSf, "Spreading Factor", sfLabels, "sel_sf", 3)
        val seekCr = setupSlider(view, R.id.seekCr, R.id.lblCr, "Coding Rate", crLabels, "sel_cr", 1)

        val btSpinner = view.findViewById<Spinner>(R.id.setBtSpinner)
        val updateBT = {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                btSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, adapter.bondedDevices.map { "${it.name}\n${it.address}" })
            }
        }
        updateBT()
        view.findViewById<Button>(R.id.btnRefreshBt).setOnClickListener { updateBT() }

        view.findViewById<Button>(R.id.btnSetConnect).setOnClickListener {
            if (btSpinner.selectedItem != null) {
                val mac = btSpinner.selectedItem.toString().substringAfterLast("\n")
                prefs.edit().apply {
                    putString("last_mac", mac)
                    putString("my_nickname", nickInput.text.toString())
                    putInt("sel_freq", seekFreq.progress)
                    putInt("sel_bw", seekBw.progress)
                    putInt("sel_tx", seekTx.progress)
                    putInt("sel_sf", seekSf.progress)
                    putInt("sel_cr", seekCr.progress)
                    apply()
                }
                Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show()
                hotConnectBt(mac)
            }
        }
        replaceFrame(view)
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

                val f = freqVals[prefs.getInt("sel_freq", 0)]
                val bw = bwVals[prefs.getInt("sel_bw", 0)]
                val tx = txVals[prefs.getInt("sel_tx", 2)]
                val sf = sfVals[prefs.getInt("sel_sf", 3)]
                val cr = crVals[prefs.getInt("sel_cr", 1)]

                val pyStatus = Python.getInstance().getModule("rns_backend").callAttr("inject_rnode", f, bw, tx, sf, cr).toString()
                runOnUiThread { Toast.makeText(this, "RNode: $pyStatus", Toast.LENGTH_LONG).show() }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val res = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (res != null && res.contents != null) {
            onAnnounceReceived(res.contents.trim(), "")
        } else if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            Thread {
                try {
                    val stream = contentResolver.openInputStream(data.data!!)
                    val b = BitmapFactory.decodeStream(stream)
                    val thumb = Bitmap.createScaledBitmap(b, 180, (b.height * scaleFactor(b.width, 180)).toInt(), true)
                    val f = File(cacheDir, "out.webp"); val out = FileOutputStream(f)
                    thumb.compress(Bitmap.CompressFormat.WEBP, 4, out); out.close()
                    val id = Python.getInstance().getModule("rns_backend").callAttr("send_image", targetHash, f.absolutePath).toString()
                    onNewMessage(ownHash, f.absolutePath, System.currentTimeMillis(), true, true, id)
                } catch (e: Exception) {}
            }.start()
        }
    }

    private fun scaleFactor(w: Int, target: Int): Float = target.toFloat() / w.toFloat()

    private fun showQr(data: String) {
        val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 512, 512)
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0..511) for (y in 0..511) bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
        val img = ImageView(this); img.setImageBitmap(bmp)
        AlertDialog.Builder(this).setTitle("My RNS Hash").setView(img).setPositiveButton("Close", null).show()
    }

    private fun showBigImage(path: String) {
        val img = ImageView(this); img.setImageBitmap(BitmapFactory.decodeFile(path))
        AlertDialog.Builder(this).setView(img).setPositiveButton("Close", null).show()
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
            val convHash = if (sent) targetHash else s
            val m = Message(id = id, targetHash = convHash, senderHash = s, content = c, timestamp = t, isImage = img, isSent = sent, isDelivered = false)
            allMessages.add(m)
            saveChatHistory() 
            if (currentViewType == 3 && targetHash == convHash) {
                chatAdapter.addMessage(m, id)
                chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
            }
            if (!sent) showNotification(s, if(img) "Image received" else c)
        }
    }

    override fun onMessageDelivered(id: String) { 
        runOnUiThread { 
            val msg = allMessages.find { it.id == id }
            if (msg != null) msg.isDelivered = true
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

    private fun saveChatHistory() {
        val jsonArray = JSONArray()
        for (m in allMessages) {
            val obj = JSONObject()
            obj.put("id", m.id); obj.put("target", m.targetHash); obj.put("sender", m.senderHash)
            obj.put("content", m.content); obj.put("timestamp", m.timestamp)
            obj.put("isImage", m.isImage); obj.put("isSent", m.isSent); obj.put("isDelivered", m.isDelivered)
            jsonArray.put(obj)
        }
        prefs.edit().putString("chat_history", jsonArray.toString()).apply()
    }

    private fun loadChatHistory() {
        try {
            val jsonArray = JSONArray(prefs.getString("chat_history", "[]"))
            allMessages.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val isSent = obj.getBoolean("isSent")
                val sender = obj.getString("sender")
                val target = if (obj.has("target")) obj.getString("target") else { if (isSent) "Unknown" else sender }
                allMessages.add(Message(obj.getString("id"), target, sender, obj.getString("content"), obj.getLong("timestamp"), obj.getBoolean("isImage"), isSent, obj.getBoolean("isDelivered")))
            }
        } catch (e: Exception) {}
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
        if (Build.VERSION.SDK_INT >= 33) { p.add(Manifest.permission.POST_NOTIFICATIONS) }
        if (p.any { ActivityCompat.checkSelfPermission(this, it) != 0 }) { ActivityCompat.requestPermissions(this, p.toTypedArray(), 1); return false }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startRns()
    }
}