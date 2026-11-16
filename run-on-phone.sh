#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME=/home/ravomaniry/.jdks/jdk-21.0.11+10
export PATH="${JAVA_HOME}/bin:${PATH}"

cd /home/ravomaniry/Documents/code/doremi-apk
/home/ravomaniry/Documents/code/doremi-apk/gradlew :app:installDebug
/home/ravomaniry/Android/Sdk/platform-tools/adb shell am force-stop mg.maniry.doremii
/home/ravomaniry/Android/Sdk/platform-tools/adb shell am start -n mg.maniry.doremii/.browser.BrowserActivity
