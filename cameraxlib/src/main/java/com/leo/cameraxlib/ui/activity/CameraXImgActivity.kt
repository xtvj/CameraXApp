package com.leo.cameraxlib.ui.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import com.leo.cameraxlib.R
import com.leo.cameraxlib.databinding.ActivityCameraxImgBinding
import com.leo.cameraxlib.extensions.*
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@SuppressLint("RestrictedApi")
class CameraXImgActivity : AppCompatActivity() {

    companion object {
        const val TAG = "CameraXImgActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var mBinding: ActivityCameraxImgBinding
    private val mOutputDirectory: File by lazy {
        getOutputDirectory(this)
    }

    private var mDisplayId: Int = -1
    private var mCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var mPreview: Preview? = null
    private var mImageCapture: ImageCapture? = null
    private var mCamera: Camera? = null

    private val displayManager by lazy {
        getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** Blocking camera operations are performed using this executor */
    private val cameraExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor()
    }

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = mBinding.cameraContainer.let { view ->
            if (displayId == this@CameraXImgActivity.mDisplayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                mImageCapture?.targetRotation = view.display.rotation
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_camerax_img)
        mBinding.run {
            mEventListener = EventListener(WeakReference(this@CameraXImgActivity))
            viewFinder.implementationMode =
                PreviewView.ImplementationMode.COMPATIBLE
        }

        // Request camera permissions
        if (!allPermissionsGranted(REQUIRED_PERMISSIONS)) {
            val permissionRequest =
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    if (it.values.all { value -> value }) {
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
            permissionRequest.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // Every time the orientation of device changes, update rotation for use cases
        displayManager.registerDisplayListener(displayListener, null)
        // Wait for the views to be properly laid out
        mBinding.cameraContainer.postDelayed(
            {
                mBinding.cameraContainer.systemUiVisibility = FLAGS_FULLSCREEN
            },
            IMMERSIVE_FLAG_TIMEOUT
        )
        mBinding.viewFinder.post {
            // Keep track of the display in which this view is attached
            mDisplayId = mBinding.viewFinder.display.displayId
            if (allPermissionsGranted(REQUIRED_PERMISSIONS) && null == mCamera) {
                bindCameraUseCases()
            }
        }
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

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { mBinding.viewFinder.display.getRealMetrics(it) }
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        val rotation = mBinding.viewFinder.display.rotation
        // Bind the CameraProvider to the LifeCycleOwner
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this@CameraXImgActivity)
        cameraProviderFuture.addListener({
            // CameraProvider
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            // Preview
            mPreview = Preview.Builder()
                // We request aspect ratio but no resolution
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation
                .setTargetRotation(rotation)
                .build()

            // Attach the viewfinder's surface provider to preview use case
            mPreview?.setSurfaceProvider(mBinding.viewFinder.surfaceProvider)
            mImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                // 设置固定比例 16:9 4:3
//                .setTargetAspectRatio(screenAspectRatio)
                // 设置自定义尺寸 -> 可设置全屏
                .setTargetResolution(Size(metrics.widthPixels, metrics.heightPixels))
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()

            // Must unbind the use-cases before rebinding them
            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                mCamera = cameraProvider.bindToLifecycle(
                    this, mCameraSelector, mPreview, mImageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    /** When key down event is triggered, relay it via local broadcast so fragments can handle it */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                mBinding.cameraCaptureButton.simulateClick()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    inner class EventListener constructor(private val mActRef: WeakReference<CameraXImgActivity>) {
        fun onClick(v: View) {
            val cameraXImgActivity = mActRef.get() ?: return
            when (v.id) {
                R.id.camera_switch_button -> {
                    cameraXImgActivity.switchCameraSelector()
                }
                R.id.camera_capture_button -> {
                    cameraXImgActivity.takePhoto()
                }
                R.id.camera_back_button -> {
                    cameraXImgActivity.onBackPressed()
                }
            }
        }
    }

    fun switchCameraSelector() {
        mCameraSelector = if (CameraSelector.DEFAULT_FRONT_CAMERA == mCameraSelector) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        // Re-bind use cases to update selected camera
        bindCameraUseCases()
    }

    fun takePhoto() {
        mImageCapture?.run {
            // Create output file to hold the image
            val photoFile =
                createFile(
                    mOutputDirectory,
                    FILENAME,
                    PHOTO_EXTENSION
                )

            // Setup image capture metadata
            val metadata = Metadata().apply {
                // Mirror image when using the front camera
                isReversedHorizontal =
                    mCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
            }

            // Create output options object which contains file + metadata
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                .setMetadata(metadata)
                .build()

            // Setup image capture listener which is triggered after photo has been taken
            takePicture(
                outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedFileUri = output.savedUri ?: photoFile.fileUri()
                        Log.d(TAG, "Photo capture succeeded: $savedFileUri")

//                        savedFileUri?.let {
//                            notifyMediaScanner(this@CameraXImgActivity, savedFileUri)
//                        }

                        setResult(Activity.RESULT_OK, Intent().also { it.data = savedFileUri })
                        finish()
                    }
                })

            // We can only change the foreground Drawable using API level 23+ API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Display flash animation to indicate that photo was captured
                mBinding.cameraContainer.postDelayed({
                    mBinding.cameraContainer.foreground = ColorDrawable(Color.WHITE)
                    mBinding.cameraContainer.postDelayed(
                        { mBinding.cameraContainer.foreground = null },
                        ANIMATION_FAST_MILLIS
                    )
                }, ANIMATION_SLOW_MILLIS)
            }
        }
    }
}
