# R8 混淆规则(项目文档 6.4:Jsoup / kotlinx.serialization 需要 keep 规则)

# kotlinx.serialization:保留序列化器与可序列化类元数据
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lawquery.**$$serializer { *; }
-keepclassmembers class com.lawquery.** {
    *** Companion;
}
-keepclasseswithmembers class com.lawquery.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Jsoup:按类名反射少量使用,保留包名避免混淆后行为异常
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
