# PixelPal — Operations Handbook

How pushes, pulls and releases are managed for `riddhibantia/PixelPal`, written
so that **another machine and another agent** (or a human) can reproduce every
step from a clean checkout using the **riddhibantia GitHub identity**.

Two documents live side by side:

| File | Covers |
|---|---|
| **this file** (`docs/OPERATIONS.md`) | Git identities, pushing/pulling, tags, releases, secrets, verification, rollback |
| `RELEASING.md` (repo root) | The release workflow internals, pre-flight R8 checks, keystore history, smoke tests |

---

## 0. The ground truth (verified 2026-11-05)

| Fact | Value |
|---|---|
| Repo | `https://github.com/riddhibantia/PixelPal.git` (public) |
| Default branch | `main`, **no branch protection rules** — one bad push ships |
| Local commit identity | `Riddhi Sudhir Bantia <riddhibantia@gmail.com>` (from **global** git config) |
| Machine `gh` accounts | `shaikhakramshakil` (ACTIVE), `turfzobusiness-sys`, `riddhibantia` (in keyring, inactive) |
| `shaikhakramshakil` on this repo | **read only** (`push: false`) |
| `riddhibantia` on this repo | **admin** (push, secrets, releases, Actions) |
| Git credential helper | `!gh auth git-credential` for `github.com` — git uses **whatever account is active in `gh`** |
| CI release trigger | push a tag `v*` (semver only) |
| versionCode formula | `MAJOR*10000 + MINOR*100 + PATCH` (tag `v1.3.1` → `10301`) |

**The one fact everything below guards against:** the machine's *active* `gh`
account can be the wrong one. Commits made by `Riddhi Sudhir Bantia` but pushed
with `shaikhakramshakil`'s token are rejected — `shaikhakramshakil` cannot push
to this repo.

---

## 1. One-time setup on a new machine

```bash
# 1. Get the code (token-free URL; auth happens through gh at push time)
git clone https://github.com/riddhibantia/PixelPal.git
cd PixelPal

# 2. Authenticate gh — this is per-machine, not copied from the other laptop
gh auth login          # device code flow, browser prompt

# 3. Make sure the riddhibantia token exists in the keyring
#    Either re-login as that account:
gh auth login          # choose GitHub.com → HTTPS → riddhibantia
#    …or (if you hold a classic PAT with 'repo' scope) inject it:
#      gh auth login --with-token < token-file
gh auth status         # both (or at least riddhibantia) must show ✓
gh auth switch --user riddhibantia      # make it the ACTIVE account

# 4. Commit identity — set GLOBAL config to match repo history
git config --global user.name  "Riddhi Sudhir Bantia"
git config --global user.email "riddhibantia@gmail.com"

# 5. JDK 17 + Android SDK are required for builds
#    e.g. sdk install java 17.0.13-tem ; ANDROID_HOME=<sdk dir>
```

The token scopes that matter: `repo` (push, tags, releases), `workflow`
(required to touch `.github/workflows/*` — CI uses this). Without `workflow`,
a push that modifies workflow files is rejected even from `riddhibantia`.

### Keystore: do NOT copy it around

Signing lives in **GitHub Secrets**, releases are built by Actions (see
`RELEASING.md` §"Why GitHub builds it"). A laptop copy of
`.release-keys/pixelpal-release.jks` is a backup only — this machine keeps one,
gitignored. On a new machine you do not need it, and importing it there
**violates the "one signing place" rule** unless you are recovering from an
emergency (see §7).

```bash
# Verify the local backup matches the CI key (optional, read-only)
keytool -list -v -keystore .release-keys/pixelpal-release.jks -alias pixelpal \
  | grep SHA256
# Expected: 9B:6D:2E:84:EC:70:BB:D6:62:5D:42:33:54:06:51:24:62:5E:F5:36:EE:22:2E:69:B3:BF:84:43:96:55:E7:5F
```

### Verify all four signing secrets exist

```bash
gh secret list -R riddhibantia/PixelPal
# must show: RELEASE_STORE_BASE64, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
# optional:  FIREBASE_TOKEN (only needed for Firestore rules deploys)
```
If the list is empty or incomplete, run `gh auth switch --user riddhibantia`
first — `gh secret` from a read-only account returns `403`, which **silently
misleads you into thinking the secrets are missing**.

---

## 2. Day-to-day: pull / commit / push

```bash
gh auth switch --user riddhibantia        # once per session, BEFORE pushing

git pull --ff-only origin main            # always start from what shipped
# …work…
git add <files>                           # stage consciously
./gradlew :app:testDebugUnitTest          # 83 unit tests must pass
git commit -m "feat(area): summary"       # conventional-commit style (see log)
git push origin main                      # main has NO protection: review first
```

Notes that have bitten real sessions:

- **Verify identity before the push, not after:** `gh auth status` (active row)
  and `git log -1 --format='%an <%ae>'`. A mixed state produces commits authored
  by riddhibantia carrying a token from an account that cannot push.
- **Alternative without switching accounts**, for scripted/agent use — borrow
  riddhibantia's token for one command only:
  ```bash
  git -c credential.helper='!f() { echo username=riddhibantia;
        echo password=$(gh auth token --user riddhibantia); }; f' push origin main
  ```
  This does **not** change the global active account. Never put the token in the
  remote URL: it lands in `.git/config` and shell history.
- `git pull --ff-only` keeps history linear; if it refuses, someone force-pushed
  or you have local commits — resolve deliberately, never with `--force`.
- **Agents must not push without explicit user instruction.** The repo has no
  branch protection; a push to `main` is visible immediately and CI runs on it.

---

## 3. Releases — the whole lifecycle

### 3.1 Cut a release (3 commands, any machine)

```bash
git pull --ff-only origin main
git tag v1.3.2                       # semver only: vMAJOR.MINOR.PATCH
git push origin v1.3.2
```

**That is the release process.** Everything else is Actions:

1. `release.yml` triggers on the tag (also supports `workflow_dispatch` to
   re-run for the same tag).
2. Resolves `versionName=1.3.2`, `versionCode=10302` from the tag; refuses
   non-semver tags.
3. Fails fast if any of the four signing secrets is missing.
4. Decodes `RELEASE_STORE_BASE64` → keystore in `$RUNNER_TEMP`.
5. `./gradlew :app:assembleRelease` (R8 + resource shrinking; version stamps
   passed through `-P`). The app's Gradle file **refuses to build a release**
   without explicit version + signing, so CI always passes both.
6. `apksigner verify --print-certs` — an unsigned APK can never be published.
7. Stages `PixelPal-<ver>.apk`, `pixelpal-bridge-<ver>.tar.gz`, `install.sh`,
   `SHA256SUMS`; **everything goes into a DRAFT release** with generated notes.
8. A human publishes the draft (next section).

### 3.2 Publish: the draft quirk (do not skip)

Draft releases are **invisible to accounts without write access**. On this
machine the active `gh` is often `shaikhakramshakil` (read-only), so:

```bash
gh auth token --user riddhibantia > /tmp/rt || true   # never paste tokens into history
RT=$(gh auth token --user riddhibantia)
GH_TOKEN=$RT gh release list -R riddhibantia/PixelPal   # drafts visible now

# Inspect, then publish with write access:
GH_TOKEN=$RT gh release view v1.3.2 -R riddhibantia/PixelPal
GH_TOKEN=$RT gh release edit v1.3.2 -R riddhibantia/PixelPal --draft=false

# Point the "Latest" marker at the right release (GitHub only auto-picks it
# when published chronologically last; a late-published older release does
# NOT become Latest — this is how "latest" currently points at v1.3.0)
GH_TOKEN=$RT gh release edit v1.3.1 -R riddhibantia/PixelPal --latest 2>/dev/null
GH_TOKEN=$RT gh release edit v1.3.2 -R riddhibantia/PixelPal --latest
```

(Passing tokens via env keeps them out of the URL and `~/.git/config`.)

### 3.3 What users see

- `install.sh` on any computer resolves the **latest published** release and
  its `SHA256SUMS` — nothing is consumable until the draft is published and
  `--latest` is set (a draft **404s**, as does the app's one-liner on first
  install after a new tag).
- The Android app's Agent tab shows the same URL, which the app derives from
  `BuildConfig.REPO_SLUG` (`PIXELPAL_REPO_SLUG`, default `riddhibantia/PixelPal`).
  Renaming/moving the repo therefore requires no app code change — pass
  `-PPIXELPAL_REPO_SLUG=<new slug>` on release builds, or change the default
  once in `app/build.gradle.kts`.

### 3.4 CI on `main` (continuous, not release)

| Workflow file | What it does |
|---|---|
| `.github/workflows/ci.yml` | `assembleDebug`, Room-schema drift check, unit tests, lint; emulator instrumented tests; (optional) Firestore rules deploy if `FIREBASE_TOKEN` secret exists |
| `.github/workflows/android.yml` | debug build + unit tests uploaded as artifacts; **unsigned R8 release pre-check** (`-PPIXELPAL_RELEASE_PRECHECK=true`) — this is the job that catches ProGuard/R8 mistakes; mapping.txt uploaded for crash de-obfuscation |
| `.github/workflows/release.yml` | the tag → signed release pipeline above |

---

## 4. Pre-tag checklist (run these on the machine doing the tag)

```bash
./gradlew :app:testDebugUnitTest                  # 83 unit tests, 0 failures
./gradlew :app:assembleRelease \                  # R8 pass — MUST run before tag
  -PPIXELPAL_RELEASE_PRECHECK=true                # unsigned is fine here
```

Failures in either means: do not tag; fix (`main` is un-protected; a red tag
run wastes a release).

Optional deeper check (this machine only, needs `.release-keys/`):
```
KEYPASS=$(cat .release-keys/KEYPASS.txt)
./gradlew :app:assembleRelease \
  -PPIXELPAL_VERSION_CODE=103xx -PPIXELPAL_VERSION_NAME=1.3.x \
  -PRELEASE_STORE_FILE="$PWD/.release-keys/pixelpal-release.jks" \
  -PRELEASE_STORE_PASSWORD="$KEYPASS" -PRELEASE_KEY_ALIAS=pixelpal \
  -PRELEASE_KEY_PASSWORD="$KEYPASS"
# then: apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```
This local signed APK is **never distributed** — shipping it breaks the
signing-key invariant (§7).

---

## 5. Verification baked into the pipeline

| Layer | Check | Fails how |
|---|---|---|
| Gradle | unsigned release build | throws (no silent versions/signing) |
| CI | `apksigner verify --print-certs` | job fails, nothing uploaded |
| `install.sh` | SHA-256 of the tarball vs `SHA256SUMS` | abort, exits non-zero |
| Users | `sha256sum -c SHA256SUMS` (documented in the release body) | — |
| App | embedding a Gemini key in a release APK requires explicit opt-in | key ships empty by default |

---

## 6. Branch/tag conventions

- `main` only. `develop` exists in CI triggers but is not used.
- Commit messages: conventional-commit prefixes (`feat:`, `fix(bridge):`,
  `docs:`, `chore(app):`) — matching the log.
- Tags: `vX.Y.Z` pointing at a commit on `main`. **Never re-tag the same
  version** (see §7).

---

## 7. Recovery playbook

| Situation | Action |
|---|---|
| Tag pushed too early / wrong commit | Do **not** delete and re-push the same version (versionCode collision = users can't install over). Delete the draft, bump to a new patch (`v1.3.3`), fix, tag again. |
| Published release is broken | Keep it (users may have installed it). Fix forward: new tag, new release; users update by installing the newer signed APK. |
| Keystore lost | There is no recovery. Every install requires uninstall+data loss. **That is why the only copy location is documented in RELEASING.md §1** — verify it yearly. |
| A secret leaks | `gh secret set RELEASE_STORE_PASSWORD --body '<new>'` … but note key rotation = new app identity for users. Prefer deleting a leak, not rotating. |
| Push rejected `403` | active `gh` account is not riddhibantia → §2. |
| Push rejected touching `.github/workflows` | token lacks `workflow` scope → re-auth. |
| Draft release "vanishes" | you are looking with a read-only account's token → §3.2. |
| Worst case: malformed history on `main` | `git push --force` from an admin token is possible (no protection). Create an issue first, and push the corrected history only after confirmation — force-pushes are auditable. |

---

## 8. Rules for agents working from any machine

1. **Identities first.** Before anything that writes (push, tag, release edit,
   secret set), run `gh auth status` and confirm the active account is
   `riddhibantia`; otherwise use the per-command `GH_TOKEN` form.
2. **Read the tree status** (`git status`, `--left-right --count`) before
   pushing; never push unknown/uncommitted work wholesale.
3. Never commit: `local.properties`, `.release-keys/`, `.KEYPASS.txt`,
   `*.jks`, anything matching `.gitignore`'s release-keys block.
4. Never paste a token into a command line, remote URL, or a doc. Use
   `GITHUB_TOKEN=$(gh auth token --user …)` env form.
5. For releases, always tag on the machine (or from Actions UI) and publish via
   §3.2 — never `git push --tags *` blindly; tag semver names only.
6. Everything above assumes the workflow files are authoritative; if `RELEASING.md`
   and `docs/OPERATIONS.md` disagree with what a `yml` actually does, **the yaml
   wins** — fix the docs in the same commit.
7. Both docs' "verified" date and `MACHINE = <hostname>` marker at the bottom
   must be updated when ground truth changes.

---

## 9. Machine verification snapshot

| Check | Command | Expected (2026-11-05, this machine) |
|---|---|---|
| Active `gh` account | `gh auth status` | shows all three; the ACTIVE row is whatever `switch` last set |
| riddhibantia push right | `GH_TOKEN=$(gh auth token --user riddhibantia) gh api repos/riddhibantia/PixelPal --jq .permissions` | `{"admin":true,...,"push":true}` |
| read-only check | `GH_TOKEN=$(gh auth token --user shaikhakramshakil) gh api repos/riddhibantia/PixelPal --jq .permissions` | `{"push":false,...}` |
| Branch protection | `gh api repos/riddhibantia/PixelPal/branches/main/protection` | 404 (not protected) |
| Sync state | `git fetch && git rev-list --left-right --count origin/main...main` | `0 0` |
| Tags | `git tag --sort=-v:refname \| head` | v1.3.1, v1.3.0, v1.1.0 |
| Secrets | `gh secret list` | 4 signing + optional FIREBASE_TOKEN |
| Local keystore SHA-256 | `keytool … -alias pixelpal` | `9B:6D:2E:84:…` matches |

*MACHINE = akram@aurangabad-laptop, verified 2026-11-05. Update this block
whenever any ground truth in §0 changes.*
