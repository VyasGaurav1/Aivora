# Offline reasoning backend

Pinned llama.cpp v0.8.0 commit8642541b255bfb5dc39cd351a178be09013a55d6 (MIT), minimal unmodified upstream runtime source. CPU-only NDK28.2/CMake3.22 across all existing default Android ABIs, API30 minimum. Disable tools/server/common/OpenSSL/RPC/download features, kernel fetches, host-native/AVX/FMA and llamafile. No installation or phone-model filter.

Pinned ggml-org/Qwen3-0.6B-GGUF revision13ac06624948e4d8589cb4b6f90100837342fcb8 Q4_0:428970080bytes, SHA256 da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4. Apache2 base Qwen/Qwen3-0.6B. Bundle after build-time hash verification; no runtime downloader. Larger official Q8 candidate is retained only in build/model-candidates and not packaged. Licenses/provenance retained.

Replaceable LocalReasoningEngine initially answers conversational questions only. Deterministic commands/memory stay first; generated text never becomes an action or execution authority. Grammar limits output to one JSON reply object; malformed/incomplete output refuses. UI labels answers unverified with no phone action performed. No memory/history is passed to the model initially, so owner-fact invention must be refused. Structured action planning and RAG remain required future work.

Serialize native work and hold ownership until native cancellation completes. Cancellation IDs, model progress/decode abort callbacks and a60second deadline bound work; context1024/output192tokens/question1600chars/threads4/output4096bytes. Check RAM/storage, copy/hash fixed private model file for mmap, unload model/context/sampler after every return/failure. No startup or idle model. Native constrained inference, cancellation and resource behavior require actual tests; mocks do not prove model operation. Physical quality/speed/battery/OEM and full reasoning acceptance remain unfinished.

The backend uses [llama.cpp v0.8.0](https://github.com/ggml-org/llama.cpp/tree/8642541b255bfb5dc39cd351a178be09013a55d6) and the [pinned Qwen3 Q4_0 artifact](https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF/tree/13ac06624948e4d8589cb4b6f90100837342fcb8). Build verification checks the exact model size and SHA-256 before packaging. Provenance and licenses are packaged beside the asset. Settings exposes an Offline question answers toggle. The first question needs private storage for the verified model copy. Devices with less than 768 MiB available memory use the command path and receive an availability response for model questions.

The explicit LocalReasoningProbe runs real bundled inference, checks the answer to two plus two, cancels during native decoding, observes idle native state, then verifies another answer. It also checks the installed application permissions for the absence of INTERNET. Broader factual accuracy and physical device performance still require testing.

After a successful offline answer, say `explain more`, `make it simpler`, or `give me an example`. These phrases reuse the original question for two minutes. The topic lives only in the current session and is cleared on cancellation or an intervening command. The follow-up asks for a new explanation of the topic.

The vendored subset omits cpp-httplib, which is only needed by the disabled llama.cpp common build. The Android JNI runtime builds without it. Preserve this omission when refreshing the upstream snapshot.
- The starter build omits the reasoning assets. To include the preserved optional model from app/src/localReasoning/assets, build with -PaivoraIncludeLocalReasoning=true.
