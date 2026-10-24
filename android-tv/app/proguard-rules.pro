# The native AirPlay bridge finds these by name over JNI.
-keep class com.mangoscreencast.tv.airplay.AirPlayNative { *; }
-keep interface com.mangoscreencast.tv.airplay.AirPlayCallbacks { *; }
-keep class * implements com.mangoscreencast.tv.airplay.AirPlayCallbacks { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
