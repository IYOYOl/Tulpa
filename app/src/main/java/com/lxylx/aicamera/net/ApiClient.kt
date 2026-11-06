package com.lxylx.aicamera.net

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: Int = 0, val retryAfterSeconds: Long? = null) : Exception(message)

/** 智能体产出：最终提示词 + 可展示的思考摘要（位置/姿态/自查） */
data class ScenePlan(val prompt: String, val note: String)

/**
 * 双密钥调用：
 *  - DeepSeek：/chat/completions 看图并写提示词（OpenAI 兼容，图片走 base64 image_url）
 *  - 火山方舟：/images/generations 生图（多图参考：场景照 + 参考人物照）
 */
class ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(240, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    // 生图专用：5.0 pro 这类模型出图很慢，给 10 分钟
    private val longClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ------------------------------------------------------------ 模型列表

    fun fetchModelIds(baseUrl: String, apiKey: String, provider: String): List<String> {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            Log.i(TAG, "[$provider] GET /models -> ${response.code}, ${body.length}B")
            if (!response.isSuccessful) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                throw ApiException(errorMessage(provider, response.code, body), response.code, retryAfter)
            }
            val data = JSONObject(body).optJSONArray("data")
                ?: throw ApiException("$provider 模型列表返回格式异常：${body.take(300)}")
            val ids = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) ids.add(id)
            }
            return ids.distinct()
        }
    }

    // ------------------------------------------------------------ DeepSeek 智能体（两步思考）

    /**
     * 两步思考，避免"直出"造成违和：
     *  第 1 步：分析场景（天气/光线/可交互支点），给出 3 个"怎么放人"的方案 + 每个方案的违和风险
     *  第 2 步：挑最不容易违和的方案、自我审查并修正，输出最终生图指令
     */
    /**
     * 多角色商讨式智能体（3 个阶段）：
     *  ① 视觉分析员：详细拆解场景（空间、透视、镜头、光线、物体朝向、可交互点）
     *  ② 摄影师 / 灯光师 / 合成师：三方从构图、光影、违和风险给意见
     *  ③ 导演：汇总意见，产出最终生图指令
     */
    fun planScenePrompt(
        baseUrl: String,
        apiKey: String,
        model: String,
        sceneJpeg: ByteArray,
        referenceJpeg: ByteArray?,
        cameraAware: Boolean,
        dimensionLevel: Int,
        userHint: String = ""
    ): ScenePlan {
        val hintBlock = if (userHint.isBlank()) "" else "\n用户本人对这次合成的具体要求（优先级最高，必须满足）：$userHint"
        val images = if (referenceJpeg != null) listOf(sceneJpeg, referenceJpeg) else listOf(sceneJpeg)

        val analysis = chatWithFallback(baseUrl, apiKey, model, images, analyzeInstruction() + hintBlock)
        Log.i(TAG, "智能体①视觉分析(${analysis.length}字): ${analysis.take(700)}")

        val discussion = chatWithFallback(baseUrl, apiKey, model, images, discussInstruction(analysis) + hintBlock)
        Log.i(TAG, "智能体②三方商讨(${discussion.length}字): ${discussion.take(700)}")

        val finalText = chatWithFallback(
            baseUrl, apiKey, model, images,
            finalInstruction(cameraAware, dimensionLevel, analysis, discussion) + hintBlock
        )
        Log.i(TAG, "智能体③导演定稿(${finalText.length}字): ${finalText.take(500)}")

        val startIdx = finalText.indexOf('{')
        val endIdx = finalText.lastIndexOf('}')
        val obj = if (startIdx >= 0 && endIdx > startIdx) {
            runCatching { JSONObject(finalText.substring(startIdx, endIdx + 1)) }.getOrNull()
        } else {
            null
        }
        val prompt = obj?.optString("prompt").orEmpty().ifBlank { extractPrompt(finalText) }
        val note = buildString {
            obj?.optString("interaction").orEmpty().takeIf { it.isNotBlank() }?.let { appendLine("互动：$it") }
            obj?.optString("place").orEmpty().takeIf { it.isNotBlank() }?.let { appendLine("位置：$it") }
            obj?.optString("pose").orEmpty().takeIf { it.isNotBlank() }?.let { appendLine("状态：$it") }
            val why = obj?.optString("note").orEmpty().ifBlank { obj?.optString("selfCheck").orEmpty() }
            why.takeIf { it.isNotBlank() }?.let { appendLine("判断：$it") }
        }.trim()
        if (prompt.isBlank()) throw ApiException("智能体没有产出有效提示词，请重试")
        return ScenePlan(prompt = prompt, note = note)
    }
    /** 一次带图对话；正文为空时用官方最小请求形状重试一次。 */
    private fun chatWithFallback(
        baseUrl: String,
        apiKey: String,
        model: String,
        images: List<ByteArray>,
        instructionText: String
    ): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", instructionText))
        images.forEach { jpeg ->
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl(jpeg)))
            )
        }
        val userMessage = JSONObject().put("role", "user").put("content", content)

        val withSystem = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(userMessage)
            )
            .put("temperature", 0.85)
            .put("max_tokens", 4096)
            .put("stream", false)
            // 开启思考模式并拉满推理强度；正文为空时会自动用最小请求兜底
            .put("thinking", JSONObject().put("type", "enabled"))
            .put("reasoning_effort", "high")
        val first = try {
            runChat(baseUrl, apiKey, withSystem)
        } catch (e: ApiException) {
            // 某些账号/模型可能不认 thinking/reasoning_effort，参数报错时退到最小请求
            if (e.code == 400 || e.code == 422) {
                Log.w(TAG, "带思考参数的请求被拒（${e.code}）：${e.message}，改用最小请求")
                ""
            } else {
                throw e
            }
        }
        if (first.isNotBlank()) return first

        Log.w(TAG, "DeepSeek 正文为空，改用最小请求重试")
        val minimal = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(userMessage))
        val retry = runChat(baseUrl, apiKey, minimal)
        if (retry.isNotBlank()) return retry

        throw ApiException(
            "DeepSeek 返回了空内容（已用最小请求形状重试仍为空）。" +
                "请确认模型是支持图片输入的 deepseek-flash，或在设置里换模型后重试。"
        )
    }
    /** 发一次 chat/completions，返回 message.content（可能为空串）。 */
    private fun runChat(baseUrl: String, apiKey: String, payload: JSONObject): String {
        val body = postWithRetry("DeepSeek", baseUrl, "/chat/completions", apiKey, payload)
        val choices = body.optJSONArray("choices") ?: run {
            Log.w(TAG, "DeepSeek 无 choices，响应：${body.toString().take(600)}")
            return ""
        }
        val message = choices.optJSONObject(0)?.optJSONObject("message")
        val text = message?.optString("content").orEmpty()
        if (text.isNotBlank()) return text

        val reasoning = message?.optString("reasoning_content").orEmpty()
        Log.w(
            TAG,
            "DeepSeek 正文为空（reasoning ${reasoning.length} 字），响应：${body.toString().take(800)}"
        )
        if (reasoning.isNotBlank()) {
            // 少数情况内容全在 reasoning_content 里，能救就救
            val extracted = extractPrompt(reasoning)
            if (extracted.isNotBlank()) return extracted
        }
        return ""
    }

    // ------------------------------------------------------------ 火山方舟 生图

    fun generateImage(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        sceneJpeg: ByteArray,
        referenceJpeg: ByteArray?,
        apiSize: String
    ): ByteArray {
        val images = JSONArray().put(dataUrl(sceneJpeg))
        if (referenceJpeg != null) images.put(dataUrl(referenceJpeg))

        val payload = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("image", images)
            .put("size", apiSize)
            .put("response_format", "url")
            .put("watermark", false)

        // 不同版本的生图模型对 size 支持不同：先按精确宽高请求，
        // 如果被拒（参数错误），自动退到 "2K" 预设再试一次，出图后本地裁回目标比例。
        val body = try {
            postWithRetry("火山方舟", baseUrl, "/images/generations", apiKey, payload)
        } catch (e: java.net.SocketTimeoutException) {
            throw ApiException(
                "生图请求超时（客户端已等 10 分钟）。5.0 pro 出图很慢，" +
                    "通常是服务器还在生成；请稍后用「重试」再看，或换 5.0-flash 提速。"
            )
        } catch (e: ApiException) {
            val retriable = e.code == 400 && e.message?.contains("size") == true
            if (!retriable) throw e
            Log.w(TAG, "size=$apiSize 被模型拒绝，改用 2K 预设重试")
            payload.put("size", "2K")
            postWithRetry("火山方舟", baseUrl, "/images/generations", apiKey, payload)
        }
        val data = body.optJSONArray("data")
            ?: throw ApiException("生图接口没有返回图片：${body.toString().take(400)}")
        if (data.length() == 0) throw ApiException("生图接口返回空结果")

        val item = data.optJSONObject(0) ?: throw ApiException("生图结果格式异常")
        val b64 = item.optString("b64_json")
        if (b64.isNotBlank()) return Base64.decode(b64, Base64.DEFAULT)

        val url = item.optString("url")
        if (url.isBlank()) {
            val err = item.optString("error")
            throw ApiException(if (err.isNotBlank()) "生图失败：$err" else "生图结果里没有图片地址")
        }
        return download(url)
    }

    /** 诊断用：发一个最小纯文生图请求，用来判断是额度/开通问题还是多图参数问题。 */
    fun testImageGeneration(baseUrl: String, apiKey: String, model: String): String {
        val payload = JSONObject()
            .put("model", model)
            .put("prompt", "a cat sitting on a sofa, photo")
            .put("response_format", "url")
            .put("watermark", false)
        return try {
            val body = postWithRetry("火山方舟", baseUrl, "/images/generations", apiKey, payload)
            val first = body.optJSONArray("data")?.optJSONObject(0)
            val url = first?.optString("url").orEmpty()
            val b64 = first?.optString("b64_json").orEmpty()
            if (url.isNotBlank() || b64.isNotBlank()) {
                "✅ 生图接口正常（模型可用、额度正常）"
            } else {
                "⚠️ 生图返回异常：${body.toString().take(300)}"
            }
        } catch (e: ApiException) {
            "❌ 生图失败（HTTP ${e.code}）：${e.message}"
        } catch (e: Exception) {
            "❌ 生图失败：${e.message}"
        }
    }

    // ------------------------------------------------------------ 内部

    /** 429 限流 / 5xx 抖动时自动退避重试（3s、8s、15s），最多 4 次请求。 */
    private fun postWithRetry(
        provider: String,
        baseUrl: String,
        path: String,
        apiKey: String,
        payload: JSONObject
    ): JSONObject {
        // 429 常见是按分钟窗口限流，所以等待时间按 5s/20s/60s/60s 递增
        val waits = listOf(5_000L, 20_000L, 60_000L, 60_000L)
        var attempt = 0
        while (true) {
            try {
                return post(provider, baseUrl, path, apiKey, payload)
            } catch (e: ApiException) {
                val detail = (e.message ?: "").lowercase()
                val quotaIssue = e.code == 429 && (
                    detail.contains("quota") || detail.contains("balance") ||
                        detail.contains("arrears") || detail.contains("额度")
                    )
                // 额度问题重试也没用，直接失败；只有频率限流/5xx 才退避重试
                val retriable = (e.code == 429 && !quotaIssue) ||
                    e.code == 500 || e.code == 502 || e.code == 503
                if (!retriable || attempt >= waits.size) throw e
                val suggested = e.retryAfterSeconds?.let { (it * 1000).coerceAtMost(120_000L) }
                val waitMs = suggested ?: waits[attempt]
                Log.w(
                    TAG,
                    "[$provider] $path 返回 ${e.code}，${waitMs}ms 后重试（第 ${attempt + 1}/${waits.size} 次）"
                )
                try {
                    Thread.sleep(waitMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                attempt++
            }
        }
    }

    private fun post(
        provider: String,
        baseUrl: String,
        path: String,
        apiKey: String,
        payload: JSONObject
    ): JSONObject {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        val httpClient = if (path.contains("images/generations")) longClient else client
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            Log.i(TAG, "[$provider] POST $path -> ${response.code}, req=${payload.toString().length}B, resp=${text.length}B")
            if (!response.isSuccessful) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                throw ApiException(errorMessage(provider, response.code, text), response.code, retryAfter)
            }
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ApiException("$provider 返回的不是合法 JSON：${text.take(300)}")
            }
        }
    }

    private fun download(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("下载生成图片失败：HTTP ${response.code}")
            return response.body?.bytes() ?: throw ApiException("下载生成图片失败：空响应")
        }
    }

    private fun errorMessage(provider: String, code: Int, body: String): String {
        val raw = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) {
            ""
        }
        val detail = raw.ifBlank { body.take(300) }
        Log.e(TAG, "[$provider] API 错误 $code: $detail")

        if (detail.contains("has not activated") || detail.contains("activate the model")) {
            return "这个模型没在你的方舟账号开通：$detail\n\n去方舟控制台「模型广场」开通后重试，或在设置里换一个生图模型。"
        }
        return when (code) {
            401 -> "$provider 密钥无效或已过期（401）：$detail"
            403 -> "$provider 没有该模型的调用权限（403）：$detail"
            404 -> "$provider 模型或接口不存在（404）：$detail"
            429 -> {
                val lower = detail.lowercase()
                val quota = lower.contains("quota") || lower.contains("balance") || lower.contains("arrears") || detail.contains("额度")
                val head = if (quota) {
                    "$provider 额度不足或模型未开通配额（429）："
                } else {
                    "$provider 触发限流（429）："
                }
                head + detail + "\n\n已自动重试（5s/20s/60s/60s）仍失败。" +
                    if (quota) {
                        "请到方舟控制台确认该模型已开通、账户余额/配额充足（充值到账可能有延迟）。"
                    } else {
                        "这是每分钟调用次数或并发限制，等 1 分钟后重试，或换一个限流更宽松的生图模型。"
                    }
            }
            else -> "$provider 接口错误（$code）：$detail"
        }
    }

    private fun dataUrl(jpeg: ByteArray): String =
        "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)

    /** 模型可能带 markdown 代码块或解释文字，尽量取出其中的 prompt 字段。 */
    private fun extractPrompt(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val prompt = JSONObject(raw.substring(start, end + 1)).optString("prompt")
                if (prompt.isNotBlank()) return prompt.trim()
            } catch (_: Exception) {
                // 落到下面
            }
        }
        return raw.trim()
    }

    private fun analyzeInstruction(): String = """
        你是一名极其细致的图像分析师。图1是相机刚拍的场景照片；如果有图2，那是参考人物照片。
        先仔细看图，尽可能详细地拆解场景，供后面的摄影师、灯光师、合成师讨论：
        - 空间结构：前景/中景/远景分别是什么，地面材质，墙面位置，画面里有没有透视引导线（地砖缝、桌沿、天花线）
        - 镜头：拍摄高度（平视/俯拍/仰拍）、焦距感（广角/标准/长焦）、画面是否倾斜
        - 光线：主光方向、软硬、色温、明暗对比、有没有反光面、阴影落点方向
        - 物体朝向与排列：主要物体的朝向和排列规律（只写与人物互动相关的，不用罗列全部物品）
        - 可交互点：人物可以坐在/靠在/扶着/站在哪里，具体是哪个物体，接触面在哪里
        - 穿帮风险：这个场景放人最容易露馅的地方（比例、遮挡、影子、透视、地面反光等）
        要具体、可执行，不要空话。只输出 JSON：
        {"space":"...","camera":"...","light":"...","objectOrder":"...","anchors":[{"where":"...","contact":"..."}],"risks":["...","..."]}
    """.trimIndent()

    private fun discussInstruction(analysis: String): String = """
        图1是场景照片，图2（如有）是参考人物照片。视觉分析员给出的分析：
        $analysis

        现在请三位专家分别发表意见，讨论"这个人怎么放进画面才最真实"：
        - 摄影师：位置、占画面比例、朝向、镜头一致性、遮挡关系怎么处理
        - 灯光师：人物受光、阴影与投影方向、色温、接触阴影、地面反射怎么处理
        - 合成师：最容易露馅的地方在哪、怎么规避（比例、接触点、边缘、材质、画质匹配）
        三人可以意见不同，先不用给统一结论（导演最后拍板）。只输出 JSON，不要解释：
        {"photographer":"...","lighting":"...","compositor":"..."}
    """.trimIndent()

    private fun finalInstruction(
        cameraAware: Boolean,
        dimensionLevel: Int,
        analysis: String,
        discussion: String
    ): String {
        val style = if (dimensionLevel < 50) {
            "二次元：动漫/插画风格，保留描边、平涂上色、二次元五官比例；但仍要接受现场光影与阴影"
        } else {
            "三次元：真人写实，真实皮肤纹理与毛孔、真实发丝与布料；禁止描边、平涂、夸张大眼、二次元比例"
        }
        val aware = if (cameraAware) {
            "人物知道你在拍他/她：自然看向镜头或侧头回望，有轻微表情或动作反应，但要像抓拍，不是摆拍。"
        } else {
            "人物没有意识到被拍：不看镜头，专注自己在做的事。"
        }
        return """
            图1是场景照片，图2（如有）是参考人物照片。
            视觉分析：$analysis
            三方讨论：$discussion

            你是导演，现在做最终决定：可以融合三方建议，写一条给生图模型的中文指令。
            判断标准：出图后要像现场真实拍下的同一张照片，看不出是后加进去的。
            规则：
            - 人物写实程度：$style
            - 人物状态：$aware
            - 顺着场景的透视和物体朝向安排人物互动，不要为了配合人物去改变场景里物体的方向或位置（这是通用原则）
            - 参考照片只提供人物身份特征；不改变原照片内容与构图，不添加原图没有的元素，不加文字水印
            - 人物要有活人的情绪和松弛感，不要面无表情、证件照、摆拍
            - prompt 直接可执行，不解释、不列物品，长度 300 字以内
            只输出 JSON：
            {"interaction":"与场景里哪个物体怎么互动","place":"位置和占画面比例","pose":"姿态与状态","note":"你为什么这么选，哪一步最容易出戏","prompt":"最终指令"}
        """.trimIndent()
    }
    companion object {
        private const val TAG = "ApiClient"
        private const val SYSTEM_PROMPT =
            "你是导演兼摄影师。你来判断怎样让人物出现在场景里才最自然，像现场真实拍下的照片；判断标准交给你的专业直觉，而不是套模板。"
    }
}
