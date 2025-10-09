# Architecture Decision: Multi-Model Specialist Approach

**Date:** 2025-10-09
**Decision:** Multi-model architecture with specialized models

---

## Context

**Project Pivot:** From Android Auto document assistant → General-purpose local AI assistant

**Original Plan:** Test if Granite Docling 258M can handle all tasks (documents + general Q&A)

**Problem:** CPU inference too slow to run comprehensive benchmark, but we can make informed decision based on model design.

---

## Analysis

### Granite Docling 258M Characteristics

**Designed for:**
- ✅ Document understanding (invoices, PDFs, forms)
- ✅ Document conversion (OCR, structure extraction)
- ✅ Vision tasks (image analysis)
- ✅ Multimodal input (image + text)

**NOT designed for:**
- ❌ General knowledge Q&A
- ❌ Math and reasoning
- ❌ Conversational dialogue
- ❌ Creative writing

**Evidence:**
- Model name: "Docling" (document-focused)
- Training: Specialized on document datasets
- IBM's description: "Document understanding & conversion"
- Use cases: OCR, layout analysis, table extraction

### Benchmark Reality

**What we learned:**
- CPU inference is prohibitively slow (10+ min timeout)
- Cannot validate general Q&A on laptop
- **BUT:** Model design already tells us the answer

**Logical conclusion:**
- Specialized model won't excel at general tasks
- Need dedicated general LLM for conversational AI

---

## Decision: Multi-Model Architecture

### Architecture Overview

```
User Input (Voice/Text)
    ↓
[Whisper Tiny STT] (39 MB)
    ↓
[Intent Router] (lightweight classifier)
    ↓
    ├─→ Document/Vision Query → [Granite Docling INT4] (358 MB)
    │                            • Invoice scanning
    │                            • PDF analysis
    │                            • OCR tasks
    │                            • Image description
    │
    ├─→ General Q&A Query → [Llama 3.2 1B INT4] (~600 MB)
    │                        • Factual questions
    │                        • Math/calculations
    │                        • Explanations
    │                        • Conversations
    │
    └─→ Simple Commands → Rule-based (no LLM)
                          • "What time is it?"
                          • "Set timer"
                          • "Open app"
    ↓
[TTS - Piper] (80 MB)
    ↓
Audio Output
```

### Selected Models

| Component | Model | Size | Purpose |
|-----------|-------|------|---------|
| **STT** | Whisper Tiny | 39 MB | Speech-to-text |
| **Document/Vision** | Granite Docling 258M INT4 | 358 MB | Document understanding |
| **General LLM** | Llama 3.2 1B INT4 | ~600 MB | Conversation, Q&A |
| **Intent Router** | DistilBERT/Custom | ~50 MB | Route to correct model |
| **TTS** | Piper | ~80 MB | Text-to-speech |
| **Total** | — | **~1.1 GB** | Fits in modern device storage |

---

## Why Llama 3.2 1B for General Q&A?

### Comparison of Options

| Model | Size (INT4) | Pros | Cons | Decision |
|-------|-------------|------|------|----------|
| **Llama 3.2 1B** | ~600 MB | Small, Meta-backed, good quality | Newer | ✅ **SELECTED** |
| Llama 3.2 3B | ~1.5 GB | Better quality | Large | ❌ Too big |
| Phi-3-mini | ~2 GB | Excellent quality | Large | ❌ Too big |
| Gemma 2 2B | ~1 GB | Google-optimized | License concerns | ⚠️ Backup |

**Llama 3.2 1B wins because:**
- Smallest viable general LLM (~600 MB INT4)
- Open weights, permissive license
- Meta's latest architecture
- Good balance of size and capability
- Combined with Granite: ~1 GB total (acceptable)

---

## Intent Routing Logic

### Simple Keyword-Based Router (MVP)

```python
def route_intent(user_query: str) -> str:
    """Route query to appropriate model"""

    # Document keywords
    doc_keywords = [
        'invoice', 'receipt', 'pdf', 'document', 'scan',
        'extract', 'read', 'ocr', 'form', 'paper'
    ]

    # Check for image input
    if has_image_attachment(user_query):
        return "granite"  # Vision model

    # Check for document keywords
    if any(kw in user_query.lower() for kw in doc_keywords):
        return "granite"

    # Default to general LLM
    return "llama"
```

### Advanced Router (V2)

- Use lightweight classifier (DistilBERT, 50 MB)
- Train on labeled intent dataset
- Categories: document, vision, qa, math, conversation, command
- 95%+ accuracy achievable

---

## Benefits of Multi-Model Architecture

### ✅ Advantages

1. **Better Quality:** Each model excels at its specialty
2. **Right Tool for Job:** Document expert for docs, conversation expert for chat
3. **Flexibility:** Can swap/upgrade individual models
4. **Scalability:** Easy to add new specialists (translation, code, etc.)
5. **Efficiency:** Load only needed model at runtime

### ⚠️ Trade-offs

1. **More Storage:** ~1.1 GB vs 358 MB single model
2. **Complexity:** Need intent routing logic
3. **Latency:** Model switching adds overhead (~1-2s)
4. **Memory:** Can use lazy loading (only 1 model in RAM at a time)

---

## Storage Optimization Strategy

### Lazy Loading Pattern

```kotlin
object ModelManager {
    private var currentModel: Interpreter? = null

    fun getModel(intent: Intent): Interpreter {
        return when(intent) {
            Intent.DOCUMENT -> {
                if (currentModel != graniteModel) {
                    unloadCurrent()
                    loadGranite()
                }
                graniteModel
            }
            Intent.GENERAL -> {
                if (currentModel != llamaModel) {
                    unloadCurrent()
                    loadLlama()
                }
                llamaModel
            }
        }
    }
}
```

**Memory Impact:**
- Maximum RAM: ~600 MB (1 model loaded)
- Storage: 1.1 GB total
- Swap time: 1-2 seconds (acceptable)

---

## Implementation Plan

### Week 2 Tasks (Updated)

**Days 1-2: Llama 3.2 Integration**
- Download Llama 3.2 1B from HuggingFace
- Quantize to INT4 using bitsandbytes (same as Granite)
- Test basic inference
- Export to ONNX, convert to TFLite

**Days 3-4: Intent Router**
- Implement keyword-based router (MVP)
- Test routing accuracy on sample queries
- Add fallback logic

**Days 5-7: Android Integration**
- Set up dual-model loading
- Implement lazy loading pattern
- Test model swapping performance
- Measure memory usage

---

## Example Use Cases

### Document Tasks → Granite Docling

**User:** "What's the total on this receipt?"
- **Route:** Granite (image + document keyword)
- **Input:** Receipt photo + question
- **Output:** "$137.50"

**User:** "Summarize this PDF"
- **Route:** Granite (document keyword)
- **Input:** PDF pages as images + prompt
- **Output:** Summary text

### General Q&A → Llama 3.2

**User:** "What's 25% of 80?"
- **Route:** Llama (math/calculation)
- **Input:** Text query
- **Output:** "20"

**User:** "Explain photosynthesis"
- **Route:** Llama (knowledge query)
- **Input:** Text query
- **Output:** Detailed explanation

### Conversation → Llama 3.2

**User:** "Help me write an email to my boss"
- **Route:** Llama (writing task)
- **Input:** Text + context
- **Output:** Draft email

---

## Success Criteria

### Performance Targets

| Metric | Target | Method |
|--------|--------|--------|
| **Latency** | <5s response | NPU acceleration |
| **Accuracy** | >80% correct routing | Intent classifier |
| **Memory** | <700 MB RAM | Lazy loading |
| **Storage** | <1.5 GB | INT4 quantization |
| **Battery** | <5%/100 queries | Efficient inference |

### Quality Benchmarks

- **Document tasks:** >90% accuracy (Granite specialty)
- **General Q&A:** >70% accuracy (Llama capability)
- **Vision tasks:** >80% accuracy (Granite multimodal)
- **Routing:** >95% correct intent classification

---

## Risk Mitigation

### Potential Issues & Solutions

**1. Storage too large (>1.5 GB)**
- Solution: On-demand model download (core + specialists)
- User selects which capabilities to enable

**2. Model switching too slow**
- Solution: Predictive loading (load likely next model)
- Cache frequently used model in RAM

**3. Intent routing errors**
- Solution: Allow user to specify intent manually
- Learn from corrections (personalization)

**4. Llama 3.2 quality insufficient**
- Solution: Fallback to Phi-3-mini (larger but better)
- Hybrid: Llama for simple, Phi-3 for complex

---

## Alternative Considered: Single Model

### Why NOT Single Model

**Option A: Granite Only**
- ✅ Simple, small (358 MB)
- ❌ Poor at general Q&A
- ❌ Not trained for conversation
- **Verdict:** Insufficient for general assistant

**Option B: General LLM Only (Llama 3.2)**
- ✅ Good at conversation
- ❌ Poor document understanding
- ❌ No vision capabilities
- **Verdict:** Missing key differentiator (document AI)

**Decision:** Need both → Multi-model wins

---

## Portfolio Value

### Why This Architecture is Better for Job Applications

**Demonstrates:**
1. **System Design:** Multi-model coordination
2. **Trade-off Analysis:** Size vs capability
3. **Production Thinking:** Lazy loading, optimization
4. **Specialization:** Right tool for right job
5. **Scalability:** Easy to extend

**Interview Story:**
> "I built a multi-model architecture with specialized LLMs. Granite Docling for document understanding (my differentiator), Llama 3.2 for general Q&A. Implemented intent routing to coordinate them. Shows I understand that one model doesn't fit all tasks - specialist models excel at their domain."

---

## Final Recommendation

**✅ PROCEED WITH MULTI-MODEL ARCHITECTURE**

**Next Steps:**
1. Download & quantize Llama 3.2 1B to INT4
2. Implement keyword-based intent router
3. Export both models to TFLite
4. Test on Android device

**Timeline:** Week 2 (7 days)
**Confidence:** 90% (proven approach)
**Complexity:** Medium (manageable)

---

*Decision made: 2025-10-09*
*Based on: Model design analysis, project requirements, practical constraints*
*Architecture: Multi-model specialist (Granite + Llama 3.2)*
