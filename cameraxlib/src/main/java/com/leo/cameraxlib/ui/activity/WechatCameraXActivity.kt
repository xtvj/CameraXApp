package com.leo.cameraxlib.ui.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Camera
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.impl.VideoCaptureConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import com.leo.cameraxlib.R
import com.leo.cameraxlib.ui.enums.CameraState
import com.leo.cameraxlib.ui.view.WechatCameraUiContainer
import com.leo.cameraxlib.utils.*
import com.tbruyelle.rxpermissions2.RxPermissions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class WechatCameraXActivity : AppCompatActivity(), WechatCameraUiContainer.OnControlCallback,
    VideoCapture.OnVideoSavedCallback {
    private lateinit var container: ConstraintLayout
    private lateinit var viewFinder: PreviewView
    private lateinit var outputDirectory: File
    private lateinit var permissionHelp: RxPermissions

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private var uiContainer: WechatCameraUiContainer? = null

    private val displayManager by lazy {
        getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = container.let { view ->
            if (displayId == this@WechatCameraXActivity.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camerax)
        container = findViewById(R.id.camera_container)
        viewFinder = findViewById(R.id.view_finder)
        viewFinder.preferredImplementationMode = PreviewView.ImplementationMode.TEXTURE_VIEW
        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()
        // Determine the output directory
        outputDirectory =
            getOutputDirectory(
                this
            )
        permissionHelp = RxPermissionsHelp.newInstance(this)
    }

    override fun onResume() {
        super.onResume()
        // Wait for the views to be properly laid out
        permissionHelp.request(Manifest.permission.CAMERA)
            .subscribe({ aBoolean ->
                if (aBoolean!!) {
                    container.postDelayed(
                        {
                            container.systemUiVisibility = FLAGS_FULLSCREEN
                        },
                        IMMERSIVE_FLAG_TIMEOUT
                    )
                    // Every time the orientation of device changes, update rotation for use cases
                    displayManager.registerDisplayListener(displayListener, null)
                    viewFinder.post {
                        // Keep track of the display in which this view is attached
                        displayId = viewFinder.display.displayId
                        // Build UI controls
                        updateCameraUi()
                        // Bind use cases
                        bindCameraUseCases()
                    }
                } else {
                    Toast.makeText(
                        this@WechatCameraXActivity,
                        "lack of camera permission",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }, { throwable ->
                Log.e(TAG, throwable.toString())
                Toast.makeText(
                    this@WechatCameraXActivity,
                    throwable.message,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            })
    }

    override fun onPause() {
        super.onPause()
        // Unregister the broadcast receivers and listeners
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateCameraUi()
    }

    /** Declare and bind preview, capture and analysis use cases */
    @SuppressLint("RestrictedApi")
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = viewFinder.display.rotation

        // Bind the CameraProvider to the LifeCycleOwner
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this@WechatCameraXActivity)
        cameraProviderFuture.addListener(Runnable {

            // CameraProvider
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            // Preview
            preview = Preview.Builder()
                // We request aspect ratio but no resolution
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation
                .setTargetRotation(rotation)
                .build()

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(viewFinder.createSurfaceProvider(camera?.cameraInfo))

            // ImageCapture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()

            videoCapture = VideoCaptureConfig.Builder()
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .setVideoFrameRate(30)
                .build()

            // Must unbind the use-cases before rebinding them
            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
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
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    private fun updateCameraUi() {

        // Remove previous UI if any
        container.findViewById<ConstraintLayout>(R.id.wechat_camera_ui_container)?.let {
            container.removeView(it)
        }
        val view =
            LayoutInflater.from(this).inflate(
                R.layout.wechat_camera_ui_container,
                container
            )
        uiContainer = WechatCameraUiContainer()
        uiContainer!!.init(view, callback = this)
    }

    companion object {
        const val TAG = "CameraXActivity"
    }

    override fun onCancal() {

    }

    override fun onResultOk() {
//        setResult(Activity.RESULT_OK, Intent().also { it.data = savedUri })
//        finish()
    }

    override fun onBack() {
        onBackPressed()
    }

    override fun onCapture() {
        // Get a stable reference of the modifiable image capture use case
        imageCapture?.let { imageCapture ->

            // Create output file to hold the image
            val photoFile =
                createFile(
                    outputDirectory,
                    FILENAME,
                    PHOTO_EXTENSION
                )

            // Setup image capture metadata
            val metadata = ImageCapture.Metadata().apply {

                // Mirror image when using the front camera
                isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
            }

            // Create output options object which contains file + metadata
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                .setMetadata(metadata)
                .build()

            // Setup image capture listener which is triggered after photo has been taken
            imageCapture.takePicture(
                outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    @SuppressLint("VisibleForTests")
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                        Log.d(TAG, "Photo capture succeeded: $savedUri")

                        // Implicit broadcasts will be ignored for devices running API level >= 24
                        // so if you only target API level 24+ you can remove this statement
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            this@WechatCameraXActivity.sendBroadcast(
                                Intent(Camera.ACTION_NEW_PICTURE, savedUri)
                            )
                        }
                        // 发送更新文件信息广播
                        val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                        intent.data = savedUri
                        sendBroadcast(intent)
                        // If the folder selected is an external media directory, this is
                        // unnecessary but otherwise other apps will not be able to access our
                        // images unless we scan them using [MediaScannerConnection]
                        val mimeType = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(savedUri.toFile().extension)
                        MediaScannerConnection.scanFile(
                            this@WechatCameraXActivity,
                            arrayOf(savedUri.toString()),
                            arrayOf(mimeType)
                        ) { _, uri ->
                            Log.d(TAG, "Image capture scanned into media store: $uri")
                        }

                        uiContainer?.setState(CameraState.PICTURE_TAKEN)
                    }
                })

            // We can only change the foreground Drawable using API level 23+ API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                container.postDelayed({
                    container.foreground = ColorDrawable(Color.WHITE)
                    container.postDelayed(
                        { container.foreground = null }, ANIMATION_FAST_MILLIS
                    )
                }, ANIMATION_SLOW_MILLIS)
            }
        }
    }

    override fun onStartRecord() {
        val videoFile =
            createFile(
                outputDirectory,
                FILENAME,
                VIDEO_EXTENSION
            )
        videoCapture?.startRecording(videoFile, cameraExecutor, this)
    }

    override fun onStopRecord() {
        videoCapture?.stopRecording()
        uiContainer?.setState(CameraState.RECORD_PROCESS)
    }

    override fun onSwitchCamera() {
        lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        // Re-bind use cases to update selected camera
        bindCameraUseCases()
    }

    /** Called when the video has been successfully saved.  */
    override fun onVideoSaved(file: File) {
        uiContainer?.setState(CameraState.RECORD_TAKEN)
    }

    /** Called when an error occurs while attempting to save the video.  */
    override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
    }
}