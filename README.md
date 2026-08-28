# Walkman — App Android con WebView integrada al sistema multimedia

Aplicación Android que abre `https://djmanri3.github.io/walkman-server/`
en un WebView y se integra **totalmente con el motor multimedia de Android**:
el widget de reproducción, la barra de notificaciones, la pantalla de bloqueo,
los botones físicos/bluetooth y el audio focus.

## Cómo funciona la integración

- La web WALKMAN ya incluye un puente para apps nativas
  (`AndroidBridge.setMediaState(JSON)`). Esta app inyecta ese objeto con
  `WebView.addJavascriptInterface(..., "AndroidBridge")`.
- Cada vez que la web cambia de pista o reproduce/pausa, el puente actualiza
  la `MediaSession` de Android y la notificación `MediaStyle`.
- Un servicio en primer plano (`MediaService`) mantiene viva la sesión para que
  el widget siga funcionando en segundo plano.
- Los comandos del sistema (play/pause/next/prev/seek/repeat/shuffle) se
  devuelven a la web mediante `evaluateJavascript` llamando a las funciones
  nativas de la web (`togglePlay()`, `nextTrack()`, ...).
- Un proceso de *polling* lee `audio.currentTime` del WebView para mantener la
  barra de progreso del widget sincronizada.

## Estructura

```
app/src/main/java/com/djmanri3/Walkman/
├── MainActivity.java   # WebView + inyección del puente + comandos JS
├── AndroidBridge.java  # Interfaz JavaScript -> Android (setMediaState)
└── MediaService.java   # MediaSession, notificación MediaStyle, bluetooth
app/src/main/res/       # Recursos (layouts, iconos, textos, estilos)
app/src/main/AndroidManifest.xml
build.gradle / settings.gradle / gradle wrapper
```

## Herramientas necesarias

Para compilar el APK necesitas:

| Herramienta | Versión | Requisito |
|---|---|---|
| [JDK](https://adoptium.net/) | 17+ | Java (Android Studio / OpenJDK / Temurin) |
| [Android SDK](https://developer.android.com/studio#cmdline-tools) | platform 34 + build-tools 34.0.0 | Compilación de la app |
| Gradle | 8.9 (lo baja el wrapper solo) | No hay que instalarlo |
| [ADB](https://developer.android.com/tools/adb) | incluido en el SDK | Solo para instalar/probar en un dispositivo |

Detalles:

- **JDK**: se usa Java 17 en este proyecto. Si tu JDK no está en la ruta por
  defecto, ajusta `org.gradle.java.home` en `gradle.properties`.
- **Android SDK**: `local.properties` apunta con `sdk.dir=` a la ruta de tu
  SDK. Ajusta esa ruta si tienes el SDK en otro sitio. Acepta las licencias
  con `sdkmanager --licenses`.
- El **gradle wrapper** (`./gradlew`) descarga Gradle 8.9 automáticamente en la
  primera ejecución.

## Compilar el APK

```bash
# APK de depuración (firma de debug, lista para instalar)
./gradlew :app:assembleDebug

# APK de release (sin firmar; firmarlo con keystore propio para publicar)
./gradlew :app:assembleRelease
```

El APK de depuración queda en `app/build/outputs/apk/debug/app-debug.apk`
y el de release en `app/build/outputs/apk/release/app-release.apk`.

Para ambas variantes puedes añadir `--stacktrace` si falla algo y quieres ver
el error completo.

## Instalar y probar

Con un dispositivo conectado (o un emulador):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.djmanri3.Walkman/.MainActivity
```

En Android 13+ la app pide permiso de notificaciones al abrirse (necesario para
mostrar el widget). Tras configurar tu servidor Emby/Jellyfin/Plex dentro de la
web, al reproducir una pista aparecerán los controles multimedia en la barra de
notificaciones y en la pantalla de bloqueo.

## Notas

- `local.properties` apunta al SDK (`sdk.dir=...`). Ajusta esa ruta si tu SDK
  está en otro sitio.
- El audio de red se reproduce desde los servidores que configures; el WebView
  usa `MIXED_CONTENT_COMPATIBILITY_MODE` para tolerar medios http/https mixtos.
