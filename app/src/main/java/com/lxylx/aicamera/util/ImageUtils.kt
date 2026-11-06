package com.lxylx.aicamera.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

object ImageUtils {

    /** 解码 JPEG 并按 EXIF 摆正方向；maxSide > 0 时按 2 的幂采样，避免大图 OOM。 */
    fun decode(bytes: ByteArray, maxSide: Int = 0): Bitmap {
        val options = BitmapFactory.Options()
        if (maxSide > 0) {
            options.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            var sample = 1
            val longest = maxOf(options.outWidth, options.outHeight)
            while (longest / sample > maxSide * 2) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalStateException("图片解析失败")
        return applyExifRotation(bytes, bitmap)
    }

    fun applyExifRotation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            ExifInterface.ORIENTATION_TRANSPOSE -> 90f
            ExifInterface.ORIENTATION_TRANSVERSE -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** 居中裁切到指定比例（aspectW : aspectH）。 */
    fun cropToAspect(bitmap: Bitmap, aspectW: Int, aspectH: Int): Bitmap {
        if (aspectW <= 0 || aspectH <= 0) return bitmap
        val targetRatio = aspectW.toFloat() / aspectH.toFloat()
        val currentRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        if (kotlin.math.abs(currentRatio - targetRatio) < 0.01f) return bitmap
        return if (currentRatio > targetRatio) {
            val newWidth = (bitmap.height * targetRatio).toInt().coerceAtLeast(1)
            val x = ((bitmap.width - newWidth) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(bitmap, x, 0, newWidth, bitmap.height)
        } else {
            val newHeight = (bitmap.width / targetRatio).toInt().coerceAtLeast(1)
            val y = ((bitmap.height - newHeight) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(bitmap, 0, y, bitmap.width, newHeight)
        }
    }

    fun scaleToMaxSide(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    fun compressJpeg(bitmap: Bitmap, quality: Int = 90): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    fun readBytes(file: File): ByteArray = file.readBytes()

    fun copyUriToFile(context: Context, uri: Uri, target: File) {
        target.parentFile?.mkdirs()
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取所选图片")
        val bitmap = scaleToMaxSide(decode(raw, 2000), 1600)
        target.writeBytes(compressJpeg(bitmap, 92))
    }

    /** 删除之前由本 App 写进系统相册的那张图（按 MediaStore URI）。 */
    fun deleteFromGallery(context: Context, uriString: String): Boolean {
        if (uriString.isBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            val rows = context.contentResolver.delete(uri, null, null)
            rows > 0
        } catch (e: Exception) {
            false
        }
    }

    fun saveToGallery(context: Context, jpeg: ByteArray, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Tulpa")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法写入系统相册")
        resolver.openOutputStream(uri)?.use { it.write(jpeg) }
            ?: throw IllegalStateException("无法写入系统相册")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }
}
