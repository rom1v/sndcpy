#!/bin/bash
set -e
./gradlew assembleDebug
SNDCPY_APK=app/build/outputs/apk/debug/app-debug.apk ./sndcpy "$@"
