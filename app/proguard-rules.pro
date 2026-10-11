# Az Notes ProGuard/R8 规则（release 已启用 R8 混淆与资源收缩）

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

# 枚举：备份/设置 JSON 与同步记录按常量名序列化，R8 会改写未 keep 的枚举字段名，
# 保留 com.az.notes 下全部 enum 成员名，保证旧数据可持续解析。
-keepclassmembers enum com.az.notes.** { *; }

# WorkManager 按类名反射实例化 Worker（SyncWorker 未在 Manifest 声明）
-keep class com.az.notes.work.SyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 堆栈可读性：保留行号信息，release 崩溃日志仍可定位到源码行
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Markdown 渲染库
-keep class com.mikepenz.markdown.** { *; }
