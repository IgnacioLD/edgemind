# Local AI Assistant - Android App

Privacy-first AI assistant running 100% on-device. Built with Clean Architecture, MVVM, and industry best practices.

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
- **Framework:** TensorFlow Lite 2.14.0
- **Acceleration:** GPU Delegate, NNAPI
- **Models:**
  - Phi-3-mini 3.8B (text, INT4) - ~2GB
  - Granite Docling 258M (vision, INT4) - ~358MB

### Quality
- **Logging:** Timber
- **Testing:** JUnit, Mockito, Truth, Turbine
- **Code Quality:** ProGuard rules

## Key Features

### Multi-Model Architecture
- **Intent Router:** Automatically routes queries to appropriate model
- **Text Model:** Phi-3-mini for general Q&A and conversation
- **Vision Model:** Granite Docling for document understanding
- **Lazy Loading:** Models loaded on-demand to save memory

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

## Models (Not Included)

Place TFLite models in `src/main/assets/models/`:
- `phi3_mini_int4.tflite` (~2GB)
- `granite_docling_int4.tflite` (~358MB)

## License

[Your License Here]

## Contributors

[Your Name]
