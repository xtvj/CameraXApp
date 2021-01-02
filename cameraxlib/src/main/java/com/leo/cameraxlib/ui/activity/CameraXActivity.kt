package com.leo.cameraxlib.ui.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Camera
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import com.leo.cameraxlib.R
import com.leo.cameraxlib.ui.enums.CameraState
import com.leo.cameraxlib.ui.view.CameraControllerLayout
import com.leo.cameraxlib.utils.*
import com.tbruyelle.rxpermissions2.RxPermissions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@SuppressLint("RestrictedApi", "CheckResult")
class CameraXActivity : AppCompatActivity(),
    CameraControllerLayout.IControlCallback,
    VideoCapture.OnVideoSavedCallback {

    private lateinit var constraintLayout: ConstraintLayout
    private lateinit var viewFinder: PreviewView
    private lateinit var cameraController: CameraControllerLayout

    private lateinit var outputDirectory: File
    private lateinit var permissionHelp: RxPermissions

    private var displayId: Int = -1
    private var mCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var mImageCapture: ImageCapture? = null
    private var mVideoCapture: VideoCapture? = null

    var savedUri: Uri? = null;

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camerax)
        constraintLayout = findViewById(R.id.constraintLayout)
        viewFinder = findViewById(R.id.view_finder)
        cameraController = findViewById(R.id.cameraController)

        cameraController.load(this)
        viewFinder.preferredImplementationMode =
            PreviewView.ImplementationMode.TEXTURE_VIEW
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
        permissionHelp.request(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
            .subscribe({ aBoolean ->
                if (aBoolean!!) {
                    constraintLayout.postDelayed(
                        {
                            constraintLayout.systemUiVisibility = FLAGS_FULLSCREEN
                        },
                        IMMERSIVE_FLAG_TIMEOUT
                    )
                    viewFinder.post {
                        // Keep track of the display in which this view is attached
                        displayId = viewFinder.display.displayId
                        // Bind use cases
                        bindCameraUseCases()
                    }
                } else {
                    Toast.makeText(
                        this@CameraXActivity,
                        "lack of camera permission",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }, { throwable ->
                Log.e(TAG, throwable.toString())
                Toast.makeText(
                    this@CameraXActivity,
                    throwable.message,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            })
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        val rotation = viewFinder.display.rotation
        // Bind the CameraProvider to the LifeCycleOwner
        val cameraSelector = mCameraSelector

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.createSurfaceProvider())
                }

            // ImageCapture
            mImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()

            // VideoCapture
            mVideoCapture = VideoCapture.Builder()
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                //视频帧率  越高视频体积越大
                .setVideoFrameRate(30)
                .setBitRate(3 * 1024 * 1024)
                .build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, mImageCapture, mVideoCapture
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

    companion object {
        const val TAG = "CameraXActivity"
    }

    override fun onCancel() {

    }

    override fun onResultOk() {
        setResult(Activity.RESULT_OK, Intent().also { it.data = savedUri })
        finish()
    }

    override fun onBack() {
        onBackPressed()
    }

    override fun onCapture() {
        // Get a stable reference of the modifiable image capture use case
        mImageCapture?.let { imageCapture ->

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
                isReversedHorizontal = mCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
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
                        savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                        Log.d(TAG, "Photo capture succeeded: $savedUri")

                        // Implicit broadcasts will be ignored for devices running API level >= 24
                        // so if you only target API level 24+ you can remove this statement
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            this@CameraXActivity.sendBroadcast(
                                Intent(Camera.ACTION_NEW_PICTURE, savedUri)
                            )
                        }

                        notify(savedUri!!)
                        cameraController.setState(CameraState.PICTURE_TAKEN)
                    }
                })

            // We can only change the foreground Drawable using API level 23+ API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                constraintLayout.postDelayed({
                    constraintLayout.foreground = ColorDrawable(Color.WHITE)
                    constraintLayout.postDelayed(
                        { constraintLayout.foreground = null }, ANIMATION_FAST_MILLIS
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
        mVideoCapture?.startRecording(videoFile, cameraExecutor, this)
    }

    override fun onStopRecord() {
        mVideoCapture?.stopRecording()
        cameraController.setState(CameraState.RECORD_PROCESS)
    }

    override fun onSwitchCamera() {
        mCameraSelector = if (CameraSelector.DEFAULT_FRONT_CAMERA == mCameraSelector) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        // Re-bind use cases to update selected camera
        bindCameraUseCases()
    }

    /** Called when the video has been successfully saved.  */
    override fun onVideoSaved(file: File) {
        savedUri = Uri.fromFile(file)
        notify(savedUri!!)
        cameraController.setState(CameraState.RECORD_TAKEN)
    }

    /** Called when an error occurs while attempting to save the video.  */
    override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
    }

    fun notify(notifyUri: Uri) {
        // 发送更新文件信息广播
        val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        intent.data = notifyUri
        sendBroadcast(intent)
        // If the folder selected is an external media directory, this is
        // unnecessary but otherwise other apps will not be able to access our
        // images unless we scan them using [MediaScannerConnection]
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(notifyUri.toFile().extension)
        MediaScannerConnection.scanFile(
            this@CameraXActivity,
            arrayOf(notifyUri.toString()),
            arrayOf(mimeType)
        ) { _, uri ->
            Log.d(TAG, "capture scanned into media store: $uri")
        }
    }
}