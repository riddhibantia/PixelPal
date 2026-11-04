# Deploying PixelPal changes from another machine

You edit/deploy Firebase from a **different machine** (logged in through the
Firebase console GUI). This guide is the exact sequence for that setup.

Two different things get "deployed" and they must not be confused:

| What | Where it lives | How it ships |
|---|---|---|
| **App code** (Android) | this Git repo | you build/install it, or CI does |
| **Firestore security rules** | Firebase project `pixel-pet-a1cc6` | **console GUI** (your other machine) |

The relay only works after the **rules** are updated. The app code alone is not
enough.

---

## Part 1 — Done on this machine (already pushed)

Everything is on `main` at https://github.com/riddhibantia/PixelPal

- `firestore.rules` — adds a `/relays` branch, leaves `/users` untouched
- `firebase.json` — tells the Firebase CLI where the rules live
- `tools/agent-bridge/` — the bridge (Claude Code / Codex / agy / OMP /
  opencode / Hermes)
- `test/rules/` — 19 automated proofs that the rules are safe

Verified here before pushing: 158 Python tests, 89 Kotlin tests, 19 rules tests,
a full end-to-end relay run, and a clean `assembleDebug`.

---

## Part 2 — On your OTHER machine (the one logged into Firebase)

### Step 2.1 — Get the code

```bash
git clone https://github.com/riddhibantia/PixelPal.git
cd PixelPal
git log --oneline -3        # you should see the bridge + rules commits
```

If you already had it cloned:

```bash
git pull origin main
```

### Step 2.2 — Confirm you are pointing at the right Firebase project

You said you deploy through the GUI, so the safest path needs no CLI at all.
But check the project first: it must be **`pixel-pet-a1cc6`** — that is the id
inside `app/google-services.json` and the one the app talks to.

In the console, the project name shown in the top bar should correspond to that
id (Firebase shows the *display* name, so check **Project settings → Project ID**).

### Step 2.3 — Deploy the rules **through the GUI**

1. Open https://console.firebase.google.com/
2. Select the project **pixel-pet-a1cc6**
3. Left sidebar → **Build → Firestore Database**
4. Click the **Rules** tab
5. Open `firestore.rules` from the cloned repo in any editor
6. Select **all** of it, copy, and paste it over the editor contents
   (replace everything — do not append, or the file will have two `service`
   blocks and fail to publish)
7. Click **Publish**

You should see "Rules published successfully" and a timestamp update.

> **If publishing fails**, the message usually names the line. The file was
> validated against the real Firestore emulator before pushing, so a failure
> here almost always means the paste got truncated or merged rather than
> replaced.

### Step 2.4 — Sanity-check the published rules in the console

In the **Rules** editor, confirm you can see **both** of these:

- `match /users/{userId}` — the existing user-data branch (unchanged)
- `match /relays/{relayId}` — the new relay branch

And near the bottom, the default deny:

```
match /{document=**} {
  allow read, write: if false;
}
```

If `/relays` is missing, the paste did not take. Re-paste the whole file.

### Step 2.5 — Verify the relay actually accepts a pairing

You can do this without a phone, from the other machine:

```bash
cd tools/agent-bridge
python3 -m pip install -e .
pixelpal-bridge setup --no-service
```

- If it prints a **9-character code**, the relay registered: **the rules are
  live and correct.** ✅
- If it prints `relay failed: ... PERMISSION_DENIED`, the rules did not publish.
  Go back to 2.3.

To clean up that test registration afterwards:

```bash
pixelpal-bridge uninstall
```

---

## Part 3 — Optional: the CLI path instead of the GUI

Only if you would rather deploy from a terminal. The GUI route above is enough.

```bash
npm install -g firebase-tools
firebase login                                   # sign in as Riddhi
firebase projects:list                           # confirm pixel-pet-a1cc6 appears
firebase deploy --only firestore:rules --project pixel-pet-a1cc6
```

`firebase.json` is already in the repo, so the rules path is picked up
automatically. If `firebase projects:list` does **not** show
`pixel-pet-a1cc6`, that account is not a member of that project — use the GUI
route, or ask an owner to add you.

---

## Part 4 — On the machine where you run your agents (this laptop)

```bash
cd tools/agent-bridge
python3 -m pip install -e .
pixelpal-bridge setup
```

This installs hooks, starts the background service, registers the relay, and
prints the 9-character code. Then in the app: **Agent tab → type the code →
Connect**.

Check it any time with:

```bash
pixelpal-bridge doctor      # names whatever is wrong
pixelpal-bridge status      # current agents
```

---

## Part 5 — If you also build the app on the other machine

The app builds anywhere; only the rules deploy needs Firebase access.

```bash
./gradlew :app:assembleDebug          # build
./gradlew :app:testDebugUnitTest      # 89 unit tests
./gradlew :app:installDebug           # install to a connected phone
```

You need `app/google-services.json` present (it is committed) and
`local.properties` with your SDK path (never committed — recreate it:

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
```
).

---

## Troubleshooting

| What you see | What it means |
|---|---|
| `PERMISSION_DENIED` from `pixelpal-bridge setup` | rules not published yet (2.3), or published to the wrong project |
| App says "No bridge found" after typing the code | bridge registered but rules block reads — same cause |
| App pairs, then agents never appear | bridge service not running: `pixelpal-bridge status` |
| Works on Wi-Fi, not on mobile data | relay rules fine but the bridge daemon stopped; check `systemctl --user status pixelpal-bridge` |
| Console says "Error saving rules" | paste did not fully replace the editor; select-all then paste |
| Two `service cloud.firestore` blocks | you appended instead of replaced — start from a clean editor |

---

## What each change was for (so the diff makes sense in review)

**`firestore.rules`**

- Adds `match /relays/{relayId}` plus `state` and `inbox` subcollections.
- A relay doc is readable only by the two authenticated uids named inside it —
  knowing the document id is not enough.
- The phone may write approval verdicts but **not** agent state.
- The bridge may **not** answer its own approval requests.
- `/users/**` is byte-for-byte unchanged.

**`firebase.json`** — points the CLI at `firestore.rules`.

**`test/rules/`** — proves the above with the emulator (no live project used):
a stranger is denied, a wrong code is rejected, the phone cannot forge state.

---

## Rollback

If anything looks wrong after publishing, paste the previous rules back into the
console's Rules editor and publish. The old version is:

```bash
git show 1da58ed:firestore.rules          # the rules before the relay branch
```

Deleting the `/relays` branch only disables off-Wi-Fi pairing; the direct
same-Wi-Fi connection keeps working, and no user data is affected.
