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

# Smartspacer SDK
# Target 是通过 Binder 把 Parcelable 模型（Icon / Text / TapAction / SmartspaceTarget）
# 跨进程交给 Smartspacer 的，对方用**自己那份 SDK 副本按类名**反序列化。
# 如果 R8 把类名混淆掉，对方就找不到对应类，Target 会直接失效。
# 官方 wiki 也建议在特定场景下排除 SDK 混淆，这里统一排除，代价只是少量体积。
-keep class com.kieronquinn.app.smartspacer.sdk.** { *; }
