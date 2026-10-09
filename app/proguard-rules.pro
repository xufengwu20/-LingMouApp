# LingMou 默认混淆规则：当前 release 未开启 minify，这里只保留必要的 keep 规则，
# 方便你以后打开 isMinifyEnabled 时不会误删 ML Kit / MediaSession 相关类。

# ML Kit 通过反射注册人脸检测实现
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }

# kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.example.lingmouapp.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.lingmouapp.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn okhttp3.**
-dontwarn okio.**
