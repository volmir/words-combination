# ProGuard / R8 rules for SostavSlova

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt

-keepclassmembers class * {
    *** Companion;
}

-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    <fields>;
    <methods>;
}

-keep,allowobfuscation,allowshrinking class * {
    <fields>;
}

-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}

# org.json (used in JVM tests and manifest parsing)
-keep class org.json.** { *; }
-dontwarn org.json.**
