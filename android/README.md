# Vela - Android App

On-device AI assistant with Phi-3 mini (3.8B INT4). Built with Clean Architecture, MVVM, and production-grade practices.

## Architecture

This project follows **Clean Architecture** principles with clear separation of concerns:

```
app/
├── domain/           # Business logic (platform-independent)
│   ├── model/       # Domain models
│   ├── repository/  # Repository interfaces
│   └── usecase/     # Use cases (business rules)
│
├── data/            # Data layer
│   ├── local/       # Local data sources (Room, TFLite)
│   ├── repository/  # Repository implementations
│   └── mapper/      # Data ↔ Domain mappers
│
├── presentation/    # UI layer (MVVM)
│   ├── chat/       # Chat feature
│   ├── settings/   # Settings feature
│   └── common/     # Shared UI components
│
└── di/             # Dependency injection (Hilt)
```

## Tech Stack

### Core
- **Language:** Kotlin 1.9.20
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)

### Architecture & Patterns
- **Architecture:** Clean Architecture
- **Presentation Pattern:** MVVM
- **Dependency Injection:** Hilt 2.48
- **Async:** Coroutines + Flow

### UI
- **Framework:** Jetpack Compose
- **Design System:** Material 3
- **Navigation:** Compose Navigation

### Data
- **Database:** Room 2.6.1
- **Preferences:** DataStore
- **Serialization:** Kotlinx Serialization

### ML
- **Framework:** ONNX Runtime 1.19.2 with IR version 7
- **Acceleration:** NNAPI (NPU/TPU/DSP), CPU fallback
- **Model:** Phi-3 mini 3.8B (INT4, 2.6GB)
- **Tokenizer:** Custom SentencePiece BPE (32,064 vocab)
- **Optimization:** KV cache for O(n) generation, memory-mapped loading

### Quality
- **Logging:** Timber
- **Testing:** JUnit, Mockito, Truth, Turbine
- **Code Quality:** ProGuard rules

## Key Features

### Core Capabilities
- **Phi-3 mini 3.8B:** Microsoft's small language model (INT4 quantized)
- **NNAPI Acceleration:** Hardware NPU/TPU/DSP support for fast inference
- **Streaming Chat:** Real-time token generation via Kotlin Flow
- **Smart Stopping:** N-gram repetition detection, adaptive token limits
- **KV Cache:** 6x faster generation with O(n) complexity

### Clean Architecture Benefits
- **Testable:** Business logic independent of framework
- **Maintainable:** Clear separation of concerns
- **Scalable:** Easy to add new features
- **Framework-agnostic domain:** Can swap UI/data layers

### MVVM Pattern
- **ViewModels:** Survive configuration changes
- **StateFlow:** Reactive UI updates
- **Unidirectional data flow:** Predictable state management

## Testing

### Unit Tests
```bash
./gradlew test
```

Tests located in `src/test/`:
- **Domain Tests:** Use case logic, business rules
- **ViewModel Tests:** UI state management
- **Repository Tests:** Data operations

### Instrumentation Tests
```bash
./gradlew connectedAndroidTest
```

Tests located in `src/androidTest/`:
- **UI Tests:** Compose UI testing
- **Integration Tests:** Database, Room DAOs

## Building

### Debug Build
```bash
./gradlew assembleDebug
```

### Release Build
```bash
./gradlew assembleRelease
```

## Project Structure Details

### Domain Layer
- **Pure Kotlin:** No Android dependencies
- **Models:** Immutable data classes
- **Use Cases:** Single Responsibility Principle
- **Repositories:** Interfaces only (implementations in data layer)

### Data Layer
- **Repository Pattern:** Single source of truth
- **Mappers:** Convert between domain ↔ data models
- **Room:** Local persistence for conversations
- **TFLite:** Model inference wrapper

### Presentation Layer
- **Jetpack Compose:** Modern declarative UI
- **ViewModels:** Business logic for screens
- **State Management:** MutableStateFlow → StateFlow
- **Hilt Integration:** ViewModel injection

### Dependency Injection
- **Hilt:** Compile-time DI framework
- **Modules:** Database, Repository, App
- **Scopes:** Singleton, ViewModel-scoped
- **Binds vs Provides:** Performance-optimized

## Best Practices Implemented

### Code Quality
- ✅ Clean Architecture
- ✅ SOLID principles
- ✅ Repository pattern
- ✅ Dependency injection
- ✅ Immutable models
- ✅ Sealed classes for results
- ✅ Extension functions
- ✅ Coroutines for async
- ✅ Flow for reactive data

### Testing
- ✅ Unit tests for use cases
- ✅ ViewModel tests with coroutines
- ✅ Repository tests
- ✅ Turbine for Flow testing
- ✅ Truth assertions
- ✅ MockK/Mockito for mocking

### Android
- ✅ Material 3 design
- ✅ Jetpack Compose
- ✅ Edge-to-edge UI
- ✅ Dark theme support
- ✅ ProGuard rules
- ✅ Resource optimization

### Performance
- ✅ Lazy model loading
- ✅ GPU delegate for inference
- ✅ Room for efficient DB
- ✅ Flow for backpressure
- ✅ R8 optimization

## Model Setup

### Download Phi-3 Model
```bash
# Download from HuggingFace (2.6GB)
wget https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-onnx/resolve/main/cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4/phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx

# Push to device
adb push phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx /sdcard/Android/data/com.vela.assistant/files/models/
```

### Tokenizer
The SentencePiece BPE tokenizer configuration (`tokenizer.json`) is included in `src/main/assets/` (3.5MB)

## License

[Your License Here]

## Contributors

[Your Name]
