package com.lxylx.aicamera.data

import android.util.Log
import org.json.JSONArray
import java.io.File

/** 管理 App 自己拍的/生成的图片文件与索引（不碰系统相册）。 */
class PhotoStore(context: android.content.Context) {

    private val dir: File = File(context.filesDir, "photos").apply { mkdirs() }
    private val indexFile: File = File(dir, "index.json")

    fun sceneFile(id: String): File = File(dir, "${id}_scene.jpg")

    fun resultFile(id: String): File = File(dir, "${id}_result.jpg")

    fun load(): List<PhotoItem> = try {
        if (!indexFile.exists()) {
            emptyList()
        } else {
            val array = JSONArray(indexFile.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { PhotoItem.fromJson(it) }
            }.filter { File(it.scenePath).exists() || it.resultPath != null }
        }
    } catch (e: Exception) {
        Log.e(TAG, "读取图片索引失败", e)
        emptyList()
    }

    fun save(items: List<PhotoItem>) {
        try {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            indexFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "写入图片索引失败", e)
        }
    }

    fun delete(item: PhotoItem) {
        sceneFile(item.id).delete()
        resultFile(item.id).delete()
    }

    companion object {
        private const val TAG = "PhotoStore"
    }
}
