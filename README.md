# 🐾 PixelPal

**A pixel-art screen companion that watches your AI coding agents for you.**

<p>
  <a href="https://github.com/riddhibantia/PixelPal/releases">📲 Download APK</a> •
</p>

<p>
  <img src="docs/screenshots/home.png" width="250" alt="Home" />
  <img src="docs/screenshots/agent.png" width="250" alt="AI Agent" />
</p>

PixelPal is an Android app that lives as a small floating pet on top of your phone screen. Underneath the cute pixel-art exterior, its real job is to be a **live window into your AI coding agents** — Claude Code, agy, Codex, OMP, opencode, Hermes, Gemini, or anything that exposes a status endpoint — so you can glance at your pet and instantly know what your agent is doing, without opening a terminal, a laptop, or a dashboard.

Tasks, reminders, a bond/personality system, and character customization round out the companion experience, but the agent-monitoring layer is the core idea: **turn "is my AI agent still working?" into a one-glance question.**

---

## ✨ Why PixelPal

Anyone running long AI coding sessions knows the pattern: kick off a task, then repeatedly tab back in just to check "is it done yet?" PixelPal moves that check off your laptop and onto your phone — your companion reacts, updates, and notifies you as the agent's state changes, and when an agent needs permission for a tool, **you approve it from your phone**.

---

## 🤖 Core Feature: the Universal Agent Bridge

One integration, every terminal coding agent. The pet reacts to whichever agent is working — and can **approve or deny tool permissions from your phone**.

```
Claude Code ─┐
agy ─────────┤
Codex ───────┼─ hooks ─→ normalize ─→ POST /events ─┐
OMP ─────────┤                    (AgentEvent)       │
opencode ────┤                                        ├─→ reducer ─→ /agents → 📱 pet
Hermes ──────┘                    (DB poll) ─────────┘
```

### Install (one line, no repo, no pipx)

```bash
curl -fsSL https://raw.githubusercontent.com/riddhibantia/PixelPal/main/install.sh | bash
pixelpal-bridge setup
```

`setup` detects agents, installs their hooks, starts the bridge as a background service, and prints a pairing code for the app's **Agent** tab. Scan its optional terminal QR or paste the full `pixelpal://pair?...` code; a plain URL is not enough because pairing also needs the key. Same Wi-Fi, that's the whole story. Away from home? Install [Tailscale](https://tailscale.com) on both devices and re-run `setup` — it prints your tailnet address instead.

### Supported agents

| Agent | Mechanism | Latency | Approve from phone |
|---|---|---|---|
| **Claude Code** | `PreToolUse`/`PostToolUse`/`PermissionRequest` hooks | instant | ✅ |
| **agy** | same hook contract as Claude Code | instant | ✅ |
| **Codex** | same hook contract | instant | ✅ |
| **OMP** | native TypeScript extension (`pi.on(...)`) | instant | ⛔ status only |
| **opencode** | `GET /session` from `opencode serve` | ~1s | ⛔ status only |
| **Hermes** | read-only poll of `~/.hermes/state.db` | ~1s | ⛔ status only |

### The approval round-trip

When Claude Code (or agy/Codex) wants to run a tool it needs permission for, the hook long-polls the bridge, the pet shows an Approve/Deny card on the phone (with a notification), and your tap flows back to the agent in one round-trip:

```
Claude Code                    hook                     server            phone
    ├─ PermissionRequest ──→ POST /events ──→ push ──→ Approve / Deny sheet
    ├─ POST /approvals/wait  (long-poll)  ←──────────── tap
    ←─ {"decision":"allow"}  → the tool runs
```

**A dead bridge never breaks an agent** — hooks give up after 1 s, always exit 0, and can be disabled with `PIXELPAL_HOOK_DISABLED=1`. **Code never leaves your laptop** — adapters send tool names and short human summaries (basenames, first lines), never file contents or diffs. See [`tools/agent-bridge/README.md`](tools/agent-bridge/README.md) for the full story.

### Manual connections (the Agent workspace)

Beyond the bridge, the app's AI Agent workspace connects:

- **Gemini** — direct Google Generative AI SDK integration with streaming replies and your companion's personality in the prompt. Status checks are free/local; a real round-trip only happens when you explicitly verify.
- **WebSocket** — for agents that push live events (`ws://`).
- **Generic HTTP** — polls any endpoint returning `{status, currentTask?, progress?, message?, pendingApproval?}`. Private-LAN `http://` (RFC1918), loopback, and Tailscale (`100.64.0.0/10`, `*.ts.net`) endpoints are allowed by design; everything else must be HTTPS.

Polling runs through WorkManager (15/30/60/120 min) and an in-process poller (20 s idle, 4 s while an approval waits). **QR pairing** (CameraX + ML Kit) imports an endpoint URL from your laptop.

---

## 🐱 The Companion Experience

- **Floating overlay pet** on top of any app — draggable, idle/blink/sleep/walk/jump/excited Lottie states, speech bubbles with live agent status, and a dynamic island for reminders. Long-press to feed; the bubble tucks away while PixelPal is foregrounded. Keyboard-aware positioning lifts the pet above your input method.
- **Centered Hero Companion & Sleek Dashboard** — a prominent, centered glowing mascot hero with balanced, uniform cards, immediate interactive companion chat, and clean "Today" task visibility without scrolling.
- **One active companion** by design — tasks, reminders, agent connection, personality, and bond are all *features of your one pet*, not separate profiles.
- **Tasks & Reminders** — tasks with subtasks, descriptions, **due dates** (notifications + relative labels), high-precision 48dp touch targets for instant completion, and swipe-delete with Undo; reminders with one-off/daily/weekly/monthly recurrence (month-end clamping and DST handled), categories with icons, custom sounds or fully silent, and snooze. Full-screen alarms with Complete / Snooze actions.
- **Bond & Personality** — a friendship level (cap 100) that grows through real interaction (task +2, reminder +3, first 3 taps/day +1), calendar-day decay, weekly consistency streak bar charts aligned to real calendar activity, and a personality that adapts to how you use the app.
- **Activity Center** — Instagram-style chronological notification grouping ("Today", "Yesterday", "This week", "This month", and "Earlier") for tracking companion interactions, agent status transitions, and bond level-ups.
- **7 species** (Cat, Dog, Bunny, Panda, Whale, Axolotl, Llama), each with a **full 12-state animated Lottie set**, plus themes (dark/light/system with dynamic color).
- **Cloud sync** — optional Firebase Auth (email/password, Google, guest) with two-way Firestore sync (companion, bond, tasks, reminders, subtasks), tombstoned deletes, and last-write-wins conflict resolution. Signed out, everything stays local.
- **Offline-first** — Room is the source of truth; Firestore's cache is capped at 100 MB.
- **Home-screen widgets** — Home (companion/streak/next reminder/agent line) and Tasks (6 rows + inline complete) widgets, plus a desktop mini pet (`agent-endpoint/mini_pet.py`).

The UI speaks one quiet design language: calm surfaces, hairline borders, a single violet gradient on the active thing — and the pet as the one bright element on every screen.

---

## 🛠️ Tech Stack

- **Language:** Kotlin 2.0, Jetpack Compose, Material 3
- **Architecture:** Clean architecture (presentation → domain → data), single source of truth via `ActiveCompanionManager`
- **DI:** Hilt (including `hilt-work` for workers)
- **Local persistence:** Room **v16** (16 hand-written migrations) + DataStore for preferences
- **Background work:** WorkManager (agent polling, bond decay, personality, cloud sync, task due dates, boot catch-up)
- **Networking / streaming:** OkHttp WebSocket, Google Generative AI SDK (Gemini)
- **Cloud:** Firebase Auth + Cloud Firestore (app data sync only — there is no relay server between the app and your laptop)
- **Bridge:** pure Python 3.9+ stdlib, no dependencies
- **Animation:** Lottie Compose (7 species × 12 states as raw JSON assets)
- **Build:** Gradle Kotlin DSL, AGP 8.5.0, KSP (Room + Hilt), R8 minify + resource shrinking in release

---

## 🏗️ Architecture Overview

```
presentation/   → Compose UI: home, connected-agents hub (Agent tab), workspace,
                  tasks, reminders, activity, settings, onboarding, auth
domain/         → engines & use cases: ActiveCompanionManager, BondEngine,
                  EmotionEngine, AnimationEngine, RecurrenceMath,
                  CompanionEngine, CompanionReactionProvider
data/           → Room v16 (companion, bond, personality, tasks, subtasks,
                  reminders, agent connection, agent sessions, activity events),
                  DataStore, Firebase sync, agent connectors
                  (Gemini / WebSocket / Generic HTTP) + bridge transport
overlay/        → floating pet window management, speech bubbles, dynamic
                  island, keyboard dodge
worker/         → periodic + one-shot background jobs
widget/         → home-screen widgets (Home + Tasks)
tools/agent-bridge/  → the universal bridge: adapters, pure reducer, HTTP
                  server, daemon service, installer (Python, stdlib-only)
```

The bridge's `agent_sessions` mirror in Room is the single source every agent surface reads (Home card, Today chip, widget, Agent tab) — the legacy `agent_connection` row is configuration only.

---

## 📲 Install on your phone

Grab **`PixelPal-<version>.apk`** from the [latest GitHub Release](https://github.com/riddhibantia/PixelPal/releases), open it, allow installing from that source once, done. Every release APK is signed with the project release key and its SHA-256 is published next to it (`SHA256SUMS`).

Building from source instead:

```bash
git clone https://github.com/riddhibantia/PixelPal.git
cd PixelPal
./gradlew :app:installDebug
```

Add an optional `GEMINI_API_KEY` to `local.properties` — without it the app falls back to bundled dialogue packs. The `:app` module expects `app/google-services.json` for cloud sync; the repo ships a demo project's file and cloud sync stays dormant while signed out, so the app is fully usable without your own Firebase project.

On first launch the app asks for the "Display over other apps" permission — required for the floating pet. The optional accessibility service enables keyboard-aware positioning.

---

## 📱 Usage

1. **Onboard** your companion — pick a species, name it, grant the overlay permission.
2. **On your laptop**, run the two-line bridge install above → `pixelpal-bridge setup`.
3. **Scan the pairing QR** from the Agent tab, or paste the printed pairing code.
4. Go about your day — the pet animates, speaks, and notifies as your agents work, and asks you to approve permission requests right from the notification or the Agent tab.
5. Add **tasks and reminders** — completing them grows the bond and streak and gradually shifts your pet's personality.

---

## 🔒 Privacy

- Tasks, reminders, companion, and bond live in a **local Room database**. If you sign in, they additionally sync to **your own Firebase account's** Firestore under your uid — nothing is sent to a server we operate.
- **Agent monitoring is laptop→phone directly** (LAN HTTP or Tailscale/WireGuard). There is no intermediary server and no third-party relay; agent metadata (tool names, short summaries) never leaves your machines. File contents and diffs are never sent.
- The bridge's pairing key is stored at `~/.config/pixelpal/bridge.json` (mode `0600`) and sent as `X-PixelPal-Key`; loopback calls skip it.
- Your Gemini API key is only used to talk to Google's Generative AI API on your behalf.
- No analytics, no telemetry, no ads. **Android Auto Backup carries the database only, never the preferences file** (it holds the bridge key and the Gemini key override — excluded from backup and device transfer).

---

## ⚠️ Known limitations

Tracked honestly; the file-and-line audit lives in [MEMORY.md](MEMORY.md):

- **Personality drift is not yet real** — `PersonalityWorker` feeds itself placeholder stats.
- **Task photo URIs** are not persisted with `takePersistableUriPermission`, so an attached photo can fail to load after a restart.
- **Overlay edge cases** — a fast show→hide can race the window token; the dynamic island's x-position math is fragile.
- **QR scan frame** is functional but its alignment overlay isn't drawn; POST_NOTIFICATIONS isn't re-requested if denied once.
- **Status-only agents** — OMP/opencode/Hermes can't be approved from the phone (their surfaces don't expose permission events); approval is Claude Code/agy/Codex only.
- **Off-Wi-Fi needs Tailscale** — there is no built-in relay; both devices must share a network or a tailnet.

---

## 🗺️ Roadmap

- [ ] LAN auto-discovery (mDNS) so even pasting the URL disappears
- [ ] In-app update check against GitHub Releases
- [ ] Richer agent-progress visualization in the overlay itself
- [ ] Wear OS companion view
- [ ] Full localization (UI copy is hardcoded English; string-resource migration started)

---

## 🚀 Releases

**GitHub Actions is the only release path** — push a tag, CI builds and signs the APK, verifies the signature, bundles the bridge tarball + `SHA256SUMS`, and opens a **draft release**:

```bash
git tag v1.4.1 && git push origin v1.4.1
```

Versioning is tag-driven (`vMAJOR.MINOR.PATCH` → versionCode `MAJOR*10000+MINOR*100+PATCH`). The keystore lives only in GitHub Secrets (plus an offline backup) — **losing it makes installed apps un-updatable**. The full checklist — the four secrets, `base64` encoding, verification steps — is in [RELEASING.md](RELEASING.md).

Local signed build (same properties CI uses):

```bash
./gradlew :app:assembleRelease \
  -PRELEASE_STORE_FILE=$HOME/keystores/pixelpal.jks \
  -PRELEASE_STORE_PASSWORD=… -PRELEASE_KEY_ALIAS=pixelpal -PRELEASE_KEY_PASSWORD=… \
  -PPIXELPAL_VERSION_NAME=1.3.0 -PPIXELPAL_VERSION_CODE=10300
```

---

## 🧪 Tests

```bash
./gradlew :app:testDebugUnitTest          # 82 JVM unit tests (engines, models, sync policy)
./gradlew :app:connectedDebugAndroidTest  # Room migration/DAO + navigation smoke (needs a device)
cd tools/agent-bridge && python3 -m pytest pixelpal_bridge/tests/ -q   # 118 bridge tests
```

---

## 🤝 Contributing

Early-stage solo project — issues and suggestions are welcome; please open an issue to discuss before submitting a PR.

---

## 📄 License

MIT — see [LICENSE](LICENSE).

---

## 📬 Contact

**Riddhi Bantia**
[email: riddhibantia@gmail.com
LinkedIn : https://www.linkedin.com/in/riddhi-bantia ]
