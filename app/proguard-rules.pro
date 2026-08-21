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
