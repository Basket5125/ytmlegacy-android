# Conscrypt: JNI and reflection.
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
# Keep stack traces readable.
-keepattributes SourceFile,LineNumberTable
