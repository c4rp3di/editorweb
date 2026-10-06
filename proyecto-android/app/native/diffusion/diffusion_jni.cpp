#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "stable-diffusion.h"

namespace {

constexpr const char * TAG = "ChatProDiffusion";

static std::string toString(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

static const char * emptyToNull(const std::string & s) {
    return s.empty() ? nullptr : s.c_str();
}

static bool writeImageContainer(const std::string & path, const sd_image_t & image) {
    if (!image.data || image.width == 0 || image.height == 0 || image.channel == 0) return false;
    const std::uint64_t bytes = static_cast<std::uint64_t>(image.width) * image.height * image.channel;
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    const char magic[8] = {'C','P','I','M','G','1','\0','\0'};
    const std::uint32_t width = image.width;
    const std::uint32_t height = image.height;
    const std::uint32_t channels = image.channel;
    const std::uint64_t size = bytes;
    out.write(magic, sizeof(magic));
    out.write(reinterpret_cast<const char *>(&width), sizeof(width));
    out.write(reinterpret_cast<const char *>(&height), sizeof(height));
    out.write(reinterpret_cast<const char *>(&channels), sizeof(channels));
    out.write(reinterpret_cast<const char *>(&size), sizeof(size));
    out.write(reinterpret_cast<const char *>(image.data), static_cast<std::streamsize>(bytes));
    return out.good();
}

static bool writeVideoContainer(const std::string & path, sd_image_t * frames, int frameCount, int fps) {
    if (!frames || frameCount <= 0 || fps <= 0) return false;
    if (frames[0].width == 0 || frames[0].height == 0 || frames[0].channel == 0) return false;
    const std::uint32_t width = frames[0].width;
    const std::uint32_t height = frames[0].height;
    const std::uint32_t channels = frames[0].channel;

    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    const char magic[8] = {'C','P','V','I','D','1','\0','\0'};
    out.write(magic, sizeof(magic));
    out.write(reinterpret_cast<const char *>(&width), sizeof(width));
    out.write(reinterpret_cast<const char *>(&height), sizeof(height));
    out.write(reinterpret_cast<const char *>(&channels), sizeof(channels));
    out.write(reinterpret_cast<const char *>(&frameCount), sizeof(frameCount));
    out.write(reinterpret_cast<const char *>(&fps), sizeof(fps));

    for (int i = 0; i < frameCount; ++i) {
        const sd_image_t & image = frames[i];
        if (!image.data || image.width != width || image.height != height || image.channel != channels) return false;
        const std::uint64_t bytes = static_cast<std::uint64_t>(width) * height * channels;
        out.write(reinterpret_cast<const char *>(image.data), static_cast<std::streamsize>(bytes));
    }
    return out.good();
}

static sd_ctx_t * createContext(const std::string & modelPath,
                                const std::string & clipL,
                                const std::string & clipG,
                                const std::string & t5,
                                const std::string & vae,
                                const std::string & diffusionModel) {
    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = emptyToNull(modelPath);
    params.clip_l_path = emptyToNull(clipL);
    params.clip_g_path = emptyToNull(clipG);
    params.t5xxl_path = emptyToNull(t5);
    params.vae_path = emptyToNull(vae);
    params.diffusion_model_path = emptyToNull(diffusionModel);
    params.backend = "vulkan0";
    params.params_backend = "vulkan0";
    params.n_threads = 4;
    params.enable_mmap = true;
    params.flash_attn = true;
    params.diffusion_flash_attn = true;
    return new_sd_ctx(&params);
}

static void logError(const char * message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message);
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_ejemplo_chat_ia_DiffusionNative_nativeVersion(JNIEnv * env, jclass) {
    const char * v = sd_version();
    if (!v || !*v) v = sd_get_system_info();
    return env->NewStringUTF(v ? v : "stable-diffusion.cpp");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ejemplo_chat_ia_DiffusionNative_nativeGenerateImage(
        JNIEnv * env, jclass,
        jstring jmodel, jstring jclipL, jstring jclipG, jstring jvae,
        jstring jprompt, jstring jnegative, jstring jout,
        jint width, jint height, jint steps, jlong seed) {
    const std::string model = toString(env, jmodel);
    const std::string clipL = toString(env, jclipL);
    const std::string clipG = toString(env, jclipG);
    const std::string vae = toString(env, jvae);
    const std::string prompt = toString(env, jprompt);
    const std::string negative = toString(env, jnegative);
    const std::string outPath = toString(env, jout);

    if (model.empty() || prompt.empty() || outPath.empty()) return JNI_FALSE;
    if (width <= 0 || height <= 0 || steps <= 0) return JNI_FALSE;

    sd_ctx_t * ctx = createContext(model, clipL, clipG, {}, vae, {});
    if (!ctx) {
        logError("No se pudo crear el contexto de stable-diffusion.cpp");
        return JNI_FALSE;
    }

    sd_img_gen_params_t gen;
    sd_img_gen_params_init(&gen);
    gen.prompt = prompt.c_str();
    gen.negative_prompt = negative.empty() ? nullptr : negative.c_str();
    gen.width = width;
    gen.height = height;
    gen.sample_params.sample_steps = steps;
    gen.sample_params.sample_method = EULER_SAMPLE_METHOD;
    gen.sample_params.scheduler = DISCRETE_SCHEDULER;
    gen.sample_params.guidance.txt_cfg = 7.0f;
    gen.seed = seed;
    gen.batch_count = 1;

    sd_image_t * images = nullptr;
    int count = 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "IMG start model=%s size=%dx%d steps=%d", model.c_str(), width, height, steps);
    const bool ok = generate_image(ctx, &gen, &images, &count);
    __android_log_print(ANDROID_LOG_INFO, TAG, "IMG generate returned=%d count=%d", ok ? 1 : 0, count);
    bool wrote = false;
    if (ok && images && count > 0) wrote = writeImageContainer(outPath, images[0]);

    if (images) free_sd_images(images, count);
    free_sd_ctx(ctx);
    __android_log_print(ANDROID_LOG_INFO, TAG, "IMG end wrote=%d", wrote ? 1 : 0);
    return wrote ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ejemplo_chat_ia_DiffusionNative_nativeGenerateVideo(
        JNIEnv * env, jclass,
        jstring jdiffusion, jstring jvae, jstring jt5,
        jstring jprompt, jstring jnegative, jstring jout,
        jint width, jint height, jint frames, jint fps, jint steps, jlong seed) {
    const std::string diffusion = toString(env, jdiffusion);
    const std::string vae = toString(env, jvae);
    const std::string t5 = toString(env, jt5);
    const std::string prompt = toString(env, jprompt);
    const std::string negative = toString(env, jnegative);
    const std::string outPath = toString(env, jout);

    if (diffusion.empty() || vae.empty() || t5.empty() || prompt.empty() || outPath.empty()) return JNI_FALSE;
    if (width <= 0 || height <= 0 || frames <= 0 || fps <= 0 || steps <= 0) return JNI_FALSE;

    sd_ctx_t * ctx = createContext({}, {}, {}, t5, vae, diffusion);
    if (!ctx) {
        logError("No se pudo crear el contexto de vídeo");
        return JNI_FALSE;
    }

    sd_vid_gen_params_t gen;
    sd_vid_gen_params_init(&gen);
    gen.prompt = prompt.c_str();
    gen.negative_prompt = negative.empty() ? nullptr : negative.c_str();
    gen.width = width;
    gen.height = height;
    gen.video_frames = frames;
    gen.fps = fps;
    gen.sample_params.sample_steps = steps;
    gen.sample_params.sample_method = EULER_SAMPLE_METHOD;
    gen.sample_params.scheduler = DISCRETE_SCHEDULER;
    gen.sample_params.guidance.txt_cfg = 6.0f;
    gen.sample_params.flow_shift = 3.0f;
    gen.seed = seed;

    sd_image_t * outFrames = nullptr;
    sd_audio_t * audio = nullptr;
    int frameCount = 0;
    int fpsOut = 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "VID start model=%s size=%dx%d frames=%d fps=%d steps=%d", diffusion.c_str(), width, height, frames, fps, steps);
    // La API de stable-diffusion.cpp devuelve además los fps reales.
    const bool ok = generate_video(ctx, &gen, &outFrames, &frameCount, &audio, &fpsOut);
    __android_log_print(ANDROID_LOG_INFO, TAG, "VID generate returned=%d framesOut=%d fpsOut=%d", ok ? 1 : 0, frameCount, fpsOut);
    const int writeFps = fpsOut > 0 ? fpsOut : fps;
    bool wrote = false;
    if (ok && outFrames && frameCount > 0) wrote = writeVideoContainer(outPath, outFrames, frameCount, writeFps);

    if (audio) free_sd_audio(audio);
    if (outFrames) free_sd_images(outFrames, frameCount);
    free_sd_ctx(ctx);
    __android_log_print(ANDROID_LOG_INFO, TAG, "VID end wrote=%d", wrote ? 1 : 0);
    return wrote ? JNI_TRUE : JNI_FALSE;
}
