#!/bin/bash
VERSION=$(git describe --tags --always)
rm -rf dist
./gradlew assembleRelease
mkdir dist
cd dist
cp ../app/build/outputs/apk/release/app-release.apk sndcpy.apk
cp ../sndcpy .
cp ../sndcpy.bat .
zip sndcpy-"$VERSION".zip sndcpy.apk sndcpy sndcpy.bat
