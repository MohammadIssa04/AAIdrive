# Issa Music inside the AAIdrive build: keep it as it was when it was built on its own
-keep class com.musik.app.** { *; }
-keepattributes *Annotation*
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
