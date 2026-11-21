# CLAUDE.md — Neutrino Photos for Android

Part of the Neutrino platform — read the root `../CLAUDE.md` coupling rules first. This is the
Android port of `neutrino_photos_ios_mobile`, built on `../neutrino_shared_android` (consumed by
local path through `includeBuild`, unversioned — a change there reaches this app and every other
Android app at once). Its OAuth client id is `neutrino-photos-android`
(`neutrino/migrations/00160_oauth__*`); its prefs/secrets prefix is `nphoto`. Never change or
reuse either.

- Build with JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Put logic in `:core` (JVM-tested), not `:app`. `:app` holds only what needs Android: the picker,
  MediaStore, EXIF, bitmaps, video frames, playback, WorkManager.
- **The library is Photos, the bytes are Drive.** An import is a Drive upload into the root, with
  no folder, which is where the web looks; then `POST /photos` to register it. A record's
  `metadata` is written only by merge (`PATCH /photos/{id}/metadata`), never with `PUT`. That is
  rule 4 of `neutrino/agent_docs/live-photos.md`, and the metadata shape is the web's
  `PhotoMetadata` key for key.
- **Formats shared with the web and iOS, which change everywhere or nowhere:**
  - the 512 px plaintext cover thumbnail (`thumbnail_b64`, sent before the file part)
  - the 2048 px encrypted preview in the root folder "Photo Previews", named
    `<original file id>.preview.jpg` (the name is the index)
  - the capture date, sent exactly as `yyyy-MM-ddTHH:mm:ss` in UTC
  - `motionPhoto` for a Google Motion Photo (`core/media/MotionPhoto.kt` is a port of the web's
    `xmp.ts`; its tests are the web's)
  - Live Photo clips hidden by `device.liveVideoFileID` or a shared content identifier
- **Location is held back by default.** The server stores `metadata` in the clear. Coordinates go
  up only with Settings › Privacy on; with it off, the patch removes any already there with `null`s.
- File bodies are E2EE through the shared code: NEB1 on the way up, and on the way down
  `FileCrypto.decryptFile`, which reads every body shape. Never add crypto here: in
  neutrino#318, five clients that each had their own decrypt could not read the new body shape.
- **One import at a time** (`ImportPipeline`'s lock), with the ledger checked inside it. A JPEG is
  fingerprinted from its image data, not its metadata: the photo picker strips location, so a
  picked photo and the same photo found by backup differ only there. Both rules exist because the
  same photo was once uploaded twice.
- Status, decisions, the iOS → Android feature map and the next-session checklist are in
  `agent_docs/android-port.md`.
