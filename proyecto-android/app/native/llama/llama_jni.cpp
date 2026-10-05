#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <string>
#include <vector>
#include <unistd.h>

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
};

static std::string toString(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
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
    llama_backend_init();
    const char * info = llama_print_system_info();
    return env->NewStringUTF(info ? info : "llama.cpp");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ejemplo_chat_ia_llama_LlamaCppNative_nativeLoadModel(
        JNIEnv * env, jclass, jstring jmodel_path, jint requested_context, jint requested_threads) {
    const std::string model_path = toString(env, jmodel_path);
    if (model_path.empty()) return 0;

    llama_backend_init();
    auto * h = new ChatProHandle();

    llama_model_params model_params = llama_model_default_params();
    h->model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!h->model) { delete h; return 0; }

    llama_context_params ctx = llama_context_default_params();
    const int trained = llama_model_n_ctx_train(h->model);
    const int wanted = std::max(512, static_cast<int>(requested_context));
    ctx.n_ctx = trained > 0 ? std::min(wanted, trained) : wanted;
    ctx.n_batch = 512;
    ctx.n_ubatch = 512;

    const int cpu = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    const int threads = std::max(1, std::min(static_cast<int>(requested_threads), std::max(1, cpu)));
    ctx.n_threads = threads;
    ctx.n_threads_batch = threads;

    h->context = llama_init_from_model(h->model, ctx);
    if (!h->context) { releaseHandle(h); return 0; }

    h->batch = common_batch(h->context);
    h->templates = common_chat_templates_init(h->model, "");

    common_params_sampling sampling;
    sampling.temp = 0.3f;
    h->sampler = common_sampler_init(h->model, sampling);
    if (!h->sampler) { releaseHandle(h); return 0; }

    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ejemplo_chat_ia_llama_LlamaCppNative_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jstring jprompt, jint max_tokens) {
    auto * h = reinterpret_cast<ChatProHandle *>(handle);
    if (!h || !h->model || !h->context || !h->sampler) return env->NewStringUTF("");

    const std::string prompt = toString(env, jprompt);
    if (prompt.empty()) return env->NewStringUTF("");

    common_chat_msg user;
    user.role = "user";
    user.content = prompt;

    const bool has_template = common_chat_templates_was_explicit(h->templates.get());
    const std::string formatted = has_template
        ? common_chat_format_single(h->templates.get(), h->history, user, true, false)
        : prompt;

    const auto tokens = common_tokenize(h->context, formatted, has_template, has_template);
    if (tokens.empty()) return env->NewStringUTF("");

    const int limit = std::max(1, static_cast<int>(max_tokens));
    const int context_size = static_cast<int>(llama_n_ctx(h->context));
    if (h->current_position + static_cast<llama_pos>(tokens.size()) + limit >= context_size) {
        return env->NewStringUTF("[Contexto lleno; inicia una nueva conversación para continuar.]");
    }

    if (!decode(h, tokens, h->current_position)) {
        return env->NewStringUTF("");
    }
    h->current_position += static_cast<llama_pos>(tokens.size());
    common_sampler_reset(h->sampler);

    std::string output;
    for (int i = 0; i < limit; ++i) {
        const llama_token token = common_sampler_sample(h->sampler, h->context, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(h->model), token)) break;
        common_sampler_accept(h->sampler, token, true);

        output += common_token_to_piece(h->context, token);
        h->batch.clear();
        h->batch.add(token, h->current_position, 0, true);
        if (llama_process(h->context, LLAMA_PROCESS_TYPE_DECODE, h->batch.get()) != 0) break;
        ++h->current_position;
    }

    h->history.push_back(user);
    if (!output.empty()) {
        common_chat_msg assistant;
        assistant.role = "assistant";
        assistant.content = output;
        h->history.push_back(std::move(assistant));
    }
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_ejemplo_chat_ia_llama_LlamaCppNative_nativeRelease(JNIEnv *, jclass, jlong handle) {
    releaseHandle(reinterpret_cast<ChatProHandle *>(handle));
}
