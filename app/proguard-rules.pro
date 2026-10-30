# Issa Music – R8 rules (libraries bring their own rules; these are just safety nets)
-keep class com.musik.app.PlaybackService { *; }
-keep class com.musik.app.PlayerWidget { *; }
-keep class com.musik.app.IssaApp { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class com.musik.app.** { *; }

# the TV app's bridge to its page (called by name from JavaScript)
-keepattributes *Annotation*
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
