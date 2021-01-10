package com.leo.cameraxlib.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import androidx.databinding.DataBindingUtil
import com.leo.cameraxlib.R
import com.leo.cameraxlib.databinding.WechatCameraUiContainerBinding
import com.leo.cameraxlib.ui.enums.CameraState

@SuppressLint("ClickableViewAccessibility")
class CameraControllerLayout : FrameLayout {
    private val mMainHandler: Handler = Handler(Looper.getMainLooper())
    private var mOnControlCallback: IControlCallback? = null

    /**
     * 用户操作
     */
    private var isPressRecord = false
    private var mState = CameraState.PREVIEW

    private val mBinging: WechatCameraUiContainerBinding

    /**
     * 捕获按钮动画
     */
    private val leftAction: TranslateAnimation = TranslateAnimation(
        Animation.RELATIVE_TO_SELF, 1.5f, Animation.RELATIVE_TO_SELF,
        0f, Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f
    )
    private val rightAction: TranslateAnimation = TranslateAnimation(
        Animation.RELATIVE_TO_SELF, -1.5f, Animation.RELATIVE_TO_SELF,
        0f, Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f
    )

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    init {
        leftAction.duration = 200
        rightAction.duration = 200

        mBinging = DataBindingUtil.inflate(
            LayoutInflater.from(context),
            R.layout.wechat_camera_ui_container,
            this, true
        )
        mBinging.mEventListener = EventListener()

        mBinging.mBtnRecord.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isPressRecord = true
                    mMainHandler.postDelayed(launchRunnable, 500)
                }
                MotionEvent.ACTION_MOVE -> {
//                    Log.e(TAG,"ACTION_MOVE Y: ${event.y}")
//                    if (event.y < 0 && state.get() == STATE_RECORDING){
//                        // 录像中滑动变焦
//                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    // 录像到最大时间而提前结束也会触发， 防止重复调用
                    if (isPressRecord) {
                        isPressRecord = false
                        unPressRecord()
                    }
                }
            }
            true
        }
        mBinging.mBtnRecord.setOnFinishCallBack(object : CircleProgressButton.OnFinishCallback {
            override fun progressStart() {
                mMainHandler.post(startRecordRunnable)
            }

            override fun progressFinish() {
                // 录像到最大时间 直接结束录像
                isPressRecord = false
                unPressRecord()
            }
        })
    }

    fun setState(@CameraState state: Int) {
        this.mState = state
        mMainHandler.post {
            if (state == CameraState.PICTURE_TAKEN
                || state == CameraState.RECORD_PROCESS
            ) {
                showBtnLayout()
            }
            mBinging.run {
                mTvRecordTip.visibility =
                    if (state == CameraState.PREVIEW) View.VISIBLE else View.GONE
                mBtnRecord.visibility =
                    if (state == CameraState.PREVIEW
                        || state == CameraState.RECORDING
                    ) View.VISIBLE else View.GONE
                mBack.visibility =
                    if (state == CameraState.PREVIEW) View.VISIBLE else View.GONE
                contrainerLl.visibility =
                    if (state == CameraState.PICTURE_TAKEN
                        || state == CameraState.RECORD_PROCESS
                        || state == CameraState.RECORD_TAKEN
                    ) View.VISIBLE else View.GONE
                cameraSwitchButton.visibility =
                    if (state == CameraState.PICTURE_TAKEN
                        || state == CameraState.RECORD_PROCESS
                        || state == CameraState.RECORD_TAKEN
                    ) View.GONE else View.VISIBLE
            }
        }
    }

    fun load(callback: IControlCallback) {
        this.mOnControlCallback = callback
        setState(CameraState.PREVIEW)
    }

    private fun showBtnLayout() {
        mBinging.run {
            mBtnCancel.startAnimation(leftAction)
            mBtnOK.startAnimation(rightAction)
        }
    }

    private fun unPressRecord() {
        mMainHandler.removeCallbacks(launchRunnable)
        when (mState) {
            CameraState.PREVIEW -> {
                // 手指松开还未开始录像 进行拍照
                mOnControlCallback?.onCapture()
            }
            CameraState.RECORDING -> {
                // 正在录像 停止录像
                mBinging.mBtnRecord.stop()
                mOnControlCallback?.onStopRecord()
                setState(CameraState.RECORD_PROCESS)
            }
        }
    }

    // 操作回调
    private var launchRunnable = Runnable {
        // 如果还处于按下状态 表示要录像
        if (isPressRecord) {
            // 拍摄开始启动
            mBinging.mBtnRecord.start()
        }
    }

    private var startRecordRunnable = Runnable {
        if (isPressRecord) {
            mOnControlCallback?.onStartRecord()
            setState(CameraState.RECORDING)
        }
    }

    inner class EventListener {
        fun onClick(view: View) {
            when (view.id) {
                R.id.camera_switch_button -> {
                    mOnControlCallback?.onSwitchCamera()
                }
                R.id.mBack -> {
                    mOnControlCallback?.onBack()
                }
                R.id.mBtnCancel -> {
                    mOnControlCallback?.onCancel()
                }
                R.id.mBtnOK -> {
                    mOnControlCallback?.onResultOk()
                }
            }
        }
    }

    interface IControlCallback {
        fun onCancel()
        fun onResultOk()
        fun onBack()
        fun onCapture()
        fun onStartRecord()
        fun onStopRecord()
        fun onSwitchCamera()
    }
}