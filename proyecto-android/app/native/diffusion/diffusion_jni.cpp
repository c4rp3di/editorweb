#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <fstream>
#include <string>
#include <vector>

#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <unistd.h>
#include <unwind.h>

#include "stable-diffusion.h"

namespace {

constexpr const char * TAG = "ChatProDiffusion";

// ---- Registro nativo persistente (sobrevive a un cierre brusco) ----
// stdout/stderr se redirigen a un archivo: así quedan también los logs de stable-diffusion.cpp/ggml.
static int g_logfd = -1;

static void wrStr(const char * s) {
    if (g_logfd >= 0 && s) { const ssize_t r = write(g_logfd, s, strlen(s)); (void) r; }
}

static void wrNum(long v) {
    char buf[24];
    int i = 23;
    buf[i] = 0;
    const bool neg = v < 0;
    unsigned long u = neg ? static_cast<unsigned long>(-v) : static_cast<unsigned long>(v);
    do { buf[--i] = static_cast<char>('0' + (u % 10)); u /= 10; } while (u && i > 1);
    if (neg) buf[--i] = '-';
    wrStr(buf + i);
}

static void wrHex(uintptr_t v) {
    char buf[2 + sizeof(uintptr_t) * 2 + 1];
    buf[0] = '0'; buf[1] = 'x';
    int n = 0;
    for (int shift = static_cast<int>(sizeof(uintptr_t)) * 8 - 4; shift >= 0; shift -= 4) {
        buf[2 + n++] = "0123456789abcdef"[(v >> shift) & 0xF];
    }
    buf[2 + n] = 0;
    wrStr(buf);
}

static void nlogf(const char * fmt, ...) __attribute__((format(printf, 1, 2)));
static void nlogf(const char * fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", buf);
    wrStr("[NATIVE] ");
    wrStr(buf);
    wrStr("\n");
}

struct BtState { void * frames[32]; int n; };

static _Unwind_Reason_Code unwindCb(struct _Unwind_Context * ctx, void * arg) {
    auto * st = static_cast<BtState *>(arg);
    const uintptr_t pc = static_cast<uintptr_t>(_Unwind_GetIP(ctx));
    if (pc == 0 || st->n >= 32) return _URC_END_OF_STACK;
    st->frames[st->n++] = reinterpret_cast<void *>(pc);
    return _URC_NO_REASON;
}

static void onFatalSignal(int sig, siginfo_t * info, void *) {
    wrStr("\n[NATIVE] !! SENAL ");
    wrNum(sig);
    switch (sig) {
        case SIGSEGV: wrStr(" (SIGSEGV)"); break;
        case SIGABRT: wrStr(" (SIGABRT)"); break;
        case SIGBUS:  wrStr(" (SIGBUS)");  break;
        case SIGILL:  wrStr(" (SIGILL)");  break;
        case SIGFPE:  wrStr(" (SIGFPE)");  break;
        default: break;
    }
    wrStr(" dir=");
    wrHex(reinterpret_cast<uintptr_t>(info ? info->si_addr : nullptr));
    wrStr(" codigo=");
    wrNum(info ? info->si_code : 0);
    wrStr("\n");
    BtState st;
    st.n = 0;
    _Unwind_Backtrace(unwindCb, &st);
    for (int i = 0; i < st.n; ++i) {
        wrStr("[NATIVE]   #");
        wrNum(i);
        wrStr(" ");
        Dl_info di;
        if (dladdr(st.frames[i], &di) && di.dli_fname) {
            wrStr(di.dli_fname);
            wrStr(" +");
            wrHex(reinterpret_cast<uintptr_t>(st.frames[i]) - reinterpret_cast<uintptr_t>(di.dli_fbase));
            if (di.dli_sname) { wrStr(" ("); wrStr(di.dli_sname); wrStr(")"); }
        } else {
            wrHex(reinterpret_cast<uintptr_t>(st.frames[i]));
        }
        wrStr("\n");
    }
    wrStr("[NATIVE] fin del informe de senal\n");
    _exit(128 + sig);
}

static void onTerminate() {
    wrStr("\n[NATIVE] !! std::terminate: ");
    try {
        auto e = std::current_exception();
        if (e) std::rethrow_exception(e);
        wrStr("sin excepcion activa");
    } catch (const std::exception & ex) {
        wrStr(ex.what());
    } catch (...) {
        wrStr("excepcion desconocida");
    }
    wrStr("\n");
    abort();
}

static void installSignalHandlers() {
    static char altstack[32768];
    stack_t ss;
    ss.ss_sp = altstack;
    ss.ss_size = sizeof(altstack);
    ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sigemptyset(&sa.sa_mask);
    sa.sa_sigaction = onFatalSignal;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    const int sigs[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};
    for (int s : sigs) sigaction(s, &sa, nullptr);
}

// Progreso consultable desde Kotlin (solo atómicos: sin JNI desde el callback).
// fase: 0 inactivo · 1 cargando modelo · 2 muestreando · 3 decodificando/guardando
static std::atomic<int> g_phase{0};
static std::atomic<int> g_step{0};
static std::atomic<int> g_steps{0};
static std::atomic<long long> g_last_step_ms{0};

static void onProgress(int step, int steps, float time, void * /*data*/) {
    g_step.store(step);
    g_steps.store(steps);
    g_last_step_ms.store(static_cast<long long>(time * 1000.0f));
}

struct PhaseGuard {
    PhaseGuard() {
        g_phase.store(1);
        g_step.store(0);
        g_steps.store(0);
        g_last_step_ms.store(0);
        sd_set_progress_callback(onProgress, nullptr);
    }
    ~PhaseGuard() { g_phase.store(0); }
};

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
                                const std::string & diffusionModel,
                                bool ligero = false) {
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
    // Modo ligero (imagen): pesos a F16 y sin mmap, para no duplicar en RAM el archivo y los buffers de la GPU.
    params.enable_mmap = !ligero;
    if (ligero) params.wtype = SD_TYPE_F16;
    params.flash_attn = true;
    params.diffusion_flash_attn = true;
    return new_sd_ctx(&params);
}

static void logError(const char * message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message);
    wrStr("[NATIVE] ERROR: ");
    wrStr(message);
    wrStr("\n");
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_ejemplo_chat_ia_DiffusionNative_nativeVersion(JNIEnv * env, jclass) {
    const char * v = sd_version();
    if (!v || !*v) v = sd_get_system_info();
    return env->NewStringUTF(v ? v : "stable-diffusion.cpp");
}

static jboolean generateImageImpl(
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

    PhaseGuard guard;
    sd_ctx_t * ctx = createContext(model, clipL, clipG, {}, vae, {}, true);
    if (!ctx) {
        logError("No se pudo crear el contexto de stable-diffusion.cpp");
        return JNI_FALSE;
    }

    g_phase.store(2);
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
    nlogf("IMG start model=%s size=%dx%d steps=%d", model.c_str(), width, height, steps);
    const bool ok = generate_image(ctx, &gen, &images, &count);
    g_phase.store(3);
    nlogf("IMG generate returned=%d count=%d", ok ? 1 : 0, count);
    bool wrote = false;
    if (ok && images && count > 0) wrote = writeImageContainer(outPath, images[0]);

    if (images) free_sd_images(images, count);
    free_sd_ctx(ctx);
    nlogf("IMG end wrote=%d", wrote ? 1 : 0);
    return wrote ? JNI_TRUE : JNI_FALSE;
}

static jboolean generateVideoImpl(
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

    PhaseGuard guard;
    sd_ctx_t * ctx = createContext({}, {}, {}, t5, vae, diffusion);
    if (!ctx) {
        logError("No se pudo crear el contexto de vídeo");
        return JNI_FALSE;
    }

    g_phase.store(2);
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
    nlogf("VID start model=%s size=%dx%d frames=%d fps=%d steps=%d", diffusion.c_str(), width, height, frames, fps, steps);
    // La API de stable-diffusion.cpp devuelve además los fps reales.
    const bool ok = generate_video(ctx, &gen, &outFrames, &frameCount, &audio, &fpsOut);
    g_phase.store(3);
    nlogf("VID generate returned=%d framesOut=%d fpsOut=%d", ok ? 1 : 0, frameCount, fpsOut);
    const int writeFps = fpsOut > 0 ? fpsOut : fps;
    bool wrote = false;
    if (ok && outFrames && frameCount > 0) wrote = writeVideoContainer(outPath, outFrames, frameCount, writeFps);

    if (audio) free_sd_audio(audio);
    if (outFrames) free_sd_images(outFrames, frameCount);
    free_sd_ctx(ctx);
    nlogf("VID end wrote=%d", wrote ? 1 : 0);
    return wrote ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ejemplo_chat_ia_DiffusionNative_nativeProgress(JNIEnv * env, jclass) {
    const std::string s = std::to_string(g_phase.load()) + "|" + std::to_string(g_step.load()) + "|" +
                          std::to_string(g_steps.load()) + "|" + std::to_string(g_last_step_ms.load());
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ejemplo_chat_ia_DiffusionNative_nativeGenerateImage(
        JNIEnv * env, jclass cls,
        jstring jmodel, jstring jclipL, jstring jclipG, jstring jvae,
        jstring jprompt, jstring jnegative, jstring jout,
        jint width, jint height, jint steps, jlong seed) {
    try {
        return generateImageImpl(env, cls, jmodel, jclipL, jclipG, jvae, jprompt, jnegative, jout, width, height, steps, seed);
    } catch (const std::exception & e) {
        nlogf("EXCEPCION C++ generando imagen: %s", e.what());
    } catch (...) {
        nlogf("EXCEPCION C++ desconocida generando imagen");
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ejemplo_chat_ia_DiffusionNative_nativeGenerateVideo(
        JNIEnv * env, jclass cls,
        jstring jdiffusion, jstring jvae, jstring jt5,
        jstring jprompt, jstring jnegative, jstring jout,
        jint width, jint height, jint frames, jint fps, jint steps, jlong seed) {
    try {
        return generateVideoImpl(env, cls, jdiffusion, jvae, jt5, jprompt, jnegative, jout, width, height, frames, fps, steps, seed);
    } catch (const std::exception & e) {
        nlogf("EXCEPCION C++ generando video: %s", e.what());
    } catch (...) {
        nlogf("EXCEPCION C++ desconocida generando video");
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ejemplo_chat_ia_DiffusionNative_nativeInstallLog(JNIEnv * env, jclass, jstring jpath) {
    static bool instalado = false;
    if (instalado) return;
    const std::string path = toString(env, jpath);
    if (path.empty()) return;
    const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return;
    instalado = true;
    g_logfd = fd;
    dup2(fd, 1);
    dup2(fd, 2);
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    std::set_terminate(onTerminate);
    installSignalHandlers();
    wrStr("[NATIVE] registro nativo activo (stdout/stderr + senales)\n");
}
