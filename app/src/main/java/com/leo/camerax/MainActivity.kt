package com.leo.camerax

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import com.leo.cameraxlib.ui.activity.CameraXImgActivity
import com.leo.cameraxlib.ui.activity.WechatCameraXActivity
import com.leo.commonutil.app.FileUtils
import com.leo.commonutil.notify.ToastUtil

class MainActivity : AppCompatActivity() {
    companion object {
        const val IMG_REQUEST = 0
        const val IMG_VIDEO_REQUEST = 1
    }

    private lateinit var mTakePhotoBtn: Button
    private lateinit var mTakeBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mTakePhotoBtn = findViewById(R.id.takePhotoBtn)
        mTakeBtn = findViewById(R.id.takeBtn)
        mTakePhotoBtn.setOnClickListener {
            startActivityForResult(
                Intent(this@MainActivity, CameraXImgActivity::class.java),
                IMG_REQUEST
            )
        }
        mTakeBtn.setOnClickListener {
            startActivityForResult(
                Intent(this@MainActivity, WechatCameraXActivity::class.java),
                IMG_VIDEO_REQUEST
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            data ?: return
            val uri = data.data
            when (requestCode) {
                IMG_REQUEST -> {
                    val path = FileUtils.getPath(this, uri)
                    ToastUtil.show(text = path)
                }
                IMG_VIDEO_REQUEST -> {
                    val path = FileUtils.getPath(this, uri)
                    ToastUtil.show(text = path)
                }
            }
        }
    }
}
