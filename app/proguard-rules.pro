# The source is public, so names are kept: a stack trace reads as the code does. Only unused code goes.
-dontobfuscate

# Started by name with app_process, as the shell user, from this same APK (phone/HelperClient.kt).
-keep class dev.miubridge.helper.HelperMain { public static void main(java.lang.String[]); }

# Optional pieces of the libraries that Android does not have and the app does not use.
-dontwarn org.bouncycastle.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
