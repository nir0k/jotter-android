# Development

How to build, check and release Jotter for Android. What the application does is in [spec.md](spec.md).

## Requirements

- JDK 25 for the Gradle daemon, as `gradle/gradle-daemon-jvm.properties` asks (with an older JDK, Gradle downloads one); the code is compiled to JDK 17 bytecode. The JDK bundled with Android Studio works: point `JAVA_HOME` at its `jbr` folder. Without a JDK, `./gradlew` fails, and with `-q` the failure is hidden, so check for it before reading the result of a quiet run.
- Android SDK with platform 37, named in `local.properties` as `sdk.dir` or by `ANDROID_HOME`.
- A device or emulator with Android 16 (API 36) or newer to run the application.

## Build

```sh
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

The application's version comes from the root `VERSION` file; `versionCode` is `major * 1000000 + minor * 1000 + patch`.

## Modules

| Module | Content |
| --- | --- |
| `core` | Library format, body codec and editing model, code highlighting, index and search, rules, synchronisation and SFTP; plain Kotlin where possible, tested on the JVM |
| `designsystem` | The interface's theme, palettes, components and glyphs |
| `editor` | The native editor on Compose and the drawing of notes for exports |
| `data` | The Android side of the library: the open library and its index, device preferences, sync runs in the background with their notifications and credentials, geotags and exports |
| `ui` | What the feature screens share: frames, dialogs, item styles, file handling and the export dialog |
| `feature:notes` | The home screen, the note lists, search and the trees of collections and tags |
| `feature:note` | The note screen: the editor with its formatting bar, dialogs, information and image viewer |
| `feature:settings` | Settings: appearance and language, libraries, rules, themes, About and the first start |
| `feature:sync` | The Sync tab of Settings and the setup of an SFTP connection |
| `app` | The activities, the navigation between the features, capture from the shortcut, tile and share sheet, and the screenshot test of every screen |

Features depend on `ui`, `data` and the modules below them, never on each other; the app wires them together. Each module keeps its own strings in English and Russian: a string used by several features lives in `ui`, one used by `data` in `data`.

The settings the modules share — the SDK levels, JDK 17 bytecode, Compose and the Robolectric options of unit tests — live in the convention plugins of `build-logic/` (`jotter.android.application`, `jotter.android.library`, `jotter.jvm.library`); a module's build file names its plugin, namespace and dependencies.

## Code style

Code, comments, documentation, log messages and identifiers are in English. Every function has a KDoc comment that explains its purpose and behaviour; non-trivial ones also describe their arguments and return values. Screens are built from the components, theme roles, `Dimens`, `Shapes` and `AppIcons` of `designsystem`, without hard-coded colours, sizes or other icon sets. Dependencies are open source, published outside Google's proprietary SDKs, and pinned exactly in `gradle/libs.versions.toml`.

Rules the code keeps:

- The library lives in the application's private storage, and every write is atomic: a temporary file in the same folder, flushed and renamed over the target.
- Library files are read and written back byte for byte in the desktop's form; the fixtures check it.
- The kind of an inserted image is detected from its bytes against the desktop's allowlist, never from the declared type or the extension alone.
- Passwords, passphrases, private keys and other credentials never reach logs, notifications or crash reports.
- The editor reads only the open note's attachment folder, has no network access and runs nothing of a note's content.
- No Google Play services or other proprietary dependencies.
- The library format and synchronisation follow the [Jotter desktop specification](https://github.com/nir0k/Jotter/blob/main/docs/spec.md); a change to either is made there first.

## Checks

The full set of checks:

```sh
./gradlew :core:test testDebugUnitTest lintDebug :app:assembleDebug :app:verifyRoborazziDebug
```

| Task | Covers |
| --- | --- |
| `:core:test` | Library format and fixtures, the body codec and the editor's commands, code highlighting, index and search, rules, organisation, synchronisation, export without drawing |
| `testDebugUnitTest` | The unit tests of every Android module, on Robolectric where they need Android: the editor, the data layer, the features and the Roborazzi screenshot test of `app` |
| `lintDebug` | Android lint of every Android module |
| `:app:verifyRoborazziDebug` | Screens against the images in `app/screenshots/` |

### Screenshots

New and changed screens are recorded, and the changed images are reviewed before they are committed:

```sh
./gradlew :app:recordRoborazziDebug
```

Each screen is drawn in light English, dark Russian, large text and the Monokai palette.

### SFTP test stand

Without further settings the sync scenarios run against a local folder. With `JOTTER_ANDROID_SFTP` they run against an SFTP server, each in a folder of its own, together with the checks of host keys, sign-in, the probe and choosing a folder. The disposable OpenSSH stand of the [Jotter desktop project](https://github.com/nir0k/Jotter/tree/main/tests) (`docker compose up -d` in its `tests/`) or an SFTPGo container serves:

```sh
JOTTER_ANDROID_SFTP=sftp://jotter:jotter@127.0.0.1:2222/library ./gradlew :core:test
```

`JOTTER_ANDROID_SFTP_KEY` names a private key the stand accepts, for the key sign-in. These are test settings; the application never reads them.

## Fixtures

`core/src/test/resources/fixtures/` holds library files written by the Jotter desktop application, which `core` reads and writes back byte for byte, and the merge cases of synchronisation; its README describes each file and how a fixture is changed. `./gradlew :core:test` checks them.

### Loading fixtures into a debug build

The library lives in private storage, which only a debuggable build lets a shell reach. The fixtures go into a new folder beside the other libraries, whose names are their identifiers, so nothing on the device is overwritten:

```sh
PACKAGE=io.github.nir0k.jotter
LIBRARY=01a1f000-0000-7000-8000-00000000e4e4

adb shell run-as $PACKAGE mkdir -p files/libraries/$LIBRARY
tar -C core/src/test/resources/fixtures/library -cf - . | adb exec-in run-as $PACKAGE tar -xf - -C files/libraries/$LIBRARY
```

Settings → Library then lists it by the date in its `library.json`, and choosing it opens it and builds its index. In the fixtures, Lists is archived and Links is in the trash. To remove it, switch to another library first:

```sh
adb shell run-as $PACKAGE rm -rf files/libraries/$LIBRARY
```

## Translations

Interface text lives in Android string resources, never in code: each module with screens or notifications has `src/main/res/values/strings.xml` (English) and `values-ru/strings.xml` (Russian), and keeps there the strings it alone uses; a string several features use lives in `ui`. A new or changed string is added to both; lint reports a string missing from the Russian resources.

## Release

Releases are signed APKs attached to GitHub releases.

1. Raise the version in `VERSION`.
2. Commit to a branch, open a pull request into `main` and delete the branch after the merge.
3. Build the signed APK from the merged `main`. Signing reads `keystore.properties` in the root or the variables `JOTTER_ANDROID_KEYSTORE_FILE`, `JOTTER_ANDROID_KEYSTORE_PASSWORD`, `JOTTER_ANDROID_KEY_ALIAS` and `JOTTER_ANDROID_KEY_PASSWORD`; without them the release build stays unsigned. The keystore and its passwords are never committed.

   ```sh
   ./gradlew :app:assembleRelease
   ```

4. Attach `app/build/outputs/apk/release/app-release.apk` to a release tagged `v<version>`, named `jotter-android-<version>.apk`, with notes on what changed for the people who use the application:

   ```sh
   cp app/build/outputs/apk/release/app-release.apk jotter-android-<version>.apk
   gh release create v<version> jotter-android-<version>.apk --title "Jotter for Android <version>" --notes-file notes.md
   ```
