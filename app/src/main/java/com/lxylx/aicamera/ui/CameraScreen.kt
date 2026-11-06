package com.lxylx.aicamera.ui

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.lxylx.aicamera.R
import com.lxylx.aicamera.data.PhotoItem
import com.lxylx.aicamera.data.Prefs
import com.lxylx.aicamera.util.Aspect
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
fun CameraScreen(
    prefs: Prefs,
    queueCount: Int,
    latestPhoto: PhotoItem?,
    captured: (ByteArray, String, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenQueue: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ---- 状态
    var ratioLabel by remember { mutableStateOf(prefs.ratioLabel) }
    var flashMode by remember { mutableIntStateOf(prefs.flashMode) }
    var displayRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var hasFlashUnit by remember { mutableStateOf(false) }
    var sensorLandscape by remember { mutableStateOf(false) }
    // 手机实际朝向对应的旋转（用于让照片本身是正的横/竖图）
    var deviceRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
    // 镜头感：开着时人物"知道你在拍他"
    var cameraAware by remember { mutableStateOf(prefs.cameraAware) }
    // 二次元 ↔ 三次元
    var dimensionLevel by remember { mutableIntStateOf(prefs.dimensionLevel) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val previewUseCase = remember {
        Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // ---- 沉浸式：隐藏状态栏 / 导航栏
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // ---- 传感器方向：只用来判断拍出来是横图还是竖图
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                sensorLandscape = orientation in 45 until 135 || orientation in 225 until 315
                deviceRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    // ---- 显示方向：相机跟随屏幕方向
    DisposableEffect(Unit) {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                displayRotation = displayManager
                    ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
            }
        }
        displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        displayRotation = displayManager
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        onDispose { displayManager?.unregisterDisplayListener(listener) }
    }

    // ---- 相机初始化
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        try {
            provider = suspendCancellableCoroutine { continuation ->
                future.addListener({
                    try {
                        continuation.resume(future.get())
                    } catch (t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        } catch (e: Exception) {
            onError("相机初始化失败：${e.message}")
        }
    }

    LaunchedEffect(provider, displayRotation) {
        val cameraProvider = provider ?: return@LaunchedEffect
        imageCapture.targetRotation = displayRotation
        previewUseCase.targetRotation = displayRotation
        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                previewUseCase,
                imageCapture
            )
        } catch (e: Exception) {
            onError("相机启动失败：${e.message}")
        }
    }

    // ---- 变焦范围 / 闪光灯
    DisposableEffect(camera) {
        val activeCamera = camera
        val observer = Observer<ZoomState> { state ->
            minZoom = state.minZoomRatio
            maxZoom = state.maxZoomRatio
            android.util.Log.i(
                "Tulpa",
                "zoomState min=${state.minZoomRatio} max=${state.maxZoomRatio} zoom=${state.zoomRatio} 显示方向=$displayRotation 横拍=$sensorLandscape"
            )
        }
        activeCamera?.cameraInfo?.zoomState?.observe(lifecycleOwner, observer)
        hasFlashUnit = activeCamera?.cameraInfo?.hasFlashUnit() ?: false
        onDispose {
            activeCamera?.cameraInfo?.zoomState?.removeObserver(observer)
        }
    }

    LaunchedEffect(zoom, camera) {
        try {
            camera?.cameraControl?.setZoomRatio(zoom.coerceIn(minZoom, maxZoom))
        } catch (_: Exception) {
            // 个别机型不支持该倍率时忽略
        }
    }

    // 闪光灯下发
    LaunchedEffect(flashMode) {
        imageCapture.flashMode = when (flashMode) {
            1 -> ImageCapture.FLASH_MODE_AUTO
            2 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    fun capturePhoto() {
        if (capturing) return
        capturing = true
        // 让照片本身按手机实际朝向成像：竖拍=竖图，横拍=横图
        imageCapture.targetRotation = deviceRotation
        prefs.ratioLabel = ratioLabel
        prefs.flashMode = flashMode
        val file = File(context.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    capturing = false
                    val bytes = try {
                        file.readBytes()
                    } catch (e: Exception) {
                        null
                    }
                    if (bytes != null) {
                        captured(bytes, ratioLabel, sensorLandscape)
                    } else {
                        onError("读取照片失败")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    onError("拍照失败：${exception.message}")
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // 顶部：闪光灯 / 比例 / 设置
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlashButton(mode = flashMode, enabled = hasFlashUnit) {
                flashMode = (flashMode + 1) % 3
            }
            Spacer(Modifier.width(6.dp))
            RatioButton(label = Aspect.label(ratioLabel, false)) {
                ratioLabel = if (ratioLabel == Prefs.RATIO_3_4) Prefs.RATIO_16_9 else Prefs.RATIO_3_4
            }
            Spacer(Modifier.width(6.dp))
            AwareButton(on = cameraAware) {
                cameraAware = !cameraAware
                prefs.cameraAware = cameraAware
            }
            Spacer(Modifier.width(6.dp))
            DimensionToggle(thirdDimension = dimensionLevel >= 50) { third ->
                dimensionLevel = if (third) 100 else 0
                prefs.dimensionLevel = dimensionLevel
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color.White)
            }
        }


        // 队列提示
        if (queueCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable(onClick = onOpenQueue)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = Accent,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("正在生成 $queueCount 张", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("查看队列 ›", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }

        // 取景框（固定竖屏比例，不随手机方向变形）
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            val previewRatio = Aspect.aspectRatio(ratioLabel, false)
            val boxW = if (maxWidth / maxHeight > previewRatio) maxHeight * previewRatio else maxWidth
            val boxH = boxW / previewRatio
            Box(
                modifier = Modifier
                    .size(boxW, boxH)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                    .pointerInput(minZoom, maxZoom) {
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            val upper = maxZoom.coerceAtLeast(minZoom + 0.05f)
                            zoom = (zoom * gestureZoom).coerceIn(minZoom, upper)
                        }
                    }
            ) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            }
        }

        // 变焦滑动条（等距刻度、窄条居中）
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ZoomSlider(
                ratio = zoom,
                minRatio = minZoom,
                maxRatio = maxZoom,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) { zoom = it }
        }

        // 底部：图库 / 快门 / 占位
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryButton(latestPhoto = latestPhoto, onClick = onOpenGallery)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .border(5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(if (capturing) Color(0xFF9A9A9A) else Color.White)
                    .clickable(enabled = !capturing) { capturePhoto() }
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(54.dp))
        }
    }
}

@Composable
private fun FlashButton(mode: Int, enabled: Boolean, onClick: () -> Unit) {
    val label = when (mode) {
        1 -> "自动"
        2 -> "开"
        else -> "关"
    }
    val icon: Painter = painterResource(
        if (mode == 0) R.drawable.ic_flash_off else R.drawable.ic_flash_on
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = icon,
            contentDescription = "闪光灯",
            tint = when {
                !enabled -> Color.White.copy(alpha = 0.25f)
                mode == 0 -> Color.White.copy(alpha = 0.6f)
                else -> Accent
            },
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(5.dp))
        if (enabled) {
            Text(text = label, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun RatioButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DimensionToggle(thirdDimension: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(2.dp)
    ) {
        DimensionSegment("二次元", selected = !thirdDimension) { onChange(false) }
        DimensionSegment("三次元", selected = thirdDimension) { onChange(true) }
    }
}

@Composable
private fun DimensionSegment(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            color = if (selected) Color.Black else Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun AwareButton(on: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_eye),
            contentDescription = "镜头感",
            tint = if (on) Accent else Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = if (on) "开" else "关",
            color = if (on) Accent else Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun GalleryButton(latestPhoto: PhotoItem?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val path = latestPhoto?.let { it.resultPath ?: it.scenePath }
        if (path != null) {
            AsyncImage(
                model = File(path),
                contentDescription = "图库",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_gallery),
                contentDescription = "图库",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
