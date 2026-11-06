package com.lxylx.aicamera.data

import org.json.JSONObject

enum class JobStatus { QUEUED, PROCESSING, DONE, FAILED }

/** 一次拍摄对应一条记录：原图（场景照）+ 合成结果。 */
data class PhotoItem(
    val id: String,
    val createdAt: Long,
    val scenePath: String,
    val resultPath: String? = null,
    val status: JobStatus = JobStatus.QUEUED,
    val step: String = "",
    val error: String? = null,
    val ratioLabel: String = "3:4",
    val landscape: Boolean = false,
    val prompt: String = "",
    val cameraAware: Boolean = false,
    val agentNote: String = "",
    /** 自动存进系统相册后拿到的 MediaStore URI（用于删除时同步删除） */
    val galleryUri: String = "",
    /** 二次元↔三次元（0=二次元，100=真人） */
    val dimensionLevel: Int = 70,
    /** 重新合成时用户给智能体的额外要求 */
    val userHint: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("scenePath", scenePath)
        put("resultPath", resultPath ?: "")
        put("status", status.name)
        put("step", step)
        put("error", error ?: "")
        put("ratioLabel", ratioLabel)
        put("landscape", landscape)
        put("prompt", prompt)
        put("cameraAware", cameraAware)
        put("agentNote", agentNote)
        put("galleryUri", galleryUri)
        put("dimensionLevel", dimensionLevel)
        put("userHint", userHint)
    }

    companion object {
        fun fromJson(json: JSONObject): PhotoItem = PhotoItem(
            id = json.optString("id"),
            createdAt = json.optLong("createdAt"),
            scenePath = json.optString("scenePath"),
            resultPath = json.optString("resultPath").takeIf { it.isNotBlank() },
            status = runCatching { JobStatus.valueOf(json.optString("status")) }.getOrDefault(JobStatus.FAILED),
            step = json.optString("step"),
            error = json.optString("error").takeIf { it.isNotBlank() },
            ratioLabel = json.optString("ratioLabel").ifBlank { "3:4" },
            landscape = json.optBoolean("landscape"),
            prompt = json.optString("prompt"),
            cameraAware = json.optBoolean("cameraAware"),
            agentNote = json.optString("agentNote"),
            galleryUri = json.optString("galleryUri"),
            dimensionLevel = json.optInt("dimensionLevel", 70),
            userHint = json.optString("userHint")
        )
    }
}
