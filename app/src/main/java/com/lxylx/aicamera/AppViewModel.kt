package com.lxylx.aicamera

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lxylx.aicamera.data.JobStatus
import com.lxylx.aicamera.data.PhotoItem
import com.lxylx.aicamera.data.PhotoStore
import com.lxylx.aicamera.data.Prefs
import com.lxylx.aicamera.net.ApiClient
import com.lxylx.aicamera.net.ModelCatalog
import com.lxylx.aicamera.util.Aspect
import com.lxylx.aicamera.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed interface Screen {
    data object Camera : Screen
    data object Settings : Screen
    data object Gallery : Screen
    data class Detail(val photoId: String) : Screen
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = Prefs(application)
    private val api = ApiClient()
    private val store = PhotoStore(application)

    var screen by mutableStateOf<Screen>(Screen.Camera)
    var photos by mutableStateOf<List<PhotoItem>>(emptyList())
    var queueOpen by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var pendingDeleteId by mutableStateOf<String?>(null)

    // ---- DeepSeek（看图 + 写提示词）
    var deepseekKey by mutableStateOf(prefs.deepseekKey)
    var deepseekBase by mutableStateOf(prefs.deepseekBaseUrl)
    var deepseekModel by mutableStateOf(prefs.deepseekModel)
    var deepseekModels by mutableStateOf<List<String>>(emptyList())
    var deepseekStatus by mutableStateOf("")
    var fetchingDeepseek by mutableStateOf(false)

    // ---- 火山方舟（生图）
    var arkKey by mutableStateOf(prefs.arkKey)
    var arkBase by mutableStateOf(prefs.arkBaseUrl)
    var arkImageModel by mutableStateOf(prefs.arkImageModel.ifBlank { ModelCatalog.FALLBACK_ARK_IMAGE.first() })
    var arkModels by mutableStateOf<List<String>>(emptyList())
    var arkStatus by mutableStateOf("")
    var fetchingArk by mutableStateOf(false)
    var arkTestStatus by mutableStateOf("")
    var testingArk by mutableStateOf(false)

    // ---- 参考人物
    var referencePath by mutableStateOf(prefs.referencePath)
    var hasReference by mutableStateOf(prefs.hasReference)



    init {
        photos = store.load().map {
            if (it.status == JobStatus.PROCESSING) it.copy(status = JobStatus.QUEUED, step = "") else it
        }
        store.save(photos)

        val cachedDeepseek = parseIds(prefs.cachedDeepseekModels)
        val cachedArk = parseIds(prefs.cachedArkModels)
        deepseekModels = cachedDeepseek.ifEmpty { ModelCatalog.FALLBACK_DEEPSEEK }
        arkModels = cachedArk.ifEmpty { ModelCatalog.FALLBACK_ARK_IMAGE }
        if (prefs.deepseekModel.isBlank()) {
            deepseekModel = deepseekModels.firstOrNull() ?: Prefs.DEFAULT_DEEPSEEK_MODEL
            prefs.deepseekModel = deepseekModel
        }
        if (prefs.arkImageModel.isBlank()) {
            arkImageModel = arkModels.firstOrNull() ?: ModelCatalog.FALLBACK_ARK_IMAGE.first()
            prefs.arkImageModel = arkImageModel
        }
        val queued = photos.filter { it.status == JobStatus.QUEUED }
        Log.i(TAG, "ViewModel 初始化：待处理 ${queued.size} 张，照片共 ${photos.size} 张")
        ensureWorker()
    }

    /** 启动常驻 worker（进程内唯一）。worker 意外退出后，再调一次会重新拉起。 */
    private fun ensureWorker() {
        if (!workerRunning.compareAndSet(false, true)) {
            Log.i(TAG, "已有队列 worker 在运行，本次不再启动")
            return
        }
        viewModelScope.launch(Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "队列 worker 崩溃", e)
            workerRunning.set(false)
        }) {
            Log.i(TAG, "队列 worker 启动（进程内唯一）")
            try {
                while (isActive) {
                    val next = photos.firstOrNull { it.status == JobStatus.QUEUED }
                    if (next == null) {
                        delay(400)
                        continue
                    }
                    Log.i(TAG, "队列取出: ${next.id}")
                    try {
                        processOne(next.id)
                    } catch (t: Throwable) {
                        Log.e(TAG, "处理任务异常（worker 继续运行）: ${next.id}", t)
                        runCatching { setPhoto(next.id) { it.copy(status = JobStatus.FAILED, step = "", error = "处理异常：${t.message}") } }
                        delay(300)
                        continue
                    }
                    // 保险：如果处理完还是"排队中"（比如被重复保护跳过），标记失败，避免无限排队
                    val after = photos.firstOrNull { it.id == next.id }
                    if (after != null && after.status == JobStatus.QUEUED) {
                        Log.w(TAG, "任务未被处理，标记失败: ${next.id}")
                        setPhoto(next.id) {
                            it.copy(status = JobStatus.FAILED, step = "", error = "任务未能开始，请点重试")
                        }
                    }
                }
            } finally {
                workerRunning.set(false)
            }
        }
    }

    val queueCount: Int
        get() = photos.count { it.status == JobStatus.QUEUED || it.status == JobStatus.PROCESSING }

    val queueDone: Int
        get() = photos.count { it.status == JobStatus.DONE || it.status == JobStatus.FAILED }

    // ------------------------------------------------------------ 设置项

    fun updateDeepseekKey(value: String) {
        deepseekKey = value
        prefs.deepseekKey = value
    }

    fun updateDeepseekBase(value: String) {
        deepseekBase = value
        prefs.deepseekBaseUrl = value
    }

    fun updateDeepseekModel(value: String) {
        deepseekModel = value
        prefs.deepseekModel = value
    }

    fun updateArkKey(value: String) {
        arkKey = value
        prefs.arkKey = value
    }

    fun updateArkBase(value: String) {
        arkBase = value
        prefs.arkBaseUrl = value
    }

    fun updateArkModel(value: String) {
        arkImageModel = value
        prefs.arkImageModel = value
    }

    fun refreshDeepseekModels() {
        if (deepseekKey.isBlank()) {
            deepseekStatus = "请先填写 DeepSeek API Key"
            return
        }
        if (fetchingDeepseek) return
        fetchingDeepseek = true
        deepseekStatus = "正在拉取…"
        viewModelScope.launch {
            try {
                val ids = withContext(Dispatchers.IO) {
                    api.fetchModelIds(deepseekBase, deepseekKey, "DeepSeek")
                }
                val models = ModelCatalog.deepseekModels(ids)
                deepseekModels = models
                prefs.cachedDeepseekModels = JSONArray(models).toString()
                if (deepseekModel.isBlank() || deepseekModel !in models) {
                    deepseekModel = models.firstOrNull() ?: Prefs.DEFAULT_DEEPSEEK_MODEL
                    prefs.deepseekModel = deepseekModel
                }
                deepseekStatus = "拉取成功：${models.size} 个模型"
            } catch (e: Exception) {
                Log.e(TAG, "拉取 DeepSeek 模型失败", e)
                deepseekStatus = "拉取失败：${e.message}"
            } finally {
                fetchingDeepseek = false
            }
        }
    }

    fun refreshArkModels() {
        if (arkKey.isBlank()) {
            arkStatus = "请先填写火山方舟 API Key"
            return
        }
        if (fetchingArk) return
        fetchingArk = true
        arkStatus = "正在拉取…"
        viewModelScope.launch {
            try {
                val ids = withContext(Dispatchers.IO) {
                    api.fetchModelIds(arkBase, arkKey, "火山方舟")
                }
                val models = ModelCatalog.arkImageModels(ids)
                arkModels = models
                prefs.cachedArkModels = JSONArray(models).toString()
                if (arkImageModel.isBlank() || arkImageModel !in models) {
                    arkImageModel = models.firstOrNull() ?: ModelCatalog.FALLBACK_ARK_IMAGE.first()
                    prefs.arkImageModel = arkImageModel
                }
                arkStatus = if (models.isEmpty()) {
                    "这个账号下没找到生图模型（seedream/seededit）"
                } else {
                    "拉取成功：${models.size} 个生图模型"
                }
            } catch (e: Exception) {
                Log.e(TAG, "拉取方舟模型失败", e)
                arkStatus = "拉取失败：${e.message}"
            } finally {
                fetchingArk = false
            }
        }
    }

    fun testArkImage() {
        if (arkKey.isBlank() || arkImageModel.isBlank()) {
            arkTestStatus = "请先填方舟 Key 并选择生图模型"
            return
        }
        if (testingArk) return
        testingArk = true
        arkTestStatus = "正在测试生图接口（纯文生图，不带参考图）…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.testImageGeneration(arkBase, arkKey, arkImageModel)
            }
            arkTestStatus = result
            testingArk = false
        }
    }

    fun pickReference(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val oldPath = prefs.referencePath
                // 每次用唯一文件名，避免同名文件被图片库缓存导致"换了没反应"
                val target = withContext(Dispatchers.IO) {
                    val dir = File(app.filesDir, "reference").apply { mkdirs() }
                    val file = File(dir, "ref_${System.currentTimeMillis()}.jpg")
                    ImageUtils.copyUriToFile(app, uri, file)
                    file
                }
                referencePath = target.absolutePath
                prefs.referencePath = target.absolutePath
                hasReference = true
                Log.i(TAG, "参考照片已更新: ${target.absolutePath}")
                withContext(Dispatchers.IO) {
                    if (oldPath.isNotBlank() && oldPath != target.absolutePath) {
                        runCatching { File(oldPath).delete() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "参考照片保存失败", e)
                error = e.message ?: "参考照片保存失败"
            }
        }
    }

    // ------------------------------------------------------------ 拍照 -> 入队

    fun onCaptured(jpeg: ByteArray, ratio: String, landscape: Boolean) {
        if (!checkReady()) return
        viewModelScope.launch {
            try {
                val id = newId()
                withContext(Dispatchers.IO) {
                    val raw = ImageUtils.decode(jpeg, 2400)
                    val (targetW, targetH) = Aspect.target(ratio, landscape)
                    val cropped = ImageUtils.cropToAspect(raw, targetW, targetH)
                    val scaled = ImageUtils.scaleToMaxSide(cropped, 2048)
                    store.sceneFile(id).writeBytes(ImageUtils.compressJpeg(scaled, 92))
                    Log.i(
                        TAG,
                        "场景图: 原图 ${raw.width}x${raw.height} -> 裁切 ${cropped.width}x${cropped.height}" +
                            "（目标比例 ${targetW}:${targetH}，横拍=${landscape}）"
                    )
                }
                photos = photos + PhotoItem(
                    id = id,
                    createdAt = System.currentTimeMillis(),
                    scenePath = store.sceneFile(id).absolutePath,
                    status = JobStatus.QUEUED,
                    ratioLabel = ratio,
                    landscape = landscape,
                    cameraAware = prefs.cameraAware,
                    dimensionLevel = prefs.dimensionLevel
                )
                store.save(photos)
                Log.i(TAG, "入队: id=$id ratio=$ratio landscape=$landscape")
                enqueue(id)
                ensureWorker()
            } catch (e: Exception) {
                Log.e(TAG, "保存照片失败", e)
                error = "保存照片失败：${e.message}"
            }
        }
    }

    private fun checkReady(): Boolean {
        if (!checkReadyInternal()) return false
        return true
    }

    private fun checkReadyInternal(): Boolean {
        if (!prefs.isConfigured) {
            error = "请先在设置里填好 DeepSeek 和火山方舟两个 API Key，并选择模型"
            screen = Screen.Settings
            return false
        }
        if (!hasReference) {
            error = "请先在设置里上传一张参考人物照片"
            screen = Screen.Settings
            return false
        }
        if (!ModelCatalog.supportsReferenceImage(arkImageModel)) {
            error = "当前生图模型（$arkImageModel）不支持参考图，无法把人加进照片。请换用 seedream-4 / seededit 系列。"
            screen = Screen.Settings
            return false
        }
        return true
    }

    // ------------------------------------------------------------ 队列执行

    private fun enqueue(id: String) {
        // 队列由常驻 worker 轮询处理，这里只记录一下
        Log.i(TAG, "入队: $id")
    }

    private suspend fun processOne(id: String) {
        // 单飞保护：同一时刻只允许一个生成任务，防止重复请求触发 429
        if (!processing.compareAndSet(false, true)) {
            Log.w(TAG, "已有任务在处理，跳过: $id")
            return
        }
        try {
            processOneInner(id)
        } finally {
            processing.set(false)
        }
    }

    private suspend fun processOneInner(id: String) {
        Log.i(TAG, "processOne 进入: $id")
        val item = photos.firstOrNull { it.id == id }
        if (item == null) {
            Log.w(TAG, "processOne 找不到记录: $id（当前 ${photos.size} 条）")
            return
        }
        if (item.status != JobStatus.QUEUED) {
            Log.w(TAG, "不是待处理状态（${item.status}），跳过: $id")
            return
        }
        // 同一条照片在本进程内只处理一次（防止多个 ViewModel 实例重复请求）
        if (!handledIds.add(id)) {
            Log.w(TAG, "该照片已处理或在处理中，跳过: $id")
            return
        }
        Log.i(TAG, "processOne 准备处理: $id, 场景文件=${item.scenePath}")
        if (item.galleryUri.isNotBlank()) {
            val oldUri = item.galleryUri
            withContext(Dispatchers.IO) { ImageUtils.deleteFromGallery(getApplication(), oldUri) }
            setPhoto(id) { it.copy(galleryUri = "") }
        }
        setPhoto(id) { it.copy(status = JobStatus.PROCESSING, step = "正在准备照片…", error = null) }
        Log.i(TAG, "processOne 状态已置为处理中: $id")
        try {
            val sceneBytes = withContext(Dispatchers.IO) { File(item.scenePath).readBytes() }
            val (targetW, targetH) = Aspect.target(item.ratioLabel, item.landscape)
            val apiSize = Aspect.apiSize(item.ratioLabel, item.landscape)

            setPhoto(id) { it.copy(step = "正在看图…") }
            val sceneJpeg = withContext(Dispatchers.IO) {
                ImageUtils.compressJpeg(
                    ImageUtils.scaleToMaxSide(ImageUtils.decode(sceneBytes, 2000), 1536), 90
                )
            }
            val referenceJpeg = withContext(Dispatchers.IO) {
                val bytes = File(referencePath).readBytes()
                ImageUtils.compressJpeg(
                    ImageUtils.scaleToMaxSide(ImageUtils.decode(bytes, 2000), 1400), 92
                )
            }
            Log.i(TAG, "发起请求 id=$id model=$arkImageModel size=$apiSize target=${targetW}x$targetH")

            setPhoto(id) { it.copy(step = "智能体正在分析场景…") }
            val plan = withContext(Dispatchers.IO) {
                api.planScenePrompt(
                    baseUrl = deepseekBase,
                    apiKey = deepseekKey,
                    model = deepseekModel,
                    sceneJpeg = sceneJpeg,
                    referenceJpeg = referenceJpeg,
                    cameraAware = item.cameraAware,
                    dimensionLevel = item.dimensionLevel,
                    userHint = item.userHint
                )
            }
            prefs.lastPrompt = plan.prompt
            setPhoto(id) {
                it.copy(step = "正在生成…（出图较慢时请耐心等待）", prompt = plan.prompt, agentNote = plan.note)
            }
            val resultBytes = withContext(Dispatchers.IO) {
                val raw = api.generateImage(
                    baseUrl = arkBase,
                    apiKey = arkKey,
                    model = arkImageModel,
                    prompt = plan.prompt,
                    sceneJpeg = sceneJpeg,
                    referenceJpeg = referenceJpeg,
                    apiSize = apiSize
                )
                // 有些模型只支持 2K/3K/4K 预设，出图比例可能和拍摄比例不一致，
                // 这里统一按目标比例居中裁切，保证成片比例和取景框一致。
                val bitmap = ImageUtils.decode(raw, 4096)
                val cropped = ImageUtils.cropToAspect(bitmap, targetW, targetH)
                Log.i(TAG, "成片裁切: ${bitmap.width}x${bitmap.height} -> ${cropped.width}x${cropped.height}")
                ImageUtils.compressJpeg(cropped, 92)
            }
            val galleryUri = withContext(Dispatchers.IO) {
                val outFile = store.resultFile(id)
                outFile.writeBytes(resultBytes)
                // 自动存入系统相册，并记录 URI 以便删除时同步
                try {
                    ImageUtils.saveToGallery(
                        getApplication(),
                        resultBytes,
                        "Tulpa_${id}.jpg"
                    ).toString()
                } catch (e: Exception) {
                    Log.w(TAG, "自动存入相册失败（不影响出图）: ${e.message}")
                    ""
                }
            }
            setPhoto(id) {
                it.copy(
                    status = JobStatus.DONE,
                    step = "",
                    resultPath = store.resultFile(id).absolutePath,
                    galleryUri = galleryUri
                )
            }
            Log.i(TAG, "生成完成 id=$id -> 已存相册=$galleryUri")
        } catch (e: Exception) {
            Log.e(TAG, "生成失败 id=$id", e)
            setPhoto(id) {
                it.copy(status = JobStatus.FAILED, step = "", error = e.message ?: "生成失败")
            }
        }
    }

    private suspend fun setPhoto(id: String, transform: (PhotoItem) -> PhotoItem) {
        withContext(Dispatchers.Main) {
            photos = photos.map { if (it.id == id) transform(it) else it }
            store.save(photos)
        }
    }

    fun retry(id: String) {
        handledIds.remove(id)
        photos = photos.map {
            if (it.id == id) it.copy(status = JobStatus.QUEUED, step = "", error = null) else it
        }
        store.save(photos)
        enqueue(id)
        ensureWorker()
    }

    /** 重新合成：可选带上用户自己的想法，重置状态后重新入队 */
    fun recompose(id: String, hint: String) {
        handledIds.remove(id)
        photos = photos.map {
            if (it.id == id) it.copy(
                status = JobStatus.QUEUED,
                step = "",
                error = null,
                prompt = "",
                agentNote = "",
                userHint = hint.trim()
            ) else it
        }
        store.save(photos)
        enqueue(id)
        ensureWorker()
    }

    /** 删除前先确认，防止误删 */
    fun requestDelete(id: String) {
        pendingDeleteId = id
    }

    fun cancelDelete() {
        pendingDeleteId = null
    }

    fun confirmDelete() {
        val id = pendingDeleteId ?: return
        pendingDeleteId = null
        deletePhotoNow(id)
    }

    private fun deletePhotoNow(id: String) {
        val item = photos.firstOrNull { it.id == id } ?: return
        // 同步删掉系统相册里那张（如果自动存入过）
        val app = getApplication<Application>()
        if (item.galleryUri.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                val ok = ImageUtils.deleteFromGallery(app, item.galleryUri)
                Log.i(TAG, "删除相册图片: uri=${item.galleryUri} 成功=$ok")
            }
        }
        store.delete(item)
        photos = photos.filterNot { it.id == id }
        store.save(photos)
        if ((screen as? Screen.Detail)?.photoId == id) screen = Screen.Gallery
    }

    fun savePhotoToGallery(id: String) {
        val item = photos.firstOrNull { it.id == id } ?: return
        val path = item.resultPath ?: item.scenePath
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ImageUtils.saveToGallery(
                        app,
                        File(path).readBytes(),
                        "Tulpa_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                    )
                }
                error = null
            } catch (e: Exception) {
                error = "保存到相册失败：${e.message}"
            }
        }
    }

    // ------------------------------------------------------------ 导航

    fun openSettings() {
        screen = Screen.Settings
    }

    fun openGallery() {
        screen = Screen.Gallery
    }

    fun openDetail(id: String) {
        queueOpen = false
        screen = Screen.Detail(id)
    }

    fun openQueue() {
        queueOpen = true
    }

    fun closeQueue() {
        queueOpen = false
    }

    fun backToCamera() {
        screen = Screen.Camera
    }

    fun backToGallery() {
        screen = Screen.Gallery
    }

    // ------------------------------------------------------------ 工具

    private fun newId(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" +
            UUID.randomUUID().toString().take(4)

    private fun parseIds(raw: String): List<String> = try {
        if (raw.isBlank()) emptyList() else JSONArray(raw).let { array ->
            (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() } }
        }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        const val TAG = "Tulpa"
        private val workerRunning = java.util.concurrent.atomic.AtomicBoolean(false)
        private val processing = java.util.concurrent.atomic.AtomicBoolean(false)
        private val handledIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}
