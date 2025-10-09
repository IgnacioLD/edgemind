# Local AI Assistant - Project Plan

**Project:** Privacy-First On-Device AI Assistant for Android
**Timeline:** 3 weeks to MVP
**Status:** Week 1 - Model Validation Phase

---

## Project Vision

Build a **100% offline, privacy-first AI assistant** that runs entirely on-device. No cloud APIs, no data collection, complete user control. Handles document understanding, general Q&A, and voice interaction using locally-run LLMs optimized for mobile deployment.

### Core Principles
- **Privacy First:** All processing on-device, zero telemetry
- **Offline Capable:** Full functionality without internet
- **Production Quality:** Real-world usable, not just a demo
- **Open Architecture:** Framework-agnostic, reproducible

---

## Strategic Pivot (Oct 9, 2025)

**Original Scope:** Android Auto document assistant (narrow use case)
**Updated Scope:** General-purpose local AI assistant (broader market appeal)

**Why Pivot:**
- Broader portfolio story (more impressive)
- Better real-world utility (daily use vs car-only)
- More challenging technically (better showcase)
- Market relevance (privacy concerns, offline AI trend)

**Impact on Timeline:** Minimal (same core tech, expanded use cases)

---

## Technical Architecture

### Phase 1: Model Architecture Decision

**Current Status:**
- ✅ Granite Docling 258M validated (INT4, 358 MB)
- ✅ Excellent for document understanding & vision
- ❓ Unknown: General conversation capability

### Option A: Single Model (Simpler)
**Use Granite Docling for everything:**
- Document processing ✅
- General Q&A ❓
- Vision tasks ✅
- Conversation ❓

**Decision Pending:** Comprehensive benchmark needed

### Option B: Multi-Model Specialists (Recommended)
**Specialist architecture - best model per task:**

```
User Input
    ↓
[Intent Router - 50-100MB]
    ↓
    ├─→ Document/Vision → [Granite Docling INT4 - 358MB]
    ├─→ General Q&A → [Phi-3-mini/Llama 3.2 - ~2GB INT4]
    └─→ Simple Commands → Rule-based (no LLM)
```

**Components:**

1. **Speech-to-Text:** Whisper Tiny (39 MB)
2. **Vision/Document Specialist:** Granite Docling 258M INT4 (358 MB)
3. **General LLM (Optional):** Phi-3-mini or Llama 3.2 1B (~1-2 GB INT4)
4. **Text-to-Speech:** Piper/Kokoro (~80 MB)

**Total Storage:** 500 MB - 2.5 GB (depending on architecture)
**Total RAM:** 400-500 MB (1 model loaded) to 3 GB (all models)

### Storage Optimization Strategies
- **Lazy Loading:** Load models only when needed
- **Model Swapping:** Unload unused models from memory
- **Shared Embeddings:** Reuse tokenizers/embeddings where possible
- **On-demand Download:** Core models bundled, specialists downloaded as needed

---

## Use Case Categories

### 1. Document Intelligence (Granite Strength)
- Invoice/receipt scanning and extraction
- PDF summarization
- Handwriting recognition (OCR)
- Form filling assistance
- Contract analysis

**Example Prompts:**
- "What's the total on this receipt?"
- "Summarize this PDF document"
- "Extract invoice number and due date"

### 2. General Q&A
- Factual questions
- Calculations and math
- Definitions and explanations
- How-to instructions

**Example Prompts:**
- "What's 25% of 80?"
- "Explain photosynthesis"
- "How do I change a tire?"

### 3. Visual Understanding
- Object recognition
- Scene description
- Photo OCR
- Product identification
- Accessibility (describe images)

**Example Prompts:**
- "What objects are in this photo?"
- "Read the text from this image"
- "Describe what you see"

### 4. Productivity
- Message/email drafting assistance
- Note-taking and organization
- Calendar parsing ("Remind me to...")
- Code snippets (basic)
- Translation assistance

**Example Prompts:**
- "Help me write a professional email"
- "Translate this to Spanish"
- "Write a Python function to sort a list"

### 5. Conversational Assistant
- Multi-turn dialogue
- Context retention (short-term memory)
- Follow-up questions
- Clarification handling

**Example Prompts:**
- "What was my last question?"
- "Can you elaborate on that?"
- "Compare option A and option B"

---

## Development Roadmap

### Week 1: Model Validation & Selection ✅ (Current)

**Completed:**
- [x] Granite Docling 258M load validation
- [x] INT4 (NF4) quantization (358 MB achieved)
- [x] Conversion path identified (PyTorch → ONNX → TFLite)
- [x] Development environment setup

**In Progress:**
- [ ] Comprehensive benchmark suite (document + general Q&A)
- [ ] Model capability assessment
- [ ] Architecture decision (single vs multi-model)

**Deliverables:**
- `ml/FINDINGS.md` - Initial model validation
- `ml/QUANTIZATION_RESULTS.md` - INT4 quantization analysis
- `ml/PROJECT_PLAN.md` - This document
- Test scripts: `test_granite_docling.py`, `test_int4.py`

### Week 2: Core Integration & Android Foundation

**Days 1-2: Model Export & Conversion**
- Export Granite INT4 to ONNX format
- Convert ONNX to TensorFlow Lite
- Validate TFLite model accuracy
- If multi-model: Select & quantize general LLM

**Days 3-4: Android App Foundation**
- Set up Android project (Kotlin)
- TFLite model loading infrastructure
- Intent routing logic (if multi-model)
- Basic UI (voice button, text display)
- Whisper Tiny integration (STT)

**Days 5-7: Device Testing**
- Deploy to target Android device
- Benchmark inference speed with NPU/GPU acceleration
- Memory profiling
- Battery consumption testing
- Latency optimization

**Deliverables:**
- Working Android APK
- TFLite models (optimized for mobile)
- Performance benchmarks on real device

### Week 3: Polish & Documentation

**Days 1-3: Feature Completion**
- Multi-turn conversation support
- Context retention (conversation history)
- Error handling & fallbacks
- Edge case coverage
- UI polish

**Days 4-5: Documentation**
- Architecture diagrams
- Performance benchmark report
- User guide / README
- API documentation (if exposing)

**Days 6-7: Portfolio Integration**
- Demo video (2-3 minutes)
- Blog post: "Building a Privacy-First Local AI Assistant"
- GitHub polishing (README, screenshots)
- Case study write-up

**Deliverables:**
- Portfolio-ready demo
- Comprehensive documentation
- Blog post / case study
- Demo video

---

## Quality Benchmarks & Metrics

### Benchmark Suite Structure

```python
benchmark_categories = {
    "document_processing": {
        "invoice_extraction": ["total", "date", "invoice_number"],
        "pdf_summarization": ["key_points", "length"],
        "form_ocr": ["accuracy", "formatting"]
    },
    "general_qa": {
        "factual": ["accuracy", "completeness"],
        "calculations": ["correctness"],
        "explanations": ["clarity", "accuracy"]
    },
    "vision": {
        "object_detection": ["accuracy", "count"],
        "scene_description": ["relevance", "detail"],
        "photo_ocr": ["accuracy"]
    },
    "conversation": {
        "multi_turn": ["context_retention", "coherence"],
        "follow_ups": ["relevance"],
        "tone": ["appropriateness"]
    }
}
```

### Quality Metrics
- **Accuracy:** Correct answer vs expected output
- **Latency:** Time to first token, total inference time
- **Relevance:** Response addresses the user query
- **Coherence:** Response is logically structured
- **Completeness:** All required information provided

### Performance Targets (Modern Android Devices)
- **Latency:** <5 seconds for typical query
- **Memory:** <500 MB RAM for single model in use
- **Battery:** <5% drain per 100 queries
- **Accuracy:** >80% on benchmark suite

---

## Technology Stack

### Machine Learning
- **Framework:** PyTorch (training/export)
- **Mobile Runtime:** TensorFlow Lite (Android inference)
- **Quantization:** bitsandbytes (INT4/NF4)
- **Conversion:** ONNX intermediate format

### Models
- **Vision/Document:** Granite Docling 258M (IBM, Apache 2.0)
- **STT:** Whisper Tiny (OpenAI, MIT)
- **TTS:** Piper or Kokoro (~80 MB)
- **General LLM (TBD):** Phi-3-mini, Llama 3.2, or Gemma 2

### Android
- **Language:** Kotlin (primary), Java
- **Architecture:** MVVM + Clean Architecture
- **ML Integration:** TensorFlow Lite Android
- **UI:** Material Design 3
- **Build:** Gradle (no Android Studio required)

### Development Environment
- **OS:** Linux
- **Python:** 3.13+
- **Hardware:** Development laptop + modern Android device (NPU-enabled recommended)

---

## Model Selection Criteria

### Evaluating Granite Docling for General Q&A

**Test Categories:**
1. **Document Tasks** (expected to excel)
2. **General Q&A** (unknown)
3. **Math/Reasoning** (unknown)
4. **Vision Tasks** (expected to work)

**Decision Framework:**
- If Granite scores **>70% on general Q&A**: Use single model
- If Granite scores **<70% on general Q&A**: Add specialist LLM

### Alternative General LLMs (If Needed)

| Model | Size (INT4) | Pros | Cons |
|-------|-------------|------|------|
| **Phi-3-mini** (3.8B) | ~2 GB | Excellent quality, Microsoft | Larger size |
| **Llama 3.2 1B** | ~600 MB | Very small, Meta | Newer, less tested |
| **Llama 3.2 3B** | ~1.5 GB | Better quality | Moderate size |
| **Gemma 2 2B** | ~1 GB | Google, well-optimized | License restrictions |

**Recommendation:** Start with Llama 3.2 1B (smallest, good balance)

---

## Risk Assessment

### High Risk Items
- **Granite not suitable for general Q&A**
  - *Mitigation:* Multi-model architecture ready
  - *Impact:* Adds 600 MB - 2 GB storage

### Medium Risk Items
- **Combined model size >3 GB**
  - *Mitigation:* Lazy loading, model swapping
  - *Impact:* Slower initial load times

- **Inference too slow on target device**
  - *Mitigation:* INT4 quantization, NPU/GPU delegates
  - *Likelihood:* Low (modern devices with NPU handle multi-billion parameter models)

### Low Risk Items
- **PyTorch → TFLite conversion issues**
  - *Mitigation:* Standard pipeline, well-documented

- **Battery drain excessive**
  - *Mitigation:* Result caching, batch inference

---

## Success Criteria

### MVP Features (Must Have)
- ✅ Voice input via Whisper Tiny
- ✅ Document scanning & Q&A
- ✅ General conversation (Granite or Phi-3)
- ✅ 100% offline operation
- ✅ Runs on modern Android devices with <5s response time
- ✅ Basic multi-turn conversation

### Portfolio Quality (Must Have)
- ✅ Comprehensive README with architecture
- ✅ Performance benchmarks documented
- ✅ Demo video showcasing capabilities
- ✅ Blog post / case study
- ✅ Clean, documented codebase

### Nice to Have (V2 Features)
- Multi-language support (Spanish, French, etc.)
- Camera integration (live OCR)
- Conversation export (PDF, text)
- Custom wake word
- Voice customization (TTS voices)
- Widget for quick access

---

## Portfolio Positioning

### Project Narrative

**Title:** "Privacy-First Local AI Assistant for Android"

**Elevator Pitch:**
> A fully offline AI assistant that runs 100% on-device. Uses quantized multimodal LLMs (Granite Docling 258M) for document understanding and general Q&A. Demonstrates GenAI model integration, MLOps optimization (INT4 quantization, edge deployment), and production-ready Android development.

### Key Talking Points

**GenAI Expertise:**
- Multimodal model integration (vision + text)
- Multiple specialized models coordinated
- Prompt engineering for different contexts
- Framework-agnostic approach (not API-dependent)

**MLOps Expertise:**
- Model quantization (INT4 via bitsandbytes)
- Conversion pipeline: PyTorch → ONNX → TFLite
- Edge deployment optimization
- Performance benchmarking methodology
- Memory management (lazy loading, model swapping)

**Engineering Depth:**
- Built from scratch (not API wrapper)
- Multi-model architecture
- Intent routing system
- Battery & memory optimization
- Production-ready quality

**Product Thinking:**
- Privacy-first positioning (market trend)
- Real user value (offline capability)
- Use case prioritization
- Performance vs capability tradeoffs

### Why This Project Matters (Job Applications)

**Alignment with €85k Senior AI Engineer Role:**
- Shows **GenAI specialization** (Margarita's advice)
- Shows **MLOps depth** (model optimization, deployment)
- "**FROM SCRATCH**" narrative (not just API calls)
- Framework-agnostic (valued by companies)
- Portfolio-worthy complexity

**Differentiators:**
- Privacy-first (market trend, competitive angle)
- Offline-first (technical challenge, real value)
- Mobile optimization (edge AI, growth area)
- Multi-model architecture (shows system design)

---

## Immediate Next Steps (Week 1 Completion)

### Today: Comprehensive Benchmark
1. Create test suite with 20-30 diverse prompts across all categories
2. Test Granite Docling INT4 on:
   - Document tasks (baseline performance)
   - General Q&A (capability validation)
   - Vision tasks (multimodal test)
   - Math/reasoning (logic test)
3. Measure quality + latency (accept slow on laptop CPU)
4. **Decision:** Single model or multi-model architecture?

### Tomorrow: Model Selection (If Multi-Model Needed)
- Research Phi-3-mini, Llama 3.2 variants, Gemma 2
- Quantize best candidate to INT4
- Run same benchmark suite
- Compare results with Granite

### Day 3-4: Export & Convert
- Export final model(s) to ONNX
- Convert ONNX to TensorFlow Lite
- Validate TFLite accuracy vs PyTorch
- Test inference speed on CPU (baseline)

### Day 5-7: Android Foundation
- Set up Android project structure
- Integrate TFLite runtime
- Load models and test inference
- Basic voice interface (Whisper + TTS)
- Deploy to Android device for real-world testing

---

## Technical Debt & Future Work

### Known Limitations (MVP)
- No internet-based knowledge updates
- Limited context window (model constraints)
- English-only (initially)
- No learning from user interactions (privacy trade-off)

### Potential Improvements (Post-MVP)
- RAG (Retrieval-Augmented Generation) for knowledge base
- Fine-tuning on specific domains
- Multi-language support
- Continuous learning (on-device, privacy-preserving)
- Cloud-sync for preferences (optional, encrypted)

---

## Resources & References

### Models
- **Granite Docling 258M:** https://huggingface.co/ibm-granite/granite-docling-258M
- **Whisper Tiny:** https://huggingface.co/openai/whisper-tiny
- **Phi-3-mini:** https://huggingface.co/microsoft/Phi-3-mini-4k-instruct
- **Llama 3.2:** https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct

### Documentation
- **TensorFlow Lite:** https://www.tensorflow.org/lite
- **bitsandbytes:** https://github.com/TimDettmers/bitsandbytes
- **ONNX:** https://onnx.ai/

### Inspiration Projects
- **Dicio Android:** https://github.com/Stypox/dicio-android (FOSS voice assistant)
- **Whisper Android:** https://github.com/vilassn/whisper_android (TFLite Whisper)

---

## Project Structure

```
local-llm-android-auto/
├── README.md (main project overview)
├── PROJECT_PLAN.md (this file)
├── ml/
│   ├── requirements.txt
│   ├── FINDINGS.md (initial validation)
│   ├── QUANTIZATION_RESULTS.md (INT4 analysis)
│   ├── granite/
│   │   ├── test_granite_docling.py
│   │   ├── test_int4.py
│   │   ├── benchmark.py
│   │   └── comprehensive_benchmark.py (to be created)
│   ├── whisper/ (STT integration)
│   ├── tts/ (text-to-speech)
│   └── onnx_models/ (exported models)
├── android/
│   ├── app/
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── kotlin/ (app code)
│   │   │   │   ├── assets/ (TFLite models)
│   │   │   │   └── AndroidManifest.xml
│   │   │   └── test/
│   │   └── build.gradle
│   └── build.gradle
├── docs/
│   ├── architecture.md
│   ├── benchmarks.md
│   └── user_guide.md
└── scripts/
    ├── export_to_onnx.sh
    ├── convert_to_tflite.sh
    └── benchmark_s22.sh
```

---

## Timeline Summary

| Phase | Duration | Status | Key Deliverables |
|-------|----------|--------|------------------|
| **Week 1** | 7 days | ✅ 70% | Model validation, INT4 quantization, architecture decision |
| **Week 2** | 7 days | ⏭️ Planned | Android integration, device testing, performance optimization |
| **Week 3** | 7 days | ⏭️ Planned | Polish, documentation, demo, portfolio integration |
| **Total** | 21 days | — | Portfolio-ready local AI assistant |

**Job Applications Start:** Week 4 (November 2025)
**Target Companies:** Microsoft Valencia, Wellhub, N26, Datadog

---

*Last Updated: 2025-10-09*
*Status: Week 1 - Model Validation Phase*
*Next Milestone: Comprehensive benchmark & architecture decision*
