#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 3 ]; then
    echo "Usage: $0 app.apk app-classes.jar media-api.jar" >&2
    exit 2
fi
apk_path=$1
app_classes=$2
media_api=$3
sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
: "${sdk_root:?Set ANDROID_HOME to the Android SDK directory}"
api_jar="$sdk_root/platforms/android-34/android.jar"
d8_path=$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -name d8 -type f | sort -V | tail -n 1)
: "${d8_path:?Android build-tools d8 not found}"
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
test_dir=$(mktemp -d /tmp/walkman-aa-smoke.XXXXXX)
trap 'rm -rf "$test_dir"' EXIT
mkdir -p "$test_dir/classes" "$test_dir/dex"
javac -source 8 -target 8 -cp "$api_jar:$app_classes:$media_api" -d "$test_dir/classes" \
    "$repo_root/tools/tests/androidx/media/BrowseTestResult.java" \
    "$repo_root/tools/tests/AndroidAutoLoadSmokeTest.java"
"$d8_path" --lib "$api_jar" --classpath "$app_classes" --classpath "$media_api" \
    --output "$test_dir/dex" "$test_dir"/classes/androidx/media/*.class \
    "$test_dir"/classes/com/djmanri3/Walkman/*.class
adb push "$test_dir/dex/classes.dex" /data/local/tmp/walkman-aa-test.dex
adb push "$apk_path" /data/local/tmp/walkman-aa-test.apk
adb shell 'CLASSPATH=/data/local/tmp/walkman-aa-test.dex:/data/local/tmp/walkman-aa-test.apk app_process /system/bin com.djmanri3.Walkman.AndroidAutoLoadSmokeTest'
