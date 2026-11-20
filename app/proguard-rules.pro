# Media3 / ExoPlayer
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.cutvideo.editor.domain.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.cutvideo.editor.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.cutvideo.editor.**$$serializer { *; }

# JNI
-keep class com.cutvideo.editor.nativebridge.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
