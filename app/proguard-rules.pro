# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.wakeup.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.wakeup.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.wakeup.data.model.**$$serializer { *; }

# 保留数据模型字段名，便于排错
-keepclassmembers class com.wakeup.data.model.** {
    <fields>;
}
