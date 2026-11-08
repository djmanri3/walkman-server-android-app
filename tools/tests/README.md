# Prueba de carátulas en Android

`ArtworkSmokeTest` comprueba imágenes grandes horizontales, verticales y con
proporciones extremas, imágenes pequeñas y archivos inexistentes. Se ejecuta
con el motor gráfico de un dispositivo Android conectado, sin instalar ni
reemplazar la app.

Desde la raíz del repositorio, con `javac`, `d8` y `adb` en el PATH:

```bash
ART_TEST_DIR=$(mktemp -d /tmp/walkman-art-test.XXXXXX)
ANDROID_API_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
mkdir -p "$ART_TEST_DIR/classes" "$ART_TEST_DIR/dex"
javac -source 8 -target 8 -cp "$ANDROID_API_JAR" -d "$ART_TEST_DIR/classes" \
  app/src/main/java/com/djmanri3/Walkman/ArtworkDecoder.java \
  app/src/main/java/com/djmanri3/Walkman/AlbumArtColor.java \
  tools/tests/ArtworkSmokeTest.java
d8 --lib "$ANDROID_API_JAR" --output "$ART_TEST_DIR/dex" \
  "$ART_TEST_DIR"/classes/com/djmanri3/Walkman/*.class
adb push "$ART_TEST_DIR/dex/classes.dex" /data/local/tmp/walkman-art-test.dex
adb shell 'CLASSPATH=/data/local/tmp/walkman-art-test.dex app_process /system/bin com.djmanri3.Walkman.ArtworkSmokeTest'
```

La prueba debe imprimir `PASS`. Para comprobar la app completa:

```bash
./gradlew :app:assembleDebug :app:lintDebug
```

Si `gradle.properties` apunta a un JDK que ya no existe, se puede usar
`-Dorg.gradle.java.home=/ruta/al/jdk17` sin cambiar la configuración del proyecto.

## Navegación Atrás

Con Node.js y Google Chrome instalados:

```bash
node tools/tests/back-navigation.test.js
```

Comprueba en un navegador real el regreso desde el reproductor, los botones de
volver de la biblioteca, el cierre de paneles y submenús, y el regreso desde el menú principal de My Music a Playing. No instala ni abre la app del dispositivo. El gesto nativo y
la interacción con el teclado deben comprobarse también en Android.

## Carga de Android Auto

Pruebas del puente JavaScript con servidores lentos, peticiones repetidas,
listas vacías, errores, cambios de biblioteca y restauración tardía de la conexión:

```bash
node tools/tests/android-auto-loading.test.js
```

Para probar los resultados asíncronos del servicio y la persistencia en un
dispositivo Android conectado, sin instalar la app, compilar primero el APK y
usar JDK 17, `adb` y `ANDROID_HOME` configurados:

```bash
bash tools/tests/run-android-auto-smoke-test.sh \
  app/build/outputs/apk/debug/app-debug.apk \
  app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar \
  /ruta/a/la/cache/gradle/media-1.7.0-api.jar
```

El tercer argumento es el JAR de AndroidX Media generado en la caché de Gradle.
La prueba verifica migración de cachés vacías antiguas, peticiones compartidas, categorías vacías, una biblioteca de
5.000 álbumes, el límite de navegación de 500 elementos, persistencia completa,
espera limitada y respuestas tardías. Debe imprimir `PASS`; tarda unos 14 s.
La prueba no sustituye una comprobación en la pantalla real de Android Auto.

## Reproducción desde Android Auto

```bash
node tools/tests/android-playback.test.js
```

Requiere Google Chrome. Reproduce el fallo del elemento `track-title-text`
ausente tras restaurar la cola, verifica su reparación antes de cambiar de
canción y comprueba que Reproducir y Pausar sean órdenes idempotentes. También
comprueba el inicio de una cola restaurada sin `audio.src`, la reanudación y
las colas vacías. La reproducción real se verifica adicionalmente en Android.

## Importación de música local

```bash
node tools/tests/local-import.test.js
mkdir -p /tmp/walkman-metadata-tests
javac -cp "$ANDROID_HOME/platforms/android-34/android.jar" \
  -d /tmp/walkman-metadata-tests \
  app/src/main/java/com/djmanri3/Walkman/EmbeddedCover.java \
  app/src/main/java/com/djmanri3/Walkman/LocalAudioQuality.java \
  tools/tests/EmbeddedCoverFlacTest.java
timeout 10 java -cp "/tmp/walkman-metadata-tests:$ANDROID_HOME/platforms/android-34/android.jar" \
  com.djmanri3.Walkman.EmbeddedCoverFlacTest
```

Comprueba la finalización de la carga al importar, cancelar, seleccionar una
carpeta vacía o fallar el lector o la web. La prueba FLAC verifica metadatos y
portada, archivos truncados, ausencia de marca de último bloque y bloques de
imagen grandes. El límite de tiempo detecta el antiguo bucle infinito al leer
los bloques FLAC desalineados. Requiere JDK 17 y el SDK de Android.
También comprueba códec, frecuencia de muestreo, profundidad de bits y canales
del STREAMINFO de FLAC (44,1/48/96/192 kHz, 16/24/32 bits).
