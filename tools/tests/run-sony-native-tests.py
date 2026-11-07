#!/usr/bin/env python3
"""Run the packaged original DSP and provider tests inside the installed Walkman UID."""
import argparse, functools, http.server, os, pathlib, shutil, subprocess, tempfile, threading, zipfile
parser=argparse.ArgumentParser()
parser.add_argument('--app-classes',type=pathlib.Path,required=True)
args=parser.parse_args()
root=pathlib.Path(__file__).resolve().parents[2]
sdk=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Android/Sdk'))
java=pathlib.Path(os.environ.get('JAVA_HOME','/home/manri/jdk17/jdk-17.0.13+11'))/'bin'
bt=sdk/'build-tools/35.0.0';android=sdk/'platforms/android-35/android.jar'
def run(*args):subprocess.run([str(a) for a in args],check=True)
with tempfile.TemporaryDirectory(prefix='walkman-sony-native-test-') as temporary:
 work=pathlib.Path(temporary)
 for folder in ['classes','dex','assets','media']:(work/folder).mkdir()
 run('ffmpeg','-hide_banner','-loglevel','error','-f','lavfi','-i','aevalsrc=0.08*sin(2*PI*80*t)+0.06*sin(2*PI*1000*t)+0.03*sin(2*PI*7000*t):s=48000:d=2','-ac','2',work/'assets/probe.flac')
 run('ffmpeg','-hide_banner','-loglevel','error','-f','lavfi','-i','sine=frequency=120:duration=7','-ar','44100','-ac','2',work/'media/tone.mp3')
 run(java/'javac','-source','8','-target','8','-classpath',str(android)+':'+str(args.app_classes),'-d',work/'classes',root/'tools/tests/sony/VerifySony.java')
 run(bt/'d8','--min-api','24','--lib',android,'--classpath',args.app_classes,'--output',work/'dex',*sorted((work/'classes').rglob('*.class')))
 manifest=work/'AndroidManifest.xml'
 manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.djmanri3.Walkman.sonytests"><uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/><application android:label="Walkman Sony validation"/><instrumentation android:name="com.sony.songpal.localplayer.playbackservice.VerifySony" android:targetPackage="com.djmanri3.Walkman"/></manifest>')
 apk=work/'unsigned.apk'
 run(bt/'aapt','package','-f','-M',manifest,'-I',android,'-A',work/'assets','-F',apk)
 with zipfile.ZipFile(apk,'a',zipfile.ZIP_DEFLATED) as archive:archive.write(work/'dex/classes.dex','classes.dex')
 run(bt/'zipalign','-f','4',apk,work/'aligned.apk')
 run(bt/'apksigner','sign','--ks',pathlib.Path.home()/'.android/debug.keystore','--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--out',work/'test.apk',work/'aligned.apk')
 server=http.server.ThreadingHTTPServer(('127.0.0.1',8767),functools.partial(http.server.SimpleHTTPRequestHandler,directory=str(work/'media')))
 threading.Thread(target=server.serve_forever,daemon=True).start()
 try:
  run('adb','reverse','tcp:8767','tcp:8767')
  run('adb','install','-r',work/'test.apk')
  result=subprocess.run(['adb','shell','am','instrument','-w','com.djmanri3.Walkman.sonytests/com.sony.songpal.localplayer.playbackservice.VerifySony'],check=True,capture_output=True,text=True)
  print(result.stdout,flush=True)
  if 'INSTRUMENTATION_CODE: -1' not in result.stdout or 'result=PASS' not in result.stdout:
   raise SystemExit('Sony native verification failed')
 finally:
  subprocess.run(['adb','uninstall','com.djmanri3.Walkman.sonytests'],check=False)
  subprocess.run(['adb','reverse','--remove','tcp:8767'],check=False)
  server.shutdown();server.server_close()
