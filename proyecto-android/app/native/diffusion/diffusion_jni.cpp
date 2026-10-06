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
#include <algorithm>

#include <zlib.h>

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

static long currentRssMb() {
    std::ifstream in("/proc/self/status");
    std::string line;
    while (std::getline(in, line)) {
        if (line.rfind("VmRSS:", 0) == 0) {
            long kb = 0;
            if (sscanf(line.c_str(), "VmRSS: %ld kB", &kb) == 1) return kb / 1024;
        }
    }
    return -1;
}

static void onProgress(int step, int steps, float time, void * /*data*/) {
    g_step.store(step);
    g_steps.store(steps);
    g_last_step_ms.store(static_cast<long long>(time * 1000.0f));
    // stable-diffusion.cpp puede seguir dentro de generate_image después del último
    // callback. Marcamos esa fase como salida/decodificación para no confundir
    // "140/140" con "la función JNI ya ha terminado".
    if (step >= steps && steps > 0) g_phase.store(3);
}

// stable-diffusion.cpp no imprime nada si no hay callback registrado: sin esto los
// errores reales (memoria, Vulkan, operador no soportado...) no llegan a native.log.
static void onSdLog(sd_log_level_t level, const char * text, void * /*data*/) {
    if (!text) return;
    const int lv = static_cast<int>(level); // 0 debug · 1 info · 2 warn · 3 error
    if (lv < 1) return;
    std::string s(text);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    nlogf("SD %s: %.400s", lv >= 3 ? "ERROR" : (lv == 2 ? "WARN" : "INFO"), s.c_str());
}

struct PhaseGuard {
    PhaseGuard() {
        g_phase.store(1);
        g_step.store(0);
        g_steps.store(0);
        g_last_step_ms.store(0);
        sd_set_log_callback(onSdLog, nullptr);
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

static void writeBe32(std::ofstream & out, std::uint32_t v) {
    const unsigned char b[4] = {
        static_cast<unsigned char>((v >> 24) & 0xff),
        static_cast<unsigned char>((v >> 16) & 0xff),
        static_cast<unsigned char>((v >> 8) & 0xff),
        static_cast<unsigned char>(v & 0xff)
    };
    out.write(reinterpret_cast<const char *>(b), 4);
}

static bool writePngChunk(std::ofstream & out, const char type[4], const unsigned char * data, std::size_t size) {
    if (size > 0xffffffffu) return false;
    writeBe32(out, static_cast<std::uint32_t>(size));
    out.write(type, 4);
    if (size) out.write(reinterpret_cast<const char *>(data), static_cast<std::streamsize>(size));
    uLong crc = crc32(0L, Z_NULL, 0);
    crc = crc32(crc, reinterpret_cast<const Bytef *>(type), 4);
    if (size) crc = crc32(crc, reinterpret_cast<const Bytef *>(data), static_cast<uInt>(size));
    writeBe32(out, static_cast<std::uint32_t>(crc));
    return out.good();
}

// Escribe PNG directamente desde el buffer nativo, fila a fila. Esto evita el
// antiguo camino CPIMG1 -> ByteArray -> IntArray -> Bitmap -> PNG, que creaba
// varias copias completas de la imagen en el proceso Android.
static bool writePngStreaming(const std::string & path, const sd_image_t & image) {
    if (!image.data || image.width == 0 || image.height == 0) return false;
    if (image.channel != 3 && image.channel != 4) return false;
    const std::size_t rowBytes = static_cast<std::size_t>(image.width) * image.channel;
    if (rowBytes > 0xffffffffu) return false;

    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    static const unsigned char signature[8] = {137,80,78,71,13,10,26,10};
    out.write(reinterpret_cast<const char *>(signature), sizeof(signature));

    unsigned char ihdr[13] = {};
    ihdr[0] = static_cast<unsigned char>((image.width >> 24) & 0xff);
    ihdr[1] = static_cast<unsigned char>((image.width >> 16) & 0xff);
    ihdr[2] = static_cast<unsigned char>((image.width >> 8) & 0xff);
    ihdr[3] = static_cast<unsigned char>(image.width & 0xff);
    ihdr[4] = static_cast<unsigned char>((image.height >> 24) & 0xff);
    ihdr[5] = static_cast<unsigned char>((image.height >> 16) & 0xff);
    ihdr[6] = static_cast<unsigned char>((image.height >> 8) & 0xff);
    ihdr[7] = static_cast<unsigned char>(image.height & 0xff);
    ihdr[8] = 8;              // bit depth
    ihdr[9] = image.channel == 4 ? 6 : 2; // RGBA / RGB
    if (!writePngChunk(out, "IHDR", ihdr, sizeof(ihdr))) return false;

    z_stream zs{};
    if (deflateInit(&zs, Z_BEST_SPEED) != Z_OK) return false;
    std::vector<unsigned char> row(rowBytes + 1);
    std::vector<unsigned char> compressed(64 * 1024);
    bool ok = true;
    bool wroteIdat = false;

    auto emit = [&](int flush) -> bool {
        zs.next_out = compressed.data();
        zs.avail_out = static_cast<uInt>(compressed.size());
        const int rc = deflate(&zs, flush);
        if (rc != Z_OK && rc != Z_STREAM_END) return false;
        const std::size_t produced = compressed.size() - zs.avail_out;
        if (produced > 0) {
            if (!writePngChunk(out, "IDAT", compressed.data(), produced)) return false;
            wroteIdat = true;
        }
        return true;
    };

    const unsigned char * pixels = image.data;
    for (std::uint32_t y = 0; y < image.height && ok; ++y) {
        row[0] = 0; // PNG filter: None
        std::memcpy(row.data() + 1, pixels + static_cast<std::size_t>(y) * rowBytes, rowBytes);
        zs.next_in = row.data();
        zs.avail_in = static_cast<uInt>(row.size());
        while (zs.avail_in > 0 && ok) ok = emit(Z_NO_FLUSH);
    }
    if (ok) {
        zs.next_in = nullptr;
        zs.avail_in = 0;
        int rc = Z_OK;
        while (rc == Z_OK) {
            zs.next_out = compressed.data();
            zs.avail_out = static_cast<uInt>(compressed.size());
            rc = deflate(&zs, Z_FINISH);
            const std::size_t produced = compressed.size() - zs.avail_out;
            if (produced && !writePngChunk(out, "IDAT", compressed.data(), produced)) ok = false;
            if (rc != Z_OK && rc != Z_STREAM_END) ok = false;
        }
    }
    deflateEnd(&zs);
    if (!ok || !wroteIdat) return false;
    if (!writePngChunk(out, "IEND", nullptr, 0)) return false;
    out.flush();
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
    // v19.2: UNet y CLIP en la GPU Vulkan; VAE en CPU (en Mali-G715 cada tile de VAE tardaba
    // ~130 s y acababa en "waitForFences Timeout"). Sintaxis por módulo: docs/backend.md.
    // Vídeo (Wan, !ligero): el T5 (umt5 Q4_K) abortaba en ggml_backend_sched_split_graph porque
    // Vulkan0 no puede ejecutar una operación sobre su tabla de embeddings; el codificador de texto va a CPU.
    const char * backendSpec = ligero ? "diffusion=vulkan0,vae=cpu"
                                      : "diffusion=vulkan0,te=cpu,vae=cpu";
    params.backend = backendSpec;
    params.params_backend = backendSpec;
    params.n_threads = 4;
    // Modo ligero (imagen): pesos a F16 y sin mmap, para no duplicar en RAM el archivo y los buffers de la GPU.
    params.enable_mmap = !ligero;
    if (ligero) params.wtype = SD_TYPE_F16;
    // v19.2 PRUEBA: flash attention desactivado (en Mali sin coopmat el shader FA es escalar y lento;
    // el UNet tardaba ~59 s por paso). Si empeora, volver a true.
    params.flash_attn = false;
    params.diffusion_flash_attn = false;
    // La v15 ya evitaba las copias Java, pero los logs muestran que el pico
    // de RSS ocurre dentro de stable-diffusion.cpp, después del último paso
    // de muestreo, mientras decodifica el VAE. Limitamos la memoria de trabajo
    // de Vulkan y dejamos que el VAE procese por teselas.
    // PRUEBA v19.1: max_vram desactivado (sospecha de regresión: 1.5 GiB < UNet F16 ~1.7 GiB).
    // params.max_vram = "1.5";
    params.disable_prefetch = true;
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
    // El fallo observado en v15 no está en el PNG: el RSS salta a ~4.8 GB
    // todavía dentro de generate_image(), justo en la fase posterior al
    // muestreo. El tiling VAE reduce ese pico sin cambiar la resolución final.
    gen.vae_tiling_params.enabled = true;
    gen.vae_tiling_params.temporal_tiling = false;
    gen.vae_tiling_params.tile_size_w = 256;
    gen.vae_tiling_params.tile_size_h = 256;
    gen.vae_tiling_params.target_overlap = 0.5f;

    sd_image_t * images = nullptr;
    int count = 0;
    nlogf("IMG start model=%s size=%dx%d steps=%d rss=%ldMB", model.c_str(), width, height, steps, currentRssMb());
    const bool ok = generate_image(ctx, &gen, &images, &count);
    g_phase.store(3);
    nlogf("IMG generate returned=%d count=%d rss=%ldMB", ok ? 1 : 0, count, currentRssMb());
    bool wrote = false;
    if (ok && images && count > 0) {
        nlogf("IMG salida recibida width=%u height=%u channels=%u bytes=%llu rss=%ldMB",
              images[0].width, images[0].height, images[0].channel,
              static_cast<unsigned long long>(static_cast<std::uint64_t>(images[0].width) * images[0].height * images[0].channel),
              currentRssMb());
        wrote = writePngStreaming(outPath, images[0]);
        nlogf("IMG PNG directo terminado=%d rss=%ldMB", wrote ? 1 : 0, currentRssMb());
    } else {
        nlogf("IMG sin salida utilizable: ok=%d count=%d", ok ? 1 : 0, count);
    }

    if (images) { free_sd_images(images, count); images = nullptr; }
    free_sd_ctx(ctx);
    nlogf("IMG end wrote=%d rss=%ldMB", wrote ? 1 : 0, currentRssMb());
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
