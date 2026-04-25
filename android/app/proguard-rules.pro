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
-keep,includedescriptorclasses class com.localai.assistant.domain.model.**$$serializer { *; }
-keepclassmembers class com.localai.assistant.domain.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.localai.assistant.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
