package com.leo.cameraxlib.ui.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import com.leo.cameraxlib.R
import com.leo.cameraxlib.databinding.ActivityCameraxBinding
import com.leo.cameraxlib.extensions.*
import com.leo.cameraxlib.ui.enums.CameraState
import com.leo.cameraxlib.ui.view.CameraControllerLayout
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@SuppressLint("RestrictedApi", "CheckResult")
class CameraXActivity : AppCompatActivity(),
    CameraControllerLayout.IControlCallback {

    companion object {
        const val TAG = "CameraXActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    /** Blocking camera operations are performed using this executor */
    private val cameraExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor()
    }
    private val mOutputDirectory: File by lazy {
        getOutputDirectory(this)
    }

    private lateinit var mBinding: ActivityCameraxBinding
    private var mCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var mImageCapture: ImageCapture? = null
    private var mVideoCapture: VideoCapture? = null
    private var mCamera: Camera? = null

    private var mSavedUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_camerax)
        mBinding.run {
            viewFinder.preferredImplementationMode =
                PreviewView.ImplementationMode.TEXTURE_VIEW
            cameraController.load(this@CameraXActivity)
        }

        // Request camera permissions
        if (!allPermissionsGranted(REQUIRED_PERMISSIONS)) {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted(REQUIRED_PERMISSIONS)) {
                bindCameraUseCases()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Wait for the views to be properly laid out
        mBinding.constraintLayout.postDelayed(
            {
                mBinding.constraintLayout.systemUiVisibility = FLAGS_FULLSCREEN
                if (allPermissionsGranted(REQUIRED_PERMISSIONS) && null == mCamera) {
                    bindCameraUseCases()
                }
            },
            IMMERSIVE_FLAG_TIMEOUT
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { mBinding.viewFinder.display.getRealMetrics(it) }
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        val rotation = mBinding.viewFinder.display.rotation
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
                    it.setSurfaceProvider(mBinding.viewFinder.createSurfaceProvider())
                }

            // ImageCapture
            mImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setTargetAspectRatio(screenAspectRatio)
                .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
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
                .setVideoFrameRate(60)
                .setBitRate(3 * 1024 * 1024)
                .build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                // Bind use cases to camera
                mCamera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, mImageCapture, mVideoCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onCancel() {
        mBinding.cameraController.setState(CameraState.PREVIEW)
    }

    override fun onResultOk() {
        setResult(Activity.RESULT_OK, Intent().also { it.data = mSavedUri })
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
                    mOutputDirectory,
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
                        mSavedUri = output.savedUri ?: Uri.fromFile(photoFile)
                        Log.d(TAG, "Photo capture succeeded: $mSavedUri")

                        // Implicit broadcasts will be ignored for devices running API level >= 24
                        // so if you only target API level 24+ you can remove this statement
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            this@CameraXActivity.sendBroadcast(
                                Intent(android.hardware.Camera.ACTION_NEW_PICTURE, mSavedUri)
                            )
                        }

                        notifyMediaScanner(this@CameraXActivity, mSavedUri!!)
                        mBinding.cameraController.setState(CameraState.PICTURE_TAKEN)
                    }
                })

            // We can only change the foreground Drawable using API level 23+ API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mBinding.constraintLayout.postDelayed({
                    mBinding.constraintLayout.foreground = ColorDrawable(Color.WHITE)
                    mBinding.constraintLayout.postDelayed(
                        { mBinding.constraintLayout.foreground = null }, ANIMATION_FAST_MILLIS
                    )
                }, ANIMATION_SLOW_MILLIS)
            }
        }
    }

    override fun onStartRecord() {
        val videoFile =
            createFile(
                mOutputDirectory,
                FILENAME,
                VIDEO_EXTENSION
            )
        mVideoCapture?.startRecording(
            videoFile,
            cameraExecutor,
            object : VideoCapture.OnVideoSavedCallback {
                override fun onVideoSaved(file: File) {
                    mSavedUri = Uri.fromFile(file)
                    notifyMediaScanner(this@CameraXActivity, mSavedUri!!)
                    mBinding.cameraController.setState(CameraState.RECORD_TAKEN)
                }

                override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                    TODO("Not yet implemented")
                }
            })
    }

    override fun onStopRecord() {
        mVideoCapture?.stopRecording()
        mBinding.cameraController.setState(CameraState.RECORD_PROCESS)
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
}