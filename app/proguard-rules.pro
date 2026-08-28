# Reglas ProGuard específicas de la app Walkman.
# El WebView y el puente JavaScript no deben ser ofuscados ni eliminados.
-keepclassmembers class com.manri.walkman.AndroidBridge {
    <methods>;
}
-keepclassmembers class com.manri.walkman.MainActivity {
    <methods>;
}
