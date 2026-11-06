package com.lxylx.aicamera.data

import android.content.Context
import androidx.core.content.edit

/**
 * 本地配置（双密钥）：
 *  - DeepSeek：负责看图 + 写生图提示词
 *  - 火山方舟：只负责生图（seedream / seededit，支持参考图）
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("ai_camera", Context.MODE_PRIVATE)

    init {
        // 从旧版单密钥配置迁移：旧 api_key 是方舟密钥
        if (sp.getString(KEY_ARK_KEY, "").isNullOrBlank()) {
            val legacyKey = sp.getString("api_key", "").orEmpty()
            if (legacyKey.isNotBlank()) sp.edit { putString(KEY_ARK_KEY, legacyKey) }
        }
        if (sp.getString(KEY_ARK_BASE, "").isNullOrBlank()) {
            val legacyBase = sp.getString("base_url", "").orEmpty()
            if (legacyBase.isNotBlank()) sp.edit { putString(KEY_ARK_BASE, legacyBase) }
        }
    }

    // ------------------------------------------------------------ DeepSeek（视觉）

    var deepseekKey: String
        get() = sp.getString(KEY_DEEPSEEK_KEY, "").orEmpty()
        set(value) = sp.edit { putString(KEY_DEEPSEEK_KEY, value.trim()) }

    var deepseekBaseUrl: String
        get() = sp.getString(KEY_DEEPSEEK_BASE, "").orEmpty().ifBlank { DEFAULT_DEEPSEEK_BASE }
        set(value) = sp.edit { putString(KEY_DEEPSEEK_BASE, value.trim()) }

    var deepseekModel: String
        get() = sp.getString(KEY_DEEPSEEK_MODEL, "").orEmpty().ifBlank { DEFAULT_DEEPSEEK_MODEL }
        set(value) = sp.edit { putString(KEY_DEEPSEEK_MODEL, value.trim()) }

    var cachedDeepseekModels: String
        get() = sp.getString(KEY_CACHED_DEEPSEEK, "").orEmpty()
        set(value) = sp.edit { putString(KEY_CACHED_DEEPSEEK, value) }

    // ------------------------------------------------------------ 火山方舟（生图）

    var arkKey: String
        get() = sp.getString(KEY_ARK_KEY, "").orEmpty()
        set(value) = sp.edit { putString(KEY_ARK_KEY, value.trim()) }

    var arkBaseUrl: String
        get() = sp.getString(KEY_ARK_BASE, "").orEmpty().ifBlank { DEFAULT_ARK_BASE }
        set(value) = sp.edit { putString(KEY_ARK_BASE, value.trim()) }

    var arkImageModel: String
        get() = sp.getString(KEY_ARK_MODEL, "").orEmpty()
        set(value) = sp.edit { putString(KEY_ARK_MODEL, value.trim()) }

    var cachedArkModels: String
        get() = sp.getString(KEY_CACHED_ARK, "").orEmpty()
        set(value) = sp.edit { putString(KEY_CACHED_ARK, value) }

    // ------------------------------------------------------------ 拍摄相关

    var ratioLabel: String
        get() = sp.getString(KEY_RATIO, RATIO_3_4) ?: RATIO_3_4
        set(value) = sp.edit { putString(KEY_RATIO, value) }

    /** 0 = 关，1 = 自动，2 = 开 */
    var flashMode: Int
        get() = sp.getInt(KEY_FLASH, 0)
        set(value) = sp.edit { putInt(KEY_FLASH, value) }

    var referencePath: String
        get() = sp.getString(KEY_REFERENCE, "").orEmpty()
        set(value) = sp.edit { putString(KEY_REFERENCE, value) }

    /** 镜头感：开 = 人物知道被拍，会看镜头/有反应 */
    var cameraAware: Boolean
        get() = sp.getBoolean(KEY_CAMERA_AWARE, false)
        set(value) = sp.edit { putBoolean(KEY_CAMERA_AWARE, value) }

    /** 二次元 ↔ 三次元：0 = 二次元，100 = 真人写实 */
    var dimensionLevel: Int
        get() = sp.getInt(KEY_DIMENSION, 100)
        set(value) = sp.edit { putInt(KEY_DIMENSION, value) }

    var lastPrompt: String
        get() = sp.getString(KEY_LAST_PROMPT, "").orEmpty()
        set(value) = sp.edit { putString(KEY_LAST_PROMPT, value) }

    val hasReference: Boolean
        get() = referencePath.isNotBlank() && java.io.File(referencePath).exists()

    val isConfigured: Boolean
        get() = deepseekKey.isNotBlank() && arkKey.isNotBlank() &&
            deepseekModel.isNotBlank() && arkImageModel.isNotBlank()

    companion object {
        const val DEFAULT_DEEPSEEK_BASE = "https://api.deepseek.com"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-flash"
        const val DEFAULT_ARK_BASE = "https://ark.cn-beijing.volces.com/api/v3"

        const val RATIO_3_4 = "3:4"
        const val RATIO_16_9 = "16:9"

        private const val KEY_DEEPSEEK_KEY = "deepseek_key"
        private const val KEY_DEEPSEEK_BASE = "deepseek_base"
        private const val KEY_DEEPSEEK_MODEL = "deepseek_model"
        private const val KEY_CACHED_DEEPSEEK = "cached_deepseek_models"
        private const val KEY_ARK_KEY = "ark_key"
        private const val KEY_ARK_BASE = "ark_base"
        private const val KEY_ARK_MODEL = "ark_image_model"
        private const val KEY_CACHED_ARK = "cached_ark_models"
        private const val KEY_RATIO = "ratio"
        private const val KEY_FLASH = "flash_mode"
        private const val KEY_REFERENCE = "reference_path"
        private const val KEY_LAST_PROMPT = "last_prompt"
        private const val KEY_CAMERA_AWARE = "camera_aware"
        private const val KEY_DIMENSION = "dimension_level"
    }
}
