# EdgeMind

**On-Device AI Assistant for Android**

**Status:** Functional - Chat with Phi-3 mini working
**Current:** Edge LLM inference with Phi-3 mini (3.8B INT4)
**Tech:** ONNX Runtime, NNAPI Acceleration, Custom BPE Tokenizer

---

## Project Overview

Privacy-first AI assistant running **100% locally** on Android devices - no cloud, no data transmission.

### Current Features
- **Phi-3 mini 3.8B** (INT4 quantized to 2.6GB)
- **NNAPI Hardware Acceleration** (NPU/TPU/DSP)
- **KV Cache** for fast text generation (~120-200ms/token)
- **Custom SentencePiece BPE Tokenizer** (32k vocab)
- **Streaming Chat** with real-time token generation
- **Clean Architecture** (MVVM + Domain/Data layers)
- **Smart Stopping** (n-gram detection, sentence completion)

---

## Tech Stack

### AI/ML
- **Model:** Phi-3 mini 3.8B (INT4 quantized, 2.6GB)
- **Runtime:** ONNX Runtime 1.19.2 with IR version 7
- **Acceleration:** NNAPI (NPU/TPU/DSP), CPU fallback
- **Tokenizer:** Custom SentencePiece BPE implementation (32,064 vocab)
- **Optimization:** KV cache for O(n) generation, memory-mapped loading

### Android
- **Language:** Kotlin 1.9.20
- **Min SDK:** 26 (Android 8.0) / Target SDK: 34 (Android 14)
- **Architecture:** Clean Architecture (Domain/Data/Presentation)
- **Pattern:** MVVM with StateFlow
- **DI:** Hilt 2.48
- **UI:** Jetpack Compose + Material 3
- **Async:** Coroutines + Flow for streaming

---

## Project Structure

```
edgemind/
├── android/                         # Android application
│   ├── app/src/main/kotlin/com/localai/assistant/
│   │   ├── domain/                 # Business logic (Clean Architecture)
│   │   │   ├── model/              # Domain models
│   │   │   ├── repository/         # Repository interfaces
│   │   │   └── usecase/            # Use cases
│   │   ├── data/                   # Data layer
│   │   │   ├── local/              # Local data sources
│   │   │   │   ├── ONNXModelWrapper.kt      # ONNX Runtime wrapper
│   │   │   │   └── Phi3BPETokenizer.kt      # SentencePiece tokenizer
│   │   │   └── repository/         # Repository implementations
│   │   │       └── ModelRepositoryImpl.kt   # Main inference logic
│   │   ├── presentation/           # UI layer (MVVM)
│   │   │   └── chat/               # Chat screen with Compose
│   │   └── di/                     # Hilt dependency injection
│   └── app/src/main/assets/
│       └── tokenizer.json          # Phi-3 tokenizer config (3.5MB)
├── ml/                             # Model experiments & preparation
│   └── granite/                    # Model testing & conversion
└── INFERENCE_FINDINGS.md           # Performance analysis & debugging
```

---

## Development Status

### Phase 1: Core Inference (COMPLETED)
- Phi-3 mini INT4 model integration (2.6GB)
- ONNX Runtime 1.19.2 with NNAPI acceleration
- Custom SentencePiece BPE tokenizer (32k vocab)
- KV cache implementation (O(n) generation)
- Streaming chat with real-time tokens
- Memory-mapped model loading
- Clean Architecture (Domain/Data/Presentation)
- Smart stopping criteria (n-gram detection)

### Phase 2: Optimization (IN PROGRESS)
- NNAPI NPU acceleration (~120-200ms/token)
- KV cache memory management
- Repetition detection & early stopping
- Temperature/top-p sampling (currently greedy)
- Prompt caching for faster repeated queries
- Battery optimization profiling

### Phase 3: Features (PLANNED)
- [ ] Conversation history persistence (Room DB)
- [ ] Multiple chat sessions
- [ ] System prompt customization
- [ ] Model parameter tuning (temperature, top-p)
- [ ] Export/import conversations
- [ ] Settings UI for performance tuning

### Phase 4: Android Auto (FUTURE)
- [ ] Android Auto manifest & permissions
- [ ] Voice Interaction Session
- [ ] Speech-to-Text integration (Whisper)
- [ ] Text-to-Speech integration
- [ ] Car-optimized UI

---

## Building & Running

### Prerequisites
1. **Phi-3 Model:** Download Phi-3 mini INT4 ONNX model (2.6GB)
   ```bash
   # Download from HuggingFace
   wget https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-onnx/resolve/main/cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4/phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx

   # Push to device
   adb push phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx /sdcard/Android/data/com.localai.assistant/files/models/
   ```

2. **Android Studio:** Arctic Fox or newer with Kotlin 1.9.20

### Build & Install
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Development
```bash
# Run tests
./gradlew test

# Check logs
adb logcat | grep -i "localai\|onnx\|phi3"
```

---

## Technical Highlights

### GenAI Expertise
- **LLM Integration:** Phi-3 mini (3.8B parameters) on Android
- **Custom Tokenizer:** Implemented SentencePiece BPE from scratch (32k vocab, 61k merges)
- **Streaming Generation:** Real-time token emission via Kotlin Flow
- **Prompt Engineering:** Chat templates with system/user/assistant roles
- **Smart Stopping:** N-gram repetition detection, sentence completion

### MLOps Expertise
- **Edge Deployment:** 100% on-device inference with no cloud
- **Hardware Acceleration:** NNAPI (NPU/TPU/DSP) integration
- **Performance Optimization:** KV cache reduces complexity from O(n²) to O(n)
- **Memory Management:** Memory-mapped loading, cache cleanup, OOM prevention
- **Quantization:** INT4 model (3.8B params in 2.6GB)
- **Monitoring:** Timber logging, inference profiling, crash analysis

### Software Engineering
- **Clean Architecture:** Domain/Data/Presentation separation
- **MVVM Pattern:** ViewModel + StateFlow for reactive UI
- **Dependency Injection:** Hilt with proper scoping
- **Async Programming:** Coroutines + Flow for streaming
- **Error Handling:** Result types, graceful fallbacks
- **Testing Infrastructure:** Unit tests with MockK/Truth/Turbine

---

## Portfolio Value

**What This Project Demonstrates:**

1. **Deep AI/ML Understanding**
   - Built tokenizer from scratch (not just using libraries)
   - Implemented KV caching manually
   - Debugged model outputs, logits, attention masks
   - Optimized inference pipeline from 5s+ to 120ms per token

2. **Production-Grade Engineering**
   - Clean Architecture (testable, maintainable, scalable)
   - Memory leak debugging and fixes
   - Performance profiling and optimization
   - Real-world constraints (mobile hardware, battery life)

3. **Problem-Solving Skills**
   - Diagnosed tokenizer mismatch (99 vs 32k vocab)
   - Identified and fixed KV cache memory leak
   - Implemented repetition detection for quality
   - Iterative debugging with logcat analysis

4. **Full-Stack Mobile Development**
   - Kotlin expertise (coroutines, flows, extension functions)
   - Jetpack Compose UI
   - ONNX Runtime integration
   - Android hardware acceleration (NNAPI)

**Ideal For:** Senior AI Engineer, ML Engineer, or Android ML Engineer roles at companies building edge AI products.

---

## Performance Benchmarks

**Device:** Samsung Galaxy S22 (Exynos 2200)
**Acceleration:** NNAPI (NPU)
**Model:** Phi-3 mini INT4 (2.6GB)

### Generation Speed
- **First token:** ~200ms (with cache initialization)
- **Subsequent tokens:** 120-200ms (KV cache reuse)
- **Avg speed:** ~5-8 tokens/second
- **Context window:** 4096 tokens

### Memory Usage
- **Model loading:** 2.6GB (memory-mapped, not in RAM)
- **Runtime memory:** ~500MB (includes KV cache)
- **KV cache size:** 64 tensors (32 layers × 2)

### Before vs After Optimization
- **Without KV cache:** 800ms → 5s+ (exponential slowdown)
- **With KV cache:** 120-200ms (consistent)
- **Speedup:** 6x faster generation

---

## Documentation

See `INFERENCE_FINDINGS.md` for detailed implementation notes, debugging process, and performance analysis.

---

## Resources

- [Phi-3 Model](https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-onnx) - Microsoft's small language model
- [ONNX Runtime](https://onnxruntime.ai/) - Cross-platform inference engine
- [NNAPI](https://developer.android.com/ndk/guides/neuralnetworks) - Android Neural Networks API

---

## License

MIT License - See LICENSE file for details

---

## Author

**Ignacio Loyola Delgado**
GenAI & MLOps Engineer
[ignacio.tech](https://ignacio.tech) | hi@ignacio.tech

---

*Privacy-first design. All processing happens on-device. Zero data transmission.*
