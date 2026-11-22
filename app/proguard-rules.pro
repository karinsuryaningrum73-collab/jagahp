# Preserve WebRTC JNI classes and native callbacks
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Preserve Firestore data models annotated with @Keep
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep @androidx.annotation.Keep class * { *; }
