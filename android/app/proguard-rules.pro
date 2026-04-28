# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in \$ANDROID_HOME/tools/proguard/proguard-android.txt

# Keep Room database classes
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep data classes for serialization
-keep,includedescriptorclasses class com.vela.assistant.domain.model.**$$serializer { *; }
-keepclassmembers class com.vela.assistant.domain.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.vela.assistant.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# litertlm reflective tool dispatch — ReflectionTool reads @Tool/@ToolParam at runtime.
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations
-keepattributes MethodParameters, Signature, Exceptions
-keep class com.google.ai.edge.litertlm.Tool
-keep class com.google.ai.edge.litertlm.ToolParam
-keep class * implements com.google.ai.edge.litertlm.ToolSet { *; }
-keepclassmembers class * implements com.google.ai.edge.litertlm.ToolSet {
    @com.google.ai.edge.litertlm.Tool <methods>;
}

# Kotlin reflection
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.jvm.internal.**
