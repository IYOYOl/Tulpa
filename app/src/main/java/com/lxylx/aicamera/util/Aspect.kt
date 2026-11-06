package com.lxylx.aicamera.util

/**
 * 拍摄比例相关的统一换算。
 * 相机只有两档：3:4 与 16:9，实际出图方向由设备朝向决定：
 *   竖持 3:4  -> 3:4      横持 3:4  -> 4:3
 *   竖持 16:9 -> 9:16     横持 16:9 -> 16:9
 */
object Aspect {

    fun base(label: String): Pair<Int, Int> = when (label) {
        "16:9" -> 9 to 16
        else -> 3 to 4
    }

    fun target(label: String, landscape: Boolean): Pair<Int, Int> {
        val (w, h) = base(label)
        return if (landscape) h to w else w to h
    }

    fun label(label: String, landscape: Boolean): String {
        val (w, h) = target(label, landscape)
        return "$w:$h"
    }

    fun aspectRatio(label: String, landscape: Boolean): Float {
        val (w, h) = target(label, landscape)
        return w.toFloat() / h.toFloat()
    }

    /** 2K 档位下与比例严格对应的输出分辨率。 */
    fun apiSize(label: String, landscape: Boolean): String {
        val (w, h) = target(label, landscape)
        val ratio = w.toFloat() / h.toFloat()
        return when {
            kotlin.math.abs(ratio - 4f / 3f) < 0.01f -> "2304x1728"
            kotlin.math.abs(ratio - 3f / 4f) < 0.01f -> "1728x2304"
            kotlin.math.abs(ratio - 16f / 9f) < 0.01f -> "2560x1440"
            kotlin.math.abs(ratio - 9f / 16f) < 0.01f -> "1440x2560"
            else -> "2048x2048"
        }
    }

    fun ratioChoices(): List<String> = listOf("3:4", "16:9")
}
