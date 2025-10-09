# Local LLM Android Auto

**Status:** 🚧 In Development
**Goal:** Local voice assistant for Android Auto using on-device LLMs
**Tech:** Whisper (STT), Llama 3.2 (LLM), Kokoro/Pico (TTS), Android Auto APIs

---

## 🎯 Project Overview

Privacy-first voice assistant for Android Auto that runs **100% locally** - no cloud, no data transmission.

### Key Features (Planned)
- 🎤 Speech-to-Text with Whisper Tiny (39MB local)
- 🤖 LLM processing with Llama 3.2 1B (mobile-optimized)
- 🔊 Text-to-Speech with Kokoro 82M or Pico TTS
- 🚗 Android Auto integration for car controls
- 🔒 Complete privacy - all processing on-device

---

## 🛠️ Tech Stack

### GenAI Components
- **STT:** Whisper Tiny (TensorFlow Lite)
- **LLM:** Llama 3.2 1B via picoLLM SDK
- **TTS:** Kokoro 82M or Android Pico TTS
- **Framework:** Framework-agnostic (raw models for flexibility)

### MLOps Components
- **Model Optimization:** INT8/FP16 quantization for mobile
- **Deployment:** TensorFlow Lite + ONNX Runtime
- **Performance:** Battery optimization, inference profiling
- **Monitoring:** On-device metrics, crash reporting

### Android
- **Language:** Kotlin (primary), Java
- **Architecture:** MVVM + Clean Architecture
- **APIs:** Android Auto Voice Interaction, CarPropertyManager
- **Base:** Fork of Dicio Android for voice handling

---

## 📁 Project Structure

```
local-llm-android-auto/
├── app/                    # Android application
│   ├── src/main/
│   │   ├── kotlin/
│   │   │   ├── stt/       # Speech-to-Text module
│   │   │   ├── llm/       # LLM inference module
│   │   │   ├── tts/       # Text-to-Speech module
│   │   │   ├── auto/      # Android Auto integration
│   │   │   └── commands/  # Voice command handlers
│   │   └── assets/
│   │       └── models/    # ML models (quantized)
├── ml/                     # ML model preparation (Python)
│   ├── whisper/           # Whisper model testing & quantization
│   ├── llama/             # Llama model prep & optimization
│   ├── tts/               # TTS model testing
│   └── notebooks/         # Jupyter notebooks for experiments
├── docs/                   # Documentation
│   ├── architecture.md    # System architecture
│   ├── mlops.md          # MLOps considerations
│   └── android-auto.md   # Android Auto integration guide
└── scripts/               # Build & deployment scripts
```

---

## 🚀 Development Phases

### Phase 1: ML Pipeline (Python) - **CURRENT**
- [ ] Test Whisper Tiny locally
- [ ] Test Llama 3.2 1B locally
- [ ] Test TTS options (Kokoro vs Pico)
- [ ] Quantize models for mobile (INT8)
- [ ] Benchmark inference performance

### Phase 2: Android App Foundation
- [ ] Fork Dicio Android
- [ ] Set up Kotlin project structure
- [ ] Integrate TFLite for Whisper
- [ ] Integrate picoLLM SDK for Llama
- [ ] Add TTS integration

### Phase 3: Android Auto Integration
- [ ] Android Auto manifest & permissions
- [ ] Voice Interaction Session
- [ ] CarPropertyManager for vehicle controls
- [ ] Car-optimized UI

### Phase 4: MLOps & Optimization
- [ ] Battery usage optimization
- [ ] Inference profiling & optimization
- [ ] Model versioning strategy
- [ ] Performance monitoring

---

## 🧪 ML Experimentation (Start Here)

### Setup Python Environment
```bash
cd ml
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### Test Models Locally
```bash
# Test Whisper STT
python whisper/test_whisper.py

# Test Llama 3.2
python llama/test_llama.py

# Test TTS
python tts/test_tts.py
```

---

## 📊 GenAI + MLOps Focus

### Why This Project Showcases Both:

**GenAI:**
- LLM integration (Llama 3.2)
- Prompt engineering for car commands
- Multi-modal AI (speech → text → LLM → speech)
- Framework-agnostic approach (raw models)

**MLOps:**
- Edge computing / mobile deployment
- Model quantization (INT8/FP16)
- Performance optimization (latency, battery)
- On-device inference pipeline
- Model versioning & updates

---

## 🎯 Portfolio Value

This project demonstrates:
- ✅ GenAI expertise (LLM, STT, TTS integration)
- ✅ MLOps expertise (edge deployment, optimization)
- ✅ Android development (Kotlin, Android Auto)
- ✅ FROM SCRATCH approach (with strategic forking)
- ✅ Framework-agnostic engineering
- ✅ Production considerations (battery, performance)

**Target companies:** Microsoft, N26, Datadog, product companies with AI/ML teams

---

## 📝 Development Log

**2025-10-09:** Project initialized, renamed to `local-llm-android-auto`
- Starting with ML pipeline in Python (test models locally first)
- Android integration comes after ML pipeline is validated

---

## 🔗 Resources

- [Dicio Android](https://github.com/Stypox/dicio-android) - FOSS voice assistant
- [Whisper Android](https://github.com/vilassn/whisper_android) - TFLite Whisper
- [picoLLM](https://picovoice.ai/blog/local-llm-for-mobile-run-llama-2-and-llama-3-on-android/) - Mobile LLM SDK
- [Llama 3.2](https://ai.meta.com/blog/llama-3-2-connect-2024-vision-edge-mobile-devices/) - Mobile-optimized models

---

## 📧 Contact

**Ignacio Loyola Delgado**
GenAI & MLOps Engineer
[ignacio.tech](https://ignacio.tech) | hi@ignacio.tech

---

*Built with privacy-first principles. All processing happens on-device. Zero data transmission.*
