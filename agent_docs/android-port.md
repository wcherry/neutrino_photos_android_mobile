# Neutrino Photos for Android: port notes and handoff

**Started:** 2026-11-21.

**Status:**
- The iOS Photos app's v1 features are in, except the ones under "Not ported".
- Tested on an Android 14 emulator against a real local server.
- Never yet run on a physical device or against the hosted server.

## 1. Decisions

| Decision | Why |
|---|---|
| Kotlin + Jetpack Compose, `minSdk 26`, `targetSdk 36` | Same toolkit and minimum Android version as the other Android apps, so the shared library has one target. |
| `:core` is plain Kotlin/JVM; `:app` is Android | Everything that is not Android is tested on the JVM against `FakeServer` with real crypto. That covers the API client, import pipeline, Motion Photo reader, timeline, library and caches. The Photos service is faked in the test, in the server's response shapes. |
| The library is Photos, the bytes are Drive | As on iOS and the web: upload into the Drive root (no folder), then `POST /photos`. `metadata` is written only by merge (`PATCH`), per `neutrino/agent_docs/live-photos.md` rule 4. |
| Uploads: NEB1, key sent with the upload, then the key `PUT` again | As Drive for Android. The second call is an upsert for a server that ignored the key in the body. |
| Downloads through `FileCrypto.decryptFile` | The shared reader handles v0, NEB1 and chunked Photos bodies. A file with no key ref is served as it stands, as on the web. |
| The rendition ladder is iOS `MediaRendition` | A 512 px plaintext cover is sent as `thumbnail_b64` before the file part. A 2048 px encrypted preview goes in "Photo Previews" as `<id>.preview.jpg`, but only when it is under 70 % of the original's size. The viewer opens on the cover, then the preview, then the original. |
| HEIC is converted to full-resolution JPEG (quality 90), keeping the EXIF | The iOS rule: most browsers cannot show HEIC. |
| Capture date from EXIF `DateTimeOriginal` plus `OffsetTimeOriginal` (or the phone's zone), sent as `yyyy-MM-ddTHH:mm:ss` UTC | The server parses that one shape and drops anything else silently. |
| Google Motion Photos are classified from their XMP (`MotionPhoto.kt`, a port of the web's `xmp.ts`) | The `motionPhoto` record is the web's `MotionPhotoInfo`, `embedded` offset included. Playback slices the MP4 out of the still's own bytes. Apple Live Photo clips made elsewhere are hidden and played through `liveVideoFileID` or a shared content identifier. |
| Location is held back by default | The server stores `metadata` in the clear. As iOS `publishesLocationMetadata`. |
| Picker names become camera names | The system photo picker reports a bare number (`1000000021`). Such a name is renamed from the capture date: `IMG_20260310_093000.jpg`. |
| De-duplication by MediaStore id and by content hash; a JPEG's hash covers only the data from its start-of-scan marker on | The picker strips location from what it hands over. Without this, a photo picked once and then found by backup was uploaded a second time. |
| One import at a time, behind a lock that also covers the ledger check | Two backup runs at once each found the same photos new. That happened, which is why the lock exists. |
| Backup: WorkManager, every 6 hours, Wi-Fi only by default, battery not low | Android's equivalent of the iOS full-library import, run in the background. It can resume because the ledger records what is done. |
| OAuth client `neutrino-photos-android`, migration `00160` | Root CLAUDE.md rule 3. **Sign-in fails against any server without it.** |
| Prefix `nphoto` | Same namespace as iOS Photos. |

## 2. Feature map (iOS → Android)

**Ported:**
- **Account:** sign-in and registration, key setup, pairing, recovery kit, key code, devices.
- **Timeline:**
  - grouped by day, month or year, with a pinch between them
  - multi-select
  - capture-date order
  - pull to refresh
  - drawn from the cached listing before the network answers
- **Viewer:**
  - progressive loading: cover, then preview, then original
  - pinch and double-tap zoom up to 10×, with clamped pan
  - swipe between items, info panel
- **Media:** video playback; Motion Photo and Live Photo playback.
- **Import:**
  - the system photo picker, or a share from any app
  - HEIC → JPEG
  - EXIF capture date and metadata, thumbnail, preview
  - progress and stop
  - skips duplicates
- **Backup:** the whole library, resumable, with Wi-Fi-only and include-videos options.
- **Library management:**
  - favourites, archive, trash with restore, delete and empty, 30-day countdown
  - albums: list, create, rename, delete, open, add, remove, cover
  - Recently Added and Videos collections
- **Settings:**
  - theme and grouping
  - location sharing, backup
  - storage breakdown, cache cap, Optimize Storage (drop originals, keep previews), clear cache
  - app lock, report a bug
- **Sharing and links:**
  - share the decrypted original(s)
  - save to the phone's gallery (`Pictures/Neutrino`)
  - copy link
  - `/open/photo/<file id>` links

**Not ported:**
- search (iOS has no index behind it either)
- places and map, people, memories, editing, sharing photos with other people
- slideshow, external display
- the RAW originals path, and preview frames for videos (iOS does not have those yet either)

## 3. Deviations from iOS

- **Share target.** "Share → Neutrino Photos" from any app imports the shared media. iOS has no share extension.
- **Backup runs in the background** under WorkManager; iOS runs it in the foreground.
- **Fingerprinting:** a JPEG's content fingerprint ignores its metadata (see §1).

## 4. What is verified (2026-11-21, macOS host)

- **`./gradlew :core:test`: 20 tests pass.**
  - The web's `motionPhoto.test.ts` cases for the JPEG half, with its fixtures.
  - Timeline: claimed clips hidden, sort fallback, grouping, trash countdown.
  - Metadata: its shape, location withheld and cleared by the patch, the capture-date format, rendition names.
  - Services against the fake server with real crypto:
    - an import uploads ciphertext into the root, with the cover and every field before the file
    - it registers with the capture date, merges metadata without location, and files the preview by name
    - a second import of the same photo is a duplicate
    - another device decrypts the original and finds the preview
    - star, archive, trash, restore and empty trash
    - the listing kept for offline
    - albums
    - a Motion Photo's clip sliced from its own bytes
    - a copy with its metadata removed counts as the same photo
    - two imports of one photo at once upload it once
- **On the emulator** (Pixel 3a image, API 34) against a locally built server with migration 00160:
  - registration and key setup
  - picker import of JPEGs with EXIF: RATIONAL exposure values, an Orientation=6 photo, and a Google Motion Photo (Android's own picker recognises it as one)
  - a HEIC converted to JPEG at 4032 × 4032 with its date kept
  - backup of the library: three new items uploaded, the four already picked recognised, seven in total
  - year, month and day grouping
  - the viewer with its info panel, Motion Photo playback, video playback
  - favourite, a new album, delete, restore
  - the server rows checked directly: root folder, capture dates, metadata shape, previews in "Photo Previews"

**Not verified:**
- a physical device, a real camera's photos, or the hosted server
- an album made or a photo uploaded on the web or iOS, opened here
- a Live Photo imported on iOS, played here (implemented from the convention)
- save to gallery and the share sheet's receiving apps
- `/open/photo/` app links (they need `assetlinks.json` served)
- backup on a large library and over a long run
- app lock with biometrics
- a release (R8) build at run time

## 5. Next session checklist

1. On a physical phone, back up a real camera roll of a few hundred items, including Motion Photos and 4K video. Watch memory, battery and duplicates.
2. Open a photo uploaded here on the web (Motion badge, info panel) and on iOS. Open web and iOS uploads here.
3. Serve `deploy/assetlinks.json` with the release certificate and check `/open/photo/<id>`.
4. Build `./gradlew :app:assembleRelease` with a signing config and run it.
5. Consider a foreground-service notification for long backups. WorkManager's 10-minute window per run is enough today because each run resumes.
