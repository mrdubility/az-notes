# Az Notes ProGuard/R8 规则（Demo 阶段未启用混淆，规则先行保留）

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.az.notes.**$$serializer { *; }
-keepclassmembers class com.az.notes.** {
    *** Companion;
    *** INSTANCE;
}

# dav4jvm / OkHttp（M3 同步引擎引入时启用）
# -dontwarn org.apache.james.**
# -dontwarn okhttp3.**
# -dontwarn okio.**

# Markdown 渲染库
-keep class com.mikepenz.markdown.** { *; }
