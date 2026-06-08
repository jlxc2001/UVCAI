// Copyright (C) 2021 THL A29 Limited, a Tencent company.
// Licensed under the BSD 3-Clause License.

#ifndef NANODET_H
#define NANODET_H

#include <android/asset_manager_jni.h>
#include <opencv2/core/core.hpp>
#include <net.h>
#include <vector>

struct Object
{
    cv::Rect_<float> rect;
    int label;
    float prob;
};

class NanoDet
{
public:
    NanoDet();

    int load(AAssetManager* mgr, const char* modeltype, int target_size,
             const float* mean_vals, const float* norm_vals, bool use_gpu = false);

    int detect(const cv::Mat& rgb, std::vector<Object>& objects,
               float prob_threshold = 0.4f, float nms_threshold = 0.5f);

private:
    ncnn::Net nanodet;
    int target_size;
    float mean_vals[3];
    float norm_vals[3];
    ncnn::UnlockedPoolAllocator blob_pool_allocator;
    ncnn::PoolAllocator workspace_pool_allocator;
};

#endif // NANODET_H
