package com.leo.cameraxlib.ui.enums;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@IntDef({CameraState.PREVIEW,
        CameraState.PICTURE_TAKEN,
        CameraState.RECORDING,
        CameraState.RECORD_PROCESS,
        CameraState.RECORD_TAKEN})
@Retention(RetentionPolicy.SOURCE)
public @interface CameraState {
    // 预览相机
    int PREVIEW = 0;
    // 拍照捕获图片
    int PICTURE_TAKEN = 1;
    // 正在录像
    int RECORDING = 2;
    // 处理录像
    int RECORD_PROCESS = 3;
    // 捕获录像
    int RECORD_TAKEN = 4;
}
