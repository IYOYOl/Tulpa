package com.lxylx.aicamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lxylx.aicamera.ui.AICameraTheme
import com.lxylx.aicamera.ui.Accent
import com.lxylx.aicamera.ui.CameraScreen
import com.lxylx.aicamera.ui.GalleryScreen
import com.lxylx.aicamera.ui.PhotoDetailScreen
import com.lxylx.aicamera.ui.QueueSheet
import com.lxylx.aicamera.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AICameraTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        when (vm.screen) {
            Screen.Camera -> if (hasCameraPermission) {
                CameraScreen(
                    prefs = vm.prefs,
                    queueCount = vm.queueCount,
                    latestPhoto = vm.photos.maxByOrNull { it.createdAt },
                    captured = vm::onCaptured,
                    onOpenSettings = vm::openSettings,
                    onOpenGallery = vm::openGallery,
                    onOpenQueue = vm::openQueue,
                    onError = { vm.error = it }
                )
            } else {
                PermissionGate { permissionLauncher.launch(Manifest.permission.CAMERA) }
            }

            Screen.Settings -> SettingsScreen(vm)
            Screen.Gallery -> GalleryScreen(vm)
            is Screen.Detail -> PhotoDetailScreen(vm)
        }

        if (vm.queueOpen) {
            QueueSheet(vm)
        }

        vm.pendingDeleteId?.let {
            AlertDialog(
                onDismissRequest = { vm.cancelDelete() },
                title = { Text("删除照片") },
                text = { Text("App 里的这张照片和系统相册里的这张都会被删除，确定删除吗？") },
                confirmButton = {
                    TextButton(onClick = { vm.confirmDelete() }) {
                        Text("删除", color = Color(0xFFFF8A80))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.cancelDelete() }) { Text("取消") }
                },

            )
        }

        vm.error?.let { message ->
            AlertDialog(
                onDismissRequest = { vm.error = null },
                confirmButton = {
                    TextButton(onClick = { vm.error = null }) { Text("知道了") }
                },
                title = { Text("提示") },
                text = { Text(message) },

            )
        }
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("需要相机权限才能拍照", color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("授予权限") }
    }
}
