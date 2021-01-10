package com.leo.cameraxlib.extensions

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.MimeTypeMap
import androidx.camera.core.AspectRatio
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import com.leo.cameraxlib.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


const val ANIMATION_SLOW_MILLIS = 200L

/** Combination of all flags required to put activity into immersive mode */
const val FLAGS_FULLSCREEN =
    View.SYSTEM_UI_FLAG_LOW_PROFILE or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
const val PHOTO_EXTENSION = ".jpg"
const val VIDEO_EXTENSION = ".mp4"
const val RATIO_4_3_VALUE = 4.0 / 3.0
const val RATIO_16_9_VALUE = 16.0 / 9.0

const val IMMERSIVE_FLAG_TIMEOUT = 500L

fun Activity.createFile(baseFolder: File, format: String, extension: String) =
    File(
        baseFolder, java.text.SimpleDateFormat(format, java.util.Locale.US)
            .format(System.currentTimeMillis()) + extension
    )

/** Use external media if it is available, our app's file directory otherwise */
fun Activity.getOutputDirectory(context: Context): File {
    val appContext = context.applicationContext
    val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
        File(it, appContext.resources.getString(R.string.app_name)).apply { mkdirs() }
    }
    return if (mediaDir != null && mediaDir.exists())
        mediaDir else appContext.filesDir
}

fun Activity.allPermissionsGranted(permissions: Array<String>): Boolean {
    return permissions.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 *  [androidx.camera.core.ImageAnalysisConfig] requires enum value of
 *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
 *
 *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
 *  of preview ratio to one of the provided values.
 *
 *  @param width - preview width
 *  @param height - preview height
 *  @return suitable aspect ratio
 */
fun Activity.aspectRatio(width: Int, height: Int): Int {
    val previewRatio = max(width, height).toDouble() / min(width, height)
    if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
        return AspectRatio.RATIO_4_3
    }
    return AspectRatio.RATIO_16_9
}

/**
 * 通知系统更新文件
 */
fun Activity.notifyMediaScanner(context: Context, notifyUri: Uri) {
    // 发送更新文件信息广播
    val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
    intent.data = notifyUri
    sendBroadcast(intent)
    // If the folder selected is an external media directory, this is
    // unnecessary but otherwise other apps will not be able to access our
    // images unless we scan them using [MediaScannerConnection]
    val mimeType = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(notifyUri.toFile().extension)

    // Implicit broadcasts will be ignored for devices running API level >= 24
    // so if you only target API level 24+ you can remove this statement
    if (mimeType!!.startsWith("image/", true)
        && Build.VERSION.SDK_INT < Build.VERSION_CODES.N
    ) {
        sendBroadcast(
            Intent(android.hardware.Camera.ACTION_NEW_PICTURE, notifyUri)
        )
    }
    MediaScannerConnection.scanFile(
        context,
        arrayOf(notifyUri.toString()),
        arrayOf(mimeType)
    ) { s: String, uri: Uri ->
    }
}