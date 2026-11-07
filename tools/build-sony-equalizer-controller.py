#!/usr/bin/env python3
"""Build a compatible Sony Equalizer controller in staging; leave its source tree intact."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, required=True)
parser.add_argument('--output', type=Path, help='Defaults to ../releases/sony-equalizer next to the source directory')
args = parser.parse_args()
source = args.source.resolve()
output = (args.output or source.parent / 'releases/sony-equalizer/SonyEqualizer-v1.16.apk').resolve()
with tempfile.TemporaryDirectory(prefix='walkman-sony-controller-') as temporary:
    stage = Path(temporary) / 'controller'
    stage.mkdir()
    for directory in ['src', 'res', 'assets', 'lib', 'tools']:
        shutil.copytree(source / directory, stage / directory)
    for name in ['AndroidManifest.xml', 'build.sh']:
        shutil.copy2(source / name, stage / name)
    # Keep the controller's existing update signature. Do not generate a new key.
    key = source / 'build/local.jks'
    if not key.is_file():
        raise SystemExit('Missing original controller signing key: build/local.jks')
    (stage / 'build').mkdir()
    shutil.copy2(key, stage / 'build/local.jks')
    provider = stage / 'src/com/sony/songpal/localplayer/playbackservice/SonySettingsProvider.java'
    text = provider.read_text()
    old = 'pkg.equals("com.sonyericsson.music.emby")'
    if 'pkg.equals("com.djmanri3.Walkman")' not in text:
        if old not in text:
            raise SystemExit('Controller allowlist changed; inspect before adapting')
        text = text.replace(old, old + '||pkg.equals("com.djmanri3.Walkman")')
    provider.write_text(text)
    manifest = stage / 'AndroidManifest.xml'
    text = manifest.read_text()
    legacy = 'android:versionCode="16"' in text and 'android:versionName="1.15"' in text
    compatible = 'android:versionCode="17"' in text and 'android:versionName="1.16"' in text
    if not (legacy or compatible):
        raise SystemExit('Expected Sony Equalizer 1.15 (16) or 1.16 (17); review version before updating')
    manifest.write_text(text.replace('android:versionCode="16"', 'android:versionCode="17"')
                       .replace('android:versionName="1.15"', 'android:versionName="1.16"'))
    env = os.environ.copy()
    env['EQ_OUTPUT_DIR'] = str(Path(temporary) / 'release')
    subprocess.run(['bash', str(stage / 'build.sh')], env=env, check=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(Path(env['EQ_OUTPUT_DIR']) / 'sony-equializer_1.0.apk', output)
    print('Controller APK:', output)
