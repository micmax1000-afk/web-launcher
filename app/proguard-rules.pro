# Web Launcher: il codice dell'app resta intero (Gson, WorkManager e i bridge JavaScript
# usano la riflessione); R8 toglie solo il codice inutilizzato delle librerie.
-keep class com.weblauncher.app.** { *; }
-keepclassmembers class com.weblauncher.app.** { *; }

# Gson (2.10 non include regole proprie)
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-dontwarn sun.misc.**

# WebView JavaScript bridges
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# Stack trace leggibili nei rapporti di errore
-keepattributes SourceFile, LineNumberTable
-dontobfuscate
