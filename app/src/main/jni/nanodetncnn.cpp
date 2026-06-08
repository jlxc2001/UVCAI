// Minimal NanoDet JNI bridge for UVCAI screen-frame detection.

#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <vector>

#include <platform.h>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include "nanodet.h"

static NanoDet* g_nanodet = 0;
static ncnn::Mutex lock;

static jfloatArray make_empty_result(JNIEnv* env)
{
    return env->NewFloatArray(0);
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "UVCAI", "JNI_OnLoad");
    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "UVCAI", "JNI_OnUnload");

    ncnn::MutexLockGuard g(lock);
    delete g_nanodet;
    g_nanodet = 0;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_nanodetncnn_NanoDetNcnn_loadModel(
        JNIEnv* env, jobject thiz, jobject assetManager, jint modelid, jint cpugpu)
{
    if (modelid < 0 || modelid > 6 || cpugpu < 0 || cpugpu > 1)
        return JNI_FALSE;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr)
        return JNI_FALSE;

    const char* modeltypes[] =
    {
        "m",
        "m-416",
        "g",
        "ELite0_320",
        "ELite1_416",
        "ELite2_512",
        "RepVGG-A0_416"
    };

    const int target_sizes[] =
    {
        320,
        416,
        416,
        320,
        416,
        512,
        416
    };

    const float mean_vals[][3] =
    {
        {103.53f, 116.28f, 123.675f},
        {103.53f, 116.28f, 123.675f},
        {103.53f, 116.28f, 123.675f},
        {127.f, 127.f, 127.f},
        {127.f, 127.f, 127.f},
        {127.f, 127.f, 127.f},
        {103.53f, 116.28f, 123.675f}
    };

    const float norm_vals[][3] =
    {
        {1.f / 57.375f, 1.f / 57.12f, 1.f / 58.395f},
        {1.f / 57.375f, 1.f / 57.12f, 1.f / 58.395f},
        {1.f / 57.375f, 1.f / 57.12f, 1.f / 58.395f},
        {1.f / 128.f, 1.f / 128.f, 1.f / 128.f},
        {1.f / 128.f, 1.f / 128.f, 1.f / 128.f},
        {1.f / 128.f, 1.f / 128.f, 1.f / 128.f},
        {1.f / 57.375f, 1.f / 57.12f, 1.f / 58.395f}
    };

    bool use_gpu = (int)cpugpu == 1;

    ncnn::MutexLockGuard g(lock);

#if NCNN_VULKAN
    if (use_gpu && ncnn::get_gpu_count() == 0)
        use_gpu = false;
#else
    use_gpu = false;
#endif

    if (!g_nanodet)
        g_nanodet = new NanoDet;

    int ret = g_nanodet->load(mgr,
                              modeltypes[(int)modelid],
                              target_sizes[(int)modelid],
                              mean_vals[(int)modelid],
                              norm_vals[(int)modelid],
                              use_gpu);

    if (ret != 0)
    {
        delete g_nanodet;
        g_nanodet = 0;
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

// 保留原项目相机 JNI 符号，当前 UVCAI 不直接调用。
JNIEXPORT jboolean JNICALL Java_com_tencent_nanodetncnn_NanoDetNcnn_openCamera(JNIEnv* env, jobject thiz, jint facing)
{
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_nanodetncnn_NanoDetNcnn_closeCamera(JNIEnv* env, jobject thiz)
{
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_nanodetncnn_NanoDetNcnn_setOutputWindow(JNIEnv* env, jobject thiz, jobject surface)
{
    return JNI_TRUE;
}

// public native float[] detectBitmap(Bitmap bitmap);
// return: [srcW, srcH, label, prob, x, y, w, h, ...]
JNIEXPORT jfloatArray JNICALL Java_com_tencent_nanodetncnn_NanoDetNcnn_detectBitmap(JNIEnv* env, jobject thiz, jobject bitmap)
{
    if (bitmap == 0)
        return make_empty_result(env);

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0)
        return make_empty_result(env);

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return make_empty_result(env);

    void* pixels = 0;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || pixels == 0)
        return make_empty_result(env);

    cv::Mat rgba((int)info.height, (int)info.width, CV_8UC4, pixels);
    cv::Mat rgb;
    cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);

    AndroidBitmap_unlockPixels(env, bitmap);

    std::vector<Object> objects;
    {
        ncnn::MutexLockGuard g(lock);
        if (!g_nanodet)
            return make_empty_result(env);

        g_nanodet->detect(rgb, objects, 0.35f, 0.5f);
    }

    const int max_objects = 80;
    const int count = std::min((int)objects.size(), max_objects);
    const int item_size = 6;
    const int out_size = 2 + count * item_size;

    std::vector<float> out(out_size);
    out[0] = (float)info.width;
    out[1] = (float)info.height;

    for (int i = 0; i < count; i++)
    {
        const Object& obj = objects[i];
        const int base = 2 + i * item_size;
        out[base] = (float)obj.label;
        out[base + 1] = obj.prob;
        out[base + 2] = obj.rect.x;
        out[base + 3] = obj.rect.y;
        out[base + 4] = obj.rect.width;
        out[base + 5] = obj.rect.height;
    }

    jfloatArray jret = env->NewFloatArray(out_size);
    env->SetFloatArrayRegion(jret, 0, out_size, out.data());
    return jret;
}

}
