# JNI: native code calls back into these by name.
-keep class com.windowsandroid.VmNative { *; }
-keepclasseswithmembernames class * { native <methods>; }
