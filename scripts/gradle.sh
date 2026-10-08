#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
if [[ "${SENTRI_GRADLE_DIRECT:-0}" == "1" ]]; then
  exec ./gradlew -Dhttp.proxyHost= -Dhttps.proxyHost= "$@"
fi
exec ./gradlew "$@"
