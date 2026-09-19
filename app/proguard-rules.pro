# kotlinx.serialization 的 @Serializable 数据类与其生成的 serializer 必须保留
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations

-keep class com.rikka.dsusage.data.** { *; }
-keep class com.rikka.dsusage.MainActivity { *; }
-keep class com.rikka.dsusage.ui.** { *; }

-dontwarn kotlinx.serialization.**
-dontwarn org.jetbrains.annotations.**
