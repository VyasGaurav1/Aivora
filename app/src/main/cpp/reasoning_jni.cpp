#include <jni.h>
#include <algorithm>
#include "llama.h"
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::once_flag initialized;
std::atomic<int64_t> canceled_id{0};
std::atomic<int> phase{0};
struct PhaseScope { PhaseScope() { phase.store(1); } ~PhaseScope() { phase.store(0); } };
struct Request {
    int64_t id;
    std::chrono::steady_clock::time_point deadline;
};
bool aborted(void * data) {
    const auto & request = *static_cast<Request *>(data);
    return canceled_id.load() == request.id || std::chrono::steady_clock::now() >= request.deadline;
}
bool progress(float, void * data) { return !aborted(data); }
void quiet_log(ggml_log_level, const char *, void *) {}
const char * grammar = R"GRAMMAR(
root ::= "{" ws "\"reply\"" ws ":" ws string ws "}" ws
string ::= "\"" char* "\""
char ::= [^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])
ws ::= [ \t\n\r]*
)GRAMMAR";
}

extern "C" JNIEXPORT void JNICALL
Java_com_chronoit_aivora_brain_NativeReasoningBridge_cancel(JNIEnv *, jobject, jlong id) {
    canceled_id.store(id);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_chronoit_aivora_brain_NativeReasoningBridge_phase(JNIEnv *, jobject) { return phase.load(); }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_chronoit_aivora_brain_NativeReasoningBridge_generate(JNIEnv * env, jobject, jstring path, jbyteArray input, jint limit, jint threads, jlong id) {
    const char * timeout_message = "Local model loading timed out";
    Request request{id, std::chrono::steady_clock::now() + std::chrono::seconds(60)};
    try {
        PhaseScope active;
        if (aborted(&request)) throw std::runtime_error("Reasoning canceled");
        std::call_once(initialized, [] { llama_log_set(quiet_log, nullptr); llama_backend_init(); });
        const char * chars = env->GetStringUTFChars(path, nullptr);
        if (!chars) return nullptr;
        std::string file(chars);
        env->ReleaseStringUTFChars(path, chars);
        const auto size = env->GetArrayLength(input);
        if (size < 1 || size > 16000 || limit < 1 || limit > 192 || threads < 1 || threads > 4) throw std::runtime_error("Invalid reasoning request");
        std::string prompt(size, '\0');
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(prompt.data()));
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.use_extra_bufts = false;
        mp.progress_callback = progress;
        mp.progress_callback_user_data = &request;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(file.c_str(), mp), llama_model_free);
        if (!model || aborted(&request)) throw std::runtime_error("Local model could not load");
        auto cp = llama_context_default_params();
        cp.n_ctx = 1024; cp.n_batch = 256; cp.n_ubatch = 256; cp.n_outputs_max = 1;
        cp.n_threads = threads; cp.n_threads_batch = threads;
        cp.abort_callback = aborted; cp.abort_callback_data = &request;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), cp), llama_free);
        if (!context) throw std::runtime_error("Local context unavailable");
        const auto * vocab = llama_model_get_vocab(model.get());
        std::vector<llama_token> tokens(1024);
        const int count = llama_tokenize(vocab, prompt.data(), size, tokens.data(), tokens.size(), true, true);
        if (count < 1 || count + limit > 1024) throw std::runtime_error("Question exceeds local context");
        timeout_message = "Local prompt processing timed out";
        phase.store(2);
        for (int offset = 0; offset < count; offset += 256) {
            if (aborted(&request) || llama_decode(context.get(), llama_batch_get_one(tokens.data() + offset, std::min(256, count - offset))) != 0)
                throw std::runtime_error("Local prompt decoding stopped");
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto * constrained = llama_sampler_init_grammar(vocab, grammar, "root");
        if (!constrained) throw std::runtime_error("Structured output unavailable");
        llama_sampler_chain_add(sampler.get(), constrained);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        std::string output;
        timeout_message = "Local generation timed out";
        phase.store(3);
        bool complete = false;
        for (int i = 0; i < limit && !aborted(&request); ++i) {
            llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { complete = true; break; }
            std::vector<char> piece(256);
            int length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (length < 0) { piece.resize(-length); length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (length < 0) throw std::runtime_error("Invalid generated token");
            output.append(piece.data(), length);
            if (output.size() > 4096) throw std::runtime_error("Local answer reached the token limit");
            if (llama_decode(context.get(), llama_batch_get_one(&token, 1)) != 0) {
                if (std::chrono::steady_clock::now() >= request.deadline) throw std::runtime_error("Local generation timed out");
                throw std::runtime_error("Local answer decoding stopped");
            }
        }
        if (canceled_id.load() == request.id) throw std::runtime_error("Local answer canceled");
        if (std::chrono::steady_clock::now() >= request.deadline) throw std::runtime_error("Local generation timed out");
        if (!complete) throw std::runtime_error("Local answer reached the token limit");
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception & error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), std::chrono::steady_clock::now() >= request.deadline ? timeout_message : error.what());
        return nullptr;
    }
}
