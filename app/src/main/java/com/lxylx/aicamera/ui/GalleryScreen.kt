package com.lxylx.aicamera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.lxylx.aicamera.AppViewModel
import com.lxylx.aicamera.Screen
import com.lxylx.aicamera.data.JobStatus
import com.lxylx.aicamera.data.PhotoItem
import java.io.File

@Composable
fun GalleryScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.backToCamera() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("图库", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (vm.photos.isNotEmpty()) {
                Text(
                    "${vm.photos.size} 张",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        if (vm.photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有照片，回相机拍一张吧",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                    fontSize = 13.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(vm.photos.sortedByDescending { it.createdAt }, key = { it.id }) { photo ->
                    GalleryCell(photo) { vm.openDetail(photo.id) }
                }
            }
        }
    }
}

@Composable
private fun GalleryCell(photo: PhotoItem, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = File(photo.resultPath ?: photo.scenePath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        when (photo.status) {
            JobStatus.QUEUED, JobStatus.PROCESSING -> Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Accent,
                    strokeWidth = 2.dp
                )
            }

            JobStatus.FAILED -> Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center
            ) {
                Text("失败", color = Color(0xFFFF8A80), fontSize = 12.sp)
            }

            JobStatus.DONE -> Unit
        }
    }
}

@Composable
fun PhotoDetailScreen(vm: AppViewModel) {
    val id = (vm.screen as? Screen.Detail)?.photoId.orEmpty()
    val photo = vm.photos.firstOrNull { it.id == id }
    val context = LocalContext.current
    var showOriginal by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    var showRecompose by remember { mutableStateOf(false) }
    var hintText by remember { mutableStateOf("") }



    if (photo == null) {
        LaunchedEffect(Unit) { vm.backToGallery() }
        return
    }


    val resultPath = photo.resultPath
    val hasResult = resultPath != null
    val path = if (showOriginal || !hasResult) photo.scenePath else resultPath!!

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.backToGallery() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                when (photo.status) {
                    JobStatus.DONE -> "合成结果"
                    JobStatus.FAILED -> "生成失败"
                    JobStatus.QUEUED -> "排队中"
                    JobStatus.PROCESSING -> "生成中…"
                },
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                    onClick = {
                        hintText = photo.userHint
                        showRecompose = true
                    },
                    enabled = photo.status != JobStatus.QUEUED && photo.status != JobStatus.PROCESSING
                ) {
                    Text(
                        "重新合成",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
                if (hasResult) {
                TextButton(onClick = { showOriginal = !showOriginal }) {
                    Text(
                        if (showOriginal) "看合成图" else "看原图",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
                TextButton(onClick = { showPrompt = !showPrompt }) {
                    Text("提示词", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (photo.status == JobStatus.PROCESSING || photo.status == JobStatus.QUEUED) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Accent,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    photo.step.ifBlank { "等待生成…" },
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }

        if (photo.status == JobStatus.FAILED) {
            Text(
                photo.error ?: "生成失败",
                color = Color(0xFFFF8A80),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        if (showPrompt && photo.prompt.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "提示词",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        photo.prompt,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                    if (photo.userHint.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "你的要求",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            fontSize = 10.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            photo.userHint,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }
                    if (photo.agentNote.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "AI 思考",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            fontSize = 10.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            photo.agentNote,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { vm.requestDelete(id) },
                modifier = Modifier.weight(1f)
            ) {
                Text("删除")
            }
            Button(
                onClick = { vm.backToGallery() },
                modifier = Modifier.weight(1.6f)
            ) {
                Text("返回")
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    if (showRecompose) {
        AlertDialog(
            onDismissRequest = { showRecompose = false },
            title = { Text("重新合成") },
            text = {
                Column {
                    Text(
                        "说说你希望这张照片怎么改，智能体会照你的意思重新合成：",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = hintText,
                        onValueChange = { hintText = it },
                        placeholder = {
                            Text(
                                "例如：让她坐在靠窗的位置，笑一点，别离桌子太近；\n桌子的朝向要和教室其他桌子一致",
                                fontSize = 11.sp
                            )
                        },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "留空也可以，就是按原来的方案重画一张",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        fontSize = 10.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRecompose = false
                    vm.recompose(id, hintText)
                }) { Text("开始合成") }
            },
            dismissButton = {
                TextButton(onClick = { showRecompose = false }) { Text("取消") }
            }
        )
    }
}
