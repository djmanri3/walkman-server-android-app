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
