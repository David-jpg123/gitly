# kotlinx.serialization: conservar los serializadores generados de nuestros modelos.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.david.gitly.** {
    *** Companion;
}
-keepclasseswithmembers class com.david.gitly.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.david.gitly.**$$serializer { *; }
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Nostr (UniFFI + JNA): se llama por reflexión desde código nativo.
-keep class org.nostrdevkit.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# WorkManager: Room instancia su base de datos por reflexión (WorkDatabase_Impl.<init>()).
# Sin esto la release peta al arrancar en androidx.startup.InitializationProvider.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
