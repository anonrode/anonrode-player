# Keep native method names (nextlib FFmpeg JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class androidx.media3.decoder.VideoDecoderOutputBuffer { *; }

# Media3 session/notification
-keep class androidx.media3.session.** { *; }
-dontwarn androidx.media3.**

# Room
-keep class * extends androidx.room.RoomDatabase

# ── R8 rules that make `releaseWithDebugSigning` shrinkable ───────────────
#
# That build type previously shipped UNSHRUNK because "R8 breaks
# DataStore/serialization on this path" and the keep rules were never
# written. These are the actual rules that make shrinking safe, so the
# sideload APK gets the same size/performance win as `release`.

# kotlinx.serialization — the compiler plugin generates a `Companion`
# carrying `serializer()`, resolved reflectively by `Json.decodeFromString`.
# R8 cannot see that edge, so without these the plugin's serializers are
# stripped and every settings read throws at runtime.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.anonrode.player.**$$serializer { *; }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
# The generated serializers themselves.
-keep class **$$serializer { *; }

# Kotlin coroutines: internal machinery reached only reflectively.
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepclassmembers class kotlin.coroutines.SafeContinuation { *; }

# Our @Serializable data classes: the plugin strips the generated
# constructors/fields it needs when the enclosing class is kept only for
# its members.
-keep class dev.anonrode.player.core.model.** { *; }
-keep class dev.anonrode.player.core.datastore.** { *; }
-keep class dev.anonrode.player.core.database.** { *; }

# Enum values are persisted by name/ordinal and read back after a process
# restart — R8 must not rename or merge them.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static ** valueOf(int);
}

# Compose keeps its own consumer rules via AAR metadata; nothing extra
# needed here, but keep the annotations the runtime reflects on.
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ONNX Runtime Android JNI entry points (Silero neural VAD)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**


