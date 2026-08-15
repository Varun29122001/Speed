# ============================================================================
# ProGuard/R8 Rules for Speed App
# Copyright (C) 2026 Speed App. All rights reserved.
# ============================================================================

# --- Maximum obfuscation for intellectual property protection ---
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# --- Keep the entry points that Android needs to find via reflection ---
# Service declared in manifest
-keep class com.Speed.speedtest.service.SpeedTestService { <init>(); }

# Activity declared in manifest
-keep class com.Speed.speedtest.LauncherActivity { <init>(); }

# BroadcastReceiver declared in manifest
-keep class com.Speed.speedtest.receiver.BootReceiver { <init>(); }

# --- Keep the integrity checker class name so signature verification works ---
-keep class com.Speed.speedtest.security.IntegrityChecker { *; }

# --- Obfuscate everything else aggressively ---
-obfuscationdictionary proguard-dict.txt
-classobfuscationdictionary proguard-dict.txt
-packageobfuscationdictionary proguard-dict.txt

# --- Remove logging in release builds for security ---
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# --- Keep AndroidX / Kotlin essentials ---
-keep class androidx.core.app.NotificationCompat$* { *; }
-keep class androidx.core.content.pm.ShortcutInfoCompat$* { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# --- Prevent R8 from exposing string constants that reveal app logic ---
-dontnote **
