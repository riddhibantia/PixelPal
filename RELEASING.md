# Releasing PixelPal

Releases are built **only by GitHub Actions** — see **docs/OPERATIONS.md** for
the multi-machine identity setup (two `gh` accounts, push rights, the draft
release visibility quirk, secret verification). This file covers the release
workflow internals and pre-flight checks.

Neither laptop builds or signs the
APK, so it does not matter which machine you are on:

```bash
git pull && git tag v1.3.1 && git push origin v1.3.1     # the whole release process
```

Actions stamps the version from the tag (`v1.3.1` → versionCode `10301`,
versionName `1.3.1`), builds a **signed** APK, refuses to publish if
`apksigner` says it is not properly signed, and attaches the APK, the bridge
bundle and `SHA256SUMS` to a **draft** GitHub Release. Review it, then press
**Publish release**.

Workflow: `.github/workflows/release.yml`.

**No keystore and no signing secret is ever committed.** It lives in GitHub
Secrets; a copy on a laptop is only a backup.

---

## Why GitHub builds it

Android will only install an update over an existing app if it is signed with the
**exact same private key**. Two laptops signing separately means two different
keys the moment anyone regenerates one — and every installed copy then refuses to
update, forcing users to uninstall and lose local data.

Signing in one place removes that failure mode entirely.

---

## One-time setup (repo owner)

### 1. The signing key

4096-bit RSA, alias `pixelpal`, valid until 2056, generated with
`keytool -genkeypair`. Confirm any copy matches before trusting it:

```
SHA256: 9B:6D:2E:84:EC:70:BB:D6:62:5D:42:33:54:06:51:24:62:5E:F5:36:EE:22:2E:69:B3:BF:84:43:96:55:E7:5F
Alias:  pixelpal

keytool -list -v -keystore pixelpal-release.jks -alias pixelpal | grep SHA256
```

> **Losing this file means you can never publish another update.** Every installed
> copy would have to be uninstalled and reinstalled. Back it up **offline**,
> outside both laptops — a password manager attachment or an encrypted cloud file.

### 2. Add the four repository secrets

GitHub → repo → **Settings → Secrets and variables → Actions → New repository secret**.

| Secret name | Value |
|---|---|
| `RELEASE_STORE_BASE64` | the whole `.jks`, base64-encoded on one line (~5,900 chars) |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | `pixelpal` |
| `RELEASE_KEY_PASSWORD` | key password (same as the store password for this key) |

```bash
base64 -w0 .release-keys/pixelpal-release.jks     # macOS: base64 -i file | tr -d '\n'
```

Or with the CLI — note that the plain `repo` scope is **not** enough to write
Actions secrets (the API returns `403`); use the web UI or re-authenticate with
the right scopes:

```bash
gh secret set RELEASE_STORE_BASE64   < <(base64 -w0 .release-keys/pixelpal-release.jks)
gh secret set RELEASE_STORE_PASSWORD < .release-keys/KEYPASS.txt
gh secret set RELEASE_KEY_ALIAS      --body pixelpal
gh secret set RELEASE_KEY_PASSWORD   --body "<keystore password>"
gh secret list                       # verify all four
```

---

## Cutting a release

The tag is the source of truth, not the working tree.

### Rules

- **Tags must be semver** (`vMAJOR.MINOR.PATCH`); the workflow fails rather than guess.
- **Never tag the same version twice.** `versionCode` is derived from the tag, so a
  re-tagged version produces an APK Android refuses to install over the previous one.
  Redo a release by bumping the patch number.
- Releases are **drafts** — nothing reaches users until you press Publish.

### If it fails

| Error | Cause |
|---|---|
| `Missing signing secrets` | step 2 not done, or a secret name typo |
| `Tag '…' is not semver` | tag like `v1.3` or `release-1.3.1` |
| `apksigner verify` fails | keystore in secrets is corrupt — re-encode and re-upload |
| Gradle compile error | broken code on `main`; fix before tagging |

---

## Pre-flight checks (before you tag)

```bash
./gradlew :app:assembleDebug           # debug build still compiles
./gradlew :app:testDebugUnitTest       # JVM unit tests
./gradlew :app:assembleRelease -PPIXELPAL_RELEASE_PRECHECK=true
                                       # R8/shrinking pass (unsigned is fine here)
```

`assembleDebug` does **not** exercise R8, so a ProGuard mistake can pass it and
only fail in release — always run `assembleRelease` at least once before tagging.
That is not hypothetical: an invalid `RoomDatabase\$Callback` rule once shipped to
`main` because only CI's release job compiled it.

Release builds run **R8 minification + resource shrinking**. The rules for Hilt,
Room, kotlinx.serialization, Lottie, WorkManager, CameraX/ML Kit and the Firestore
data classes live in `app/proguard-rules.pro` — read the comments there before
adding one, and prefer a narrowly-scoped rule with a reason over a blanket
`-dontwarn`.

Do not release a build that fails any of the above.

---

## Smoke-test the minified build

R8 failures are usually **runtime** failures. Before publishing, install the
signed APK on a device and check the paths R8 tends to break:

- Cold start → Home renders the companion (Hilt + Room surviving minification).
- Sign in / restore session → cloud data appears (Firestore data classes map their
  fields after obfuscation).
- Open the Agent tab → the bridge payload decodes (kotlinx.serialization) and
  approve/deny round-trips.
- Trigger a reminder → Lottie animation plays and the overlay bubble appears.
- QR scanner opens the camera (CameraX + ML Kit barcode).
- Force a crash on a throwaway build and confirm a record appears (below).

## Crash visibility

There is **no remote crash reporter**. `PixelPalApplication` installs a
process-wide `UncaughtExceptionHandler` that appends fatal crashes to
`filesDir/crashes/crash_log.jsonl` (newest last, capped at 20 entries) and then
delegates to the platform handler. Crashes are also logged through Timber in
debug builds.

```bash
adb shell run-as com.pixelpal.app cat files/crash/crash_log.jsonl
```

Each line is a JSON object: `timestamp`, `versionName`, `versionCode`, `thread`,
`message`, `stackTrace`.

If you later add Crashlytics, this file-backed logger can stay as the
offline/no-SDK fallback — it costs nothing at runtime.

---

## Do not

- **Never** build a release on a laptop and upload it by hand. It will not match the
  key in Secrets, and whichever you publish second breaks updates for everyone.
- **Never** commit `.release-keys/` or paste the password into a tracked file.
- **Never** regenerate the keystore. There is no recovery path.
- **Never** ship a debug-signed APK to users — it cannot be updated later.

---

## Local signed build (testing only, never distributed)

```bash
KEYPASS=$(cat .release-keys/KEYPASS.txt)
./gradlew :app:assembleRelease \
  -PRELEASE_STORE_FILE="$PWD/.release-keys/pixelpal-release.jks" \
  -PRELEASE_STORE_PASSWORD="$KEYPASS" \
  -PRELEASE_KEY_ALIAS=pixelpal \
  -PRELEASE_KEY_PASSWORD="$KEYPASS"
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

`app/build.gradle.kts` registers the release signing config **only when
`RELEASE_STORE_FILE` is set**, so a normal checkout keeps building unsigned.
