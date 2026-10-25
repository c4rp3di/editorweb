#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <unistd.h>
#include <unwind.h>

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"

namespace {
struct ChatProHandle {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    common_batch batch;
    common_chat_templates_ptr templates;
    common_sampler * sampler = nullptr;
    std::vector<common_chat_msg> history;
    llama_pos current_position = 0;
    uint32_t context_size = 4096;
    int32_t threads = 4;
    // true cuando el KV contiene tokens que el historial no refleja (razonamiento <think>, turno truncado o detenido).
    bool kv_dirty = false;
};

// Último estado de nativeGenerate, solo ASCII, para mostrarlo en el log de la app.
static std::string g_last_status = "sin_generaciones";

static std::string safeTail(const std::string & s, size_t n) {
    const size_t start = s.size() > n ? s.size() - n : 0;
    std::string out;
    for (size_t i = start; i < s.size(); ++i) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        if (c == '\n') out += "\\n";
        else if (c == '\r') out += "\\r";
        else if (c < 0x20 || c >= 0x80) out += '?';
        else out += static_cast<char>(c);
    }
    return out;
}

// jstring (UTF-16) -> UTF-8 estándar. GetStringUTFChars devuelve "modified UTF-8": los emojis (4 bytes) salen como
// dos secuencias de 3 bytes inválidas, y los logs/capturas adjuntos suelen llevarlos.
static std::string toString(JNIEnv * env, jstring value) {
    if (!value) return {};
    const jsize n = env->GetStringLength(value);
    const jchar * u = env->GetStringChars(value, nullptr);
    std::string out;
    if (!u) return out;
    out.reserve(static_cast<size_t>(n) * 2);
    for (jsize i = 0; i < n; ++i) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (static_cast<uint32_t>(u[i + 1]) - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD; // sustituto suelto
        }
        if (cp < 0x80) out += static_cast<char>(cp);
        else if (cp < 0x800) { out += static_cast<char>(0xC0 | (cp >> 6)); out += static_cast<char>(0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) { out += static_cast<char>(0xE0 | (cp >> 12)); out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F)); out += static_cast<char>(0x80 | (cp & 0x3F)); }
        else { out += static_cast<char>(0xF0 | (cp >> 18)); out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F)); out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F)); out += static_cast<char>(0x80 | (cp & 0x3F)); }
    }
    env->ReleaseStringChars(value, u);
    return out;
}

// ---- Registro nativo persistente (sobrevive a un cierre brusco) --------------------------------------
// stdout/stderr se redirigen a un archivo: así quedan también los logs de llama.cpp/ggml y, si la app muere
// por una señal (SIGSEGV, SIGABRT, SIGILL...), el informe con la pila. Lo absorbe DebugLog en el arranque siguiente.
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

// ---- Utilidades v19.3 ----------------------------------------------------------------------------

// Filtro del log de llama/ggml: solo avisos y errores (el resto eran cientos de líneas por generación).
static void llamaLogFiltrado(ggml_log_level level, const char * text, void *) {
    static bool last_ok = true;
    bool ok;
    if (level == GGML_LOG_LEVEL_CONT) ok = last_ok;
    else { ok = level >= GGML_LOG_LEVEL_WARN; last_ok = ok; }
    if (ok && text) fputs(text, stderr);
}

// Longitud del mayor prefijo que termina en frontera de carácter UTF-8 (un token puede cortar un carácter).
static size_t utf8CompletePrefix(const std::string & s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        const unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue; // byte de continuación
        const size_t need = c >= 0xF0 ? 4 : (c >= 0xE0 ? 3 : (c >= 0xC0 ? 2 : 1));
        return need > back ? n - back : n;
    }
    return n;
}

// UTF-8 -> jstring vía UTF-16 (NewStringUTF solo admite "modified UTF-8": rompe con emojis y bytes sueltos).
static jstring newJString(JNIEnv * env, const std::string & s) {
    std::u16string out;
    out.reserve(s.size());
    const size_t n = s.size();
    auto cont = [&](size_t k) { return k < n && (static_cast<unsigned char>(s[k]) & 0xC0) == 0x80; };
    size_t i = 0;
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        uint32_t cp = 0xFFFD;
        size_t len = 1;
        if (c < 0x80) {
            cp = c;
        } else if ((c >> 5) == 0x6 && cont(i + 1)) {
            cp = ((c & 0x1Fu) << 6) | (static_cast<unsigned char>(s[i + 1]) & 0x3Fu); len = 2;
            if (cp < 0x80) cp = 0xFFFD;
        } else if ((c >> 4) == 0xE && cont(i + 1) && cont(i + 2)) {
            cp = ((c & 0x0Fu) << 12) | ((static_cast<unsigned char>(s[i + 1]) & 0x3Fu) << 6) | (static_cast<unsigned char>(s[i + 2]) & 0x3Fu); len = 3;
            if (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF)) cp = 0xFFFD;
        } else if ((c >> 3) == 0x1E && cont(i + 1) && cont(i + 2) && cont(i + 3)) {
            cp = ((c & 0x07u) << 18) | ((static_cast<unsigned char>(s[i + 1]) & 0x3Fu) << 12)
               | ((static_cast<unsigned char>(s[i + 2]) & 0x3Fu) << 6) | (static_cast<unsigned char>(s[i + 3]) & 0x3Fu); len = 4;
            if (cp < 0x10000 || cp > 0x10FFFF) cp = 0xFFFD;
        }
        i += len;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(out.data()), static_cast<jsize>(out.size()));
}

// Separa el razonamiento <think>…</think> de la respuesta (DeepSeek-R1). Devuelve solo la respuesta:
//  - con </think>: lo que viene después; - <think> sin cerrar: "" (el modelo no llegó a responder); - sin etiquetas: el texto.
static std::string stripThink(const std::string & text, bool prompt_opens_think, bool * had_think, bool * unclosed) {
    const std::string full = prompt_opens_think ? ("<think>" + text) : text;
    static const std::string open = "<think>", close = "</think>";
    *had_think = false; *unclosed = false;
    const size_t c = full.rfind(close);
    if (c != std::string::npos) {
        *had_think = true;
        size_t start = c + close.size();
        while (start < full.size() && (full[start] == ' ' || full[start] == '\n' || full[start] == '\r' || full[start] == '\t')) ++start;
        return full.substr(start);
    }
    if (full.find(open) != std::string::npos) { *had_think = true; *unclosed = true; return std::string(); }
    return text;
}

// Algunas plantillas ya incluyen el BOS en el texto y la tokenización añade otro: se deja uno solo.
static void dedupBos(ChatProHandle * h, std::vector<llama_token> & tokens) {
    if (!h || !h->model || tokens.size() < 2) return;
    const llama_token bos = llama_vocab_bos(llama_model_get_vocab(h->model));
    if (bos >= 0 && tokens[0] == bos && tokens[1] == bos) tokens.erase(tokens.begin());
}

static bool decode(ChatProHandle * h, const std::vector<llama_token> & tokens, llama_pos start) {
    constexpr int32_t BATCH = 512;
    for (size_t offset = 0; offset < tokens.size(); offset += BATCH) {
        const int32_t count = static_cast<int32_t>(std::min<size_t>(BATCH, tokens.size() - offset));
        h->batch.clear();
        for (int32_t i = 0; i < count; ++i) {
            const llama_pos pos = start + static_cast<llama_pos>(offset + i);
            h->batch.add(tokens[offset + i], pos, 0, offset + i + 1 == tokens.size());
        }
        if (llama_process(h->context, LLAMA_PROCESS_TYPE_DECODE, h->batch.get()) != 0) return false;
    }
    return true;
}

static bool resetContext(ChatProHandle * h) {
    if (!h || !h->model) return false;
    if (h->context) {
        llama_free(h->context);
        h->context = nullptr;
    }
    llama_context_params ctx = llama_context_default_params();
    ctx.n_ctx = h->context_size;
    ctx.n_batch = 512;
    ctx.n_ubatch = 512;
    ctx.n_threads = h->threads;
    ctx.n_threads_batch = h->threads;
    h->context = llama_init_from_model(h->model, ctx);
    if (!h->context) return false;
    h->batch = common_batch(h->context);
    h->current_position = 0;
    common_sampler_reset(h->sampler);
    return true;
}

static void releaseHandle(ChatProHandle * h) {
    if (!h) return;
    if (h->sampler) common_sampler_free(h->sampler);
    h->history.clear();
    h->templates.reset();
    h->batch = common_batch();
    if (h->context) llama_free(h->context);
    if (h->model) llama_model_free(h->model);
    delete h;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ejemplo_chat_ia_llama_LlamaCppNative_nativeVersion(JNIEnv * env, jclass) {
    llama_log_set(llamaLogFiltrado, nullptr);
    llama_backend_init();
    const char * info = llama_print_system_info();
    return env->NewStringUTF(info ? info : "llama.cpp");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ejemplo_chat_ia_llama_LlamaCppNative_nativeLoadModel(
        JNIEnv * env, jclass, jstring jmodel_path, jint requested_context, jint requested_threads) {
    const std::string model_path = toString(env, jmodel_path);
    if (model_path.empty()) return 0;

    llama_log_set(llamaLogFiltrado, nullptr);
    llama_backend_init();
    auto * h = new ChatProHandle();

    llama_model_params model_params = llama_model_default_params();
    h->model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!h->model) { delete h; return 0; }

    llama_context_params ctx = llama_context_default_params();
    const int trained = llama_model_n_ctx_train(h->model);
    const int wanted = std::max(512, static_cast<int>(requested_context));
    ctx.n_ctx = trained > 0 ? std::min(wanted, trained) : wanted;
    h->context_size = ctx.n_ctx;
    ctx.n_batch = 512;
    ctx.n_ubatch = 512;

    const int cpu = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    const int threads = std::max(1, std::min(static_cast<int>(requested_threads), std::max(1, cpu)));
    ctx.n_threads = threads;
    ctx.n_threads_batch = threads;
    h->threads = threads;

    h->context = llama_init_from_model(h->model, ctx);
    if (!h->context) { releaseHandle(h); return 0; }

    h->batch = common_batch(h->context);
    h->templates = common_chat_templates_init(h->model, "");

    common_params_sampling sampling;
    // DeepSeek-R1-Distill recomienda temperatura 0.5-0.7; con 0.3 entraba en bucles de repetición.
    sampling.temp = 0.6f;
    sampling.top_p = 0.95f;
    sampling.penalty_repeat = 1.05f;
    sampling.penalty_last_n = 128;
    h->sampler = common_sampler_init(h->model, sampling);
    if (!h->sampler) { releaseHandle(h); return 0; }

    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ejemplo_chat_ia_llama_LlamaCppNative_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jstring jprompt, jint max_tokens, jobject listener) {
    g_last_status = "sin_handle_o_modelo";
    auto * h = reinterpret_cast<ChatProHandle *>(handle);
    if (!h || !h->model || !h->context || !h->sampler) return env->NewStringUTF("");

    const std::string prompt = toString(env, jprompt);
    if (prompt.empty()) { g_last_status = "prompt_vacio"; return env->NewStringUTF(""); }

    // Callback de streaming: boolean onToken(String). Devolver false detiene la generación.
    jmethodID on_token = nullptr;
    if (listener) {
        jclass lc = env->GetObjectClass(listener);
        on_token = env->GetMethodID(lc, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(lc);
        if (!on_token) { env->ExceptionClear(); }
    }

    common_chat_msg user;
    user.role = "user";
    user.content = prompt;

    const bool has_template = common_chat_templates_was_explicit(h->templates.get());
    const std::string plantilla = has_template ? "si" : "no";
    const int context_size = static_cast<int>(llama_n_ctx(h->context));

    // Camino normal: solo se procesa el turno nuevo sobre el KV existente.
    // Camino "reconstruir": el KV tiene razonamiento/turnos truncados que el historial no refleja, así que se
    // reinicia el contexto y se procesa el historial limpio (solo respuestas) + el turno nuevo.
    bool rebuild = has_template && h->kv_dirty;
    std::string formatted;
    std::vector<llama_token> tokens;
    llama_pos start_pos = h->current_position;

    auto build_delta = [&]() {
        formatted = has_template
            ? common_chat_format_single(h->templates.get(), h->history, user, true, true)
            : prompt;
        tokens = common_tokenize(h->context, formatted, has_template, has_template);
        dedupBos(h, tokens);
        start_pos = h->current_position;
    };
    auto build_rebuild = [&]() -> bool {
        std::vector<common_chat_msg> msgs = h->history;
        msgs.push_back(user);
        common_chat_templates_inputs inputs;
        inputs.messages = msgs;
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        formatted = common_chat_templates_apply(h->templates.get(), inputs).prompt;
        if (!resetContext(h)) return false;
        tokens = common_tokenize(h->context, formatted, true, true);
        dedupBos(h, tokens);
        start_pos = 0;
        return true;
    };

    if (rebuild) {
        if (!build_rebuild()) { g_last_status = "error_reiniciando_contexto"; return env->NewStringUTF(""); }
    } else {
        build_delta();
        // Si el turno no cabe, se intenta reconstruir con el historial limpio (sin razonamiento).
        if (has_template && !tokens.empty() && start_pos + static_cast<llama_pos>(tokens.size()) + 64 >= context_size) {
            rebuild = true;
            if (!build_rebuild()) { g_last_status = "error_reiniciando_contexto"; return env->NewStringUTF(""); }
        }
    }
    const std::string formatted_tail = safeTail(formatted, 240);
    if (tokens.empty()) {
        g_last_status = "tokens_vacios · plantilla=" + plantilla + " · formateado_bytes=" + std::to_string(formatted.size())
            + " · formateado_final=[" + formatted_tail + "]";
        return env->NewStringUTF("");
    }

    const int room = context_size - static_cast<int>(start_pos) - static_cast<int>(tokens.size()) - 16;
    if (room < 64) {
        g_last_status = "contexto_lleno · pos=" + std::to_string(start_pos) + "/" + std::to_string(context_size)
            + " · tokens_prompt=" + std::to_string(tokens.size());
        return newJString(env, "[Contexto lleno; inicia una nueva conversación para continuar.]");
    }
    const int limit = std::max(1, std::min(static_cast<int>(max_tokens), room));

    if (!decode(h, tokens, start_pos)) {
        h->kv_dirty = true;
        g_last_status = "error_decodificando_prompt · tokens_prompt=" + std::to_string(tokens.size())
            + " · pos=" + std::to_string(start_pos) + "/" + std::to_string(context_size);
        return env->NewStringUTF("");
    }
    h->current_position = start_pos + static_cast<llama_pos>(tokens.size());
    h->kv_dirty = false; // el KV vuelve a coincidir con el historial + este turno (se marcará de nuevo al terminar si procede)
    common_sampler_reset(h->sampler);

    // ¿La plantilla ya abre <think> en el prompt? (plantillas nuevas de R1: "<|Assistant|><think>\n")
    const std::string tail16 = formatted.size() > 16 ? formatted.substr(formatted.size() - 16) : formatted;
    const bool prompt_opens_think = tail16.find("<think>") != std::string::npos;

    std::string output;
    std::string pending;   // bytes generados aún no enviados (pueden ser medio carácter UTF-8)
    std::string parada = "limite_de_tokens";
    int generados = 0;
    bool seguir = true;

    auto emit = [&](const std::string & bytes) {
        if (bytes.empty() || !listener || !on_token) return;
        jstring js = newJString(env, bytes);
        const jboolean cont = env->CallBooleanMethod(listener, on_token, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) { env->ExceptionClear(); parada = "error_en_callback"; seguir = false; }
        else if (!cont) { parada = "detenido_por_el_usuario"; seguir = false; }
    };

    for (int i = 0; i < limit && seguir; ++i) {
        const llama_token token = common_sampler_sample(h->sampler, h->context, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(h->model), token)) { parada = "fin_de_secuencia(eog)"; break; }
        common_sampler_accept(h->sampler, token, true);

        const std::string piece = common_token_to_piece(h->context, token);
        output += piece;
        pending += piece;
        ++generados;

        const size_t k = utf8CompletePrefix(pending);
        if (k > 0) {
            emit(pending.substr(0, k));
            pending.erase(0, k);
            if (!seguir) break;
        }

        h->batch.clear();
        h->batch.add(token, h->current_position, 0, true);
        if (llama_process(h->context, LLAMA_PROCESS_TYPE_DECODE, h->batch.get()) != 0) { parada = "error_decodificando_token"; break; }
        ++h->current_position;
    }
    if (!pending.empty() && parada != "detenido_por_el_usuario") { emit(pending); } // resto (bytes incompletos -> U+FFFD)

    // Historial: solo la RESPUESTA (sin razonamiento). Un turno sin respuesta no entra en el historial.
    bool had_think = false, unclosed = false;
    const std::string answer = stripThink(output, prompt_opens_think, &had_think, &unclosed);
    const bool natural = parada.rfind("fin_de_secuencia", 0) == 0;
    if (!answer.empty()) {
        h->history.push_back(user);
        common_chat_msg assistant;
        assistant.role = "assistant";
        assistant.content = answer;
        h->history.push_back(std::move(assistant));
    }
    h->kv_dirty = has_template && (had_think || answer.empty() || !natural);

    g_last_status = "ok · plantilla=" + plantilla + " · tokens_prompt=" + std::to_string(tokens.size())
        + " · generados=" + std::to_string(generados) + " · parada=" + parada
        + " · pos=" + std::to_string(h->current_position) + "/" + std::to_string(context_size)
        + " · historial=" + std::to_string(h->history.size()) + " mensajes"
        + " · reconstruido=" + (rebuild ? "si" : "no") + " · think=" + (had_think ? (unclosed ? "sin_cerrar" : "cerrado") : "no")
        + " · respuesta_bytes=" + std::to_string(answer.size()) + " · kv_sucio=" + (h->kv_dirty ? "si" : "no")
        + " · formateado_final=[" + formatted_tail + "]";
    return newJString(env, output);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ejemplo_chat_ia_llama_LlamaCppNative_nativeRestoreHistory(
        JNIEnv * env, jclass, jlong handle, jobjectArray jroles, jobjectArray jcontents) {
    auto * h = reinterpret_cast<ChatProHandle *>(handle);
    if (!h || !h->model || !h->context || !h->sampler || !jroles || !jcontents) return JNI_FALSE;
    const jsize n = env->GetArrayLength(jroles);
    if (n != env->GetArrayLength(jcontents)) return JNI_FALSE;

    std::vector<common_chat_msg> restored;
    restored.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto roleObj = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
        auto contentObj = static_cast<jstring>(env->GetObjectArrayElement(jcontents, i));
        common_chat_msg msg;
        msg.role = toString(env, roleObj);
        msg.content = toString(env, contentObj);
        env->DeleteLocalRef(roleObj);
        env->DeleteLocalRef(contentObj);
        if (msg.role != "user" && msg.role != "assistant" && msg.role != "system") continue;
        if (msg.role == "assistant") {
            // Sin razonamiento; si no hay respuesta (pensamiento truncado/detenido) se descarta el turno entero.
            bool ht = false, uc = false;
            const std::string a = stripThink(msg.content, false, &ht, &uc);
            if (a.empty()) {
                if (!restored.empty() && restored.back().role == "user") restored.pop_back();
                continue;
            }
            msg.content = a;
        }
        restored.push_back(std::move(msg));
    }

    if (!resetContext(h)) {
        g_last_status = "error_reiniciando_contexto";
        return JNI_FALSE;
    }
    if (restored.empty()) {
        h->history.clear();
        h->kv_dirty = false;
        g_last_status = "historial_restaurado=0 · pos=0/" + std::to_string(h->context_size);
        return JNI_TRUE;
    }

    common_chat_templates_inputs inputs;
    inputs.messages = restored;
    inputs.add_generation_prompt = false;
    inputs.use_jinja = true;
    const std::string formatted = common_chat_templates_apply(h->templates.get(), inputs).prompt;
    auto tokens = common_tokenize(h->context, formatted, true, true);
    dedupBos(h, tokens);
    if (tokens.empty()) {
        g_last_status = "error_historial_tokens_vacios";
        return JNI_FALSE;
    }
    if (static_cast<llama_pos>(tokens.size()) >= static_cast<llama_pos>(h->context_size)) {
        g_last_status = "historial_demasiado_grande · tokens=" + std::to_string(tokens.size()) + "/" + std::to_string(h->context_size);
        return JNI_FALSE;
    }
    if (!decode(h, tokens, 0)) {
        g_last_status = "error_decodificando_historial · tokens=" + std::to_string(tokens.size());
        return JNI_FALSE;
    }
    h->current_position = static_cast<llama_pos>(tokens.size());
    h->history = std::move(restored);
    h->kv_dirty = false;
    g_last_status = "historial_restaurado=" + std::to_string(h->history.size()) + " mensajes · tokens=" + std::to_string(tokens.size())
        + " · pos=" + std::to_string(h->current_position) + "/" + std::to_string(h->context_size);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ejemplo_chat_ia_llama_LlamaCppNative_nativeLastStatus(JNIEnv * env, jclass) {
    return env->NewStringUTF(g_last_status.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_ejemplo_chat_ia_llama_LlamaCppNative_nativeRelease(JNIEnv *, jclass, jlong handle) {
    releaseHandle(reinterpret_cast<ChatProHandle *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_ejemplo_chat_ia_llama_LlamaCppNative_nativeInstallLog(JNIEnv * env, jclass, jstring jpath) {
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
