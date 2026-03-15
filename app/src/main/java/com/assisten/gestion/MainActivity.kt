package com.assisten.gestion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.assisten.gestion.ui.theme.GestionTheme
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.io.File

data class FileItem(val name: String, val isDirectory: Boolean, val path: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GestionTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var roleSelected by remember { mutableStateOf<String?>(null) }
    var viewingExplorer by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Permiso necesario para monitoreo", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (roleSelected == null) {
                Text(text = "Gestión Parental", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(32.dp))

                Button(onClick = { roleSelected = "Padre" }, modifier = Modifier.fillMaxWidth()) {
                    Text("MODO PADRE (Control)")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        roleSelected = "Hijo"
                        checkAndRequestStoragePermissions(context) {
                            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                        // Iniciar el servicio de monitoreo
                        val serviceIntent = Intent(context, MonitoringService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("MODO HIJO (Monitoreado)")
                }
            } else if (viewingExplorer) {
                RemoteFileExplorerScreen(onBack = { viewingExplorer = false })
            } else {
                Dashboard(
                    role = roleSelected!!,
                    onOpenExplorer = { viewingExplorer = true },
                    onBack = { roleSelected = null }
                )
            }
        }
    }
}

private fun checkAndRequestStoragePermissions(context: android.content.Context, requestLegacy: () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    } else {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestLegacy()
        }
    }
}

@Composable
fun Dashboard(role: String, onOpenExplorer: () -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Panel: $role", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        Spacer(modifier = Modifier.height(40.dp))
        
        if (role == "Hijo") {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Estado: Monitoreo Activo", fontWeight = FontWeight.Bold)
                    Text("Este dispositivo está enviando datos de seguridad.")
                }
            }
        } else {
            Button(onClick = onOpenExplorer, modifier = Modifier.fillMaxWidth()) {
                Text("Explorar Archivos del Hijo")
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onBack) { Text("Cerrar Sesión") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteFileExplorerScreen(onBack: () -> Unit) {
    val database = FirebaseDatabase.getInstance().reference
    val childId = "hijo_uno"
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf("/storage/emulated/0") }
    var filesList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var downloadingFile by remember { mutableStateOf<String?>(null) }

    // Enviar comando al cambiar de carpeta
    LaunchedEffect(currentPath) {
        isLoading = true
        database.child("commands").child(childId).setValue(mapOf(
            "type" to "GET_FILES",
            "path" to currentPath
        ))
    }

    // Escuchar respuesta de archivos y archivos listos para descargar
    DisposableEffect(Unit) {
        val filesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<FileItem>()
                snapshot.children.forEach { child ->
                    val name = child.child("name").getValue(String::class.java) ?: ""
                    val isDir = child.child("isDirectory").getValue(Boolean::class.java) ?: false
                    val path = child.child("path").getValue(String::class.java) ?: ""
                    list.add(FileItem(name, isDir, path))
                }
                filesList = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                isLoading = false
            }
            override fun onCancelled(error: DatabaseError) { isLoading = false }
        }

        val readyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val url = snapshot.child("url").getValue(String::class.java)
                val name = snapshot.child("name").getValue(String::class.java)
                if (url != null && name == downloadingFile) {
                    Toast.makeText(context, "Archivo listo: $name", Toast.LENGTH_LONG).show()
                    downloadingFile = null
                    // Abrir el link en el navegador para descargar
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        database.child("responses").child(childId).addValueEventListener(filesListener)
        database.child("file_ready").child(childId).addValueEventListener(readyListener)
        
        onDispose { 
            database.child("responses").child(childId).removeEventListener(filesListener)
            database.child("file_ready").child(childId).removeEventListener(readyListener)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Hijo: ${currentPath.split("/").last()}") },
            navigationIcon = {
                IconButton(onClick = {
                    if (currentPath != "/storage/emulated/0") {
                        currentPath = File(currentPath).parent ?: "/storage/emulated/0"
                    } else {
                        onBack()
                    }
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
            }
        )
        
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filesList) { file ->
                    FileListItem(file) {
                        if (file.isDirectory) {
                            currentPath = file.path
                        } else {
                            // Solicitar subida del archivo al hijo
                            downloadingFile = file.name
                            Toast.makeText(context, "Solicitando archivo...", Toast.LENGTH_SHORT).show()
                            database.child("commands").child(childId).setValue(mapOf(
                                "type" to "UPLOAD_FILE",
                                "path" to file.path
                            ))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FileListItem(file: FileItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(text = file.name, fontWeight = FontWeight.Medium)
            Text(text = if (file.isDirectory) "Carpeta" else "Archivo", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}
