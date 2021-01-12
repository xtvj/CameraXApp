package com.leo.cameraxlib.extensions

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import java.io.File
import java.io.IOException
import java.io.InputStream


fun File.contentUri(context: Context): Uri {
    return if (Build.VERSION.SDK_INT >= 24) {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", this)
    } else {
        Uri.fromFile(this)
    }
}

fun File.fileUri(): Uri {
    return Uri.fromFile(this)
}

/**
 * 读取一个缩放后的图片，限定图片大小，避免OOM
 * @param maxWidth  最大允许宽度
 * @param maxHeight 最大允许高度
 * @return  返回一个缩放后的Bitmap，失败则返回null
 */
fun File.bitmap(context: Context, maxWidth: Int, maxHeight: Int): Bitmap? {
    val mimeType = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(this.extension) ?: return null
    val fileUri = this.fileUri()
    var bitmap: Bitmap? = null
    if (mimeType.startsWith("image/", true)) {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true //只读取图片尺寸
        resolveUri(context, fileUri, options)
        // 计算实际缩放比例
        var scale = 1
        for (i in 0 until Int.MAX_VALUE) {
            if (options.outWidth / scale > maxWidth &&
                options.outWidth / scale > maxWidth * 1.4 ||
                options.outHeight / scale > maxHeight &&
                options.outHeight / scale > maxHeight * 1.4
            ) {
                scale++
            } else {
                break
            }
        }
        options.inSampleSize = scale
        options.inJustDecodeBounds = false // 读取图片内容
        options.inPreferredConfig = Bitmap.Config.RGB_565 // 根据情况进行修改
        try {
            bitmap = resolveUriForBitmap(context, fileUri, options)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    } else if (mimeType.startsWith("video/", true)) {
        val media = MediaMetadataRetriever()
        media.setDataSource(context, fileUri)
        bitmap = media.frameAtTime
    }
    return bitmap
}

private fun resolveUri(context: Context, uri: Uri?, options: BitmapFactory.Options) {
    if (uri == null) {
        return
    }
    val scheme = uri.scheme
    if ((ContentResolver.SCHEME_CONTENT == scheme) || (ContentResolver.SCHEME_FILE == scheme)) {
        var stream: InputStream? = null
        try {
            stream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(stream, null, options)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (stream != null) {
                try {
                    stream.close()
                } catch (e: IOException) {
                }
            }
        }
    }
}

private fun resolveUriForBitmap(
    context: Context,
    uri: Uri?,
    options: BitmapFactory.Options
): Bitmap? {
    if (uri == null) {
        return null
    }
    var bitmap: Bitmap? = null
    val scheme = uri.scheme
    if ((ContentResolver.SCHEME_CONTENT == scheme) || (ContentResolver.SCHEME_FILE == scheme)) {
        var stream: InputStream? = null
        try {
            stream = context.contentResolver.openInputStream(uri)
            bitmap = BitmapFactory.decodeStream(stream, null, options)

            try {
                val exif = ExifInterface(uri.toFile().absolutePath)
                // 读取图片中相机方向信息
                val ori = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
                val degree = when (ori) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (degree != 0f) {
                    // 旋转图片
                    val m = Matrix()
                    m.postRotate(degree)
                    bitmap = Bitmap.createBitmap(
                        bitmap!!, 0, 0, bitmap.width,
                        bitmap.height, m, true
                    )
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } catch (e: Exception) {
        } finally {
            if (stream != null) {
                try {
                    stream.close()
                } catch (e: IOException) {
                }
            }
        }
    }
    return bitmap
}