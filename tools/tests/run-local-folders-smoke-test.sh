#!/usr/bin/env bash
set -euo pipefail
# Usage: script app.apk app-classes-directory documentfile-api.jar [device-serial]
if [ "$#" -lt 3 ]; then
    echo "Usage: $0 app.apk app-classes-directory documentfile-api.jar [device-serial]" >&2
    exit 2
fi
apk=$1
app_classes=$2
document_api=$3
sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
: "${sdk_root:?Set ANDROID_HOME}"
api_jar="$sdk_root/platforms/android-34/android.jar"
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
test_dir=$(mktemp -d /tmp/walkman-local-folders.XXXXXX)
mkdir -p "$test_dir/classes" "$test_dir/dex"
javac -source 8 -target 8 -cp "$api_jar:$app_classes:$document_api" -d "$test_dir/classes" \
    "$repo_root/tools/tests/androidx/documentfile/provider/FolderTestEntry.java" \
    "$repo_root/tools/tests/LocalFoldersSmokeTest.java"
"$sdk_root/build-tools/35.0.0/d8" --lib "$api_jar" --classpath "$app_classes" --classpath "$document_api" \
    --output "$test_dir/dex" "$test_dir"/classes/androidx/documentfile/provider/*.class \
    "$test_dir"/classes/com/djmanri3/Walkman/*.class
adb_args=()
if [ "$#" -ge 4 ]; then adb_args=(-s "$4"); fi
adb "${adb_args[@]}" push "$test_dir/dex/classes.dex" /data/local/tmp/walkman-local-folders-test.dex
adb "${adb_args[@]}" push "$apk" /data/local/tmp/walkman-local-folders-test.apk
adb "${adb_args[@]}" shell 'CLASSPATH=/data/local/tmp/walkman-local-folders-test.dex:/data/local/tmp/walkman-local-folders-test.apk app_process /system/bin com.djmanri3.Walkman.LocalFoldersSmokeTest'
