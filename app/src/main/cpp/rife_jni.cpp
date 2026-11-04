#include <jni.h>
#include <mutex>
#include <memory>
#include <string>
#include <vector>

#include "rife.h"
#include "gpu.h"
#include "mat.h"

namespace {
std::mutex g_mutex;
std::unique_ptr<RIFE> g_engine;
bool g_gpu_instance = false;

void releaseEngine() {
    g_engine.reset();
    if (g_gpu_instance) {
        ncnn::destroy_gpu_instance();
        g_gpu_instance = false;
    }
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omarpriv_smoothflow_RifeEngine_nativeInit(
        JNIEnv* env, jclass, jstring modelPath, jboolean preferGpu) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseEngine();

    const char* chars = env->GetStringUTFChars(modelPath, nullptr);
    if (!chars) return JNI_FALSE;
    const std::string path(chars);
    env->ReleaseStringUTFChars(modelPath, chars);

    int gpuId = -1;
    if (preferGpu == JNI_TRUE) {
        ncnn::create_gpu_instance();
        g_gpu_instance = true;
        if (ncnn::get_gpu_count() > 0) {
            gpuId = ncnn::get_default_gpu_index();
        }
    }

    g_engine.reset(new RIFE(gpuId, false, false, false, 2, true, false));
    if (g_engine->load(path) != 0) {
        releaseEngine();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_omarpriv_smoothflow_RifeEngine_nativeInterpolate(
        JNIEnv* env, jclass, jbyteArray first, jbyteArray second,
        jint width, jint height, jfloat timestep) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine || width <= 0 || height <= 0) return nullptr;

    const jsize expected = static_cast<jsize>(width * height * 3);
    if (env->GetArrayLength(first) != expected ||
        env->GetArrayLength(second) != expected) return nullptr;

    std::vector<unsigned char> pixels0(expected);
    std::vector<unsigned char> pixels1(expected);
    env->GetByteArrayRegion(first, 0, expected,
                            reinterpret_cast<jbyte*>(pixels0.data()));
    env->GetByteArrayRegion(second, 0, expected,
                            reinterpret_cast<jbyte*>(pixels1.data()));
    if (env->ExceptionCheck()) return nullptr;

    ncnn::Mat image0(width, height, pixels0.data(), (size_t)3, (size_t)3);
    ncnn::Mat image1(width, height, pixels1.data(), (size_t)3, (size_t)3);
    ncnn::Mat output;
    if (g_engine->process(image0, image1, timestep, output) != 0 || output.empty()) {
        return nullptr;
    }

    std::vector<unsigned char> rgb(expected);
    output.to_pixels(rgb.data(), ncnn::Mat::PIXEL_RGB);

    jbyteArray result = env->NewByteArray(expected);
    if (result) {
        env->SetByteArrayRegion(result, 0, expected,
                                reinterpret_cast<const jbyte*>(rgb.data()));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omarpriv_smoothflow_RifeEngine_nativeRelease(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseEngine();
}
