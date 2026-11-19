#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

sdk_dir="$(sed -n 's/^sdk.dir=//p' local.properties | tr -d '\r')"
sdk_dir="${sdk_dir//\\:/:}"
sdk_dir="${sdk_dir//\\\\/\\}"

if [[ -n "${ANDROID_HOME:-}" ]]; then
  root="$ANDROID_HOME"
elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  root="$ANDROID_SDK_ROOT"
else
  root="$sdk_dir"
fi

adb_bin="$root/platform-tools/adb"
if [[ ! -x "$adb_bin" ]]; then
  if ! adb_bin="$(command -v adb)"; then
    echo "adb not found. Set sdk.dir in local.properties or add platform-tools to PATH." >&2
    exit 1
  fi
fi

exec "$adb_bin" shell am start -n mg.maniry.doremii/.browser.BrowserActivity
