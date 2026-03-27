package com.rnshello

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity(), RnsCallback {

    private var localRnsAddress: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Initialize Chaquopy (Python environment)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // 2. Start RNS Backend
        val py = Python.getInstance()
        val rnsBackend = py.getModule("rns_backend")
        
        // Pass the app's internal files directory to Python for RNS storage
        val storagePath = filesDir.absolutePath
        
        // Start and get local address
        localRnsAddress = rnsBackend.callAttr("start_rns", storagePath, this).toString()
        Log.d("RNS_HELLO", "My Address: $localRnsAddress")

        // (UI Initialization goes here - Step 6)
    }

    // --- CALL FROM UI TO SEND TEXT ---
    fun sendTextMessage(destinationHash: String, text: String) {
        val py = Python.getInstance()
        val rnsBackend = py.getModule("rns_backend")
        rnsBackend.callAttr("send_text", destinationHash, text)
    }

    // --- CALL FROM UI TO SEND IMAGE (THE JNI FIX) ---
    fun compressAndSendImage(destinationHash: String, rawBitmap: Bitmap) {
        Thread {
            try {
                // 1. Create a temp file in cache directory
                val tempFile = File(cacheDir, "temp_outbound.webp")
                val os = FileOutputStream(tempFile)
                
                // 2. Aggressively compress to WebP (Lossy, 40% quality) to survive LoRa MTU
                rawBitmap.compress(Bitmap.CompressFormat.WEBP, 40, os)
                os.flush()
                os.close()

                // 3. Hand ONLY the string path to Python. No byte arrays!
                val py = Python.getInstance()
                val rnsBackend = py.getModule("rns_backend")
                rnsBackend.callAttr("send_image", destinationHash, tempFile.absolutePath)
                
            } catch (e: Exception) {
                Log.e("RNS_HELLO", "Image compression failed: ${e.message}")
            }
        }.start()
    }

    // --- CALLBACKS FROM PYTHON ---
    override fun onTextReceived(senderHash: String, text: String) {
        runOnUiThread {
            Log.d("RNS_HELLO", "Received text from $senderHash: $text")
            // Update UI
        }
    }

    override fun onImageReceived(senderHash: String, imagePath: String) {
        runOnUiThread {
            Log.d("RNS_HELLO", "Received image from $senderHash saved at: $imagePath")
            // Load file into Coil/Glide UI from imagePath
        }
    }
}