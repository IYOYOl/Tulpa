package com.lxylx.aicamera.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import android.net.Uri
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lxylx.aicamera.AppViewModel
import java.io.File

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.pickReference(uri) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 40.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.backToCamera() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("设置", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.backToCamera() }) {
                Text("完成", color = Accent)
            }
        }

        Column(Modifier.padding(horizontal = 18.dp)) {

            // ------------------------------------------------ DeepSeek
            SectionTitle("① DeepSeek")
            OutlinedTextField(
                value = vm.deepseekKey,
                onValueChange = vm::updateDeepseekKey,
                singleLine = true,
                label = { Text("DeepSeek API Key") },
                placeholder = { Text("sk-…") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.deepseekBase,
                onValueChange = vm::updateDeepseekBase,
                singleLine = true,
                label = { Text("接口地址") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            ModelField(
                label = "推理模型（需支持图片输入）",
                value = vm.deepseekModel,
                options = vm.deepseekModels,
                onValueChange = vm::updateDeepseekModel
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { vm.refreshDeepseekModels() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (vm.fetchingDeepseek) "拉取中…" else "拉取 DeepSeek 模型")
                }
                if (vm.fetchingDeepseek) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Accent,
                        strokeWidth = 2.dp
                    )
                }
            }
            if (vm.deepseekStatus.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(vm.deepseekStatus, color = DimText, fontSize = 11.sp)
            }

            Spacer(Modifier.height(20.dp))
            // ------------------------------------------------ 火山方舟
            SectionTitle("② 火山方舟")
            OutlinedTextField(
                value = vm.arkKey,
                onValueChange = vm::updateArkKey,
                singleLine = true,
                label = { Text("火山方舟 API Key") },
                placeholder = { Text("粘贴方舟 API Key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.arkBase,
                onValueChange = vm::updateArkBase,
                singleLine = true,
                label = { Text("接口地址") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            ModelField(
                label = "生图模型",
                value = vm.arkImageModel,
                options = vm.arkModels,
                onValueChange = vm::updateArkModel
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { vm.refreshArkModels() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (vm.fetchingArk) "拉取中…" else "拉取方舟生图模型")
                }
                if (vm.fetchingArk) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Accent,
                        strokeWidth = 2.dp
                    )
                }
            }
            if (vm.arkStatus.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(vm.arkStatus, color = DimText, fontSize = 11.sp)
            }


            Spacer(Modifier.height(20.dp))
            // ------------------------------------------------ 参考人物
            SectionTitle("③ 参考人物照片")
            Row(verticalAlignment = Alignment.CenterVertically) {
                val refFile = if (vm.hasReference) File(vm.referencePath) else null
                if (refFile != null) {
                    AsyncImage(
                        model = refFile,
                        contentDescription = "参考人物",
                        modifier = Modifier
                            .size(92.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f),
                                RoundedCornerShape(14.dp)
                            )
                    )
                } else {
                    Box(
                        Modifier
                            .size(92.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("未上传", color = DimText, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Button(onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Text(if (vm.hasReference) "更换照片" else "选择照片")
                    }

                }
            }

            Spacer(Modifier.height(32.dp))
            SectionTitle("④ 开源项目")
            Text(
                "Tulpa 是完全开源的项目，遵循 MIT License。",
                color = DimText,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "github.com/IYOYOl/Tulpa",
                color = Accent,
                fontSize = 13.sp,
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/IYOYOl/Tulpa"))
                        )
                    }
                }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "MIT License · 欢迎 Issue 和 PR",
                color = DimText,
                fontSize = 11.sp
            )


        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun ModelField(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            label = { Text(label) },
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { expanded = true }) { Text("列表", color = Accent) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (options.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("请先拉取模型列表") },
                        onClick = { expanded = false }
                    )
                }
                options.forEach { id ->
                    DropdownMenuItem(
                        text = { Text(id, fontSize = 13.sp) },
                        onClick = {
                            onValueChange(id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
