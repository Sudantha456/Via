# Keep the JavaScript bridge, it is resolved by name from injected JS.
-keepclassmembers class com.via.bgplay.PlaybackBridge {
    public *;
}
-keep class com.via.bgplay.PlaybackBridge { *; }
