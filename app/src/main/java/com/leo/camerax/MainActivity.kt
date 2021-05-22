package com.leo.camerax

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.leo.camerax.databinding.ActivityMainBinding
import com.leo.cameraxlib.ui.activity.CameraXActivity
import com.leo.cameraxlib.ui.activity.CameraXImgActivity
import com.leo.commonutil.app.FileUtils
import com.leo.commonutil.notify.ToastUtil
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {
    private lateinit var mBinding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        mBinding.mEventListener = EventListener(WeakReference(this))
    }

    val imgRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val result = it.data ?: return@registerForActivityResult
            val path = FileUtils.getPath(this, result.data)
            ToastUtil.show(text = path)
        }

    val imgVideoRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val result = it.data ?: return@registerForActivityResult
            val path = FileUtils.getPath(this, result.data)
            ToastUtil.show(text = path)
        }

    inner class EventListener constructor(private val mActRef: WeakReference<MainActivity>) {
        fun onClick(v: View) {
            val activity = mActRef.get() ?: return
            when (v.id) {
                R.id.takePhotoBtn -> {
                    imgRequest.launch(Intent(activity, CameraXImgActivity::class.java))
                }
                R.id.takeBtn -> {
                    imgVideoRequest.launch(Intent(activity, CameraXActivity::class.java))
                }
            }
        }
    }
}
