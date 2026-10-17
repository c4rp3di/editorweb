#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
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
#include <cmath>

#include <zlib.h>

#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <unistd.h>
#include <unwind.h>

#include "stable-diffusion.h"

namespace {

constexpr const char * TAG = "ChatImagenDiffusion";

// ---- Registro nativo persistente ----
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
}

static void advancePhase(int to) {
    int cur = g_phase.load();
    while (to > cur && !g_phase.compare_exchange_weak(cur, to)) {}
}

static void onSdLog(sd_log_level_t level, const char * text, void * /*data*/) {
    if (!text) return;
    const int lv = static_cast<int>(level);
    if (lv < 1) return;
    std::string s(text);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    nlogf("SD %s: %.400s", lv >= 3 ? "ERROR" : (lv == 2 ? "WARN" : "INFO"), s.c_str());
    const char * t = s.c_str();
    if (strstr(t, "generating image:") || strstr(t, "- sample ")) advancePhase(2);
    else if (strstr(t, "sampling completed") || strstr(t, "decoding")) advancePhase(3);
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

// Escribe PNG directamente desde el buffer nativo, fila a fila.
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
    ihdr[8] = 8;
    ihdr[9] = image.channel == 4 ? 6 : 2;
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
        row[0] = 0;
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

// Unsharp mask: realza bordes sin amplificar el ruido de fondo.
// blur = media local 3x3; resultado = original * (1 + amount) - blur * amount.
// threshold: diferencia mínima por canal para aplicar el refuerzo (0 = siempre).
static void aplicarNitidez(std::vector<unsigned char> & pixels, int width, int height, int channels,
                           float amount, float threshold) {
    if (amount <= 0.0f || width < 3 || height < 3) return;
    const size_t total = static_cast<size_t>(width) * height * channels;
    std::vector<unsigned char> blurred(total, 0);
    // Media 3x3 (fila a fila, reutilizando columnas).
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            for (int c = 0; c < channels; ++c) {
                int suma = 0, cuenta = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    const int yy = y + dy;
                    if (yy < 0 || yy >= height) continue;
                    for (int dx = -1; dx <= 1; ++dx) {
                        const int xx = x + dx;
                        if (xx < 0 || xx >= width) continue;
                        suma += pixels[(static_cast<size_t>(yy) * width + xx) * channels + c];
                        cuenta++;
                    }
                }
                blurred[(static_cast<size_t>(y) * width + x) * channels + c] =
                    static_cast<unsigned char>(suma / cuenta);
            }
        }
    }
    const float umbral = threshold * 255.0f;
    for (size_t i = 0; i < total; ++i) {
        const float original = static_cast<float>(pixels[i]);
        const float suave = static_cast<float>(blurred[i]);
        const float diferencia = original - suave;
        if (std::fabs(diferencia) < umbral) continue;
        const float nitido = original + amount * diferencia;
        pixels[i] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, nitido)));
    }
}

static sd_ctx_t * createContext(const std::string & modelPath, const std::string & vaePath, bool flashAttn) {
    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = modelPath.empty() ? nullptr : modelPath.c_str();
    params.vae_path = vaePath.empty() ? nullptr : vaePath.c_str();
    const char * backendSpec = "diffusion=vulkan0,vae=cpu";
    params.backend = backendSpec;
    params.params_backend = backendSpec;
    params.n_threads = 4;
    params.enable_mmap = false;
    params.wtype = SD_TYPE_F16;
    params.flash_attn = flashAttn;
    params.diffusion_flash_attn = flashAttn;
    params.disable_prefetch = true;
    return new_sd_ctx(&params);
}

// ---- Modelo en memoria (opcional) ----
// Con "mantener modelo" el contexto sobrevive entre imágenes del mismo proceso :gen.
static sd_ctx_t * g_ctx = nullptr;
static std::string g_ctxKey;

static void releaseCachedCtx() {
    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
    }
    g_ctxKey.clear();
}

static long long msSince(const std::chrono::steady_clock::time_point & t0) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
}

static void logError(const char * message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message);
    wrStr("[NATIVE] ERROR: ");
    wrStr(message);
    wrStr("\n");
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_imagen_chat_ia_DiffusionNative_nativeVersion(JNIEnv * env, jclass) {
    const char * v = sd_version();
    if (!v || !*v) v = sd_get_system_info();
    return env->NewStringUTF(v ? v : "stable-diffusion.cpp");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_imagen_chat_ia_DiffusionNative_nativeProgress(JNIEnv * env, jclass) {
    const std::string s = std::to_string(g_phase.load()) + "|" + std::to_string(g_step.load()) + "|" +
                          std::to_string(g_steps.load()) + "|" + std::to_string(g_last_step_ms.load());
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_imagen_chat_ia_DiffusionNative_nativeGenerateImage(
        JNIEnv * env, jclass,
        jstring jmodel, jstring jvae,
        jstring jprompt, jstring jnegative, jstring jout,
        jint width, jint height, jint steps, jlong seed,
        jfloat cfg, jfloat sharpness, jint sampler, jboolean keepLoaded, jboolean flashAttn, jint vaeTile) {
    try {
        const std::string model = toString(env, jmodel);
        const std::string vae = toString(env, jvae);
        const std::string prompt = toString(env, jprompt);
        const std::string negative = toString(env, jnegative);
        const std::string outPath = toString(env, jout);

        if (model.empty() || prompt.empty() || outPath.empty()) return JNI_FALSE;
        if (width <= 0 || height <= 0 || steps <= 0) return JNI_FALSE;

        PhaseGuard guard;
        const auto tTotal = std::chrono::steady_clock::now();
        const std::string key = model + "|" + vae + (flashAttn ? "|fa1" : "|fa0");
        sd_ctx_t * ctx = nullptr;
        bool reutilizado = false;
        {
            const auto tCtx = std::chrono::steady_clock::now();
            if (g_ctx && g_ctxKey == key) {
                ctx = g_ctx;
                reutilizado = true;
            } else {
                releaseCachedCtx();   // otro modelo (o ninguno): libera lo anterior antes de cargar
                ctx = createContext(model, vae, flashAttn == JNI_TRUE);
                if (ctx && keepLoaded) { g_ctx = ctx; g_ctxKey = key; }
            }
            if (!ctx) {
                logError("No se pudo crear el contexto de stable-diffusion.cpp");
                return JNI_FALSE;
            }
            nlogf("TIEMPO contexto=%lldms %s rss=%ldMB", msSince(tCtx),
                  reutilizado ? "(modelo reutilizado en memoria)" : "(modelo cargado desde disco)", currentRssMb());
        }

        sd_img_gen_params_t gen;
        sd_img_gen_params_init(&gen);
        gen.prompt = prompt.c_str();
        gen.negative_prompt = negative.empty() ? nullptr : negative.c_str();
        gen.width = width;
        gen.height = height;
        gen.sample_params.sample_steps = steps;
        if (sampler == 0) {
            gen.sample_params.sample_method = EULER_A_SAMPLE_METHOD;
            gen.sample_params.scheduler = KARRAS_SCHEDULER;
        } else if (sampler == 2) {
            // LCM (modelos LCM): 4-8 pasos y CFG ~1-2. Con CFG 1.0 se salta la pasada del prompt negativo.
            gen.sample_params.sample_method = LCM_SAMPLE_METHOD;
            gen.sample_params.scheduler = DISCRETE_SCHEDULER;
        } else {
            gen.sample_params.sample_method = EULER_SAMPLE_METHOD;
            gen.sample_params.scheduler = DISCRETE_SCHEDULER;
        }
        gen.sample_params.guidance.txt_cfg = (negative.empty() ? 1.0f : cfg);
        gen.seed = seed;
        gen.batch_count = 1;
        // Tamaño de mosaico del VAE (editable en la app): más grande = menos pasadas pero más RAM punta.
        // Si el mosaico cubre toda la imagen, se decodifica de una vez (sin mosaicos).
        const int tile = std::min(1024, std::max(64, static_cast<int>(vaeTile)));
        gen.vae_tiling_params.enabled = tile < std::max(width, height);
        gen.vae_tiling_params.temporal_tiling = false;
        gen.vae_tiling_params.tile_size_w = tile;
        gen.vae_tiling_params.tile_size_h = tile;
        gen.vae_tiling_params.target_overlap = 0.25f;

        nlogf("IMG start model=%s size=%dx%d steps=%d seed=%lld cfg=%.2f sharp=%.2f sampler=%d flash=%d vaeTile=%d rss=%ldMB",
              model.c_str(), width, height, steps, static_cast<long long>(seed),
              cfg, sharpness, sampler, flashAttn ? 1 : 0, static_cast<int>(vaeTile), currentRssMb());

        sd_image_t * images = nullptr;
        int count = 0;
        const auto tGen = std::chrono::steady_clock::now();
        const bool ok = generate_image(ctx, &gen, &images, &count);
        g_phase.store(3);
        nlogf("TIEMPO generate_image=%lldms (texto + muestreo + VAE) · pasos=%d · %dx%d", msSince(tGen), steps, width, height);
        nlogf("IMG generate returned=%d count=%d rss=%ldMB", ok ? 1 : 0, count, currentRssMb());

        bool wrote = false;
        if (ok && images && count > 0 && images[0].data) {
            const int w = static_cast<int>(images[0].width);
            const int h = static_cast<int>(images[0].height);
            const int c = static_cast<int>(images[0].channel);
            const size_t total = static_cast<size_t>(w) * h * c;

            const auto tPost = std::chrono::steady_clock::now();
            if (sharpness > 0.0f && c >= 3) {
                std::vector<unsigned char> copia(images[0].data, images[0].data + total);
                aplicarNitidez(copia, w, h, c, sharpness, 0.0f);
                std::memcpy(images[0].data, copia.data(), total);
                nlogf("IMG nitidez aplicada · amount=%.2f · %zu bytes", sharpness, total);
            } else if (sharpness > 0.0f) {
                nlogf("IMG nitidez omitida: la imagen no tiene 3+ canales (%d)", c);
            }

            wrote = writePngStreaming(outPath, images[0]);
            nlogf("IMG PNG directo terminado=%d rss=%ldMB", wrote ? 1 : 0, currentRssMb());
            nlogf("TIEMPO nitidez+PNG=%lldms", msSince(tPost));
        } else {
            nlogf("IMG sin salida utilizable: ok=%d count=%d", ok ? 1 : 0, count);
        }

        if (images) { free_sd_images(images, count); images = nullptr; }
        // Si algo falló, nunca se conserva el contexto: podría haber quedado en un estado dudoso.
        if (keepLoaded && ok && wrote && ctx == g_ctx) {
            nlogf("IMG modelo conservado en memoria para la siguiente imagen");
        } else {
            if (ctx == g_ctx) { g_ctx = nullptr; g_ctxKey.clear(); }
            free_sd_ctx(ctx);
        }
        nlogf("TIEMPO total=%lldms", msSince(tTotal));
        nlogf("IMG end wrote=%d rss=%ldMB", wrote ? 1 : 0, currentRssMb());
        return wrote ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception & e) {
        nlogf("EXCEPCION C++ generando imagen: %s", e.what());
        releaseCachedCtx();
    } catch (...) {
        nlogf("EXCEPCION C++ desconocida generando imagen");
        releaseCachedCtx();
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_imagen_chat_ia_DiffusionNative_nativeInstallLog(JNIEnv * env, jclass, jstring jpath) {
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
    installSignalHandlers();
    wrStr("[NATIVE] registro nativo activo (stdout/stderr + senales)\n");
}
