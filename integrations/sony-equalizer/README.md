# Motor Sony original en Walkman Web

Walkman 5.0 incorpora el motor nativo de Sony Music Center 7.6.0 procedente de
`/home/manri/gitlab/walkman-dlna/sony-equalizer`. Las bibliotecas ARM64 y ARM32 y
`dsx_param_file.bin` se copian sin modificar y conservan los derechos de sus
titulares. `native-assets.sha256` permite comprobar su integridad.

El audio HTTP/HTTPS y de contenido accesible se decodifica con MediaCodec a
PCM16, pasa por el DSP Sony y sale por AudioTrack. La canción se procesa en
bloques, sin convertirla completa en un archivo temporal. La web conserva la
biblioteca, la cola y las decisiones de reproducción; el puente adapta su
`audio-player` para usar el motor nativo. El WebView no reproduce otra copia del
audio. Pausa, búsqueda, volumen, fin de pista, foco de audio y los controles de
Android Auto siguen la misma sesión. Los visualizadores reciben un FFT del PCM
posterior al DSP, sin grabación ni permisos de micrófono.

El botón de ecualizador abre Sony Equalizer. Sus cinco bandas, Clear Bass y VPT
se reciben mediante ContentObserver. Se respetan «Aplicar a otras apps», la
selección de aplicaciones y el interruptor de ecualización. La declaración
`local.extracted.equalizer.NATIVE_SONY` evita añadir encima los efectos Android.
Si el controlador no está instalado o su panel está deshabilitado al cargar la
webapp, se conserva la reproducción web y su ecualizador Web Audio. Al instalar
Sony Equalizer, vuelve a abrir la app para activar el motor Sony.

## APK

- `releases/Walkman-v5.0-SonyEQ.apk`: app con el DSP integrado.
- `/home/manri/gitlab/walkman-dlna/releases/sony-equalizer/SonyEqualizer-v1.16.apk`:
  controlador compatible, versión 17, distribuido en el repositorio `walkman-dlna`.

El controlador 1.16 autoriza `com.djmanri3.Walkman` en su proveedor. El
constructor admite tanto esta versión como la 1.15 anterior, a la que añade
la compatibilidad en una copia temporal y aumenta la versión a 1.16.
Conserva la firma de actualización y no modifica el árbol fuente del
controlador ni sus ajustes del dispositivo.

```bash
JAVA_HOME=/ruta/al/jdk17 ANDROID_HOME=/ruta/al/sdk \
  python3 tools/build-sony-equalizer-controller.py \
  --source /ruta/a/walkman-dlna/sony-equalizer
./gradlew :app:assembleDebug :app:lintDebug
```

El constructor requiere el `build/local.jks` original del controlador. No lo
incluye en este repositorio ni crea una firma alternativa.

Los adaptadores JNI se conservan en su paquete original. La copia de
SonyMediaPlayer omite la inicialización de listas de los antiguos reproductores
y expone métricas del PCM y descarta las preparaciones canceladas; PhonePlayer
añade el análisis del espectro. No se
modifican los algoritmos Sony ni sus bibliotecas.

## Verificación

```bash
node tools/tests/sony-audio.test.js
JAVA_HOME=/ruta/al/jdk17 ANDROID_HOME=/ruta/al/sdk \
  python3 tools/tests/run-sony-native-tests.py --app-classes \
  app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar
```

La prueba nativa requiere ambos APK instalados. Instala y retira una
instrumentación temporal, reproduce breves señales y usa un servidor HTTP
local mediante `adb reverse`. Compara hashes del PCM de Clear Bass y los modos
VPT, vuelta exacta a plano, streaming MP3, pausa, búsqueda, transición y acceso
al proveedor de ajustes desde el UID real de Walkman.

El motor original incluye ABI ARM de 32 y 64 bits. Los formatos de entrada
dependen de los decodificadores Android. No se añade DRM ni reproducción de
URLs `blob:` a través del motor nativo; la selección local de Android usa el
servidor HTTP local de la app.
