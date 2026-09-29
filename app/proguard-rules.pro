# Lyane ProGuard / R8 kuralları

# ── sherpa-onnx (JNI) ─────────────────────────────────────────────────────
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ── kotlinx.serialization ─────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.riat.lyane.**$$serializer { *; }
-keepclassmembers class com.riat.lyane.** { *** Companion; }
-keepclasseswithmembers class com.riat.lyane.** { kotlinx.serialization.KSerializer serializer(...); }

# ── Rhino (JS eklenti motoru) yansıtma kullanır ───────────────────────────
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.**

# ── DEX eklentileri: LyanePlugin arayüzü eklentilerde bulunabilmeli ───────
-keep interface com.riat.lyane.plugin.LyanePlugin { *; }
-keep class * implements com.riat.lyane.plugin.LyanePlugin { *; }

# ── NanoHTTPD ─────────────────────────────────────────────────────────────
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# ── commons-compress ──────────────────────────────────────────────────────
-dontwarn org.apache.commons.compress.**
