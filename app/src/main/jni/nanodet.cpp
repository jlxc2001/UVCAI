// Copyright (C) 2021 THL A29 Limited, a Tencent company.
// Licensed under the BSD 3-Clause License.

#include "nanodet.h"

#include <algorithm>
#include <cfloat>
#include <cstdio>
#include <vector>

#include <cpu.h>
#include <layer.h>
#include <mat.h>
#include <paramdict.h>

static inline float intersection_area(const Object& a, const Object& b)
{
    cv::Rect_<float> inter = a.rect & b.rect;
    return inter.area();
}

static void qsort_descent_inplace(std::vector<Object>& objects, int left, int right)
{
    int i = left;
    int j = right;
    float p = objects[(left + right) / 2].prob;

    while (i <= j)
    {
        while (objects[i].prob > p) i++;
        while (objects[j].prob < p) j--;

        if (i <= j)
        {
            std::swap(objects[i], objects[j]);
            i++;
            j--;
        }
    }

    if (left < j) qsort_descent_inplace(objects, left, j);
    if (i < right) qsort_descent_inplace(objects, i, right);
}

static void qsort_descent_inplace(std::vector<Object>& objects)
{
    if (objects.empty()) return;
    qsort_descent_inplace(objects, 0, (int)objects.size() - 1);
}

static void nms_sorted_bboxes(const std::vector<Object>& objects, std::vector<int>& picked, float nms_threshold)
{
    picked.clear();

    const int n = (int)objects.size();
    std::vector<float> areas(n);
    for (int i = 0; i < n; i++)
        areas[i] = objects[i].rect.width * objects[i].rect.height;

    for (int i = 0; i < n; i++)
    {
        const Object& a = objects[i];
        int keep = 1;
        for (int j = 0; j < (int)picked.size(); j++)
        {
            const Object& b = objects[picked[j]];
            float inter_area = intersection_area(a, b);
            float union_area = areas[i] + areas[picked[j]] - inter_area;
            if (union_area > 0.f && inter_area / union_area > nms_threshold)
                keep = 0;
        }
        if (keep)
            picked.push_back(i);
    }
}

static void generate_proposals(const ncnn::Mat& cls_pred, const ncnn::Mat& dis_pred,
                               int stride, const ncnn::Mat& in_pad,
                               float prob_threshold, std::vector<Object>& objects)
{
    const int num_grid = cls_pred.h;

    int num_grid_x;
    int num_grid_y;
    if (in_pad.w > in_pad.h)
    {
        num_grid_x = in_pad.w / stride;
        num_grid_y = num_grid / num_grid_x;
    }
    else
    {
        num_grid_y = in_pad.h / stride;
        num_grid_x = num_grid / num_grid_y;
    }

    const int num_class = cls_pred.w;
    const int reg_max_1 = dis_pred.w / 4;

    for (int i = 0; i < num_grid_y; i++)
    {
        for (int j = 0; j < num_grid_x; j++)
        {
            const int idx = i * num_grid_x + j;
            const float* scores = cls_pred.row(idx);

            int label = -1;
            float score = -FLT_MAX;
            for (int k = 0; k < num_class; k++)
            {
                if (scores[k] > score)
                {
                    label = k;
                    score = scores[k];
                }
            }

            if (score >= prob_threshold)
            {
                ncnn::Mat bbox_pred(reg_max_1, 4, (void*)dis_pred.row(idx));

                ncnn::Layer* softmax = ncnn::create_layer("Softmax");
                ncnn::ParamDict pd;
                pd.set(0, 1); // axis
                pd.set(1, 1);
                softmax->load_param(pd);

                ncnn::Option opt;
                opt.num_threads = 1;
                opt.use_packing_layout = false;
                softmax->create_pipeline(opt);
                softmax->forward_inplace(bbox_pred, opt);
                softmax->destroy_pipeline(opt);
                delete softmax;

                float pred_ltrb[4];
                for (int k = 0; k < 4; k++)
                {
                    float dis = 0.f;
                    const float* dis_after_sm = bbox_pred.row(k);
                    for (int l = 0; l < reg_max_1; l++)
                        dis += l * dis_after_sm[l];
                    pred_ltrb[k] = dis * stride;
                }

                float pb_cx = (j + 0.5f) * stride;
                float pb_cy = (i + 0.5f) * stride;

                float x0 = pb_cx - pred_ltrb[0];
                float y0 = pb_cy - pred_ltrb[1];
                float x1 = pb_cx + pred_ltrb[2];
                float y1 = pb_cy + pred_ltrb[3];

                Object obj;
                obj.rect.x = x0;
                obj.rect.y = y0;
                obj.rect.width = x1 - x0;
                obj.rect.height = y1 - y0;
                obj.label = label;
                obj.prob = score;
                objects.push_back(obj);
            }
        }
    }
}

NanoDet::NanoDet()
{
    blob_pool_allocator.set_size_compare_ratio(0.f);
    workspace_pool_allocator.set_size_compare_ratio(0.f);
    target_size = 320;
    mean_vals[0] = mean_vals[1] = mean_vals[2] = 0.f;
    norm_vals[0] = norm_vals[1] = norm_vals[2] = 1.f;
}

int NanoDet::load(AAssetManager* mgr, const char* modeltype, int _target_size,
                  const float* _mean_vals, const float* _norm_vals, bool use_gpu)
{
    nanodet.clear();
    blob_pool_allocator.clear();
    workspace_pool_allocator.clear();

    ncnn::set_cpu_powersave(2);
    ncnn::set_omp_num_threads(ncnn::get_big_cpu_count());

    nanodet.opt = ncnn::Option();
#if NCNN_VULKAN
    nanodet.opt.use_vulkan_compute = use_gpu;
#endif
    nanodet.opt.num_threads = ncnn::get_big_cpu_count();
    nanodet.opt.blob_allocator = &blob_pool_allocator;
    nanodet.opt.workspace_allocator = &workspace_pool_allocator;

    char parampath[256];
    char modelpath[256];
    std::snprintf(parampath, sizeof(parampath), "nanodet-%s.param", modeltype);
    std::snprintf(modelpath, sizeof(modelpath), "nanodet-%s.bin", modeltype);

    if (nanodet.load_param(mgr, parampath) != 0)
        return -1;
    if (nanodet.load_model(mgr, modelpath) != 0)
        return -1;

    target_size = _target_size;
    mean_vals[0] = _mean_vals[0];
    mean_vals[1] = _mean_vals[1];
    mean_vals[2] = _mean_vals[2];
    norm_vals[0] = _norm_vals[0];
    norm_vals[1] = _norm_vals[1];
    norm_vals[2] = _norm_vals[2];

    return 0;
}

int NanoDet::detect(const cv::Mat& rgb, std::vector<Object>& objects,
                    float prob_threshold, float nms_threshold)
{
    objects.clear();

    int width = rgb.cols;
    int height = rgb.rows;

    int w = width;
    int h = height;
    float scale = 1.f;
    if (w > h)
    {
        scale = (float)target_size / w;
        w = target_size;
        h = (int)(h * scale);
    }
    else
    {
        scale = (float)target_size / h;
        h = target_size;
        w = (int)(w * scale);
    }

    ncnn::Mat in = ncnn::Mat::from_pixels_resize(rgb.data, ncnn::Mat::PIXEL_RGB2BGR,
                                                 width, height, w, h);

    int wpad = (w + 31) / 32 * 32 - w;
    int hpad = (h + 31) / 32 * 32 - h;

    ncnn::Mat in_pad;
    ncnn::copy_make_border(in, in_pad,
                           hpad / 2, hpad - hpad / 2,
                           wpad / 2, wpad - wpad / 2,
                           ncnn::BORDER_CONSTANT, 0.f);

    in_pad.substract_mean_normalize(mean_vals, norm_vals);

    ncnn::Extractor ex = nanodet.create_extractor();
    ex.input("input.1", in_pad);

    std::vector<Object> proposals;

    ncnn::Mat cls_pred8;
    ncnn::Mat dis_pred8;
    ex.extract("cls_pred_stride_8", cls_pred8);
    ex.extract("dis_pred_stride_8", dis_pred8);
    generate_proposals(cls_pred8, dis_pred8, 8, in_pad, prob_threshold, proposals);

    ncnn::Mat cls_pred16;
    ncnn::Mat dis_pred16;
    ex.extract("cls_pred_stride_16", cls_pred16);
    ex.extract("dis_pred_stride_16", dis_pred16);
    generate_proposals(cls_pred16, dis_pred16, 16, in_pad, prob_threshold, proposals);

    ncnn::Mat cls_pred32;
    ncnn::Mat dis_pred32;
    ex.extract("cls_pred_stride_32", cls_pred32);
    ex.extract("dis_pred_stride_32", dis_pred32);
    generate_proposals(cls_pred32, dis_pred32, 32, in_pad, prob_threshold, proposals);

    qsort_descent_inplace(proposals);

    std::vector<int> picked;
    nms_sorted_bboxes(proposals, picked, nms_threshold);

    int count = (int)picked.size();
    objects.resize(count);
    for (int i = 0; i < count; i++)
    {
        objects[i] = proposals[picked[i]];

        float x0 = (objects[i].rect.x - (wpad / 2)) / scale;
        float y0 = (objects[i].rect.y - (hpad / 2)) / scale;
        float x1 = (objects[i].rect.x + objects[i].rect.width - (wpad / 2)) / scale;
        float y1 = (objects[i].rect.y + objects[i].rect.height - (hpad / 2)) / scale;

        x0 = std::max(std::min(x0, (float)(width - 1)), 0.f);
        y0 = std::max(std::min(y0, (float)(height - 1)), 0.f);
        x1 = std::max(std::min(x1, (float)(width - 1)), 0.f);
        y1 = std::max(std::min(y1, (float)(height - 1)), 0.f);

        objects[i].rect.x = x0;
        objects[i].rect.y = y0;
        objects[i].rect.width = x1 - x0;
        objects[i].rect.height = y1 - y0;
    }

    struct ObjectAreaGreater
    {
        bool operator()(const Object& a, const Object& b) const
        {
            return a.rect.area() > b.rect.area();
        }
    } object_area_greater;

    std::sort(objects.begin(), objects.end(), object_area_greater);
    return 0;
}
