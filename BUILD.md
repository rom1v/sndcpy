# Build

## Debug

This project is an Android application with some shell scripts to execute on the
computer. Therefore, just use `gradle` as usual:

```
./gradlew assembleDebug
```

(or build from _Android Studio_)

To run it:

```bash
./run
./run <serial>  # if several devices are connected
```

_Since building is very fast, `./run` also executes `./gradlew assembleDebug` to
always run an up-to-date version._


## Release

To build and install a release, you need to generate a signed APK.

For that purpose, first generate a _keystore_:

```bash
# generate sndcpy.keystore file
keytool -genkey -v -keystore sndcpy.keystore -alias sndcpy \
        -keyalg RSA -keysize 2048 -validity 30000
```

Then, add these lines (and adapt) in `~/.gradle/gradle.properties`:

```bash
SNDCPY_STORE_FILE=/path/to/your/sndcpy.keystore
SNDCPY_STORE_PASSWORD=the_keystore_password
SNDCPY_KEY_ALIAS=sndcpy
SNDCPY_KEY_PASSWORD=the_key_password
```

Then, execute `./release.sh`. It will generate a release in `dist/`.
