# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# ── Google API Client + Drive ─────────────────────────────────────────────────
# The Google API client library uses reflection to instantiate model classes
# (GenericJson subclasses, HttpRequestInitializer, etc.). R8 strips these by
# default in release builds, causing IllegalArgumentException / key error at
# the first Drive API call.

-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }

# Keep all GenericJson subclasses (Drive resource model objects)
-keep class * extends com.google.api.client.json.GenericJson { *; }
-keep class * extends com.google.api.client.util.Key { *; }

# Keep annotations and generic signatures used by the HTTP/JSON layer
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Suppress warnings from optional/unused transitive deps
-dontwarn com.google.api.client.**
-dontwarn com.google.common.**
-dontwarn org.apache.http.**
-dontwarn android.net.http.**
