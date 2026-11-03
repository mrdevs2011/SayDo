# ProGuard / R8 Optimization & Obfuscation Rules for SayDo

# Keep line numbers and source file for readable crash reports in app_crash.log
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod

# Keep Domain Data Models for Reflection and JSON Serializers
-keep class uz.saydo.app.data.** { *; }
-keepclassmembers class uz.saydo.app.data.** { *; }

# Keep Android Application & Lifecycle Components
-keep public class uz.saydo.app.SayDoApp { *; }
-keep public class uz.saydo.app.MainActivity { *; }
-keep public class uz.saydo.app.TriggerActivity { *; }
-keep public class uz.saydo.app.VoiceService { *; }
-keep public class uz.saydo.app.GestureService { *; }
-keep public class uz.saydo.app.FloatService { *; }

# Keep Accessibility Service Callbacks
-keepclassmembers class uz.saydo.app.GestureService {
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent);
    public void onInterrupt();
    protected void onServiceConnected();
}

# Keep Speech Recognition Callbacks
-keepclassmembers class * implements android.speech.RecognitionListener { *; }

# Strip verbose debug logging in release builds while preserving warnings and errors
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
