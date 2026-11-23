# Neutrino Photos for Android

The Android port of `neutrino_photos_ios_mobile`: an end-to-end encrypted photo and video library
in Neutrino Drive. It has a timeline, a viewer, import from the system photo picker or a share,
background backup of the phone's library, albums, favourites, archive and trash. A photo added
here opens on the web and iOS, and the other way round. Everything goes through
[`wcherry/neutrino`](https://github.com/wcherry/neutrino) (`/api/v1`). The app has no backend of
its own.

- `core/` is plain Kotlin/JVM:
  - the Photos and albums API client
  - the import pipeline: upload, register, metadata, preview rendition
  - the Google Motion Photo reader and the timeline grouping
  - the library with its offline listing, downloads through the shared decrypt, and the caches
- `app/` is the Android app (Jetpack Compose). It holds what only Android can do: the picker and
  MediaStore, EXIF, thumbnails and previews, HEIC → JPEG, video frames, playback, and the
  WorkManager backup.

## Layout

This repository must sit next to `neutrino_shared_android`, which it builds from source:

```
getneutrino.app/
├── neutrino_shared_android/
└── neutrino_photos_android_mobile/   ← this repo
```

## Build

To install the app on an emulator or phone, see [`INSTALL.md`](INSTALL.md).

Needs JDK 17 and the Android SDK (API 36).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :core:test             # Motion Photo, timeline, metadata, services against a fake server
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

## Platform registration

- **OAuth client:** `neutrino-photos-android` is seeded by `neutrino/migrations/00160_oauth__*`.
  Sign-in fails against a server without it.
- **App config and brand:** `NeutrinoApps.photos` (shared) and `PhotosBrand`
  (`app/.../ui/Components.kt`). The prefs/secrets prefix is `nphoto`, the same as the iOS app's.
  Never change or reuse it.

From the emulator, a server on your computer is `http://10.0.2.2:8080`. See
`agent_docs/android-port.md` for the decisions, what is verified, and what is left.
