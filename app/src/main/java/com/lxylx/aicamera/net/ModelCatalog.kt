package com.lxylx.aicamera.net

/**
 * 模型列表分类：
 *  - 火山方舟那边只保留生图模型（seedream / seededit），大模型一概不显示
 *  - DeepSeek 那边保留对话/视觉模型，deepseek-flash 优先
 */
object ModelCatalog {

    private val ARK_IMAGE_HINTS = listOf("seedream", "seededit")

    fun arkImageModels(ids: List<String>): List<String> = ids
        .filter { id -> ARK_IMAGE_HINTS.any { id.lowercase().contains(it) } }
        .distinct()
        .sorted()

    fun deepseekModels(ids: List<String>): List<String> = ids
        .filterNot { id ->
            val lower = id.lowercase()
            lower.contains("embedding") || lower.contains("rerank") || lower.contains("asr") || lower.contains("tts")
        }
        .distinct()
        .sortedWith(
            compareByDescending<String> { id ->
                val lower = id.lowercase()
                when {
                    lower.contains("flash") -> 2
                    lower.contains("vision") -> 1
                    else -> 0
                }
            }.thenBy { it }
        )

    /** 生图模型是否支持参考图（多图输入）。 */
    fun supportsReferenceImage(model: String): Boolean {
        val lower = model.lowercase()
        return lower.contains("seedream-4") ||
            lower.contains("seedream-5") ||
            lower.contains("seededit") ||
            lower.contains("image-edit")
    }

    val FALLBACK_ARK_IMAGE = listOf("doubao-seedream-4-0-250828")

    val FALLBACK_DEEPSEEK = listOf("deepseek-flash")
}
