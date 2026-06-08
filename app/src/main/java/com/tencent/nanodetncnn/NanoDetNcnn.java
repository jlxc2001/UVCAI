package com.tencent.nanodetncnn;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.view.Surface;

public class NanoDetNcnn {
    public native boolean loadModel(AssetManager mgr, int modelid, int cpugpu);

    // 原项目相机接口保留，避免其他代码引用时编译报错。
    public native boolean openCamera(int facing);
    public native boolean closeCamera();
    public native boolean setOutputWindow(Surface surface);

    // 新增：对屏幕截帧 Bitmap 做 NanoDet 识别。
    // 返回格式：float[]{srcW, srcH, label, prob, x, y, w, h, label, prob, x, y, w, h ...}
    public native float[] detectBitmap(Bitmap bitmap);

    static {
        System.loadLibrary("nanodetncnn");
    }
}
