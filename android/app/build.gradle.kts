plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    kotlin("kapt")
}

android {
    namespace = "com.vela.assistant"
    // Bumped from 34 to 35 because the LiteRT 2.1.4 / QNN 2.44.0 / TFLite 2.16.1 dep set
    // pulls newer AndroidX + Compose AARs (1.9.0 / 2.10.0 / 1.15.0) that require it.
    // targetSdk stays at 34 — compileSdk only controls which APIs are available at compile
    // time, runtime behaviour is governed by targetSdk.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vela.assistant"
        minSdk = 26
        targetSdk = 34
        // SemVer (MAJOR.MINOR.PATCH). versionCode is a monotonically-increasing integer that
        // must be bumped on every release regardless of whether MAJOR/MINOR/PATCH changes.
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Restrict packaged native libs to arm64 only. QNN AARs ship arm64 + x86 variants;
        // packaging both bloats the APK by ~80 MB for emulator coverage we don't need on a
        // production build, and mixed-ABI packaging has historically tripped multi-AAR
        // libLiteRt.so deduplication. Mirrors the working sample app.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Extract bundled .so files to /data/app/.../lib/arm64 at install time. Required
        // because Qualcomm's QNN runtime loads its Skel libs (libQnnHtpV73Skel.so etc.) via
        // raw dlopen() against nativeLibraryDir — that codepath can't read compressed entries
        // from inside the APK, so without extraction the loader aborts via SIGABRT before
        // any LiteRtLmJniException can be caught. AGP 8+ defaults this to false.
        jniLibs {
            useLegacyPackaging = true
            // litertlm-android and litert both ship libLiteRt.so. We want the litert-core
            // version (it exports LiteRtGetEnvironmentOptions, which the QNN dispatch library
            // needs at init); without pickFirsts Gradle errors out on duplicate paths.
            pickFirsts += setOf("**/libLiteRt.so")
        }
    }
}

// Pin the Kotlin compilation JDK to 21. Gradle's toolchain mechanism either picks an installed
// JDK 21 or downloads one (when a toolchain resolver is configured), which makes builds
// reproducible across local machines, CI, and F-Droid's build server even if the host has
// some other JDK on PATH. Bytecode target stays at 17 via compileOptions / kotlinOptions above.
kotlin {
    jvmToolchain(21)
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.57.2")
    kapt("com.google.dagger:hilt-android-compiler:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Kotlin reflection — litertlm's ReflectionTool reflects over @Tool-annotated functions.
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.21")

    // On-device LLM inference — LiteRT-LM (Gemma 4 E2B with native audio).
    // We use this rather than MediaPipe `tasks-genai` because Gemma 4's audio adapter sections are
    // CPU-pinned in the .litertlm but tasks-genai's audio executor is hardcoded GPU-only and fails
    // to load LlmParameters. LiteRT-LM exposes per-modality backends (GPU LLM, CPU audio) which
    // is what the model actually requires.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.2")

    // LiteRT core runtime — needed in addition to litertlm-android because the LiteRT-LM AAR
    // ships an older / smaller libLiteRt.so that's missing the LiteRtGetEnvironmentOptions
    // symbol the Qualcomm dispatch library needs at init. The litert AAR ships a fuller
    // build of libLiteRt.so; pickFirsts in packaging{} below resolves the duplicate.
    // Mirrors the working setup in google-ai-edge/litert-samples qualcomm/gemma_on_device.
    implementation("com.google.ai.edge.litert:litert:2.1.4")

    // tensorflow-lite-select-tf-ops drags in the TFLite runtime ops the dispatch delegate
    // expects to find at delegate-init time. Excludes the smaller tensorflow-lite + api
    // artifacts because litert already provides the equivalents and they conflict.
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.16.1") {
        exclude(group = "org.tensorflow", module = "tensorflow-lite")
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    }

    // Qualcomm QNN runtime + LiteRT delegate. The runtime AAR ships the QNN backend libs
    // (libQnnHtp, libQnnSystem, libQnnHtpV{68,69,73,75,79}{Skel,Stub}, libQnnGpu, libQnnDsp);
    // the delegate AAR ships libQnnTFLiteDelegate / libqnn_delegate_jni. Both have to be
    // declared explicitly: in 2.44.0 the delegate does NOT pull the runtime transitively
    // (verified empirically — depending on delegate alone leaves the HTP libs out of the
    // APK and dlopen of libLiteRtDispatch_Qualcomm.so fails with "cannot locate symbol
    // LiteRtQualcommOptionsGet" because the providing libs aren't on disk).
    // 2.44.0 matches the version pinned by google-ai-edge/litert-samples qualcomm sample.
    // Closed-source binaries; F-Droid build will need a product-flavor split.
    implementation("com.qualcomm.qti:qnn-runtime:2.44.0")
    implementation("com.qualcomm.qti:qnn-litert-delegate:2.44.0")

    // Room Database (for conversation history)
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // DataStore (for preferences)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Timber (logging)
    implementation("com.jakewharton.timber:timber:5.0.1")

    // ML Kit on-device language identification — used so the TTS engine can pick a Locale that
    // matches whatever language Gemma 4 chose to reply in. Self-contained (no Play Services
    // dependency at runtime); model is bundled into the AAR and runs entirely on-device.
    implementation("com.google.mlkit:language-id:17.0.4")

    // Testing - Unit Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("com.google.truth:truth:1.1.5")
    testImplementation("androidx.arch.core:core-testing:2.2.0")

    // Testing - Android Tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.57.2")
    kaptAndroidTest("com.google.dagger:hilt-android-compiler:2.57.2")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
}
