# Gson rules
-keepattributes Signature, *Annotation*, EnclosingMethod
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }

# Keep your data models to prevent R8 from renaming fields or removing them
-keep class com.mario.movies.data.** { *; }

# Keep VLC classes
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.vlc.** { *; }
