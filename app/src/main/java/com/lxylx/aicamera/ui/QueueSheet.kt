package com.lxylx.aicamera.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lxylx.aicamera.AppViewModel
import com.lxylx.aicamera.data.JobStatus
import com.lxylx.aicamera.data.PhotoItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QueueSheet(vm: AppViewModel) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .clickable { vm.closeQueue() }
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("生成队列", color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    "关闭",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { vm.closeQueue() }
                )
            }
            if (vm.photos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有拍过照片", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 13.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.photos.sortedByDescending { it.createdAt }, key = { it.id }) { photo ->
                        QueueRow(
                            photo = photo,
                            onClick = { vm.openDetail(photo.id) },
                            onRetry = { vm.retry(photo.id) },
                            onDelete = { vm.requestDelete(photo.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    photo: PhotoItem,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val statusText = when (photo.status) {
        JobStatus.QUEUED -> "排队中"
        JobStatus.PROCESSING -> photo.step.ifBlank { "生成中…" }
        JobStatus.DONE -> "已完成"
        JobStatus.FAILED -> "生成失败"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            AsyncImage(
                model = File(photo.resultPath ?: photo.scenePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (photo.status == JobStatus.PROCESSING) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(20.dp),
                    color = Accent,
                    strokeWidth = 2.dp
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(photo.createdAt)),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
            )
            Text(
                text = if (photo.status == JobStatus.FAILED) {
                    photo.error?.take(60) ?: "生成失败"
                } else {
                    statusText
                },
                color = when (photo.status) {
                    JobStatus.FAILED -> Color(0xFFFF8A80)
                    JobStatus.DONE -> Color(0xFF9CCC65)
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
                fontSize = 11.sp,
                maxLines = 2
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (photo.status) {
                JobStatus.FAILED -> TextButton(onClick = onRetry) { Text("重试", color = Accent, fontSize = 12.sp) }
                JobStatus.DONE -> TextButton(onClick = onClick) { Text("查看", color = Accent, fontSize = 12.sp) }
                else -> Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), fontSize = 12.sp)
            }
        }
    }
}
