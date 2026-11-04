# Releasing PixelPal

Operational checklist for cutting a release build. **No keystore and no
signing secret is committed to this repository** — release artifacts are
unsigned unless you supply signing properties locally or CI supplies them
from repository secrets.

---

## 1. Version bump

`app/build.gradle.kts` → `defaultConfig`:

```kotlin
versionCode = 3          // must strictly increase for every published build
versionName = "1.2.0"    // user-visible
```

Both are also exposed to code as `BuildConfig.VERSION_CODE` / `VERSION_NAME`
(e.g. recorded in each crash-log entry), so keep them honest.

## 2. Build the unsigned release APK

```bash
./gradlew :app:assembleRelease
```

Release builds run **R8 minification + resource shrinking**. The rules for
Hilt, Room, kotlinx.serialization, Lottie, WorkManager, CameraX/ML Kit and the
Firestore data classes live in `app/proguard-rules.pro` — read the comments
there before adding a rule, and prefer a narrowly-scoped rule with a reason
over a blanket `-dontwarn`.

Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

That is exactly what CI produces and uploads as an artifact, so a green CI run
is a good signing dry-run.

## 3. Sign it

### Locally

Pass the four signing properties (from the command line, `~/.gradle/gradle.properties`,
or an untracked `gradle.properties`):

```properties
RELEASE_STORE_FILE=/abs/path/pixelpal-release.jks
RELEASE_STORE_PASSWORD=…
RELEASE_KEY_ALIAS=pixelpal
RELEASE_KEY_PASSWORD=…
```

`app/build.gradle.kts` registers the `release` signing config **only when
`RELEASE_STORE_FILE` is set**, so a normal checkout keeps building unsigned.

```bash
./gradlew :app:assembleRelease \
  -PRELEASE_STORE_FILE="$HOME/keystores/pixelpal-release.jks" \
  -PRELEASE_STORE_PASSWORD="…" \
  -PRELEASE_KEY_ALIAS=pixelpal \
  -PRELEASE_KEY_PASSWORD="…"
# → app/build/outputs/apk/release/app-release.apk
```

Verify with `apksigner verify --print-certs app-release.apk`.

### In CI (GitHub Actions)

`.github/workflows/android.yml` does **not** sign by default. To enable
signed CI builds, add these repository secrets and uncomment the
"Signed release" step:

| Secret | Contents |
|---|---|
| `RELEASE_STORE_BASE64` | `base64 -w0 pixelpal-release.jks` |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

Keep the keystore out of git; back it up offline. Losing it means you can never
update the same application id again on Play.

## 4. Pre-flight checks

```bash
./gradlew :app:assembleDebug            # debug build still compiles
./gradlew :app:testDebugUnitTest        # JVM unit tests
./gradlew :app:assembleRelease          # R8/shrinking pass (unsigned)
```

Optional on a connected device: `./gradlew :app:connectedDebugAndroidTest`.
Do not sign a release that fails any of the above.

## 5. Smoke-test the minified build

R8 failures are usually **runtime** failures. Before publishing, install the
signed (or unsigned, once self-signed) APK on a device and check the paths R8
tends to break:

- Cold start → Home renders the companion (Hilt + Room surviving minification).
- Sign in / restore session → cloud data appears (Firestore data classes map
  their fields after obfuscation).
- Open the AI Agent screen → Gemini reply stream and generic HTTP poll decode
  their JSON envelopes (kotlinx.serialization).
- Trigger a reminder → Lottie animation plays and the overlay bubble appears.
- QR scanner opens the camera (CameraX + ML Kit barcode).
- Force a crash on a throwaway build and confirm a record appears (see below).

## 6. Crash visibility

The app has **no remote crash reporter**. `PixelPalApplication` installs a
process-wide `UncaughtExceptionHandler` that appends fatal crashes to
`filesDir/crashes/crash_log.jsonl` (newest last, capped at 20 entries) and
then delegates to the platform handler. Crashes are also logged through Timber
in debug builds.

Pull crashes from a device:

```bash
adb shell run-as com.pixelpal.app cat files/crashes/crash_log.jsonl
```

Each line is a JSON object: `timestamp`, `versionName`, `versionCode`,
`thread`, `message`, `stackTrace`.

If you later add Crashlytics, this file-backed logger can stay as the
offline/no-SDK fallback — it costs nothing at runtime.

## 7. Publish

1. Tag the commit: `git tag -a v1.2.0 -m "PixelPal 1.2.0" && git push --tags`.
2. Attach `app-release.apk` to the GitHub release.
3. Update the download link at the top of `README.md` if the asset name changes.
