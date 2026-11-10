# Third-party dependencies

- Local summary runtime: llama.cpp v0.8.0, commit 8642541b255bfb5dc39cd351a178be09013a55d6, MIT. Separately provisioned ggml-org/Qwen3-0.6B-GGUF Q4_0 at revision 13ac06624948e4d8589cb4b6f90100837342fcb8, Apache License 2.0 (base Qwen/Qwen3-0.6B); exact size/hash and licenses are retained in app/src/main/assets/model-notices. Model weights remain excluded from the standard APK.

- OkHttp 4.12.0 and transitive Okio: Square contributors, Apache License 2.0. Used for bounded HTTPS and secure WebSocket speech transport.
- Gnani Prisma v3.0 and Timbre v3.0 are hosted services accessed with an owner-supplied credential; their model weights and Python SDK are not bundled. Integration follows https://docs.gnani.ai/api/STT/stt-websocket and https://docs.gnani.ai/api/TTS/tts-inference. Provider-supported language and voice catalogs were checked on 5 November 2026.

- AndroidX Activity 1.10.1 and Compose Material3 1.3.0 (including transitive AndroidX dependencies): Android Open Source Project, Apache License 2.0.
- Kotlin/Compose compiler plugin 2.2.10: JetBrains, Apache License 2.0 (build tooling).
- Android Gradle Plugin 9.1.1: Android Open Source Project, Apache License 2.0 (build tooling).
- Gradle 9.3.1: Gradle contributors, Apache License 2.0 (build tooling).
- JUnit 4.13.2: JUnit contributors, Eclipse Public License 1.0 (tests only).
- AndroidX Room 2.8.4, DataStore 1.1.7, Lifecycle 2.8.7, Test Runner 1.7.0 and Test JUnit 1.3.0: Apache License 2.0.
- Kotlin Coroutines 1.10.2: JetBrains, Apache License 2.0.
- KSP 2.3.5: Google, Apache License 2.0 (build tooling).
- [sherpa-onnx 2.0.1](https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1): Xiaomi / k2-fsa contributors, Apache License 2.0; bundled static-ONNX-runtime Android AAR, SHA-256 `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575`.
- ONNX Runtime, included in the native speech runtime: Microsoft, MIT License. Full native transitive notices must be audited before distribution.
- [English streaming Zipformer 20M](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17), revision `d42f2d9f7ca24806fb667456a18a9f1b60f70d16`: Apache License 2.0 per model card. Bundled encoder int8, decoder, joiner, tokens and a test-only sample WAV. Exported from desh2608/icefall-asr-librispeech-pruned-transducer-stateless7-streaming-small.

- [VITS VCTK int8](https://huggingface.co/csukuangfj/vits-vctk/tree/5d7d647d6ea0f6206544735d74b25d3877c0f9ca), revision `5d7d647d6ea0f6206544735d74b25d3877c0f9ca`: Apache License 2.0 per model card. Upstream neural voice checkpoint is from jaywalnut310/vits; training corpus CSTR VCTK requires attribution to its authors. The bundled pronunciation dictionary derives from the CMU Pronouncing Dictionary / CMU-in-IPA conversion credited in the model README; retain their notices before distribution. `lexicon-aivora.txt` is derived from the unchanged upstream lexicon with one locally authored Aivora pronunciation entry.
- [3D-Speaker ERes2Net English](https://modelscope.cn/models/iic/speech_eres2net_sv_en_voxceleb_16k): Alibaba DAMO Academy / ModelScope contributors, Apache License 2.0 per official model metadata. ONNX export supplied by k2-fsa/sherpa-onnx speaker-recongition-models release; SHA-256 and source in docs/MODELS.lock.json.
- Speaker test WAVs are upstream k2-fsa demonstration clips, included only in the instrumentation test APK and credited to the upstream release. They are not bundled in the application APK.

Speech inference is isolated behind SpeechToTextEngine, TextToSpeechEngine and SpeakerVerifier and runs locally on CPU. There is no network client. Complete native transitive and data/dictionary license notices remain required before distribution.
- ZXing Android Embedded 4.3.0 and ZXing Core 3.4.1, Apache License 2.0. Local scanner AAR SHA-256 4ab03353127c34e55cbb80fbc9a34031decbcfb1ec5e8f991944d2d494621a33. Core JAR SHA-256 acfa30338c26267b6cab665509c2251bb96e8350bfcad89d59b949ba77011f8d.
- English GigaSpeech Zipformer 3.3M keyword-spotting model (2024-01-01), k2-fsa/sherpa-onnx kws-models release: Apache License 2.0 per bundled model README. Only int8 encoder/joiner, decoder and token assets retained. Source and exact file hashes are recorded in docs/WAKE_MODELS.lock.json; wake keyword entries are locally configured. Indian English speech uses an installed Android text-to-speech voice when available and does not bundle that engine or its voice data.
