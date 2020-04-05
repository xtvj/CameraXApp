package com.leo.cameraxlib.ui.view

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.leo.cameraxlib.R
import com.leo.cameraxlib.ui.enums.CameraState

class WechatCameraUiContainer {
    private lateinit var mCameraSwitchButton: ImageView
    private lateinit var mBtnRecord: CircleProgressButton
    private lateinit var mTvRecordTip: TextView
    private lateinit var mContrainerLl: LinearLayout
    private lateinit var mBack: ImageView
    private lateinit var mBtnCancel: ImageView
    private lateinit var mBtnOK: ImageView

    private var mOnControlCallback: OnControlCallback? = null
    private var backgroundHandler: Handler = Handler(Looper.getMainLooper())
    /**
     * 用户操作
     */
    private var isPressRecord = false
    // 是否允许拍照
    private var mIsAllowCapture = true
    // 是否允许录像
    private var mIsAllowRecord = false

    private var mState = CameraState.PREVIEW

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

    init {
        leftAction.duration = 200
        rightAction.duration = 200
    }

    fun initView(view: View) {
        mCameraSwitchButton = view.findViewById(R.id.camera_switch_button)
        mCameraSwitchButton.setOnClickListener {
            mOnControlCallback?.onSwitchCamera()
        }
        mTvRecordTip = view.findViewById(R.id.mTvRecordTip)
        mContrainerLl = view.findViewById(R.id.contrainerLl)
        mBack = view.findViewById(R.id.mBack)
        mBack.setOnClickListener {
            mOnControlCallback?.onBack()
        }
        mBtnCancel = view.findViewById(R.id.mBtnCancel)
        mBtnCancel.setOnClickListener {
            mOnControlCallback?.onCancal()
        }
        mBtnOK = view.findViewById(R.id.mBtnOK)
        mBtnOK.setOnClickListener {
            mOnControlCallback?.onResultOk()
        }
        mBtnRecord = view.findViewById(R.id.mBtnRecord)
        mBtnRecord.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.e("LEOTEST", "MotionEvent.ACTION_DOWN")
                    isPressRecord = true
                    //
                    backgroundHandler?.postDelayed(launchRunnable, 500)
                }
                MotionEvent.ACTION_MOVE -> {
//                    Log.e(TAG,"ACTION_MOVE Y: ${event.y}")
//                    if (event.y < 0 && state.get() == STATE_RECORDING){
//                        //录像中滑动变焦
//                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    //录像到最大时间而提前结束也会触发， 防止重复调用
                    if (isPressRecord) {
                        isPressRecord = false
                        unPressRecord()
                    }
                }
            }
            true
        }
        mBtnRecord.setOnFinishCallBack(object : CircleProgressButton.OnFinishCallback {
            override fun progressStart() {
                backgroundHandler?.postDelayed(startRecordRunnable, 0L)
            }

            override fun progressFinish() {
                //录像到最大时间 直接结束录像
                isPressRecord = false
                unPressRecord()
            }
        })
    }

    fun setState(@CameraState state: Int) {
        this.mState = state
        if (state == CameraState.PICTURE_TAKEN
            || state == CameraState.RECORD_PROCESS
        ) {
            showBtnLayout()
        }
        backgroundHandler.post {
            mTvRecordTip.visibility = if (state == CameraState.PREVIEW) View.VISIBLE else View.GONE
            mBtnRecord.visibility =
                if (state == CameraState.PREVIEW
                    || state == CameraState.RECORDING
                ) View.VISIBLE else View.GONE
            mBack.visibility = if (state == CameraState.PREVIEW) View.VISIBLE else View.GONE
            mContrainerLl.visibility =
                if (state == CameraState.PICTURE_TAKEN
                    || state == CameraState.RECORD_PROCESS
                    || state == CameraState.RECORD_TAKEN
                ) View.VISIBLE else View.GONE
        }
    }

    fun init(view: View, callback: OnControlCallback) {
        this.mOnControlCallback = callback
        initView(view)
        setState(CameraState.PREVIEW)
    }

    private fun showBtnLayout() {
        mBtnCancel.startAnimation(leftAction)
        mBtnOK.startAnimation(rightAction)
    }

    private fun unPressRecord() {
        backgroundHandler?.removeCallbacks(launchRunnable)
        when (mState) {
            CameraState.PREVIEW -> {
                if (mIsAllowCapture) {
                    //手指松开还未开始录像 进行拍照
                    mOnControlCallback?.onCapture()
                }
            }
            CameraState.RECORDING -> {
                //正在录像 停止录像
                mBtnRecord.stop()
                mOnControlCallback?.onStopRecord()
                setState(CameraState.RECORD_PROCESS)
            }
        }
    }

    //操作回调
    private var launchRunnable = Runnable {
        //如果还处于按下状态 表示要录像
        if (isPressRecord && mIsAllowRecord) {
            //拍摄开始启动
            Log.e("LEOTEST", "launchRunnable")
            mBtnRecord.start()
        }
    }
    private var startRecordRunnable = Runnable {
        if (isPressRecord && mIsAllowRecord) {
            Log.e("LEOTEST", "startRecordRunnable")
            mOnControlCallback?.onStartRecord()
            setState(CameraState.RECORDING)
        }
    }

    interface OnControlCallback {
        fun onCancal()
        fun onResultOk()
        fun onBack()
        fun onCapture()
        fun onStartRecord()
        fun onStopRecord()
        fun onSwitchCamera()
    }
}